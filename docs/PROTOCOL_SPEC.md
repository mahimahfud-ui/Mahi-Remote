# Mahi Remote Control — Protocol Specification

Version: 1.0  
Transport: WebSocket (Signaling) + WebRTC (Video & Data Channels)  
Encoding: JSON / H.264 Video RTP

---

## 1. Signaling Messages

### `REGISTER` (Device -> Server)
```json
{
  "type": "REGISTER",
  "sender": "MAHI-SAM-9B7C12F0",
  "payload": {
    "deviceId": "MAHI-SAM-9B7C12F0",
    "model": "Samsung Galaxy S24 Ultra",
    "fingerprint": "7A:B2:34:CD:5E:6F:10:99:88:77:66:55:44:33:22:11"
  },
  "timestamp": 1730623800000
}
```

### `PAIR_REQUEST` (Operator -> Server -> Device)
```json
{
  "type": "PAIR_REQUEST",
  "targetDeviceId": "MAHI-SAM-9B7C12F0",
  "pairingCode": "729-415",
  "operatorName": "Sylhet NOC Operator #1",
  "operatorLocation": "Sylhet, Bangladesh",
  "operatorFingerprint": "8E:4A:91:D2:11:F9:6C:55:A3:40:99:BC:14:8A:EF:29",
  "requestedScopes": ["Screen Mirroring", "Remote Input", "File Transfer", "Diagnostics"],
  "timestamp": 1730623810000
}
```

### `SDP_OFFER` & `SDP_ANSWER`
```json
{
  "type": "SDP_OFFER",
  "sender": "MAHI-SAM-9B7C12F0",
  "recipient": "operator_8E4A91D2",
  "payload": {
    "type": "offer",
    "sdp": "v=0\r\no=- 421098 2 IN IP4 127.0.0.1..."
  }
}
```

---

## 2. Remote Input Event (Operator -> Device DataChannel)

```json
{
  "commandId": "cmd_8f9021a",
  "type": "Tap",
  "sessionId": "sess_39482109",
  "timestamp": 1730623825000,
  "x": 540.0,
  "y": 960.0,
  "signature": "MEQCIFjK9...=="
}
```
Validation rules:
- `x` must be within `[0, ScreenWidth]`
- `y` must be within `[0, ScreenHeight]`
- `signature` must verify against the trusted controller's public key.
