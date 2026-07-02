#!/usr/bin/env python3
"""
Hosted Index Builder — fetches, parses, validates, and outputs all channel
sources as JSON files for the StreamVerse Android app.

Usage:
    python build_index.py [--output-dir ./dist] [--probe] [--probe-timeout 5]

Output:
    {output_dir}/
        channels.json          — merged index (all sources)
        iptv_index.json        — per‑source indices (maps to GLOBAL_INDEX)
        free_tv_index.json     — per‑source indices (maps to GLOBAL_INDEX)
        fast_tv_index.json     — per‑source indices (maps to GLOBAL_INDEX)
        premium_index.json     — per‑source indices (maps to GLOBAL_INDEX, excluded from combined)
        free_live_index.json   — per‑source indices (maps to FREE_CHANNEL)
        radio_index.json       — per‑source indices (maps to RADIO)
        stmify_index.json      — per‑source indices (maps to WORLD_TV)
        dlhd_index.json        — per‑source indices (maps to SPORTS_EVENTS)
        youtube_tv_index.json  — per‑source indices (maps to YOUTUBE_TV)
        independent_index.json — per‑source indices (maps to BROADCASTER)
        broadcaster_index.json — per‑source indices (maps to BROADCASTER)
"""

import argparse
import asyncio
import gzip
import hashlib
import io
import json
import logging
import os
import re
import sys
import time
from collections import OrderedDict
from typing import Any, Optional
from urllib.parse import urlparse

import aiohttp

logging.basicConfig(
    level=logging.INFO,
    format="%(asctime)s [%(levelname)s] %(message)s",
    datefmt="%H:%M:%S",
)
log = logging.getLogger("build_index")

VERSION = 1

# ── M3U Parser (replicates M3uParser.kt) ────────────────────────────────────

TVG_ID_RE = re.compile(r'tvg-id="(.*?)"', re.IGNORECASE)
TVG_LOGO_RE = re.compile(r'tvg-logo="(.*?)"', re.IGNORECASE)
GROUP_TITLE_RE = re.compile(r'group-title="(.*?)"', re.IGNORECASE)


def parse_m3u(text: str, source_url: str = "") -> list[dict[str, Any]]:
    entries: list[dict[str, Any]] = []
    current: dict[str, Any] | None = None
    for line in text.splitlines():
        line_stripped = line.strip()
        if not line_stripped:
            continue
        if line_stripped.startswith("#EXTINF:"):
            current = {
                "name": "",
                "streamUrl": "",
                "logoUrl": None,
                "category": None,
                "tvgId": None,
                "headers": {},
                "drmKeyId": None,
                "drmKey": None,
            }
            comma_idx = line_stripped.rfind(",")
            if comma_idx > 0 and comma_idx < len(line_stripped) - 1:
                attrs = line_stripped[8:comma_idx]
                current["name"] = line_stripped[comma_idx + 1].strip()
                m = TVG_ID_RE.search(attrs)
                if m:
                    val = m.group(1)
                    if val and val != "undefined":
                        current["tvgId"] = val
                m = TVG_LOGO_RE.search(attrs)
                if m:
                    current["logoUrl"] = m.group(1) or None
                m = GROUP_TITLE_RE.search(attrs)
                if m:
                    current["category"] = m.group(1) or None
        elif line_stripped.startswith("#EXTVLCOPT:") and current is not None:
            opt = line_stripped[len("#EXTVLCOPT:"):]
            eq = opt.find("=")
            if eq > 0:
                key = opt[:eq].strip().lower()
                val = opt[eq + 1:].strip()
                if val:
                    if key in ("http-user-agent",):
                        current["headers"]["User-Agent"] = val
                    elif key in ("http-referrer", "http-referer"):
                        current["headers"]["Referer"] = val
                    elif key == "http-origin":
                        current["headers"]["Origin"] = val
        elif line_stripped.startswith("#EXTHTTP:") and current is not None:
            try:
                extra = json.loads(line_stripped[len("#EXTHTTP:"):])
                if isinstance(extra, dict):
                    current["headers"].update(
                        {k: v for k, v in extra.items() if isinstance(v, str) and v.strip()}
                    )
            except json.JSONDecodeError:
                pass
        elif line_stripped.startswith("#KODIPROP:") and current is not None:
            prop = line_stripped[len("#KODIPROP:"):]
            eq = prop.find("=")
            if eq > 0:
                key = prop[:eq].strip().lower()
                val = prop[eq + 1:].strip()
                if key == "inputstream.adaptive.license_key":
                    parts = val.split(":")
                    if len(parts) == 2 and parts[0] and parts[1]:
                        current["drmKeyId"] = parts[0].strip()
                        current["drmKey"] = parts[1].strip()
        elif not line_stripped.startswith("#") and current is not None:
            current["streamUrl"] = line_stripped
            entries.append(current)
            current = None
    return entries


def infer_quality(url: str, name: str) -> str | None:
    text = (url + " " + name).lower()
    if "2160" in text or "4k" in text:
        return "4K"
    if "1080" in text or "fhd" in text:
        return "FHD"
    if "720" in text or "hd" in text:
        return "HD"
    if "480" in text or "sd" in text:
        return "SD"
    return None


def infer_country(tvg_id: str | None) -> str | None:
    if not tvg_id:
        return None
    dot = tvg_id.find(".")
    if dot < 0:
        return None
    code = tvg_id[dot + 1:dot + 3]
    if len(code) == 2 and code.isalpha():
        return code.upper()
    return None


def make_id(prefix: str, name: str, tvg_id: str | None = None) -> str:
    if tvg_id:
        return tvg_id
    h = hashlib.md5(name.encode()).hexdigest()[:12]
    return f"{prefix}_{h}"


# ── HTTP helpers ─────────────────────────────────────────────────────────────

HTTP_TIMEOUT = aiohttp.ClientTimeout(total=30, connect=15)
PROBE_TIMEOUT = aiohttp.ClientTimeout(total=8, connect=5)

HEADERS = {
    "User-Agent": "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36",
}


async def fetch_text(url: str, session: aiohttp.ClientSession, timeout=None) -> str:
    t = timeout or HTTP_TIMEOUT
    async with session.get(url, timeout=t, ssl=False) as resp:
        resp.raise_for_status()
        return await resp.text()


async def fetch_json(url: str, session: aiohttp.ClientSession, timeout=None) -> Any:
    t = timeout or HTTP_TIMEOUT
    async with session.get(url, timeout=t, ssl=False) as resp:
        resp.raise_for_status()
        return await resp.json()


async def probe_url(url: str, session: aiohttp.ClientSession, timeout_sec: int = 5) -> bool:
    try:
        t = aiohttp.ClientTimeout(total=timeout_sec, connect=timeout_sec)
        async with session.head(url, timeout=t, ssl=False, allow_redirects=True) as resp:
            return resp.status < 400
    except Exception:
        return False


# ── Domain rotation ────────────────────────────────────────────────────────────

MIRROR_PAGE_RE = re.compile(r'https?://(?:www\.)?([^/"\']+(?:\.\w+)+)(?=/|"|\')')

async def resolve_dlhd_from_mirror_page(session: aiohttp.ClientSession) -> str | None:
    """
    Fetch the official mirror directory at https://daddylive.pk/ and return the
    first *responding* mirror domain from its list (tried in page order).
    Falls back to the hardcoded known-domains list if the page is unreachable.
    """
    mirror_page = "https://daddylive.pk/"
    try:
        text = await fetch_text(mirror_page, session, timeout=aiohttp.ClientTimeout(total=15, connect=10))
    except Exception as e:
        log.warning("Mirror page %s unreachable: %s — using fallback list", mirror_page, e)
        return await _probe_fallback_domains(["dlhd.st", "dlhd.pk", "dlhd.sx", "dlhd.com", "dlhd.to"], session)

    # Extract all https:// mirror URLs found on the page
    candidates: list[str] = []
    for m in MIRROR_PAGE_RE.finditer(text):
        domain = m.group(1)
        # Only consider dlhd.* domains from the page
        if domain.startswith("dlhd."):
            if domain not in candidates:
                candidates.append(domain)

    if not candidates:
        log.warning("No mirrors found on %s — using fallback list", mirror_page)
        return await _probe_fallback_domains(["dlhd.st", "dlhd.pk", "dlhd.sx"], session)

    log.info("Mirrors discovered from %s: %s", mirror_page, candidates)
    for domain in candidates:
        url = f"https://{domain}/"
        try:
            async with session.head(
                url, timeout=aiohttp.ClientTimeout(total=8, connect=5),
                ssl=False, allow_redirects=True,
            ) as resp:
                if resp.status < 400:
                    log.info("Resolved DLHD domain: %s (status %d)", domain, resp.status)
                    return domain
        except Exception:
            log.debug("DLHD mirror %s did not respond — trying next", domain)
            continue

    log.warning("No DLHD mirror responded — trying fallback list")
    return await _probe_fallback_domains(["dlhd.st", "dlhd.pk", "dlhd.sx", "dlhd.com", "dlhd.to"], session)


async def _probe_fallback_domains(domains: list[str], session: aiohttp.ClientSession) -> str | None:
    """Try each fallback domain, return the first that responds."""
    for domain in domains:
        url = f"https://{domain}/"
        try:
            async with session.head(
                url, timeout=aiohttp.ClientTimeout(total=8, connect=5),
                ssl=False, allow_redirects=True,
            ) as resp:
                if resp.status < 400:
                    log.info("Fallback domain resolved: %s (status %d)", domain, resp.status)
                    return domain
        except Exception:
            continue
    return None


# ── Source fetchers ──────────────────────────────────────────────────────────

async def fetch_iptv(session: aiohttp.ClientSession, probe: bool = False, probe_timeout: int = 5) -> list[dict]:
    log.info("Fetching IPTV...")
    channels: dict[str, dict] = OrderedDict()

    main_url = "https://iptv-org.github.io/iptv/index.m3u"
    supplemental = [
        "https://iptv-org.github.io/iptv/countries/ng.m3u",
        "https://iptv-org.github.io/iptv/countries/gh.m3u",
        "https://iptv-org.github.io/iptv/countries/za.m3u",
        "https://iptv-org.github.io/iptv/countries/ke.m3u",
        "https://iptv-org.github.io/iptv/countries/eg.m3u",
        "https://iptv-org.github.io/iptv/countries/tz.m3u",
        "https://iptv-org.github.io/iptv/countries/et.m3u",
        "https://iptv-org.github.io/iptv/countries/ug.m3u",
        "https://iptv-org.github.io/iptv/regions/afr.m3u",
    ]

    tasks = [main_url] + supplemental
    results = await asyncio.gather(
        *[fetch_text_safe(url, session) for url in tasks],
        return_exceptions=True,
    )

    for url, result in zip(tasks, results):
        if isinstance(result, Exception):
            log.warning("IPTV fetch failed: %s — %s", url, result)
            continue
        entries = parse_m3u(result, url)
        for e in entries:
            cid = e["tvgId"] or make_id("iptv", e["name"])
            if cid not in channels:
                channels[cid] = {
                    "id": cid,
                    "name": e["name"],
                    "streamUrl": e["streamUrl"],
                    "logoUrl": e["logoUrl"],
                    "category": e["category"],
                    "country": infer_country(e["tvgId"]),
                    "language": None,
                    "quality": infer_quality(e["streamUrl"], e["name"]),
                    "tvgId": e["tvgId"],
                    "source": "GLOBAL_INDEX",
                    "headers": e["headers"],
                    "drmKeyId": e["drmKeyId"],
                    "drmKey": e["drmKey"],
                }

    log.info("IPTV: %d channels", len(channels))
    return list(channels.values())


async def fetch_free_tv(session: aiohttp.ClientSession, probe: bool = False, probe_timeout: int = 5) -> list[dict]:
    log.info("Fetching FREE_TV...")
    url = "https://raw.githubusercontent.com/Free-TV/IPTV/master/playlist.m3u8"
    text = await fetch_text_safe(url, session)
    if isinstance(text, Exception):
        log.warning("FREE_TV fetch failed: %s", text)
        return []
    entries = parse_m3u(text, url)
    channels = []
    for e in entries:
        cid = e["tvgId"] or make_id("freetv", e["name"])
        channels.append({
            "id": cid,
            "name": e["name"],
            "streamUrl": e["streamUrl"],
            "logoUrl": e["logoUrl"],
            "category": e["category"],
            "country": infer_country(e["tvgId"]),
            "language": None,
            "quality": infer_quality(e["streamUrl"], e["name"]),
            "tvgId": e["tvgId"],
            "source": "GLOBAL_INDEX",
            "headers": e["headers"],
            "drmKeyId": e["drmKeyId"],
            "drmKey": e["drmKey"],
        })
    log.info("FREE_TV: %d channels", len(channels))
    return channels


async def fetch_fast_tv(session: aiohttp.ClientSession, probe: bool = False, probe_timeout: int = 5) -> list[dict]:
    log.info("Fetching FAST_TV...")
    sources = {
        "ng": "https://raw.githubusercontent.com/iptv-org/iptv/master/streams/ng.m3u",
        "gh": "https://raw.githubusercontent.com/iptv-org/iptv/master/streams/gh.m3u",
        "za": "https://raw.githubusercontent.com/iptv-org/iptv/master/streams/za.m3u",
        "ke": "https://raw.githubusercontent.com/iptv-org/iptv/master/streams/ke.m3u",
        "et": "https://raw.githubusercontent.com/iptv-org/iptv/master/streams/et.m3u",
        "tz": "https://raw.githubusercontent.com/iptv-org/iptv/master/streams/tz.m3u",
        "ug": "https://raw.githubusercontent.com/iptv-org/iptv/master/streams/ug.m3u",
        "ci": "https://raw.githubusercontent.com/iptv-org/iptv/master/streams/ci.m3u",
        "cm": "https://raw.githubusercontent.com/iptv-org/iptv/master/streams/cm.m3u",
        "sn": "https://raw.githubusercontent.com/iptv-org/iptv/master/streams/sn.m3u",
        "rw": "https://raw.githubusercontent.com/iptv-org/iptv/master/streams/rw.m3u",
        "ma": "https://raw.githubusercontent.com/iptv-org/iptv/master/streams/ma.m3u",
        "eg": "https://raw.githubusercontent.com/iptv-org/iptv/master/streams/eg.m3u",
        "ao": "https://raw.githubusercontent.com/iptv-org/iptv/master/streams/ao.m3u",
        "dz": "https://raw.githubusercontent.com/iptv-org/iptv/master/streams/dz.m3u",
        "uk": "https://raw.githubusercontent.com/iptv-org/iptv/master/streams/uk.m3u",
        "us": "https://raw.githubusercontent.com/iptv-org/iptv/master/streams/us.m3u",
        "in": "https://raw.githubusercontent.com/iptv-org/iptv/master/streams/in.m3u",
        "ph": "https://raw.githubusercontent.com/iptv-org/iptv/master/streams/ph.m3u",
        "mx": "https://raw.githubusercontent.com/iptv-org/iptv/master/streams/mx.m3u",
        "br": "https://raw.githubusercontent.com/iptv-org/iptv/master/streams/br.m3u",
        "tr": "https://raw.githubusercontent.com/iptv-org/iptv/master/streams/tr.m3u",
        "ar": "https://raw.githubusercontent.com/iptv-org/iptv/master/streams/ar.m3u",
        "de": "https://raw.githubusercontent.com/iptv-org/iptv/master/streams/de.m3u",
        "fr": "https://raw.githubusercontent.com/iptv-org/iptv/master/streams/fr.m3u",
        "es": "https://raw.githubusercontent.com/iptv-org/iptv/master/streams/es.m3u",
        "it": "https://raw.githubusercontent.com/iptv-org/iptv/master/streams/it.m3u",
        "ru": "https://raw.githubusercontent.com/iptv-org/iptv/master/streams/ru.m3u",
    }
    channels: dict[str, dict] = OrderedDict()
    keys = list(sources.keys())
    urls = [sources[k] for k in keys]
    results = await asyncio.gather(
        *[fetch_text_safe(url, session) for url in urls],
        return_exceptions=True,
    )
    for key, result in zip(keys, results):
        if isinstance(result, Exception):
            log.debug("FAST_TV %s failed: %s", key, result)
            continue
        entries = parse_m3u(result, sources[key])
        for e in entries:
            cid = f"{key}_{hashlib.md5((e['tvgId'] or e['name']).encode()).hexdigest()[:8]}"
            if cid not in channels:
                channels[cid] = {
                    "id": cid,
                    "name": e["name"],
                    "streamUrl": e["streamUrl"],
                    "logoUrl": e["logoUrl"],
                    "category": e["category"],
                    "country": infer_country(e["tvgId"]) or key[:2].upper(),
                    "source": "GLOBAL_INDEX",
                    "headers": e["headers"],
                    "tvgId": e["tvgId"],
                    "drmKeyId": e["drmKeyId"],
                    "drmKey": e["drmKey"],
                }
    log.info("FAST_TV: %d channels", len(channels))
    return list(channels.values())


async def fetch_premium(session: aiohttp.ClientSession, probe: bool = False, probe_timeout: int = 5) -> list[dict]:
    log.info("Fetching PREMIUM...")
    premium_file = os.path.join(os.path.dirname(__file__), "..", "..",
                                "core", "src", "main", "assets", "premium_sources.json")
    if not os.path.exists(premium_file):
        log.warning("premium_sources.json not found at %s", premium_file)
        premium_file = os.path.join(os.path.dirname(__file__), "premium_sources.json")

    defaults = {
        "movies": "https://iptv-org.github.io/iptv/categories/movies.m3u",
        "sports": "https://iptv-org.github.io/iptv/categories/sports.m3u",
        "series": "https://iptv-org.github.io/iptv/categories/series.m3u",
        "documentary": "https://iptv-org.github.io/iptv/categories/documentary.m3u",
    }

    sources: dict[str, str] = {}
    if os.path.exists(premium_file):
        try:
            with open(premium_file) as f:
                loaded = json.load(f)
            if isinstance(loaded, dict):
                sources.update(loaded)
        except Exception as e:
            log.warning("Failed to load premium_sources.json: %s", e)

    sources = {**defaults, **sources}
    channels: dict[str, dict] = OrderedDict()
    keys = list(sources.keys())
    urls = [sources[k] for k in keys]
    results = await asyncio.gather(
        *[fetch_text_safe(url, session, timeout=aiohttp.ClientTimeout(total=20, connect=10)) for url in urls],
        return_exceptions=True,
    )
    for key, result in zip(keys, results):
        if isinstance(result, Exception):
            log.debug("PREMIUM %s failed: %s", key, result)
            continue
        entries = parse_m3u(result, sources[key])
        for e in entries:
            cid = f"prem_{key}_{hashlib.md5((e['tvgId'] or e['name']).encode()).hexdigest()[:8]}"
            if cid not in channels:
                channels[cid] = {
                    "id": cid,
                    "name": e["name"],
                    "streamUrl": e["streamUrl"],
                    "logoUrl": e["logoUrl"],
                    "category": e["category"] or "Premium",
                    "country": infer_country(e["tvgId"]) or key[:2].upper(),
                    "tvgId": e["tvgId"],
                    "source": "GLOBAL_INDEX",
                    "headers": e["headers"],
                    "drmKeyId": e["drmKeyId"],
                    "drmKey": e["drmKey"],
                }
    log.info("PREMIUM: %d channels", len(channels))
    return list(channels.values())


async def fetch_free_live(session: aiohttp.ClientSession, probe: bool = False, probe_timeout: int = 5) -> list[dict]:
    log.info("Fetching FREE_LIVE...")
    sources_file = os.path.join(os.path.dirname(__file__), "..", "..",
                                "core", "src", "main", "assets", "free_channels_sources.json")
    if not os.path.exists(sources_file):
        log.warning("free_channels_sources.json not found")
        return []

    try:
        with open(sources_file) as f:
            raw = json.load(f)
    except Exception as e:
        log.warning("Failed to load free_channels_sources.json: %s", e)
        return []

    source_entries = []
    for key, entry in raw.items():
        url = entry.get("url", "")
        if url:
            source_entries.append((key, url, entry.get("geo", "ALL")))

    channels: dict[str, dict] = OrderedDict()
    keys_geo = [(key, geo) for key, url, geo in source_entries]
    urls = [url for key, url, geo in source_entries]
    results = await asyncio.gather(
        *[fetch_text_safe(url, session) for url in urls],
        return_exceptions=True,
    )
    for (key, geo), result in zip(keys_geo, results):
        if isinstance(result, Exception):
            log.debug("FREE_LIVE %s failed: %s", key, result)
            continue
        url = dict(zip((k for k, u, g in source_entries), (u for k, u, g in source_entries)))[key]
        entries = parse_m3u(result, url)
        for e in entries:
            cid = f"{key}_{hashlib.md5((e['tvgId'] or e['name']).encode()).hexdigest()[:8]}"
            if cid not in channels:
                channels[cid] = {
                    "id": cid,
                    "name": e["name"],
                    "streamUrl": e["streamUrl"],
                    "logoUrl": e["logoUrl"],
                    "category": e["category"],
                    "country": infer_country(e["tvgId"]) or geo,
                    "tvgId": e["tvgId"],
                    "source": "FREE_CHANNEL",
                    "headers": e["headers"],
                    "drmKeyId": e["drmKeyId"],
                    "drmKey": e["drmKey"],
                }
    log.info("FREE_LIVE: %d channels", len(channels))
    return list(channels.values())


async def fetch_radio(session: aiohttp.ClientSession, probe: bool = False, probe_timeout: int = 5) -> list[dict]:
    log.info("Fetching RADIO...")
    base = "https://de1.api.radio-browser.info"
    urls = [
        f"{base}/json/stations/topclick/500",
        f"{base}/json/stations/bycountry/Nigeria?limit=200",
        f"{base}/json/stations/bycountry/South%20Africa?limit=200",
        f"{base}/json/stations/bycountry/Ghana?limit=200",
    ]

    seen = set()
    channels = []
    for url in urls:
        result = await fetch_text_safe(url, session)
        if isinstance(result, Exception):
            log.debug("RADIO %s failed: %s", url, result)
            continue
        try:
            data = json.loads(result)
        except json.JSONDecodeError:
            continue
        if not isinstance(data, list):
            continue
        for item in data:
            uid = item.get("stationuuid", "")
            if uid in seen:
                continue
            seen.add(uid)
            resolved = item.get("url_resolved") or item.get("url") or ""
            channels.append({
                "id": uid,
                "name": item.get("name", ""),
                "streamUrl": resolved,
                "logoUrl": item.get("favicon") or None,
                "category": "Radio",
                "country": (item.get("countrycode") or "").upper() or None,
                "language": item.get("language") or None,
                "quality": None,
                "source": "RADIO",
                "headers": {},
                "drmKeyId": None,
                "drmKey": None,
                "_tags": item.get("tags"),
                "_codec": item.get("codec"),
                "_bitrate": item.get("bitrate"),
                "_clickcount": item.get("clickcount"),
            })
    log.info("RADIO: %d stations", len(channels))
    return channels


async def fetch_stmify(session: aiohttp.ClientSession, probe: bool = False, probe_timeout: int = 5) -> list[dict]:
    log.info("Fetching STMIFY...")
    base = "https://stmify.com"
    api = f"{base}/wp-json/wp/v2/live-tv"
    channels = []

    for page in range(1, 4):
        url = f"{api}?per_page=100&page={page}&_embed=wp:featuredmedia,wp:term"
        result = await fetch_text_safe(url, session, headers={
            "User-Agent": "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36",
            "Accept": "application/json",
        })
        if isinstance(result, Exception):
            log.debug("STMIFY page %d failed: %s", page, result)
            break
        try:
            items = json.loads(result)
        except json.JSONDecodeError:
            break
        if not isinstance(items, list) or not items:
            break
        for item in items:
            slug = item.get("slug", "")
            if not slug:
                continue
            title = (item.get("title") or {}).get("rendered", "").strip()
            embedded = item.get("_embedded", {})
            media = (embedded.get("wp:featuredmedia") or [{}])[0]
            img_url = media.get("source_url")
            if not img_url:
                sizes = (media.get("media_details") or {}).get("sizes", {})
                for size_key in ("medium_large", "medium", "large"):
                    if size_key in sizes:
                        img_url = sizes[size_key].get("source_url")
                        if img_url:
                            break
            terms = embedded.get("wp:term") or []
            genres = []
            for term_list in terms:
                for t in term_list:
                    genres.append(t.get("name", ""))
            quality = None
            title_lower = (title or "").lower()
            if "4k" in title_lower:
                quality = "4K"
            elif "hd" in title_lower:
                quality = "HD"

            channels.append({
                "id": slug,
                "name": title,
                "streamUrl": f"{base}/live-tv/{slug}/",
                "logoUrl": img_url,
                "category": genres[0] if genres else None,
                "country": None,
                "language": None,
                "quality": quality,
                "source": "STMIFY",
                "headers": {},
                "drmKeyId": None,
                "drmKey": None,
            })
        if len(items) < 100:
            break
    log.info("STMIFY: %d channels", len(channels))
    return channels


async def fetch_dlhd(session: aiohttp.ClientSession, probe: bool = False, probe_timeout: int = 5) -> list[dict]:
    log.info("Fetching DLHD...")

    resolved = await resolve_dlhd_from_mirror_page(session)
    if not resolved:
        log.warning("DLHD: no domain resolved, skipping")
        return []
    log.info("DLHD: using domain %s", resolved)

    scrape_url = f"https://{resolved}/24-7-channels.php"
    scrape_result = await fetch_text_safe(scrape_url, session, headers={
        "User-Agent": "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36",
    })
    if isinstance(scrape_result, Exception):
        log.warning("DLHD scrape failed: %s — skipping", scrape_result)
        return []

    from html.parser import HTMLParser

    class DlhdParser(HTMLParser):
        def __init__(self):
            super().__init__()
            self.channels: list[dict] = []
            self._in_card = False
            self._current: dict = {}
            self._in_title = False
            self._resolved = resolved

        def handle_starttag(self, tag, attrs):
            attrs_dict = dict(attrs)
            classes = attrs_dict.get("class", "")
            class_set = set(classes.split())
            if "card" in class_set or "channel-item" in class_set or "col-sm" in class_set:
                self._in_card = True
                self._current = {"id": attrs_dict.get("data-id", ""), "name": "", "logo": ""}
                href = attrs_dict.get("href", "")
                if "watch.php?id=" in href:
                    self._current["id"] = href.split("watch.php?id=")[1].split("&")[0]
            if tag == "img" and self._in_card:
                self._current["logo"] = attrs_dict.get("src", "")
            if tag in ("h2", "h3", "a", "div") and self._in_card:
                cls = attrs_dict.get("class", "")
                if any(c in cls for c in ("title", "name", "card-title")) or not cls:
                    self._in_title = True

        def handle_data(self, data):
            if self._in_title and self._in_card:
                self._current["name"] = data.strip()

        def handle_endtag(self, tag):
            self._in_title = False
            if self._in_card and tag in ("div", "a", "li"):
                cid = self._current.get("id", "")
                name = self._current.get("name", "")
                if cid.isdigit() and name and len(name) >= 2:
                    self.channels.append({
                        "id": cid,
                        "name": name,
                        "streamUrl": f"https://{self._resolved}/watch.php?id={cid}",
                        "logoUrl": self._current.get("logo", "") or None,
                        "category": "Sports",
                        "country": None,
                        "language": None,
                        "quality": None,
                        "source": "DLHD",
                        "headers": {},
                        "drmKeyId": None,
                        "drmKey": None,
                    })
                self._in_card = False
                self._current = {}

    parser = DlhdParser()
    parser.feed(scrape_result)
    channels = parser.channels
    log.info("DLHD: %d channels (scraped)", len(channels))
    return channels


async def fetch_youtube_tv(session: aiohttp.ClientSession, probe: bool = False, probe_timeout: int = 5) -> list[dict]:
    log.info("Fetching YOUTUBE_TV...")
    curated = [
        # ── Global News ─────────────────────────────────
        ("al_jazeera", "Al Jazeera English", "UCfi-iTtY4IqYBg1gR0VxQxA", "News", "en", ""),
        ("france24_en", "France 24 English", "UCEjB3pD6s7uRw3VkPbvX0Q", "News", "en", ""),
        ("france24_fr", "France 24 Français", "UCQoW_fBqgY5V7u_eHxw0gA", "News", "fr", ""),
        ("dw_news", "DW News", "UCX7kFqGgXn7oQzGQ2qX8Q", "News", "en", ""),
        ("trt_world", "TRT World", "UC7fWeaHhqgM4g_VdGQKjQ", "News", "en", ""),
        ("cgtn", "CGTN", "UCGhz5KQpF0hN8JLJpXq6w", "News", "en", ""),
        ("nhk_world", "NHK World Japan", "UCSPEjw6zKvCJQj6y7y4Q", "News", "en", ""),
        ("wion", "WION", "UC_gUM8rL-Lrg6O3Qa5xQ", "News", "en", ""),
        ("scmp", "South China Morning Post", "UC4cV9g1xWGLz5qVwzYb0Xw", "News", "en", ""),
        ("euronews", "Euronews", "UCXoJ2p7KjRZGZ4qZpLjRzQ", "News", "en", ""),
        ("reuters", "Reuters", "UCd9I8vBO7L3jVHa6mX2N3Q", "News", "en", ""),
        ("ap", "Associated Press", "UCqa1H1HkS5IHicP54rvC3wQ", "News", "en", ""),
        ("bloomberg", "Bloomberg TV", "UCUMZ7gohGI9HcU9VNsFgM_g", "Business", "en", ""),

        # ── United States ───────────────────────────────
        ("abc_news", "ABC News", "UCBi2mrWuNuyYy4gbM6fU18Q", "News", "en", "US"),
        ("cbs_news", "CBS News", "UC8p1vwvWtl6T73JiExfWs1g", "News", "en", "US"),
        ("nbc_news", "NBC News", "UCeY0bbntWzzVIaj2vt3QoXg", "News", "en", "US"),
        ("fox_weather", "Fox Weather", "UCXq6uB3v5JpTJxQh_5a_xQ", "Weather", "en", "US"),
        ("newsmax", "Newsmax", "UC8q6UB1WN1bT2v3R3Pp6f5A", "News", "en", "US"),
        ("nasa", "NASA TV", "UCX7j_jgHqHjQ0eXyfX6X1w", "Science", "en", "US"),
        ("pbs_newshour", "PBS NewsHour", "UCx9JqXK7tqZxK8Y7y3Zy5g", "News", "en", "US"),
        ("tyt", "The Young Turks", "UC1yB4mZNrXx4X5b_MCkQ7g", "News", "en", "US"),
        ("fox_business", "Fox Business", "UCC0E4J2hVwQq0CqWq3ZxXw", "Business", "en", "US"),

        # ── United Kingdom ──────────────────────────────
        ("sky_news", "Sky News", "UCoLrcjPV5PbUrUyXq5mjc_A", "News", "en", "GB"),
        ("bbc_news", "BBC News", "UC16niRr50-MSBQi3zBNENrg", "News", "en", "GB"),
        ("gb_news", "GB News", "UCkPmJjN0V9XzXjZq0Zx1YQ", "News", "en", "GB"),

        # ── Canada ──────────────────────────────────────
        ("cbc_news_yt", "CBC News", "UC5p1vwvWtl6T73JiExfWs1g", "News", "en", "CA"),

        # ── Nigeria ─────────────────────────────────────
        ("channels_tv", "Channels Television", "UCEXGDNclvmg6RW0vipJYsTQ", "News", "en", "NG"),
        ("tvc_news", "TVC News Nigeria", "UCgp4A6I8LCWrhUzn-5SbKvA", "News", "en", "NG"),
        ("arise_news", "Arise News", "UCyEJX-kSj0kOOCS7Qlq2G7g", "News", "en", "NG"),
        ("nta_network", "NTA Network", "UCLLWAXn5F415g2kNAcE_T1g", "News", "en", "NG"),
        ("tv360_nigeria", "TV360 Nigeria", "UCBzu4YqGiBxBD8pq8NiBuKw", "News", "en", "NG"),
        ("news_central", "News Central Africa", "UCPLKy4Ypb4mfblbjJI8Aljw", "News", "en", "NG"),
        ("ait_nigeria", "AIT Africa Independent Television", "UCBJvTeuSKEpZZV3E5EKEd4g", "News", "en", "NG"),

        # ── South Africa ────────────────────────────────
        ("sabc_news", "SABC News", "UC8yH-uI81UUtEMDsowQyx1g", "News", "en", "ZA"),
        ("newzroom_afrika", "Newzroom Afrika", "UCQMML3hAsx-Mz9j9ZN0tThQ", "News", "en", "ZA"),
        ("enca", "eNCA", "UCI3RT5PGmdi1KVp9FG_CneA", "News", "en", "ZA"),

        # ── Kenya ───────────────────────────────────────
        ("citizen_tv_kenya", "Citizen TV Kenya", "UChBQgieUidXV1CmDxSdRm3g", "News", "en", "KE"),
        ("ktn_news_kenya", "KTN News Kenya", "UCKVsdeoHExltrWMuK0hOWmg", "News", "en", "KE"),
        ("ntv_kenya", "NTV Kenya", "UCqBJ47FjJcl61fmSbcadAVg", "News", "en", "KE"),
        ("k24_tv_kenya", "K24 TV Kenya", "UCt3SE-Mvs3WwP7UW-PiFdqQ", "News", "en", "KE"),

        # ── Ghana ───────────────────────────────────────
        ("joynews_ghana", "JoyNews Ghana", "UChd1DEecCRlxaa0-hvPACCw", "News", "en", "GH"),

        # ── Uganda ──────────────────────────────────────
        ("ntv_uganda", "NTV Uganda", "UCwga1dPCqBddbtq5KYRii2g", "News", "en", "UG"),

        # ── Tanzania ──────────────────────────────────────
        ("azam_tv_tz", "Azam TV Tanzania", "UCpHiA0taMn231yDiUeqoANw", "General", "sw", "TZ"),

        # ── India ───────────────────────────────────────
        ("dd_news_india", "DD News India", "UCKwucPzHZ7zCUIf7If-Wo1g", "News", "en", "IN"),
        ("times_now", "Times Now", "UC6RJ7-PaXg6TIH2BzZfTV7w", "News", "en", "IN"),

        # ── More World News ─────────────────────────────
        ("rt_news", "RT News", "UCpwvZwUam-URkxB7g4USKpg", "News", "en", ""),
        ("bbc_persian", "BBC Persian", "UCHZk9MrT3DGWmVqdsj5y0EA", "News", "fa", ""),
    ]

    channels = []
    for ref_id, name, channel_id, category, lang, country in curated:
        channels.append({
            "id": f"yt_{ref_id}",
            "name": name,
            "streamUrl": f"https://www.youtube.com/channel/{channel_id}/live",
            "logoUrl": None,
            "category": category,
            "country": country or None,
            "language": lang,
            "quality": None,
            "source": "YOUTUBE_TV",
            "headers": {},
            "drmKeyId": None,
            "drmKey": None,
        })

    api_key = os.environ.get("YOUTUBE_API_KEY", "")
    if api_key:
        queries = [        "live news channel", "live tv stream", "live sports", "24/7 live stream",
            "Africa news live", "Nigeria news live", "Kenya news live",
            "South Africa news live", "Ghana news live", "Uganda news live",
            "Ethiopia news live", "Egypt news live", "Morocco news live",
            "India news live", "Brazil news live", "Japan news live"]
        seen_ids = {c["id"] for c in channels}
        for query in queries:
            search_url = (
                f"https://www.googleapis.com/youtube/v3/search"
                f"?part=snippet&q={query.replace(' ', '%20')}"
                f"&type=channel&eventType=live&maxResults=10&key={api_key}"
            )
            result = await fetch_text_safe(search_url, session)
            if isinstance(result, Exception):
                continue
            try:
                data = json.loads(result)
                items = data.get("items", [])
                for item in items:
                    snippet = item.get("snippet", {})
                    cid = item.get("id", {}).get("channelId", "")
                    if not cid:
                        continue
                    ref = f"api_{cid}"
                    if ref in seen_ids:
                        continue
                    seen_ids.add(ref)
                    title = snippet.get("title", "")[:100]
                    channels.append({
                        "id": f"yt_{ref}",
                        "name": title,
                        "streamUrl": f"https://www.youtube.com/channel/{cid}/live",
                        "logoUrl": None,
                        "category": "General",
                        "country": (snippet.get("country") or "")[:2] or None,
                        "language": (snippet.get("defaultLanguage") or "en")[:5],
                        "quality": None,
                        "source": "YOUTUBE_TV",
                        "headers": {},
                        "drmKeyId": None,
                        "drmKey": None,
                    })
            except json.JSONDecodeError:
                pass

    log.info("YOUTUBE_TV: %d channels", len(channels))
    return channels


async def fetch_independent(session: aiohttp.ClientSession, probe: bool = False, probe_timeout: int = 5) -> list[dict]:
    log.info("Fetching INDEPENDENT...")
    # Replicates IndependentClient.kt curated list
    channels = [
        {"id": "aljazeera_en", "name": "Al Jazeera English", "streamUrl": "https://live-hls-aje-ak.getaj.net/AJE/01.m3u8", "logoUrl": "https://upload.wikimedia.org/wikipedia/en/thumb/f/f8/Al_Jazeera_English_logo.svg/512px-Al_Jazeera_English_logo.svg.png", "category": "News", "country": "QA", "language": "English", "quality": "FHD", "source": "BROADCASTER"},
        {"id": "france24_en", "name": "France 24 English", "streamUrl": "https://live.france24.com/hls/live/2037218-b/F24_EN_HI_HLS/master_5000.m3u8", "logoUrl": "https://upload.wikimedia.org/wikipedia/en/thumb/1/11/France_24_English_logo.svg/512px-France_24_English_logo.svg.png", "category": "News", "country": "FR", "language": "English", "quality": "FHD", "source": "BROADCASTER"},
        {"id": "dw_en", "name": "DW English", "streamUrl": "https://dwamdstream102.akamaized.net/hls/live/2015525/dwstream102/index.m3u8", "logoUrl": "https://upload.wikimedia.org/wikipedia/commons/thumb/4/4a/Deutsche_Welle_symbol_2012.svg/512px-Deutsche_Welle_symbol_2012.svg.png", "category": "News", "country": "DE", "language": "English", "quality": "FHD", "source": "BROADCASTER"},
        {"id": "cgtn_en", "name": "CGTN", "streamUrl": "https://news.cgtn.com/resource/live/english/cgtn-news.m3u8", "logoUrl": "https://upload.wikimedia.org/wikipedia/commons/thumb/2/2c/CGTN.svg/512px-CGTN.svg.png", "category": "News", "country": "CN", "language": "English", "quality": "FHD", "source": "BROADCASTER"},
        {"id": "nhk_world", "name": "NHK World", "streamUrl": "https://masterpl.hls.nhkworld.jp/hls/w/live/smarttv.m3u8", "logoUrl": "https://upload.wikimedia.org/wikipedia/commons/thumb/2/2b/NHK_World_logo.svg/512px-NHK_World_logo.svg.png", "category": "News", "country": "JP", "language": "English", "quality": "FHD", "source": "BROADCASTER"},
        {"id": "trt_world", "name": "TRT World", "streamUrl": "https://tv-trtworld.medya.trt.com.tr/master_1080.m3u8", "logoUrl": "https://upload.wikimedia.org/wikipedia/commons/thumb/0/0f/TRT_World_logo.svg/512px-TRT_World_logo.svg.png", "category": "News", "country": "TR", "language": "English", "quality": "FHD", "source": "BROADCASTER"},
        {"id": "bloomberg_tv", "name": "Bloomberg Television", "streamUrl": "https://bloomberg.com/media-manifest/streams/us.m3u8", "logoUrl": "https://upload.wikimedia.org/wikipedia/commons/thumb/8/8b/Bloomberg_Television_logo.svg/512px-Bloomberg_Television_logo.svg.png", "category": "Business", "country": "US", "language": "English", "quality": "FHD", "source": "BROADCASTER"},
        {"id": "newsmax", "name": "Newsmax", "streamUrl": "https://nmx1ota.akamaized.net/hls/live/2107010/Live_1/3.m3u8", "logoUrl": "https://upload.wikimedia.org/wikipedia/en/thumb/6/63/Newsmax_logo.svg/512px-Newsmax_logo.svg.png", "category": "News", "country": "US", "language": "English", "quality": "HD", "source": "BROADCASTER"},
        {"id": "oann", "name": "One America News", "streamUrl": "https://oneamericanews-roku-us.amagi.tv/playlistR1080p.m3u8", "logoUrl": "https://upload.wikimedia.org/wikipedia/en/thumb/e/e4/One_America_News_Network_logo.svg/512px-One_America_News_Network_logo.svg.png", "category": "News", "country": "US", "language": "English", "quality": "FHD", "source": "BROADCASTER"},
        {"id": "euronews_en", "name": "Euronews English", "streamUrl": "https://a-cdn.klowdtv.com/live3/euronews_720p/playlist.m3u8", "logoUrl": "https://upload.wikimedia.org/wikipedia/commons/thumb/2/2a/Euronews_2022_logo.svg/512px-Euronews_2022_logo.svg.png", "category": "News", "country": "FR", "language": "English", "quality": "HD", "source": "BROADCASTER"},
        {"id": "sky_news", "name": "Sky News", "streamUrl": "https://jmp2.uk/plu-55b285cd2665de274553d66f.m3u8", "logoUrl": "https://upload.wikimedia.org/wikipedia/commons/thumb/0/0b/Sky_News_logo.svg/512px-Sky_News_logo.svg.png", "category": "News", "country": "GB", "language": "English", "quality": "HD", "source": "BROADCASTER"},
        {"id": "cbs_news", "name": "CBS News", "streamUrl": "https://jmp2.uk/plu-62310f66d5888f0007534342.m3u8", "logoUrl": "https://upload.wikimedia.org/wikipedia/commons/thumb/5/55/CBS_News_logo.svg/512px-CBS_News_logo.svg.png", "category": "News", "country": "US", "language": "English", "quality": "HD", "source": "BROADCASTER"},
        {"id": "fox_news", "name": "Fox News Channel", "streamUrl": "https://trs1.aynaott.com/foxnews/index.m3u8", "logoUrl": "https://upload.wikimedia.org/wikipedia/commons/thumb/6/67/Fox_News_Channel_logo.svg/512px-Fox_News_Channel_logo.svg.png", "category": "News", "country": "US", "language": "English", "quality": "FHD", "source": "BROADCASTER"},
        {"id": "rt_news", "name": "RT News", "streamUrl": "https://rt-glb.rttv.com/live/rtnews/playlist.m3u8", "logoUrl": "https://upload.wikimedia.org/wikipedia/en/thumb/1/1f/RT_%28TV_network%29_logo.svg/512px-RT_%28TV_network%29_logo.svg.png", "category": "News", "country": "RU", "language": "English", "quality": "FHD", "source": "BROADCASTER"},
        {"id": "reuters_now", "name": "Reuters Now", "streamUrl": "https://amg00453-reuters-amg00453c1-xumo-us-2073.playouts.now.amagi.tv/reuters-reuters-hls/playlist.m3u8", "logoUrl": "https://upload.wikimedia.org/wikipedia/commons/thumb/1/1f/Reuters_logo.svg/512px-Reuters_logo.svg.png", "category": "News", "country": "GB", "language": "English", "quality": "HD", "source": "BROADCASTER"},
        {"id": "wion", "name": "WION", "streamUrl": "https://d7x8z4yuq42qn.cloudfront.net/index_7.m3u8", "logoUrl": "https://upload.wikimedia.org/wikipedia/commons/thumb/7/7a/WION_logo.svg/512px-WION_logo.svg.png", "category": "News", "country": "IN", "language": "English", "quality": "HD", "source": "BROADCASTER"},
        {"id": "abc_news_au", "name": "ABC News Australia", "streamUrl": "https://c.mjh.nz/abc-news.m3u8", "logoUrl": "https://upload.wikimedia.org/wikipedia/commons/thumb/0/02/ABC_News_24_logo.svg/512px-ABC_News_24_logo.svg.png", "category": "News", "country": "AU", "language": "English", "quality": "HD", "source": "BROADCASTER"},
        {"id": "nat_geo", "name": "National Geographic", "streamUrl": "http://185.102.171.218/NatGeo/index.m3u8", "logoUrl": "https://upload.wikimedia.org/wikipedia/commons/thumb/f/fc/Nat_Geo_logo.svg/512px-Nat_Geo_logo.svg.png", "category": "Documentary", "country": "US", "language": "English", "quality": "HD", "source": "BROADCASTER"},
        {"id": "history_channel", "name": "History Channel", "streamUrl": "http://84.17.50.102/history/index.m3u8", "logoUrl": "https://upload.wikimedia.org/wikipedia/commons/thumb/d/d1/History_Channel_logo.svg/512px-History_Channel_logo.svg.png", "category": "Documentary", "country": "US", "language": "English", "quality": "HD", "source": "BROADCASTER"},
        {"id": "bbc_earth", "name": "BBC Earth", "streamUrl": "https://jmp2.uk/plu-656535fc2c46f30008870fae.m3u8", "logoUrl": "https://upload.wikimedia.org/wikipedia/commons/thumb/6/6e/BBC_Earth_logo.svg/512px-BBC_Earth_logo.svg.png", "category": "Documentary", "country": "GB", "language": "English", "quality": "FHD", "source": "BROADCASTER"},
        {"id": "rt_documentary", "name": "RT Documentary", "streamUrl": "https://rt-rtd.rttv.com/live/rtdoc/playlist.m3u8", "logoUrl": "https://upload.wikimedia.org/wikipedia/commons/thumb/8/80/RT_Documentary_logo.svg/512px-RT_Documentary_logo.svg.png", "category": "Documentary", "country": "RU", "language": "English", "quality": "HD", "source": "BROADCASTER"},
        {"id": "pbs_kids", "name": "PBS Kids", "streamUrl": "https://livestream.pbskids.org/out/v1/14507d931bbe48a69287e4850e53443c/est.m3u8", "logoUrl": "https://upload.wikimedia.org/wikipedia/en/thumb/3/33/PBS_Kids_logo.svg/512px-PBS_Kids_logo.svg.png", "category": "Kids", "country": "US", "language": "English", "quality": "HD", "source": "BROADCASTER"},
        {"id": "pbs_food", "name": "PBS Food", "streamUrl": "https://d3w43rc3ob044a.cloudfront.net/PBS_Food.m3u8", "logoUrl": "https://upload.wikimedia.org/wikipedia/commons/thumb/3/33/PBS_logo.svg/512px-PBS_logo.svg.png", "category": "Food", "country": "US", "language": "English", "quality": "HD", "source": "BROADCASTER"},
        {"id": "pbs_travel", "name": "PBS Travel", "streamUrl": "https://d3hqevbyoxtkoi.cloudfront.net/PBS_Travel.m3u8", "logoUrl": "https://upload.wikimedia.org/wikipedia/commons/thumb/3/33/PBS_logo.svg/512px-PBS_logo.svg.png", "category": "Travel", "country": "US", "language": "English", "quality": "HD", "source": "BROADCASTER"},
        {"id": "pbs_nature", "name": "PBS Nature", "streamUrl": "https://d3mr43kyql7wgk.cloudfront.net/PBS_Nature.m3u8", "logoUrl": "https://upload.wikimedia.org/wikipedia/commons/thumb/3/33/PBS_logo.svg/512px-PBS_logo.svg.png", "category": "Nature", "country": "US", "language": "English", "quality": "HD", "source": "BROADCASTER"},
        {"id": "hbo", "name": "HBO", "streamUrl": "https://d18dyiwuowmsp6.cloudfront.net/hbo/hbo.m3u8", "logoUrl": "https://upload.wikimedia.org/wikipedia/commons/thumb/d/de/HBO_logo.svg/512px-HBO_logo.svg.png", "category": "Premium", "country": "US", "language": "English", "quality": "FHD", "source": "BROADCASTER"},
        {"id": "showtime", "name": "Showtime", "streamUrl": "https://d18dyiwuowmsp6.cloudfront.net/showtime/showtime.m3u8", "logoUrl": "https://upload.wikimedia.org/wikipedia/commons/thumb/0/06/Showtime_logo.svg/512px-Showtime_logo.svg.png", "category": "Premium", "country": "US", "language": "English", "quality": "FHD", "source": "BROADCASTER"},
        {"id": "starz", "name": "Starz", "streamUrl": "https://d18dyiwuowmsp6.cloudfront.net/starz/starz.m3u8", "logoUrl": "https://upload.wikimedia.org/wikipedia/commons/thumb/1/1b/Starz_Logo.svg/512px-Starz_Logo.svg.png", "category": "Premium", "country": "US", "language": "English", "quality": "FHD", "source": "BROADCASTER"},
        {"id": "cinemax", "name": "Cinemax", "streamUrl": "https://d18dyiwuowmsp6.cloudfront.net/cinemax/cinemax.m3u8", "logoUrl": "https://upload.wikimedia.org/wikipedia/commons/thumb/4/4a/Cinemax_logo.svg/512px-Cinemax_logo.svg.png", "category": "Premium", "country": "US", "language": "English", "quality": "FHD", "source": "BROADCASTER"},
        {"id": "espn", "name": "ESPN", "streamUrl": "https://cdn1.sportnettv.live/espn/espn.m3u8", "logoUrl": "https://upload.wikimedia.org/wikipedia/commons/thumb/2/2f/ESPN_logo.svg/512px-ESPN_logo.svg.png", "category": "Sports", "country": "US", "language": "English", "quality": "FHD", "source": "BROADCASTER"},
        {"id": "nfl_network", "name": "NFL Network", "streamUrl": "https://cdn1.sportnettv.live/nfl/nfl.m3u8", "logoUrl": "https://upload.wikimedia.org/wikipedia/commons/thumb/2/20/NFL_Network_logo.svg/512px-NFL_Network_logo.svg.png", "category": "Sports", "country": "US", "language": "English", "quality": "FHD", "source": "BROADCASTER"},
        {"id": "tvc_news_ng", "name": "TVC News", "streamUrl": "http://69.64.57.208/tvcnews/playlist.m3u8", "logoUrl": "https://upload.wikimedia.org/wikipedia/commons/thumb/9/98/TVC_News_logo.svg/512px-TVC_News_logo.svg.png", "category": "News", "country": "NG", "language": "English", "quality": "SD", "source": "BROADCASTER"},
        {"id": "news_central_ng", "name": "News Central TV", "streamUrl": "https://wf.newscentral.ng:8443/hls/stream.m3u8", "logoUrl": "https://newscentraltv.com/wp-content/uploads/2023/12/News-Central-Logo-1.png", "category": "News", "country": "NG", "language": "English", "quality": "HD", "source": "BROADCASTER"},
        {"id": "wazobia_max", "name": "Wazobia Max TV", "streamUrl": "https://wazobia.live:8333/channel/wmax.m3u8", "logoUrl": "https://wazobiamax.ng/wp-content/uploads/2022/07/wazobia-max-tv-logo.png", "category": "General", "country": "NG", "language": "English", "quality": "HD", "source": "BROADCASTER"},
        {"id": "channels_tv_ng", "name": "Channels TV", "streamUrl": "https://cs2.push2stream.com/CHANNELSTV-DVR/playlist.m3u8", "logoUrl": "https://upload.wikimedia.org/wikipedia/en/thumb/4/4f/Channels_TV_logo.svg/512px-Channels_TV_logo.svg.png", "category": "News", "country": "NG", "language": "English", "quality": "HD", "source": "BROADCASTER"},
        {"id": "arise_news", "name": "Arise News", "streamUrl": "https://liveedge-arisenews.visioncdn.com/live-hls/arisenews/arisenews/arisenews_web/master.m3u8", "logoUrl": "https://upload.wikimedia.org/wikipedia/commons/thumb/9/9a/Arise_News_logo.svg/512px-Arise_News_logo.svg.png", "category": "News", "country": "NG", "language": "English", "quality": "FHD", "source": "BROADCASTER"},
        {"id": "sabc_news_za", "name": "SABC News", "streamUrl": "https://sabconetanw.cdn.mangomolo.com/news/smil:news.stream.smil/master.m3u8", "logoUrl": "https://i.imgur.com/liLta8j.png", "category": "News", "country": "ZA", "language": "English", "quality": "HD", "source": "BROADCASTER"},
        {"id": "k24_ke", "name": "K24", "streamUrl": "https://livecdn.premiumfree.tv/afxpstr/K24Backup/index.m3u8", "logoUrl": "https://i.imgur.com/SUjo6Bm.png", "category": "News", "country": "KE", "language": "English", "quality": "HD", "source": "BROADCASTER"},
        {"id": "tv3_gh", "name": "TV3 Ghana", "streamUrl": "https://g2qd3exjy7an-hls-live.5centscdn.com/webtv3/ghanatv.stream/playlist.m3u8", "logoUrl": "https://i.imgur.com/8t967Hq.png", "category": "General", "country": "GH", "language": "English", "quality": "SD", "source": "BROADCASTER"},
        {"id": "2m_ma", "name": "2M", "streamUrl": "https://stream-lb.livemediama.com/2m-tnt/hls/master.m3u8", "logoUrl": "https://i.imgur.com/PJYTfHi.png", "category": "General", "country": "MA", "language": "Arabic", "quality": "FHD", "source": "BROADCASTER"},
    ]
    log.info("INDEPENDENT: %d channels", len(channels))
    return channels


async def fetch_broadcaster(session: aiohttp.ClientSession, probe: bool = False, probe_timeout: int = 5) -> list[dict]:
    log.info("Fetching BROADCASTER...")
    bcast_file = os.path.join(os.path.dirname(__file__), "..", "..",
                              "core", "src", "main", "assets", "broadcaster_sources.json")
    if not os.path.exists(bcast_file):
        log.warning("broadcaster_sources.json not found")
        return []

    try:
        with open(bcast_file, encoding="utf-8-sig") as f:
            items = json.load(f)
    except Exception as e:
        log.warning("Failed to load broadcaster_sources.json: %s", e)
        return []

    channels = []
    for item in items:
        channels.append({
            "id": item.get("id", ""),
            "name": item.get("name", ""),
            "streamUrl": item.get("streamUrl", ""),
            "logoUrl": item.get("logoUrl"),
            "category": item.get("category"),
            "country": item.get("country"),
            "language": item.get("language"),
            "quality": item.get("quality"),
            "source": "BROADCASTER",
            "headers": item.get("headers") or {},
            "drmKeyId": item.get("drmKeyId"),
            "drmKey": item.get("drmKey"),
        })
    log.info("BROADCASTER: %d channels", len(channels))
    return channels


# ── Utility ──────────────────────────────────────────────────────────────────

async def fetch_text_safe(url: str, session: aiohttp.ClientSession, headers: dict | None = None, timeout=None) -> str | Exception:
    try:
        t = timeout or HTTP_TIMEOUT
        h = {**HEADERS, **(headers or {})}
        async with session.get(url, timeout=t, headers=h, ssl=False) as resp:
            resp.raise_for_status()
            try:
                return await resp.text()
            except UnicodeDecodeError:
                raw = await resp.read()
                return raw.decode("latin-1")
    except Exception as e:
        return e


async def probe_channels(channels: list[dict], session: aiohttp.ClientSession, timeout_sec: int, concurrency: int = 50) -> list[dict]:
    """Probe stream URLs and mark dead ones."""
    sem = asyncio.Semaphore(concurrency)

    async def probe_one(ch: dict) -> dict:
        url = ch.get("streamUrl", "")
        if not url:
            ch["_probed"] = False
            return ch
        async with sem:
            alive = await probe_url(url, session, timeout_sec)
        ch["_probed"] = alive
        return ch

    results = await asyncio.gather(*[probe_one(ch) for ch in channels])
    alive = [r for r in results if r.get("_probed")]
    dead = len(results) - len(alive)
    if dead:
        log.info("Probe: %d alive, %d dead (removed)", len(alive), dead)
    for ch in alive:
        ch.pop("_probed", None)
    return alive


# ── Output ───────────────────────────────────────────────────────────────────

def write_index(channels: list[dict], name: str, output_dir: str):
    """Write a per-source index JSON file."""
    obj = {
        "version": VERSION,
        "source": name,
        "total": len(channels),
        "channels": channels,
    }
    path = os.path.join(output_dir, f"{name.lower()}_index.json")
    with open(path, "w", encoding="utf-8") as f:
        json.dump(obj, f, ensure_ascii=False, indent=None, separators=(",", ":"))
    log.info("Wrote %s (%d channels, %.1f KB)", path, len(channels),
             os.path.getsize(path) / 1024)





# ── Main ─────────────────────────────────────────────────────────────────────

SOURCE_FETCHERS = [
    ("iptv", fetch_iptv),
    ("free_tv", fetch_free_tv),
    ("fast_tv", fetch_fast_tv),
    ("premium", fetch_premium),
    ("free_live", fetch_free_live),
    ("radio", fetch_radio),
    ("stmify", fetch_stmify),
    ("dlhd", fetch_dlhd),
    ("youtube_tv", fetch_youtube_tv),
    ("independent", fetch_independent),
    ("broadcaster", fetch_broadcaster),
]


async def main():
    parser = argparse.ArgumentParser(description="Build hosted channel index")
    parser.add_argument("--output-dir", default="./dist", help="Output directory for JSON files")
    parser.add_argument("--probe", action="store_true", help="Probe stream URLs and remove dead ones")
    parser.add_argument("--probe-timeout", type=int, default=5, help="Timeout per probe request in seconds")
    args = parser.parse_args()

    output_dir = os.path.abspath(args.output_dir)
    os.makedirs(output_dir, exist_ok=True)

    connector = aiohttp.TCPConnector(limit=100, limit_per_host=20, force_close=True)
    async with aiohttp.ClientSession(connector=connector) as session:
        tasks = {name: fetcher(session, args.probe, args.probe_timeout) for name, fetcher in SOURCE_FETCHERS}
        results = await asyncio.gather(*tasks.values(), return_exceptions=True)

        all_channels: list[dict] = []
        seen_ids: set[str] = set()

        for name, result in zip(tasks.keys(), results):
            if isinstance(result, Exception):
                log.error("%s failed: %s", name, result)
                continue

            channels = result
            elapsed = 0  # timing not tracked per-source in parallel mode
            log.info("%s: %d channels", name, len(channels))

            if args.probe and channels:
                channels = await probe_channels(channels, session, args.probe_timeout)

            if channels:
                write_index(channels, name, output_dir)

            if name == "premium":
                log.info("Skipping premium from combined index (too large — %d channels)", len(channels))
                continue

            for ch in channels:
                cid = ch.get("id", "")
                if cid and cid not in seen_ids:
                    seen_ids.add(cid)
                    all_channels.append(ch)

        log.info("Combined total: %d channels (unique by id, skipped — pipeline handles combined channels.json)", len(all_channels))

    log.info("Done — output in %s", output_dir)


if __name__ == "__main__":
    asyncio.run(main())
