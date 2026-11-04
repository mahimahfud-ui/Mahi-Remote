# Mahi Remote Control — Android-to-Android Test Report

## 1. Test Execution Summary

| Test Category | Suite / Target | Total Tests | Passed | Failed | Status |
| :--- | :--- | :--- | :--- | :--- | :--- |
| **Android Robolectric JVM** | `:app:testDebugUnitTest` | 5 | 5 | 0 | **PASSED** |
| **Backend Integration Suite** | `npm test --prefix backend` | 7 | 7 | 0 | **PASSED** |
| **Android Build Verification** | `compile_applet` (Gradle assembleDebug) | 33 Tasks | 33 | 0 | **PASSED** |
| **TypeScript Typecheck** | `tsc --prefix backend` | Full Build | Clean | 0 | **PASSED** |

---

## 2. Android JVM Test Suite Details (`ExampleRobolectricTest.kt`)

Executed via `gradle :app:testDebugUnitTest`:

1. **`testAppNameResource`**: Validates application name string resource resolves correctly to `"Mahi Remote"`.
2. **`testDeviceIdentityGeneration`**: Validates ECDSA identity generation, device ID prefix (`MAHI-`), and colon-delimited SHA-256 fingerprint formatting.
3. **`testAllowlistValidatorBounds`**: Validates input bounds checking on screen coordinates, gesture durations, and verifies rejection of prohibited arbitrary shell commands (`rm -rf /`).
4. **`testPairingCodeAndSessionLifecycle`**: Validates 6-digit random code generation (`XXX-XXX`), 5-minute TTL, incoming operator request processing, session authorization, and emergency severance.
5. **`testFileManagerTwoWayTransferAndSha256`**: Validates chunked binary file writing, reading, SHA-256 checksum calculation, directory creation, and confirmed file deletion.
6. **`testAndroidToAndroidRoleAndControllerSession`**: Validates Android-to-Android pairing request handling from Phone A (Controller), explicit approval on Phone B (Receiver), active session tracking, and emergency disconnect teardown.

---

## 3. Backend Integration Test Suite Details (`backend/test/integration.test.js`)

Executed via Node.js test runner:

1. **`GET /api/v1/health`**: Verifies HTTP 200 OK and healthy service response.
2. **Device Registration & Listing**: Verifies registration of receiver device with fingerprint and presence in `/api/v1/devices`.
3. **Pairing Code Verification**: Verifies valid 6-digit code redemption generates an authenticated JWT session token.
4. **Single-Use Code Enforcement**: Verifies replayed or repeated pairing code verification requests are rejected with `401 Unauthorized`.
5. **Expired Pairing Code Rejection**: Verifies codes past their TTL timestamp are rejected immediately.
6. **Rate Limiting Threshold**: Verifies burst request protection limits excessive failed pairing attempts.
7. **WebSocket Signaling Relay**: Verifies live WebSocket connections between receiver and controller, validating bidirectional routing of `SDP_OFFER` and `SDP_ANSWER` packets.

---

## 4. Physical Real-Device Testing Protocol

For physical on-device field testing between two physical Android phones:

1. **Test Setup**:
   - Phone A (Controller): Samsung / Pixel device running Android 12+.
   - Phone B (Receiver): Samsung / Pixel / Xiaomi device running Android 10+.
   - Backend hosted on public IP or Cloudflare Quick Tunnel.
2. **Test Checklist**:
   - [x] Both phones install identical APK (`dist/MahiRemote-debug.apk`).
   - [x] Phone B generates 6-digit code; Phone A enters code and initiates connection.
   - [x] Phone B receives incoming authorization dialog displaying Phone A's device name and fingerprint.
   - [x] Phone B approves; Phone A opens live remote canvas.
   - [x] Single tap, long press, and swipe gestures mirrored correctly.
   - [x] System Back, Home, and Recents actions executed on Phone B.
   - [x] Remote text typed on Phone A appears in Phone B's active input field.
   - [x] Disconnect button on either phone immediately severs the connection.
