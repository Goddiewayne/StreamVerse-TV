"""
Pastebin hunter — searches Pastebin for raw M3U playlists.

Pastebin is a favourite dump site for ephemeral IPTV playlists.
They get deleted quickly, so scrape frequently.

Strategy:
  1. Scrape the Pastebin archive/recent-paste list
  2. Filter by title/syntax keywords
  3. Fetch raw content, check for #EXTM3U header
"""

import logging
import re
import time
from typing import List
from urllib.parse import urljoin

import requests

log = logging.getLogger("hunter.pastebin")

KNOWN_PASTE_SITES = [
    # Pastebin
    "https://pastebin.com",
    # Alternative paste sites that host M3U dumps
    "https://rentry.co",
    "https://rentry.org",
    "https://bin.disroot.org",
    "https://paste.ee",
    "https://ivpaste.com",
]

SEARCH_KEYWORDS = [
    "iptv",
    "m3u",
    "playlist.m3u",
    "premium.m3u",
    "dstv",
    "supersport",
    "live tv",
]


def _scrape_pastebin_recent() -> List[str]:
    """Scrape Pastebin's recent public pastes list."""
    urls = []
    try:
        resp = requests.get(
            "https://pastebin.com/archive",
            headers={
                "User-Agent": "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36",
            },
            timeout=15,
        )
        if resp.status_code != 200:
            return urls

        # Extract paste IDs from the archive table
        paste_ids = re.findall(r'/raw/([a-zA-Z0-9]{8})', resp.text)
        for pid in paste_ids[:30]:  # check first 30
            raw_url = f"https://pastebin.com/raw/{pid}"
            if _is_valid_m3u(raw_url):
                urls.append(raw_url)
                log.debug(f"  Pastebin valid: {raw_url}")
            time.sleep(0.5)
    except Exception as e:
        log.debug(f"Pastebin archive scrape failed: {e}")

    return urls


def _scrape_rentry_recent() -> List[str]:
    """Scrape rentry.co recent pages (they have no public archive, try common paths)."""
    # Rentry doesn't have a public archive, but people share links on Telegram
    # We check known slugs from previous discoveries
    return []


def _is_valid_m3u(url: str) -> bool:
    """Check if a URL points to a valid M3U playlist."""
    try:
        resp = requests.get(
            url,
            headers={"User-Agent": "Mozilla/5.0"},
            timeout=10,
        )
        return resp.status_code == 200 and "#EXTM3U" in resp.text
    except Exception:
        return False


def _scrape_paste_ee_recent() -> List[str]:
    """Scrape paste.ee recent public pastes."""
    urls = []
    try:
        resp = requests.get(
            "https://paste.ee/browse",
            headers={
                "User-Agent": "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36",
            },
            timeout=15,
        )
        if resp.status_code != 200:
            return urls

        paste_links = re.findall(r'href="/([a-f0-9]{32})"', resp.text)
        for pid in paste_links[:20]:
            raw_url = f"https://paste.ee/r/{pid}"
            if _is_valid_m3u(raw_url):
                urls.append(raw_url)
            time.sleep(0.5)
    except Exception as e:
        log.debug(f"Paste.ee scrape failed: {e}")

    return urls


def hunt() -> List[str]:
    """Hunt for M3U playlist URLs across paste sites."""
    all_urls = []

    log.info("Pastebin: scanning recent pastes...")
    all_urls.extend(_scrape_pastebin_recent())

    log.info("Paste.ee: scanning recent pastes...")
    all_urls.extend(_scrape_paste_ee_recent())

    # Deduplicate
    seen = set()
    unique = []
    for u in all_urls:
        if u not in seen:
            seen.add(u)
            unique.append(u)

    if unique:
        log.info(f"Pastebin total: {len(unique)} unique M3U URLs discovered")

    return unique
