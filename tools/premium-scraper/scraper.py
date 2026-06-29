#!/usr/bin/env python3
"""
Premium channel scraper — discovers fresh premium playlist URLs via multiple
hunters (GitHub repos/gists, Telegram, Pastebin, aggregator sites), then
validates, merges, and outputs a unified M3U + JSON config for the Android app.

Usage:
    python scraper.py                          # Full pipeline (all hunters)
    python scraper.py --hunters github,telegram # Specific hunters only
    python scraper.py --discover               # Discovery only
    python scraper.py --validate               # Validate existing playlists
    python scraper.py --output premium.m3u     # Custom output path
    python scraper.py --telethon-client         # Use Telethon for Telegram (if installed)
"""

import argparse
import json
import logging
import os
import re
import sys
import time
from concurrent.futures import ThreadPoolExecutor, as_completed
from datetime import datetime, timezone
from pathlib import Path

import requests

from hunters import GithubHunter, TelegramHunter, PastebinHunter, AggregatorHunter, ResellerHunter, BroadcasterHunter

logging.basicConfig(
    level=logging.INFO,
    format="[%(levelname)s] %(message)s",
)
log = logging.getLogger("premium-scraper")

# ── Known premium playlist sources ──────────────────────────────────────────

GITHUB_SOURCES = [
    "https://iptv-org.github.io/iptv/categories/movies.m3u",
    "https://iptv-org.github.io/iptv/categories/sports.m3u",
    "https://iptv-org.github.io/iptv/categories/series.m3u",
    "https://iptv-org.github.io/iptv/categories/documentary.m3u",
]

# ── Premium keywords for channel classification ─────────────────────────────

PREMIUM_KEYWORDS = re.compile(
    r"\b(?:"
    r"hbo|showtime|starz|cinemax|max|"
    r"nfl|nba|nhl|mlb|ufc|wwe|"
    r"espn|fox\s*sports|bt\s*sport|sky\s*sport|"
    r"nfl\s*redzone|nfl\s*network|"
    r"paramount\+|peacock|disney\+|"
    r"cinema|premier(?!\s*pro)|"
    r"sky\s*cinema|sky\s*atlantic|"
    r"dazn|"
    r"disney\s*(jr|xd|channel)|nickelodeon|cartoon\s*network|"
    r"dstv|supersport|canal\+|bein\s*sport|mzanzi|kyknet"
    r")\b",
    re.IGNORECASE,
)


def fetch_m3u(url: str, timeout: int = 15) -> list[dict] | None:
    """Fetch and parse an M3U playlist."""
    try:
        resp = requests.get(url, timeout=timeout, headers={
            "User-Agent": "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36",
        })
        resp.raise_for_status()
        text = resp.text
        if "#EXTM3U" not in text:
            return None
        channels = []
        current = {}
        for line in text.splitlines():
            line = line.strip()
            if line.startswith("#EXTINF:"):
                current = {"name": "", "tvg_id": "", "tvg_logo": "", "group": ""}
                tvg_match = re.search(r'tvg-id="([^"]*)"', line)
                if tvg_match:
                    current["tvg_id"] = tvg_match.group(1)
                logo_match = re.search(r'tvg-logo="([^"]*)"', line)
                if logo_match:
                    current["tvg_logo"] = logo_match.group(1)
                group_match = re.search(r'group-title="([^"]*)"', line)
                if group_match:
                    current["group"] = group_match.group(1)
                name_match = re.search(r',(.+)$', line)
                if name_match:
                    current["name"] = name_match.group(1).strip()
            elif line and not line.startswith("#"):
                current["url"] = line
                if current.get("name"):
                    channels.append(current)
                current = {}
        return channels
    except Exception as e:
        log.debug(f"Failed to fetch {url}: {e}")
        return None


def is_premium(name: str, group: str = "") -> bool:
    """Check if a channel name or group indicates premium content."""
    return bool(PREMIUM_KEYWORDS.search(name)) or bool(
        PREMIUM_KEYWORDS.search(group)
    )


def validate_playlist(url: str) -> dict:
    """Quick-probe: check HTTP status and first 2 KB for #EXTM3U header."""
    result = {"url": url, "valid": False, "channels": 0, "premium": 0}
    try:
        resp = requests.get(url, timeout=12, headers={
            "User-Agent": "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36",
            "Range": "bytes=0-2047",
        })
        if resp.status_code not in (200, 206):
            return result
        data = resp.text
        if not data.startswith("#EXTM3U"):
            return result
        result["valid"] = True
        # Estimate channel count from #EXTINF lines
        result["channels"] = data.count("#EXTINF:")
        result["premium"] = sum(1 for line in data.splitlines()
                                if is_premium(line, ""))
    except Exception:
        pass
    return result


def merge_playlists(channels_list: list[list[dict]]) -> str:
    """Merge multiple playlists into a single M3U, deduplicating by URL."""
    seen_urls = set()
    lines = ["#EXTM3U"]
    for channels in channels_list:
        for c in channels:
            url = c.get("url", "")
            if url in seen_urls:
                continue
            seen_urls.add(url)
            name = c.get("name", "Unknown")
            tvg_id = c.get("tvg_id", "")
            tvg_logo = c.get("tvg_logo", "")
            group = c.get("group", "Premium")
            attrs = f'tvg-id="{tvg_id}" tvg-logo="{tvg_logo}" group-title="{group}"'
            lines.append(f'#EXTINF:-1 {attrs},{name}')
            lines.append(url)
    return "\n".join(lines)


def update_android_config(output_path: Path, sources: list[str]):
    """Generate a JSON config file the Android PremiumClient can ingest."""
    config = {
        "generated": datetime.now(timezone.utc).isoformat(),
        "sources": sources,
    }
    with open(output_path, "w", encoding="utf-8") as f:
        json.dump(config, f, indent=2)
    log.info(f"Config written to {output_path}")


def run_hunters(selected: list[str], telethon: bool) -> list[str]:
    """Run selected hunters and return discovered M3U URLs."""
    found = []

    if "github" in selected:
        log.info("=== GitHub hunter ===")
        found.extend(GithubHunter())

    if "telegram" in selected:
        log.info("=== Telegram hunter ===")
        client = None
        if telethon:
            try:
                from telethon.sync import TelegramClient
                from telethon.errors import SessionPasswordNeededError
                log.info("Starting Telethon client (reads TELEGRAM_API_ID, TELEGRAM_API_HASH env vars)...")
                import os as _os
                api_id = _os.environ.get("TELEGRAM_API_ID")
                api_hash = _os.environ.get("TELEGRAM_API_HASH")
                phone = _os.environ.get("TELEGRAM_PHONE")
                if api_id and api_hash:
                    session_path = _os.environ.get("TELEGRAM_SESSION", "premium_scraper")
                    client = TelegramClient(session_path, int(api_id), api_hash)
                    try:
                        client.start()
                    except SessionPasswordNeededError:
                        log.warning("Telethon needs 2FA password — falling back to public preview")
                        client = None
                    except Exception:
                        if phone:
                            saved_code = _os.environ.get("TELEGRAM_CODE")
                            code_callback = lambda: saved_code if saved_code else input("Enter Telegram code: ")
                            client.start(phone=phone, code_callback=code_callback)
                        else:
                            log.warning("No session and no phone — falling back to public preview")
                            client = None
                else:
                    log.warning("TELEGRAM_API_ID/HASH not set — falling back to public preview")
            except ImportError:
                log.warning("Telethon not installed — falling back to public preview")
        found.extend(TelegramHunter(telethon_client=client))
        if client:
            client.disconnect()

    if "pastebin" in selected:
        log.info("=== Pastebin hunter ===")
        found.extend(PastebinHunter())

    if "aggregators" in selected:
        log.info("=== Aggregators hunter ===")
        found.extend(AggregatorHunter())

    if "resellers" in selected:
        log.info("=== Reseller panels hunter ===")
        found.extend(ResellerHunter())

    if "broadcasters" in selected:
        log.info("=== Broadcaster CDN hunter ===")
        found.extend(BroadcasterHunter())

    # Deduplicate
    seen = set()
    unique = []
    for u in found:
        if u not in seen:
            seen.add(u)
            unique.append(u)

    return unique


def main():
    parser = argparse.ArgumentParser(
        description="Premium channel playlist scraper — discovers DSTV / satellite M3U feeds"
    )
    parser.add_argument(
        "--hunters",
        default="github,telegram,pastebin,aggregators,broadcasters",
        help="Comma-separated hunters: github,telegram,pastebin,aggregators,resellers,broadcasters (default: all)",
    )
    parser.add_argument("--telethon-client", action="store_true",
                        help="Use Telethon for Telegram (needs TELEGRAM_API_ID/HASH env vars)")
    parser.add_argument("--discover", action="store_true", help="Run discovery only")
    parser.add_argument("--validate", action="store_true", help="Validate existing playlists only")
    parser.add_argument("--output", default="premium_channels.m3u", help="Output M3U path")
    parser.add_argument("--config", default="premium_sources.json", help="Output config path")
    parser.add_argument("--verbose", "-v", action="store_true", help="Verbose logging")
    args = parser.parse_args()

    if args.verbose:
        logging.getLogger().setLevel(logging.DEBUG)

    output_path = Path(args.output)
    config_path = Path(args.config)
    scraper_dir = Path(__file__).resolve().parent
    asset_path = scraper_dir.parent.parent / "core" / "src" / "main" / "assets" / "premium_sources.json"
    selected_hunters = [h.strip() for h in args.hunters.split(",")]

    # Phase 1: Discover via hunters
    discovered = []
    if args.discover or not (args.validate):
        log.info("Phase 1: Running hunters...")
        discovered = run_hunters(selected_hunters, args.telethon_client)
        log.info(f"Discovered {len(discovered)} unique playlist URLs")

    # Combine known + discovered, deduplicated while preserving order
    all_sources = list(dict.fromkeys(GITHUB_SOURCES + discovered))

    if args.discover:
        update_android_config(config_path, discovered)
        for url in discovered:
            print(url)
        return

    # Phase 2: Validate
    log.info(f"Phase 2: Validating {len(all_sources)} playlists...")
    valid_sources = []
    with ThreadPoolExecutor(max_workers=16) as pool:
        futures = {pool.submit(validate_playlist, url): url for url in all_sources}
        for future in as_completed(futures):
            result = future.result()
            url = futures[future]
            if result["valid"]:
                valid_sources.append(url)
                log.info(f"  OK  {url} ({result['channels']} ch, {result['premium']} premium)")
            else:
                log.debug(f"  DEAD {url}")

    if not valid_sources:
        log.warning("No valid premium playlists found!")
        with open(output_path, "w", encoding="utf-8") as f:
            f.write("#EXTM3U\n")
        update_android_config(config_path, [])
        return

    # Phase 3: Fetch and merge
    log.info(f"Phase 3: Merging {len(valid_sources)} playlists...")
    all_channels = []
    with ThreadPoolExecutor(max_workers=16) as pool:
        futures = {pool.submit(fetch_m3u, url): url for url in valid_sources}
        for future in as_completed(futures):
            channels = future.result()
            if channels:
                all_channels.append(channels)

    merged = merge_playlists(all_channels)
    with open(output_path, "w", encoding="utf-8") as f:
        f.write(merged)
    log.info(f"Merged playlist written to {output_path} ({merged.count('#EXTINF')} channels)")

    update_android_config(config_path, valid_sources)

    # Also write to Android assets as a map (key → URL) for PremiumClient
    asset_path.parent.mkdir(parents=True, exist_ok=True)
    source_map = {}
    for url in valid_sources:
        key = url.rstrip("/").rsplit("/", 1)[-1].replace(".m3u", "").replace(".m3u8", "")
        key = re.sub(r"[^a-zA-Z0-9_-]", "_", key).strip("_").lower() or f"src_{len(source_map)}"
        source_map[key] = url
    if GITHUB_SOURCES:
        for url in GITHUB_SOURCES:
            key = url.rstrip("/").rsplit("/", 1)[-1].replace(".m3u", "").replace(".m3u8", "")
            key = re.sub(r"[^a-zA-Z0-9_-]", "_", key).strip("_").lower() or f"src_{len(source_map)}"
            source_map[key] = url
    with open(asset_path, "w") as f:
        json.dump(source_map, f, indent=2)
    log.info(f"Config synced to Android assets: {asset_path} ({len(source_map)} sources)")

    total = merged.count("#EXTINF")
    premium_count = sum(1 for line in merged.splitlines() if PREMIUM_KEYWORDS.search(line))
    log.info(f"Summary: {total} total channels, ~{premium_count} premium-flagged")
    print(f"\nOutput: {output_path.resolve()}")
    print(f"Config: {config_path.resolve()}")
    if not os.environ.get("GITHUB_TOKEN"):
        print(f"\nTip: Set GITHUB_TOKEN env var for 5000 req/hr (vs 60 unauthenticated)")
    print(f"     --hunters github,telegram,pastebin,aggregators")
    print(f"     --telethon-client  (requires Telethon + env vars)")


if __name__ == "__main__":
    main()
