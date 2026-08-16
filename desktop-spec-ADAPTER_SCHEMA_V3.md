# SNP Adapter Schema v3

Schema v3 extends v2 without removing v1/v2 loading compatibility. Bundled adapters in Noodle Desktop 1.9 use v3.

## New fields

```json
{
  "version": 3,
  "engine": "kvs_videos",
  "engine_dependencies": [
    "jwplayer_config",
    "videojs_config",
    "generic_html5",
    "oembed_provider",
    "iframe_chain",
    "hls_manifest",
    "dash_manifest"
  ],
  "repair": {
    "enabled": false,
    "mode": "conservative",
    "failure_threshold": 3,
    "cooldown_minutes": 60,
    "auto_quarantine": false,
    "strategies": ["resolver_refresh"]
  }
}
```

`engine_dependencies` contains canonical registered engine IDs. Required dependencies are checked before the SNP can run. Unknown dependencies, self-dependencies and missing required dependencies are rejected.

## Repair policy

Only `conservative` repair is accepted. Supported strategies are:

- `selector_relearn` — for eligible user `declarative_catalog` adapters; re-learns CSS-lite selectors and validates the candidate through real search/playback before replacement.
- `resolver_refresh` — refreshes the safe resolver strategy chain and validates the candidate before replacement.

`failure_threshold` is 1..20. `cooldown_minutes` is 0..10080. `auto_quarantine=true` disables a repeatedly failing user source after an unsuccessful permitted repair.

Bundled adapters may be monitored but are shipped with automatic repair disabled; bundled files are not rewritten by the repair pipeline.

## Breakage detection

A Schema v3 adapter is not repaired after a single transient error. `AdapterBreakageMonitor` persists consecutive semantic failures. Below the threshold the source is `degraded`; at the threshold it becomes `broken`. Repair runs only when enabled and outside cooldown.

Possible states include `healthy`, `degraded`, `broken`, `repair_succeeded`, `repair_failed`, `quarantined`, and `invalid`.

## Migration v1/v2 -> v3

Migration is candidate-based:

1. load old adapter;
2. generate a v3 candidate;
3. validate Schema/URL/dependencies/repair policy;
4. delete the temporary candidate so it cannot leak into a snapshot;
5. snapshot the current user SNP when requested;
6. write the validated v3 JSON;
7. rebuild `snp-package.json` when the package already uses an integrity manifest.

Bundled SNPs migrate with application releases. User SNPs may be migrated from Source Center or with `--migrate-adapters-v3`.

## Compatibility

v1 and v2 remain loadable. Legacy v2 `health.auto_repair` continues to use legacy repair behavior; v3 uses threshold/cooldown policy from `repair`.
