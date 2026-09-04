#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
fail=0
check_absent() {
  local pattern="$1"; shift
  if grep -RInE "$pattern" "$@" --exclude-dir=build --exclude='*.md' >/tmp/sk_security_hits 2>/dev/null; then
    echo "FAIL: forbidden pattern: $pattern"
    cat /tmp/sk_security_hits
    fail=1
  fi
}
check_present() {
  local pattern="$1"; shift
  if ! grep -RInE "$pattern" "$@" --exclude-dir=build >/dev/null 2>&1; then
    echo "FAIL: required hardening missing: $pattern"
    fail=1
  fi
}
if grep -nE '<uses-permission[^>]+android:permission="android\.permission\.INTERNET"' "$ROOT/app/src/main/AndroidManifest.xml" >/tmp/sk_security_hits 2>/dev/null; then echo 'FAIL: INTERNET permission declared'; cat /tmp/sk_security_hits; fail=1; fi
check_absent 'Cipher\.getInstance\("(DES|DESede|RC4|AES/ECB|AES/CBC)' "$ROOT/app/src/main/java"
check_absent 'MessageDigest\.getInstance\("(MD5|SHA-?1)"' "$ROOT/app/src/main/java"
check_present 'SecurityIntentValidator\.encryptedFileUri' "$ROOT/app/src/main/java"
check_present 'FLAG_SECURE' "$ROOT/app/src/main/java"
check_present 'android:allowBackup="false"' "$ROOT/app/src/main/AndroidManifest.xml"
check_present 'android:usesCleartextTraffic="false"' "$ROOT/app/src/main/AndroidManifest.xml"
if [[ "$fail" -ne 0 ]]; then exit 1; fi
echo "V22 hardening audit: PASS"
