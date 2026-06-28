# Prompt: Free Premium Channel Sources (Self-Healing)

**Goal:** A Python system that continuously discovers, validates, and maintains working free premium channel sources (HBO, Showtime, Starz, Cinemax, ESPN, NFL, etc.) from underground aggregators.

## Philosophy

"Forever" doesn't exist with premium sources. The correct approach is a **self-healing pipeline** — not a static list of links, but a system that hunts fresh sources daily and prunes dead ones automatically.

## Source Types (by resilience, high to low)

1. **Discord servers** — Private shares communities, invite-only but discoverable via disboard.org
2. **Telegram channels** — Automated bots posting M3U links, less likely to die than single servers
3. **GitHub scrapers** — Repos that scrape and aggregate; they fork when DMCA'd, so track the fork tree
4. **IPTV aggregator sites** — Sites that repost M3U playlists daily (domain-rotate, use regex)
5. **Plex/Jellyfin share directories** — Forum threads, subreddits, Discord bots listing shares
6. **Reseller panel leaks** — Temporary trial accounts from reseller panels, short-lived but highest quality

## Technical Architecture

### Discovery Layer
- **Discord scraper**: Use self-bot approach (undocumented API) to search disboard.org for IPTV servers, join, collect #free-trial and #m3u channels
- **Telegram scraper**: Telethon client to search @iptv_channels, collect posted M3U URLs
- **GitHub watcher**: Track repos with keywords (iptv, m3u, playlist, free-tv), monitor forks for new active repos
- **Web crawler**: Recurring scrape of known aggregator sites (domain-fuzzy match for rotation)

### Validation Layer
For each discovered playlist URL:
1. HTTP HEAD check (200, non-zero size)
2. Parse M3U, extract unique channel URLs
3. Probe first 5 channel URLs — must return HLS/MPEG-DASH headers
4. Validate channel names against premium keyword list (HBO, Showtime, etc.)
5. Score source: (valid channels / total channels) * quality_bonus

### Aggregation Layer
- Merge all validated sources into clean M3U
- Deduplicate by URL
- Tag premium channels with group-title
- Generate EPG XML if provider has EPG endpoint
- Output: `premium_channels.m3u` + `epg.xml`

### Health Layer
```python
# Cron: every 6 hours
- Re-validate all source URLs
- Prune dead sources (2 consecutive failures = removed)
- Re-run discovery layer for fresh sources
- Git-commit changes so you have rollback history
```

## CLI Usage

```
python premium_scraper.py               # Run full pipeline
python premium_scraper.py --discover    # Discovery layer only
python premium_scraper.py --validate    # Re-validate existing sources
python premium_scraper.py --watch       # Continuous mode, check every 6h
```

## Deliverables

1. `premium_scraper.py` — main orchestrator
2. `discovery/` — one module per source type (discord.py, telegram.py, github.py, web.py)
3. `validation/` — stream validator, M3U parser, channel classifier
4. `config.yml` — keyword lists, webhook URLs, Discord tokens, Telegram API creds
5. `requirements.txt` — python-telegram, discord.py-self, requests, beautifulsoup4, m3u8, pyyaml
6. `channels.m3u` — output playlist
7. `epg.xml` — optional EPG data if discovered

## Critical Constraints

- **No scraping of premium services directly** — only aggregators who repost links. The agent hunts *where the links live*, not the services themselves
- Handle rate limiting gracefully — Discord/Telegram will ban aggressive bots
- All tokens and creds go in `config.yml`, never hardcoded
- Log everything to `premium_scraper.log` with rotation
- Support `--dry-run` flag that logs what it *would* do without executing
- Code must handle encoding issues — these playlists frequently have fucked UTF-8

## What Premium Means

Channels matching these patterns (configurable in config.yml):
- HBO, HBO2, HBO Family, HBO Signature, HBO Zone, HBO Comedy
- Showtime, Showtime Extreme, Showtime Beyond, Showtime Showcase
- Starz, Starz Cinema, Starz Comedy, Starz Edge, Starz Encore, Starz In Black, Starz Kids
- Cinemax, Cinemax MoreMax, Cinemax ActionMax, Cinemax ThrillerMax
- NFL Network, NFL RedZone, ESPN, ESPN2, ESPNU, ESPN Deportes
- Sky Sports, Sky Cinema, Sky Atlantic, Sky Witness
- BT Sport 1/2/3/ESPN, Premier Sports
- DAZN, UFC Fight Pass, WWE Network
- Disney+, Netflix, Apple TV+ (rare but appear on high-end shares)

## Example Output

```
$ python premium_scraper.py --discover --dry-run
[DISCOVER] Discord: found 12 servers matching "iptv"
[DISCOVER] Discord: joined 8, scanning #free-trial channels...
[DISCOVER] Telegram: found 4 channels posting M3U playlists
[DISCOVER] GitHub: tracked 3 active repo forks
[DISCOVER] Web: found 2 aggregator sites, 1 responsive
[INFO] Dry-run mode, no changes written
[INFO] Would have discovered ~34 unique source URLs
```

## The Hard Truth

Some premium channels on free sources buffer constantly. The validation layer should flag sources with >50% failure rate as "unstable" rather than dead, so you can choose to keep or discard. Good sources are worth hunting daily because they die at 3 AM and a fresh one pops up by noon.
