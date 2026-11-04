#!/usr/bin/env node
/**
 * Generate a fresh ECDSA P-256 pair for Live UI release signing.
 * Key material is written only under backend/data/keys, which is gitignored.
 */
const fs = require('fs');
const path = require('path');
const crypto = require('crypto');

const keyDir = path.join(__dirname, '../data/keys');
fs.mkdirSync(keyDir, { recursive: true });

const { privateKey, publicKey } = crypto.generateKeyPairSync('ec', {
  namedCurve: 'prime256v1',
  publicKeyEncoding: { type: 'spki', format: 'pem' },
  privateKeyEncoding: { type: 'pkcs8', format: 'pem' },
});

const privatePath = path.join(keyDir, 'ui-release-private-key.pem');
const publicPath = path.join(keyDir, 'ui-release-public-key.pem');
fs.writeFileSync(privatePath, privateKey, { mode: 0o600, flag: 'wx' });
fs.writeFileSync(publicPath, publicKey, { mode: 0o644, flag: 'wx' });

const publicDer = crypto.createPublicKey(publicKey).export({ type: 'spki', format: 'der' });
console.log('New Live UI ECDSA P-256 key pair created locally.');
console.log(`Private key: ${privatePath}`);
console.log(`Public key:  ${publicPath}`);
console.log('');
console.log('Set UI_RELEASE_PRIVATE_KEY to the private PEM with escaped \\n newlines.');
console.log('Set UI_RELEASE_PUBLIC_KEY to the public PEM with escaped \\n newlines.');
console.log('Set MAHI_UI_RELEASE_PUBLIC_KEY_BASE64 for Android builds to:');
console.log(publicDer.toString('base64'));
console.log('');
console.log('Never commit or upload the private key to the repository.');
