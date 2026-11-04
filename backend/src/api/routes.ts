import { Router, Request, Response } from 'express';
import crypto from 'crypto';
import path from 'path';
import fs from 'fs';
import { DeviceService } from '../device-management/DeviceService';
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
apiRouter.post('/pair/register-code', (_req: Request, res: Response) => {
  return res.status(410).json({
    error: 'REST pairing is disabled. Register and consume pairing codes through the authenticated WebSocket flow so device-owner consent is required.'
  });
});

apiRouter.post('/pair/verify', (_req: Request, res: Response) => {
  return res.status(410).json({
    error: 'REST pairing is disabled. Pair through WebSocket and wait for an explicit PAIR_ACCEPT from the receiver.'
  });
});

// -------------------------------------------------------------
// ICE Servers (STUN/TURN)
// -------------------------------------------------------------
apiRouter.get('/ice-servers', (_req: Request, res: Response) => {
  const iceServers: Array<Record<string, unknown>> = [
    { urls: 'stun:stun.l.google.com:19302' }
  ];

  const turnSecret = process.env.TURN_SHARED_SECRET;
  const turnDomain = process.env.TURN_DOMAIN;
  if (turnSecret && turnDomain) {
    const turnPort = process.env.TURN_PORT || '3478';
    const expiry = Math.floor(Date.now() / 1000) + 12 * 3600;
    const username = `${expiry}:mahi_session`;
    const credential = crypto.createHmac('sha1', turnSecret).update(username).digest('base64');
    iceServers.push({
      urls: [
        `turn:${turnDomain}:${turnPort}?transport=udp`,
        `turn:${turnDomain}:${turnPort}?transport=tcp`,
      ],
      username,
      credential,
    });
  }

  return res.json({ iceServers });
});

apiRouter.post('/sessions/:id/terminate', (_req: Request, res: Response) => {
  return res.status(401).json({ error: 'Authenticated session ownership is required to terminate a session' });
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
  if (!release) return res.status(404).json({ error: 'No verified installable APK release is available' });
  return res.json(release);
});

apiRouter.get('/app-updates/history', (_req: Request, res: Response) => {
  return res.json({ releases: OtaService.getHistory() });
});

apiRouter.post('/admin/app-updates/upload', (req: Request, res: Response) => {
  const authHeader = req.headers['authorization'] || (req.headers['x-admin-key'] as string);
  if (!OtaService.validateAdminKey(authHeader)) {
    return res.status(401).json({ error: 'Unauthorized: Admin API key required' });
  }

  const { versionName, versionCode, releaseNotes, isMandatory, apkBase64, signingFingerprint, packageName } = req.body;
  if (typeof apkBase64 !== 'string' || !/^(?:[A-Za-z0-9+/]{4})*(?:[A-Za-z0-9+/]{2}==|[A-Za-z0-9+/]{3}=)?$/.test(apkBase64)) {
    return res.status(400).json({ error: 'A valid base64 APK payload is required' });
  }

  const buffer = Buffer.from(apkBase64, 'base64');
  const result = OtaService.publishApkBinary({
    buffer,
    versionName,
    versionCode: Number(versionCode),
    releaseNotes,
    signingFingerprint,
    packageName,
    isMandatory: Boolean(isMandatory),
  });

  if (!result.success) return res.status(400).json({ error: result.error });
  return res.status(201).json({
    success: true,
    message: `Stored verified APK for v${versionName} (${buffer.length} bytes)`,
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
  if (!result.success) return res.status(400).json({ error: result.error });
  return res.json({ success: true, message: `Rolled back native APK to v${targetVersionName}`, release: result.release });
});

function sendApkDownload(versionName: string | undefined, res: Response) {
  const release = versionName === 'latest'
    ? OtaService.getLatestRelease('android')
    : OtaService.getHistory().find((item) => item.versionName === versionName) || null;

  if (!release) return res.status(404).json({ error: 'Requested installable APK release was not found' });
  const filePath = OtaService.getApkFilePath(release.versionName);
  if (!filePath || !fs.existsSync(filePath)) {
    return res.status(404).json({ error: 'APK binary is unavailable; no placeholder download is served' });
  }

  res.setHeader('Content-Type', 'application/vnd.android.package-archive');
  res.setHeader('Content-Disposition', `attachment; filename="mahi-remote-v${release.versionName}.apk"`);
  res.setHeader('Content-Length', fs.statSync(filePath).size);
  res.setHeader('X-Content-Type-Options', 'nosniff');
  const stream = fs.createReadStream(filePath);
  stream.on('error', () => {
    if (!res.headersSent) res.status(500).json({ error: 'APK download failed' });
    else res.destroy();
  });
  return stream.pipe(res);
}

// Keep the exact "latest" path before the version parameter route.
apiRouter.get('/app-updates/download/latest', (_req: Request, res: Response) => sendApkDownload('latest', res));
apiRouter.get('/app-updates/download/:version', (req: Request, res: Response) => sendApkDownload(req.params.version, res));
