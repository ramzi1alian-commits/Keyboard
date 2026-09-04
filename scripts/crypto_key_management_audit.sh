#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
SRC="$ROOT/app/src/main/java"
fail=0

check_absent() {
  local pattern="$1"; local label="$2"
  if grep -RInE "$pattern" "$SRC" >/tmp/sk_crypto_audit.$$ 2>/dev/null; then
    echo "FAIL: $label"
    cat /tmp/sk_crypto_audit.$$
    fail=1
  else
    echo "PASS: $label"
  fi
  rm -f /tmp/sk_crypto_audit.$$
}

check_present() {
  local pattern="$1"; local label="$2"
  if grep -RInE "$pattern" "$SRC" >/dev/null 2>&1; then echo "PASS: $label"; else echo "FAIL: $label"; fail=1; fi
}

check_present 'object SecureMemory' 'centralized memory wipe helper exists'
check_present 'setRandomizedEncryptionRequired\(true\)' 'Keystore local key requires randomized encryption'
check_present 'atomic identity commit failed' 'device identity uses atomic commit path'
check_present 'SECURITY_LEVEL_STRONGBOX' 'runtime StrongBox detection is present'
check_present 'invalid public key size' 'contact public-key size bound is present'
check_absent 'DES/ECB|DESede|RC4|AES/ECB|Cipher\.getInstance\("AES/ECB|MessageDigest\.getInstance\("MD5|MessageDigest\.getInstance\("SHA-1' 'legacy/unsafe primitive patterns absent'

exit "$fail"
