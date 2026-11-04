# Mahi Remote Control — Android API Limitations & System Boundaries

This document provides a transparent, technically rigorous assessment of the operational boundaries and security limitations enforced by the Android Operating System on unrooted, non-Device Owner devices.

---

## 1. Executive Summary

Mahi Remote Control operates entirely within Android's legitimate public API surface (`MediaProjection`, `AccessibilityService`, `Storage Access Framework`, `PackageManager`). It does not employ privilege escalation exploits, private API reflection, or stealth bypasses.

---

## 2. API Capability & Limitation Breakdown

### A. Screen Capture (`MediaProjection`)
| Capability | Status | Technical Details |
| :--- | :--- | :--- |
| **Full Device Screen Mirroring** | **Supported** | Captured via `VirtualDisplay` and encoded via hardware `MediaCodec` H.264. |
| **Audio Capture** | **Supported** | Requires user consent; captures internal media audio on Android 10+. |
| **Silent Background Capture** | **Prohibited by OS** | Android requires explicit user confirmation via a system dialog (`createScreenCaptureIntent()`) and enforces a foreground service notification. |
| **Capture of Banking / Password Screens** | **Blocked by OS** | Windows with `FLAG_SECURE` (e.g. banking apps, password fields, Netflix) are rendered as black pixels by the SurfaceFlinger display compositor. |

---

### B. Remote Input & Gestures (`AccessibilityService`)
| Capability | Status | Technical Details |
| :--- | :--- | :--- |
| **Taps, Double Taps, Long Presses** | **Supported** | Dispatched via `AccessibilityService.dispatchGesture()` using `GestureDescription.StrokeDescription`. |
| **Swipes, Drags, Scrolling** | **Supported** | Smooth continuous multi-point paths. |
| **System Navigation (Back, Home, Recents)** | **Supported** | `performGlobalAction(GLOBAL_ACTION_BACK / HOME / RECENTS)`. |
| **Notification Shade & Quick Settings** | **Supported** | `performGlobalAction(GLOBAL_ACTION_NOTIFICATIONS / QUICK_SETTINGS)`. |
| **Lock Screen** | **Supported** | `performGlobalAction(GLOBAL_ACTION_LOCK_SCREEN)` on Android 9+. |
| **Text Input** | **Supported** | Injected via `AccessibilityNodeInfo.ACTION_SET_TEXT` on active focused editable nodes. |
| **Silent Enablement** | **Prohibited by OS** | Accessibility services must be manually enabled by the user in Android Settings. |
| **Interacting with System Security Prompts** | **Restricted by OS** | Android 12+ prevents accessibility gestures from clicking "Allow" on runtime permission dialogs or device administrator prompts to prevent clickjacking. |
| **Bypassing Lock Screen PIN/Pattern** | **Prohibited by OS** | Cannot unlock the device if protected by a secure biometric, PIN, or pattern. |

---

### C. System Controls & Hardware
| Capability | Status | Technical Details |
| :--- | :--- | :--- |
| **Volume Adjustment** | **Supported** | `AudioManager.adjustStreamVolume()`. |
| **Screen Brightness** | **Partial** | App can adjust window brightness; global system brightness requires `WRITE_SETTINGS` permission. |
| **Airplane Mode / Wi-Fi / Mobile Data Toggle** | **Restricted by OS** | Normal apps cannot toggle Airplane mode or cellular data. Mahi routes the user to the corresponding System Settings intent. |
| **Power Off / Reboot** | **Prohibited by OS** | Requires system signature or Device Owner privileges. |

---

### D. Application Management
| Capability | Status | Technical Details |
| :--- | :--- | :--- |
| **Query Installed Apps** | **Supported** | `PackageManager.getInstalledPackages()` using `<queries>` declaration. |
| **Launch Applications** | **Supported** | `PackageManager.getLaunchIntentForPackage()` + `startActivity()`. |
| **Open App Settings** | **Supported** | `Settings.ACTION_APPLICATION_DETAILS_SETTINGS`. |
| **Request App Uninstallation** | **Supported** | Prompts official system uninstall confirmation via `Intent.ACTION_DELETE`. Silent uninstallation is prohibited on standard Android devices. |

---

### E. File System & Storage
| Capability | Status | Technical Details |
| :--- | :--- | :--- |
| **Scoped Storage (Documents / Downloads)** | **Supported** | Handled via SAF or standard scoped storage permissions. |
| **App-Private Storage Sandbox** | **Supported** | Full read/write within Mahi's own `filesDir` and cache. |
| **Accessing Other Apps' `/data/data/`** | **Prohibited by OS** | Android Linux user sandboxing prevents cross-app data access without root (Magisk). |
