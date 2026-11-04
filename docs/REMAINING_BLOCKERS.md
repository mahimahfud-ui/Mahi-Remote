# Mahi Remote Control — Verified Status and Remaining Engineering Blockers

**Review scope:** repository source on the main branch. Source-level fixes have been committed, but no physical Android devices or Windows desktop runtime were available in this review session. Do not interpret source changes or a workflow definition as a passing build or end-to-end result.

## Current implementation boundaries

### Android → Android

Implemented in source:
- Android Keystore ECDSA identity challenge/response at WebSocket connection.
- One-time pairing-code registration, controller request, explicit receiver accept/reject, pending and active server-side pair states.
- WebRTC Android screen sender using MediaProjection and ScreenCapturerAndroid.
- WebRTC Android controller viewer and signaling handlers.
- Remote input dispatch through the Accessibility service when the receiver owner has manually enabled it.
- Projection-stop and ICE-state reporting without fabricated FPS/bitrate/RTT values.

Still requires physical verification:
- Install both app instances and verify that each role can be selected and connected.
- Verify the Android Keystore public key/fingerprint pair is stable after app restart and app update.
- Verify permission prompt, stop notification, revocation, background/foreground transitions and device rotation.
- Verify real screen frames, original system status/navigation bars, remote taps/swipes/text and accessibility action feedback.
- Verify network interruption/reconnection and resource cleanup across repeated sessions.

### Browser → Android

Implemented in source:
- Browser WebSocket pairing and receiver-approval handling.
- Browser RTCPeerConnection, video track binding and pointer-to-display mapping.
- The remote video element is layered above the neutral gesture canvas; the UI no longer claims that a fake canvas is a live session.
- Browser input is sent over the approved signaling pair because Android currently has no matching DataChannel input dispatcher.

Still requires physical verification:
- Browser on a desktop and a real Android receiver on the same LAN.
- Browser on a remote network and Android on cellular/Wi-Fi.
- Autoplay, full-screen, orientation, real video frames, gesture scaling and permission failures.

### Windows WPF controller

**Prototype-only for live video.** The WPF signaling shell does not create a native PeerConnection, decode the Android video track or feed decoded frames into the bitmap receiver. The fake generated phone screen and fake TURN secret were removed. The bitmap sink is only a future frame-rendering endpoint. Use the browser controller for the current desktop-to-Android path until native WPF WebRTC is implemented and verified.

The backend now rejects the WPF client's self-asserted operator fingerprint header until a signed nonce challenge-response flow is implemented in the Windows client. WPF therefore cannot currently connect as a trusted controller.

## Security and deployment blockers

1. **Release signing-key compromise:** a Live UI private key was present in Git history. The file was deleted from the current branch, but historical commits still contain it. Treat the key as compromised. Generate a new ECDSA P-256 key pair locally with `cd backend && npm run generate:release-keys`; configure the new private/public key in deployment secrets, and inject the matching SPKI public-key base64 into the next Android build. Do not reuse the old key.
2. **Git history cleanup:** after backups and collaborator coordination, purge the old PEM from all Git history (for example with git-filter-repo), then force-push the rewritten branch. Existing clones must be recreated or hard-reset to the rewritten history.
3. **Database migration:** older server records may have `public_key` containing the previous fingerprint placeholder rather than SPKI public-key base64. Those devices will fail the stricter identity pin check. Re-enrol with a new key or migrate only after independently verifying device ownership.
4. **Single backend instance:** pairing-code records and active pair state are process-local. Do not horizontally scale the signaling backend until atomic database-backed code consumption and shared session state are implemented.
5. **TURN:** the backend ICE endpoint can generate time-limited TURN credentials, but the Android sender and Android controller still instantiate PeerConnections with Google STUN only. Android must fetch the backend ICE list before robust cross-carrier/symmetric-NAT operation can be claimed. The Browser controller already requests the dynamic ICE list.
6. **Secrets:** the default admin key, TURN shared secret and checked-in release private key have been removed from the current source/configuration. Supply strong values through uncommitted .env/deployment secrets. PostgreSQL TLS verification is not disabled by the application.
7. **OTA APK:** the previously committed APK was a 54-byte mock archive, not an installable APK; it has been deleted. The OTA API correctly returns no release until a real APK is uploaded. Validate package ID and signing-certificate continuity against the app already installed on the actual handset before attempting an update.
8. **Installed package/signing continuity:** the configured application ID is `com.aistudio.mahiremote.bqpjku`. Confirm the actual installed package and signer on both target phones. A package-name match alone is not enough for in-place OTA.
9. **Database policies:** apply the hardened schema to the target database and verify that only the backend's trusted service role can access device, pairing, session and audit tables.

## Telemetry accuracy

The Android capture service now transitions based on the real WebRTC ICE state. FPS, encoded frame count, bitrate, RTT and packet-loss are intentionally left at zero/unknown because real RTCP statistics are not wired yet. Add a real stats collector before displaying numeric streaming performance values.

## Required end-to-end verification matrix

| Scenario | Required expected result | Status in this review |
|---|---|---|
| Valid pairing code | Receiver sees a request and can approve | Source flow updated; physical test pending |
| Invalid code | Controller gets a clear error; no session | Source flow updated; CI/runtime test pending |
| Expired code | No pairing request is created | Source flow updated; CI/runtime test pending |
| Rejected request | Controller sees rejection; no control route | Source flow updated; physical test pending |
| Receiver offline | Pair request rejected with offline status | Source flow updated; physical test pending |
| MediaProjection denied | Capture does not start; state reports error | Physical test pending |
| Capture permission revoked | Capture stops and state reports stopped | Callback wired; physical test pending |
| WebRTC negotiation timeout | UI remains connecting then exposes a recoverable error | Full timeout/recovery verification pending |
| ICE connection failure | Stream state reports failure; no fake live telemetry | ICE-state callback wired; physical test pending |
| Network interruption | Session moves to reconnect/error state and does not report live | Recovery behavior pending |
| Reconnection successful | Real frames resume and state reflects real connection | Physical test pending |
| Controller disconnected | Receiver session is closed and sharing can be stopped | Physical test pending |
| Receiver stops sharing | Controller loses stream and shows a recoverable stopped state | Physical test pending |

## Build and CI verification

The repository contains a GitHub Actions workflow defining:
- Backend TypeScript compilation and integration suite.
- Android unit tests and debug APK assembly.
- Windows WPF solution build.

During this review the GitHub Actions API returned zero workflow runs for the repository. Therefore there is no verified CI result to report yet. Run or enable Actions in the repository, then inspect every job's logs before treating the branch as build-clean.

## Immediate engineering backlog

1. Run GitHub Actions and resolve any backend, Kotlin/Gradle or .NET build failures.
2. Verify dynamic ICE/TURN credentials and successful candidate selection on LAN, home broadband, cellular and symmetric-NAT networks.
3. Complete two-physical-phone and browser-to-phone test sessions; capture logs and real RTCP/video evidence.
4. Implement real Android RTCP stats; do not infer FPS/bitrate from the selected preset.
5. Implement Windows WPF native WebRTC PeerConnection, H.264 decode and frame delivery; add signed operator identity authentication.
6. Move pairing-code consumption and active session state to a shared transactional store before scaling backend replicas.
7. Rotate release keys, purge the compromised key from Git history, and build a new APK pinned to the new public key.
8. Upload a genuine APK and verify package/signer continuity on a device before enabling OTA.
