# V26 Build Fix

Fixed a CI compilation failure where `SecureMemory.secureDelete(File)` was
referenced by file-encryption cleanup paths but the helper only exposed
`wipe(...)` methods.

The helper now implements bounded-chunk best-effort overwrite + `fd.sync()` +
delete for temporary plaintext files.

This fix does not change the cryptographic format or key derivation.
