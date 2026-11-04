# Mahi Remote Control

> Production-grade, secure remote Android device support and control platform connecting Windows desktop operators to Android phones over the internet.

---

## 🌟 Overview

**Mahi Remote Control** allows an authorized Windows desktop operator (e.g. located in **Sylhet, Bangladesh**) to securely view, diagnose, and troubleshoot an Android phone (e.g. located in **Noakhali, Bangladesh**) across different cellular or Wi-Fi networks without physical USB connections, shared local networks, or rooting requirements.

### Key Capabilities
- **Non-Root by Design:** Works immediately on stock, unrooted Android 8.0+ devices using official **MediaProjection** and **AccessibilityService** APIs.
- **WebRTC Screen Mirroring:** Hardware-accelerated H.264 video pipeline with adaptive bitrate (1080p 60fps, 720p 30fps, 480p 15fps) and automatic orientation handling.
- **Touch & Gesture Interaction:** Mouse clicks translate to taps, drags translate to swipes, scroll wheels translate to vertical scrolls, and physical keyboards input text into focused fields.
- **Automated Diagnostics:** One-click automated diagnostics for Internet connectivity, DNS latency, Google Play Services status, storage capacity, battery subsystem health, system clock sync, and remote permissions.
- **Scoped File Manager:** Secure browsing of internal and external Documents/Downloads directories with download, upload, rename, and confirmation-gated file deletion.
- **Application Management:** Query installed packages, inspect version/target SDK details, launch applications, open application settings, or request system uninstallation prompts.
- **Cryptographic Security:** Ed25519 / ECDSA device identity, 6-digit expiring pairing codes, QR-code pairing, zero-trust command allowlists, persistent user notifications, and an instant emergency killswitch.
- **Optional Magisk Integration:** Automatic detection of Magisk on rooted phones. If present, privileged mode is strictly opt-in and restricted to signed allowlists.

---

## 📐 Architecture & Monorepo Structure

```
mahi-remote-control/
│
├── app/                        # Android Agent (Live Kotlin + Jetpack Compose App)
│   ├── src/main/java/com/example/
│   │   ├── security/           # DeviceIdentity, AuditLogger, AllowlistValidator
│   │   ├── pairing/            # 6-digit codes, QR generation, trusted controllers
│   │   ├── remotescreen/       # MediaProjection service, H.264 pipeline
│   │   ├── remoteinput/        # AccessibilityService gesture dispatch
│   │   ├── filetransfer/       # Scoped storage SAF file manager
│   │   ├── appmanager/         # Installed packages & launch/uninstall
│   │   ├── diagnostics/        # Hardware telemetry & automated repairs
│   │   ├── rootintegration/    # Magisk detection & signed allowlists
│   │   ├── signaling/          # WebSocket signaling client
│   │   └── ui/                 # Dark Charcoal/Cyan M3 Compose Screens
│
├── windows-controller/         # C# & .NET 10 WPF Desktop Application
│   ├── MahiRemote.sln
│   ├── MahiRemote.App/         # WPF Window, mouse-to-touch canvas, diagnostics
│   ├── MahiRemote.Core/        # Models, Command packets, session state
│   ├── MahiRemote.WebRTC/      # WebSocket signaling & video decoder
│   ├── MahiRemote.Devices/     # Paired device registry
│   └── MahiRemote.Security/    # Cryptographic signing & verification
│
├── backend/                    # Cloud Signaling & Device Management
│   ├── src/index.ts            # Express + WebSocket HTTP server
│   ├── src/signaling/          # Room router, SDP/ICE relay
│   ├── src/device-management/  # Device registry & pairing code consumption
│   └── src/authentication/     # JWT sessions, rate limiting, crypto verification
│
├── infrastructure/             # Database & Deployment
│   ├── database/schema.sql     # PostgreSQL / Supabase schema with RLS policies
│   ├── deployment/docker-compose.yml # Multi-container production stack
│   ├── deployment/coturn.conf  # STUN/TURN server for NAT traversal
│   └── monitoring/prometheus.yml # Telemetry scraping
│
├── docs/                       # Architecture, Protocol & Security Specifications
├── scripts/                    # Automated PowerShell build & test scripts
├── SECURITY.md                 # Security policies & threat model
└── BUILD.md                    # Build and compilation instructions
```

---

## 🚀 Quick Start Guide

### 1. Android Agent
1. Open the Mahi Remote app on the Android phone.
2. The dashboard shows device telemetry and a 6-digit pairing code (e.g. `729-415`).
3. Tap **Share Screen** to launch the Android MediaProjection consent prompt.
4. Ensure the **Mahi Remote Input Service** is enabled in Android Settings > Accessibility.

### 2. Windows Controller Options

#### Option A: Zero-Install Web Browser Controller (Recommended)
You can control the Android phone directly from **any Windows web browser** (Google Chrome, Microsoft Edge, Firefox, Brave) without installing any client software:
- **Public IP Deployment:** Access via `http://<PUBLIC_IP>:8080` (see [IP Deployment Guide](docs/IP-DEPLOYMENT.md)).
- **Cloudflare Quick Tunnel:** Access via automatically generated `https://<random>.trycloudflare.com` without purchasing a domain (see [Tunnel Deployment Guide](docs/TUNNEL-DEPLOYMENT.md)).
- **Launch with one click:**
  ```powershell
  # For direct Public IPv4 deployment:
  .\scripts\start-public-access.ps1 -Port 8080

  # For zero-domain Cloudflare Tunnel:
  .\scripts\start-cloudflare-tunnel.ps1 -Port 8080
  ```

#### Option B: Native Windows WPF Application
1. Launch `MahiRemote.App.exe` on Windows.
2. Click **+ Pair Device** and enter the 6-digit code shown on the phone.
3. The Android phone displays an authorization dialog with the operator name, location (e.g. *Sylhet NOC*), and requested scopes.
4. Once the phone owner taps **Authorize Session**, the screen mirrors in real-time.
5. Interact with the phone using mouse clicks (taps) and drags (swipes).

---

## 🔒 Security Principles

1. **Explicit Consent Only:** No remote session can start without device-owner approval on the phone.
2. **Visible Notification:** A persistent, non-dismissible foreground notification with a bright red **Stop Sharing** action is displayed during the entire streaming duration.
3. **Emergency Killswitch:** Either party can immediately sever the connection and revoke tokens at any time.
4. **Zero Arbitrary Shell:** Mahi Remote strictly forbids and blocks arbitrary network shell commands. All operations pass through a compiled allowlist validator.
5. **Privacy Preserved:** Sensitive screens flagged with `FLAG_SECURE` (e.g., banking apps, password fields) are automatically blanked by the Android OS.
