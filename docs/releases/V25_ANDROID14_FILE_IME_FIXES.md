# V25 Android 14 File/IME Reliability Fixes

This build addresses the reported Android 14 issues around encrypted-file
sharing, returning from the Android document picker, silent keyboard input,
and visible operation status.

## Changes
- Encrypted SKF attachments now advertise only `application/octet-stream`;
  the original file MIME is never presented as the MIME of encrypted bytes.
- Sharesheet intents include `setDataAndType()` and `ClipData` so Android 14
  can propagate the FileProvider read grant reliably.
- The attachment picker forces the outgoing encrypted MIME to octet-stream.
- The IME requests itself to be shown again shortly after DocumentsUI returns.
- Secure-compose shows a persistent operation-status line in addition to Toasts.
- Keyboard key haptic feedback was removed; key presses are silent.
- Temporary decrypted files use the existing best-effort secure-delete helper.
- File encryption/decryption controls are disabled while an operation runs.
- Version: 3.3 / versionCode 15.

## Verification
Static source inspection and ZIP integrity can be performed locally. A real
Android 14 + WhatsApp run remains required to confirm OEM/WhatsApp-specific
file sharing behavior.
