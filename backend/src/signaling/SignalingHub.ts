import { WebSocket, WebSocketServer } from 'ws';
import { DeviceService } from '../device-management/DeviceService';
import { UiReleaseService } from '../release-management/UiReleaseService';

interface ClientConnection {
  ws: WebSocket;
  id: string;
  type: 'ANDROID' | 'WINDOWS' | 'CONTROLLER' | 'RECEIVER';
  fingerprint: string;
}

export class SignalingHub {
  private clients = new Map<string, ClientConnection>();

  constructor(private wss: WebSocketServer) {
    this.init();
    UiReleaseService.setUpdateBroadcaster((release) => {
      this.broadcastUiUpdate(release);
    });
  }

  public broadcastUiUpdate(release: any): void {
    const payload = JSON.stringify({
      type: 'UI_UPDATE_AVAILABLE',
      release,
      timestamp: Date.now()
    });
    console.log(`[SignalingHub] Broadcasting UI_UPDATE_AVAILABLE v${release.version} to ${this.clients.size} connected client(s)`);
    for (const [id, client] of this.clients.entries()) {
      if (client.ws.readyState === WebSocket.OPEN) {
        client.ws.send(payload);
      }
    }
  }

  public broadcastAppUpdate(otaRelease: any): void {
    const payload = JSON.stringify({
      type: 'APP_UPDATE_AVAILABLE',
      release: otaRelease,
      timestamp: Date.now()
    });
    console.log(`[SignalingHub] Broadcasting APP_UPDATE_AVAILABLE v${otaRelease.versionName} to clients`);
    for (const [id, client] of this.clients.entries()) {
      if (client.ws.readyState === WebSocket.OPEN) {
        client.ws.send(payload);
      }
    }
  }

  private init(): void {
    this.wss.on('connection', (ws: WebSocket, req) => {
      const deviceId = req.headers['x-device-id'] as string;
      const operatorFp = req.headers['x-operator-fingerprint'] as string;
      const clientRole = (req.headers['x-client-role'] as string)?.toUpperCase();

      let clientId = '';
      let clientType: 'ANDROID' | 'WINDOWS' | 'CONTROLLER' | 'RECEIVER' = 'ANDROID';

      if (deviceId) {
        clientId = deviceId;
        clientType = clientRole === 'CONTROLLER' ? 'CONTROLLER' : 'RECEIVER';
        DeviceService.registerDevice(deviceId, req.headers['x-device-name'] as string || 'Android Phone', (req.headers['x-device-fingerprint'] as string) || 'UNKNOWN');
      } else if (operatorFp) {
        clientId = `operator_${operatorFp.substring(0, 8)}`;
        clientType = 'WINDOWS';
      } else {
        clientId = `client_${Date.now()}`;
      }

      const client: ClientConnection = {
        ws,
        id: clientId,
        type: clientType,
        fingerprint: (req.headers['x-device-fingerprint'] || operatorFp || '') as string,
      };

      this.clients.set(clientId, client);
      console.log(`[SignalingHub] Connected: ${clientId} (${clientType})`);

      ws.on('message', (data: string) => {
        try {
          const msg = JSON.parse(data.toString());
          this.handleMessage(client, msg);
        } catch (e) {
          console.error('[SignalingHub] Invalid message payload', e);
        }
      });

      ws.on('close', () => {
        this.clients.delete(clientId);
        console.log(`[SignalingHub] Disconnected: ${clientId}`);
      });
    });
  }

  private handleMessage(sender: ClientConnection, msg: any): void {
    const { type, recipient, targetDeviceId, sequence, code, pairingCode } = msg;

    if (type === 'PING') {
      sender.ws.send(JSON.stringify({ type: 'PONG', timestamp: Date.now() }));
      return;
    }

    if (type === 'REGISTER') {
      sender.ws.send(JSON.stringify({ type: 'REGISTER_ACK', status: 'OK', deviceId: sender.id }));
      return;
    }

    if (type === 'REGISTER_PAIRING_CODE') {
      const pCode = code || pairingCode || msg.payload?.code;
      const exp = msg.expiresAt || msg.payload?.expiresAt || (Date.now() + 5 * 60 * 1000);
      if (pCode) {
        DeviceService.storePairingCode(sender.id, pCode, exp);
        console.log(`[SignalingHub] Registered pairing code ${pCode} for device ${sender.id}`);
        sender.ws.send(JSON.stringify({ type: 'CODE_REGISTERED_ACK', code: pCode }));
      }
      return;
    }

    if (type === 'PAIR_BY_CODE' || type === 'PAIR_CODE_CONNECT') {
      const pCode = code || pairingCode || msg.payload?.code;
      const targetId = DeviceService.validateAndConsumePairingCode(pCode);
      if (targetId && this.clients.has(targetId)) {
        const target = this.clients.get(targetId)!;
        console.log(`[SignalingHub] Pairing code ${pCode} matched! Forwarding PAIR_REQUEST from ${sender.id} to ${targetId}`);
        target.ws.send(JSON.stringify({
          type: 'PAIR_REQUEST',
          sender: sender.id,
          senderId: sender.id,
          controllerId: sender.id,
          operatorName: msg.operatorName || msg.controllerName || 'Mahi Controller Phone',
          operatorLocation: msg.operatorLocation || 'Remote Android Controller',
          fingerprint: sender.fingerprint || msg.fingerprint,
          requestedScopes: msg.requestedScopes || ['Screen Mirroring', 'Remote Interaction', 'File Transfer', 'Diagnostics'],
          timestamp: Date.now(),
        }));
      } else {
        sender.ws.send(JSON.stringify({
          type: 'PAIR_ERROR',
          error: 'Invalid, consumed, or expired 6-digit pairing code',
          code: pCode,
        }));
      }
      return;
    }

    // Direct routing if recipient specified
    const targetId = recipient || targetDeviceId;
    if (targetId && this.clients.has(targetId)) {
      const target = this.clients.get(targetId)!;
      target.ws.send(JSON.stringify({
        ...msg,
        senderId: sender.id,
        sender: sender.id,
      }));

      // Generate ACK for commands
      if (sequence) {
        sender.ws.send(JSON.stringify({
          type: 'COMMAND_ACK',
          sequence,
          success: true,
          rtt: 18,
        }));
      }
      return;
    }

    // Sensitive commands must never be broadcast without a target device
    const sensitiveCommands = ['INPUT_EVENT', 'APP_COMMAND', 'FILE_COMMAND', 'DIAGNOSTICS_RUN', 'EMERGENCY_DISCONNECT'];
    if (sensitiveCommands.includes(type)) {
      console.warn(`[SignalingHub] Blocked unrouted sensitive command ${type} from ${sender.id} (missing valid target recipient)`);
      if (sequence) {
        sender.ws.send(JSON.stringify({
          type: 'COMMAND_ACK',
          sequence,
          success: false,
          error: 'Missing or inactive target recipient device',
        }));
      }
      return;
    }

    // Broadcast non-sensitive state notifications from Windows/Controller to paired Android receivers
    if (sender.type === 'WINDOWS' || sender.type === 'CONTROLLER') {
      let routed = false;
      for (const [id, client] of this.clients.entries()) {
        if (client.type === 'ANDROID' || client.type === 'RECEIVER') {
          client.ws.send(JSON.stringify({
            ...msg,
            senderId: sender.id,
            sender: sender.id,
          }));
          routed = true;
        }
      }

      if (sequence) {
        sender.ws.send(JSON.stringify({
          type: 'COMMAND_ACK',
          sequence,
          success: routed,
          rtt: 22,
        }));
      }
      return;
    }

    // Broadcast from Android receiver to Controller or Windows
    if (sender.type === 'ANDROID' || sender.type === 'RECEIVER') {
      for (const [id, client] of this.clients.entries()) {
        if (client.type === 'WINDOWS' || client.type === 'CONTROLLER') {
          client.ws.send(JSON.stringify({
            ...msg,
            senderId: sender.id,
            sender: sender.id,
          }));
        }
      }
    }
  }
}
