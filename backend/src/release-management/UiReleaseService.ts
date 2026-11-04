import fs from 'fs';
import path from 'path';
import crypto from 'crypto';

export interface UiAssetEntry {
  path: string;
  sha256: string;
  size: number;
}

export interface UiReleaseManifest {
  version: string;
  buildId: string;
  releaseDate: string;
  minNativeAppVersion: string;
  compatibleApiVersion: string;
  releaseNotes: string;
  bundleUrl: string;
  assets: UiAssetEntry[];
  checksumSha256: string;
  signature: string;
  signingAlgorithm: string;
  rollbackRef: string | null;
  isMandatory: boolean;
  componentsUpdated: string[];
}

export class UiReleaseService {
  private static storageDir = path.join(__dirname, '../../data');
  private static keysDir = path.join(UiReleaseService.storageDir, 'keys');
  private static storageFile = path.join(UiReleaseService.storageDir, 'ui-releases.json');
  private static privateKeyFile = path.join(UiReleaseService.keysDir, 'ui-release-private-key.pem');
  private static publicKeyFile = path.join(UiReleaseService.keysDir, 'ui-release-public-key.pem');

  private static adminKey = process.env.ADMIN_API_KEY || 'mahi-admin-secret-2026';

  private static privateKeyPem: string = '';
  private static publicKeyPem: string = '';

  private static releases: Map<string, UiReleaseManifest> = new Map();
  private static activeVersion: string = '1.0.0';
  private static updateBroadcaster: ((release: UiReleaseManifest) => void) | null = null;

  static {
    this.ensureStorage();
    this.initEcdsaKeys();
    this.loadReleases();
  }

  public static setUpdateBroadcaster(fn: (release: UiReleaseManifest) => void) {
    this.updateBroadcaster = fn;
  }

  public static validateAdminKey(key: string | undefined): boolean {
    if (!key) return false;
    return key === this.adminKey || key === `Bearer ${this.adminKey}`;
  }

  private static ensureStorage() {
    if (!fs.existsSync(this.storageDir)) {
      try {
        fs.mkdirSync(this.storageDir, { recursive: true });
      } catch (e) {
        console.warn('[UiReleaseService] Could not create storage directory:', e);
      }
    }
    if (!fs.existsSync(this.keysDir)) {
      try {
        fs.mkdirSync(this.keysDir, { recursive: true });
      } catch (e) {
        console.warn('[UiReleaseService] Could not create keys directory:', e);
      }
    }
  }

  /**
   * Initializes or loads ECDSA P-256 (prime256v1) keypair for client-verifiable asymmetric signing.
   * Private key is stored exclusively on the publisher side.
   * Public key is pinned in the Android APK.
   */
  private static initEcdsaKeys() {
    try {
      if (fs.existsSync(this.privateKeyFile) && fs.existsSync(this.publicKeyFile)) {
        this.privateKeyPem = fs.readFileSync(this.privateKeyFile, 'utf-8');
        this.publicKeyPem = fs.readFileSync(this.publicKeyFile, 'utf-8');
      } else {
        console.log('[UiReleaseService] Generating new ECDSA P-256 signing keypair...');
        const { privateKey, publicKey } = crypto.generateKeyPairSync('ec', {
          namedCurve: 'prime256v1',
          publicKeyEncoding: { type: 'spki', format: 'pem' },
          privateKeyEncoding: { type: 'pkcs8', format: 'pem' },
        });

        this.privateKeyPem = privateKey;
        this.publicKeyPem = publicKey;

        fs.writeFileSync(this.privateKeyFile, privateKey, { mode: 0o600 });
        fs.writeFileSync(this.publicKeyFile, publicKey, { mode: 0o644 });
      }
    } catch (e) {
      console.warn('[UiReleaseService] Error initializing ECDSA keypair, generating fallback in memory:', e);
      const { privateKey, publicKey } = crypto.generateKeyPairSync('ec', {
        namedCurve: 'prime256v1',
        publicKeyEncoding: { type: 'spki', format: 'pem' },
        privateKeyEncoding: { type: 'pkcs8', format: 'pem' },
      });
      this.privateKeyPem = privateKey;
      this.publicKeyPem = publicKey;
    }
  }

  public static getPublicKeyPem(): string {
    return this.publicKeyPem;
  }

  private static loadReleases() {
    if (fs.existsSync(this.storageFile)) {
      try {
        const raw = fs.readFileSync(this.storageFile, 'utf-8');
        const data = JSON.parse(raw);
        if (Array.isArray(data.releases)) {
          for (const rel of data.releases) {
            if (rel.signingAlgorithm !== 'SHA256withECDSA' || !rel.signature || rel.signature.length === 64) {
              const canonical = `${rel.version}:${rel.buildId}:${rel.checksumSha256}`;
              rel.signature = this.signPayload(canonical);
              rel.signingAlgorithm = 'SHA256withECDSA';
            }
            this.releases.set(rel.version, rel);
          }
        }
        if (data.activeVersion && this.releases.has(data.activeVersion)) {
          this.activeVersion = data.activeVersion;
        }
      } catch (e) {
        console.warn('[UiReleaseService] Failed to load releases from disk, using defaults:', e);
      }
    }

    // Seed v1.0.0 if empty
    if (this.releases.size === 0) {
      const initialManifest = this.createManifestObject(
        '1.0.0',
        'MAHI-BUILD-OBSIDIAN-100',
        'Baseline Obsidian Telemetry Live Presentation layer with adaptive diagnostics, pairing, file transfer, and app manager.',
        ['Dashboard', 'Pairing', 'Diagnostics', 'Files', 'Apps', 'Settings'],
        [
          { path: 'live/index.html', sha256: this.computeSha256('Mahi Initial UI Bundle Index'), size: 4096 },
          { path: 'live/live.css', sha256: this.computeSha256('Mahi Initial CSS Bundle'), size: 8192 },
          { path: 'live/live.js', sha256: this.computeSha256('Mahi Initial JS Bundle'), size: 16384 },
        ],
        null,
        false
      );
      this.releases.set('1.0.0', initialManifest);
      this.activeVersion = '1.0.0';
      this.persist();
    }
  }

  private static persist() {
    try {
      this.ensureStorage();
      const payload = {
        activeVersion: this.activeVersion,
        updatedAt: new Date().toISOString(),
        publicKey: this.publicKeyPem,
        releases: Array.from(this.releases.values())
      };
      fs.writeFileSync(this.storageFile, JSON.stringify(payload, null, 2), 'utf-8');
    } catch (e) {
      console.warn('[UiReleaseService] Failed to persist releases to disk:', e);
    }
  }

  public static getLatestRelease(): UiReleaseManifest | null {
    return this.releases.get(this.activeVersion) || null;
  }

  public static getAllReleases(): UiReleaseManifest[] {
    return Array.from(this.releases.values()).reverse();
  }

  public static getReleaseByVersion(version: string): UiReleaseManifest | null {
    return this.releases.get(version) || null;
  }

  public static computeSha256(content: string | Buffer): string {
    return crypto.createHash('sha256').update(content).digest('hex');
  }

  /**
   * Asymmetric ECDSA-SHA256 digital signature of the canonical payload
   */
  public static signPayload(payload: string): string {
    const sign = crypto.createSign('SHA256');
    sign.update(payload);
    sign.end();
    return sign.sign(this.privateKeyPem, 'base64');
  }

  /**
   * Verifies an ECDSA-SHA256 signature against the public key
   */
  public static verifySignature(payload: string, signatureBase64: string): boolean {
    try {
      const verify = crypto.createVerify('SHA256');
      verify.update(payload);
      verify.end();
      return verify.verify(this.publicKeyPem, signatureBase64, 'base64');
    } catch (e) {
      return false;
    }
  }

  private static createManifestObject(
    version: string,
    buildId: string,
    releaseNotes: string,
    componentsUpdated: string[],
    assets: UiAssetEntry[],
    rollbackRef: string | null,
    isMandatory: boolean
  ): UiReleaseManifest {
    const manifestCore = {
      version,
      buildId,
      releaseDate: new Date().toISOString(),
      minNativeAppVersion: '1.0',
      compatibleApiVersion: 'v1',
      releaseNotes,
      bundleUrl: `/api/v1/ui-releases/${version}/bundle`,
      assets,
      rollbackRef,
      isMandatory,
      componentsUpdated,
    };

    const checksumSha256 = this.computeSha256(JSON.stringify(manifestCore));
    const canonicalString = `${version}:${buildId}:${checksumSha256}`;
    const signature = this.signPayload(canonicalString);

    return {
      ...manifestCore,
      checksumSha256,
      signature,
      signingAlgorithm: 'SHA256withECDSA',
    };
  }

  public static publishRelease(params: {
    version: string;
    buildId: string;
    releaseNotes: string;
    componentsUpdated?: string[];
    assets?: UiAssetEntry[];
    isMandatory?: boolean;
  }): { success: boolean; release?: UiReleaseManifest; error?: string } {
    const { version, buildId, releaseNotes, componentsUpdated = [], assets = [], isMandatory = false } = params;

    if (!version || !buildId || !releaseNotes) {
      return { success: false, error: 'Version, buildId, and releaseNotes are required' };
    }

    if (!/^\d+\.\d+\.\d+$/.test(version)) {
      return { success: false, error: 'Version must be semantic version format (e.g. 1.1.0)' };
    }

    const previousVersion = this.activeVersion;

    const defaultAssets: UiAssetEntry[] = assets.length > 0 ? assets : [
      { path: 'live/index.html', sha256: this.computeSha256(`index-${version}-${buildId}`), size: 5120 },
      { path: 'live/live.css', sha256: this.computeSha256(`css-${version}-${buildId}`), size: 10240 },
      { path: 'live/live.js', sha256: this.computeSha256(`js-${version}-${buildId}`), size: 20480 },
    ];

    const newManifest = this.createManifestObject(
      version,
      buildId,
      releaseNotes,
      componentsUpdated.length > 0 ? componentsUpdated : ['Dashboard', 'Controls', 'Diagnostics'],
      defaultAssets,
      previousVersion,
      isMandatory
    );

    this.releases.set(version, newManifest);
    this.activeVersion = version;
    this.persist();

    console.log(`[UiReleaseService] Published new Live UI Release: v${version} (${buildId}) with ECDSA signature`);

    // Notify connected clients over WebSocket
    if (this.updateBroadcaster) {
      this.updateBroadcaster(newManifest);
    }

    return { success: true, release: newManifest };
  }

  public static rollback(targetVersion?: string): { success: boolean; activeRelease?: UiReleaseManifest; error?: string } {
    let destVersion = targetVersion;
    if (!destVersion) {
      const current = this.releases.get(this.activeVersion);
      if (current && current.rollbackRef && this.releases.has(current.rollbackRef)) {
        destVersion = current.rollbackRef;
      }
    }

    if (!destVersion || !this.releases.has(destVersion)) {
      return { success: false, error: `Cannot rollback: target version ${destVersion || 'unknown'} not found in history` };
    }

    this.activeVersion = destVersion;
    this.persist();

    const rolledBackManifest = this.releases.get(destVersion)!;
    console.log(`[UiReleaseService] Rolled back Live UI to: v${destVersion}`);

    if (this.updateBroadcaster) {
      this.updateBroadcaster(rolledBackManifest);
    }

    return { success: true, activeRelease: rolledBackManifest };
  }
}
