#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"
PASS=0; FAIL=0
check(){ local name="$1"; shift; if "$@" >/dev/null 2>&1; then echo "PASS: $name"; PASS=$((PASS+1)); else echo "FAIL: $name"; FAIL=$((FAIL+1)); fi; }
check "No INTERNET permission" bash -c '! grep -Eq "<uses-permission[^>]+android:name=\"android.permission.INTERNET\"" app/src/main/AndroidManifest.xml'
check "No WebView/dynamic dex loading" bash -c '! grep -RIsEq "WebView|DexClassLoader|PathClassLoader|loadUrl" app/src/main/java'
check "No shell/process execution APIs" bash -c '! grep -RIsEq "ProcessBuilder|Runtime\.getRuntime\(\).*exec|java\.lang\.Runtime" app/src/main/java'
check "SecurityApplication registered" grep -q 'android:name=".SecurityApplication"' app/src/main/AndroidManifest.xml
check "Runtime security checks present" test -f app/src/main/java/com/securekeyboard/app/SecurityRuntime.kt
check "V40 audit document present" test -f docs/security/V40_SECURITY_AUDIT_READINESS.md
check "File decryption hardening present" grep -q 'verifyContent' app/src/main/java/com/securekeyboard/app/SecureFileCrypto.kt
printf '\nV40 static audit: %d PASS, %d FAIL\n' "$PASS" "$FAIL"
test "$FAIL" -eq 0
