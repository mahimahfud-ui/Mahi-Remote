# Mahi Remote Control

Mahi Remote is a cross-platform remote-support project for Android devices. Its intended controller paths are:

- **Android → Android** — one Android handset requests permission and controls another Android handset.
- **Browser → Android** — a desktop or mobile browser pairs with an Android receiver and displays its WebRTC screen stream.
- **Windows WPF native controller** — an experimental desktop client; its native WebRTC video receiver is not complete yet.

The project uses the existing Obsidian Telemetry visual language. This repository documents the current implementation honestly: source code and automated tests do not replace physical-device verification.

## Current implementation status

| Area | Current source status | Verification still required |
|---|---|---|
| Android device identity | Android Keystore-backed ECDSA challenge/response is implemented for the signaling socket. | Verify key continuity and registration on physical devices; migrate old server records that stored a fingerprint in the public-key column. |
| Pairing | Expiring one-time pairing code, receiver-side approval/rejection, and server-side pending/active pair gates are implemented. | Run invalid, expired, rejected, offline, replay, timeout and disconnect scenarios on two phones and a browser. |
| Android screen sender | **ScreenCaptureService** uses MediaProjection through **ScreenCapturerAndroid** and a native WebRTC video track. | Verify real frames, Android 14+ permission behavior, orientation, status/navigation bars, revocation and background lifecycle on physical devices. |
| Android remote input | Controller commands are routed only over an owner-approved signaling pair and dispatched through **MahiAccessibilityService**. | Enable the Accessibility service manually and verify coordinates/gestures across different display sizes and system bars. |
| Browser controller | Browser **RTCPeerConnection**, remote video track rendering, touch/mouse mapping, pairing approval, retry and fullscreen UI are present. | Verify with real receiver hardware, public-network NAT traversal and actual input delivery. |
| Live telemetry | ICE state drives connecting/connected/error state. Unavailable FPS, bitrate, RTT and packet-loss values remain zero/unknown rather than fabricated. | Add real RTCP statistics collection and verify with a live session. |
| Live UI updates | ECDSA manifest verification and signed per-asset SHA-256/size checks are implemented. Publishing is deliberately disabled until a newly rotated key pair is configured. | Generate a new release key, configure publisher/backend secrets, build an APK with the matching public-key pin, then verify a full signed update/rollback. |
| Native APK OTA | Real APK binary, package, version, size, SHA-256 and archive checks are required. No placeholder APK is advertised. | Upload a real signed APK; verify signer continuity against the installed production app and test PackageInstaller on a handset. |
| PostgreSQL | Schema and connection support exist; direct public access policies have been removed. | Apply the updated schema, migrate old key records and verify service-role permissions and database backups. |
| Windows WPF native video | Bitmap sink can render decoded frames, but no native PeerConnection/decoder pipeline is wired to feed it. Fake phone frames were removed. | Implement native WebRTC signaling, PeerConnection, decoder and frame delivery before claiming WPF screen control. |

## Architecture

~~~text
Android receiver
  ├─ Android Keystore device identity
  ├─ Pairing code + local owner approval
  ├─ MediaProjection / ScreenCapturerAndroid
  ├─ WebRTC sender
  └─ AccessibilityService command execution

Browser controller
  ├─ WebSocket signaling
  ├─ one-time pairing code
  ├─ receiver-approved session
  ├─ RTCPeerConnection / remote video element
  └─ pointer-to-display coordinate mapping

Backend
  ├─ Express API
  ├─ WebSocket SignalingHub
  ├─ one-time pairing + pending/active session gate
  ├─ Android Keystore nonce verification
  ├─ ECDSA-signed Live UI release service
  └─ validated binary APK OTA service

Windows WPF
  └─ experimental controller shell; native WebRTC video path remains incomplete
~~~

## Android setup

1. Install the Android app on both handsets.
2. Choose the receiver phone and generate a fresh one-time pairing code.
3. On the receiver, approve screen sharing in the Android MediaProjection system prompt.
4. On the controller phone, enter the receiver code or scan its QR code.
5. Approve the requested scopes on the receiver before control starts.
6. Enable **Mahi Remote Input Service** manually in Android Settings → Accessibility before trying remote touch input.

Android does not allow the app to silently approve MediaProjection or Accessibility access. The user must grant these permissions on the receiver.

## Backend setup

Use Node.js 20 or later and PostgreSQL if persistent device identity storage is required.

~~~bash
cd backend
cp ../.env.example .env
# Fill every required secret in .env; do not commit it.
npm ci
npm run build
npm test
npm start
~~~

The backend listens on port 8080 by default and binds to 0.0.0.0. Configure TURN with **TURN_SHARED_SECRET**, **TURN_DOMAIN** and a reachable Coturn service for networks where STUN-only connectivity fails.

**Important:** the Android receiver/controller code currently configures Google STUN directly. The backend ICE-server endpoint can generate TURN credentials, but Android clients still need to consume that endpoint before cross-carrier/symmetric-NAT connectivity can be considered supported.

## Live UI signing key rotation

A release private key was found in the repository history. It has been removed from the current branch, but deleting a file does not erase old Git commits. Treat that key as compromised and never reuse it.

Create a fresh local key pair:

~~~bash
cd backend
npm ci
npm run generate:release-keys
~~~

The generator writes key files under the ignored **backend/data/keys/** directory and prints the public-key SPKI base64 needed for the Android build. Keep the private key only in deployment secrets:

- **UI_RELEASE_PRIVATE_KEY**
- **UI_RELEASE_PUBLIC_KEY**
- **MAHI_UI_RELEASE_PUBLIC_KEY_BASE64** for the Android Gradle build

Do not commit PEM, JKS, keystore, APK or .env files. Purge the old private key from Git history and rotate any deployed copies before enabling release publication.

## Native APK OTA

The repository intentionally contains no installable APK binary. The former **mahi-remote-v1.1.0.apk** was only a tiny mock archive and has been deleted. The latest-release endpoint returns 404 until a real APK is uploaded.

An uploaded APK must have the expected package name **com.aistudio.mahiremote.bqpjku**, a valid ZIP/APK archive, a monotonic version code, file-size and SHA-256 metadata, and a supplied SHA-256 signer fingerprint. Android PackageInstaller enforces the installed app's signing-certificate continuity; server metadata alone does not prove that the uploaded APK is signed by the currently installed app's key.

## CI

GitHub Actions workflow **.github/workflows/ci.yml** defines:

- Backend TypeScript build and integration tests.
- Android unit tests and debug APK assembly.
- Windows WPF solution build.

A workflow definition is not proof of a successful run. Check the repository's Actions tab for the latest run before treating any build as verified.

## Security and operational boundaries

- Pairing requests require receiver approval. Commands are forwarded only for a receiver-approved active pair.
- Android devices must sign a server nonce with their Keystore key before being registered as online.
- Browser controller identities are ephemeral and gain no device access before code verification and receiver approval.
- Windows WPF operator identity authentication is not yet equivalent to the Android Keystore challenge flow.
- Device records in older databases may have an obsolete public-key value and require an explicit migration or re-enrolment.
- The current pairing code registry is process-local. Run one backend instance until database-backed atomic pairing-code consumption is implemented.
- A public WebSocket connection is not itself a secure remote-control session. Keep TLS enabled in production and deploy TURN separately for media traffic.

## Documentation

See **docs/REMAINING_BLOCKERS.md** for the engineering backlog and physical-device verification checklist.
