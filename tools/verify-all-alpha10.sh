#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"

echo '=== Noodle Mobile alpha10 full portable regression ==='
./tools/verify-core.sh
python3 tools/verify-stage3.py
./tools/verify-stage3-syntax.sh
./tools/verify-stage4.sh
python3 tools/verify-stage4.py
./tools/verify-stage5.sh
python3 tools/verify-stage5.py
./tools/verify-stage6.sh
./tools/verify-stage7.sh
./tools/verify-stage8.sh
./tools/verify-stage9.sh
./tools/verify-stage10.sh

echo '=== alpha10 portable regression complete ==='
