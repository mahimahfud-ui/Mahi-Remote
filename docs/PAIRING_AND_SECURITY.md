# Mahi Remote Control — Pairing & Security Specification

This document details the cryptographic identity, mutual authentication, pairing protocols, and zero-trust security architecture of Mahi Remote Control.

---

## 1. Cryptographic Device Identity

Every device running Mahi Remote Control generates a unique, persistent hardware-bound identity upon first launch (`DeviceIdentity.kt`):

- **Key Pair**: Generated using **ECDSA (P-256)** or **Ed25519** stored securely within Android Keystore (hardware-backed TEE / StrongBox where available).
- **Device ID**: Prefixed format `MAHI-<RANDOM_HEX_8>` (e.g. `MAHI-7F89B21C`).
- **Fingerprint**: SHA-256 digest of the public key representation, formatted as 16 colon-delimited hex octets (e.g. `8E:4A:91:D2:11:F9:6C:55:A3:40:99:BC:14:8A:EF:29`).

---

## 2. Pairing Code Protocol

### Entropy & Generation
- Generated via `java.security.SecureRandom`.
- Integer range: $100,000 \le n \le 999,999$.
- Formatted as a hyphenated 6-digit string: `XXX-XXX` (e.g. `729-415`).

### Expiration & Single-Use Consumption
- **TTL**: Each code has a strict 5-minute time-to-live ($T_0 + 300\text{s}$).
- **Single-Use Enforcement**: When a controller verifies a code via `/api/v1/pair/verify` or WebSocket `PAIR_BY_CODE`, the backend matches the device ID, deletes the pairing code from volatile memory, and prevents replay attacks. Subsequent attempts with the same code return `401 Unauthorized`.
- **Rate Limiting**: IP and fingerprint-based rate limiting caps verification attempts to 5 per minute to prevent brute-force attacks.

### QR Code Payload Schema
When generating a QR code on Phone B, the payload is serialized as JSON:
```json
{
  "deviceId": "MAHI-NOAKHALI-01",
  "deviceName": "Pixel 8 Pro",
  "fingerprint": "7A:B3:99:C2:54:E1:88:20:D1:66:33:F0:91:AA:BB:CC",
  "pairingCode": "729-415",
  "expiresAt": 1798939200000,
  "v": 1
}
```

---

## 3. Mutual Authentication & Session Approval Flow

```
Phone A (Controller)                 Signaling Server                 Phone B (Receiver)
         │                                   │                                 │
         │  1. Connect with code/QR          │                                 │
         │ ────────────────────────────────> │                                 │
         │                                   │  2. Forward PAIR_REQUEST        │
         │                                   │ ──────────────────────────────> │
         │                                   │                                 │
         │                                   │                    [3. Prompt User]
         │                                   │                    "Authorize Remote Support?"
         │                                   │                    Operator: Pixel 8 Pro
         │                                   │                    FP: AA:BB:CC:...
         │                                   │                                 │
         │                                   │  4. Send PAIR_ACCEPT (Signed)   │
         │                                   │ <────────────────────────────── │
         │  5. Forward PAIR_ACCEPT           │                                 │
         │ <──────────────────────────────── │                                 │
         │                                                                     │
         │ ══════════════ WebRTC Encrypted Media & DataChannel ═══════════════ │
```

### Mandatory Explicit Consent
Under no circumstance can a remote session be initiated silently. The receiver application displays `OperatorApprovalDialog`, requiring manual biometric or touch confirmation on Phone B.

---

## 4. Zero-Trust Command Allowlist (`AllowlistValidator.kt`)

Every incoming gesture, navigation, and file command is validated against strict constraints before being dispatched to Android APIs:

1. **Touch Bounds**: Coordinates must satisfy $0 \le x \le \text{screenWidth}$ and $0 \le y \le \text{screenHeight}$.
2. **Gesture Timing**: Swipe and drag durations must fall within $50\text{ms} \le t \le 5000\text{ms}$.
3. **Global Actions**: Restricted to authorized system enums: `GLOBAL_ACTION_BACK`, `GLOBAL_ACTION_HOME`, `GLOBAL_ACTION_RECENTS`, `GLOBAL_ACTION_NOTIFICATIONS`, `GLOBAL_ACTION_QUICK_SETTINGS`, `GLOBAL_ACTION_LOCK_SCREEN`.
4. **Shell Execution Block**: All arbitrary shell strings (`sh`, `su`, `exec`, `eval`, piped commands) are rejected and logged to the tamper-evident audit log as high-severity security incidents.

---

## 5. Privacy & Platform Protection (`FLAG_SECURE`)

- Android's native `FLAG_SECURE` window policy is strictly respected.
- When the user navigates into banking applications, password managers, or secure credential dialogs, the Android OS automatically masks the video frame with solid black.
- Accessibility inputs targeted at protected credential windows cannot read password text.

---

## 6. Emergency Killswitch & Session Revocation

1. **Receiver Killswitch**: Phone B maintains a non-dismissible foreground notification with an immediate "Stop Sharing" intent. Additionally, the dashboard features a red emergency disconnect killswitch.
2. **Controller Disconnect**: Phone A can sever the session at any time.
3. **Immediate Teardown**:
   - `ScreenCaptureService.stop()` terminates the VirtualDisplay and MediaProjection session.
   - Session tokens are deleted from both devices and the backend.
   - WebRTC peer connection and data channels are closed.
