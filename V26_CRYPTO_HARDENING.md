# V26 — Cryptographic Hardening

V26 strengthens contact-bound cryptography with per-message and per-file P-256 ECDHE.

## Changes

- Fresh P-256 ephemeral key pair for every contact-bound message.
- Fresh P-256 ephemeral key pair for every contact-bound encrypted file.
- Ephemeral public keys are authenticated by AES-256-GCM AAD.
- SKF2 file format version 2 is written for new files.
- SKF2 version 1 remains decryptable for migration compatibility.
- Contact-bound message format version 3 is written for new messages.
- Message format version 2 remains decryptable for migration compatibility.
- Sensitive ephemeral key material and derived AES keys are wiped on normal cleanup paths.
- Existing passphrase factor and domain separation between MESSAGE and FILE remain in place.

## Security property

A compromise of the long-term device identity alone does not reveal previously encrypted V26 content when the corresponding ephemeral private key has been discarded. This is the intended forward-secrecy improvement for the contact-bound message/file layer.

This is an application-level cryptographic property, not a guarantee against a fully compromised endpoint, memory-forensics attack, malicious OS, or compromised device at encryption time.

## Compatibility

- New V26 messages/files require the existing contact pairing and the active session passphrase.
- Legacy message format v2 and SKF2 file format v1 remain readable.
- No network access is introduced.
