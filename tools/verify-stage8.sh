#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
OUT="$ROOT/build/stage8-harness"
rm -rf "$OUT" && mkdir -p "$OUT"
kotlinc "$ROOT/tools/Stage8Harness.kt" -include-runtime -d "$OUT/stage8-harness.jar"
java -jar "$OUT/stage8-harness.jar" "$ROOT" | tee "$OUT/stage8-harness-verification.txt"
python3 "$ROOT/tools/verify-stage8.py" | tee "$OUT/stage8-static-verification.txt"
bash "$ROOT/tools/verify-stage4-syntax.sh" | tee "$OUT/stage8-syntax-smoke.txt"
