# Prompt: Extract Live Channel Sources

## Goal
Generate a working Python script(s) that extracts playable stream URLs (M3U8, MPD, or direct RTMP) from the following free live TV sources. Output must be a unified M3U playlist with valid, tested URLs.

## Sources

| Source | Type | Notes |
|--------|------|-------|
| Pluto TV | Web + API | Has public EPG API, reverse from web app |
| Livehdtv | Web | Sports-heavy, iframe embeds, geo-variable |
| Plex | Web + API | 1k+ channels, needs device auth token |
| Roku Channel | Web + API | 500+ channels, requires session handling |
| Globe TV | Web | 7k international, iframe maze, geo-blocked |
| Xumo Play | Web + API | 300 channels, clean API from web client |
| Tubi | Web + API | 250 live channels, needs token flow |
| NTV | Web | 850+ international, regional geo-locks |
| DaddyLive | Web | Sports/entertainment, domain-rotates often |
| PBS Live TV | Web | US-only, affiliate-dependent streams |
| TheTVApp | Web | 118 channels, simple iframe extraction |
| UfreeTV | Web | 1k+ IPTV-style, mixed source quality |
| Puffer TV | Web | Stanford research, U.S. broadcast nets, rate-limited |
| Distro TV | Web | Global niche channels, per-channel URLs |
| Global Free TV | Web | 5k channels, 100+ countries, no signup |
| TVFreedom | Web | 1600 sports channels, organized layout |
| FreeInterTV | Web | 1500 international channels + top 100 |
| Freely | Web + API | UK-only, BBC/ITV/C4/C5 backed, DRM likely |

## Technical Requirements

### Per-Source Implementation
For each source, determine and implement the approach (in priority order):
1. **API reverse-engineering** — open DevTools, capture XHR, replicate auth/client headers
2. **HTML parsing** — if no API, parse page DOM for iframe/src, video tags, or embedded JSON config
3. **M3U8 discovery** — scan for `.m3u8` URLs in page source or network traffic
4. **Playlist proxying** — if source uses XHR-loaded playlists, proxy or mimic the request chain

### Output Format
Single M3U file with entries like:

```
#EXTM3U
#EXTINF:-1 tvg-id="pluto-101" tvg-name="Pluto TV Movies" tvg-logo="..." group-title="Pluto TV",Pluto TV Movies
http://example.com/pluto/channel-101.m3u8
```

- Deduplicate by URL
- Tag channels by source group
- Include `tvg-id` and `tvg-logo` when available
- Mark dead/unreachable sources as comments (`# DEAD: ...`)

### Handling Strategy per Source Category

**Web apps with APIs** (Pluto, Xumo, Plex, Roku, Tubi):
- Find auth/token endpoint
- Get channel listing from content API
- Extract stream URL (often M3U8 with session token)

**Iframe-based** (Livehdtv, Globe TV, NTV, DaddyLive, TheTVApp, UfreeTV):
- Resolve iframe chain recursively
- Extract final video source
- Implement retry with proxy rotation for geo-blocks

**Educational/research** (Puffer TV):
- Respect robots.txt and rate limits
- Implement polite scraping delays (2–5s)
- Cache responses aggressively

**Geo-restricted** (Freely, PBS, Globe TV):
- Document required region
- Optionally accept proxy list as CLI arg `--proxies proxies.txt`
- Flag geo-locked channels in M3U comments

## Deliverables

1. `channel_scraper.py` — main script, CLI runnable
2. `sources/` — per-source modules (one file per source, clean import)
3. `sources/__init__.py` — registry of all sources
4. `channels.m3u` — output playlist
5. `requirements.txt` — minimal deps (requests, beautifulsoup4, m3u8, lxml)

## Constraints

- No Selenium/Playwright — HTTP requests only, keep lightweight
- Handle timeouts (5s default), retries (3), and connection errors gracefully
- Log progress to stdout, errors to stderr
- Support `--source pluto` to run single source
- Support `--output channels.m3u` for custom output path
- Comments in code explaining *what* each block does, not *how*
- Detect 403/401 and suggest proxy/VPN in log, don't crash

## Example

```
python channel_scraper.py --source pluto,tubi --output my_playlist.m3u
```

Expected output:

```
[INFO] Pluto TV: fetching channel list...
[INFO] Pluto TV: found 287 channels
[INFO] Pluto TV: resolving stream URLs (23.5s)
[INFO] Tubi: fetching channel list...
[INFO] Tubi: found 251 channels
[INFO] Tubi: resolving stream URLs (18.2s)
[INFO] Writing playlist: my_playlist.m3u (538 channels)
```

## Note

Some sources may change their DOM or API structure. The agent should implement a `--test` mode that validates extracted URLs actually stream (checks HTTP 200 + M3U8 header) and reports pass/fail per source.
