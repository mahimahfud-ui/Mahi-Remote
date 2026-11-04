import { WebSocket, WebSocketServer } from 'ws';
import { DeviceService } from '../device-management/DeviceService';
import { AuthService } from '../authentication/AuthService';
import { UiReleaseService } from '../release-management/UiReleaseService';

type ClientRole = 'CONTROLLER' | 'RECEIVER' | 'WINDOWS';

interface ClientConnection {
  ws: WebSocket;
  id: string;
  type: ClientRole;
  fingerprint: string;
}

interface PairRecord {
  controllerId: string;
  receiverId: string;
  createdAt: number;
}

const PAIRING_CODE_TTL_MS = 5 * 60 * 1000;
const PAIRING_RATE_LIMIT = 10;
const PAIRING_RATE_WINDOW_MS = 5 * 60 * 1000;

export class SignalingHub {
  private clients = new Map<string, ClientConnection>();
  private pendingPairs = new Map<string, PairRecord>();
  private activePairs = new Map<string, PairRecord>();

  constructor(private wss: WebSocketServer) {
    this.init();
    UiReleaseService.setUpdateBroadcaster((release) => this.broadcastUiUpdate(release));
  }

  private pairKey(controllerId: string, receiverId: string): string {
    return [controllerId, receiverId].sort().join('::');
  }

  private send(client: ClientConnection | undefined, payload: Record<string, unknown>): void {
    if (client?.ws.readyState === WebSocket.OPEN) {
      client.ws.send(JSON.stringify(payload));
    }
  }

  private getActivePair(senderId: string, requestedPeerId?: string): PairRecord | undefined {
    for (const pair of this.activePairs.values()) {
      const peerId = pair.controllerId === senderId ? pair.receiverId :
        pair.receiverId === senderId ? pair.controllerId : undefined;
      if (peerId && (!requestedPeerId || requestedPeerId === peerId)) return pair;
    }
    return undefined;
  }

  private removePair(pair: PairRecord, reason: string): void {
    const key = this.pairKey(pair.controllerId, pair.receiverId);
    this.pendingPairs.delete(key);
    this.activePairs.delete(key);
    const peerId = pair.controllerId;
    const otherId = pair.receiverId;
    this.send(this.clients.get(peerId), { type: 'SESSION_ENDED', peerId: otherId, reason, timestamp: Date.now() });
    this.send(this.clients.get(otherId), { type: 'SESSION_ENDED', peerId, reason, timestamp: Date.now() });
  }

  public broadcastUiUpdate(release: unknown): void {
    const payload = JSON.stringify({ type: 'UI_UPDATE_AVAILABLE', release, timestamp: Date.now() });
    for (const client of this.clients.values()) {
      if (client.ws.readyState === WebSocket.OPEN) client.ws.send(payload);
    }
  }

  public broadcastAppUpdate(otaRelease: unknown): void {
    const payload = JSON.stringify({ type: 'APP_UPDATE_AVAILABLE', release: otaRelease, timestamp: Date.now() });
    for (const client of this.clients.values()) {
      if (client.ws.readyState === WebSocket.OPEN) client.ws.send(payload);
    }
  }

  private init(): void {
    this.wss.on('connection', (ws: WebSocket, req) => {
      const deviceId = req.headers['x-device-id'] as string | undefined;
      const operatorFingerprint = req.headers['x-operator-fingerprint'] as string | undefined;
      const requestedRole = String(req.headers['x-client-role'] || '').toUpperCase();

      let clientId: string;
      let clientType: ClientRole;
      if (deviceId) {
        if (!/^[A-Za-z0-9._:-]{3,128}$/.test(deviceId)) {
          ws.close(1008, 'Invalid device identity');
          return;
        }
        clientId = deviceId;
        clientType = requestedRole === 'CONTROLLER' ? 'CONTROLLER' : 'RECEIVER';
        void DeviceService.registerDevice(
          deviceId,
          String(req.headers['x-device-name'] || 'Android Phone').slice(0, 100),
          String(req.headers['x-device-fingerprint'] || 'UNKNOWN').slice(0, 128)
        );
      } else if (operatorFingerprint) {
        clientId = `operator_${operatorFingerprint.slice(0, 48)}`;
        clientType = 'WINDOWS';
      } else {
        // Browser WebSocket APIs cannot set arbitrary headers; give each web tab a
        // per-connection controller identity. Pairing code approval binds it to a receiver.
        clientId = `browser_${Date.now()}_${Math.random().toString(36).slice(2, 10)}`;
        clientType = 'CONTROLLER';
      }

      const previous = this.clients.get(clientId);
      if (previous?.ws.readyState === WebSocket.OPEN) {
        this.send(previous, { type: 'CONNECTION_REPLACED', reason: 'A newer connection replaced this client' });
        previous.ws.close(4001, 'Connection replaced');
      }

      const client: ClientConnection = {
        ws,
        id: clientId,
        type: clientType,
        fingerprint: String(req.headers['x-device-fingerprint'] || operatorFingerprint || '').slice(0, 128),
      };
      this.clients.set(clientId, client);
      this.send(client, { type: 'SERVER_CONNECTED', clientId, role: clientType, timestamp: Date.now() });

      ws.on('message', (data) => {
        try {
          const message = JSON.parse(data.toString());
          if (!message || typeof message.type !== 'string') {
            this.send(client, { type: 'PROTOCOL_ERROR', error: 'Message type is required' });
            return;
          }
          this.handleMessage(client, message);
        } catch {
          this.send(client, { type: 'PROTOCOL_ERROR', error: 'Invalid JSON message' });
        }
      });

      ws.on('close', () => {
        if (this.clients.get(clientId)?.ws !== ws) return;
        this.clients.delete(clientId);
        if (client.type === 'RECEIVER') DeviceService.markDeviceOffline(clientId);

        for (const pair of [...this.pendingPairs.values()]) {
          if (pair.controllerId === clientId || pair.receiverId === clientId) {
            const other = pair.controllerId === clientId ? pair.receiverId : pair.controllerId;
            this.pendingPairs.delete(this.pairKey(pair.controllerId, pair.receiverId));
            this.send(this.clients.get(other), { type: 'PAIR_ERROR', error: 'The other device disconnected during pairing' });
          }
        }
        for (const pair of [...this.activePairs.values()]) {
          if (pair.controllerId === clientId || pair.receiverId === clientId) {
            this.removePair(pair, 'A paired device disconnected');
          }
        }
      });
    });
  }

  private handlePairByCode(sender: ClientConnection, message: any): void {
    if (sender.type !== 'CONTROLLER' && sender.type !== 'WINDOWS') {
      this.send(sender, { type: 'PAIR_ERROR', error: 'Only a controller can submit a pairing code' });
      return;
    }
    if (!AuthService.checkRateLimit(`pair:${sender.id}`, PAIRING_RATE_LIMIT, PAIRING_RATE_WINDOW_MS)) {
      this.send(sender, { type: 'PAIR_ERROR', error: 'Too many pairing attempts. Try again in a few minutes.' });
      return;
    }

    const payload = message.payload && typeof message.payload === 'object' ? message.payload : {};
    const code = String(message.code || message.pairingCode || payload.code || '').trim();
    if (!/^\\d{3}-?\\d{3}$/.test(code)) {
      this.send(sender, { type: 'PAIR_ERROR', error: 'Enter a valid 6-digit pairing code' });
      return;
    }
    const normalizedCode = code.includes('-') ? code : `${code.slice(0, 3)}-${code.slice(3)}`;
    const receiverId = DeviceService.peekPairingCode(normalizedCode);
    const receiver = receiverId ? this.clients.get(receiverId) : undefined;
    if (!receiverId || !receiver || receiver.type !== 'RECEIVER' || receiver.ws.readyState !== WebSocket.OPEN) {
      this.send(sender, { type: 'PAIR_ERROR', error: 'Invalid, expired, or offline pairing code' });
      return;
    }
    if (this.getActivePair(sender.id) || this.getActivePair(receiverId)) {
      this.send(sender, { type: 'PAIR_ERROR', error: 'One of these devices is already in an active session' });
      return;
    }

    const key = this.pairKey(sender.id, receiverId);
    if (this.pendingPairs.has(key)) {
      this.send(sender, { type: 'PAIR_ERROR', error: 'A pairing request is already pending' });
      return;
    }
    if (DeviceService.validateAndConsumePairingCode(normalizedCode) !== receiverId) {
      this.send(sender, { type: 'PAIR_ERROR', error: 'Pairing code was already used or expired' });
      return;
    }

    const pair: PairRecord = { controllerId: sender.id, receiverId, createdAt: Date.now() };
    this.pendingPairs.set(key, pair);
    this.send(receiver, {
      type: 'PAIR_REQUEST',
      sender: sender.id,
      senderId: sender.id,
      controllerId: sender.id,
      operatorName: String(message.operatorName || message.controllerName || payload.operatorName || 'Mahi Controller').slice(0, 100),
      operatorLocation: String(message.operatorLocation || payload.operatorLocation || 'Remote Controller').slice(0, 100),
      fingerprint: sender.fingerprint || String(message.fingerprint || payload.fingerprint || ''),
      requestedScopes: Array.isArray(message.requestedScopes) ? message.requestedScopes :
        Array.isArray(payload.requestedScopes) ? payload.requestedScopes :
          ['Screen Mirroring', 'Remote Interaction'],
      timestamp: Date.now(),
    });
    this.send(sender, { type: 'PAIR_PENDING', receiverId, expiresAt: Date.now() + PAIRING_CODE_TTL_MS });
  }

  private handleDirectPairRequest(sender: ClientConnection, message: any): void {
    if (sender.type !== 'CONTROLLER' && sender.type !== 'WINDOWS') return;
    const payload = message.payload && typeof message.payload === 'object' ? message.payload : {};
    const receiverId = String(message.recipient || message.targetDeviceId || payload.targetDeviceId || '');
    const receiver = this.clients.get(receiverId);
    if (!receiver || receiver.type !== 'RECEIVER' || receiver.ws.readyState !== WebSocket.OPEN) {
      this.send(sender, { type: 'PAIR_ERROR', error: 'Target Android receiver is offline' });
      return;
    }
    if (this.getActivePair(sender.id) || this.getActivePair(receiverId)) {
      this.send(sender, { type: 'PAIR_ERROR', error: 'One of these devices is already in an active session' });
      return;
    }

    const key = this.pairKey(sender.id, receiverId);
    if (this.pendingPairs.has(key)) {
      this.send(sender, { type: 'PAIR_ERROR', error: 'A pairing request is already pending' });
      return;
    }
    const pair: PairRecord = { controllerId: sender.id, receiverId, createdAt: Date.now() };
    this.pendingPairs.set(key, pair);
    this.send(receiver, {
      type: 'PAIR_REQUEST',
      sender: sender.id,
      senderId: sender.id,
      controllerId: sender.id,
      operatorName: String(message.operatorName || payload.operatorName || 'Mahi Controller').slice(0, 100),
      operatorLocation: String(message.operatorLocation || payload.operatorLocation || 'Remote Controller').slice(0, 100),
      fingerprint: sender.fingerprint || String(message.operatorFingerprint || payload.fingerprint || ''),
      requestedScopes: Array.isArray(message.requestedScopes) ? message.requestedScopes :
        Array.isArray(payload.requestedScopes) ? payload.requestedScopes :
          ['Screen Mirroring', 'Remote Interaction'],
      timestamp: Date.now(),
    });
  }

  private handleMessage(sender: ClientConnection, message: any): void {
    const type = message.type as string;
    const payload = message.payload && typeof message.payload === 'object' ? message.payload : {};

    if (type === 'PING') {
      this.send(sender, { type: 'PONG', timestamp: Date.now() });
      return;
    }
    if (type === 'REGISTER') {
      this.send(sender, { type: 'REGISTER_ACK', status: 'OK', deviceId: sender.id });
      return;
    }
    if (type === 'REGISTER_PAIRING_CODE') {
      if (sender.type !== 'RECEIVER') {
        this.send(sender, { type: 'PAIR_ERROR', error: 'Only a receiver can register a pairing code' });
        return;
      }
      const code = String(message.code || message.pairingCode || payload.code || '');
      const expiresAt = Number(message.expiresAt || payload.expiresAt || 0);
      if (!/^\\d{3}-\\d{3}$/.test(code) || !Number.isFinite(expiresAt) ||
          expiresAt <= Date.now() || expiresAt > Date.now() + PAIRING_CODE_TTL_MS + 5000) {
        this.send(sender, { type: 'PAIR_ERROR', error: 'Invalid pairing code or expiry' });
        return;
      }
      void DeviceService.storePairingCode(sender.id, code, expiresAt);
      this.send(sender, { type: 'CODE_REGISTERED_ACK', code, expiresAt });
      return;
    }
    if (type === 'PAIR_BY_CODE' || type === 'PAIR_CODE_CONNECT') {
      this.handlePairByCode(sender, message);
      return;
    }
    if (type === 'PAIR_REQUEST') {
      this.handleDirectPairRequest(sender, message);
      return;
    }

    if (type === 'PAIR_ACCEPT' || type === 'PAIR_REJECT') {
      if (sender.type !== 'RECEIVER') return;
      const receiverId = sender.id;
      const controllerId = String(message.recipient || message.targetDeviceId || payload.controllerId || '');
      const key = this.pairKey(controllerId, receiverId);
      const pending = this.pendingPairs.get(key);
      if (!pending || pending.receiverId !== receiverId) {
        this.send(sender, { type: 'PAIR_ERROR', error: 'No matching pending controller request exists' });
        return;
      }
      this.pendingPairs.delete(key);
      if (type === 'PAIR_REJECT') {
        this.send(this.clients.get(controllerId), {
          type: 'PAIR_REJECT',
          sender: receiverId,
          senderId: receiverId,
          reason: String(message.reason || payload.reason || 'User declined connection request'),
          timestamp: Date.now(),
        });
        return;
      }
      this.activePairs.set(key, pending);
      this.send(this.clients.get(controllerId), {
        type: 'PAIR_ACCEPT',
        sender: receiverId,
        senderId: receiverId,
        payload: message.payload || {},
        sessionId: payload.sessionId || message.sessionId,
        receiverName: payload.receiverName || message.receiverName,
        fingerprint: payload.fingerprint || message.fingerprint,
        timestamp: Date.now(),
      });
      this.send(sender, { type: 'PAIR_SESSION_ESTABLISHED', controllerId, timestamp: Date.now() });
      return;
    }

    const requestedPeerId = String(message.recipient || message.targetDeviceId || '');
    const pair = this.getActivePair(sender.id, requestedPeerId || undefined);
    if (!pair) {
      this.send(sender, {
        type: 'COMMAND_ACK',
        sequence: message.sequence,
        success: false,
        error: 'No owner-approved active session for this command',
      });
      return;
    }

    const peerId = pair.controllerId === sender.id ? pair.receiverId : pair.controllerId;
    const peer = this.clients.get(peerId);
    if (!peer || peer.ws.readyState !== WebSocket.OPEN) {
      this.send(sender, { type: 'SESSION_ENDED', peerId, reason: 'Paired device is offline', timestamp: Date.now() });
      this.removePair(pair, 'Paired device is offline');
      return;
    }

    const allowedSessionMessages = new Set([
      'SDP_OFFER', 'SDP_ANSWER', 'ICE_CANDIDATE', 'DISPLAY_INFO',
      'INPUT_EVENT', 'APP_COMMAND', 'FILE_COMMAND', 'DIAGNOSTICS_RUN',
      'DIAGNOSTICS_RESULT', 'APP_LIST_RESPONSE', 'FILE_LIST_RESPONSE',
      'FILE_CHUNK_RESPONSE', 'RESOLUTION_SYNC', 'EMERGENCY_DISCONNECT',
    ]);
    if (!allowedSessionMessages.has(type)) {
      this.send(sender, { type: 'PROTOCOL_ERROR', error: `Unsupported message type: ${type}` });
      return;
    }

    this.send(peer, {
      ...message,
      recipient: peerId,
      senderId: sender.id,
      sender: sender.id,
    });

    if (type === 'EMERGENCY_DISCONNECT') {
      this.removePair(pair, 'Session ended by a participant');
    }
    // Do not synthesize a successful command ACK. The receiver must report the
    // actual execution result; forwarding a packet is not proof of execution.
  }
}
