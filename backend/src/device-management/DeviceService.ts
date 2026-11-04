import { Pool } from 'pg';

export interface RegisteredDevice {
  deviceId: string;
  deviceName: string;
  fingerprint: string;
  isOnline: boolean;
  lastSeen: number;
}

export interface PairingCodeRecord {
  code: string;
  deviceId: string;
  expiresAt: number;
}

export class DeviceService {
  private static devices = new Map<string, RegisteredDevice>();
  private static pairingCodes = new Map<string, PairingCodeRecord>();
  private static activeSessions = new Map<string, { deviceId: string; operatorFingerprint: string; startedAt: number }>();
  
  private static dbPool: Pool | null = null;

  static {
    if (process.env.DATABASE_URL) {
      try {
        this.dbPool = new Pool({
          connectionString: process.env.DATABASE_URL,
          ssl: process.env.NODE_ENV === 'production' ? { rejectUnauthorized: false } : undefined,
          max: 10,
          idleTimeoutMillis: 30000,
        });
        console.log('[DeviceService] PostgreSQL pool initialized successfully');
      } catch (e) {
        console.warn('[DeviceService] PostgreSQL pool init failed, falling back to in-memory adapter:', e);
      }
    }
  }

  public static async registerDevice(deviceId: string, deviceName: string, fingerprint: string): Promise<RegisteredDevice> {
    const dev: RegisteredDevice = {
      deviceId,
      deviceName,
      fingerprint,
      isOnline: true,
      lastSeen: Date.now(),
    };
    this.devices.set(deviceId, dev);

    if (this.dbPool) {
      try {
        await this.dbPool.query(
          `INSERT INTO devices (device_id, device_name, fingerprint, public_key, android_version, manufacturer, model, is_online, last_seen_at)
           VALUES ($1, $2, $3, $4, '14.0', 'Android', 'Physical Device', true, NOW())
           ON CONFLICT (device_id) DO UPDATE SET is_online = true, last_seen_at = NOW()`,
          [deviceId, deviceName, fingerprint, fingerprint]
        );
      } catch (e) {
        console.warn('[DeviceService] DB error in registerDevice:', e);
      }
    }

    return dev;
  }

  public static async storePairingCode(deviceId: string, code: string, expiresAt: number): Promise<void> {
    this.pairingCodes.set(code, { code, deviceId, expiresAt });

    if (this.dbPool) {
      try {
        await this.dbPool.query(
          `INSERT INTO pairing_codes (code, expires_at, is_consumed)
           VALUES ($1, TO_TIMESTAMP($2 / 1000.0), false)`,
          [code, expiresAt]
        );
      } catch (e) {
        console.warn('[DeviceService] DB error in storePairingCode:', e);
      }
    }
  }

  public static validateAndConsumePairingCode(code: string): string | null {
    const record = this.pairingCodes.get(code);
    if (!record) return null;
    if (Date.now() > record.expiresAt) {
      this.pairingCodes.delete(code);
      return null;
    }
    this.pairingCodes.delete(code);

    if (this.dbPool) {
      this.dbPool.query(
        `UPDATE pairing_codes SET is_consumed = true WHERE code = $1`,
        [code]
      ).catch(e => console.warn('[DeviceService] DB error in validateAndConsumePairingCode:', e));
    }

    return record.deviceId;
  }

  public static getDevice(deviceId: string): RegisteredDevice | undefined {
    return this.devices.get(deviceId);
  }

  public static listDevices(): RegisteredDevice[] {
    return Array.from(this.devices.values());
  }

  public static startSession(sessionId: string, deviceId: string, operatorFingerprint: string): void {
    this.activeSessions.set(sessionId, { deviceId, operatorFingerprint, startedAt: Date.now() });
    if (this.dbPool) {
      this.dbPool.query(
        `INSERT INTO support_sessions (session_id, controller_fingerprint, operator_name, status)
         VALUES ($1, $2, 'Mahi Operator', 'ACTIVE')`,
        [sessionId, operatorFingerprint]
      ).catch(e => console.warn('[DeviceService] DB error in startSession:', e));
    }
  }

  public static endSession(sessionId: string): boolean {
    if (this.dbPool) {
      this.dbPool.query(
        `UPDATE support_sessions SET status = 'TERMINATED', ended_at = NOW() WHERE session_id = $1`,
        [sessionId]
      ).catch(e => console.warn('[DeviceService] DB error in endSession:', e));
    }
    return this.activeSessions.delete(sessionId);
  }
}
