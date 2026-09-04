# SecureKeyboard V19 — Security & Compatibility Hardening

## Implemented

1. **Explicit AES-GCM finalization for file decryption**
   - Removed `CipherInputStream` from the SKF2 decrypt path.
   - Ciphertext is streamed through `Cipher.update()`.
   - `Cipher.doFinal()` is called explicitly so the GCM authentication tag is verified before the decrypted temporary file is accepted.
   - On any failure, the temporary plaintext file is deleted.

2. **Unicode-safe keyboard backspace**
   - Uses `deleteSurroundingTextInCodePoints(1, 0)` on API 24+ instead of deleting a single UTF-16 code unit.
   - Prevents splitting supplementary characters such as emoji.
   - Keeps a compatibility fallback for unusual editor implementations.

3. **Safety Number formatting**
   - Changed the six groups from variable-width decimal (`%02d`) to fixed-width hexadecimal (`%02X`).
   - Every group is exactly two characters, matching the original comment and making comparison deterministic.

4. **Filename sanitization in FileCryptoActivity**
   - Output filenames now strip path separators/control characters and trim unsafe leading/trailing dots/spaces.
   - Length is bounded to 180 characters.

5. **Version**
   - versionName: 2.7
   - versionCode: 9

## Verification status

- ZIP/package integrity can be checked locally.
- Android APK compilation still requires an Android SDK/Gradle environment; this environment does not contain the Android SDK or a Gradle executable, so no claim of a successful local APK build is made.
- The GitHub Actions workflow remains the authoritative build test for the project.

## Important scope note

This hardening does not add Forward Secrecy to contact-bound encryption, does not change the cryptographic file format, and does not claim automatic pressing of WhatsApp's Send button.
