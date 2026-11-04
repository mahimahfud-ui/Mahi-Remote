# Mahi Remote Control — Cloudflare Tunnel Deployment Guide

This guide explains how to expose the Mahi Remote Control backend and Web Browser Controller to the internet using **Cloudflare Quick Tunnels (`trycloudflare.com`)**.

**Key Benefits:**
- **Zero Cost**: Completely free without subscribing to any paid plan.
- **No Domain Required**: Cloudflare automatically generates a temporary public domain (e.g., `https://random-words.trycloudflare.com`).
- **Instant Valid HTTPS & WSS**: Automatically trusted SSL certificate signed by Cloudflare CA. No browser security warnings or self-signed certificate hassles.
- **No Router Port Forwarding**: Operates purely over outbound HTTPS connections from your backend machine.

---

## 1. How It Works (Traffic & WebRTC Architecture)

Understanding how Cloudflare Quick Tunnel fits into the Mahi Remote Control architecture:

```
[ Android Device (Sylhet/Noakhali) ]
     │               ▲
     │ (Signaling)   │ (WebRTC P2P Data & Video via STUN/TURN UDP)
     ▼               ▼
[ Cloudflare Edge ] ─── [ Operator Web Browser (Chrome/Edge) ]
     │
     │ Outbound Tunnel Connection (TCP 443 / QUIC)
     ▼
[ Local Mahi Backend (Node.js on :8080) ]
```

1. **Signaling & Web Controller (Proxied)**:
   - Cloudflare terminates TLS and proxies HTTP and WebSockets (`/ws`) to your local backend on port `8080`.
   - The Operator accesses the controller at `https://<subdomain>.trycloudflare.com`.
   - The Android Agent sends pairing requests and exchanges WebRTC SDP / ICE candidates over `wss://<subdomain>.trycloudflare.com/ws`.

2. **WebRTC Media & Screen Streaming (Peer-to-Peer)**:
   - Cloudflare Tunnels do **not** route raw UDP WebRTC media packets.
   - WebRTC media flows directly between the Android device and the operator's browser via UDP.
   - For NAT traversal across cellular/broadband firewalls, the system uses public STUN (`stun:stun.l.google.com:19302`) and/or your TURN relay.

---

## 2. Quick Start with PowerShell

We have provided a one-step PowerShell script that automates the entire workflow:

```powershell
.\scripts\start-cloudflare-tunnel.ps1 -Port 8080
```

### What the script does automatically:
1. Verifies that `cloudflared` is installed (or provides installation commands).
2. Starts the Node.js backend server on `127.0.0.1:8080`.
3. Launches `cloudflared tunnel --url http://localhost:8080`.
4. Captures and prints the assigned `https://<random>.trycloudflare.com` URL.
5. Displays the exact URLs for both the browser and the Android app.
6. Gracefully terminates both processes on `Ctrl+C`.

---

## 3. Manual Setup (Linux, macOS, or Manual Windows)

If you prefer to run the commands manually:

### Step 1: Install `cloudflared`

- **Windows**:
  ```powershell
  winget install --id Cloudflare.cloudflared
  ```
- **Ubuntu / Debian**:
  ```bash
  curl -L --output cloudflared.deb https://github.com/cloudflare/cloudflared/releases/latest/download/cloudflared-linux-amd64.deb
  sudo dpkg -i cloudflared.deb
  ```
- **macOS**:
  ```bash
  brew install cloudflared
  ```

### Step 2: Start the Mahi Backend

```bash
cd backend
npm install
npm run build
export PORT=8080
export HOST=127.0.0.1
node dist/index.js
```

### Step 3: Run the Cloudflare Quick Tunnel

In a second terminal window:

```bash
cloudflared tunnel --url http://localhost:8080
```

Watch the terminal output for lines like:
```text
+--------------------------------------------------------------------------------------------+
|  Your quick Tunnel has been created! Visit it at (it may take some time to be reachable):  |
|  https://autumn-marathon-valley-echo.trycloudflare.com                                     |
+--------------------------------------------------------------------------------------------+
```

---

## 4. Connecting the Operator & Android Phone

### 1. Operator Web Browser
- Open the printed URL (e.g., `https://autumn-marathon-valley-echo.trycloudflare.com`).
- Log in with your operator credentials.
- The browser interface connects over secure `wss://` automatically.

### 2. Android Device
- Launch the **Mahi Remote Control** app.
- In the connection dialog, enter:
  - **Server URL**: `https://autumn-marathon-valley-echo.trycloudflare.com`
  - **WebSocket URL**: `wss://autumn-marathon-valley-echo.trycloudflare.com/ws`
- Tap **Generate Pairing Code** or scan the pairing QR code from the browser screen.

---

## 5. Important Caveats & Best Practices

1. **Tunnel Lifecycle**:
   - Quick tunnel URLs (`trycloudflare.com`) are temporary. If you terminate the `cloudflared` process, restarting it will generate a new random subdomain.
   - If you want a persistent, unchanging tunnel name without paying for a domain, you can register a free subdomain on DuckDNS or use a Cloudflare Zero Trust account with a free domain.

2. **WebRTC STUN / TURN Requirement**:
   - Because Cloudflare Quick Tunnels proxy HTTP and WebSockets only, WebRTC data channels and video streams require ICE candidates to be exchanged.
   - If both the operator and the Android phone are behind strict symmetric NATs (e.g. two separate carrier-grade NAT cellular networks), ensure a TURN relay server (Coturn) is configured in `backend/.env`.

3. **Security**:
   - Because the tunnel URL is publicly reachable over the internet, never share your pairing codes or admin tokens publicly.
   - Mahi Remote Control implements rate-limited single-use pairing codes and cryptographic session tokens to protect unauthorized access.
