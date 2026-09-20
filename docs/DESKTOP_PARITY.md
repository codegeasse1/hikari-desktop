# Bringing hikari-desktop to Android parity

The Android app (`codegeasse1/hikari`) is the reference implementation. This
document is the port plan for everything still missing from the desktop app, in
the order it should be done, with the file-by-file work each step needs.

Nothing here changes the desktop architecture: **JavaFX UI + jpackage/WiX .exe +
bundled mpv + dex2jar for `.cs3`/`.hiki`**. The hard machinery already exists; what
is missing is breadth.

## What is already done

CloudStream `.cs3` on the JVM (dex2jar + `shim/android/**`), Hikari `.hiki`, Stremio
addons, universal scrapers, mpv playback with `HlsRelay`/`LocalProxy`, the design
system and every main screen, `AppStore` persistence, the updater, the ad blocker,
the WebView fallback resolver, and the download queue with its HLS/MP4 engine.

A layout/input pass went over every screen afterwards: screens now fit the window
(minimum sizes are pinned, see `UI_GUIDE.md` rules 8–11), the detail screen's
left column scrolls while the watch panel keeps its own scrolling source list,
the episode range pager was replaced by one grid plus a filter, and the tooltips
that were eating clicks are gone.

## Stage 1 — Downloads (done)

Ported from Android's `download/` package into
`desktop/src/main/kotlin/com/hikari/app/download/`:

| Android | Desktop | Change |
|---|---|---|
| `DownloadModels.kt` | `DownloadModels.kt` | none — identical JSON, so a queue moves between the two apps |
| `DownloadStore.kt` | `DownloadStore.kt` | DataStore → `downloads.json` (temp-file + rename, so a crash can't truncate it) |
| `DownloadEngine.kt` | `DownloadEngine.kt` | `MediaExtractor`/`MediaMuxer` → `desktop.player.Mpv.remux` (stream copy); `MediaStore`/`Environment` → the user's `Downloads/Hikari` folder |
| `DownloadsRepository.kt` | `DownloadsRepository.kt` | `Context` → `HikariApp.instance.filesDir`; the foreground `DownloadService` → an in-process pump on its own scope |
| `DownloadService.kt` | (none) | a desktop window *is* the app, so the queue needs no service; `onTaskFinished` raises a toast through `AppShell` |
| `PlayerHttp.client` | `DownloadHttp.kt` | own OkHttp client with a 16-requests-per-host budget (a download fans out over many connections) |
| `DownloadsScreen.kt` | `DownloadsScreen.kt` | live queue: per-task progress/speed/ETA, pause/resume/remove, play offline, reveal the exported copy |

Decisions worth remembering:

- **mpv is the muxer.** A stream whose audio arrives as a separate HLS rendition
  needs its video-only and audio-only parts merged. mpv's encode mode with
  `--ovc=copy --oac=copy` does exactly that, with no re-encode, using a binary the
  app already ships. If it fails, the export falls back to copying the whole local
  bundle into a named folder, so nothing the user waited for is lost.
- **An EXPORT download keeps its local bundle**, unlike Android (which deletes the
  work dir after exporting). The bundle is what makes the download instantly
  playable inside the app; the exported file is the copy the user can take away.
- **`downloadToFolder` (default on)** decides whether a new download also writes a
  single playable file into `Downloads/Hikari`. Off keeps downloads inside the app.

## Stage 2 — i18n

- Copy `app/src/main/assets/i18n/*.json` (21 locales) to
  `desktop/src/main/resources/i18n/`.
- Port `i18n/I18n.kt`; add a language picker to Settings; re-render the shell on
  change; right-to-left for `ar`/`he`.
- Wrap the UI strings that already have keys — start with the shell (nav, top bar,
  screen titles, Settings) and work outward.

## Stage 3 — Metadata (TMDB, ratings, collections, backup)

Mostly pure HTTP/JSON, so low risk: `TmdbBrowse`, `TmdbMeta`, `TmdbPresets`,
`TmdbSources` → rich catalog rows, cast, ratings and "more like this"; then
`Ratings`, `CollectionsRepository`, `BackupManager` (JSON file export/import through
a `FileChooser`), `StreamCache`, `SourceUrls`, `RedirectAllow`,
`NuvioCatalogImport`, `IptvPlaylist` + `IptvProvider` (m3u channels — Android has
live TV, the desktop app has none), and `net/DohDns` + `DnsWire` + `DnsProviders`.

## Stage 4 — SkyStream + Nuvio (needs one spike first)

Both run JavaScript providers through **QuickJS with a synchronous fetch bridge**
(`com.dokar.quickjs`), chosen on Android precisely because the host calls must be
synchronous. Do **not** move this onto JavaFX WebView: that forces an async bridge
and a rewrite of `NuvioScraper`/`SkyStreamProvider`.

**Spike (do this before porting anything else here):** get one real provider
returning streams under a JVM JS engine. Candidates:

- `app.cash.quickjs:quickjs-jvm` — closest API to the Android library, pure JNI.
- `com.caoccao.javet:javet` — V8, also supports synchronous host calls, faster,
  larger download.

Wrap whichever wins behind a thin `JsEngine` interface (evaluate, set global,
bind host function, timeouts) so the binding can be swapped. Then the asset payload
(`nuvio/boot.js`, `cheerio.js`, `crypto-js.js`, `harness.js`, `nuvio/patches/*`,
`skystream/shim.js`) copies over **unchanged** — only the ~10 Kotlin files that
touch the `QuickJs` API need swapping. Port the timeouts, the `setTimeout` drain
loop and `NuvioCryptoBridge` with them.

## Stage 5 — Aniyomi (`.apk` anime extensions)

Rides on the same dex path as `.cs3`:

- Extend `DexJar` to accept an APK: unzip, translate `classes.dex`, read
  `AndroidManifest.xml` for the `tachiyomi.animeextension` meta-data and
  `extVersionCode` (Android's `AniyomiExtensionManager` already does this
  validation and ports over).
- Port the EuKanade/Tachiyomi shim tree (`eu/kanade/tachiyomi/**`,
  `tachiyomi/core/**`, `mihon/core/**`) — ~55 files, mostly plain Kotlin
  (OkHttp + jsoup). The Android-bound pieces are `AndroidCookieJar` (→ an OkHttp
  cookie jar), `JavaScriptEngine` and any `WebViewResolver` user (→
  `desktop/web/FxWebView.kt`), `ChildFirstPathClassLoader` (→ child-first loader
  over the dex loader), `AppInfo` and the preference screens.
- Add the `jsoup` and `io.reactivex:rxjava:1.3.8` dependencies (both plain Java).
- Grow `shim/android/**` one file at a time, each with a comment naming the
  extension that needed it, and keep extending `DexJarSelfTest` with real plugins.

## Stage 6 — Player parity (mpv IPC)

Launch mpv with `--input-ipc-server=\\.\pipe\hikari-mpv` and drive
`loadfile` / `set_property` / `get_property` from JavaFX. That unlocks a real
custom OSD (timeline, tracks, next episode, accurate errors), `EnhancePreset` via
`--vo=gpu-next` plus Anime4K shaders, and `SubtitleStyle` via `--sub-*`.

## Risks

- **The JVM JS engine is the only true unknown.** Do the Stage 4 spike before
  committing to SkyStream/Nuvio.
- **dex2jar has limits**: obfuscated or `invokedynamic`-heavy dex can verify badly
  on the JVM. `DexJarSelfTest` is the place to catch it.
- **Aniyomi extensions assume Android resources** (resources, themes,
  `SharedPreferences`). Budget for a growing `shim/android/**`, and keep each shim
  in its own file.
- **Keep the config formats identical to Android** (`repo.json`, scraper JSON,
  `.hiki`, `AppStore` keys) so a user can move between the two apps.
