#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
OUT="$ROOT/build/stage6-harness"
rm -rf "$OUT" && mkdir -p "$OUT"
kotlinc \
  "$ROOT/app/src/main/java/pro/noodle/mobile/core/download/AdaptiveDownloadSelection.kt" \
  "$ROOT/tools/Stage6Harness.kt" \
  -include-runtime -d "$OUT/stage6-harness.jar"
java -jar "$OUT/stage6-harness.jar" | tee "$OUT/stage6-verification.txt"
python3 "$ROOT/tools/verify-stage6.py" | tee "$OUT/stage6-static-verification.txt"
bash "$ROOT/tools/verify-stage4-syntax.sh" | tee "$OUT/stage6-syntax-smoke.txt"
