# SecureKeyboard V21 — Adversarial Testing Foundation

V21 is built directly on V20. It does not replace the cryptographic design;
it adds an executable adversarial test layer around the message ciphertext
boundary and CI checks that keep the hardening from silently regressing.

## Tests included

- Unicode round-trip, including supplementary characters.
- Single-byte ciphertext corruption.
- Header/version corruption.
- Truncated ciphertext.
- Malicious Argon2 memory parameters.
- Wrong-passphrase rejection.
- Ciphertext-shape sanity checks.

All failure tests require decryption to fail closed. No test accepts a partial
or unauthenticated plaintext result.

## Threat model covered by this stage

V21 specifically targets malformed/untrusted ciphertext supplied to the app:

1. Parser confusion and short-input handling.
2. Authentication bypass attempts through modified GCM data/AAD.
3. Resource-exhaustion attempts through attacker-controlled Argon2 parameters.
4. Accidental acceptance of wrong keys.
5. Unicode boundary regressions.

This is not a substitute for a professional penetration test, fuzzing on
physical devices, OS-level exploitation testing, or formal cryptographic
module validation.

## CI behavior

GitHub Actions now has an emulator-based adversarial test job. The normal
assembleDebug job remains separate so a crypto-test failure cannot be hidden
by a successful compilation.
