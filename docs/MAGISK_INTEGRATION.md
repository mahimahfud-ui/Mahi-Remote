# Optional Magisk Root Integration Guide

Mahi Remote Control supports standard non-root devices as first-class citizens. Rooting is NEVER required for remote screen viewing, touch input, file management, or diagnostics.

---

## 🔍 Root Detection Subsystem

The Android agent automatically checks for standard Superuser binaries and Magisk package signatures:
- Checks `/system/bin/su`, `/system/xbin/su`, `/sbin/su`, `/vendor/bin/su`, `/data/local/xbin/su`.
- Inspects package manager for `com.topjohnwu.magisk`.

### Non-Root Device Behavior
- Root toggle is disabled.
- The UI displays: `DEVICE STATUS: NON-ROOT (SECURE SANDBOX)`.
- All features execute within official Android permission boundaries.

### Rooted Device Behavior
- Magisk presence is detected and reported.
- Privileged mode remains **DISABLED by default**.
- The device owner must explicitly toggle "Privileged Mode Opt-In" and approve the confirmation dialog.

---

## 🔒 Signed Privileged Allowlist

Even when root is opted-in, Mahi Remote **NEVER** allows arbitrary shell commands over the network.
Only specific, signed diagnostics actions are executed:
1. `QUERY_SELINUX`: Non-destructive inspection of `getenforce` to verify system integrity.
2. `DUMP_BATTERY_STATS`: Reading hardware register state from `/sys/class/power_supply/battery`.
3. `PRIVILEGED_INPUT_HEALTH`: Direct uinput kernel subsystem verification.

Every privileged action is logged to the persistent `AuditLogger` with severity `CRITICAL`.
An **Emergency Killswitch** is continuously available to revoke root privileges and disconnect the operator immediately.
