# V22 — Android Hardening

## Implemented
- Centralized validation of external `VIEW` / `SEND` encrypted-file intents.
- Only `content://` and `file://` URIs are accepted by the external receive entry point.
- Incoming MIME metadata is constrained to `application/octet-stream` or an `.skf` file name; malformed/arbitrary intents are rejected before decryption.
- External receive remains the only intentionally exported crypto-facing Activity; the keyboard and crypto Activities remain private.
- Contact store writes are encrypted, zeroized in memory, and replaced atomically.
- Contact store input sizes are bounded to reduce parser/resource abuse.
- Temporary decrypted files remain in the app-private cache and are removed on failure/destroy.
- Existing `FLAG_SECURE`, disabled backup, disabled cleartext traffic, and no-INTERNET-permission controls remain enforced.

## Important limitations
- Android/TEE/StrongBox hardware backing is device-dependent. The current contact ECDH identity intentionally uses a software P-256 private key encrypted at rest; this is documented rather than falsely presented as hardware-backed.
- Memory zeroization in managed Kotlin/Java cannot guarantee removal from every GC/JIT/framework copy. V22 reduces avoidable copies but does not claim physical RAM zeroization.
- External intent validation reduces attack surface; it does not make a malicious or compromised Android OS trustworthy.
