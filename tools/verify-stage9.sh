#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"; OUT="$ROOT/build/stage9-harness"
rm -rf "$OUT" && mkdir -p "$OUT"
kotlinc "$ROOT/tools/Stage9Harness.kt" -include-runtime -d "$OUT/stage9-harness.jar"
java -jar "$OUT/stage9-harness.jar" "$ROOT" | tee "$OUT/stage9-harness-verification.txt"
python3 "$ROOT/tools/verify-stage9.py" | tee "$OUT/stage9-static-verification.txt"
if [[ "${NOODLE_SKIP_SYNTAX_SMOKE:-0}" == "1" ]]; then
  echo "SKIP | Kotlin parser syntax smoke (already covered by full regression)" | tee "$OUT/stage9-syntax-smoke.txt"
else
  bash "$ROOT/tools/verify-stage4-syntax.sh" | tee "$OUT/stage9-syntax-smoke.txt"
fi
