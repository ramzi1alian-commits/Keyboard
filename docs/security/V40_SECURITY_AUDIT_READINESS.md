# SecureKeyboard V40 — Security Audit Readiness

## Purpose

V40 is a security-audit candidate derived from V38 and includes the V39 file-decryption hardening plus additional runtime integrity/anti-instrumentation checks.

This release is **not** claimed to be immune to Pegasus, malware, rooted devices, or OS compromise. A compromised Android OS can bypass application-level controls.

## V40 hardening

- Two-pass authenticated file decryption from V39: encrypted content is authenticated before plaintext is released to the temporary output file.
- Encrypted-file size guard retained from V39.
- Local runtime posture checks:
  - Android debuggable flag.
  - Attached debugger.
  - `/proc/self/status` TracerPid.
  - Suspicious instrumentation library names in `/proc/self/maps` (Frida/Xposed/LSPosed/Substrate-style indicators).
  - Existing root heuristic.
  - Optional SHA-256 signing-certificate pinning supplied at release build time.
- No telemetry is added. Runtime checks do not transmit anything.
- Sensitive encryption UI warns when the runtime posture is not trusted.
- Existing `FLAG_SECURE`, disabled backup, private components, no `INTERNET` permission, Keystore-backed storage, AES-GCM and Argon2id protections are retained.

## Certificate pinning

The project contains an empty certificate pin by default so local development is not blocked.

For a controlled release build, inject the exact SHA-256 digest of the intended release signing certificate:

`-PsecureKeyboardExpectedCertSha256=<64-hex-character-digest>`

The audit team should verify that the resulting APK certificate matches the approved release key and that a repackaged APK fails the pin check.

## Audit scope requested

1. Android application security / MASVS-style review.
2. Cryptographic design and implementation review.
3. File-format parser fuzzing.
4. Runtime instrumentation and tamper testing.
5. Rooted-device and hostile-environment testing.
6. APK repackaging and signature/tamper testing.
7. Static dependency and supply-chain review.
8. Secret/key lifecycle review, including memory lifetime.
9. Exported-component and Intent abuse testing.
10. Independent penetration test and final residual-risk assessment.

## Known limitations / explicit findings for auditors

- Application-level anti-debugging and anti-hooking checks are heuristics and can be bypassed by a sufficiently privileged attacker.
- Root detection is heuristic and is not a security boundary.
- `FLAG_SECURE` cannot prevent a privileged OS/root attacker or a physical camera from capturing the display.
- No application can guarantee protection from a fully compromised Android OS or kernel-level spyware.
- The project must be built and signed by a controlled release environment before certification claims are made.
- Gradle dependency locking is configured, but the repository must contain a generated lockfile and CI must enforce locked resolution before production release.
- Independent cryptographic/security review has not been performed by this project.

## Release gate

Do not market this build as "military-grade", "Pegasus-proof", "unhackable", or "10/10 secure". Those claims require evidence beyond source-level hardening, including an independent audit and a defined certification scope.
