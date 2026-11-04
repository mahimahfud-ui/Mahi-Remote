import { Router, Request, Response } from 'express';
import crypto from 'crypto';
import path from 'path';
import fs from 'fs';
import { DeviceService } from '../device-management/DeviceService';
import { AuthService } from '../authentication/AuthService';
import { UiReleaseService } from '../release-management/UiReleaseService';
import { OtaService } from '../release-management/OtaService';
import { detectLanIp, getAllLanInterfaces } from '../utils/network';

export const apiRouter = Router();

// -------------------------------------------------------------
// Health and Network Telemetry
// -------------------------------------------------------------
apiRouter.get('/health', (req: Request, res: Response) => {
  const lanIp = detectLanIp();
  res.json({
    status: 'healthy',
    service: 'Mahi Remote Cloud Signaling & Auth',
    version: '1.0.0',
    timestamp: new Date().toISOString(),
    lanIp,
    host: req.get('host'),
    protocol: req.protocol,
    interfaces: getAllLanInterfaces()
  });
});

apiRouter.get('/devices', (req: Request, res: Response) => {
  const devices = DeviceService.listDevices();
  res.json({ devices });
});

// -------------------------------------------------------------
// Device Pairing Lifecycle
// -------------------------------------------------------------
apiRouter.post('/pair/register-code', (req: Request, res: Response) => {
  const { deviceId, code, expiresAt } = req.body;
  if (!deviceId || !code) {
    return res.status(400).json({ error: 'Missing deviceId or pairing code' });
  }
  const expiry = expiresAt || (Date.now() + 5 * 60 * 1000);
  DeviceService.storePairingCode(deviceId, code, expiry);
  return res.json({ success: true, message: 'Pairing code registered', code, expiresAt: expiry });
});

apiRouter.post('/pair/verify', (req: Request, res: Response) => {
  const { code, operatorFingerprint, operatorName, operatorLocation } = req.body;
  if (!code || !operatorFingerprint) {
    return res.status(400).json({ error: 'Missing pairing parameters' });
  }

  const deviceId = DeviceService.validateAndConsumePairingCode(code);
  if (!deviceId) {
    return res.status(401).json({ error: 'Invalid or expired 6-digit pairing code' });
  }

  const token = AuthService.generateSessionToken({
    entityId: operatorFingerprint,
    entityType: 'OPERATOR',
    fingerprint: operatorFingerprint,
  });

  // Dynamic signaling WebSocket URL respecting current host (LAN IP or domain)
  const host = req.get('host') || '192.168.1.105:8080';
  const proto = req.protocol === 'https' ? 'wss' : 'ws';
  const dynamicSignalingUrl = `${proto}://${host}/ws`;

  return res.json({
    success: true,
    deviceId,
    sessionToken: token,
    signalingUrl: dynamicSignalingUrl,
  });
});

// -------------------------------------------------------------
// ICE Servers (STUN/TURN)
// -------------------------------------------------------------
apiRouter.get('/ice-servers', (req: Request, res: Response) => {
  const turnSecret = process.env.TURN_SHARED_SECRET || 'mahi-turn-secret-key-prod-2026';
  const turnDomain = process.env.TURN_DOMAIN || 'turn.mahi-remote.cloud';
  const turnPort = process.env.TURN_PORT || '3478';

  const expiry = Math.floor(Date.now() / 1000) + 12 * 3600;
  const username = `${expiry}:mahi_session`;
  const hmac = crypto.createHmac('sha1', turnSecret);
  hmac.setEncoding('base64');
  hmac.write(username);
  hmac.end();
  const credential = hmac.read();

  res.json({
    iceServers: [
      { urls: 'stun:stun.l.google.com:19302' },
      { urls: `stun:${turnDomain}:${turnPort}` },
      {
        urls: [
          `turn:${turnDomain}:${turnPort}?transport=udp`,
          `turn:${turnDomain}:${turnPort}?transport=tcp`,
        ],
        username,
        credential,
      },
    ],
  });
});

apiRouter.post('/sessions/:id/terminate', (req: Request, res: Response) => {
  const { id } = req.params;
  DeviceService.endSession(id);
  res.json({ success: true, message: `Session ${id} terminated` });
});

// -------------------------------------------------------------
// LIVE UI RELEASE SYSTEM (Type A: No APK Reinstallation)
// -------------------------------------------------------------
apiRouter.get('/ui-updates/latest', (req: Request, res: Response) => {
  const release = UiReleaseService.getLatestRelease();
  if (!release) {
    return res.status(404).json({ error: 'No active UI release found' });
  }
  return res.json(release);
});

apiRouter.get('/ui-updates/manifest', (req: Request, res: Response) => {
  const release = UiReleaseService.getLatestRelease();
  if (!release) {
    return res.status(404).json({ error: 'Manifest not found' });
  }
  return res.json(release);
});

apiRouter.get('/ui-releases/history', (req: Request, res: Response) => {
  const history = UiReleaseService.getAllReleases();
  const current = UiReleaseService.getLatestRelease();
  return res.json({
    activeVersion: current?.version || '1.0.0',
    releases: history,
  });
});

apiRouter.get('/ui-releases/:version/bundle', (req: Request, res: Response) => {
  const { version } = req.params;
  const release = UiReleaseService.getReleaseByVersion(version);
  if (!release) {
    return res.status(404).json({ error: `Version ${version} not found` });
  }

  // Deliver bundle manifest and component payload
  const publicDir = path.join(__dirname, '../../public');
  const liveIndexPath = path.join(publicDir, 'live/index.html');
  const liveCssPath = path.join(publicDir, 'live/live.css');
  const liveJsPath = path.join(publicDir, 'live/live.js');

  const bundlePayload = {
    manifest: release,
    timestamp: new Date().toISOString(),
    files: {
      'index.html': fs.existsSync(liveIndexPath) ? fs.readFileSync(liveIndexPath, 'utf-8') : '<!-- Mahi Live UI -->',
      'live.css': fs.existsSync(liveCssPath) ? fs.readFileSync(liveCssPath, 'utf-8') : '/* Mahi Live CSS */',
      'live.js': fs.existsSync(liveJsPath) ? fs.readFileSync(liveJsPath, 'utf-8') : '// Mahi Live JS',
    }
  };

  return res.json(bundlePayload);
});

// Admin-Authenticated Live UI Release Publishing
apiRouter.post('/admin/ui-releases', (req: Request, res: Response) => {
  const authHeader = req.headers['authorization'] || (req.headers['x-admin-key'] as string);
  if (!UiReleaseService.validateAdminKey(authHeader)) {
    return res.status(401).json({ error: 'Unauthorized: Valid Admin API key required for release publishing' });
  }

  const { version, buildId, releaseNotes, componentsUpdated, assets, isMandatory } = req.body;
  const result = UiReleaseService.publishRelease({
    version,
    buildId,
    releaseNotes,
    componentsUpdated,
    assets,
    isMandatory
  });

  if (!result.success) {
    return res.status(400).json({ error: result.error });
  }

  return res.status(201).json({
    success: true,
    message: `UI Release v${version} published successfully`,
    release: result.release,
  });
});

// Admin-Authenticated Rollback to Previous UI
apiRouter.post('/admin/ui-releases/rollback', (req: Request, res: Response) => {
  const authHeader = req.headers['authorization'] || (req.headers['x-admin-key'] as string);
  if (!UiReleaseService.validateAdminKey(authHeader)) {
    return res.status(401).json({ error: 'Unauthorized: Admin authentication required' });
  }

  const { targetVersion } = req.body;
  const result = UiReleaseService.rollback(targetVersion);

  if (!result.success) {
    return res.status(400).json({ error: result.error });
  }

  return res.json({
    success: true,
    message: `Successfully rolled back UI to v${result.activeRelease?.version}`,
    activeRelease: result.activeRelease,
  });
});

apiRouter.get('/ui-updates/public-key', (req: Request, res: Response) => {
  res.json({
    algorithm: 'SHA256withECDSA',
    curve: 'prime256v1',
    publicKeyPem: UiReleaseService.getPublicKeyPem(),
  });
});

// -------------------------------------------------------------
// NATIVE APK OTA UPDATES (Type B: Requires PackageInstaller)
// -------------------------------------------------------------
apiRouter.get('/app-updates/latest', (req: Request, res: Response) => {
  const platform = (req.query.platform as string) || 'android';
  const release = OtaService.getLatestRelease(platform);
  return res.json(release);
});

apiRouter.get('/app-updates/history', (req: Request, res: Response) => {
  const history = OtaService.getHistory();
  return res.json({ releases: history });
});

apiRouter.post('/admin/app-updates/upload', (req: Request, res: Response) => {
  const authHeader = req.headers['authorization'] || (req.headers['x-admin-key'] as string);
  if (!OtaService.validateAdminKey(authHeader)) {
    return res.status(401).json({ error: 'Unauthorized: Admin API key required' });
  }

  const { versionName, versionCode, releaseNotes, isMandatory, apkBase64 } = req.body;
  if (!apkBase64) {
    return res.status(400).json({ error: 'apkBase64 payload required for binary upload' });
  }

  const buffer = Buffer.from(apkBase64, 'base64');
  const result = OtaService.publishApkBinary({
    buffer,
    versionName,
    versionCode: parseInt(versionCode, 10),
    releaseNotes,
    isMandatory: Boolean(isMandatory),
  });

  if (!result.success) {
    return res.status(400).json({ error: result.error });
  }

  return res.status(201).json({
    success: true,
    message: `Stored binary APK for v${versionName} (${buffer.length} bytes)`,
    release: result.release,
  });
});

apiRouter.post('/admin/app-updates/rollback', (req: Request, res: Response) => {
  const authHeader = req.headers['authorization'] || (req.headers['x-admin-key'] as string);
  if (!OtaService.validateAdminKey(authHeader)) {
    return res.status(401).json({ error: 'Unauthorized: Admin API key required' });
  }

  const { targetVersionName } = req.body;
  const result = OtaService.rollback(targetVersionName);
  if (!result.success) {
    return res.status(400).json({ error: result.error });
  }

  return res.json({
    success: true,
    message: `Rolled back active APK release to v${targetVersionName}`,
    release: result.release,
  });
});

apiRouter.get('/app-updates/download/:version', (req: Request, res: Response) => {
  const { version } = req.params;
  const targetVer = version === 'latest' ? OtaService.getLatestRelease().versionName : version;
  const filePath = OtaService.getApkFilePath(targetVer);

  res.setHeader('Content-Type', 'application/vnd.android.package-archive');
  res.setHeader('Content-Disposition', `attachment; filename="mahi-remote-v${targetVer}.apk"`);

  if (filePath && fs.existsSync(filePath)) {
    const stat = fs.statSync(filePath);
    res.setHeader('Content-Length', stat.size);
    const stream = fs.createReadStream(filePath);
    return stream.pipe(res);
  }

  // Fallback testing binary
  const fallbackBytes = Buffer.from(`Mahi-Remote-Real-APK-Binary-v${targetVer}-Package-com.aistudio.mahiremote.bqpjku`);
  res.setHeader('Content-Length', fallbackBytes.length);
  return res.send(fallbackBytes);
});

apiRouter.get('/app-updates/download/latest', (req: Request, res: Response) => {
  const ota = OtaService.getLatestRelease('android');
  const filePath = OtaService.getApkFilePath(ota.versionName);

  res.setHeader('Content-Type', 'application/vnd.android.package-archive');
  res.setHeader('Content-Disposition', `attachment; filename="mahi-remote-v${ota.versionName}.apk"`);

  if (filePath && fs.existsSync(filePath)) {
    const stat = fs.statSync(filePath);
    res.setHeader('Content-Length', stat.size);
    const stream = fs.createReadStream(filePath);
    return stream.pipe(res);
  }

  const fallbackBytes = Buffer.from(`Mahi-Remote-Real-APK-Binary-v${ota.versionName}-Package-${ota.packageName}`);
  res.setHeader('Content-Length', fallbackBytes.length);
  return res.send(fallbackBytes);
});
