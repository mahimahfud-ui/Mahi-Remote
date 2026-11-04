# Mahi Remote Control — System Architecture

```
+-----------------------------------------------------------------------------------------+
|                                    MAHI REMOTE CONTROL                                   |
+-----------------------------------------------------------------------------------------+

  [ Android Agent ]                                             [ Windows Controller ]
  (Noakhali, Bangladesh)                                        (Sylhet, Bangladesh)
  -----------------------                                       ----------------------
  * MediaProjection API                                         * WPF .NET 10 App
  * H.264 AVC Hardware Encoder                                  * DirectShow / D3D Video Canvas
  * AccessibilityService Input                                  * Mouse-to-Touch Translator
  * Scoped Storage SAF Manager                                  * Diagnostic Telemetry View
  * Automated Diagnostics Engine                                * Scoped File Explorer
  * Ed25519 Device Identity                                     * ECDSA Signing Engine
            |                                                             |
            |                                                             |
            |   1. WebSocket Signaling (WSS)                              |
            +---------------------> [ Cloud Signaling Hub ] <-------------+
            |                       (Node.js / Express / WS)              |
            |                       * Device Registration                 |
            |                       * Ephemeral 6-Digit Pairing           |
            |                       * SDP Offer/Answer Relay              |
            |                       * Session Token Auth                  |
            |                                                             |
            |   2. NAT Traversal (STUN / TURN)                            |
            +---------------------> [ Coturn Server ] <-------------------+
            |                       (RFC 5766 / RFC 5389)                 |
            |                                                             |
            |   3. Peer-to-Peer Encrypted Media & Data (WebRTC SRTP)      |
            +============================================================>+
                     * Live Screen Stream (H.264 RTP)
                     * Input & Gesture Commands (SCTP DataChannel)
                     * File Transfer Chunks (SCTP DataChannel)
```

## Architectural Highlights

### 1. Data Flow
1. **Pairing Phase:**
   - Android agent generates a 6-digit one-time code and registers with the Signaling Server.
   - Windows operator inputs the code.
   - Signaling server matches the code and sends an authorization challenge to the phone.
   - Android device owner verifies the operator's details and taps **Authorize Session**.

2. **WebRTC Negotiation:**
   - Android agent initiates MediaProjection and configures an H.264 `MediaCodec` encoder.
   - SDP Offer and ICE candidates are exchanged over WebSocket via the Signaling Hub.
   - STUN/TURN servers resolve NAT mappings across different cellular/ISP networks.

3. **Remote Control Loop:**
   - Operator clicks on the Windows screen canvas.
   - Windows controller signs the coordinate packet and transmits it via WebRTC DataChannel.
   - Android agent verifies the cryptographic signature, validates coordinate bounds, and dispatches the gesture via `MahiAccessibilityService`.
