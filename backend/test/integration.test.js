const http = require('http');
const path = require('path');
const fs = require('fs');
const crypto = require('crypto');
const express = require('express');
const cors = require('cors');
const { WebSocket, WebSocketServer } = require('ws');
const assert = require('assert');

// Use ephemeral test secrets. No production credentials or release keys are used.
process.env.JWT_SECRET = 'test-only-jwt-secret-0123456789-abcdef';
process.env.ADMIN_API_KEY = 'test-only-admin-key-never-use-in-production';
const testReleaseKeys = crypto.generateKeyPairSync('ec', {
  namedCurve: 'prime256v1',
  publicKeyEncoding: { type: 'spki', format: 'pem' },
  privateKeyEncoding: { type: 'pkcs8', format: 'pem' },
});
process.env.UI_RELEASE_PRIVATE_KEY = testReleaseKeys.privateKey;
process.env.UI_RELEASE_PUBLIC_KEY = testReleaseKeys.publicKey;

const { apiRouter } = require('../dist/api/routes');
const { SignalingHub } = require('../dist/signaling/SignalingHub');
const { DeviceService } = require('../dist/device-management/DeviceService');
const { AuthService } = require('../dist/authentication/AuthService');
const { UiReleaseService } = require('../dist/release-management/UiReleaseService');

const BASE_URL = 'http://127.0.0.1:8099';
const WS_URL = 'ws://127.0.0.1:8099/ws';
const ADMIN_KEY = process.env.ADMIN_API_KEY;

function waitForMessage(ws, predicate, timeoutMs = 4000) {
  return new Promise((resolve, reject) => {
    const timer = setTimeout(() => {
      ws.off('message', onMessage);
      reject(new Error('Timed out waiting for expected WebSocket message'));
    }, timeoutMs);
    const onMessage = (raw) => {
      let message;
      try { message = JSON.parse(raw.toString()); } catch { return; }
      if (!predicate(message)) return;
      clearTimeout(timer);
      ws.off('message', onMessage);
      resolve(message);
    };
    ws.on('message', onMessage);
  });
}

function openWebSocket(ws) {
  return new Promise((resolve, reject) => {
    ws.once('open', resolve);
    ws.once('error', reject);
  });
}

async function connectAndroidReceiver(deviceId) {
  const pair = crypto.generateKeyPairSync('ec', {
    namedCurve: 'prime256v1',
    publicKeyEncoding: { type: 'spki', format: 'der' },
    privateKeyEncoding: { type: 'pkcs8', format: 'der' },
  });
  const publicDer = pair.publicKey;
  const publicKeyBase64 = publicDer.toString('base64');
  const fingerprint = crypto.createHash('sha256').update(publicDer).digest('hex')
    .toUpperCase().match(/.{2}/g).join(':').slice(0, 47);

  const ws = new WebSocket(WS_URL, {
    headers: {
      'x-device-id': deviceId,
      'x-device-name': 'Android Receiver Test',
      'x-device-fingerprint': fingerprint,
      'x-device-public-key': publicKeyBase64,
      'x-client-role': 'RECEIVER',
    },
  });
  await openWebSocket(ws);

  const challenge = await waitForMessage(ws, message => message.type === 'AUTH_CHALLENGE');
  const signature = crypto.sign('sha256', Buffer.from(challenge.nonce, 'utf8'), {
    key: pair.privateKey,
    format: 'der',
    type: 'pkcs8',
  }).toString('base64');

  ws.send(JSON.stringify({ type: 'AUTH', payload: { signature } }));
  const authenticated = await waitForMessage(ws, message => message.type === 'AUTH_OK');
  assert.strictEqual(authenticated.clientId, deviceId);

  return { ws, fingerprint, publicKeyBase64 };
}

function closeWebSocket(ws) {
  return new Promise(resolve => {
    if (!ws || ws.readyState === WebSocket.CLOSED) return resolve();
    ws.once('close', resolve);
    ws.close();
    setTimeout(() => {
      if (ws.readyState !== WebSocket.CLOSED) ws.terminate();
      resolve();
    }, 1000).unref();
  });
}

async function runTests() {
  console.log('=== MAHI REMOTE SECURITY & INTEGRATION TESTS ===');
  const app = express();
  app.use(cors());
  app.use(express.json({ limit: '50mb' }));
  app.use('/api/v1', apiRouter);

  const server = http.createServer(app);
  const wss = new WebSocketServer({ server, path: '/ws' });
  new SignalingHub(wss);
  await new Promise(resolve => server.listen(8099, '127.0.0.1', resolve));

  const sockets = [];
  try {
    const health = await fetch(`${BASE_URL}/api/v1/health`);
    assert.strictEqual(health.status, 200);
    assert.strictEqual((await health.json()).status, 'healthy');
    console.log('✓ health endpoint');

    const devicesPublic = await fetch(`${BASE_URL}/api/v1/devices`);
    assert.strictEqual(devicesPublic.status, 401, 'Device inventory must not be public');
    const devicesAdmin = await fetch(`${BASE_URL}/api/v1/devices`, {
      headers: { 'X-Admin-Key': ADMIN_KEY },
    });
    assert.strictEqual(devicesAdmin.status, 200);
    console.log('✓ device inventory requires admin authorization');

    const restPair = await fetch(`${BASE_URL}/api/v1/pair/verify`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ code: '839-201' }),
    });
    assert.strictEqual(restPair.status, 410, 'Legacy REST pairing must not bypass receiver consent');
    console.log('✓ REST pairing bypass disabled');

    // Device auth: a valid signed nonce is required before the Android receiver is registered.
    const receiverId = 'MAHI-WS-RECEIVER-001';
    const receiverAuth = await connectAndroidReceiver(receiverId);
    const wsReceiver = receiverAuth.ws;
    sockets.push(wsReceiver);
    const code = '839-201';
    wsReceiver.send(JSON.stringify({
      type: 'REGISTER_PAIRING_CODE',
      payload: { code, expiresAt: Date.now() + 300000 },
    }));
    const codeAck = await waitForMessage(wsReceiver, message => message.type === 'CODE_REGISTERED_ACK');
    assert.strictEqual(codeAck.code, code);
    assert(DeviceService.getDevice(receiverId), 'Authenticated receiver should be registered');
    console.log('✓ Android nonce challenge and public-key identity verification');

    // Browser controller requests pairing. The receiver must explicitly approve.
    const wsController = new WebSocket(WS_URL);
    sockets.push(wsController);
    await openWebSocket(wsController);
    const hello = await waitForMessage(wsController, message => message.type === 'SERVER_CONNECTED');
    const controllerId = hello.clientId;

    const pairRequestPromise = waitForMessage(wsReceiver, message => message.type === 'PAIR_REQUEST');
    wsController.send(JSON.stringify({
      type: 'PAIR_BY_CODE',
      code,
      operatorName: 'Browser Test Controller',
      requestedScopes: ['Screen Mirroring', 'Remote Interaction'],
    }));
    const pairPending = await waitForMessage(wsController, message => message.type === 'PAIR_PENDING');
    assert.strictEqual(pairPending.receiverId, receiverId);
    const pairRequest = await pairRequestPromise;
    assert.strictEqual(pairRequest.controllerId, controllerId);
    console.log('✓ pairing code delivers a pending owner-consent request');

    // Before consent, all remote control packets must be rejected.
    wsController.send(JSON.stringify({
      type: 'INPUT_EVENT',
      targetDeviceId: receiverId,
      command: { type: 'TAP', x: 100, y: 200 },
    }));
    const blocked = await waitForMessage(wsController, message => message.type === 'COMMAND_ACK');
    assert.strictEqual(blocked.success, false);
    assert.match(blocked.error, /owner-approved active session/i);
    console.log('✓ unapproved controller commands are blocked');

    // Explicit receiver approval establishes the active pair.
    const acceptedPromise = waitForMessage(wsController, message => message.type === 'PAIR_ACCEPT');
    wsReceiver.send(JSON.stringify({
      type: 'PAIR_ACCEPT',
      recipient: controllerId,
      payload: {
        sessionId: 'TEST-SESSION-001',
        receiverName: 'Android Receiver Test',
        fingerprint: receiverAuth.fingerprint,
      },
    }));
    const accepted = await acceptedPromise;
    assert.strictEqual(accepted.senderId, receiverId);
    console.log('✓ PAIR_ACCEPT establishes an owner-approved pair');

    // After consent, real signaling and input payloads are forwarded to the paired peer.
    const inputPromise = waitForMessage(wsReceiver, message => message.type === 'INPUT_EVENT');
    wsController.send(JSON.stringify({
      type: 'INPUT_EVENT',
      targetDeviceId: receiverId,
      command: { type: 'TAP', x: 320, y: 640 },
    }));
    const forwardedInput = await inputPromise;
    assert.strictEqual(forwardedInput.senderId, controllerId);
    assert.strictEqual(forwardedInput.command.type, 'TAP');

    const sdpPromise = waitForMessage(wsReceiver, message => message.type === 'SDP_OFFER');
    wsController.send(JSON.stringify({
      type: 'SDP_OFFER',
      targetDeviceId: receiverId,
      payload: { sdp: 'v=0\\r\\no=- test 1 IN IP4 127.0.0.1' },
    }));
    const sdp = await sdpPromise;
    assert.match(sdp.payload.sdp, /^v=0/);

    const answerPromise = waitForMessage(wsController, message => message.type === 'SDP_ANSWER');
    wsReceiver.send(JSON.stringify({
      type: 'SDP_ANSWER',
      recipient: controllerId,
      payload: { sdp: 'v=0\\r\\no=- answer 1 IN IP4 127.0.0.1' },
    }));
    const answer = await answerPromise;
    assert.match(answer.payload.sdp, /^v=0/);
    console.log('✓ approved input and SDP offer/answer relay');

    // Reuse of the one-time pairing code must fail.
    const wsReplay = new WebSocket(WS_URL);
    sockets.push(wsReplay);
    await openWebSocket(wsReplay);
    await waitForMessage(wsReplay, message => message.type === 'SERVER_CONNECTED');
    wsReplay.send(JSON.stringify({ type: 'PAIR_BY_CODE', code }));
    const replay = await waitForMessage(wsReplay, message => message.type === 'PAIR_ERROR');
    assert.match(replay.error, /invalid|expired|offline/i);
    console.log('✓ one-time pairing code replay is rejected');

    // Public release endpoints do not pretend an active release exists before a real signed publish.
    const noUiRelease = await fetch(`${BASE_URL}/api/v1/ui-updates/latest`);
    assert.strictEqual(noUiRelease.status, 404);
    const pubKeyResponse = await fetch(`${BASE_URL}/api/v1/ui-updates/public-key`);
    assert.strictEqual(pubKeyResponse.status, 200);
    const pubKey = await pubKeyResponse.json();
    assert.strictEqual(pubKey.publicKeyPem, testReleaseKeys.publicKey);
    console.log('✓ release key endpoint uses configured rotated key');

    // Publishing a UI release requires all three current assets and their actual bytes to match.
    const liveDir = path.join(__dirname, '../public/live');
    const assets = ['index.html', 'live.css', 'live.js'].map(file => {
      const bytes = fs.readFileSync(path.join(liveDir, file));
      return {
        path: `live/${file}`,
        sha256: crypto.createHash('sha256').update(bytes).digest('hex'),
        size: bytes.length,
      };
    });
    const uiPublish = await fetch(`${BASE_URL}/api/v1/admin/ui-releases`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json', 'X-Admin-Key': ADMIN_KEY },
      body: JSON.stringify({
        version: '9.9.1',
        buildId: 'CI-REVIEW-991',
        releaseNotes: 'Security and signaling verification test',
        componentsUpdated: ['Pairing', 'WebRTC'],
        assets,
      }),
    });
    assert.strictEqual(uiPublish.status, 201);
    const published = await uiPublish.json();
    assert.strictEqual(published.success, true);
    const uiLatest = await fetch(`${BASE_URL}/api/v1/ui-updates/latest`);
    assert.strictEqual(uiLatest.status, 200);
    const uiManifest = await uiLatest.json();
    assert.strictEqual(uiManifest.version, '9.9.1');

    const bundleResponse = await fetch(`${BASE_URL}/api/v1/ui-releases/9.9.1/bundle`);
    assert.strictEqual(bundleResponse.status, 200);
    const bundle = await bundleResponse.json();
    for (const asset of assets) {
      const fileName = path.basename(asset.path);
      const bytes = Buffer.from(bundle.files[fileName], 'utf8');
      assert.strictEqual(bytes.length, asset.size);
      assert.strictEqual(crypto.createHash('sha256').update(bytes).digest('hex'), asset.sha256);
    }
    console.log('✓ ECDSA-signed UI release and every bundle asset verified');

    // Tampering with active assets after publication makes download fail closed.
    const liveIndex = path.join(liveDir, 'index.html');
    const originalIndex = fs.readFileSync(liveIndex);
    fs.writeFileSync(liveIndex, Buffer.concat([originalIndex, Buffer.from('tampered')]));
    const tamperedBundle = await fetch(`${BASE_URL}/api/v1/ui-releases/9.9.1/bundle`);
    assert.strictEqual(tamperedBundle.status, 409);
    fs.writeFileSync(liveIndex, originalIndex);
    console.log('✓ modified signed UI bundle is rejected');

    // OTA must never advertise or serve the previous 54-byte placeholder as an APK.
    const otaLatest = await fetch(`${BASE_URL}/api/v1/app-updates/latest?platform=android`);
    assert.strictEqual(otaLatest.status, 404);
    const fakeApk = Buffer.from('PK\\x03\\x04MockApkZipHeaderAndContent');
    const invalidUpload = await fetch(`${BASE_URL}/api/v1/admin/app-updates/upload`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json', 'X-Admin-Key': ADMIN_KEY },
      body: JSON.stringify({
        versionName: '9.9.2',
        versionCode: 2,
        releaseNotes: 'This upload is intentionally invalid',
        packageName: 'com.aistudio.mahiremote.bqpjku',
        signingFingerprint: 'AA:'.repeat(31) + 'AA',
        apkBase64: fakeApk.toString('base64'),
      }),
    });
    assert.strictEqual(invalidUpload.status, 400);
    const noFakeDownload = await fetch(`${BASE_URL}/api/v1/app-updates/download/latest`);
    assert.strictEqual(noFakeDownload.status, 404);
    console.log('✓ placeholder APK upload and download are rejected');

    // The old committed default admin key must not authorize publication.
    const weakAdminAttempt = await fetch(`${BASE_URL}/api/v1/admin/ui-releases`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json', 'X-Admin-Key': 'mahi-admin-secret-2026' },
      body: JSON.stringify({ version: '9.9.2', buildId: 'NOPE', releaseNotes: 'should fail', assets }),
    });
    assert.strictEqual(weakAdminAttempt.status, 401);
    console.log('✓ removed default admin credential is rejected');

    // Rate limiter behavior is independently tested.
    const rateKey = 'integration-rate-limit';
    let blockedByRateLimit = false;
    for (let i = 0; i < 15; i++) {
      if (!AuthService.checkRateLimit(rateKey, 10, 60000)) {
        blockedByRateLimit = true;
        break;
      }
    }
    assert(blockedByRateLimit);
    console.log('✓ rate limiter');

    // Sync agent uses the same configured rotated public key for signature checks.
    const { verifyEcdsaSignature } = require('../scripts/sync-agent');
    const canonical = `${uiManifest.version}:${uiManifest.buildId}:${uiManifest.checksumSha256}`;
    assert.strictEqual(verifyEcdsaSignature(canonical, uiManifest.signature, testReleaseKeys.publicKey), true);
    assert.strictEqual(verifyEcdsaSignature(canonical + ':tampered', uiManifest.signature, testReleaseKeys.publicKey), false);
    console.log('✓ sync bridge signature verification');

    console.log('=== ALL BACKEND INTEGRATION TESTS PASSED ===');
  } finally {
    await Promise.all(sockets.map(closeWebSocket));
    await new Promise(resolve => server.close(resolve));
  }
}

runTests().catch(error => {
  console.error('INTEGRATION TEST FAILURE:', error);
  process.exitCode = 1;
});
