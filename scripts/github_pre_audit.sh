#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"

echo '== SecureKeyboard V40 GitHub pre-audit =='
./scripts/security_audit.sh
./scripts/security_hardening_audit.sh
./scripts/crypto_key_management_audit.sh
./scripts/v40_audit.sh

echo 'Static audits passed. Build/test is delegated to GitHub Actions.'
