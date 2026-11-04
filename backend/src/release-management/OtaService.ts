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

export class OtaService {
  private static storageDir = path.join(__dirname, '../../data');
  private static apkDir = path.join(OtaService.storageDir, 'apk');
  private static storageFile = path.join(OtaService.storageDir, 'app-updates.json');
  private static adminKey = process.env.ADMIN_API_KEY || 'mahi-admin-secret-2026';

  private static currentRelease: OtaReleaseMetadata = {
    packageName: 'com.aistudio.mahiremote.bqpjku',
    versionName: '1.0.0',
    versionCode: 1,
    apkDownloadUrl: '/api/v1/app-updates/download/latest',
    sha256: '9f86d081884c7d659a2feaa0c55ad015a3bf4f1b2b0b822cd15d6c15b0f00a08',
    fileSizeBytes: 24500000,
    releaseDate: new Date().toISOString(),
    minSdk: 26,
    targetSdk: 36,
    signingFingerprint: '14:2B:6F:89:D2:C1:4E:99:A5:12:33:77:FF:88:99:00',
    releaseNotes: 'Initial production native core with WebRTC streaming, MediaProjection foreground service, and Accessibility input.',
    isMandatory: false,
  };

  private static history: OtaReleaseMetadata[] = [];

  static {
    this.ensureStorage();
    this.loadRelease();
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
        console.warn('[OtaService] Could not create storage directory:', e);
      }
    }
    if (!fs.existsSync(this.apkDir)) {
      try {
        fs.mkdirSync(this.apkDir, { recursive: true });
      } catch (e) {
        console.warn('[OtaService] Could not create apk directory:', e);
      }
    }
  }

  private static loadRelease() {
    if (fs.existsSync(this.storageFile)) {
      try {
        const raw = fs.readFileSync(this.storageFile, 'utf-8');
        const data = JSON.parse(raw);
        if (data.currentRelease && data.currentRelease.packageName) {
          this.currentRelease = data.currentRelease;
        }
        if (Array.isArray(data.history)) {
          this.history = data.history;
        }
      } catch (e) {
        console.warn('[OtaService] Failed to load OTA metadata, using default:', e);
      }
    }
    if (this.history.length === 0) {
      this.history.push(this.currentRelease);
    }
  }

  private static persist() {
    try {
      this.ensureStorage();
      fs.writeFileSync(
        this.storageFile,
        JSON.stringify({ currentRelease: this.currentRelease, history: this.history }, null, 2),
        'utf-8'
      );
    } catch (e) {
      console.warn('[OtaService] Failed to persist OTA release:', e);
    }
  }

  public static getLatestRelease(platform: string = 'android'): OtaReleaseMetadata {
    return this.currentRelease;
  }

  public static getHistory(): OtaReleaseMetadata[] {
    return this.history;
  }

  public static getApkFilePath(versionName?: string): string | null {
    const targetVersion = versionName || this.currentRelease.versionName;
    const filePath = path.join(this.apkDir, `mahi-remote-v${targetVersion}.apk`);
    return fs.existsSync(filePath) ? filePath : null;
  }

  public static publishApkBinary(params: {
    buffer: Buffer;
    versionName: string;
    versionCode: number;
    releaseNotes: string;
    isMandatory?: boolean;
    signingFingerprint?: string;
  }): { success: boolean; release?: OtaReleaseMetadata; error?: string } {
    const { buffer, versionName, versionCode, releaseNotes, isMandatory = false } = params;

    if (!buffer || buffer.length === 0) {
      return { success: false, error: 'Empty APK buffer received' };
    }

    if (!versionName || !versionCode || !releaseNotes) {
      return { success: false, error: 'versionName, versionCode, and releaseNotes are required' };
    }

    if (versionCode <= this.currentRelease.versionCode) {
      return { success: false, error: `versionCode (${versionCode}) must be greater than current (${this.currentRelease.versionCode})` };
    }

    this.ensureStorage();
    const sha256 = crypto.createHash('sha256').update(buffer).digest('hex');
    const apkFileName = `mahi-remote-v${versionName}.apk`;
    const apkPath = path.join(this.apkDir, apkFileName);

    try {
      fs.writeFileSync(apkPath, buffer);
    } catch (e: any) {
      return { success: false, error: `Failed to store APK file: ${e.message}` };
    }

    const previousVersion = this.currentRelease.versionName;

    this.currentRelease = {
      packageName: 'com.aistudio.mahiremote.bqpjku',
      versionName,
      versionCode,
      apkDownloadUrl: `/api/v1/app-updates/download/${versionName}`,
      sha256,
      fileSizeBytes: buffer.length,
      releaseDate: new Date().toISOString(),
      minSdk: 26,
      targetSdk: 36,
      signingFingerprint: params.signingFingerprint || '14:2B:6F:89:D2:C1:4E:99:A5:12:33:77:FF:88:99:00',
      releaseNotes,
      isMandatory,
      rollbackRef: previousVersion
    };

    this.history.unshift(this.currentRelease);
    this.persist();

    console.log(`[OtaService] Stored and published Native APK: v${versionName} (code: ${versionCode}, size: ${buffer.length} bytes, SHA-256: ${sha256})`);
    return { success: true, release: this.currentRelease };
  }

  public static rollback(targetVersionName: string): { success: boolean; release?: OtaReleaseMetadata; error?: string } {
    const target = this.history.find(h => h.versionName === targetVersionName);
    if (!target) {
      return { success: false, error: `Release v${targetVersionName} not found in history` };
    }

    this.currentRelease = target;
    this.persist();
    console.log(`[OtaService] Rolled back native APK to v${targetVersionName}`);
    return { success: true, release: this.currentRelease };
  }
}
