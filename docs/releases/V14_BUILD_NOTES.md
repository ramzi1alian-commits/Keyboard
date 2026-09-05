# SecureKeyboard V14 build notes

- Target compatibility: Android 8.0 (API 26) through Android 14 (API 34); project minSdk remains API 24 for Android 7 compatibility.
- V14 versionCode: 5, versionName: 2.3.
- Contact ECDH: one software EC P-256 implementation for API 24-34, with the private key encrypted at rest using LocalStorageCrypto (Android Keystore AES key).
- Existing V13 contact pairings must be re-paired because V14 intentionally uses a new device identity.
- Argon2id new-message memory is reduced to 32/48/64/96 MiB tiers according to the process max heap; the exact chosen value is embedded in V3 ciphertext.
- Decrypted clipboard popup is non-focusable/touchable to avoid IME teardown/recreation seen on some Android 12-14 builds.
- File picker remains Storage Access Framework based; ACTION_OPEN_DOCUMENT_TREE is API 21+ and therefore compatible with Android 8+.
- Local environment does not contain a complete Android SDK/Gradle toolchain, so this package has not been claimed as locally APK-built. CI/GitHub Actions remains the final build verification.
