# Contact-bound encryption architecture

## One contact identity, separate AES keys

Messages and files both begin with the same selected contact identity:

`DeviceIdentity (this device private key) + paired contact public key -> ECDH shared secret`

The shared secret is then combined with the active secure-session passphrase and HKDF/HMAC-SHA256.

The derivation is domain-separated:

- Messages: `SecureKeyboard-v1-e2e-salt` / `SecureKeyboard-message-key-v1`
- Files: `SecureKeyboard-file-salt-v1` / `SecureKeyboard-file-key-v1`

Therefore a message and a file for the same contact are cryptographically bound to the same contact identity, while intentionally using different AES-256 keys. Reusing one AES key across unrelated content types would be a weaker design.

Both content types use AES-256-GCM for authenticated encryption. File content is streamed so large files do not need to be loaded fully into RAM.

No network service is required for encryption or decryption.
