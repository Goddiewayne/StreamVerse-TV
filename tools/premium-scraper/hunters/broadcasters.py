"""
Broadcaster hunter — discovers direct FTA/satellite broadcaster CDN playlists.

Targets:
  • Known GitHub repos that curate official broadcaster HLS feeds
  • DStv (MultiChoice) public channel API for metadata/EPG
  • Amagi/Praxis FAST channel manifests (origin CDN URLs)
  • CNR satellite pull feeds (Chinese FTA satellite)
"""

import logging
import re
from typing import List

import requests

log = logging.getLogger("hunter.broadcasters")

BROADCASTER_GITHUB_REPOS = [
    {
        "owner": "Free-TV",
        "repo": "IPTV",
        "branch": "master",
        "paths": ["playlist.m3u8", "streams/africa.m3u", "streams/sports.m3u"],
    },
    {
        "owner": "iptv-org",
        "repo": "iptv",
        "branch": "master",
        "paths": ["countries/us.m3u", "countries/gb.m3u", "countries/de.m3u",
                   "countries/fr.m3u", "countries/cn.m3u", "countries/jp.m3u",
                   "countries/ng.m3u", "countries/za.m3u", "countries/in.m3u",
                   "countries/br.m3u"],
    },
]

ORIGIN_CDN_PATTERNS = re.compile(
    r"https?://(?:"
    r"satellitepull\.cnr\.cn|"          # Chinese FTA satellite
    r"icdn-rai\d?\.akamaized\.net|"     # RAI (Italy)
    r"icdn-rainews\.akamaized\.net|"    # RAI News
    r"mcdn\.daserste\.de|"              # ARD (Germany)
    r"zdf-hls-\d+\.akamaized\.net|"    # ZDF (Germany)
    r"dwamdstream\d+\.akamaized\.net|" # DW (Germany)
    r"artelive-lh\.akamaized\.net|"    # ARTE
    r"stream\.francetv\.fr|"           # France TV
    r"live\.france24\.com|"            # France 24
    r"rtvelivestream\.akamaized\.net|" # TVE (Spain)
    r"tv-trt\w*\.medya\.trt\.com\.tr|" # TRT (Turkey)
    r"rt-\w+\.rttv\.com|"             # RT (Russia)
    r"masterpl\.hls\.nhkworld\.jp|"    # NHK World
    r"live-hls-\w+-\w+\.getaj\.net|"  # Al Jazeera
    r"live\.alarabiya\.net|"           # Al Arabiya
    r"d2e1aslsp3qdki\.cloudfront\.net|" # CNA (Singapore)
    r"cbcnewshd-f\.akamaihd\.net|"    # CBC (Canada)
    r"cbsn-\w+\.cbsnstream\.cbsnews\.com|" # CBS News
    r"nmx\d+ota\.akamaized\.net|"     # Newsmax
    r"amg\d+-\w+-amg\d+\w*-\w+-.*\.amagi\.tv" # Amagi FAST channels
    r")/",
    re.IGNORECASE,
)

DSTV_CHANNEL_API = "https://dstv.stream/api/cs-mobile/v7/epg-service/channels/events"


def _scrape_github_broadcasters() -> List[str]:
    found = []
    for repo in BROADCASTER_GITHUB_REPOS:
        for path in repo["paths"]:
            url = f"https://raw.githubusercontent.com/{repo['owner']}/{repo['repo']}/{repo['branch']}/{path}"
            try:
                resp = requests.head(url, timeout=8, headers={"User-Agent": "Mozilla/5.0"})
                if resp.status_code == 200:
                    found.append(url)
                    log.info(f"  GitHub broadcaster: {url}")
            except Exception:
                pass
    return found


def _scrape_dstv_channels() -> List[str]:
    try:
        resp = requests.get(
            DSTV_CHANNEL_API,
            headers={
                "User-Agent": "DSTVNow/7.0 (Android 14)",
                "Accept": "application/json",
            },
            timeout=12,
        )
        if resp.status_code == 200:
            data = resp.json()
            channels = data.get("channels", data) if isinstance(data, dict) else data
            count = len(channels) if isinstance(channels, list) else 0
            log.info(f"  DStv API: {count} channels listed (metadata only, streams require auth)")
    except Exception as e:
        log.debug(f"  DStv API failed: {e}")
    return []


def _scan_for_origin_cdns(existing_urls: List[str]) -> List[str]:
    origin_urls = []
    for url in existing_urls:
        try:
            resp = requests.get(
                url,
                headers={"User-Agent": "Mozilla/5.0"},
                timeout=15,
            )
            if resp.status_code != 200 or "#EXTM3U" not in resp.text:
                continue
            for line in resp.text.splitlines():
                line = line.strip()
                if line.startswith("http") and ORIGIN_CDN_PATTERNS.search(line):
                    if line not in origin_urls:
                        origin_urls.append(line)
        except Exception:
            continue
    if origin_urls:
        log.info(f"  Found {len(origin_urls)} direct broadcaster CDN stream URLs")
    return origin_urls


def hunt() -> List[str]:
    all_urls = []

    log.info("Broadcasters: scraping GitHub FTA repos...")
    all_urls.extend(_scrape_github_broadcasters())

    log.info("Broadcasters: probing DStv public API...")
    _scrape_dstv_channels()

    if all_urls:
        log.info("Broadcasters: scanning playlists for origin CDN URLs...")
        _scan_for_origin_cdns(all_urls)

    seen = set()
    unique = []
    for u in all_urls:
        if u not in seen:
            seen.add(u)
            unique.append(u)

    log.info(f"Broadcasters total: {len(unique)} M3U URLs")
    return unique
