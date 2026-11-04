import crypto from 'crypto';
import jwt from 'jsonwebtoken';

function getJwtSecret(): string {
  const secret = process.env.JWT_SECRET;
  if (!secret || Buffer.byteLength(secret, 'utf8') < 32) {
    throw new Error('JWT_SECRET must be configured with at least 32 bytes before issuing or verifying sessions');
  }
  return secret;
}

export interface AuthContext {
  entityId: string;
  entityType: 'DEVICE' | 'OPERATOR';
  fingerprint: string;
}

export class AuthService {
  private static rateLimitMap = new Map<string, { count: number; resetAt: number }>();

  public static generateSessionToken(ctx: AuthContext, expiresIn = '4h'): string {
    return jwt.sign(
      {
        sub: ctx.entityId,
        type: ctx.entityType,
        fp: ctx.fingerprint,
      },
      getJwtSecret(),
      { expiresIn: expiresIn as any }
    );
  }

  public static verifySessionToken(token: string): AuthContext | null {
    try {
      const decoded = jwt.verify(token, getJwtSecret()) as any;
      return {
        entityId: decoded.sub,
        entityType: decoded.type,
        fingerprint: decoded.fp,
      };
    } catch {
      return null;
    }
  }

  public static checkRateLimit(ipOrId: string, maxRequests = 60, windowMs = 60000): boolean {
    const now = Date.now();
    if (this.rateLimitMap.size > 10000) {
      for (const [key, value] of this.rateLimitMap) {
        if (now > value.resetAt) this.rateLimitMap.delete(key);
      }
    }
    const entry = this.rateLimitMap.get(ipOrId);

    if (!entry || now > entry.resetAt) {
      this.rateLimitMap.set(ipOrId, { count: 1, resetAt: now + windowMs });
      return true;
    }

    if (entry.count >= maxRequests) {
      return false;
    }

    entry.count++;
    return true;
  }

  public static verifyDeviceSignature(payload: string, signature: string, publicKey: string): boolean {
    try {
      const verifier = crypto.createVerify('SHA256');
      verifier.update(payload);
      verifier.end();
      return verifier.verify(publicKey, signature, 'base64');
    } catch {
      return false;
    }
  }
}
