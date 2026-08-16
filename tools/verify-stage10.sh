#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"; OUT="$ROOT/build/stage10-harness"
rm -rf "$OUT" && mkdir -p "$OUT"
kotlinc "$ROOT/tools/Stage10Harness.kt" -include-runtime -d "$OUT/stage10-harness.jar"
java -jar "$OUT/stage10-harness.jar" "$ROOT" | tee "$OUT/stage10-harness-verification.txt"
python3 "$ROOT/tools/verify-stage10.py" | tee "$OUT/stage10-static-verification.txt"
if [[ "${NOODLE_SKIP_SYNTAX_SMOKE:-0}" == "1" ]]; then
  echo "SKIP | Kotlin parser syntax smoke (covered by full regression)" | tee "$OUT/stage10-syntax-smoke.txt"
else
  bash "$ROOT/tools/verify-stage4-syntax.sh" | tee "$OUT/stage10-syntax-smoke.txt"
fi
