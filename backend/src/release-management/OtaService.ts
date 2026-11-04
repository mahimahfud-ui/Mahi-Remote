import fs from 'fs';
import path from 'path';
import crypto from 'crypto';

export interface OtaReleaseMetadata {
  packageName: string;
  versionName: string;
  versionCode: number;
  apkDownloadUrl: string;
  sha256: string;
  fileSizeBytes: number;
  releaseDate: string;
  minSdk: number;
  targetSdk: number;
  signingFingerprint: string;
  releaseNotes: string;
  isMandatory: boolean;
  rollbackRef?: string | null;
}

const PACKAGE_NAME = 'com.aistudio.mahiremote.bqpjku';

export class OtaService {
  private static storageDir = path.join(__dirname, '../../data');
  private static apkDir = path.join(OtaService.storageDir, 'apk');
  private static storageFile = path.join(OtaService.storageDir, 'app-updates.json');
  private static adminKey = process.env.ADMIN_API_KEY || '';
  private static currentRelease: OtaReleaseMetadata | null = null;
  private static history: OtaReleaseMetadata[] = [];

  static {
    this.ensureStorage();
    this.loadRelease();
  }

  public static validateAdminKey(key: string | undefined): boolean {
    if (!key || !this.adminKey) return false;
    const supplied = key.startsWith('Bearer ') ? key.slice(7) : key;
    const a = Buffer.from(supplied);
    const b = Buffer.from(this.adminKey);
    return a.length === b.length && crypto.timingSafeEqual(a, b);
  }

  private static ensureStorage() {
    fs.mkdirSync(this.apkDir, { recursive: true });
  }

  private static isValidApkBuffer(buffer: Buffer): boolean {
    if (!buffer || buffer.length < 128) return false;
    const eocd = Buffer.from([0x50, 0x4b, 0x05, 0x06]);
    const centralDirectory = Buffer.from([0x50, 0x4b, 0x01, 0x02]);
    const searchFrom = Math.max(0, buffer.length - 65557);
    const eocdIndex = buffer.lastIndexOf(eocd);
    if (eocdIndex < searchFrom) return false;
    if (buffer.indexOf(centralDirectory, 0) < 0) return false;
    // Android APKs are ZIP archives with a root AndroidManifest.xml entry.
    return buffer.includes(Buffer.from('AndroidManifest.xml', 'ascii'));
  }

  private static isReleaseFileValid(release: OtaReleaseMetadata): boolean {
    if (!release || release.packageName !== PACKAGE_NAME ||
        !/^\\d+\\.\\d+\\.\\d+$/.test(release.versionName) ||
        !Number.isSafeInteger(release.versionCode) || release.versionCode < 1 ||
        !/^[a-f0-9]{64}$/i.test(release.sha256)) return false;

    const filePath = this.getApkFilePath(release.versionName);
    if (!filePath) return false;
    try {
      const stat = fs.statSync(filePath);
      if (stat.size !== release.fileSizeBytes) return false;
      const bytes = fs.readFileSync(filePath);
      if (!this.isValidApkBuffer(bytes)) return false;
      return crypto.createHash('sha256').update(bytes).digest('hex') === release.sha256;
    } catch {
      return false;
    }
  }

  private static loadRelease() {
    if (!fs.existsSync(this.storageFile)) return;
    try {
      const raw = fs.readFileSync(this.storageFile, 'utf-8');
      const data = JSON.parse(raw);
      const candidates: OtaReleaseMetadata[] = Array.isArray(data.history) ? data.history : [];
      if (data.currentRelease) candidates.unshift(data.currentRelease);
      const seen = new Set<string>();
      this.history = candidates.filter((release) => {
        if (!release || seen.has(release.versionName) || !this.isReleaseFileValid(release)) return false;
        seen.add(release.versionName);
        return true;
      });
      this.currentRelease = this.history[0] || null;
    } catch (e) {
      console.warn('[OtaService] Failed to load valid APK releases:', e);
      this.currentRelease = null;
      this.history = [];
    }
  }

  private static persist() {
    fs.mkdirSync(this.storageDir, { recursive: true });
    fs.writeFileSync(
      this.storageFile,
      JSON.stringify({ currentRelease: this.currentRelease, history: this.history }, null, 2),
      'utf-8'
    );
  }

  public static getLatestRelease(platform: string = 'android'): OtaReleaseMetadata | null {
    if (platform.toLowerCase() !== 'android' || !this.currentRelease) return null;
    return this.isReleaseFileValid(this.currentRelease) ? this.currentRelease : null;
  }

  public static getHistory(): OtaReleaseMetadata[] {
    return this.history.filter((release) => this.isReleaseFileValid(release));
  }

  public static getApkFilePath(versionName?: string): string | null {
    const targetVersion = versionName || this.currentRelease?.versionName;
    if (!targetVersion || !/^\\d+\\.\\d+\\.\\d+$/.test(targetVersion)) return null;
    const filePath = path.join(this.apkDir, `mahi-remote-v${targetVersion}.apk`);
    return fs.existsSync(filePath) ? filePath : null;
  }

  public static publishApkBinary(params: {
    buffer: Buffer;
    versionName: string;
    versionCode: number;
    releaseNotes: string;
    isMandatory?: boolean;
    signingFingerprint: string;
    packageName?: string;
  }): { success: boolean; release?: OtaReleaseMetadata; error?: string } {
    const {
      buffer,
      versionName,
      versionCode,
      releaseNotes,
      isMandatory = false,
      signingFingerprint,
      packageName = PACKAGE_NAME,
    } = params;

    if (!this.adminKey) return { success: false, error: 'ADMIN_API_KEY is not configured' };
    if (!buffer || buffer.length === 0 || !this.isValidApkBuffer(buffer)) {
      return { success: false, error: 'Upload is not a valid APK ZIP archive with AndroidManifest.xml' };
    }
    if (packageName !== PACKAGE_NAME) {
      return { success: false, error: `APK package must be ${PACKAGE_NAME}` };
    }
    if (!versionName || !/^\\d+\\.\\d+\\.\\d+$/.test(versionName) ||
        !Number.isSafeInteger(versionCode) || versionCode < 1 || !releaseNotes) {
      return { success: false, error: 'Valid versionName, positive integer versionCode, and releaseNotes are required' };
    }
    if (!signingFingerprint || !/^([a-f0-9]{2}:){31}[a-f0-9]{2}$/i.test(signingFingerprint)) {
      return { success: false, error: 'A verified SHA-256 APK signing-certificate fingerprint is required' };
    }
    if (this.currentRelease && versionCode <= this.currentRelease.versionCode) {
      return { success: false, error: `versionCode (${versionCode}) must be greater than current (${this.currentRelease.versionCode})` };
    }

    this.ensureStorage();
    const sha256 = crypto.createHash('sha256').update(buffer).digest('hex');
    const apkFileName = `mahi-remote-v${versionName}.apk`;
    const apkPath = path.join(this.apkDir, apkFileName);
    try {
      fs.writeFileSync(apkPath, buffer, { flag: 'wx' });
    } catch (e: any) {
      return { success: false, error: `Failed to store APK file: ${e.message}` };
    }

    const previousVersion = this.currentRelease?.versionName || null;
    const release: OtaReleaseMetadata = {
      packageName: PACKAGE_NAME,
      versionName,
      versionCode,
      apkDownloadUrl: `/api/v1/app-updates/download/${versionName}`,
      sha256,
      fileSizeBytes: buffer.length,
      releaseDate: new Date().toISOString(),
      minSdk: 26,
      targetSdk: 36,
      signingFingerprint,
      releaseNotes,
      isMandatory,
      rollbackRef: previousVersion,
    };

    this.currentRelease = release;
    this.history = [release, ...this.history.filter((item) => item.versionName !== versionName)];
    this.persist();
    console.log(`[OtaService] Published structurally valid APK v${versionName}; SHA-256 ${sha256}`);
    return { success: true, release };
  }

  public static rollback(targetVersionName: string): { success: boolean; release?: OtaReleaseMetadata; error?: string } {
    const target = this.history.find((release) =>
      release.versionName === targetVersionName && this.isReleaseFileValid(release)
    );
    if (!target) {
      return { success: false, error: `Valid APK release v${targetVersionName} was not found` };
    }
    this.currentRelease = target;
    this.persist();
    return { success: true, release: this.currentRelease };
  }
}
