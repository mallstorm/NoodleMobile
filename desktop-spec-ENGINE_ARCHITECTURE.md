# Engine Architecture

## Contract

`src/Engines/EngineTypes.pas` defines the engine-neutral model:

- `TEngineCapabilities`;
- search requests/items;
- media sources;
- adaptive video variants;
- audio tracks;
- text tracks;
- resolve results;
- `ISourceEngine`.

Each engine exposes a stable ID, aliases, capabilities, search URL construction, optional search parsing and optional playback resolution.

## Registry

`TEngineRegistry` is initialized once and protected by `TMonitor`. It contains fifteen canonical engines:

| Engine | Responsibility |
|---|---|
| `kvs_view` | KVS View search/playback profile |
| `kvs_videos` | KVS Videos search/playback profile |
| `noodle_watch_playlist` | existing Noodle playlist compatibility |
| `sosalkino_catalog` | existing catalog compatibility |
| `novideo_embed` | existing embed compatibility |
| `generic_html5` | HTML5, OpenGraph, JSON-LD and direct iframe discovery |
| `hls_manifest` | HLS master/media manifests, variants, audio and subtitles |
| `dash_manifest` | MPD variants, audio/text metadata and DRM detection |
| `jwplayer_config` | JW Player setup/sources/tracks/playlist URL extraction |
| `videojs_config` | Video.js DOM and JSON configuration extraction |
| `iframe_chain` | bounded follow-up iframe discovery |
| `declarative_catalog` | CSS-lite data-driven HTML catalog search |
| `json_api_catalog` | restricted JSON-path API catalog search |
| `oembed_provider` | official JSON oEmbed discovery and embed metadata |
| `wordpress_rest` | WordPress REST search profile |

Aliases are normalized before dispatch. Unknown engine IDs are rejected by adapter validation.

## Dispatch

```text
adapter.json
  → AdapterSchema / permissions
  → canonical engine ID
  → EngineRegistry
  → search engine (legacy / declarative HTML / JSON API / WordPress)
  → primary resolver
  → ordered view.strategies
  → bounded follow-up pages
  → HLS/DASH manifest expansion
  → URL/host policy filter
  → TVideoItem
  → WebView2 player descriptor
```

Legacy parsers remain only where a modular engine has not yet fully replaced an established source-specific path.

## Resolver pipeline

`TResolverPipeline`:

- normalizes strategy aliases;
- merges titles, posters, player pages, sources, variants, audio and text tracks;
- deduplicates all output classes;
- follows at most 8 pages and 3 iframe levels;
- expands at most 6 HLS and 4 DASH manifests;
- removes a protected manifest source after a controlled DRM refusal;
- preserves a working HTML5/source fallback when a dependent manifest request fails.

Network access is provided through a fetch callback owned by `NoodleClient`. The callback applies `TUrlPolicy` and SNP `allowed_hosts` before the request.

## HLS

`THlsManifestEngine` handles:

- master and media playlists;
- `EXT-X-STREAM-INF` and I-frame variants;
- structured width, height, bitrate and codec metadata;
- `EXT-X-MEDIA` audio and subtitle renditions;
- adaptive master source plus explicit child variants;
- SAMPLE-AES/session-key/non-identity key-format refusal.

The master manifest remains the default playback URL so hls.js can preserve ABR and live track switching. Child playlists remain available as explicit variants/download choices.

## DASH

`TDashManifestEngine` handles:

- MPD detection;
- video AdaptationSet/Representation metadata;
- audio AdaptationSet metadata;
- direct text BaseURL metadata;
- MPD/root BaseURL resolution;
- highest-bandwidth default metadata;
- Widevine, PlayReady, FairPlay/CENC/default_KID refusal.

The MPD itself remains the playback source. dash.js obtains the actual live track list after initialization.

## Player bridge

The player shell receives a JSON descriptor containing variants, audio tracks and text tracks. Local hls.js/dash.js instances then populate live menus and send WebMessage telemetry back to Delphi:

- adaptive runtime ready;
- quality/audio/subtitle changes;
- HLS/DASH errors;
- runtime-asset failures.

This keeps UI state observable from the native application rather than trapping errors inside JavaScript.

## Source health

`TSourceHealthMonitor` validates every adapter and performs an explicit bounded availability probe only when the user asks for it. It reports healthy, partial, unavailable, restricted, JavaScript-gated and invalid-adapter states. `--diagnostics` does not silently perform those requests; the most recent explicit health report is merely attached to the diagnostic bundle.

## Next targets

- adapter update catalog with signed metadata;
- visual Adapter Studio for generating CSS-lite/JSON mappings;
- per-source health history and DOM/API fingerprinting;
- extraction of remaining legacy Noodle/catalog paths from `NoodleClient.pas`;
- opt-in WebView DOM engine for JavaScript-rendered pages.

## Explicit dependencies (Stage 9)

`EngineDependencies.pas` owns hard/optional/recommended engine relations. Schema v3 stores the declared canonical IDs in `engine_dependencies`. Validation is transitive: a declared helper's own hard requirements must also be declared. This keeps future resolver composition explicit instead of relying on hidden unit-level assumptions.
