# SecureKeyboard security and reliability hardening

This repository is based on the supplied SecureKeyboard project and the review notes provided with it.

## Applied fixes

- Session passphrases remain process-memory-only, are defensively copied, explicitly zeroized, and now expire using `SystemClock.elapsedRealtime()` so wall-clock changes cannot extend a session.
- Session duration is bounded to 24 hours.
- User-learned word and phrase dictionaries are encrypted at rest with Android Keystore-backed AES-GCM.
- Plaintext byte buffers used for dictionary persistence are explicitly zeroized after encryption/write.
- Dictionary writes are serialized through a single executor and replaced using a temporary file + rename, reducing lost updates and partially-written database files after interruption.
- Sensitive/password input continues to disable suggestions and learning.
- Backup/device-transfer rules exclude private app data; the manifest keeps automatic backup disabled.
- Large static dictionaries load in background threads, keeping keyboard interaction off disk I/O.
- `Prefs` keeps one process-local preferences handle instead of reopening the XML-backed preferences on every keyboard repaint/hot-path lookup.
- Accent preferences are stored as stable names instead of resource IDs, preventing resource-table changes from turning into crashes after updates.
- Argon2id parameters embedded in ciphertext are validated before key derivation, preventing attacker-controlled memory/CPU exhaustion from crafted ciphertext.
- Ciphertext input has a bounded encoded size before Base64 decoding to reduce memory-exhaustion risk.
- New ciphertext remains AES-256-GCM with Argon2id and authenticated headers; legacy v2 parameters remain supported.
- No `android.permission.INTERNET` is declared.
- Release signing keys are not committed to the repository.
- GitHub Actions workflow builds the project using JDK 17 and Gradle 8.4 and uploads the debug APK as an artifact.

## Verification performed in this environment

- All XML resources parsed successfully.
- GitHub Actions YAML parsed successfully.
- Source search found no `Log.d/v/i/w/e` calls or `println()` calls in the application sources.
- Direct `getSharedPreferences()` access exists only inside `Prefs`.
- No `<uses-permission>` declaration exists in `AndroidManifest.xml`.
- Source syntax was checked with the installed Kotlin compiler; full Android compilation could not be executed because this environment does not contain the Android SDK/Gradle distribution and cannot download external build dependencies.

## Build

On GitHub, `.github/workflows/android-build.yml` performs the debug build automatically.

For a signed release build, provide your private keystore through CI secrets/environment variables as documented in `app/build.gradle`; no signing material is stored in the repository.
