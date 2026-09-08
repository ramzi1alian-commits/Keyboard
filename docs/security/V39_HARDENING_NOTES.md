# V39 Hardening Candidate

This build is a **security-hardening candidate**, not a claim of 10/10 security or certification.

## Changes from V38

1. Streaming file decryption is now two-pass for both SKF2 v1 and v2:
   - pass 1 consumes the complete ciphertext and calls `doFinal()` without releasing plaintext;
   - pass 2 is opened only after authentication succeeds and writes plaintext to the private cache file.
2. Added a 4 GiB encrypted-file size policy guard to reduce resource-exhaustion risk.
3. Preserved SKF2 v1/v2 wire compatibility.
4. Preserved existing AES-256-GCM, P-256 ECDH and SecureRandom design.

## Remaining work before any "10/10" claim

- Redesign contact-file key derivation if passphrases must resist offline guessing: use a versioned format with Argon2id and per-file salt/parameters.
- Add sender authentication/signatures if origin authentication is a requirement.
- Refactor the large IME service and consolidate duplicate crypto APIs.
- Add property/fuzz tests for malformed file headers, truncation, oversized lengths, nonce handling and provider differences.
- Build and test on every supported API level/device class.
- Perform independent cryptographic/security review. No software-only review can honestly guarantee 10/10 or military certification.
