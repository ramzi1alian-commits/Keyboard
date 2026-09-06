# V38 — Android 8/14 File & Secure-Compose Fixes

- Fixed SKF2 v2 ECDHE header reconstruction during decryption. The ephemeral public key is now rebuilt in the exact wire order, preventing GCM authentication failure on valid v2 files.
- Removed `FLAG_ACTIVITY_NO_HISTORY` from the standalone file-crypto Activity so Android 14 DocumentsUI return keeps the file tool on screen instead of jumping to the host app/home.
- Removed the file-tool return-broadcast/IME-nudge path; it could fire while DocumentsUI was temporarily on top and was unnecessary once the Activity remains in task history.
- Secure Compose plaintext/result views now use the app's day/night text palette instead of hard-coded white text, making light-mode typing readable.
- Long-press Arabic variant popup now uses an opaque window background, explicit non-touchable/non-focusable settings, and above/below placement fallback so `ا`/`ي` variants remain visible while the anchor continues receiving the drag gesture.
- No new root-level release files were added.
