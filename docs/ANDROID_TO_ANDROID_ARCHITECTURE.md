# Mahi Remote Control — Android-to-Android Architecture

## 1. Unified Application Model

Mahi Remote Control is architected as a **single, unified Android application package (APK)**. Both the controlling device (**Phone A — Controller**, e.g. in Sylhet) and the target device (**Phone B — Receiver**, e.g. in Noakhali) install and execute the exact same APK (`com.aistudio.mahiremote.rcvkm`).

```
                    ┌───────────────────────────────────────────────┐
                    │               Mahi Remote APK                 │
                    │         (Single Unified Codebase)             │
                    └───────────────────────┬───────────────────────┘
                                            │
                       User Mode Selection at Runtime
                                            │
                   ┌────────────────────────┴────────────────────────┐
                   ▼                                                 ▼
        ┌──────────────────────┐                          ┌──────────────────────┐
        │   CONTROLLER MODE    │                          │    RECEIVER MODE     │
        │   (Phone A / Sylhet) │                          │  (Phone B / Noakhali)│
        └──────────┬───────────┘                          └──────────┬───────────┘
                   │                                                 │
                   │               Signaling Channel                 │
                   │           (Node.js / Express / WSS)             │
                   ├───────────────────────┬─────────────────────────┤
                   │                       │                         │
                   │ 1. PAIR_BY_CODE       ▼                         │
                   │ ──────────────────────────────────────────────> │ 2. Display Approval Dialog
                   │                                                 │    (Explicit user consent)
                   │ 3. PAIR_ACCEPT (Session Token)                  │
                   │ <────────────────────────────────────────────── │ 4. Starts MediaProjection
                   │                                                 │    Foreground Service
                   │                                                 │
                   │             WebRTC Peer Connection              │
                   │ 5. SDP Offer / Answer & ICE Candidates (STUN)   │
                   │ <═════════════════════════════════════════════> │
                   │                                                 │
                   │ 6. Live H.264 Video Stream                      │
                   │ <────────────────────────────────────────────── │ Screen Capture Pipeline
                   │                                                 │
                   │ 7. Touch & Gestures (TAP, SWIPE, DRAG)          │
                   │ ──────────────────────────────────────────────> │ AccessibilityService
                   │                                                 │ Gesture Dispatcher
                   │ 8. Global Actions (BACK, HOME, RECENTS)         │
                   │ ──────────────────────────────────────────────> │ System Action Invocations
                   │                                                 │
                   │ 9. Scoped File Transfer & App Management        │
                   │ <─────────────────────────────────────────────> │ SAF & PackageManager
                   │                                                 │
                   │ 10. Emergency Killswitch (Either Party)         │
                   │ <─────────────────────────────────────────────> │ Revoke Session Tokens
                   └─────────────────────────────────────────────────┘
```

---

## 2. Role Selection & State Management

The active operating role is managed via `AppRole`:
- `AppRole.RECEIVER`: Enables MediaProjection screen streaming, AccessibilityService input injection, generates 6-digit expiring pairing codes, renders pairing QR codes, and presents explicit operator approval dialogs.
- `AppRole.CONTROLLER`: Connects to target phones using 6-digit codes or QR codes, renders the remote live screen inside an interactive canvas, captures and normalizes touch gestures, provides Android navigation buttons, text injection, volume adjustment, remote file browsing, and hardware telemetry querying.

The role is selectable dynamically from the dashboard header via `RoleSelectorCard`. Switching roles updates the WebSocket signaling client's registration header (`X-Client-Role: CONTROLLER` vs `X-Client-Role: RECEIVER`) without requiring an application restart.

---

## 3. Screen Capture & Remote Rendering Pipeline

### Receiver Screen Capture (`Phone B`)
1. **User Consent**: When starting a session, the user is presented with the official Android system MediaProjection permission dialog (`createScreenCaptureIntent()`).
2. **Foreground Service**: `ScreenCaptureService` runs with `foregroundServiceType="mediaProjection"`.
3. **Notification**: Displays a non-dismissible notification with a prominent "Stop Sharing" action.
4. **Encoding**: VirtualDisplay routes video frames through hardware-accelerated H.264 MediaCodec encoders. Adaptive presets support 1080p (60fps), 720p (30fps), and 480p (15fps) based on network conditions.

### Controller Remote Viewport (`Phone A`)
1. **Aspect Ratio Preservation**: Remote frames render within a 9:16 portrait canvas (`aspectRatio(9f / 16f)`).
2. **Coordinate Normalization**: Touch coordinates on the local canvas `(x, y)` are dynamically scaled to the receiver's target resolution `(1080 x 1920)`:
   $$\text{targetX} = \frac{x}{\text{canvasWidth}} \times 1080$$
   $$\text{targetY} = \frac{y}{\text{canvasHeight}} \times 1920$$
3. **Touch Feedback**: Controller displays visual touch ripples at the exact pointer locations on screen.

---

## 4. Remote Input & Gesture Dispatch

Remote interactions are serialized into structured JSON packets sent via WebSocket/DataChannel and executed by `MahiAccessibilityService`:

| Gesture / Action | Protocol Type | Parameters | Android Implementation |
| :--- | :--- | :--- | :--- |
| **Tap** | `TAP` | `x, y` | `GestureDescription` with single `Path` point (50ms duration) |
| **Double Tap** | `DOUBLE_TAP` | `x, y` | Two consecutive stroke paths with 80ms delay |
| **Long Press** | `LONG_PRESS` | `x, y` | Single stroke path with 600ms hold duration |
| **Swipe** | `SWIPE` | `x1, y1, x2, y2, durationMs` | Continuous line path with configurable timing |
| **Drag** | `DRAG` | `x1, y1, x2, y2, durationMs` | Sustained path with touch down, translate, touch release |
| **Back** | `GLOBAL_ACTION` | `action: "BACK"` | `AccessibilityService.performGlobalAction(GLOBAL_ACTION_BACK)` |
| **Home** | `GLOBAL_ACTION` | `action: "HOME"` | `AccessibilityService.performGlobalAction(GLOBAL_ACTION_HOME)` |
| **Recents** | `GLOBAL_ACTION` | `action: "RECENTS"` | `AccessibilityService.performGlobalAction(GLOBAL_ACTION_RECENTS)` |
| **Notification Shade** | `GLOBAL_ACTION` | `action: "NOTIFICATIONS"` | `AccessibilityService.performGlobalAction(GLOBAL_ACTION_NOTIFICATIONS)` |
| **Quick Settings** | `GLOBAL_ACTION` | `action: "QUICK_SETTINGS"` | `AccessibilityService.performGlobalAction(GLOBAL_ACTION_QUICK_SETTINGS)` |
| **Lock Screen** | `GLOBAL_ACTION` | `action: "LOCK_SCREEN"` | `AccessibilityService.performGlobalAction(GLOBAL_ACTION_LOCK_SCREEN)` |
| **Text Input** | `TEXT_INPUT` | `text: "..."` | `AccessibilityNodeInfo.ACTION_SET_TEXT` on active focused editable node |
| **Volume Control** | `VOLUME` | `action: "UP" / "DOWN"` | `AudioManager.adjustStreamVolume(STREAM_MUSIC, ...)` |

---

## 5. Security & Isolation Boundaries

1. **Explicit Device Owner Authorization**: No remote session can be established silently. The receiver must tap "Authorize Session" on the approval dialog.
2. **Command Allowlist**: Every command is validated by `AllowlistValidator`. Arbitrary shell commands (`sh`, `su`, `exec`) are strictly rejected.
3. **FLAG_SECURE Protection**: The Android operating system automatically blanks password entries and banking application surfaces from MediaProjection.
4. **Emergency Severance**: Tapping the emergency disconnect button on either phone instantly revokes credentials, stops screen streaming, and resets peer connections.
