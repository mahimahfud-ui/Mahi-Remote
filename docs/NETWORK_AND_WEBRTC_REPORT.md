# Mahi Remote Control — Network & WebRTC Integration Report

This report analyzes the network architecture, signaling protocols, WebRTC media flow, NAT traversal, and internet relay configurations of Mahi Remote Control.

---

## 1. Network Architecture Overview

Mahi Remote Control establishes real-time peer-to-peer communication between Android devices across heterogeneous networks:

```
[ Phone A — Controller ]                               [ Phone B — Receiver ]
(e.g., Sylhet / Wi-Fi)                                (e.g., Noakhali / 4G)
          │                                                       │
          │ ─── 1. WSS Signaling (SDP Offer/Answer & ICE) ──────> │
          │                                                       │
          ▼                                                       ▼
   [ STUN Server ]                                         [ STUN Server ]
(Discover Public IP/Port)                               (Discover Public IP/Port)
          │                                                       │
          └───────────────────────────┬───────────────────────────┘
                                      │
                         [ Direct UDP P2P Possible? ]
                                      │
                    ┌─────────────────┴─────────────────┐
                    │ YES                               │ NO (Symmetric / Carrier-Grade NAT)
                    ▼                                   ▼
          [ Direct UDP Flow ]                 [ Coturn TURN Relay ]
     (Low Latency 20–45ms RTT)             (Relay Audio, Video, Data)
```

---

## 2. Signaling Layer (Node.js & WebSocket)

- **Endpoint**: `/ws` on port `8080` (or proxied over Cloudflare Quick Tunnel / HTTPS).
- **Transport**: WSS (WebSocket over TLS).
- **Header Authentication**:
  - `X-Device-Id`: Unique device identifier.
  - `X-Client-Role`: `CONTROLLER` or `RECEIVER`.
  - `X-Device-Fingerprint`: SHA-256 identity key fingerprint.
- **Message Types**:
  - `REGISTER`: Registers device presence with `DeviceService`.
  - `REGISTER_PAIRING_CODE`: Registers a generated 6-digit code with its 5-minute expiration timestamp.
  - `PAIR_BY_CODE`: Controller requests connection to receiver via 6-digit code.
  - `PAIR_REQUEST`: Dispatched to receiver for explicit owner approval.
  - `PAIR_ACCEPT`: Sent by receiver upon authorization.
  - `SDP_OFFER` & `SDP_ANSWER`: WebRTC session description exchange.
  - `ICE_CANDIDATE`: Candidate gathering exchange (Host, Server Reflexive, Relay).
  - `INPUT_EVENT`: Touch, gesture, keyboard, volume, and global navigation packets.
  - `APP_COMMAND` & `FILE_COMMAND`: Remote device management.
  - `EMERGENCY_DISCONNECT`: Instant session teardown.

---

## 3. WebRTC Media & Data Pipeline

- **Video Track**: Hardware-accelerated H.264 stream originating from `MediaProjection`.
- **Bitrate Presets**:
  - High: 1080p @ 60 FPS (4,000 kbps)
  - Balanced: 720p @ 30 FPS (2,000 kbps)
  - Efficiency: 480p @ 15 FPS (800 kbps)
- **DataChannel**: SCTP DataChannel (`mahi-control-channel`) with unordered delivery for ultra-low latency gesture transmission.

---

## 4. NAT Traversal & Connectivity Scenarios

| Scenario | Network A (Controller) | Network B (Receiver) | Path | Expected Latency | Status |
| :--- | :--- | :--- | :--- | :--- | :--- |
| **Local Network** | Home Wi-Fi (192.168.1.10) | Same Home Wi-Fi (192.168.1.20) | Host ICE Candidates (Direct LAN) | 5–15 ms | **Verified** |
| **Broadband to Broadband** | Fiber ISP (Sylhet) | Fiber ISP (Dhaka) | STUN Hole-Punching (Srflx Candidate) | 25–45 ms | **Verified** |
| **Mobile Data to Wi-Fi** | Grameenphone 4G (Sylhet) | Broadband Wi-Fi (Noakhali) | STUN / Hole-Punching | 35–65 ms | **Verified** |
| **Symmetric / CGNAT** | Banglalink 4G | Robi 4G | Coturn TURN Relay (Relay Candidate) | 45–80 ms | **Verified with Coturn** |

---

## 5. Cloudflare Tunnel vs WebRTC Boundary

It is vital to distinguish between HTTP/WebSocket tunneling and WebRTC media transport:
- **Cloudflare Quick Tunnels (`trycloudflare.com`)**:
  - Proxies **TCP HTTP and WebSockets** (ports 80 and 443).
  - Provides instant trusted TLS certificates without purchasing a domain.
  - Successfully routes signaling (`/ws`) and the Web Controller SPA.
- **WebRTC UDP Media**:
  - Cloudflare Quick Tunnels **do not** proxy raw peer-to-peer UDP media packets.
  - Peer media packets flow directly between Phone A and Phone B over UDP (or through Coturn TURN on port 3478).
  - Therefore, running a TURN server is mandatory when both phones are trapped behind strict symmetric carrier NATs.
