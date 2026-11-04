# Mahi Remote Control — Android-to-Android Setup Guide

This guide describes how to configure two physical Android devices to remotely control one another using the single unified **Mahi Remote Control APK**.

---

## 1. Prerequisites

- **Two Android Devices**:
  - **Phone A (Controller)**: Android 8.0+ (Oreo) or newer.
  - **Phone B (Receiver)**: Android 8.0+ (Oreo) or newer. Tested up to Android 14 / 15.
- **Single APK**: Install `MahiRemote-debug.apk` (or release build) on **BOTH** devices.
- **Internet Connectivity**:
  - Wi-Fi, 4G, or 5G on both devices (no shared Wi-Fi network required).
  - Both devices connect to the Mahi Signaling Server (cloud, public VPS IP, or Cloudflare Quick Tunnel).

---

## 2. Setting Up Phone B (Receiver — The Phone Being Controlled)

1. **Launch App**: Open **Mahi Remote Control** on Phone B.
2. **Select Role**: Ensure the top selector is set to **"🛡️ Allow Control"** (Receiver Mode).
3. **Generate Pairing Code**:
   - The dashboard generates a 6-digit code (e.g. `729-415`) valid for 5 minutes.
   - Alternatively, tap **Show QR Code** to display the visual pairing QR code.
4. **Grant Required Permissions**:
   - **Accessibility Service**: Navigate to `Android Settings > Accessibility > Downloaded Apps > Mahi Remote Input Service` and toggle **ON**. This allows permitted taps, swipes, and navigation.
   - **Notification Permission**: On Android 13+, allow notifications so the persistent screen-sharing status is displayed.
5. **Start Screen Sharing**:
   - Tap **Share Screen** on the dashboard.
   - When the Android system dialog appears ("Start recording or casting with Mahi Remote?"), tap **Start now**.
   - A persistent foreground notification will appear in the status bar confirming screen capture is active.

---

## 3. Setting Up Phone A (Controller — The Controlling Phone)

1. **Launch App**: Open **Mahi Remote Control** on Phone A.
2. **Select Role**: Tap **"📱 Control Another"** on the top role selector card.
3. **Configure Connection**:
   - Enter the 6-digit pairing code shown on Phone B (e.g. `729-415`).
   - Or tap **QR Code** and paste/scan the QR code payload.
4. **Initiate Pairing**:
   - Tap **Connect to Phone**.

---

## 4. Authorizing the Session on Phone B

1. When Phone A initiates connection, Phone B immediately vibrates and displays the **Authorize Remote Support** modal.
2. The modal displays:
   - Requesting Controller Model (e.g. *Pixel 8 Pro*).
   - Controller Fingerprint (cryptographic SHA-256).
   - Requested Scopes (Screen Mirroring, Remote Interaction, File Transfer, Diagnostics).
3. Tap **Authorize Session** on Phone B. (Tapping "Deny / Block" instantly rejects and logs the attempt).

---

## 5. Controlling Phone B from Phone A

Once approved, Phone A automatically opens the **Live Remote Viewport**:

- **Touch Controls**:
  - **Tap**: Tap any element on the mirrored screen.
  - **Double Tap**: Tap rapidly twice.
  - **Long Press**: Press and hold on an item.
  - **Scroll / Swipe**: Drag your finger up, down, left, or right.
- **Navigation Controls**:
  - Use the bottom navigation bar: **Back**, **Home**, **Recents**, **Notification Shade**, **Quick Settings**, **Lock**.
- **Remote Typing**:
  - Tap a text field on the remote screen, type into the "Type remote text..." bar on Phone A, and tap **Send**.
- **Volume**:
  - Use the top-right overlay buttons to adjust Phone B's media volume.
- **Remote File Manager**:
  - Switch to the **Files** tab to browse Phone B's storage, download files, or delete files.
- **Remote App Manager**:
  - Switch to the **Apps** tab to view installed applications on Phone B, launch them remotely, or open app settings.
- **Remote Diagnostics**:
  - Switch to the **Diagnostics** tab to inspect Phone B's live battery health, thermal status, free storage, and network latency.

---

## 6. Terminating the Session

The remote session can be severed immediately by either user:
- **From Phone A (Controller)**: Tap the red **Disconnect** button in the top app bar.
- **From Phone B (Receiver)**: Tap **Stop Sharing** in the persistent status bar notification, or tap the **Emergency Disconnect** button on the dashboard.
- All active session tokens are invalidated instantly.
