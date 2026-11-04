# Mahi Remote Control — Public IP Deployment Guide

This guide details how to deploy and operate Mahi Remote Control directly using a **Public IPv4 address** on any Virtual Private Server (VPS), cloud instance (AWS EC2, DigitalOcean, Hetzner, Linode, Oracle Cloud), or dedicated server without purchasing or registering a permanent domain.

---

## 1. Network & Port Architecture

When running directly on a public IP address, the following network ports are required:

| Service | Port | Protocol | Purpose |
| :--- | :--- | :--- | :--- |
| **Browser Controller & Signaling** | `8080` (or `3000`) | TCP | Serves Web UI & handles WebSocket (`/ws`) and REST endpoints |
| **STUN / TURN Service (Coturn)** | `3478` | UDP & TCP | NAT traversal and relay for WebRTC audio/video across WANs |
| **WebRTC Media Relays** | `49152–65535` | UDP | Dynamic media ports for WebRTC direct data and media channels |

---

## 2. Server Prerequisites

### On Linux (Ubuntu 22.04 / 24.04 LTS or Debian 12)

```bash
# Update package lists
sudo apt update && sudo apt upgrade -y

# Install Node.js 20.x
curl -fsSL https://deb.nodesource.com/setup_20.x | sudo -E bash -
sudo apt install -y nodejs coturn git ufw

# Verify versions
node -v   # v20.x+
npm -v    # 10.x+
```

---

## 3. Firewall Configuration

Configure `ufw` or cloud security groups (AWS Security Groups, DigitalOcean Cloud Firewall, etc.):

```bash
# Allow SSH
sudo ufw allow 22/tcp

# Allow Mahi Web Controller & Signaling
sudo ufw allow 8080/tcp

# Allow STUN/TURN Signaling
sudo ufw allow 3478/tcp
sudo ufw allow 3478/udp

# Allow WebRTC dynamic media port range
sudo ufw allow 49152:65535/udp

# Enable firewall
sudo ufw enable
```

---

## 4. Coturn TURN Server Setup (for Cross-WAN NAT Traversal)

For remote connections across cellular networks (e.g. Bangladesh Grameenphone / Banglalink 4G) and symmetric broadband NATs, configure Coturn:

Edit `/etc/turnserver.conf`:

```conf
# /etc/turnserver.conf
listening-port=3478
fingerprint
lt-cred-mech
use-auth-secret
static-auth-secret=your_strong_generated_secret_key_here
realm=mahi.local
total-quota=100
bps-capacity=0
stale-nonce=600
no-loopback-peers
no-multicast-peers
min-port=49152
max-port=65535
verbose
```

Enable and start the service:

```bash
sudo systemctl enable coturn
sudo systemctl restart coturn
```

---

## 5. Mahi Backend Deployment

1. **Clone or copy the project files to the VPS**:
   ```bash
   git clone <repo-url> /opt/mahi-remote-control
   cd /opt/mahi-remote-control/backend
   ```

2. **Install and build**:
   ```bash
   npm install
   npm run build
   ```

3. **Configure Environment Variables**:
   Create `/opt/mahi-remote-control/backend/.env`:
   ```env
   PORT=8080
   HOST=0.0.0.0
   NODE_ENV=production
   JWT_SECRET=super_secret_jwt_key_at_least_32_characters_long
   ADMIN_TOKEN=mahi_admin_session_auth_token_change_this

   # Public IP Address of your VPS
   PUBLIC_HOST=203.0.113.15

   # WebRTC ICE Servers (STUN & TURN)
   STUN_URL=stun:203.0.113.15:3478
   TURN_URL=turn:203.0.113.15:3478
   TURN_USERNAME=mahi_operator
   TURN_CREDENTIAL=your_strong_generated_secret_key_here
   ```

4. **Run with PM2 for 24/7 reliability**:
   ```bash
   sudo npm install -g pm2
   pm2 start dist/index.js --name "mahi-backend"
   pm2 save
   pm2 startup
   ```

---

## 6. Windows Workstation Automated Launch

If running the backend directly on a Windows server or workstation with a public IP or port-forwarded router, run:

```powershell
.\scripts\start-public-access.ps1 -Port 8080
```

The script will:
1. Fetch and print the public IPv4 address.
2. Bind Node.js to `0.0.0.0:8080`.
3. Provide the exact browser URL and Android Agent configuration strings.

---

## 7. Connecting from the Browser & Android Device

### Browser Controller
1. Open Google Chrome, Microsoft Edge, or Mozilla Firefox.
2. Navigate to:
   ```
   http://<YOUR_VPS_PUBLIC_IP>:8080
   ```
3. Enter your Operator Username and Master Secret (or use pairing code verification).
4. The Web Controller displays device pairing status, real-time screen canvas, file manager, and system control buttons.

### Android Agent Setup
1. Launch **Mahi Remote Control** on the Android device.
2. In the connection settings, enter:
   - **Server Host**: `http://<YOUR_VPS_PUBLIC_IP>:8080`
   - **Signaling URL**: `ws://<YOUR_VPS_PUBLIC_IP>:8080/ws`
3. Tap **Generate Pairing Code** or scan the QR code displayed on the Web Controller.
4. When prompted by the system dialog, accept **MediaProjection** (screen capture) and ensure **AccessibilityService** is toggled ON in Android Settings.

---

## 8. HTTPS / WSS on Public IP (Optional)

WebRTC in modern browsers requires a "Secure Context" (either `localhost` or HTTPS). If accessing over an external untrusted IP without a domain name:

- **Option A (ZeroSSL IP Certificate)**: ZeroSSL issues public IP SSL certificates directly for IPv4 addresses without requiring a domain name.
- **Option B (Chrome Flag for Testing)**:
  Open `chrome://flags/#unsafely-treat-insecure-origin-as-secure` on the controller PC, add `http://<YOUR_VPS_PUBLIC_IP>:8080`, and restart Chrome.
- **Option C (Cloudflare Quick Tunnel)**:
  Use `scripts/start-cloudflare-tunnel.ps1` for immediate, zero-domain HTTPS and WSS (see `docs/TUNNEL-DEPLOYMENT.md`).
