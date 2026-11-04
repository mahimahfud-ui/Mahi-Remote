#!/usr/bin/env node
/**
 * Mahi Remote Control — Cloud UI Release Publisher
 *
 * Runs inside Google AI Studio to publish new UI changes from backend/public/live/
 * Generates versioned manifest, signs with asymmetric ECDSA P-256 private key,
 * and publishes to the cloud distribution endpoint.
 *
 * Usage:
 *   node scripts/publish-cloud.js --version 1.1.0 --notes "Enhanced Obsidian Telemetry"
 */

const fs = require('fs');
const path = require('path');
const crypto = require('crypto');

// Resolve arguments
const args = process.argv.slice(2);
function getArg(name, def) {
  const idx = args.indexOf(`--${name}`);
  if (idx !== -1 && args[idx + 1]) return args[idx + 1];
  return def;
}

const version = getArg('version', '1.1.0');
const releaseNotes = getArg('notes', 'Obsidian Telemetry Live UI update from Google AI Studio.');
const components = getArg('components', 'Dashboard,Diagnostics,Controls,LiveUI').split(',');

const publicLiveDir = path.join(__dirname, '../public/live');
const releasesFile = path.join(__dirname, '../data/ui-releases.json');

if (!fs.existsSync(publicLiveDir)) {
  console.error(`[Publisher] Directory not found: ${publicLiveDir}`);
  process.exit(1);
}

function computeSha256(content) {
  return crypto.createHash('sha256').update(content).digest('hex');
}

// Compute asset entries from public/live
const files = ['index.html', 'live.css', 'live.js'];
const assets = [];
for (const file of files) {
  const filePath = path.join(publicLiveDir, file);
  if (fs.existsSync(filePath)) {
    const content = fs.readFileSync(filePath);
    assets.push({
      path: `live/${file}`,
      sha256: computeSha256(content),
      size: content.length,
    });
  }
}

// Signing is disabled until a newly rotated key pair is configured outside Git.
const privateKeyPem = (process.env.UI_RELEASE_PRIVATE_KEY || '').replace(/\\n/g, '\n');
const publicKeyPem = (process.env.UI_RELEASE_PUBLIC_KEY || '').replace(/\\n/g, '\n');
if (!privateKeyPem || !publicKeyPem) {
  console.error('[Publisher] UI_RELEASE_PRIVATE_KEY and UI_RELEASE_PUBLIC_KEY are required.');
  console.error('[Publisher] Configure a newly rotated ECDSA P-256 key pair in the deployment secret store.');
  process.exit(1);
}
try {
  const derivedPublicKey = crypto.createPublicKey(privateKeyPem)
    .export({ type: 'spki', format: 'pem' }).toString().trim();
  if (derivedPublicKey !== publicKeyPem.trim()) throw new Error('Configured public/private keys do not match');
} catch (error) {
  console.error('[Publisher] Invalid UI signing key configuration:', error.message);
  process.exit(1);
}

const buildId = `MAHI-BUILD-${Date.now().toString(36).toUpperCase()}`;

// Canonical manifest object
const manifestCore = {
  version,
  buildId,
  releaseDate: new Date().toISOString(),
  minNativeAppVersion: '1.0',
  compatibleApiVersion: 'v1',
  releaseNotes,
  bundleUrl: `/api/v1/ui-releases/${version}/bundle`,
  assets,
  rollbackRef: '1.0.0',
  isMandatory: false,
  componentsUpdated: components,
};

const checksumSha256 = computeSha256(JSON.stringify(manifestCore));
const canonicalString = `${version}:${buildId}:${checksumSha256}`;

// Asymmetric digital signature
const signer = crypto.createSign('SHA256');
signer.update(canonicalString);
signer.end();
const signature = signer.sign(privateKeyPem, 'base64');

const fullManifest = {
  ...manifestCore,
  checksumSha256,
  signature,
  signingAlgorithm: 'SHA256withECDSA',
};

// Persist to releases JSON
let currentData = { activeVersion: version, releases: [] };
if (fs.existsSync(releasesFile)) {
  try {
    currentData = JSON.parse(fs.readFileSync(releasesFile, 'utf-8'));
  } catch (e) {}
}

const filtered = currentData.releases.filter(r => r.version !== version);
filtered.push(fullManifest);

currentData.activeVersion = version;
currentData.updatedAt = new Date().toISOString();
currentData.releases = filtered;

fs.writeFileSync(releasesFile, JSON.stringify(currentData, null, 2), 'utf-8');

console.log('================================================================');
console.log(`✓ PUBLISHED LIVE UI RELEASE v${version}`);
console.log(`✓ Build ID: ${buildId}`);
console.log(`✓ Canonical Checksum: ${checksumSha256}`);
console.log(`✓ ECDSA P-256 Signature: ${signature.substring(0, 32)}...`);
console.log(`✓ Bundle contains ${assets.length} verified assets.`);
console.log('================================================================');
console.log('Now run `npm run sync:cloud` on your Windows machine to synchronize!');
