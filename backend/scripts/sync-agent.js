#!/usr/bin/env node
/**
 * Mahi Remote Control — Cloud-to-Windows Live UI Sync Bridge
 *
 * Runs on the Windows laptop (or local environment) to bridge updates from
 * Google AI Studio (Cloud) to the local Windows LAN backend (192.168.1.105:8080)
 * without Git, Git push, or manual USB installation.
 *
 * Flow:
 * 1. Pulls latest signed UI release from Google AI Studio cloud URL.
 * 2. Verifies asymmetric ECDSA P-256 digital signature against pinned public key.
 * 3. Verifies SHA-256 integrity checksum of every asset.
 * 4. Ingests the updated bundle into the Windows LAN backend.
 * 5. Local backend immediately broadcasts UI_UPDATE_AVAILABLE over WebSocket to LAN devices.
 */

const http = require('http');
const https = require('https');
const fs = require('fs');
const path = require('path');
const crypto = require('crypto');

// Configuration
const CLOUD_URL = process.env.AI_STUDIO_URL || 'https://ais-dev-4r5sh7f5y743hkp4umtssh-967365360779.asia-east1.run.app';
const LOCAL_URL = process.env.LOCAL_BACKEND_URL || 'http://localhost:8080';
const ADMIN_KEY = process.env.ADMIN_API_KEY || '';
const POLL_INTERVAL_MS = parseInt(process.env.POLL_INTERVAL_MS || '5000', 10);
const ONCE = process.argv.includes('--once');

// Supply the newly rotated public key from the same secret configuration used by the publisher.
const PINNED_PUBLIC_KEY = (process.env.UI_RELEASE_PUBLIC_KEY || '').replace(/\\n/g, '\n');

function fetchJson(urlStr, options = {}) {
  return new Promise((resolve, reject) => {
    const url = new URL(urlStr);
    const client = url.protocol === 'https:' ? https : http;
    const req = client.request(url, options, (res) => {
      let data = '';
      res.on('data', (chunk) => (data += chunk));
      res.on('end', () => {
        try {
          if (res.statusCode >= 400) {
            return reject(new Error(`HTTP ${res.statusCode}: ${data}`));
          }
          resolve(JSON.parse(data));
        } catch (e) {
          reject(new Error(`Failed to parse response: ${e.message}`));
        }
      });
    });
    req.on('error', reject);
    if (options.body) req.write(options.body);
    req.end();
  });
}

function verifyEcdsaSignature(canonicalData, signatureBase64, publicKeyPem) {
  try {
    if (!publicKeyPem && !PINNED_PUBLIC_KEY) return false;
    const verify = crypto.createVerify('SHA256');
    verify.update(canonicalData);
    verify.end();
    return verify.verify(publicKeyPem || PINNED_PUBLIC_KEY, signatureBase64, 'base64');
  } catch (e) {
    console.error('[SyncBridge] Signature verification error:', e.message);
    return false;
  }
}

function computeSha256(content) {
  return crypto.createHash('sha256').update(content).digest('hex');
}

async function runSyncCycle() {
  if (!ADMIN_KEY || !PINNED_PUBLIC_KEY) {
    console.error('[SyncBridge] ADMIN_API_KEY and UI_RELEASE_PUBLIC_KEY are required. Sync disabled.');
    return false;
  }
  console.log(`[SyncBridge] Checking Google AI Studio Cloud updates at: ${CLOUD_URL}/api/v1/ui-updates/latest`);

  try {
    // 1. Fetch latest release manifest from Google AI Studio Cloud
    const cloudManifest = await fetchJson(`${CLOUD_URL}/api/v1/ui-updates/latest`);
    const { version, buildId, checksumSha256, signature } = cloudManifest;
    const manifestCore = {
      version: cloudManifest.version,
      buildId: cloudManifest.buildId,
      releaseDate: cloudManifest.releaseDate,
      minNativeAppVersion: cloudManifest.minNativeAppVersion,
      compatibleApiVersion: cloudManifest.compatibleApiVersion,
      releaseNotes: cloudManifest.releaseNotes,
      bundleUrl: cloudManifest.bundleUrl,
      assets: cloudManifest.assets,
      rollbackRef: cloudManifest.rollbackRef,
      isMandatory: cloudManifest.isMandatory,
      componentsUpdated: cloudManifest.componentsUpdated
    };
    if (!Array.isArray(cloudManifest.assets) ||
        computeSha256(JSON.stringify(manifestCore)) !== checksumSha256) {
      throw new Error('Cloud manifest checksum mismatch; refusing update');
    }

    console.log(`[SyncBridge] Found Cloud UI Release: v${version} (Build: ${buildId})`);

    // 2. Fetch local active version from Windows LAN backend
    let localVersion = '0.0.0';
    try {
      const localManifest = await fetchJson(`${LOCAL_URL}/api/v1/ui-updates/latest`);
      localVersion = localManifest.version;
      console.log(`[SyncBridge] Current Windows Local Version: v${localVersion}`);
    } catch (e) {
      console.log('[SyncBridge] Local server active version check:', e.message);
    }

    if (version === localVersion && !process.argv.includes('--force')) {
      console.log(`[SyncBridge] Windows LAN backend is already at latest version (v${version}). No sync required.`);
      return true;
    }

    // 3. Cryptographic Asymmetric ECDSA Verification
    const canonicalString = `${version}:${buildId}:${checksumSha256}`;
    const isValid = verifyEcdsaSignature(canonicalString, signature, PINNED_PUBLIC_KEY);
    if (!isValid) {
      console.error(`[SyncBridge] CRITICAL: Invalid ECDSA signature for cloud release v${version}! Rejecting update.`);
      return false;
    }
    console.log(`[SyncBridge] ✓ ECDSA P-256 signature verified against pinned key.`);

    // 4. Download bundle payload containing index.html, live.css, live.js
    console.log(`[SyncBridge] Downloading UI bundle for v${version}...`);
    const bundle = await fetchJson(`${CLOUD_URL}/api/v1/ui-releases/${version}/bundle`);

    if (!bundle.files || !bundle.files['index.html'] || !bundle.files['live.css'] || !bundle.files['live.js']) {
      throw new Error('Downloaded bundle is missing required UI assets');
    }

    // Verify each payload against the signed manifest before changing active files.
    const bundleNames = { 'live/index.html': 'index.html', 'live/live.css': 'live.css', 'live/live.js': 'live.js' };
    for (const asset of cloudManifest.assets) {
      const fileName = bundleNames[asset.path];
      if (!fileName || typeof bundle.files[fileName] !== 'string') {
        throw new Error(`Unexpected or missing UI asset: ${asset.path}`);
      }
      const bytes = Buffer.from(bundle.files[fileName], 'utf8');
      if (bytes.length !== asset.size || computeSha256(bytes) !== asset.sha256) {
        throw new Error(`UI asset integrity check failed: ${asset.path}`);
      }
    }
    if (cloudManifest.assets.length !== 3) throw new Error('Manifest must contain exactly three UI assets');

    // Stage the complete validated bundle before publishing.
    const liveDir = path.join(__dirname, '../public/live');
    const stageDir = path.join(__dirname, '../public/.live-stage');
    fs.rmSync(stageDir, { recursive: true, force: true });
    fs.mkdirSync(stageDir, { recursive: true });
    fs.writeFileSync(path.join(stageDir, 'index.html'), bundle.files['index.html'], 'utf8');
    fs.writeFileSync(path.join(stageDir, 'live.css'), bundle.files['live.css'], 'utf8');
    fs.writeFileSync(path.join(stageDir, 'live.js'), bundle.files['live.js'], 'utf8');

    // Install the verified bytes before registering the release: the backend
    // independently re-hashes the on-disk bundle during publication.
    fs.mkdirSync(liveDir, { recursive: true });
    const backupDir = path.join(__dirname, '../data/local-ui-backup');
    fs.rmSync(backupDir, { recursive: true, force: true });
    fs.mkdirSync(backupDir, { recursive: true });
    const bundleFiles = ['index.html', 'live.css', 'live.js'];
    const previouslyExisted = new Set();
    for (const file of bundleFiles) {
      const activeFile = path.join(liveDir, file);
      if (fs.existsSync(activeFile)) {
        previouslyExisted.add(file);
        fs.copyFileSync(activeFile, path.join(backupDir, file));
      }
      fs.copyFileSync(path.join(stageDir, file), activeFile);
    }

    // 6. Register release into Windows local backend via admin API
    console.log(`[SyncBridge] Publishing v${version} to Windows LAN backend (${LOCAL_URL})...`);
    const publishPayload = JSON.stringify({
      version,
      buildId,
      releaseNotes: cloudManifest.releaseNotes || 'Synchronized from Google AI Studio',
      componentsUpdated: cloudManifest.componentsUpdated || ['Dashboard', 'Telemetry', 'Controls'],
      assets: cloudManifest.assets,
      isMandatory: cloudManifest.isMandatory || false
    });

    const publishRes = await fetchJson(`${LOCAL_URL}/api/v1/admin/ui-releases`, {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
        'Content-Length': Buffer.byteLength(publishPayload),
        'X-Admin-Key': ADMIN_KEY
      },
      body: publishPayload
    });

    fs.rmSync(stageDir, { recursive: true, force: true });
    if (publishRes.success) {
      fs.rmSync(backupDir, { recursive: true, force: true });
      console.log(`================================================================`);
      console.log(`✓ SYNC COMPLETE: v${version} active on Windows LAN Backend!`);
      console.log(`✓ WebSocket broadcast emitted: All Android phones on LAN will auto-reload!`);
      console.log(`================================================================`);
      return true;
    }

    // Restore the previous active UI if the local release service rejected it.
    for (const file of bundleFiles) {
      const activeFile = path.join(liveDir, file);
      if (previouslyExisted.has(file)) {
        fs.copyFileSync(path.join(backupDir, file), activeFile);
      } else if (fs.existsSync(activeFile)) {
        fs.unlinkSync(activeFile);
      }
    }
    fs.rmSync(backupDir, { recursive: true, force: true });
    console.error(`[SyncBridge] Failed to register release on local server:`, publishRes.error);
    return false;
  } catch (err) {
    console.warn(`[SyncBridge] Sync cycle error: ${err.message}`);
    return false;
  }
}

async function main() {
  console.log('================================================================');
  console.log('   MAHI REMOTE CONTROL — CLOUD-TO-WINDOWS SYNC BRIDGE AGENT     ');
  console.log('================================================================');
  console.log(`Cloud Source:  ${CLOUD_URL}`);
  console.log(`Windows Local: ${LOCAL_URL}`);
  console.log(`Poll Interval: ${POLL_INTERVAL_MS}ms (Once mode: ${ONCE})`);

  if (ONCE) {
    const success = await runSyncCycle();
    process.exit(success ? 0 : 1);
  } else {
    await runSyncCycle();
    setInterval(runSyncCycle, POLL_INTERVAL_MS);
  }
}

if (require.main === module) {
  main();
}

module.exports = { runSyncCycle, verifyEcdsaSignature, PINNED_PUBLIC_KEY };
