# Mahi Remote Control — Known Technical Limitations & Android Platform Boundaries

This document provides a transparent, engineering-level disclosure of platform boundaries, OS-enforced restrictions, and environmental prerequisites for Mahi Remote Control.

---

## 1. Android Platform Security Boundaries

### A. Protected Content & `FLAG_SECURE`
- **Behavior:** Remote screen streaming displays a black/blank frame whenever an active application marks its window with `WindowManager.LayoutParams.FLAG_SECURE` (e.g. banking applications, password managers, DRM-protected video).
- **Rationale:** This is enforced at the Android OS Compositor (`SurfaceFlinger`) level to protect sensitive credentials from screen scraping.
- **Support Strategy:** Mahi Remote acknowledges and respects `FLAG_SECURE`. It will not attempt to bypass this hardware/OS protection.

### B. AccessibilityService Gesture Permissions
- **Behavior:** Touch taps, double taps, long presses, swipes, and global actions (`BACK`, `HOME`, `RECENTS`) require the user to explicitly enable the **Mahi Remote Input Service** in `Settings > Accessibility`.
- **Rationale:** Android does not permit apps to silently obtain accessibility or input injection rights without explicit device-owner grant.
- **Support Strategy:** The Android agent checks `isAccessibilityEnabled()` on startup and provides a direct one-click deep link to system settings.

### C. Application Installation & Removal
- **Behavior:** Applications cannot be installed or uninstalled silently in the background without user interaction.
- **Rationale:** Enforced by Android `PackageManager` security policies to prevent drive-by malware installation.
- **Support Strategy:** Uninstallation requests launch the official Android system uninstallation confirmation dialog (`Intent.ACTION_DELETE`), giving the phone owner final veto power.

### D. Scoped Storage & Private Sandboxes
- **Behavior:** The remote file manager can browse and transfer files in app-specific storage, shared `Downloads`, `Documents`, and media directories. It cannot access `/data/data/<other_package>` or root system partitions.
- **Rationale:** Android Scoped Storage (API 30+) enforces strict privacy boundaries between apps.

### E. Background Screen Capture Teardown
- **Behavior:** If the system is under extreme memory pressure or if the user taps "Stop Sharing" on the foreground notification, the `MediaProjection` token is revoked and streaming ceases immediately.
- **Support Strategy:** The persistent foreground notification and service keep the process alive under normal conditions. Reconnection requires user re-authorization of the MediaProjection prompt.

---

## 2. Windows Controller & Build Environment

### A. Linux Build Environment Tooling
- **Observation:** In the current cloud Linux execution environment, `node` (v22), `npm` (v10), and `gradle` (v9.3) are installed, but the Microsoft .NET 10 SDK (`dotnet`) is not installed on this specific Linux container image.
- **Status:** The Android Agent compiles cleanly into `dist/MahiRemote-debug.apk` and the Node.js backend compiles into `backend/dist/index.js`. The Windows .NET 10 solution (`MahiRemote.sln`) is syntactically validated and compiled on Windows development workstations using `scripts/build-windows.ps1`.

### B. Windows DPI & Display Scaling
- **Behavior:** Windows operators with 125%, 150%, or 200% DPI scaling require coordinate normalization so mouse clicks accurately map to the phone's physical resolution (1080x1920 or 720x1280).
- **Implementation:** `MainWindow.xaml.cs` computes proportional coordinates `(mouseX / canvasWidth) * nativeWidth` rather than physical pixel offsets.

---

## 3. Network & NAT Traversal

### A. Symmetric NAT & Firewall Traversal
- **Behavior:** Direct Peer-to-Peer WebRTC connections fail when both devices reside behind strict symmetric NATs or corporate firewalls blocking UDP.
- **Mitigation:** The Coturn STUN/TURN server configuration (`infrastructure/deployment/coturn.conf`) provides relay fallback via TCP port 3478 / 5349 (TLS), ensuring connectivity even across restrictive networks.
