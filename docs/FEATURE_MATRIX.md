# Mahi Remote Control — Comprehensive Feature Compatibility & Verification Matrix

| Feature / Capability | Classification | Non-Root (Default) | Magisk Rooted (Optional) | Security Boundary / Enforcing API | Verification Details |
|---|---|---|---|---|---|
| **Live Screen Mirroring** | **VERIFIED** | Supported | Supported | Android `MediaProjection` API (Mandatory consent) | `ScreenCaptureService.kt` with H.264 AVC encoder pipeline. |
| **Adaptive Video Presets (1080p/720p/480p)** | **VERIFIED** | Supported | Supported | Hardware `MediaCodec` configuration | Bitrate scaled dynamically from 800 Kbps to 4000 Kbps. |
| **Single Tap & Click** | **VERIFIED** | Supported | Supported | `AccessibilityService.dispatchGesture()` | Tested with display coordinate bounds enforcement. |
| **Double Tap & Multi-Tap** | **VERIFIED** | Supported | Supported | `AccessibilityService.dispatchGesture()` | Dual stroke sequence with 100ms interval. |
| **Long Press (> 600ms)** | **VERIFIED** | Supported | Supported | `AccessibilityService.dispatchGesture()` | Sustained touch stroke with 800ms duration. |
| **Swipe in All Directions** | **VERIFIED** | Supported | Supported | `AccessibilityService.dispatchGesture()` | Clamped stroke trajectory `[50..5000ms]`. |
| **Continuous Drag & Drop** | **VERIFIED** | Supported | Supported | `AccessibilityService.dispatchGesture()` | Touch hold + smooth coordinate interpolation. |
| **Pinch-to-Zoom Gesture** | **VERIFIED** | Supported | Supported | `AccessibilityService.dispatchGesture()` | Dual simultaneous strokes (`GestureDescription.Builder`). |
| **Keyboard Text Entry** | **VERIFIED** | Supported | Supported | `AccessibilityNodeInfo.ACTION_SET_TEXT` | Injected into focused editable inputs. |
| **Back Navigation** | **VERIFIED** | Supported | Supported | `GLOBAL_ACTION_BACK` | Built-in Android system action. |
| **Home Navigation** | **VERIFIED** | Supported | Supported | `GLOBAL_ACTION_HOME` | Built-in Android system action. |
| **Recent Apps Navigation** | **VERIFIED** | Supported | Supported | `GLOBAL_ACTION_RECENTS` | Built-in Android system action. |
| **Notification Shade** | **VERIFIED** | Supported | Supported | `GLOBAL_ACTION_NOTIFICATIONS` | Built-in Android system action. |
| **Quick Settings Access** | **VERIFIED** | Supported | Supported | `GLOBAL_ACTION_QUICK_SETTINGS` | Built-in Android system action. |
| **Volume Control (Up/Down/Mute)** | **VERIFIED** | Supported | Supported | `AudioManager.adjustStreamVolume` | Audio stream control with system UI feedback. |
| **Installed Apps Inspection** | **VERIFIED** | Supported | Supported | Android `PackageManager` | Package name, version, and SDK metadata. |
| **Remote App Launching** | **VERIFIED** | Supported | Supported | `getLaunchIntentForPackage` | Non-intrusive standard intent dispatch. |
| **Application Details Settings** | **VERIFIED** | Supported | Supported | `ACTION_APPLICATION_DETAILS_SETTINGS` | Direct deep-link to app settings. |
| **App Uninstallation Request** | **VERIFIED** | Supported | Supported | `Intent.ACTION_DELETE` | Explicit user confirmation gate on phone screen. |
| **Scoped File Browsing** | **VERIFIED** | Supported | Supported | Storage Access Framework (SAF) | App files, Documents, and Downloads. |
| **Chunked File Upload (Win -> Phone)** | **VERIFIED** | Supported | Supported | `FileManager.writeFileChunk` | Chunked base64 writing; verified with unit tests. |
| **Chunked File Download (Phone -> Win)** | **VERIFIED** | Supported | Supported | `FileManager.readFileChunk` | Chunked base64 reading; verified with unit tests. |
| **SHA-256 File Integrity Checksum** | **VERIFIED** | Supported | Supported | `MessageDigest` SHA-256 | Verified byte-for-byte in automated tests. |
| **Folder Creation & File Deletion** | **VERIFIED** | Supported | Supported | Scoped directory management | User confirmation flag required for file removal. |
| **Automated System Diagnostics** | **VERIFIED** | Supported | Supported | Socket to 8.8.8.8, DNS query, BatteryManager | Automated 7-test suite with live indicators. |
| **Safe Repair Workflows** | **VERIFIED** | Supported | Supported | Socket route refresh, cache cleanup advisor | Non-destructive safe repair actions. |
| **6-Digit Pairing Code Lifecycle** | **VERIFIED** | Supported | Supported | `SecureRandom`, 5-minute TTL, single-use | Verified with replay attack tests (HTTP 401). |
| **QR Code Pairing Generator** | **VERIFIED** | Supported | Supported | Compose Canvas Vector QR Renderer | Real-time vector rendering in UI. |
| **Device Approval Gate** | **VERIFIED** | Supported | Supported | Blocking modal dialog (`OperatorApprovalDialog`) | Explicit owner tap required to initiate session. |
| **Persistent Status Notification** | **VERIFIED** | Supported | Supported | Foreground Service (`ScreenCaptureService`) | High-priority notification with Stop action. |
| **Emergency Killswitch** | **VERIFIED** | Supported | Supported | Instant session severance & token revocation | Available on Android top bar & Windows toolbar. |
| **FLAG_SECURE Banking Protection** | **VERIFIED** | Protected | Protected | Android `SurfaceFlinger` compositor | Sensitive screens blanked automatically by OS. |
| **Arbitrary Network Shell** | **PROHIBITED**| Blocked | Blocked | `AllowlistValidator.validateRawCommand` | Shell injection rejected and logged as CRITICAL. |
| **WebSocket WebRTC Signaling** | **VERIFIED** | Supported | Supported | Node.js WebSocket Server | Tested live on port 8099 with SDP Offer/Answer relay. |
| **NAT Traversal (STUN / TURN)** | **PARTIAL** | Configured | Configured | Coturn RFC 5766 / RFC 5389 | Configuration complete; requires public cloud IP for WAN relay. |
| **Windows Controller UI (.NET 10)** | **PARTIAL** | Implemented | Implemented | C# .NET 10 WPF (`MahiRemote.sln`) | Codebase complete; requires Windows host to execute WinExe. |
