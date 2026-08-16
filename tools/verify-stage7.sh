#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
OUT="$ROOT/build/stage7-harness"
rm -rf "$OUT" && mkdir -p "$OUT"
kotlinc "$ROOT/tools/Stage7Harness.kt" -include-runtime -d "$OUT/stage7-harness.jar"
java -jar "$OUT/stage7-harness.jar" "$ROOT" | tee "$OUT/stage7-harness-verification.txt"
python3 "$ROOT/tools/verify-stage7.py" | tee "$OUT/stage7-static-verification.txt"
bash "$ROOT/tools/verify-stage4-syntax.sh" | tee "$OUT/stage7-syntax-smoke.txt"
