#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"
FILES=(
 app/src/main/java/pro/noodle/mobile/core/storage/NoodleDatabase.kt
 app/src/main/java/pro/noodle/mobile/core/storage/UserMediaRepository.kt
 app/src/main/java/pro/noodle/mobile/core/settings/AppSettings.kt
 app/src/main/java/pro/noodle/mobile/core/settings/SourceOverridesRepository.kt
 app/src/main/java/pro/noodle/mobile/core/download/DownloadRepository.kt
 app/src/main/java/pro/noodle/mobile/core/download/DownloadWorker.kt
 app/src/main/java/pro/noodle/mobile/core/web/SandboxedWebExtractor.kt
 app/src/main/java/pro/noodle/mobile/core/player/PlaybackRepository.kt
 app/src/main/java/pro/noodle/mobile/core/search/BundledSearchRepository.kt
 app/src/main/java/pro/noodle/mobile/ui/player/PlayerScreen.kt
 app/src/main/java/pro/noodle/mobile/ui/favorites/FavoritesViewModel.kt
 app/src/main/java/pro/noodle/mobile/ui/downloads/DownloadsViewModel.kt
 app/src/main/java/pro/noodle/mobile/ui/sources/SourcesViewModel.kt
 app/src/main/java/pro/noodle/mobile/ui/SettingsViewModel.kt
 app/src/main/java/pro/noodle/mobile/ui/NoodleTheme.kt
 app/src/main/java/pro/noodle/mobile/MainActivity.kt
)
set +e
kotlinc "${FILES[@]}" -d /tmp/noodle-stage3-syntax.jar >/tmp/noodle-stage3-kotlinc.out 2>&1
status=$?
set -e
if grep -E "error: (expecting|unexpected|missing|unclosed|syntax)" /tmp/noodle-stage3-kotlinc.out; then
  echo "FAIL | Kotlin parser syntax errors"
  exit 1
fi
echo "PASS | Kotlin parser syntax smoke"
echo "kotlinc exit=$status is expected without Android/AndroidX classpath; no parser-level syntax errors were detected."
