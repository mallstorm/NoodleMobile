#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"
mapfile -t FILES < <(find app/src/main/java -name '*.kt' | sort)
set +e
kotlinc "${FILES[@]}" -d /tmp/noodle-stage4-syntax.jar >/tmp/noodle-stage4-kotlinc.out 2>&1
status=$?
set -e
if grep -E "error: (expecting|unexpected|missing|unclosed|syntax)" /tmp/noodle-stage4-kotlinc.out; then
  echo "FAIL | Kotlin parser syntax errors"
  exit 1
fi
echo "PASS | Kotlin parser syntax smoke"
echo "kotlinc exit=$status is expected without Android/AndroidX/OkHttp classpath; no parser-level syntax errors were detected."
