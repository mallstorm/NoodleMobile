#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
OUT="$ROOT/build/core-harness"
rm -rf "$OUT" && mkdir -p "$OUT"
# Plain-JVM verification compiles only platform-neutral code. Android Context,
# OkHttp/Media3 and coroutine UI repositories are exercised by Android build/tests.
CORE="$ROOT/app/src/main/java/pro/noodle/mobile/core"
mapfile -t REL_FILES < <(cd "$CORE" && find . -name '*.kt' \
  ! -path './network/*' ! -path './search/*' ! -path './player/*' ! -path './storage/*' ! -path './settings/*' ! -path './download/*' ! -path './web/*' ! -path './source/*' ! -path './maintenance/*' ! -path './catalog/*' ! -path './studio/*' ! -path './diagnostics/*' \
  ! -name 'BundledAdapterRepository.kt' | sort)
FILES=()
for f in "${REL_FILES[@]}"; do FILES+=("$CORE/${f#./}"); done
kotlinc "${FILES[@]}" "$ROOT/tools/CoreHarness.kt" -include-runtime -d "$OUT/core-harness.jar"
(cd "$ROOT" && java -jar "$OUT/core-harness.jar" "app/src/main/assets/adapters/bundled") | tee "$OUT/core-verification.txt"
kotlinc "${FILES[@]}" "$ROOT/tools/Stage2Harness.kt" -include-runtime -d "$OUT/stage2-harness.jar"
(cd "$ROOT" && java -jar "$OUT/stage2-harness.jar") | tee "$OUT/stage2-verification.txt"
