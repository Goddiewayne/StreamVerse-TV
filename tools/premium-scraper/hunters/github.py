"""
GitHub hunter — discovers M3U playlists from GitHub repos and gists.

Repos: searches for IPTV/premium-related repos, then crawls their file trees.
Gists: searches for public gists containing .m3u files (ephemeral, DMCA-prone).
"""

import logging
import re
import time
from typing import List

import requests

log = logging.getLogger("hunter.github")

# Optional GitHub token for higher rate limits (5000 req/hr vs 60 req/hr)
_GITHUB_TOKEN: str | None = None


def _github_headers() -> dict:
    headers = {"Accept": "application/vnd.github.v3+json"}
    global _GITHUB_TOKEN
    if _GITHUB_TOKEN is None:
        import os
        _GITHUB_TOKEN = os.environ.get("GITHUB_TOKEN") or None
    if _GITHUB_TOKEN:
        headers["Authorization"] = f"token {_GITHUB_TOKEN}"
    return headers

REPO_QUERIES = [
    "iptv premium playlist",
    "premium channels m3u",
    "dstv m3u playlist",
    "supersport m3u",
    "iptv m3u 2026",
    "free iptv m3u",
    "live tv m3u",
    "hbo m3u playlist",
    "bein sport m3u",
    "sky sport m3u",
    "canal plus m3u",
    "premium iptv list",
]

GIST_QUERIES = [
    "m3u",
    "iptv",
    "playlist.m3u",
    "premium.m3u",
    "dstv",
    "supersport",
]


def _search_repos(query: str, per_page: int = 10) -> List[str]:
    """Search GitHub repos and crawl their trees for M3U files."""
    urls = []
    try:
        resp = requests.get(
            "https://api.github.com/search/repositories",
            params={"q": query, "sort": "updated", "per_page": per_page},
            headers=_github_headers(),
            timeout=10,
        )
        if resp.status_code != 200:
            return urls

        for repo in resp.json().get("items", []):
            full_name = repo["full_name"]
            branch = repo.get("default_branch", "master")

            # Get repo file tree
            tree_resp = requests.get(
                f"https://api.github.com/repos/{full_name}/git/trees/{branch}?recursive=1",
                headers=_github_headers(),
                timeout=10,
            )

            files = []
            if tree_resp.status_code == 200:
                files = [
                    entry["path"]
                    for entry in tree_resp.json().get("tree", [])
                    if entry["type"] == "blob"
                    and (entry["path"].endswith(".m3u") or entry["path"].endswith(".m3u8"))
                    and entry.get("mode") not in ("120000", "160000")
                ]
            elif tree_resp.status_code == 403:
                # Rate limited — probe 3 common paths
                for cp in ("playlist.m3u", "channels.m3u", "index.m3u"):
                    probe_url = f"https://raw.githubusercontent.com/{full_name}/{branch}/{cp}"
                    try:
                        pr = requests.get(probe_url, timeout=5, headers={
                            "User-Agent": "Mozilla/5.0", "Range": "bytes=0-511"})
                        if pr.status_code in (200, 206):
                            files.append(cp)
                    except Exception:
                        pass

            for path in files:
                raw = f"https://raw.githubusercontent.com/{full_name}/{branch}/{path}"
                urls.append(raw)

            if files:
                log.info(f"  Repo {full_name}: {len(files)} playlist(s)")

    except Exception as e:
        log.debug(f"GitHub repo search failed for '{query}': {e}")

    return urls


def _search_gists(query: str, per_page: int = 20) -> List[str]:
    """Search GitHub gists for M3U content (ephemeral, high turnover)."""
    urls = []
    try:
        resp = requests.get(
            "https://api.github.com/search/code",
            params={"q": f"{query} extension:m3u", "per_page": per_page},
            headers=_github_headers(),
            timeout=10,
        )
        # Code search applies to all indexed content; gists are included.
        # If 403 (too many code searches), fall back to gist search endpoint.
        if resp.status_code == 403:
            return _search_gists_fallback(query, per_page)
        if resp.status_code != 200:
            return urls

        for item in resp.json().get("items", []):
            raw = item.get("html_url", "")
            if raw and ("gist" in raw or "raw.githubusercontent" in raw):
                raw_url = raw.replace("github.com", "raw.githubusercontent.com")
                if "/raw/" in raw_url:
                    raw_url = raw_url.replace("/raw/", "/")
                urls.append(raw_url)
    except Exception as e:
        log.debug(f"GitHub gist search failed for '{query}': {e}")

    return urls


def _search_gists_fallback(query: str, per_page: int) -> List[str]:
    """Fallback: search gists directly (less accurate, but avoids code search quota)."""
    urls = []
    try:
        resp = requests.get(
            "https://api.github.com/gists/public",
            params={"per_page": per_page},
            headers=_github_headers(),
            timeout=10,
        )
        if resp.status_code != 200:
            return urls

        for gist in resp.json():
            desc = (gist.get("description") or "").lower()
            if query.lower() not in desc and "m3u" not in desc:
                continue
            for file_info in gist.get("files", {}).values():
                fname = file_info.get("filename", "")
                if fname.endswith(".m3u") or fname.endswith(".m3u8"):
                    urls.append(file_info["raw_url"])
    except Exception as e:
        log.debug(f"GitHub gist fallback failed: {e}")

    return urls


def hunt() -> List[str]:
    """Hunt for M3U URLs across GitHub repos and gists."""
    all_urls = []

    log.info("GitHub: searching repos...")
    total_queries = len(REPO_QUERIES)
    for idx, query in enumerate(REPO_QUERIES, 1):
        all_urls.extend(_search_repos(query))
        log.info(f"  Query [{idx}/{total_queries}]: '{query}' — {len(all_urls)} URLs so far")
        time.sleep(0.5)

    log.info("GitHub: searching gists...")
    for query in GIST_QUERIES:
        all_urls.extend(_search_gists(query))
        time.sleep(0.5)

    seen = set()
    unique = []
    for u in all_urls:
        if u not in seen:
            seen.add(u)
            unique.append(u)

    if unique:
        log.info(f"GitHub total: {len(unique)} unique M3U URLs discovered")

    return unique
