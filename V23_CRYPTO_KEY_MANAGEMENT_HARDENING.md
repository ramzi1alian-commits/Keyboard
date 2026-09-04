# V23 — Cryptographic & Key Management Hardening

## Scope
V23 hardens key lifecycle and cryptographic material handling without breaking Android 8–14 compatibility.

### Changes
- Centralized best-effort memory wiping for `ByteArray` and `CharArray` values.
- Android Keystore AES local-storage key explicitly requires randomized encryption.
- Added runtime reporting of whether the local-storage Keystore key is hardware-backed / StrongBox-backed on supported Android versions.
- Device ECDH identity file now has an explicit format version and stricter length validation.
- Device identity persistence uses an encrypted temporary file followed by an atomic rename, reducing corruption risk during power loss/crash.
- Contact public-key decoding now rejects implausibly small/large encoded keys before cryptographic processing.
- Session key cleanup now uses the centralized wipe helper.

## Important limitation
The contact ECDH identity remains a software EC key pair encrypted at rest by the Android Keystore AES key for compatibility with Android 8–14. V23 therefore **does not claim** that the ECDH private key itself is hardware-backed or non-exportable.

The Keystore status API is diagnostic: it reports the actual protection level of the local-storage AES key on the device. It does not turn software ECDH into a hardware-backed identity.

## Security boundary
AES-256-GCM remains the authenticated-encryption primitive. Argon2id remains the passphrase KDF. ECDH remains P-256 for contact-bound operations.

Forward secrecy is intentionally not claimed in V23; protocol redesign for ephemeral/session keys is reserved for V24.

## Verification
- Static security audit: run `scripts/security_audit.sh`.
- Hardening audit: run `scripts/security_hardening_audit.sh`.
- Android emulator/instrumentation tests must be executed in CI because this development environment does not contain the Android SDK.
