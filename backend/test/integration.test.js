const http = require('http');
const express = require('express');
const cors = require('cors');
const { WebSocket, WebSocketServer } = require('ws');
const assert = require('assert');

// Import compiled backend modules
const { apiRouter } = require('../dist/api/routes');
const { SignalingHub } = require('../dist/signaling/SignalingHub');
const { DeviceService } = require('../dist/device-management/DeviceService');
const { AuthService } = require('../dist/authentication/AuthService');

async function runTests() {
  console.log('=== STARTING BACKEND INTEGRATION TESTS ===');
  
  // 1. Setup ephemeral test server
  const app = express();
  app.use(cors());
  app.use(express.json());
  app.use('/api/v1', apiRouter);

  const server = http.createServer(app);
  const wss = new WebSocketServer({ server, path: '/ws' });
  new SignalingHub(wss);

  await new Promise((resolve) => server.listen(8099, resolve));
  console.log('Test Server listening on port 8099');

  try {
    // 2. Test /api/v1/health
    console.log('[TEST] Checking /api/v1/health...');
    const healthRes = await fetch('http://localhost:8099/api/v1/health');
    assert.strictEqual(healthRes.status, 200);
    const healthData = await healthRes.json();
    assert.strictEqual(healthData.status, 'healthy');
    console.log('✓ /api/v1/health PASSED');

    // 3. Test Device Registration & Listing
    console.log('[TEST] Registering mock device in Noakhali...');
    DeviceService.registerDevice('MAHI-TEST-NOAKHALI', 'Samsung Test Device', 'AA:BB:CC:DD:EE:FF:11:22');
    const devRes = await fetch('http://localhost:8099/api/v1/devices');
    assert.strictEqual(devRes.status, 200);
    const devData = await devRes.json();
    const found = devData.devices.find(d => d.deviceId === 'MAHI-TEST-NOAKHALI');
    assert(found, 'Device not found in listing');
    console.log('✓ Device registration and listing PASSED');

    // 4. Test 6-Digit Pairing Code Lifecycle (Single-use & Expiry)
    console.log('[TEST] Testing Pairing Code verification and single-use consumption...');
    const pairingCode = '839-201';
    DeviceService.storePairingCode('MAHI-TEST-NOAKHALI', pairingCode, Date.now() + 300000); // 5 min expiry

    // First attempt (Valid)
    const pairRes = await fetch('http://localhost:8099/api/v1/pair/verify', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({
        code: pairingCode,
        operatorFingerprint: 'OPERATOR-SYLHET-99',
        operatorName: 'Sylhet Test Operator',
        operatorLocation: 'Sylhet, Bangladesh'
      })
    });
    assert.strictEqual(pairRes.status, 200);
    const pairData = await pairRes.json();
    assert.strictEqual(pairData.success, true);
    assert.strictEqual(pairData.deviceId, 'MAHI-TEST-NOAKHALI');
    assert(pairData.sessionToken, 'Session token missing');
    console.log('✓ Initial Pairing Verification PASSED (Token received)');

    // Second attempt with SAME code (Must fail - Single use enforce)
    const replayRes = await fetch('http://localhost:8099/api/v1/pair/verify', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({
        code: pairingCode,
        operatorFingerprint: 'OPERATOR-SYLHET-99'
      })
    });
    assert.strictEqual(replayRes.status, 401, 'Replay of consumed pairing code was not rejected');
    console.log('✓ Single-Use Code Enforcement PASSED (Replay blocked with 401)');

    // 5. Test Expired Code
    console.log('[TEST] Testing expired code rejection...');
    const expiredCode = '111-222';
    DeviceService.storePairingCode('MAHI-TEST-NOAKHALI', expiredCode, Date.now() - 1000); // Expired 1s ago
    const expiredRes = await fetch('http://localhost:8099/api/v1/pair/verify', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({
        code: expiredCode,
        operatorFingerprint: 'OPERATOR-SYLHET-99'
      })
    });
    assert.strictEqual(expiredRes.status, 401);
    console.log('✓ Expired Pairing Code Rejection PASSED');

    // 6. Test Rate Limiting
    console.log('[TEST] Testing Rate Limiter threshold...');
    const clientKey = '192.168.1.99';
    let blocked = false;
    for (let i = 0; i < 70; i++) {
      if (!AuthService.checkRateLimit(clientKey, 50, 10000)) {
        blocked = true;
        break;
      }
    }
    assert(blocked, 'Rate limiter failed to block excessive requests');
    console.log('✓ Rate Limiter Threshold Enforcement PASSED');

    // 7. Test WebSocket Signaling: Device and Operator connection and SDP relay
    console.log('[TEST] Testing WebSocket WebRTC signaling relay...');
    const wsDevice = new WebSocket('ws://localhost:8099/ws', {
      headers: {
        'x-device-id': 'MAHI-WS-DEVICE',
        'x-device-fingerprint': 'DEV-FP-1234'
      }
    });

    const wsOperator = new WebSocket('ws://localhost:8099/ws', {
      headers: {
        'x-operator-fingerprint': 'OP-FP-5678',
        'x-operator-name': 'Sylhet Operator',
        'x-operator-location': 'Sylhet'
      }
    });

    await Promise.all([
      new Promise(res => wsDevice.on('open', res)),
      new Promise(res => wsOperator.on('open', res))
    ]);
    console.log('  Both Device and Operator connected to WebSocket signaling');

    // Operator sends SDP Offer targeted to MAHI-WS-DEVICE
    const sdpOfferPromise = new Promise((resolve, reject) => {
      const timeout = setTimeout(() => reject(new Error('Timeout waiting for SDP relay')), 3000);
      wsDevice.on('message', (raw) => {
        const msg = JSON.parse(raw.toString());
        if (msg.type === 'SDP_OFFER') {
          clearTimeout(timeout);
          resolve(msg);
        }
      });
    });

    wsOperator.send(JSON.stringify({
      type: 'SDP_OFFER',
      targetDeviceId: 'MAHI-WS-DEVICE',
      payload: { sdp: 'v=0\r\no=- 1234 2 IN IP4 0.0.0.0...' }
    }));

    const receivedOffer = await sdpOfferPromise;
    assert.strictEqual(receivedOffer.type, 'SDP_OFFER');
    assert(receivedOffer.payload.sdp.includes('v=0'), 'SDP payload corrupted');
    console.log('✓ WebSocket Signaling SDP Offer Relay PASSED');

    // Device responds with SDP Answer
    const sdpAnswerPromise = new Promise((resolve, reject) => {
      const timeout = setTimeout(() => reject(new Error('Timeout waiting for SDP answer relay')), 3000);
      wsOperator.on('message', (raw) => {
        const msg = JSON.parse(raw.toString());
        if (msg.type === 'SDP_ANSWER') {
          clearTimeout(timeout);
          resolve(msg);
        }
      });
    });

    wsDevice.send(JSON.stringify({
      type: 'SDP_ANSWER',
      recipient: 'operator_OP-FP-56',
      payload: { sdp: 'v=0\r\no=- 5678 2 IN IP4 0.0.0.0...' }
    }));

    const receivedAnswer = await sdpAnswerPromise;
    assert.strictEqual(receivedAnswer.type, 'SDP_ANSWER');
    console.log('✓ WebSocket Signaling SDP Answer Relay PASSED');

    // 8. Test Live UI Release API (GET /api/v1/ui-updates/latest)
    console.log('[TEST] Checking /api/v1/ui-updates/latest...');
    const uiRes = await fetch('http://localhost:8099/api/v1/ui-updates/latest');
    assert.strictEqual(uiRes.status, 200);
    const uiData = await uiRes.json();
    assert(uiData.version, 'Missing UI version');
    assert(uiData.checksumSha256, 'Missing checksumSha256');
    assert(uiData.signature, 'Missing digital signature');
    console.log(`✓ Initial Live UI Manifest PASSED (Version: v${uiData.version}, Build: ${uiData.buildId})`);

    // 9. Test Admin-Authenticated Live UI Publication & WebSocket Broadcast
    console.log('[TEST] Testing Admin Live UI Publication & Real-time WebSocket Broadcast...');
    const wsUiListener = new WebSocket('ws://localhost:8099/ws', {
      headers: { 'x-device-id': 'MAHI-TEST-RECEIVER-PHONE' }
    });
    await new Promise(res => wsUiListener.on('open', res));

    const broadcastPromise = new Promise((resolve, reject) => {
      const timeout = setTimeout(() => reject(new Error('Timeout waiting for UI_UPDATE_AVAILABLE')), 3000);
      wsUiListener.on('message', (raw) => {
        const msg = JSON.parse(raw.toString());
        if (msg.type === 'UI_UPDATE_AVAILABLE') {
          clearTimeout(timeout);
          resolve(msg);
        }
      });
    });

    const publishRes = await fetch('http://localhost:8099/api/v1/admin/ui-releases', {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
        'X-Admin-Key': 'mahi-admin-secret-2026'
      },
      body: JSON.stringify({
        version: '1.2.0',
        buildId: 'MAHI-TEST-BUILD-120',
        releaseNotes: 'Telemetry refresh and responsive design overhaul.',
        componentsUpdated: ['Dashboard', 'Diagnostics']
      })
    });
    assert.strictEqual(publishRes.status, 201);
    const publishData = await publishRes.json();
    assert.strictEqual(publishData.success, true);
    assert.strictEqual(publishData.release.version, '1.2.0');

    const receivedBroadcast = await broadcastPromise;
    assert.strictEqual(receivedBroadcast.release.version, '1.2.0');
    console.log('✓ Admin UI Release Publication & WebSocket Broadcast PASSED');

    // 10. Test UI Rollback
    console.log('[TEST] Testing Live UI Rollback to previous version...');
    const rollbackRes = await fetch('http://localhost:8099/api/v1/admin/ui-releases/rollback', {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
        'X-Admin-Key': 'mahi-admin-secret-2026'
      },
      body: JSON.stringify({ targetVersion: '1.0.0' })
    });
    assert.strictEqual(rollbackRes.status, 200);
    const rollbackData = await rollbackRes.json();
    assert.strictEqual(rollbackData.activeRelease.version, '1.0.0');
    console.log('✓ Live UI Rollback PASSED (Rolled back to v1.0.0)');

    // 11. Test Native APK OTA Metadata (GET /api/v1/app-updates/latest)
    console.log('[TEST] Checking /api/v1/app-updates/latest?platform=android...');
    const otaRes = await fetch('http://localhost:8099/api/v1/app-updates/latest?platform=android');
    assert.strictEqual(otaRes.status, 200);
    const otaData = await otaRes.json();
    assert.strictEqual(otaData.packageName, 'com.aistudio.mahiremote.bqpjku');
    assert(otaData.versionCode >= 1);
    assert(otaData.apkDownloadUrl);
    console.log(`✓ Native APK OTA Metadata PASSED (Package: ${otaData.packageName}, Code: ${otaData.versionCode})`);

    // 12. Test ECDSA P-256 Public Key Retrieval
    console.log('[TEST] Checking /api/v1/ui-updates/public-key...');
    const pubKeyRes = await fetch('http://localhost:8099/api/v1/ui-updates/public-key');
    assert.strictEqual(pubKeyRes.status, 200);
    const pubKeyData = await pubKeyRes.json();
    assert(pubKeyData.publicKeyPem.includes('BEGIN PUBLIC KEY'), 'Public key is not in PEM format');
    assert.strictEqual(pubKeyData.algorithm, 'SHA256withECDSA');
    console.log('✓ ECDSA Public Key Retrieval PASSED');

    // 13. Test ECDSA Asymmetric Signature Validation & Rejection of Tampered Manifest
    console.log('[TEST] Verifying ECDSA signature integrity and tamper rejection...');
    const crypto = require('crypto');
    const { UiReleaseService } = require('../dist/release-management/UiReleaseService');
    const validManifest = UiReleaseService.getLatestRelease();
    assert(validManifest, 'Latest manifest missing');

    const canonicalOriginal = `${validManifest.version}:${validManifest.buildId}:${validManifest.checksumSha256}`;
    const isAuthentic = UiReleaseService.verifySignature(canonicalOriginal, validManifest.signature);
    assert.strictEqual(isAuthentic, true, 'Valid manifest signature was not verified');

    // Tampered payload (modified version or checksum)
    const canonicalTampered = `${validManifest.version}:TAMPERED_BUILD:${validManifest.checksumSha256}`;
    const isTamperedAccepted = UiReleaseService.verifySignature(canonicalTampered, validManifest.signature);
    assert.strictEqual(isTamperedAccepted, false, 'Tampered manifest was mistakenly accepted');
    console.log('✓ Asymmetric ECDSA Signature & Tamper Rejection PASSED');

    // 14. Test Binary APK Upload, Download, History & Rollback
    console.log('[TEST] Testing APK binary storage, upload, download, and rollback...');
    const mockApkBytes = Buffer.from('PK\x03\x04MockApkZipHeaderAndContentForMahiRemoteControl2026');
    const mockApkBase64 = mockApkBytes.toString('base64');
    const mockApkSha = crypto.createHash('sha256').update(mockApkBytes).digest('hex');

    const apkUploadRes = await fetch('http://localhost:8099/api/v1/admin/app-updates/upload', {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
        'X-Admin-Key': 'mahi-admin-secret-2026'
      },
      body: JSON.stringify({
        versionName: '1.1.0',
        versionCode: 2,
        releaseNotes: 'Performance optimizations and WebRTC latency fix.',
        sha256: mockApkSha,
        apkBase64: mockApkBase64,
        isMandatory: false
      })
    });
    assert.strictEqual(apkUploadRes.status, 201);
    const apkUploadData = await apkUploadRes.json();
    assert.strictEqual(apkUploadData.success, true);
    assert.strictEqual(apkUploadData.release.versionCode, 2);

    // Verify download returns binary
    const apkDownloadRes = await fetch('http://localhost:8099/api/v1/app-updates/download/1.1.0');
    assert.strictEqual(apkDownloadRes.status, 200);
    const downloadedBuf = Buffer.from(await apkDownloadRes.arrayBuffer());
    assert.strictEqual(downloadedBuf.toString('hex'), mockApkBytes.toString('hex'));
    console.log('✓ APK Binary Upload & Download Verification PASSED');

    // Verify history and rollback
    const apkHistoryRes = await fetch('http://localhost:8099/api/v1/app-updates/history');
    assert.strictEqual(apkHistoryRes.status, 200);
    const apkHistoryData = await apkHistoryRes.json();
    assert(apkHistoryData.releases.length >= 2, 'History did not record new release');

    const apkRollbackRes = await fetch('http://localhost:8099/api/v1/admin/app-updates/rollback', {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
        'X-Admin-Key': 'mahi-admin-secret-2026'
      },
      body: JSON.stringify({ targetVersionName: '1.0.0' })
    });
    assert.strictEqual(apkRollbackRes.status, 200);
    console.log('✓ Native APK History & Rollback PASSED');

    // 15. Test Sync Bridge Agent signature verification logic
    console.log('[TEST] Testing Cloud-to-Windows Sync Bridge verification...');
    const { verifyEcdsaSignature } = require('../scripts/sync-agent');
    const syncSignatureValid = verifyEcdsaSignature(canonicalOriginal, validManifest.signature, pubKeyData.publicKeyPem);
    assert.strictEqual(syncSignatureValid, true, 'Sync bridge rejected valid signature');
    const syncTamperRejected = verifyEcdsaSignature(canonicalTampered, validManifest.signature, pubKeyData.publicKeyPem);
    assert.strictEqual(syncTamperRejected, false, 'Sync bridge accepted tampered signature');
    console.log('✓ Cloud-to-Windows Sync Bridge Verification Logic PASSED');

    wsDevice.close();
    wsOperator.close();
    wsUiListener.close();

    console.log('========================================================');
    console.log(' ALL 15 BACKEND, LIVE UI & OTA INTEGRATION TESTS PASSED ');
    console.log('========================================================');

  } finally {
    server.close();
  }
}

runTests().catch(err => {
  console.error('INTEGRATION TEST FAILURE:', err);
  process.exit(1);
});
