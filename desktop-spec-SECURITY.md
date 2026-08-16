# Security

## URL policy

The application blocks `javascript:`, `data:`, `vbscript:`, `file:`, `ftp:`, unknown schemes, loopback hosts, and private-network hosts by default. HTTP is accepted only where the caller explicitly marks a legacy HTTP URL.

## Transport

A failed HTTPS request is never automatically repeated over HTTP. Any future HTTP-only adapter must declare and document that requirement explicitly.

## Adapter archives

Adapter ZIP files are extracted only into the writable custom-adapter directory. Every entry is canonicalized before extraction; absolute paths, `..` traversal segments, paths escaping the destination, and colon-based alternate data streams are rejected.

## Logs

The logger masks common token, authorization, cookie, session, password, and key values. Diagnostic reports must not include cookies, browser local storage, authorization headers, private keys, or unredacted user-profile path prefixes.

## Release handling

Never include these items in a release package:

- WebView2 profiles;
- cookies or caches;
- logs from development machines;
- `.dcu`, `.map`, `.tds`, and debug symbols unless distributed separately;
- signing keys or certificates;
- downloaded media;
- temporary HTML snapshots.

## Reporting

Security reports should include application version, Windows version, architecture, reproduction steps, and a sanitized diagnostic report.


## Stage 3+ adaptive resolvers

См. `STAGE3_PLAYER_RESOLVERS_REPORT_RU.md`. Новые стратегии: `jwplayer_config`, `videojs_config`, `iframe_chain`; KVS flashvars обслуживается отдельным resolver. Для HLS release asset выполнить `scripts\fetch-player-assets.ps1`.


## Stage 5 declarative adapters

Declarative adapters are intentionally not scripts. CSS-lite selectors permit only a tag, one class and/or one ID. JSON mappings use a restricted dot/index path syntax. Search endpoints, discovered watch URLs, thumbnails, oEmbed endpoints and resolver follow-ups must pass both `TUrlPolicy` and the adapter `allowed_hosts` boundary before network access or navigation.

Source-health checks are explicit user actions. They use bounded timeouts and never attempt CAPTCHA solving, authentication bypass or DRM bypass.

## Stage 6 SNP package integrity and recovery

`snp-package.json` is an integrity manifest, not a substitute for a cryptographic publisher signature. SHA-256 protects the package against unnoticed file changes after a trusted manifest has been obtained, but by itself does not authenticate who created the manifest.

The loader rejects packaged adapters when hashes, sizes, IDs, versions, publishers or file lists do not match. Unexpected files not covered by the manifest are rejected. A signature value without a configured cryptographic verification provider is reported as `signature_present_unverified` and is never displayed as trusted publisher identity.

Before a custom adapter update is activated, the current version is copied to the internal `_history` tree. Update staging and repair candidates live in `_updates`; both internal trees are excluded from normal source discovery. Failed pending updates are rolled back after semantic validation.

Conservative auto-repair is restricted to user-owned declarative CSS-lite adapters and replaces selectors only after the candidate passes a real bounded search/playback probe. It does not solve CAPTCHA, bypass authentication/DRM, execute scripts from the adapter or mutate bundled adapters.

## Signed source catalog

Catalog index не считается доверенным до RSA-SHA256 проверки точных payload bytes через Windows CNG. В приложении нет приватных ключей. Production key registry не предзаполнен: доверенный ключ должен поставляться дистрибутивом или явно добавляться пользователем. Auto-check и auto-install отключены по умолчанию; auto-maintenance не устанавливает новые источники и обновляет только уже установленные custom SNP через snapshot/pending-semantic-validation pipeline.


## Source catalog trust boundary

Catalog payload используется только после RSA-SHA256/PKCS#1 v1.5 проверки Windows CNG. Доверие задаётся публичными ключами, а не сетевым происхождением URL. SHA-256 package hash обязателен для catalog entry; `adapter_id`, version и publisher повторно сверяются после распаковки. Непроверенные обновления временно отключаются до semantic validation. Production trusted key в репозиторий не встроен.

## Schema v3 repair safety

Repair is conservative and custom-adapter-only. It never rewrites bundled SNP packages, never executes adapter-provided code, and never expands `allowed_hosts`. A candidate must pass schema/URL/dependency validation and semantic search/playback validation before replacement. Automatic repair is thresholded and cooled down; optional quarantine disables a repeatedly failing user source rather than repeatedly mutating it.
