"""
Telegram hunter — scrapes M3U playlists from Telegram channels.

Two modes:
  1. Public preview (t.me/s/...) — no API key, rate-limited, only last ~50 msgs
  2. Telethon client — full history, needs API_ID + API_HASH from my.telegram.org
"""

import logging
import re
import time
from typing import List

import requests

log = logging.getLogger("hunter.telegram")

# Telegram channels known to dump free IPTV playlists
KNOWN_CHANNELS = [
    # Verified active channels
    "dstviptv",           # IPTV DSTV 2026 - premium DSTV/SuperSport
    "freeiptv2026",       # Worldwide IPTV Access | 2026 Official Group
    "bestiptv",           # Best Iptv Service
    "m3ulinks",           # M3u links(free) Indian channels
    # Newly discovered via search
    "IptvM3uPlaylists",               # IPTV M3u Playlists
    "IPTV_M3u_Playlists_Links_Mac",   # IPTV M3U PLAYLISTS,LINKS AND MAC
    "playlistiptvpremiumm",           # IPTV PREMIUM INDONESIA
    "M3uPlaylistUrlIPTV",             # M3u Playlist Url IPTV
    "visiptv6",                       # Free Iptv Mac portal
    "iptvfile",                       # Iptv Links m3u
]

PREMIUM_KEYWORDS = re.compile(
    r"\b(dstv|supersport|canal\+|bein\s*sport|"
    r"sky\s*sport|sky\s*cinema|hbo|showtime|starz|"
    r"espn|nfl|nba|ufc|premier\s*league|"
    r"sabc|e\.tv|mzanzi|kyknet)\b",
    re.IGNORECASE,
)


def _scrape_public_preview(channel: str) -> List[str]:
    """Scrape public Telegram preview page for M3U links."""
    urls = []
    try:
        resp = requests.get(
            f"https://t.me/s/{channel}",
            headers={
                "User-Agent": "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36",
                "Accept": "text/html,application/xhtml+xml",
            },
            timeout=15,
        )
        if resp.status_code != 200:
            return urls

        # Find all raw URLs ending in .m3u or .m3u8
        urls.extend(
            f"https://{u}" if u.startswith("t.me/") else u
            for u in re.findall(
                r'https?://[^\s<>"\']+\.m3u[8]?',
                resp.text,
            )
        )

        # Also find pastebin / gist URLs that might contain M3U
        urls.extend(
            u for u in re.findall(
                r'https?://(?:pastebin\.com|gist\.github\.com)/\S+',
                resp.text,
            )
        )

        if urls:
            log.info(f"Telegram @{channel}: {len(urls)} link(s) found")
    except Exception as e:
        log.debug(f"Telegram @{channel} scrape failed: {e}")
    return urls


def _scrape_telethon(channel: str, client) -> List[str]:
    """Scrape full message history using Telethon (requires running client)."""
    urls = []
    try:
        from telethon import errors as tg_errors
        from telethon.tl.functions.messages import GetHistoryRequest
    except ImportError:
        log.warning("Telethon not installed — skipping @{channel}")
        return urls

    try:
        entity = client.get_entity(channel)
        for msg in client.iter_messages(entity, limit=500):
            text = msg.text or ""
            # Also check media caption
            if msg.media and hasattr(msg.media, "caption") and msg.media.caption:
                text += "\n" + msg.media.caption
            if not text.strip():
                continue
            # M3U URLs
            urls.extend(
                u for u in re.findall(
                    r'https?://[^\s<>"\']+\.m3u[8]?', text,
                )
            )
            # Pastebin / gist
            urls.extend(
                u for u in re.findall(
                    r'https?://(?:pastebin\.com|gist\.github\.com)/\S+', text,
                )
            )
            # Any URL containing "iptv" or "m3u" in the path
            urls.extend(
                u for u in re.findall(
                    r'https?://[^\s<>"\']*(?:iptv|m3u|playlist)[^\s<>"\']*', text,
                    re.IGNORECASE,
                )
            )
        if urls:
            log.info(f"Telethon @{channel}: {len(urls)} link(s) found")
    except tg_errors.rpcerrorlist.UsernameNotOccupiedError:
        log.debug(f"Telethon @{channel}: username not occupied")
    except tg_errors.rpcerrorlist.ChatAdminRequiredError:
        log.debug(f"Telethon @{channel}: private channel (not accessible)")
    except tg_errors.rpcerrorlist.ChannelPrivateError:
        log.debug(f"Telethon @{channel}: private channel")
    except Exception as e:
        log.debug(f"Telethon @{channel} failed: {type(e).__name__} {e}")
    return urls


def hunt(telethon_client=None) -> List[str]:
    """
    Hunt for M3U playlist URLs across known Telegram channels.
    Optionally accepts a running Telethon client for full history.
    """
    all_urls = []
    for ch in KNOWN_CHANNELS:
        if telethon_client:
            all_urls.extend(_scrape_telethon(ch, telethon_client))
        else:
            all_urls.extend(_scrape_public_preview(ch))
        time.sleep(1)  # be polite to Telegram

    # Deduplicate
    seen = set()
    unique = []
    for u in all_urls:
        if u not in seen:
            seen.add(u)
            unique.append(u)

    if unique:
        log.info(f"Telegram total: {len(unique)} unique M3U URLs discovered")

    return unique
