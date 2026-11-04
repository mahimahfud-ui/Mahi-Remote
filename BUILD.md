# Mahi Remote Control — Build and Deployment Guide

This document describes how to compile and build the Android application, Windows desktop controller, backend signaling service, and database infrastructure.

---

## 📋 Prerequisites

| Component | Minimum Version | Recommended |
|---|---|---|
| **Android Agent** | JDK 17, Android SDK 34+ | Android SDK 36, Gradle 8.13+ |
| **Windows Controller** | .NET 10 SDK, Windows 10/11 | Visual Studio 2024 / .NET 10 |
| **Cloud Backend** | Node.js 20 LTS, npm 10+ | Docker 24+, Docker Compose v2 |
| **Database** | PostgreSQL 15+ | PostgreSQL 16 / Supabase |

---

## 🛠️ Automated Build Scripts (PowerShell)

The `scripts/` directory contains automated build orchestrations:

```powershell
# 1. Build Android Debug & Release APKs
./scripts/build-android.ps1 -Configuration Debug
./scripts/build-android.ps1 -Configuration Release

# 2. Build Windows Desktop Controller
./scripts/build-windows.ps1 -Configuration Release

# 3. Run Complete Automated Test Suite
./scripts/run-tests.ps1

# 4. Master Orchestration (Build all components & produce reports)
./scripts/build-all.ps1
```

All build deliverables will be copied to `dist/`:
- `dist/MahiRemote-debug.apk`
- `dist/MahiRemote-release.apk`
- `dist/MahiRemote-Windows-Setup.exe`
- `dist/build-report.md`
- `dist/test-report.md`

---

## 📱 Android Agent Build (Manual)

1. In the repository root:
   ```bash
   gradle :app:assembleDebug
   ```
2. The output APK is located at:
   `app/build/outputs/apk/debug/app-debug.apk`

3. To run unit and Robolectric tests:
   ```bash
   gradle :app:testDebugUnitTest
   ```

---

## 💻 Windows Controller Build (Manual)

1. Open `windows-controller/MahiRemote.sln` in Visual Studio or compile via .NET CLI:
   ```bash
   dotnet restore windows-controller/MahiRemote.sln
   dotnet build windows-controller/MahiRemote.sln -c Release
   ```
2. Publish standalone single-file binary:
   ```bash
   dotnet publish windows-controller/MahiRemote.App/MahiRemote.App.csproj \
       -c Release -r win-x64 --self-contained true -p:PublishSingleFile=true -o ./dist/windows
   ```

---

## ☁️ Cloud Backend & Signaling Deployment

1. Navigate to the infrastructure deployment directory:
   ```bash
   cd infrastructure/deployment
   ```
2. Start the multi-container stack:
   ```bash
   docker compose up -d --build
   ```
3. Services listening:
   - **Signaling API & WebSockets:** `http://localhost:8080` / `ws://localhost:8080/ws`
   - **PostgreSQL Database:** `localhost:5432`
   - **Coturn STUN/TURN Server:** `udp:3478`, `tcp:3478`
   - **Prometheus Metrics:** `http://localhost:9090`
