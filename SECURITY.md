# Mahi Remote Control — Security Architecture & Threat Model

Mahi Remote Control adheres to strict zero-trust security standards designed to prevent misuse, unauthorized access, and privilege escalation.

---

## 🛡️ Core Security Architecture

### 1. Cryptographic Device Identity
- Each Android device generates an asymmetric key pair upon initialization.
- A readable public fingerprint is derived from the SHA-256 hash of the public key: e.g. `MAHI-SAM-9B7C12F0` with fingerprint `7A:B2:34:CD:...`.
- Windows controllers also generate unique ECDSA P-256 key pairs. All control packets sent across the signaling wire are cryptographically signed.

### 2. Device Pairing & Authentication
- **6-Digit Ephemeral Codes:** Generated via `java.security.SecureRandom`, valid for exactly 5 minutes, single-use only.
- **Explicit Device-Owner Consent:** When an operator submits a pairing code, the Android device presents a blocking modal dialog detailing the operator's identity, location, and requested privilege scopes. The session cannot begin without the owner tapping "Authorize Session".
- **Trusted Controller Registry:** Device owners can review and revoke any paired Windows controller with a single tap, invalidating all associated session credentials.

### 3. Strict Command Allowlist
- **Arbitrary Shell Execution is Forbidden:** Mahi Remote does NOT expose a shell terminal or execute raw shell strings (`Runtime.getRuntime().exec()`, `sh`, `bash`).
- Incoming packets are validated by `AllowlistValidator.kt`:
  - Tap coordinates must fall strictly within the physical display bounds `[0, width]` and `[0, height]`.
  - Swipe durations must be within `[50ms, 5000ms]`.
  - Global navigation actions are restricted to `BACK`, `HOME`, `RECENTS`, `NOTIFICATIONS`, and `QUICK_SETTINGS`.
  - File paths are restricted to permitted scoped storage roots. Directory traversal patterns (`..`) are rejected and logged.

### 4. Visibility & Anti-Stealth Mandate
- **No Stealth Mode:** Remote control cannot operate invisibly or in the background without user awareness.
- A persistent Android Foreground Notification is displayed with `PRIORITY_HIGH` and an explicit "Stop Sharing" action.
- The top app bar features an **Emergency Killswitch** button that immediately severs WebRTC streams, cancels active sessions, and wipes ephemeral credentials.

### 5. MediaProjection & Protected Surfaces
- MediaProjection captures user-space display contents through official Android system APIs.
- Windows or surfaces with `WindowManager.LayoutParams.FLAG_SECURE` (such as password inputs, banking applications, and DRM content) are automatically blanked by the Android OS graphics compositor, preventing remote capture.

### 6. Scoped Storage Compliance
- Mahi Remote utilizes the Android Storage Access Framework (SAF) and scoped storage.
- File operations are confined to app-specific internal storage and user-accessible Documents/Downloads directories.
- System partitions (`/system`, `/data/data`) are strictly inaccessible.
- File deletion requires explicit user confirmation.

### 7. Optional Magisk / Root Policy
- On unrooted devices, Mahi Remote operates fully with zero loss of support functionality.
- On rooted devices, root access is **OPT-IN ONLY** and requires manual activation in settings.
- Privileged operations are restricted to signed diagnostic actions (e.g., querying SELinux status or reading battery registers). Silent rooting or persistence escalation is strictly prohibited.
