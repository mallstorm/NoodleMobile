#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"
./tools/verify-core.sh
python3 ./tools/verify-stage3.py
./tools/verify-stage4.sh
python3 ./tools/verify-stage4.py
./tools/verify-stage5.sh
python3 ./tools/verify-stage5.py
./tools/verify-stage6.sh
printf '\nNoodle Mobile alpha6 verification suite: PASS\n'
