# SecureKeyboard V40 — Pre-Audit Build & Test Report

Date: 2026-09-09

## Executive result

**Static/security gate: PASS.**

**Native Android Gradle build: BLOCKED in this execution environment.**
The project contains a `gradlew` launcher and `gradle-wrapper.properties`, but the required `gradle/wrapper/gradle-wrapper.jar` is absent. There is also no system Gradle installation and no Android SDK available in this environment. External dependency download is unavailable here, so a real APK build cannot honestly be claimed.

## Tests executed

### Static security audits
- `scripts/security_audit.sh` — PASS
- `scripts/security_hardening_audit.sh` — PASS
- `scripts/crypto_key_management_audit.sh` — PASS
- `scripts/v40_audit.sh` — PASS (7/7)

### Additional checks
- ZIP integrity (`ZipFile.testzip`) — PASS
- V40 source integrity manifest — PASS (106 hashed files verified)
- Suspicious API/source scan — no hits for AccessibilityService, SYSTEM_ALERT_WINDOW, RECORD_AUDIO, READ_SMS, QUERY_ALL_PACKAGES, DeviceAdmin, VpnService, WebView, dynamic dex loading, shell/process execution, HttpURLConnection, OkHttp, Retrofit, or sockets. Benign references/comments were reviewed separately.
- Kotlin source inventory — 30 `.kt` files.
- Manifest review — only CAMERA runtime permission is declared; INTERNET is absent; internal activities/provider are non-exported except the intentionally exported launcher, secure-file receive activity, and IME service.
- Runtime security checks are referenced by `EncryptActivity` before sensitive operations.

## Important limitations

1. No APK was produced in this environment.
2. Unit tests and Android instrumentation tests were not executed because Gradle/Android SDK are unavailable.
3. No emulator/device runtime test was executed.
4. Anti-debugging, anti-hooking, root, and certificate checks remain defense-in-depth heuristics; they are not a boundary against a privileged OS/kernel attacker.
5. Independent cryptographic review and penetration testing remain required.

## Required next environment

Use a controlled build machine or CI runner with:
- JDK 17 (preferred for this project workflow)
- Android SDK with compile SDK 34 and required build-tools
- Gradle 8.4 via a complete Gradle Wrapper (`gradle-wrapper.jar`)
- Network access to approved dependency repositories, or a fully populated dependency cache
- The project's dependency verification/locking material

Then run:

```text
./gradlew --no-daemon clean
./gradlew --no-daemon :app:testDebugUnitTest
./gradlew --no-daemon :app:assembleDebug
./gradlew --no-daemon :app:assembleRelease
```

For the final production/audit artifact, use the organization's controlled release keystore and record the APK SHA-256.

## Release decision

**Do not call V40 build-verified yet.** It is suitable as a source-level pre-audit candidate, but the external auditor should receive a build produced and signed in a controlled environment, together with the resulting APK hash and test logs.


## CI failure correction
- Corrected Android Gradle Plugin BuildConfig configuration by enabling `android.buildFeatures.buildConfig true`.
- This fixes the configuration failure reported by GitHub Actions when `defaultConfig` declares `buildConfigField`.
- A fresh GitHub Actions run is required to verify the full build and tests.
