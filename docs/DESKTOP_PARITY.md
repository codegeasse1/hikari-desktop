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
`HlsRelay`/`LocalProxy`, the **IPC-driven player transport dialog**, the
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
  `AppShell.playerHost`, so the video is part of the app window instead of a
  second window): a **top strip** — Back, title, a status chip that shows only
  while there is something to say, and the window's minimise/maximise/close
  cluster — over the picture, and ONE **control bar** under it: play/pause,
  elapsed time, a scrubbable seek, total time, **Source** (every server the title
  offered, switchable mid-playback, labelled with the one playing), the file's own
  audio and subtitle tracks by name and language, "Next episode" and fullscreen.
  Keyboard: space, ←/→, ↑/↓, F, N, Esc.
- **The video renders INSIDE the app.** The app *adopts* mpv's own window:
  `desktop/player/WinShell.kt` (the only raw Win32 in the app, reached through
  JNA) finds the window by the mpv process id, strips its caption/frame, makes it
  an owned, non-activating tool window (so it takes no taskbar slot, stays above
  the app, and the app keeps keyboard focus and its shortcuts) and glues it
  exactly over the video area. mpv keeps rendering into its OWN window, which is
  the whole point: the video output is created by mpv, for mpv, so it always comes
  up — the previous approach handed mpv a window JavaFX owned (and once shrank it
  to 2×2 while loading), and on real machines that produced "sound but no
  picture", after which the app reopened the stream in a second, visible window.
  While a spinner or an explanation covers the video area the adopted window is
  *parked* far off-screen at full size (never hidden, never shrunk, neither of
  which mpv's video output survives), and it is re-glued on
  move/resize/maximise/fullscreen. `--no-config --no-border --no-osc
  --no-input-default-bindings` keep the user's own mpv.conf and mpv's own
  controller/keys out of the app's player. If a stream loads and no picture ever
  arrives, the player says so over the video area with Retry / Open in browser /
  Close actions — it never spawns a second window behind the user's back.
- **IPC writes never block a caller.** A named-pipe write blocks when the
  other end stops draining it, and the callers are the player's controls (some on
  the JavaFX thread), so `MpvIpc` queues commands and writes them from one writer
  thread. A wedged pipe now costs the commands, never the UI; a write that has not
  returned in 10s marks the connection dead. `close()` closes the transport from a
  **daemon thread** for the same reason — closing a stream with an in-flight write
  blocks too (this was not theoretical: on CI a single `get_property` sat inside
  `WriteFile` and `close()` inside `RandomAccessFile`, hanging the step for eight
  minutes).
- **The watchdog never blames the stream for a player it could not talk to.** On a
  machine whose GPU falls back to software, mpv spends the first seconds of a
  stream compiling shaders and answers no IPC at all. If nothing has been answered
  `PlayerWindow.setStatus("Still opening the stream…")` says so and the failure
  dialog is withheld — only a player that HAS answered and still shows no picture
  is reported as a failed stream.
- **The bars fit the window they are in** (`PlayerWindow.applyResponsive`): below
  780px the pickers drop their labels, below 560px the audio/subtitle pickers and
  the separators go away, below 620px the total-time label goes, and the seek bar's
  length is what is left of the row after the other controls (capped at 620px,
  floored at 90px) so it can never reach the pickers. Before that, a 460px-wide
  window squeezed the seek bar to a dot and pushed the fullscreen button off the
  right edge; on a maximised window the growing seek bar ran into the Source
  button. See Stage 7 for the bars appearing and disappearing
  (`pokeChrome`/`applyChrome`).
- **The player's UI is verified by looking at it**, not by arguing:
  `desktop/uitest/UiShotTest.kt` renders the real screens and the real player layer
  (loading, failure, wide, phone-width) to PNGs on every CI run and uploads them as
  the `ui-shots` artifact.
- Releases ship the **.exe installer only** — there is no `.zip` app image.
- `DetailScreen` hands the player what only it knows: `next` (advance to the next
  episode, then play its first source), `position` (throttled history writes, so a
  title resumes where it was left) and `sources`/`onPickSource` (every source the
  episode offered, so the player's **Source** button can swap servers without
  going back to the detail screen).

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

## Stage 7 — the bars come and go, and repos stop re-fetching themselves (done)

Three complaints from a released build.

**1. The player's controls covered the picture permanently.** The bars are now
shown, not always there: a mouse move (or click, or key press) brings the top
strip and the control bar back and starts a **two-second** countdown; when it
expires they go, and their height goes back to the video area, so the picture
grows into the space. They stay up while paused, while a stream is loading, and
while an explanation is on screen, and `PauseTransition` is restarted by every
new activity (`PlayerWindow.pokeChrome` / `applyChrome`).

The hard half is *noticing* the mouse. The picture is mpv's own Win32 window glued
over the video area, so it consumes the mouse messages for its whole rectangle and
JavaFX never sees a move over it. `WinShell.cursorPos()` (`GetCursorPos`) and
`WinShell.leftButtonDown()` (`GetAsyncKeyState`) are polled from the timer the
layer already runs every 120 ms for the video surface; a move — or a click while
the bars are hidden — inside the app's window counts as activity. No hook is
installed, and `WinShellSelfTest` proves both bindings answer on CI (a silently
broken one would leave the bars unreachable over the picture). The pointer is
hidden (`Cursor.NONE`) while the bars are away; mpv hides its own over the video.

**2. The seek bar ran the width of the window and into the pickers.** Two causes,
and the second is the one that actually drew the line.

The layout cause: the seek bar used to be the growing child of the control bar, so
it stretched from the time label to the Source button — on a maximised window the
two were in each other's lap. The row is now: transport
(`play · time · seek · total`) on the left, a flexible gap, then the pickers and
window buttons on the right. The seek bar's length is what is left of the row after
the controls that must always be there, capped at 620 px and floored at 90 px
(`applyResponsive`), so it can never reach the pickers at any window size; below
620 px the total-time label gives way as well. Clicking or dragging the bar moves
the readout with the pointer and posts ONE seek on release
(`seekTo`/`previewScrub`).

The drawing cause: the timeline still ran edge to edge. JavaFX's `SliderSkin`
widens its `.track` node by the CSS `-fx-background-radius` on EACH side
(`track.resizeRelocate(trackStart - trackRadius, …, trackLength + trackRadius +
trackRadius, …)`) so that a pill-shaped track keeps its rounded ends flush — and
`theme.css` gave every slider a `-fx-background-radius: 999` "pill". Every slider
in the app therefore painted ~1000 px of extra track on each side: measured on CI,
a 620 px scrubber had a **2604 px** track node, i.e. a hairline from the window's
left edge to its right edge, straight under the Source/Audio/Subs pills. The fix is
a radius of half the track's height (3 px for the player's 6 px track, 8 for the
15 px thumb), which rounds the ends identically and widens the node by 3 px.
`UiShotTest` now measures `layoutBounds` of the seek bar and of its `.track` and
**fails the build** if the track is wider than its slider, so this cannot come back
unnoticed. (`boundsInParent` is useless for this check: it is the union with the
children's bounds, so it reports the overflow as if it were the control's own.)

**3. "Why is it fetching the repo again?"** The Extensions screen refreshed every
repo on a six-hour TTL, so a repo added days ago — every one of its extensions
installed — could still put `Fetching repo… (racing 16 mirrors)` on screen for no
reason the user could see. Now a repo that already has contents is only fetched
when the user asks (`Reload this repo`, `Reload all`, `Try again`); the background
pass is left to repos that have NEVER loaded, and not more than once per
`FAILED_RETRY_MS` (15 min). Opening a repo refreshes quietly (no shell activity
chip) and never restarts the mirror race for a repo that just failed. A repo whose
manifest cannot be fetched now lists the extensions already installed from it, says
they keep working, and its error reads as a sentence
(`No server answered from this network — the TLS handshake is being blocked by
this network (4); DNS lookup failed (4). Extensions you already installed keep
working. Try again in a minute, or switch network/VPN…`) rather than a bare
network-stack summary. The mirror list dropped three hosts that had stopped
answering (`cdn.statically.io`, `raw.gitmirror.com`, `github.moeyy.xyz`) and gained
two more jsDelivr edges (`testingcf.`, `jsdelivr.b-cdn.net`), and each stack on the
compatibility ladder now gets an EQUAL share of the fetch deadline instead of the
first one helping itself to half of it — a first pass that ate the whole budget is
how a repo reported "unreachable" while the next stack would have loaded it.

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

## Repo adds (done)

Adding a repository is visible on the spot and works from ANY box. The store entry
— and therefore the repo's card — is written BEFORE any network work, so the repo
appears immediately even when the manifest fetch afterwards races mirrors for half
a minute or fails outright; the fetch then fills in the real name, description and
plugin list, and an unreachable repo keeps its card with an `unreachable` badge
instead of vanishing.

The "Add a source" box also checks the pasted URL once and, when its body really
is a repo manifest, adds it as a REPO with the right engine — Nuvio `scrapers` →
NUVIO, CloudStream `plugins`/`pluginLists` (or a `.sky` plugin list) →
CS3/SKYSTREAM. That mix-up is what made pasting e.g. an `All-in-One-Nuvio`
`manifest.json` into the Scraper box report "Scraper added" while the repo
appeared nowhere.

## Repos that "couldn't be reached" — the network compatibility ladder (done)

A user's repo came back as `unreachable` with
`Read error: Failure in SSL library, usually a protocol error` — for one repo,
while other things in the app loaded fine. That message is the network talking,
not the site: it is the same failure mode as the earlier "install latency" work
(blocked hosts, a leftover OS proxy), except that only the *download* paths had a
rescue stack and the repo/manifest paths had none.

`Http` now owns one compatibility ladder and every fetch walks it
(`fetchRepoJson`, `fetchStringRobust`, `fetchBytesRobust`, `getStringStrict`):

1. the normal stack (Conscrypt TLS, DNS-over-HTTPS, the OS proxy),
2. the same with **TLS pinned to 1.2** — some Windows machines/networks fail
   every Conscrypt TLS 1.3 handshake with exactly the error above,
3. the same with the **OS proxy bypassed** (an uninstalled VPN/Clash/Psiphon
   entry left in the system proxy settings makes every JVM request fail while the
   browser works).

The pass that worked is remembered in `~/.hikari/cache/net.json`, so the second
launch (and every request after the first) starts on the stack this machine can
actually use. A TLS-library failure is never blamed on the host — it would
otherwise blacklist every GitHub host for ten minutes — and a failed repo fetch
is retried in two minutes rather than after the six-hour cache TTL, so a repo
that failed on the network comes back by itself.

**Authoritative URLs are raced before any mirror**, and a mirror is only allowed to
answer once they have failed (`originVariants` / `mirrorVariants`). Racing them
together was wrong in two separate ways, both of which shipped and both of which
were caught by the self-tests:

- a CDN copy of a *branch* file can be days stale (jsDelivr caches a branch ref),
  so "the newest extension is missing from my repo" and "this extension installs
  but will not load" are the same bug — an old `.cs3` with no `manifest.json`;
- a proxy frontdoor can answer **HTTP 200 with a 122-byte error page**, and being
  tiny it wins every race (`gh-proxy.net`, now removed). Every "robust" fetch
  rejects a body that is really a web page (`Http.isWebPage`, `looksLikeHtmlFile`
  for downloads), because a race has no other way to tell a fast wrong answer from
  a right one.

Both were found by `RealPluginSelfTest`, which downloads two real third-party
`.cs3` files and loads them — the reason that test exists at all.

The mirror list also grew (`fastly.`/`gcore.jsdelivr.net` — three independent
jsDelivr edges — plus `gh-proxy.com`, `ghproxy.cc`, `gh.llkk.cc`,
`github.moeyy.xyz`, `raw.gitmirror.com`). DoH now queries several providers **in
parallel** and always includes IP-literal endpoints (`1.1.1.1`, `8.8.8.8`,
`9.9.9.9:5053`) — the only kind that can rescue
a machine whose OS resolver is filtered, since a named endpoint has to be
resolved by the very resolver being worked around. When everything still fails,
the card shows a count of the real causes ("the TLS handshake is being blocked by
this network (8)") instead of whichever attempt happened to finish last.

`NetworkSelfTest` (CI) proves the ladder offline: it starts a local HTTP server,
installs a *dead* proxy, and requires the body to still arrive — and the
no-proxy pass to be remembered.

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
