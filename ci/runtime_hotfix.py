#!/usr/bin/env python3
from pathlib import Path
import hashlib
import json
import sys

root = Path(sys.argv[1] if len(sys.argv) > 1 else "work/NoodleMobile_0.10.0-alpha10_SOURCE")
if not root.is_dir():
    raise SystemExit(f"project root missing: {root}")

# Explicit trusted playback/CDN hosts observed in the bundled Stage 9 fixtures.
# Deliberately excludes analytics/ad/VAST/redirect domains.
PLAYBACK_HOSTS = {
    "noodlemagazine.com": [
        "pvvstream.pro", "*.pvvstream.pro",
        "nmcorp.video", "*.nmcorp.video",
    ],
    "video-laba.click": [
        "vwfastdelivery.com", "*.vwfastdelivery.com",
        "players-services.win", "*.players-services.win",
    ],
    "pornobomba.in": [
        "pbembed.me", "*.pbembed.me",
        "porno-bomba.net", "*.porno-bomba.net",
    ],
}

bundled = root / "app/src/main/assets/adapters/bundled"
for adapter_id, extra_hosts in PLAYBACK_HOSTS.items():
    adapter_dir = bundled / adapter_id
    adapter_path = adapter_dir / "adapter.json"
    manifest_path = adapter_dir / "snp-package.json"
    data = json.loads(adapter_path.read_text(encoding="utf-8-sig"))
    permissions = data.setdefault("permissions", {})
    hosts = [str(v).strip().lower() for v in permissions.get("allowed_hosts", []) if str(v).strip()]
    for host in extra_hosts:
        if host not in hosts:
            hosts.append(host)
    permissions["allowed_hosts"] = hosts
    adapter_text = json.dumps(data, ensure_ascii=False, indent=2) + "\n"
    adapter_path.write_text(adapter_text, encoding="utf-8")

    manifest = json.loads(manifest_path.read_text(encoding="utf-8-sig"))
    raw = adapter_path.read_bytes()
    for item in manifest.get("files", []):
        if item.get("path") == "adapter.json":
            item["sha256"] = hashlib.sha256(raw).hexdigest()
            item["size"] = len(raw)
            break
    else:
        raise SystemExit(f"adapter.json missing from package manifest: {adapter_id}")
    manifest_path.write_text(json.dumps(manifest, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")

# Improve the user-facing failure so the next site-side change is diagnosable from one screenshot.
playback = root / "app/src/main/java/pro/noodle/mobile/core/player/PlaybackRepository.kt"
text = playback.read_text(encoding="utf-8")
old = '''        if (safeSources.isEmpty()) {
            return@withContext PlaybackLoadResult(
                false,
                errorCode = "PLAYBACK_NO_ALLOWED_SOURCE",
                errorMessage = "Resolver нашёл media URL, но ни один из них не прошёл SNP host/security policy.",
            )
        }
'''
new = '''        if (safeSources.isEmpty()) {
            val blockedHosts = resolved.sources.mapNotNull { source ->
                runCatching { URI(source.url).host?.lowercase()?.takeIf { it.isNotBlank() } }.getOrNull()
            }.distinct().take(6)
            val hostDetails = blockedHosts.joinToString(", ").takeIf { it.isNotBlank() }?.let { " Заблокированные hosts: $it." }.orEmpty()
            return@withContext PlaybackLoadResult(
                false,
                errorCode = "PLAYBACK_NO_ALLOWED_SOURCE",
                errorMessage = "Источник ${adapter.id}: Resolver нашёл media URL, но ни один из них не прошёл SNP host/security policy.$hostDetails",
            )
        }
'''
if old not in text:
    raise SystemExit("PlaybackRepository diagnostic anchor missing")
playback.write_text(text.replace(old, new, 1), encoding="utf-8")

# Make the installed test build visibly distinguishable without changing package/version identity.
identity = root / "app/src/main/java/pro/noodle/mobile/core/version/BuildIdentity.kt"
text = identity.read_text(encoding="utf-8")
text = text.replace(
    'const val STAGE_LABEL = "0.10 alpha • First Test APK Candidate"',
    'const val STAGE_LABEL = "0.10 alpha • Playback Host Hotfix 1"',
    1,
)
identity.write_text(text, encoding="utf-8")

print("runtime hotfix applied: explicit bundled playback hosts + package hashes + blocked-host diagnostics")
