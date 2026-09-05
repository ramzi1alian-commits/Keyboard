# V26 Release Notes

Version: 3.4  
Version code: 16

### Main security upgrade
Per-content P-256 ECDHE is now used for contact-bound messages and encrypted files. Each encryption generates a new ephemeral key pair, while the recipient uses the paired device identity to derive the same shared secret.

### Backward compatibility
Legacy contact messages (v2) and legacy SKF2 files (v1) remain decryptable.

### Validation
- Existing static security audits retained.
- Added instrumentation tests for fresh ephemeral message ciphertexts and tampered ephemeral public keys.
- ZIP integrity verified before release.

Runtime validation on a physical Android 14 device and the target messaging application remains required before operational deployment.
