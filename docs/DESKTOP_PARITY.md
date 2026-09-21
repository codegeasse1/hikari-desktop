# Bringing hikari-desktop to Android parity

The Android app (`codegeasse1/hikari`) is the reference implementation. This
document records what the desktop app has ported, what is still missing, and the
decisions (and traps) hit along the way.

Nothing here changes the desktop architecture: **JavaFX UI + jpackage/WiX .exe +
bundled mpv + dex2jar for `.cs3`/`.hiki`**.

## What is done

CloudStream `.cs3` on the JVM (dex2jar + `shim/android/**`), Hikari `.hiki`, Stremio
addons, universal scrapers, **SkyStream `.sky`**, **Nuvio providers**,
**Aniyomi `.apk` extensions**, **IPTV m3u/m3u8 playlists**, mpv playback with
`HlsRelay`/`LocalProxy`, the **IPC-driven in-app player window**, the
design system and every main screen, `AppStore` persistence, the updater, the ad
blocker, the WebView fallback resolver, and the download queue with its HLS/MP4
engine.

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

## Stage 2 — JS plugins: SkyStream + Nuvio (done)

Both engines run JavaScript providers through a **synchronous** fetch bridge, so
the host call must happen on the engine's own thread.

**The JS engine is V8 (`com.caoccao.javet:javet` + `javet-v8-windows-x86_64`).**

- **Rhino is NOT usable here.** It is already on the classpath (`libs.rhino`) and
  looks tempting, but it cannot parse an `async function` at all (Rhino's own
  ES2017 compat page: `Error: missing ; before statement`). Every SkyStream and
  nuvio plugin is written with `async/await`, so Rhino fails on the first script —
  and the failure looks like "this extension is broken" rather than "the engine
  cannot run it".
- GraalJS was the other candidate (pure Java, no native extraction for jpackage to
  get wrong) but an *interpreted* GraalJS running cheerio (~450 KB) is far too
  slow; V8's JIT is what makes the nuvio runtime viable.
- `com.hikari.app.js.JsRuntime` wraps javet and deliberately mirrors the dokar
  QuickJS API the Android runtimes were written against — `function(name) { args -> }`,
  `evaluate<T>(js, name, marshall)`, `evaluationTimeoutMillis`, `close()` — so the
  ported runtime files stay near-verbatim copies instead of being rewritten around
  a different engine's idioms. Timeouts are a watchdog thread calling
  `terminateExecution()`; microtasks are drained with `V8Runtime.await(RunTillNoMoreTasks)`
  after each evaluation (V8 in javet has no event loop).
- `com.hikari.app.js.JsSelfTest` is the CI gate for that engine (host function,
  `async`/`await`, microtask drain, and the "resolve a promise from a *later*
  evaluation" pump pattern both runtimes depend on). It runs in the build right
  after `DexJarSelfTest`.
- The asset payload (`nuvio/boot.js`, `cheerio.js`, `crypto-js.js`, `harness.js`,
  `nuvio/patches/*`, `skystream/shim.js`) is copied over **unchanged**; the ported
  Kotlin only swaps the `QuickJs` calls for `JsRuntime` and reads its assets from
  the classpath instead of `assets.open`.
- The Android-only hooks were dropped rather than faked: `CloudflareVerifier`,
  `DohDns`, `StreamProbe.warmAsync` and the Cloudflare branch of the SkyStream
  provider do not exist on the desktop. The CloudStream/Cloudflare challenge path
  is covered by the existing `WebViewFallbackInterceptor` + `FxWebView` resolver.

## Stage 3 — Aniyomi (`.apk` anime extensions) (done)

- The `eu/kanade/tachiyomi/**`, `tachiyomi/core/**` and `mihon/core/**` trees are
  vendored (~55 files), plus `com/hikari/app/aniyomi/` (`AniyomiProvider`,
  `AniyomiExtensionManager`, `AniyomiProviderSync`, `ApkManifest`).
- **`ApkManifest`** is a small AXML reader: the JVM has no
  `PackageManager.getPackageArchiveInfo`, and an extension's whole identity lives
  in the binary `AndroidManifest.xml` (package name, version, the
  `tachiyomi.animeextension` uses-feature, and the `<meta-data>` the loader
  reads). It understands the chunk layout, the UTF-8/UTF-16 string pool and typed
  attribute values, and returns an `android.content.pm.PackageInfo`.
- Dependencies added for this tree: `io.reactivex:rxjava:1.3.8` (tachiyomi's
  interfaces are RxJava 1), `org.nanohttpd:nanohttpd` (extensions that call
  `createHttpServer()`), and `okhttp3:logging-interceptor` (`NetworkHelper`).
- `shim/android/**` grew one file per need: `Bundle`, `Build`, `SharedPreferences`
  (JSON file per name under `~/.hikari/prefs/`), `PackageManager`
  (+`ApplicationInfo`/`FeatureInfo`/`PackageInfo`), `Bitmap`/`BitmapFactory`
  (ImageIO), `CookieManager`, `androidx/core/text/parseAsHtml` (jsoup),
  `androidx/core/content/pm/PackageInfoCompat`, `androidx/compose/runtime/Stable`,
  `androidx/preference/PreferenceScreen` (a placeholder — Hikari never builds an
  Android preference UI, so nothing calls into it), and the `uy.kohesive.injekt`
  container. `HikariApp.init` registers the singletons extensions inject
  (`Application`, `Context`, `Json`, `NetworkHelper`, `JavaScriptEngine`).
- `com/hikari/app/BuildConfig` mirrors the Android app's `versionCode`/`versionName`
  because extensions gate work-arounds on the host version.
- `JavaScriptEngine` (tachiyomi's JS-backed sources) runs on the same `JsRuntime`.

## Stage 4 — IPTV (done)

- `com/hikari/app/iptv/IptvPlaylist.kt` parses the format every IPTV provider
  speaks (attribute order, quotes optional, `#EXTGRP`, bare URL lines, names with
  commas) and `IptvProvider` exposes the playlist as a normal provider: groups
  become catalogs, the global search finds channels, and a channel's stream is
  its own URL. A single pasted `.m3u8` link is one channel.
- Add one from **Extensions → IPTV playlist** (a URL) or **File / URL**
  (a local `.m3u`/`.m3u8` path). Playlists are parsed once with a 30-minute TTL.

## Stage 5 — Player parity (done)

mpv is launched with `--input-ipc-server` (a Windows named pipe, a Unix socket
elsewhere) and driven over its JSON IPC:

- `desktop/player/MpvIpc.kt` — the client: `command`/`post`/`setProperty`/
  `getProperty`/`observe_property`, with one reader thread fanning property
  changes and events out to callbacks.
- `desktop/player/PlayerWindow.kt` — the app's own player **layer** (mounted into
  `AppShell.playerHost`, so it covers the app window instead of opening one),
  laid out like the Android app's player: a loading overlay that stays up until
  mpv reports the file loaded, header badges (elapsed time, quality, server),
  favourite / download / always-on-top / lock actions, a scrubbable timeline with
  real time labels, the transport (±10s, play/pause, previous/next episode,
  volume), and the labelled feature buttons — Speed, **Source** (switch servers
  mid-playback), Quality, Audio, Subtitles, Rotate, Skip Intro, Enhance,
  Fullscreen — plus the file's actual tracks by name and language, what the
  stream really is (format) and why it failed, and keyboard control (space,
  ←/→, ↑/↓, F, L, N/P, Esc).
- **The video renders INSIDE that layer.** mpv is handed a borderless surface
  window the app owns as its `--wid` (`desktop/player/WinShell.kt`, the only raw
  Win32 in the app, reached through JNA); mpv creates its video as a child of it,
  so the plain second window mpv used to open — the "old player" — no longer
  exists. The surface is glued to the video area on move/resize/maximise/fullscreen
  and mpv's child window is re-sized to fill it, so maximising or dragging the app
  keeps the video lined up. It is hidden (not closed) while the loading overlay or
  an explanation is up, because it is a native window that would otherwise cover
  that text. Everything degrades to mpv's own window when the handle cannot be
  obtained (non-Windows, no JNA, no window) — a player that cannot be embedded must
  never be worse than no player.
- The layer is mounted *before* mpv (it owns the surface mpv renders into), so
  clicking Play shows a spinner and what is being opened immediately, closes with
  playback, and reports a failed IPC connection instead of sitting on
  "Connecting…" forever.
- Releases ship the **.exe installer only** — there is no `.zip` app image.
- `DetailScreen` hands the player what only it knows: `next` (advance to the next
  episode, then play its first source) and `position` (throttled history writes,
  so a title resumes where it was left).

## Stage 6 — installs: real third-party plugins + latency (done)

Two problems reported from a released build, both fixed here.

**1. A public repo's plugins failed to load even though the dex converted fine.**
`CastleTvProvider.cs3` / `CineTvProvider.cs3` (CNCVerse repo) reported
`manifest.json has no mainClass` — which was the *wrong* loader's message — and
then `load() threw: NoClassDefFoundError:
androidx/appcompat/app/AppCompatActivity`. The plugin registers its providers
from `load()` and opens its donation dialog first, so the entire extension was
lost.

The cause is the JVM, not the plugin. dex2jar emits class-file version 50 with no
`StackMapTable`, so HotSpot's type-inference verifier must LOAD every class a
method casts to or calls — and those classes are Android's, which a desktop JVM
does not have. `shim/android/**` now carries the stand-ins a plugin UI is built
from (views, widgets, drawables, animations, fragments, AppCompat, spans).
They are inert — a plugin's Android view tree has no window on JavaFX — but real
objects: every setter is accepted, and every factory (`GradientDrawable()`,
`ValueAnimator.ofFloat`) returns an instance so a null cannot NPE the caller.
Their signatures match Android's exactly, including void-vs-builder returns,
because a mismatch is a `NoSuchMethodError` the moment the plugin calls it.

`HikariApp.mainActivity` is now a real `DesktopActivity`, whose inheritance chain
runs `Activity → support-v4 FragmentActivity → support-v7 AppCompatActivity →
androidx.fragment FragmentActivity → androidx.appcompat AppCompatActivity`. One
object therefore satisfies BOTH the modern and the pre-Jetifier spelling of the
cast plugins make, exactly like the Android host does. If a plugin still dies
inside `load()`, `Cs3PluginManager` now keeps whatever providers it had already
registered instead of discarding the extension.

`com.hikari.app.cs3.RealPluginSelfTest` is the CI gate: it downloads those two
plugins, loads them through the full `HikariApp` runtime, and fails the build if
either registers no providers.

**2. Install and repo-add latency (30-40s → ~1-2s).** Measured causes: every
candidate URL was raced four-at-a-time, so the mirror that works waited behind the
blocked ones for a full 20s connect timeout; and each repo *candidate* walked its
own mirror list serially inside the race. Now:

- `Http.FANOUT` (12) puts every mirror in flight at once — the wall-clock cost is
  the fastest host instead of the sum of the dead ones.
- `Http.MirrorMemory` persists (`~/.hikari/cache/mirrors.json`) which hosts
  refuse / time out / break TLS (skipped for ten minutes) and which exact mirror
  URL served each file (tried first next time), so the second install in a session
  — and the first one after a restart — skips the discovery entirely.
- The first pass uses a short-timeout probe client (connect 6s, read 10s): a
  black-holed host costs 6s instead of 20s, and a second pass on the
  full-timeout client still rescues hosts that are merely slow.
- `fetchRepoJson` flattens candidates × mirror variants into ONE race. The stale
  CDN snapshot of a repo races only after the live URLs, so it can never shadow
  them.
- Reinstalling the same build skips the download when the repo's sha256 matches
  the file already on disk (that is what the "signed" badge means).
- `DexJar` already cached the dex→jar translation by path+mtime: measured 4.6s
  cold / 0.45s warm for a 225KB dex, so an install after the first is ~1s of work.

## Still to do

### i18n

- Copy `app/src/main/assets/i18n/*.json` (21 locales) to
  `desktop/src/main/resources/i18n/`.
- Port `i18n/I18n.kt`; add a language picker to Settings; re-render the shell on
  change; right-to-left for `ar`/`he`.
- Wrap the UI strings that already have keys — start with the shell (nav, top bar,
  screen titles, Settings) and work outward. The Extensions screen is the largest
  block of remaining literals.

### Metadata (TMDB, ratings, collections, backup)

Mostly pure HTTP/JSON, so low risk: `TmdbBrowse`, `TmdbMeta`, `TmdbPresets`,
`TmdbSources` → rich catalog rows, cast, ratings and "more like this"; then
`Ratings`, `CollectionsRepository`, `BackupManager` (JSON file export/import through
a `FileChooser`), `StreamCache`, `SourceUrls`, `RedirectAllow`, `NuvioCatalogImport`,
and `net/DohDns` + `DnsWire` + `DnsProviders`. Note that the nuvio port already brought
`TmdbMeta` (the title-normalisation half), `TmdbResolver`, `EpisodeTitles` and
`BangumiMeta` over.

`desktop/player/EnhancePreset`/`SubtitleStyle` (mpv `--vo=gpu-next` plus Anime4K
shaders, `--sub-*` styling) also remain.

## Risks

- **javet ships a native library.** It extracts itself from the platform jar, so
  jpackage only has to carry the jar — but a machine without a working VC++
  runtime cannot load it. `JsRuntime.available` therefore reports the engine as
  unavailable rather than crashing the app; every non-JS extension keeps working.
- **dex2jar has limits**: obfuscated or `invokedynamic`-heavy dex can verify badly
  on the JVM. `DexJarSelfTest` is the place to catch it.
- **Aniyomi extensions assume Android resources** (resources, themes,
  `SharedPreferences`, `androidx.preference`). `shim/android/**` will keep growing;
  keep each shim in its own file. A source that opens its own settings screen is
  not yet usable on the desktop (the screen type exists, the UI does not).
- **Keep the config formats identical to Android** (`repo.json`, scraper JSON,
  `.hiki`, `AppStore` keys) so a user can move between the two apps.
- **CI publishes on every successful `main` build.** While iterating on a large
  batch, compile on a scratch branch with a build-only workflow (no publish) and
  push `main` once.
