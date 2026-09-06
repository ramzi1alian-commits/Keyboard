# SecureKeyboard V27 — Release Notes

- Based on the V26 cryptographic-hardening build.
- Arabic keyboard layout updated to the requested three-row arrangement.
- Long-press on `ا` exposes `ا / أ / إ / آ`.
- Retains V26 ECDHE-per-message and ECDHE-per-file changes and legacy decrypt compatibility.
- Retains Android 14 attachment/IME/status fixes.
- Retains centralized secure-delete support.
- Repository root kept minimal; historical release/security notes live under `docs/`.
- GitHub Actions emulator job fixed to use the matching `google_apis` API 34 image and hardware acceleration on GitHub-hosted Linux runners.
- Removed the manually created AVD that was not the AVD used by the emulator-runner action.
- Disabled snapshot restore/save and wipe emulator data for deterministic boot.
