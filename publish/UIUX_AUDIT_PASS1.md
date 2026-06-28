# StreamVerse TV — UI/UX, Performance & Experience Audit (Pass 1)

_Captain Global Technologies · scope: Android Mobile (Compose) + Android TV / Fire TV (Leanback) + shared `core`._

This is the first pass of an iterative refinement effort. The codebase is ~13k LOC across 3 modules
(`app` 25 files, `core` 41, `tv` 20) and is already a competent Netflix-style product with a coherent
dark "cyber" palette, a full Material-3 type scale, edge-to-edge windows, shimmer skeletons, and a
Leanback browse experience. The findings below are about lifting it from _good_ to _flagship_.

Honest scope note: this pass deeply reviewed the mobile design system, Home, and the shared
`ChannelCard` / `ChannelLogo` / `Shimmer` components, plus the TV `TVBrowseFragment`. Player, Search,
Settings, Favorites, CategoryDetail, Schedule, PiP, and the remaining TV activities (playback/search/
settings) are catalogued for a later pass and were **not** changed.

---

## ✅ Implemented in Pass 1 (mobile — compiles clean, `:app:compileDebugKotlin` BUILD SUCCESSFUL)

| # | Area | Problem | Fix | File |
|---|------|---------|-----|------|
| 1 | **Performance** | Alphabetical bucketing (O(26·N)) and per-section category/country filtering ran **inside the `LazyColumn` content lambda** — recomputed on every recomposition and scroll frame. | Hoisted into `remember(state.channels)` groupings (`buildLetterRows`, `groupBy { category }`, `groupBy { country }`); section lookups are now O(1) map reads. | `ui/home/HomeScreen.kt` |
| 2 | **Interaction** | Hero billboard auto-advanced every 6s even **while the user was dragging** the pager, and animated even with a single page. | Auto-advance now pauses while `pagerState.isScrollInProgress` and only runs when `channels.size > 1`. | `ui/home/HomeScreen.kt` |
| 3 | **Accessibility** | Home search affordance had a 36dp touch target (below the 48dp Material/WCAG minimum). | Raised the `IconButton` to 48dp; glyph stays 22dp. | `ui/home/HomeScreen.kt` |
| 4 | **Perceived quality** | Channel logos **hard-popped** in over the gradient/monogram on load. | Added a 220ms Coil `crossfade` via `ImageRequest` so logos fade in. | `ui/components/ChannelLogo.kt` |
| 5 | **Performance / polish** | Each `ShimmerBox` created its **own** `rememberInfiniteTransition` — ~11 independent animations on Home that could drift out of phase. | Single shared shimmer transition passed down to all placeholders. | `ui/components/Shimmer.kt` |
| 6 | **Design system** | A full `LightColorScheme` was defined but **unreachable** — every screen hard-codes black/white, so the app is always dark. Inconsistent & misleading. | Committed to a single intentional dark theme; removed the dead light scheme and the `darkTheme` branch (updated the one call site). | `ui/theme/Theme.kt`, `MainActivity.kt` |
| 7 | **TV — top fix** | Favouriting (a) rebuilt every row → **DPAD focus dropped to the top**, and (b) was **unreachable on a remote** (heart not DPAD-focusable; center plays). | Decoupled favourites from the rebuild + in-place heart re-bind (focus preserved); added **long-press-to-favourite** with a Toast. **Verified on the AOSP TV emulator.** | `tv/ui/browse/TVBrowseFragment.kt`, `tv/ui/browse/TVChannelPresenter.kt` |

---

## 📺 TV (Leanback)

### TV-1 · Favoriting rebuilt the **entire** browse grid + was unreachable by remote  ✅ FIXED & VERIFIED ON-DEVICE
Two compounding problems:
1. `TVBrowseFragment` fed `favoritesRepository.getAllFavoriteIds()` into the same `combine{}` that called
   `populateRows()` → `rowsAdapter.clear()` + full rebuild on every toggle → **focus jumped to the top,
   scroll reset**.
2. The heart was a non-DPAD-reachable `TextView`; on a remote the center key plays the channel, so
   **favouriting was effectively impossible on a TV remote** (touch-only). Verified on the Android TV
   emulator via `uiautomator dump`: DPAD `RIGHT` moves card→card and never lands on the heart.

**Fixes (both verified on the AOSP TV emulator):**
- Decoupled favourites from the rebuild; hearts now re-bind **in place** via `refreshFavoriteHearts()` /
  `notifyArrayItemRangeChanged` — DPAD focus and scroll position are preserved.
- Added **long-press the focused card** to toggle favourite (standard Android-TV pattern) with a Toast
  confirmation; short-press still plays. The heart is now `isFocusable = false` (driven by long-press on
  remote, tap on touch).

**On-device test results:** long-press on "Comedy Central" → heart fills red + "♥ Added to Favourites"
toast, **focus stayed on the card, no grid rebuild**. Long-press again → heart empties + "Removed from
Favourites", focus preserved. Short-press → launches `TVPlaybackActivity` (channel plays). ✅

**Remaining tradeoff (by design):** the "My Favourites" membership row repopulates on the next natural
rebuild (sort/data change), not the instant a favourite is added — the heart fill is immediate. Tell me
if you'd prefer live row insertion (slightly more focus-management work).

Other TV notes (lower priority): SharedPreferences history read on the main thread inside `populateRows`
(`:357-368`); `title` is overwritten with the focused channel's name on every DPAD selection (`:89-91`) —
confirm that's the intended "now focused" affordance; `ListRowPresenter(ZOOM_FACTOR_NONE)` disables row
focus zoom — verify focus remains unmistakable at 10 feet.

---

## 🎨 Recommended next (mobile — deeper / needs a product decision)

1. **No brand typeface.** `Type.kt` has a strong weight/size scale but uses the platform default
   `FontFamily`. A single display/heading font would noticeably raise perceived quality.
3. **More sub-48dp targets:** the "See All" text and sort chips; the sort-chip `Row` is fixed (not
   scrollable) and can clip on narrow screens or at large font scale.
4. **`ChannelCard`:** favorite button is a 28dp target; the card has no merged semantics
   `contentDescription`, so TalkBack reads it fragmented (quality badge + name + button separately).
5. **`ChannelLogo` monogram** font is a fixed 28sp regardless of tile size — too small in the hero, too
   big in 140dp cards. Scale it to the box.

---

> Note: `window.statusBarColor` / `navigationBarColor` are deprecated on SDK 35+ (pre-existing compile
> warnings). Fold into `WindowCompat` insets styling during the design-system pass.

---

## TV browse & playback polish — verified on the AOSP TV emulator

| # | Area | Change | File |
|---|------|--------|------|
| T1 | **Channel-box focus** | Focus was a subtle 1.08× scale with a faint white ring. Now **1.16× scale + bright cyan glow ring + raised Z** so the focused tile pops forward above its neighbours — unmistakable from 10 ft. | `TVChannelPresenter.kt` |
| T2 | **Billboard "Watch Now"** | Plain white pill that *dimmed to grey* on focus (backwards). Now a **`▶ Watch Now`** pill that brightens and gains a **cyan accent ring + lift** when the hero is focused — reads as the active CTA. | `TVBillboardView.kt` |
| T3 | **Sort / control card** | Verbose "Tap to cycle (…)" box. Now a clean **"Sorted by Category"** control with a sub-line and a **`›` chevron**, cyan focus border, and accent-tinted highlight. | `TVSettingsCardPresenter.kt`, `TVBrowseFragment.kt` |
| T4 | **DSTV-style channel guide** | **NEW.** DPAD **→** during playback slides in a right-side channel panel over the live video: the **full catalogue** (932 ch) with logos, names & categories; **▲/▼** moves the highlight, **OK** tunes, **←/BACK** closes; auto-hides after 8 s. Current channel is highlighted & scrolled into view. | `TVPlaybackActivity.kt` |

**On-device results:** channel focus pops clearly (cyan ring + scale verified); the billboard CTA shows
the play glyph + cyan ring on focus; the Sort card renders cleanly with chevron; the channel guide opens
on **→**, lists all channels with logos over the playing video, **▲/▼** moves the highlight, **OK** closes
the guide and tunes, **←/BACK** closes. Input mapping: **←** = source chooser (unchanged), **→** = guide.

> Note on testing: the emulator can't reach most live IPTV/DLHD streams (they buffer/fail), which made the
> player churn during automated runs. Highlight-on-current-channel was confirmed by **code analysis** —
> `getCachedChannels()` and the browse list share the same `phase1` ids, so `indexOfFirst { it.id ==
> channel.id }` matches whenever a channel is actually playing (it falls back to the top of the list only
> while the channel is still resolving). Worth a sanity check on a real device with working streams.

> Observed (pre-existing, out of scope): a permanently-dead stream is retried indefinitely; on the
> emulator this eventually let the system kill the process. Worth a retry cap / "give up & surface" path.

---

## TV refinements round 2 — verified on the AOSP TV emulator

| # | Request | Implementation | Files |
|---|---------|----------------|-------|
| R1 | Watch Now button too long (reached the dots); want a round pill above a centered carousel | Watch Now is now a **compact wrap-content round pill**; the dots carousel is **centered** and the **button sits above it**. | `TVBillboardView.kt` |
| R2 | Strip bracketed resolution from channel names — (360p) (720p) (1080i) … | New `ChannelNameFormatter.stripResolution()` (regex `(\d{3,4}[pi])` + HD/FHD/UHD/4K/SD/HDR), broadened in `format()` and applied to the IPTV / FreeTV / radio / independent / Stmify build paths that previously skipped it. **647 tagged playlist names** now strip. | `ChannelNameFormatter.kt`, `ChannelRepository.kt` |
| R3 | Side channel list ordered by the home-page sort | Guide rebuilds on open and is ordered by the persisted home sort (Category groups → A–Z → Region). Sort is now persisted by `TVBrowseFragment` and read by the player. | `TVPlaybackActivity.kt`, `TVBrowseFragment.kt` |
| R4 | When the billboard is focused, move to next/prev featured channel | **◄/►** step through featured channels while the hero is focused; auto-cycle pauses on focus; **◄** at the first item still escapes to the header rail. | `TVBillboardView.kt` |
| R5 | TV source toggle → refresh immediately, strictly by preference, fast | Toggling now relies on the reactive `enabledFlow` filter (instant). **Disabling does no network work**; only **enabling** triggers a fetch. List is strictly `hasEnabledSource`-filtered. | `TVSettingsFragment.kt` |
| R6 | Channel list available while playing OR loading | **→** always opens the guide now (removed the source-bar branch), so it's reachable in every playback state. | `TVPlaybackActivity.kt` |
| R7 | Highlighted current channel: visible shadow + a bit bigger/expanded | The selected guide row now gets **elevation (shadow) + 1.04× scale + a cyan ring**; the list no longer clips the raised row. | `TVPlaybackActivity.kt` |

**On-device results:** billboard shows the compact pill above the centered dots; **◄/►** cycled
BFM TV France → Comedy Central (dots + name + logo updated, hero kept focus); the guide opened over
**live video** ordered by Category (Comedy → Documentary groups) with the playing channel highlighted &
expanded. Name-stripping verified by build + regex against 647 real `(NNNp)` playlist entries (IPTV source
doesn't load on the emulator, so not visually confirmed there). Source-toggle speed verified by code: the
`channels` flow already re-filters on `enabledFlow` instantly; only enable now fetches.

---

## Search availability & speed — verified on both emulators

**Problem:** searching some channels returned nothing. Root cause: the search index was built **only in
phase 2** (the network fetch of IPTV/FreeTV/Radio/FAST/Independent), and only **after every** extended
source finished. So premium channels weren't in the fast index early, and any not-yet-fetched source was
unsearchable.

| Fix | What it does | File |
|-----|--------------|------|
| **Phase-1 instant index** | Builds the search index from the premium catalogue the moment phase 1 finishes (~2–4 s) instead of waiting for phase 2. | `ChannelRepository.kt` |
| **Early IPTV index** | Indexes the bulk IPTV set (10k+) the instant it lands, without waiting for the slower radio/fast/independent fetches (skipped when a warm cache already serves search). | `ChannelRepository.kt` |
| **Browsable union** | `searchChannels` now always also scans the loaded home set, so a channel that's loaded is **never** missing from results regardless of index freshness. | `ChannelRepository.kt` |
| **Warm-cache reuse** | The searchable index persists to disk, so every launch after the first is searchable **instantly**. (Pre-existing; now protected from being shrunk by the early-IPTV step.) | `ChannelRepository.kt` |
| **Snappier debounce** | Search debounce 300 ms → **180 ms** on mobile and TV. | `SearchViewModel.kt`, `TVSearchFragment.kt` |

**On-device results:** right after launch, mobile "news" returns BBC News, CNews France, ESPNews, FOX
News, Headline News, NOVA Sports News…; TV "news" returns Newsmax, Newstalk, News 24… Net effect: every
loaded channel is searchable immediately, and each source becomes searchable the moment it loads (premium
instantly, IPTV as soon as fetched, everything instantly on repeat launches).

> The one unavoidable latency is the **first-ever** network fetch of the 10k+ extended catalogue; after
> that it's disk-cached and instant. A further win would be starting phase 2's fetches in parallel with
> phase 1 (currently sequential) — deferred as it needs careful handling of the merge base.

---

## Pass plan (remaining)

- **TV depth:** verify TV-1 on-device (focus + heart reachability); audit `TVPlaybackActivity`, TV
  search/settings fragments, splash; resolve the heart-focusability question.
- **Mobile depth:** Player, Search, Settings, Favorites, CategoryDetail, Schedule, PiP.
- **Design system:** brand font, tokenize spacing/radius, finish touch-target + TalkBack semantics sweep,
  migrate deprecated window APIs.

_Verification: all changes compile (`:app` + `:tv` assembleDebug → BUILD SUCCESSFUL) and were
**installed and exercised on live emulators** — phone (`emulator-5554`) and Android TV (`emulator-5556`).
The TV-1 favourite flow (add/remove/play/focus-retention) was confirmed on-device via DPAD input +
`uiautomator` focus inspection + screenshots._
