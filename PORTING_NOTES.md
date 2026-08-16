# Noodle Desktop -> Android porting notes

## Stage 9 validator inconsistency fixed since Android alpha1

Desktop `EngineRegistry.pas` registers `kvs_flashvars` as an alias of canonical engine `kvs_view`.
At the same time `AdapterValidator.pas` documents `kvs_flashvars` as a legacy/meta `view.strategies` token that should be ignored by engine-dependency declaration checks.

Because the Desktop code checks `IsKnownEngine(strategy)` before the legacy/meta intent can apply, `kvs_flashvars` normalizes to `kvs_view`. For adapters whose primary engine is `kvs_videos` or `novideo_embed`, this can yield `ADAPTER_VIEW_ENGINE_UNDECLARED`, although the bundled SNP is intended to be valid.

The Android port resolves the contradiction conservatively by checking the raw `kvs_flashvars` token before alias normalization. It remains a non-executable legacy resolver hint and cannot introduce code or broaden permissions.

The original Desktop source remains unchanged in the reference archive.

## Android alpha3 persistence rule

User-owned state is not written back into bundled/signed `adapter.json` files.

Enabled/disabled source overrides live separately in DataStore. Favorites, playback positions and download jobs live in Room. This preserves the Desktop Stage 9 principle that package updates and integrity-protected source metadata must not overwrite user preferences.

Room destructive migration is intentionally not enabled. Future DB schema versions must provide explicit migrations for user-owned data.

## Android alpha3 WebView rule

WebView is an opt-in extraction fallback, never the primary player. It is created only for adapters with `allow_javascript=true`, receives no file/content access, denies permissions/geolocation/mixed content/TLS bypass, is constrained to SNP hosts and safe DNS targets, and is destroyed after bounded DOM extraction.

## Android alpha4/alpha5 lifecycle and ecosystem rule

SNP packages are kept platform-neutral and continue to use Desktop Stage 9 compatibility level `1.9.0`; the independent Android app version does not participate in adapter compatibility comparisons. Custom packages use integrity manifests, snapshots, staged semantic validation and rollback. Signed Marketplace payloads are verified before parsing, and trusted-key storage contains public RSA data only.

## Android alpha6 adaptive-offline rule

HLS/DASH offline download selection is no longer represented by a raw all-tracks `DownloadRequest`. `DownloadHelper.Factory` prepares the adaptive item using the SNP policy-aware data source, applies video/audio/text selection parameters, and only then emits a `DownloadRequest` containing the selected stream keys. The request is persisted by Media3 and recoverable from `DownloadIndex` for resume.

Completed adaptive media is played from the Media3 cache with upstream disabled. A cache miss is an offline error, not permission to contact the remote source. This preserves the meaning of the offline library and prevents hidden network fallback.

Media3 `DownloadManager` is treated as thread-affine. First construction and mutable manager operations are main-looper guarded; blocking `DownloadIndex` reads are performed on IO only after the holder has been initialized on main.

## Android alpha7 build/API stabilization rule

`0.7.0-alpha7` treats Media3 `DownloadManager` and adaptive `DownloadHelper` setup as main-looper-affined integration points. IO-scoped callers must bridge to `Dispatchers.Main.immediate` for those short control-plane operations; network and disk workloads remain off the UI thread. `DownloadHelper.Factory` must receive a `DefaultRenderersFactory` so track selection has renderer capabilities. Background adaptive downloads use `WorkManagerScheduler` plus the Media3 download-service RESTART manifest action.

## Android alpha9 build-certification rule

Stage 9 treats dependency coordinates and Android build tools as certification inputs, not documentation hints. The stable Compose BOM is pinned to `2026.06.00`; Media3 remains on stable `1.10.1`; API 37 is built with Build Tools `36.0.0`. `tools/bootstrap-android-toolchain.sh` verifies official Gradle and Android command-line-tools SHA-256 values before installation, and `tools/verify-apk.sh` validates package/version/target SDK plus ZIP integrity before an APK may be called certified.


## Android alpha10 first-test-APK rule

`0.10.0-alpha10` keeps AGP 9 built-in Kotlin and explicitly supplies the requested KGP/KSP runtime classpaths at the top level when using Kotlin `2.4.10` and KSP `2.3.11`. The certification pipeline is strictly ordered as unit tests -> lint -> debug APK -> APK metadata/structure verification -> androidTest APK -> androidTest verification, with connected instrumentation executed only when a real device/emulator is available. A missing toolchain or failed compiler step is a blocking result and must never be represented as a built APK.
