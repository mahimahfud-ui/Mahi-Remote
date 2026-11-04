# Mahi Remote Control — Android-to-Android Feature Matrix

In strict compliance with project verification standards, feature statuses are categorized into:
- **`VERIFIED`**: Tested and confirmed working on physical Android hardware.
- **`IMPLEMENTED_NOT_TESTED`**: Fully implemented in codebase; verified via automated JVM / Robolectric tests, awaiting two-device physical verification.
- **`PARTIAL`**: Partially functional; specific platform edge cases remain.
- **`BLOCKED`**: Requires physical device connectivity, manual user permission grant, or external server configuration.
- **`NOT_IMPLEMENTED`**: Feature not present.
- **`FAILED`**: Tested and unsuccessful.

---

## 1. Core Architecture & Unified APK

| Feature | Status | Notes |
| :--- | :--- | :--- |
| **Single Unified APK for Both Phones** | **IMPLEMENTED_NOT_TESTED** | Built into `com.aistudio.mahiremote.rcvkm`. Role selectable at runtime. |
| **Role Switching (`AppRole.RECEIVER` / `CONTROLLER`)** | **IMPLEMENTED_NOT_TESTED** | Dynamic switching without restarting app; verified via unit tests. |
| **Cryptographic Identity (ECDSA & Fingerprints)** | **IMPLEMENTED_NOT_TESTED** | Generated and verified via Robolectric (`DeviceIdentityTest`). |
| **Audit Logging System** | **IMPLEMENTED_NOT_TESTED** | In-memory and persistent tamper-evident audit log for security events. |

---

## 2. Pairing & Authentication

| Feature | Status | Notes |
| :--- | :--- | :--- |
| **6-Digit Random Code Generation** | **IMPLEMENTED_NOT_TESTED** | SecureRandom, 5-minute TTL, formatted as `XXX-XXX`. Verified in tests. |
| **Single-Use Code Consumption** | **IMPLEMENTED_NOT_TESTED** | Consumed upon verification; replay attacks rejected with 401. Verified in backend tests. |
| **Expired Code Rejection** | **IMPLEMENTED_NOT_TESTED** | Verified in automated backend integration test suite. |
| **QR Code Generation & Display** | **IMPLEMENTED_NOT_TESTED** | Custom Compose QR renderer embedding device fingerprint and TTL. |
| **QR Code Scanner / Payload Input** | **IMPLEMENTED_NOT_TESTED** | Dialog supporting raw QR payload parsing and automatic connection. |
| **Explicit Receiver Approval Dialog** | **IMPLEMENTED_NOT_TESTED** | Modal displaying controller identity, location, fingerprint, and scopes. Verified in Robolectric. |
| **Trusted Device Management & Revocation** | **IMPLEMENTED_NOT_TESTED** | Persistent trusted controllers list with one-tap revocation. |

---

## 3. Screen Mirroring & Streaming

| Feature | Status | Notes |
| :--- | :--- | :--- |
| **MediaProjection Screen Capture** | **BLOCKED** | Requires physical device prompt interaction (`createScreenCaptureIntent`). |
| **Foreground Service & Notification** | **IMPLEMENTED_NOT_TESTED** | `foregroundServiceType="mediaProjection"` with ongoing "Stop Sharing" action. |
| **Hardware H.264 Encoding Pipeline** | **IMPLEMENTED_NOT_TESTED** | MediaCodec pipeline with 1080p, 720p, and 480p adaptive presets. |
| **Controller Remote Screen Viewport** | **IMPLEMENTED_NOT_TESTED** | 9:16 aspect ratio canvas with visual touch ripple rendering. |
| **`FLAG_SECURE` Privacy Masking** | **BLOCKED** | Enforced by Android OS on physical devices; verified by Android specification. |

---

## 4. Remote Control & Input Injection

| Feature | Status | Notes |
| :--- | :--- | :--- |
| **Touch Gesture Translation & Normalization** | **IMPLEMENTED_NOT_TESTED** | Scaled from canvas coordinates to 1080x1920 remote space. |
| **Single Tap Injection** | **IMPLEMENTED_NOT_TESTED** | AccessibilityService `GestureDescription` (50ms stroke). |
| **Double Tap & Long Press Injection** | **IMPLEMENTED_NOT_TESTED** | Dispatched via AccessibilityService. |
| **Swipe & Drag Gestures** | **IMPLEMENTED_NOT_TESTED** | Continuous multi-point path dispatching. |
| **System Navigation (Back, Home, Recents)** | **IMPLEMENTED_NOT_TESTED** | Dispatched via `AccessibilityService.performGlobalAction()`. |
| **System Shade & Quick Settings** | **IMPLEMENTED_NOT_TESTED** | `GLOBAL_ACTION_NOTIFICATIONS` and `GLOBAL_ACTION_QUICK_SETTINGS`. |
| **Remote Screen Lock** | **IMPLEMENTED_NOT_TESTED** | `GLOBAL_ACTION_LOCK_SCREEN` on Android 9+. |
| **Remote Text Input** | **IMPLEMENTED_NOT_TESTED** | `ACTION_SET_TEXT` on active focused editable node. |
| **Volume Adjustment** | **IMPLEMENTED_NOT_TESTED** | `AudioManager.adjustStreamVolume()`. |
| **Zero Arbitrary Shell Execution** | **IMPLEMENTED_NOT_TESTED** | Validated and blocked by `AllowlistValidator`. Verified in tests. |

---

## 5. Remote Device Management

| Feature | Status | Notes |
| :--- | :--- | :--- |
| **Remote Scoped Storage Browsing** | **IMPLEMENTED_NOT_TESTED** | Scoped directory querying over signaling channel. Verified in tests. |
| **Chunked File Transfer & SHA-256 Checksum** | **IMPLEMENTED_NOT_TESTED** | 32KB chunks with Base64 encoding. Verified in Robolectric. |
| **Installed Apps Listing** | **IMPLEMENTED_NOT_TESTED** | Package query filtered by `<queries>` intent declaration. |
| **Remote App Launch & Settings Opening** | **IMPLEMENTED_NOT_TESTED** | Dispatched via Android intent system. |
| **Remote Uninstall Request** | **IMPLEMENTED_NOT_TESTED** | Triggers Android official system confirmation dialog (`ACTION_DELETE`). |
| **Remote Hardware Telemetry (Battery/Storage)** | **IMPLEMENTED_NOT_TESTED** | Queries battery percentage, thermals, and available storage space. |

---

## 6. Connectivity & Signaling

| Feature | Status | Notes |
| :--- | :--- | :--- |
| **WebSocket Signaling Relay** | **IMPLEMENTED_NOT_TESTED** | Verified in Node.js integration tests with 7/7 passing assertions. |
| **Public IPv4 Direct Deployment** | **IMPLEMENTED_NOT_TESTED** | Verified via `scripts/start-public-access.ps1`. |
| **Cloudflare Quick Tunnel Deployment** | **IMPLEMENTED_NOT_TESTED** | Verified via `scripts/start-cloudflare-tunnel.ps1`. |
| **Coturn TURN Relay for Symmetric NAT** | **BLOCKED** | Configuration complete; requires public internet Coturn server deployment. |
| **Emergency Disconnect Killswitch** | **IMPLEMENTED_NOT_TESTED** | Instant session token revocation and stream teardown. Verified in tests. |
