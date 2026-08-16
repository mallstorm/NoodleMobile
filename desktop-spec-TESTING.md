# Testing

## DUnitX

Run on Windows from a RAD Studio command prompt:

```powershell
.\scripts\test.ps1 -Platform Win32
.\scripts\test.ps1 -Platform Win64
```

The current suite covers:

- URL scheme, HTTPS downgrade, loopback/private-host and media policy;
- writable data/WebView profile paths;
- safe ZIP entry canonicalization;
- SNP v1/v2 validation, engine identity and allowed hosts;
- Registry IDs, aliases and capabilities;
- generic HTML5, OpenGraph and JSON-LD extraction;
- KVS search, playback and protected URL normalization;
- JW Player and Video.js DOM/quoted JSON configurations;
- bounded iframe-chain behavior;
- HLS master/media playlists, variants, audio/subtitle renditions and DRM refusal;
- DASH video/audio/text metadata, BaseURL handling and DRM refusal;
- HLS/DASH pipeline expansion and fetch-failure fallback;
- pinned hls.js/dash.js provider versions and controlled missing-asset scripts.

## Self-test

```powershell
NoodleDesktop.exe --self-test
```

Exit codes:

- `0` — all core checks, WebView2 and both adaptive runtimes available;
- `2` — data directory is not writable;
- `3` — adapters are missing or invalid;
- `4` — WebView2 Runtime was not detected;
- `5` — diagnostic report creation failed;
- `6` — engine registry/capability validation failed;
- `7` — local hls.js runtime is missing;
- `9` — local dash.js runtime is missing.

When both adaptive files are absent, code `7` is returned first; the log still records both missing assets.

## Static verification

```powershell
python .\scripts\static_verify.py
```

The verifier checks:

- JSON/XML validity;
- all DPR unit references and DPR↔DPROJ parity;
- existence of referenced Pascal units;
- unique unit/program names;
- basic Delphi lexer/delimiter integrity;
- absence of silently swallowed exceptions;
- engine registration and required integration markers;
- SNP v2 permissions and strategy metadata;
- player runtime/provider/telemetry markers;
- forbidden EXE/DCU/RES/log/WebView profile artifacts.

## Required Windows manual tests

1. Build Win32 and Win64 from a clean checkout.
2. Run DUnitX for both platforms.
3. Run `--self-test` with and without WebView2/HLS/DASH runtimes.
4. Launch as a non-administrator from a read-only installation directory.
5. Test profile recovery and closure during WebView creation.
6. Play direct MP4/WebM, HLS master/media and DASH MPD samples.
7. Switch automatic/manual quality, audio and subtitle tracks repeatedly.
8. Confirm adaptive readiness/errors appear in Delphi diagnostics.
9. Validate unsupported DRM produces a controlled message and no bypass attempt.
10. Validate iframe depth/page/manifest limits and host-policy rejection.
11. Open/close the player at least 50 times and inspect memory/handles.
12. Run every enabled live adapter and record site-layout failures separately.


## Stage 5 fixtures

DUnitX now covers `declarative_catalog`, `json_api_catalog`, `oembed_provider` and `wordpress_rest`, including CSS-lite parsing, JSON path extraction, oEmbed discovery and WordPress REST normalization. Adapter schema tests verify declarative field loading and reject missing selector configurations.

Manual release verification should additionally run:

```powershell
NoodleDesktop.exe --health-check
```

Review the generated `source_health_*.json` and confirm that no source outside installed SNP adapters was contacted.

## Stage 6 adapter lifecycle checks

Additional DUnitX coverage includes SemVer parsing/comparison, package hash tamper detection, rejection of unlisted package files and Adapter Studio draft generation. Runtime semantic checks are available separately:

```text
NoodleDesktop.exe --semantic-health
NoodleDesktop.exe --adapter-update-check
```

`--semantic-health` performs an explicit network operation. For adapters with a pending update it confirms the update on success or restores the latest snapshot on failure. Eligible custom declarative adapters may attempt conservative candidate repair only when no pending-update rollback takes precedence.

Because this source package is prepared outside Windows/RAD Studio, DUnitX compilation/execution and real WebView2/network integration must still be run on the supported Windows toolchain before a release is declared complete.

## Stage 7

Добавлены DUnitX fixtures для persistent adapter overrides и RSA-SHA256 known-vector verification через Windows CNG. На Windows обязательно проверить: Source Center toggle persistence после перезапуска, disabled-source semantic probe, update download + pending validation, rollback, catalog unknown-key rejection, valid RSA catalog signature, modified payload rejection и opt-in auto-maintenance.

## Stage 8

Дополнительные DUnitX-наборы проверяют SemVer compatibility range, отказ при слишком новой/старой версии приложения, некорректный диапазон и требования к RSA public key (минимум 2048 бит и допустимый exponent). `TestAdapterSchema` проверяет чтение и отказ для некорректных `min_app_version/max_app_version`.

## Stage 9 test matrix

Additional DUnitX fixtures cover engine dependency closure, Schema v3 repair-policy validation and a real temporary-file v2 -> v3 migration. Static verification also requires bundled Schema v3, Stage 9 DPR/DPROJ references, CLI commands and repair/migration safety markers. Network semantic repair remains an integration test for a Windows/RAD Studio environment.
