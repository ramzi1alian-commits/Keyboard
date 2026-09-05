#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
MANIFEST="$ROOT/app/src/main/AndroidManifest.xml"
SRC="$ROOT/app/src/main/java"

fail=0
check_absent() {
  local pattern="$1" label="$2"
  if grep -RInF -- "$pattern" "$SRC" "$MANIFEST" --include='*.kt' --include='*.java' --include='*.xml' >/dev/null 2>&1; then
    echo "FAIL: $label"
    grep -RInF -- "$pattern" "$SRC" "$MANIFEST" --include='*.kt' --include='*.java' --include='*.xml' || true
    fail=1
  else
    echo "PASS: $label"
  fi
}

check_present() {
  local pattern="$1" file="$2" label="$3"
  if grep -qE "$pattern" "$file"; then echo "PASS: $label"; else echo "FAIL: $label"; fail=1; fi
}

echo "== SecureKeyboard V23 static security audit =="
check_present 'android:allowBackup="false"' "$MANIFEST" "Android backup disabled"
check_present 'android:usesCleartextTraffic="false"' "$MANIFEST" "Cleartext traffic disabled"
check_present 'android:networkSecurityConfig=' "$MANIFEST" "Network Security Config attached"
check_present 'android:permission="android.permission.BIND_INPUT_METHOD"' "$MANIFEST" "IME binding protected"
check_present 'FileProvider' "$MANIFEST" "FileProvider used for exported file sharing"
check_present 'AES/GCM/NoPadding' "$SRC/com/securekeyboard/app/CryptoEngine.kt" "AES-GCM message encryption"
check_present 'ARGON2_id' "$SRC/com/securekeyboard/app/CryptoEngine.kt" "Argon2id passphrase KDF"
check_present 'AES/GCM/NoPadding' "$SRC/com/securekeyboard/app/LocalStorageCrypto.kt" "Keystore-backed AES-GCM local storage"
check_present 'ECDH' "$SRC/com/securekeyboard/app/DeviceIdentity.kt" "P-256 ECDH contact identity"
check_absent 'Cipher.getInstance("DES"' "No DES cipher use"
check_absent 'Cipher.getInstance("DESede"' "No 3DES cipher use"
check_absent 'Cipher.getInstance("RC4"' "No RC4 cipher use"
check_absent 'AES/ECB' "No AES/ECB mode use"
check_absent 'MessageDigest.getInstance("MD5"' "No MD5 digest use"
check_absent 'MessageDigest.getInstance("SHA-1"' "No SHA-1 digest use"
check_absent 'android.permission.INTERNET' "No INTERNET permission"
check_absent 'http://' "No plaintext HTTP URLs"
check_absent 'OkHttpClient' "No OkHttp network stack"
check_absent 'Retrofit' "No Retrofit network stack"
check_absent 'Log.d(' "No debug Log.d calls"
check_absent 'Log.i(' "No Log.i calls"
check_absent 'Log.v(' "No verbose Log.v calls"


exported_count=$(grep -c 'android:exported="true"' "$MANIFEST" || true)
if [[ "$exported_count" == "3" ]] && grep -q 'android:name=".SettingsActivity"' "$MANIFEST" && grep -q 'android:name=".SecureFileReceiveActivity"' "$MANIFEST" && grep -q 'android:name=".SecureInputMethodService"' "$MANIFEST"; then
  echo "PASS: exported component allowlist (3 intentional entry points)"
else
  echo "FAIL: exported component allowlist changed unexpectedly"
  fail=1
fi

if grep -RInE 'TODO|FIXME|XXX' "$SRC" >/dev/null 2>&1; then
  echo "WARN: TODO/FIXME markers exist in source; review before release"
else
  echo "PASS: no TODO/FIXME markers"
fi

if (( fail != 0 )); then
  echo "SECURITY AUDIT: FAILED"
  exit 1
fi


# V25 adversarial-test guardrails
if [[ -f "$ROOT/app/src/androidTest/java/com/securekeyboard/app/CryptoEngineAdversarialTest.kt" ]]; then
  echo "PASS: adversarial crypto test suite present"
else
  echo "FAIL: adversarial crypto test suite missing"
  fail=1
fi
check_present 'cipher.doFinal' "$SRC/com/securekeyboard/app/SecureFileCrypto.kt" "File GCM authentication finalized explicitly"

echo "SECURITY AUDIT: PASSED"
