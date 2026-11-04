# Mahi Remote Control — Remaining Blockers & Platform Requirements

This document enumerates the external prerequisites, platform restrictions, and hardware verification dependencies that cannot be resolved strictly within the cloud container environment.

---

## 1. Physical Android Hardware Verification

- **Requirement**: Two physical Android handsets (one acting as Controller, one as Receiver).
- **Blocker Reason**: The cloud container lacks physical Android hardware and ADB interfaces. While automated Robolectric unit tests and Node.js integration tests pass (`5/5` and `7/7`), end-to-end hardware validation requires two physical phones.
- **Action Needed**:
  1. Transfer `dist/MahiRemote-debug.apk` to both physical Android devices.
  2. Follow the verification runbook in `docs/ANDROID_TO_ANDROID_SETUP.md`.

---

## 2. Manual User Permissions on Target Device

The Android OS strictly forbids applications from granting sensitive permissions to themselves:

1. **Accessibility Service Activation**:
   - The user must manually navigate to `Settings > Accessibility > Mahi Remote Input Service` and toggle the service to **ON**.
   - Standard Android applications cannot programmatically enable accessibility without root (Magisk) or device owner enrollment.
2. **MediaProjection Screen Capture Consent**:
   - Every screen sharing session requires the user on the receiver phone to tap **Start now** on the official Android system projection prompt.
3. **Android 12+ Overlay Restrictions**:
   - Accessibility touches cannot click "Allow" on system runtime permission dialogs (clickjacking defense).

---

## 3. Public Coturn TURN Relay for Symmetric Cellular Networks

- **Requirement**: A running Coturn instance hosted on a public IPv4 address with UDP ports `3478` and `49152–65535` exposed.
- **Blocker Reason**: When both phones connect via cellular data across symmetric carrier NATs (e.g. Grameenphone 4G to Banglalink 4G), direct STUN UDP hole-punching fails. Cloudflare Quick Tunnels proxy HTTP and WebSockets only, not raw WebRTC UDP media packets.
- **Action Needed**:
  - Deploy Coturn on a cloud VPS (AWS, DigitalOcean, Hetzner) following `docs/IP-DEPLOYMENT.md` and populate TURN credentials in `backend/.env`.

---

## 4. Windows Desktop .NET 10 Workstation Compilation

- **Requirement**: Building `windows-controller/MahiRemote.sln` requires the .NET 10 SDK and Windows Desktop Workload (WPF).
- **Blocker Reason**: The containerized Linux agent environment contains OpenJDK and Node.js, but does not provide Microsoft .NET Windows Desktop SDKs.
- **Action Needed**:
  - Windows operators can build the desktop app on their Windows workstation using `.\scripts\build-windows.ps1`.
  - Alternatively, operators can use the zero-install **Web Browser Controller** (`http://<PUBLIC_IP>:8080`) or Cloudflare Tunnel (`https://*.trycloudflare.com`) on any modern browser without compiling native desktop code.
