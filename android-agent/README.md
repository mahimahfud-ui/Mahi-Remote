# Mahi Remote — Android Agent Architecture

This directory mirrors the modularized Android Agent components deployed in the `/app` module:

- `core/`: Base application models, dependency wiring, coroutine dispatchers, and state wrappers.
- `remote-screen/`: Implemented via `com.example.remotescreen` (`ScreenCaptureService`, `VideoPreset`, `MediaProjection` pipeline).
- `remote-input/`: Implemented via `com.example.remoteinput` (`MahiAccessibilityService`, gesture dispatchers, coordinate mapping).
- `file-transfer/`: Implemented via `com.example.filetransfer` (`FileManager`, scoped storage SAF browsing, transfer queues).
- `diagnostics/`: Implemented via `com.example.diagnostics` (`DiagnosticsManager`, telemetry listeners, automated tests, safe repair).
- `root-integration/`: Implemented via `com.example.rootintegration` (`RootDetector`, Magisk check, signed allowlist enforcement).
- `security/`: Implemented via `com.example.security` (`DeviceIdentity`, `AllowlistValidator`, `AuditLogger`).
- `pairing/`: Implemented via `com.example.pairing` (6-digit code generator, `QrCodeView`, trusted controller registry).

The live application builds from the root `/app` module via Gradle.
Run `./scripts/build-android.ps1` to compile the debug or release APK.
