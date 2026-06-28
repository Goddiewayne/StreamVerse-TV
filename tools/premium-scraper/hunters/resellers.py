"""
Reseller panel hunter — probes known IPTV reseller panels for M3U playlists.

Three strategies:
  1. Xtream UI API probe: scans ports 80, 8080, 25461, 8880 on known domains
     with common test credentials (demo/demo, test/test, etc.)
  2. Direct M3U scrape: checks known M3U distribution endpoints
  3. Playwright browser login: for panels that need form-based auth

Panels change domains constantly — add new ones from Telegram dumps.
"""

import logging
import os
import re
import time
from typing import List

import requests

log = logging.getLogger("hunter.reseller")

# ── Known reseller domains collected from Telegram / forums ──
# These serve Xtream UI or custom panels. Domains rotate weekly.
# Format: (domain, panel_type, known_ports)
# panel_type: "xtream" | "custom" | "m3u_direct"
KNOWN_PANELS = [
    # Xtream UI panels (common pattern: port 8080, 25461, or 80)
    ("iptvglobal.pro",        "xtream", [80, 8080, 25461]),
    ("premium-iptv.pro",      "xtream", [80, 8080, 25461]),
    ("goldeniptv.shop",       "xtream", [80, 8080]),
    ("ultraiptv.info",        "xtream", [80, 8080, 25461]),
    ("kingiptv.store",        "xtream", [80, 8080]),
    ("xtremehdiptv.com",      "xtream", [80, 8080, 25461]),
    ("iptvdigitaal.com",      "xtream", [80, 8080]),
    ("supremium.shop",        "xtream", [80, 8080, 25461]),
    ("apolloiptv.group",      "xtream", [80, 8080]),
    ("beastiptv.xyz",         "xtream", [80, 8080, 25461]),
    ("necropolaris.com",      "xtream", [80, 8080]),
    ("proiptv.world",         "xtream", [80, 8080, 25461]),
    ("bestbuyiptv.com",       "xtream", [80, 8080]),
    ("iptvrosco.com",         "xtream", [80, 8080, 25461]),
    ("ruya-iptv.com",         "xtream", [80, 8080]),
    # DSTV / SA-specific resellers
    ("dstvstreamz.online",    "xtream", [80, 8080, 25461]),
    ("supersportiptv.xyz",    "xtream", [80, 8080]),
    ("saiptvpro.com",         "xtream", [80, 8080, 25461]),
    ("afriplay.tv",           "xtream", [80, 8080]),
    # Custom panels
    ("tvpass.xyz",            "custom", [80, 443]),
    ("iptvaccess.shop",       "custom", [80, 443]),
    # Direct M3U distribution (no auth needed)
    ("m3u.tv",                "m3u_direct", [80]),
    ("freem3u.xyz",           "m3u_direct", [80]),
    ("iptvlist.xyz",          "m3u_direct", [80]),
]

# Common test credentials for Xtream UI panels
TEST_LINES = [
    ("demo", "demo"),
    ("test", "test"),
    ("free", "free"),
    ("trial", "trial"),
    ("demo", "1234"),
    ("test", "1234"),
    ("free", "1234"),
    ("demo", "12345"),
    ("test", "12345"),
    ("demo", "admin"),
    ("test", "admin"),
    ("iptv", "iptv"),
    ("premium", "premium"),
    ("vip", "vip"),
    ("0", "0"),
    ("1", "1"),
]

# Xtream UI API paths
XTREAM_PATHS = [
    "/get.php",
    "/player_api.php",
    "/panel_api.php",
    "/xmltv.php",
    "/enigma2.php",
]

# Common Playwright login panel URLs
LOGIN_PATHS = [
    "/login",
    "/panel",
    "/admin",
    "/client",
    "/user/login",
    "/auth/login",
]

REQUEST_TIMEOUT = 8


def _probe_xtream(domain: str, port: int) -> List[str]:
    """Probe Xtream UI panel with test credentials on a single port (quick)."""
    found = []
    base = f"http://{domain}:{port}"
    session = requests.Session()
    session.headers.update({"User-Agent": "Mozilla/5.0"})

    # Quick panel check — single GET with short timeout
    try:
        resp = session.get(f"{base}/", timeout=4)
        if resp.status_code >= 400:
            return found
    except Exception:
        return found

    # Try only the most common test creds, only get.php (fastest path)
    for username, password in TEST_LINES[:5]:
        url = f"{base}/get.php?username={username}&password={password}&type=m3u_plus&output=ts"
        try:
            resp = session.get(url, timeout=5)
            if resp.status_code == 200 and "#EXTM3U" in resp.text and len(resp.text) > 500:
                found.append(url)
                log.info(f"  Xtream {domain}:{port} — {resp.text.count('#EXTINF:')} ch")
                return found
            elif resp.status_code == 200 and ('"user_info"' in resp.text or '"auth"' in resp.text):
                found.append(url)
                log.info(f"  Xtream {domain}:{port} — auth OK but no M3U")
                return found
        except Exception:
            continue
    return found


def _probe_custom_api(domain: str, port: int) -> List[str]:
    """Probe custom panel APIs that don't follow Xtream UI pattern."""
    found = []
    base = f"http://{domain}:{port}"
    session = requests.Session()
    session.headers.update({"User-Agent": "Mozilla/5.0"})

    for path in ("/playlist.m3u", "/playlist.m3u8", "/get.m3u", "/channels.m3u"):
        url = f"{base}{path}"
        try:
            resp = session.get(url, timeout=4)
            if resp.status_code == 200 and "#EXTM3U" in resp.text and len(resp.text) > 500:
                found.append(url)
                break
        except Exception:
            continue

    return found


def _probe_m3u_direct(domain: str, port: int) -> List[str]:
    """Check if domain serves M3U directly (no auth, no panel)."""
    found = []
    base = f"http://{domain}:{port}"
    session = requests.Session()
    session.headers.update({"User-Agent": "Mozilla/5.0"})

    for path in ("/", "/playlist.m3u", "/playlist.m3u8"):
        url = f"{base}{path}"
        try:
            resp = session.get(url, timeout=4)
            if resp.status_code == 200 and "#EXTM3U" in resp.text and len(resp.text) > 500:
                found.append(url)
                break
        except Exception:
            continue

    return found


def _playwright_scrape(domain: str, port: int) -> List[str]:
    """
    Use Playwright to log into a panel and extract the M3U URL.
    Only runs if playwright is installed and panel is login-protected.
    """
    found = []
    try:
        from playwright.sync_api import sync_playwright
    except ImportError:
        log.debug("Playwright not installed — skipping browser scrape for %s", domain)
        return found

    base = f"http://{domain}:{port}"

    try:
        with sync_playwright() as p:
            browser = p.chromium.launch(headless=True, args=["--no-sandbox"])
            page = browser.new_page()

            # Try each login path
            for login_path in LOGIN_PATHS:
                url = f"{base}{login_path}"
                try:
                    page.goto(url, timeout=15000, wait_until="domcontentloaded")

                    # Try test credentials
                    for username, password in TEST_LINES:
                        try:
                            page.fill("input[name*='user'], input[name*='email'], input[type='text']", username,
                                      timeout=3000)
                            page.fill("input[type='password']", password, timeout=3000)
                            page.click("button[type='submit'], input[type='submit'], button:has-text('Login')",
                                       timeout=3000)
                            time.sleep(2)

                            # Check if login succeeded (no error message, URL changed, or dashboard visible)
                            current_url = page.url
                            if "login" not in current_url.lower() or "error" not in page.content().lower()[:500]:
                                # Extract M3U URL from the page
                                content = page.content()
                                m3u_matches = re.findall(r'https?://[^\s<>"\']+\.m3u[8]?', content)
                                m3u_matches += re.findall(
                                    r'(?:get\.php|player_api\.php)[^\s<>"\']*username=[^\s<>"\']+',
                                    content
                                )
                                for m in m3u_matches:
                                    full_url = m if m.startswith("http") else f"{base}/{m}"
                                    found.append(full_url)
                                    log.info(f"  Playwright {domain}:{port} — extracted {len(m3u_matches)} M3U URLs")
                                break
                        except Exception:
                            continue
                except Exception:
                    continue

            browser.close()
    except Exception as e:
        log.debug(f"Playwright scrape failed for {domain}: {e}")

    return found


def hunt() -> List[str]:
    """Hunt for M3U URLs across known IPTV reseller panels (concurrent, fast)."""
    all_urls = []
    total_panels = len(KNOWN_PANELS)
    log.info(f"Reseller panels: probing {total_panels} panels concurrently...")

    probes = []
    for domain, panel_type, ports in KNOWN_PANELS:
        for port in ports:
            if panel_type == "xtream":
                probes.append((_probe_xtream, domain, port))
            elif panel_type == "custom":
                probes.append((_probe_custom_api, domain, port))
            elif panel_type == "m3u_direct":
                probes.append((_probe_m3u_direct, domain, port))

    from concurrent.futures import ThreadPoolExecutor, as_completed
    with ThreadPoolExecutor(max_workers=20) as pool:
        futures = {pool.submit(fn, domain, port): (domain, port)
                   for fn, domain, port in probes}
        for future in as_completed(futures):
            urls = future.result()
            if urls:
                all_urls.extend(urls)
                domain, port = futures[future]
                log.debug(f"  Found {len(urls)} URL(s) on {domain}:{port}")

    # Deduplicate
    seen = set()
    unique = []
    for u in all_urls:
        if u not in seen:
            seen.add(u)
            unique.append(u)

    if unique:
        log.info(f"Reseller panels total: {len(unique)} unique M3U URLs discovered")
    else:
        log.info("Reseller panels: no valid M3U URLs found (domains may have rotated)")

    return unique


def _playwright_scrape_known_panels() -> List[str]:
    """Try Playwright on panels that didn't respond to API probes."""
    playwright_attempts = []
    for domain, panel_type, ports in KNOWN_PANELS:
        if panel_type == "custom":
            playwright_attempts.append((domain, 80))
            playwright_attempts.append((domain, 443))
    if not playwright_attempts:
        return []

    log.info(f"Reseller panels: trying Playwright on {len(playwright_attempts)} custom panels...")
    found = []
    for domain, port in playwright_attempts:
        urls = _playwright_scrape(domain, port)
        found.extend(urls)
    return found
