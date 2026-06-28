"""
Aggregator hunter — scrapes known IPTV aggregator/blog sites for M3U links.

These are community-run sites that compile and update premium playlists
daily. Domain rotation is common — if a domain dies, add it to DEAD_DOMAINS
and find the new one via Google/GitHub.
"""

import logging
import re
import time
from typing import List

import requests

log = logging.getLogger("hunter.aggregators")

# These sites are known to aggregate premium M3U playlists.
# They rotate domains and play whack-a-mole with DMCA.
KNOWN_AGGREGATORS = [
    # IPTV aggregator & blog sites
    {
        "name": "freeiptv",
        "url": "https://freeiptv.co/playlist",
        "type": "direct_m3u",
    },
    {
        "name": "iptvlist",
        "url": "https://iptvlist.net/playlist.m3u",
        "type": "direct_m3u",
    },
    {
        "name": "iptvcat",
        "url": "https://iptvcat.com/playlist.m3u",
        "type": "direct_m3u",
    },
    {
        "name": "iptvgratis",
        "url": "https://iptvgratis.net/playlist.m3u",
        "type": "direct_m3u",
    },
    {
        "name": "tivim8",
        "url": "https://tivim8.com/playlist.m3u",
        "type": "direct_m3u",
    },
    {
        "name": "iptvfree",
        "url": "https://iptvfree.tv/playlist.m3u",
        "type": "direct_m3u",
    },
    # DSTV-specific aggregators
    {
        "name": "dstvplaylist",
        "url": "https://dstvplaylist.com/playlist.m3u",
        "type": "direct_m3u",
    },
    {
        "name": "supersportm3u",
        "url": "https://supersportm3u.xyz/playlist.m3u",
        "type": "direct_m3u",
    },
    {
        "name": "saiptv",
        "url": "https://sa-iptv.co.za/playlist.m3u",
        "type": "direct_m3u",
    },
    {
        "name": "m3usa",
        "url": "https://m3usa.co.za/playlist.m3u",
        "type": "direct_m3u",
    },
]

# Romanian / Russian streaming proxy sites known to host satellite feeds
STREAM_HOSTS = [
    "https://pontos.phantemlis.top",
    "https://kolis.phantemlis.top",
    "https://fomis.phantemlis.top",
    "https://hamis.romponalis.st",
]


# ── Free-TV/IPTV on GitHub (quality-curated alternative to iptv-org) ──

FREE_TV_SOURCES = [
    "https://raw.githubusercontent.com/Free-TV/IPTV/master/playlist.m3u8",
    "https://raw.githubusercontent.com/Free-TV/IPTV/master/streams/africa.m3u",
    "https://raw.githubusercontent.com/Free-TV/IPTV/master/streams/sports.m3u",
    "https://raw.githubusercontent.com/Free-TV/IPTV/master/streams/movies.m3u",
]

# ── Community-maintained category playlists ──

COMMUNITY_SOURCES = [
    # iptv-org (already in main scraper, but include country-specific ones)
    "https://cdn.jsdelivr.net/gh/iptv-org/iptv@master/regions/afr.m3u",
    "https://cdn.jsdelivr.net/gh/iptv-org/iptv@master/categories/sports.m3u",
    "https://cdn.jsdelivr.net/gh/iptv-org/iptv@master/categories/movies.m3u",
    "https://cdn.jsdelivr.net/gh/iptv-org/iptv@master/categories/series.m3u",
    "https://cdn.jsdelivr.net/gh/iptv-org/iptv@master/categories/news.m3u",
    "https://cdn.jsdelivr.net/gh/iptv-org/iptv@master/categories/documentary.m3u",
    # Additional community-driven sources
    "https://raw.githubusercontent.com/iptv-org/iptv/master/regions/afr.m3u",
    "https://raw.githubusercontent.com/iptv-org/iptv/master/regions/int.m3u",
]


def _check_direct_m3u(entry: dict) -> str | None:
    """Check if a direct M3U URL returns valid content."""
    try:
        resp = requests.get(
            entry["url"],
            headers={"User-Agent": "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36"},
            timeout=15,
        )
        if resp.status_code == 200 and "#EXTM3U" in resp.text:
            log.info(f"  {entry['name']}: alive ({len(resp.text)} bytes)")
            return entry["url"]
    except Exception:
        pass
    return None


def _scrape_stream_hosts() -> List[str]:
    """Probe known stream proxy hosts for M3U endpoints."""
    found = []
    for host in STREAM_HOSTS:
        # These hosts serve m3u8 directly on common paths
        for path in ["", "/index.m3u8", "/playlist.m3u8", "/premium.m3u8"]:
            url = f"{host}{path}"
            try:
                resp = requests.get(
                    url,
                    headers={
                        "User-Agent": "Mozilla/5.0",
                        "Accept": "*/*",
                    },
                    timeout=8,
                )
                if resp.status_code == 200 and ("#EXTM3U" in resp.text or "#EXT-X-STREAM-INF" in resp.text):
                    found.append(url)
                    log.info(f"  Stream host valid: {url}")
                    break
            except Exception:
                continue
    return found


def hunt() -> List[str]:
    """Hunt for M3U URLs across known aggregator sites and stream hosts."""
    all_urls = []

    log.info("Aggregators: checking direct playlist URLs...")
    for entry in KNOWN_AGGREGATORS:
        url = _check_direct_m3u(entry)
        if url:
            all_urls.append(url)
        time.sleep(1)

    log.info("Aggregators: probing stream proxy hosts...")
    all_urls.extend(_scrape_stream_hosts())

    # Always include Free-TV sources (reliable)
    all_urls.extend(FREE_TV_SOURCES)

    # Include community sources that aren't already in the main list
    all_urls.extend(COMMUNITY_SOURCES)

    seen = set()
    unique = []
    for u in all_urls:
        if u not in seen:
            seen.add(u)
            unique.append(u)

    if unique:
        log.info(f"Aggregators total: {len(unique)} unique M3U URLs")

    return unique
