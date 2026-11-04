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

  private static adminKey = process.env.ADMIN_API_KEY || '';

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
    if (!key || !this.adminKey) return false;
    const supplied = key.startsWith('Bearer ') ? key.slice(7) : key;
    const a = Buffer.from(supplied);
    const b = Buffer.from(this.adminKey);
    return a.length === b.length && crypto.timingSafeEqual(a, b);
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
      const privateKey = (process.env.UI_RELEASE_PRIVATE_KEY || '').replace(/\\n/g, '\n');
      const configuredPublicKey = (process.env.UI_RELEASE_PUBLIC_KEY || '').replace(/\\n/g, '\n');
      if (!privateKey || !configuredPublicKey) {
        console.error('[UiReleaseService] UI release signing is disabled: configure UI_RELEASE_PRIVATE_KEY and UI_RELEASE_PUBLIC_KEY.');
        return;
      }

      const derivedPublicKey = crypto.createPublicKey(privateKey)
        .export({ type: 'spki', format: 'pem' }).toString().trim();
      if (derivedPublicKey !== configuredPublicKey.trim()) {
        throw new Error('UI release public/private key pair does not match');
      }

      this.privateKeyPem = privateKey;
      this.publicKeyPem = configuredPublicKey;
    } catch (e) {
      this.privateKeyPem = '';
      this.publicKeyPem = '';
      console.error('[UiReleaseService] UI release signing disabled:', e);
    }
  }

  public static getPublicKeyPem(): string {
    return this.publicKeyPem;
  }

  private static loadReleases() {
    if (!this.publicKeyPem) return;
    if (!fs.existsSync(this.storageFile)) return;

    try {
      const raw = fs.readFileSync(this.storageFile, 'utf-8');
      const data = JSON.parse(raw);
      if (!Array.isArray(data.releases)) return;

      for (const rel of data.releases) {
        if (!rel || rel.signingAlgorithm !== 'SHA256withECDSA' ||
            typeof rel.version !== 'string' || typeof rel.buildId !== 'string' ||
            typeof rel.checksumSha256 !== 'string' || typeof rel.signature !== 'string') {
          continue;
        }
        const canonical = `${rel.version}:${rel.buildId}:${rel.checksumSha256}`;
        if (this.verifySignature(canonical, rel.signature)) {
          this.releases.set(rel.version, rel);
        }
      }

      if (data.activeVersion && this.releases.has(data.activeVersion)) {
        this.activeVersion = data.activeVersion;
      } else {
        this.activeVersion = Array.from(this.releases.keys()).at(-1) || '';
      }
    } catch (e) {
      console.warn('[UiReleaseService] Failed to load verified releases:', e);
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
    if (!this.privateKeyPem || !this.publicKeyPem) return null;
    return this.releases.get(this.activeVersion) || null;
  }

  public static getAllReleases(): UiReleaseManifest[] {
    if (!this.publicKeyPem) return [];
    return Array.from(this.releases.values()).reverse();
  }

  public static getReleaseByVersion(version: string): UiReleaseManifest | null {
    if (!this.publicKeyPem) return null;
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

    if (!this.privateKeyPem || !this.publicKeyPem) {
      return { success: false, error: 'UI release publishing is disabled until a rotated ECDSA key pair is configured' };
    }
    if (!version || !buildId || !releaseNotes) {
      return { success: false, error: 'Version, buildId, and releaseNotes are required' };
    }
    if (!/^\\d+\\.\\d+\\.\\d+$/.test(version)) {
      return { success: false, error: 'Version must be semantic version format (e.g. 1.1.0)' };
    }

    const requiredPaths = ['live/index.html', 'live/live.css', 'live/live.js'];
    const assetPaths = assets.map(asset => asset.path).sort();
    if (assets.length !== requiredPaths.length ||
        requiredPaths.slice().sort().some((path, index) => path !== assetPaths[index]) ||
        assets.some(asset => !/^[a-f0-9]{64}$/i.test(asset.sha256) ||
          !Number.isSafeInteger(asset.size) || asset.size <= 0)) {
      return { success: false, error: 'A release must include valid SHA-256 and size metadata for index.html, live.css, and live.js' };
    }

    const previousVersion = this.activeVersion;

    const newManifest = this.createManifestObject(
      version,
      buildId,
      releaseNotes,
      componentsUpdated.length > 0 ? componentsUpdated : ['Dashboard', 'Controls', 'Diagnostics'],
      assets,
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
    if (!this.privateKeyPem || !this.publicKeyPem) {
      return { success: false, error: 'UI release rollback is disabled until a rotated ECDSA key pair is configured' };
    }
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
