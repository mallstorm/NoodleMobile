#!/usr/bin/env python3
from __future__ import annotations

from pathlib import Path
from urllib.parse import quote, urljoin, urlparse
from urllib.request import Request, build_opener, HTTPCookieProcessor
from http.cookiejar import CookieJar
import html
import json
import re
import ssl
import sys

ROOT = Path(sys.argv[1] if len(sys.argv) > 1 else ".")
OUT = Path(sys.argv[2] if len(sys.argv) > 2 else ROOT / "build-certification/LIVE_SOURCE_PROBE.json")
BUNDLED = ROOT / "ci/fixtures/bundled"
UA = "NoodleMobile-LiveSourceProbe/0.10 (technical compatibility probe; no content retained)"
MAX_BYTES = 2 * 1024 * 1024
TIMEOUT = 20
QUERIES = ["video", "секс"]

ctx = ssl.create_default_context()
opener = build_opener(HTTPCookieProcessor(CookieJar()))


def fetch(url: str) -> dict:
    req = Request(url, headers={
        "User-Agent": UA,
        "Accept": "text/html,application/xhtml+xml,application/json;q=0.8,*/*;q=0.3",
        "Accept-Language": "ru,en;q=0.7",
    })
    try:
        with opener.open(req, timeout=TIMEOUT, context=ctx) if False else opener.open(req, timeout=TIMEOUT) as r:
            raw = r.read(MAX_BYTES + 1)
            truncated = len(raw) > MAX_BYTES
            raw = raw[:MAX_BYTES]
            ctype = r.headers.get("Content-Type", "")
            charset = "utf-8"
            m = re.search(r"charset=([\w.-]+)", ctype, re.I)
            if m:
                charset = m.group(1)
            try:
                text = raw.decode(charset, errors="replace")
            except LookupError:
                text = raw.decode("utf-8", errors="replace")
            return {
                "ok": True,
                "status": getattr(r, "status", 200),
                "final_url": r.geturl(),
                "content_type": ctype.split(";", 1)[0].strip().lower(),
                "bytes": len(raw),
                "truncated": truncated,
                "text": text,
            }
    except Exception as e:
        code = getattr(e, "code", None)
        final = getattr(e, "url", url)
        return {"ok": False, "status": code, "final_url": final, "error": f"{type(e).__name__}: {e}", "text": ""}


def host_of(value: str, base: str) -> str:
    try:
        u = urljoin(base, html.unescape(value.strip()))
        h = (urlparse(u).hostname or "").lower()
        return h
    except Exception:
        return ""


def path_of(value: str, base: str) -> str:
    try:
        u = urlparse(urljoin(base, html.unescape(value.strip())))
        path = u.path or "/"
        if u.query:
            path += "?" + u.query[:160]
        return path[:240]
    except Exception:
        return ""


def attr_values(text: str, attr: str) -> list[str]:
    pat = re.compile(rf"\b{re.escape(attr)}\s*=\s*([\"'])(.*?)\1", re.I | re.S)
    return [html.unescape(m.group(2).strip()) for m in pat.finditer(text)]


def link_values(text: str) -> list[str]:
    return attr_values(text, "href")


def classify(text: str, base: str) -> dict:
    hrefs = link_values(text)
    srcs = []
    for a in ("src", "data-src", "data-original", "data-preview", "poster"):
        srcs.extend(attr_values(text, a))

    watch = []
    counters = {"/view/": 0, "/videos/": 0, "/video/": 0, "/watch/": 0, "/novideo/": 0, "/out/?ps=": 0}
    for h in hrefs:
        p = path_of(h, base)
        low = p.lower()
        for key in counters:
            if key in low:
                counters[key] += 1
        if any(marker in low for marker in ("/view/", "/videos/", "/video/", "/watch/", "/novideo/", "/out/?ps=")):
            u = urljoin(base, h)
            if u not in watch:
                watch.append(u)

    image_hosts = set()
    for s in srcs:
        low = s.lower().split("?", 1)[0]
        if re.search(r"\.(?:jpe?g|png|webp|gif|avif|bmp)$", low) or "image" in low or "thumb" in low or "preview" in low:
            h = host_of(s, base)
            if h:
                image_hosts.add(h)

    iframe_hosts = {host_of(v, base) for v in attr_values(text, "src") if v and "iframe" not in v.lower()}
    iframe_hosts.discard("")
    # Restrict iframe hosts to values actually inside iframe tags.
    iframe_hosts = set()
    for m in re.finditer(r"<iframe\b[^>]*\bsrc\s*=\s*([\"'])(.*?)\1", text, re.I | re.S):
        h = host_of(m.group(2), base)
        if h:
            iframe_hosts.add(h)

    media_hosts = set()
    media_re = re.compile(r"https?:\\?/\\?/[^\"'<>\\\s]+?(?:\.m3u8|\.mpd|\.mp4)(?:\?[^\"'<>\\\s]*)?", re.I)
    for m in media_re.finditer(text):
        raw = m.group(0).replace("\\/", "/")
        h = host_of(raw, base)
        if h:
            media_hosts.add(h)

    og_image_hosts = set()
    for m in re.finditer(r"<meta\b[^>]*(?:property|name)\s*=\s*([\"'])og:image\1[^>]*>", text, re.I | re.S):
        tag = m.group(0)
        vals = re.findall(r"\bcontent\s*=\s*([\"'])(.*?)\1", tag, re.I | re.S)
        for _, v in vals:
            h = host_of(v, base)
            if h:
                og_image_hosts.add(h)

    return {
        "pattern_counts": counters,
        "watch_candidates": [path_of(u, base) for u in watch[:8]],
        "image_hosts": sorted(image_hosts)[:20],
        "iframe_hosts": sorted(iframe_hosts)[:20],
        "media_hosts": sorted(media_hosts)[:20],
        "og_image_hosts": sorted(og_image_hosts)[:20],
        "_watch_urls": watch,
    }


def search_candidates(adapter: dict, query: str) -> list[str]:
    base = adapter["base_url"].rstrip("/")
    q = quote(query, safe="")
    eng = adapter.get("engine", "")
    if eng in ("kvs_view", "kvs_videos"):
        return [
            f"{base}/search/?q={q}",
            f"{base}/search/{q}/",
            f"{base}/?s={q}",
            f"{base}/?q={q}",
        ]
    if eng == "noodle_watch_playlist":
        return [f"{base}/video/{q}", f"{base}/search/?q={q}", f"{base}/?q={q}"]
    if eng == "novideo_embed":
        return [f"{base}/{q}/", f"{base}/search/?q={q}", f"{base}/?q={q}"]
    return [f"{base}/search/?q={q}", f"{base}/?q={q}"]


def compact_fetch(r: dict) -> dict:
    return {k: v for k, v in r.items() if k != "text"}


def probe_adapter(path: Path) -> dict:
    adapter = json.loads(path.read_text(encoding="utf-8-sig"))
    base = adapter["base_url"].rstrip("/")
    result = {
        "id": adapter["id"],
        "engine": adapter.get("engine"),
        "base_host": (urlparse(base).hostname or "").lower(),
        "declared_allowed_hosts": adapter.get("permissions", {}).get("allowed_hosts", []),
    }

    home = fetch(base + "/")
    result["home"] = compact_fetch(home)
    if home.get("text"):
        hc = classify(home["text"], home.get("final_url", base))
        hc.pop("_watch_urls", None)
        result["home_structure"] = hc

    attempts = []
    best = None
    best_score = -1
    best_class = None
    for query in QUERIES:
        for url in search_candidates(adapter, query):
            r = fetch(url)
            c = classify(r.get("text", ""), r.get("final_url", url)) if r.get("text") else None
            score = 0
            if c:
                score = sum(c["pattern_counts"].values()) + len(c["image_hosts"]) + len(c["watch_candidates"]) * 3
            attempts.append({"query_kind": "ascii" if query == "video" else "utf8", "url_path": path_of(url, base), **compact_fetch(r), "structure_score": score})
            if r.get("ok") and score > best_score:
                best, best_score, best_class = r, score, c
            if r.get("ok") and score >= 4:
                break
        if best_score >= 4:
            break
    result["search_attempts"] = attempts
    if best:
        result["selected_search"] = compact_fetch(best)
        if best_class:
            watch_urls = best_class.pop("_watch_urls", [])
            result["search_structure"] = best_class
            if watch_urls:
                watch = fetch(watch_urls[0])
                result["watch_probe"] = compact_fetch(watch)
                if watch.get("text"):
                    wc = classify(watch["text"], watch.get("final_url", watch_urls[0]))
                    wc.pop("_watch_urls", None)
                    result["watch_structure"] = wc
    return result


report = {
    "schema": 1,
    "purpose": "technical source compatibility only; no video titles or page bodies retained",
    "sources": [],
}
for adapter_path in sorted(BUNDLED.glob("*/adapter.json")):
    report["sources"].append(probe_adapter(adapter_path))

OUT.parent.mkdir(parents=True, exist_ok=True)
OUT.write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
print(json.dumps(report, ensure_ascii=False, indent=2))
