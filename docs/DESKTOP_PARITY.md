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
  controller/keys out of the app's player, and mpv is launched with
  **`--force-window=immediate`** — not `yes` — so its window exists from the
  moment the process starts rather than after the file has finished
  initialising. That distinction is the whole difference between "the app
  adopted the player" and "the app gave up on a stream that was still loading"
  (see Stage 9). If a stream loads and no picture ever
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

## Stage 8 — the certificate trust store (the real reason NO repo loaded), instant reinstalls, player teardown, "play straight away" (done)

Four complaints from a released build.

**1. "Adding any repo shows this" — and the app blamed the network.** The error card
read `No server answered from this network — the TLS handshake is being blocked by
this network … Unacceptable certificate: CN=AAA Certificate Services, O=Comodo CA
Limited …` for every repo, every extension download and every mirror, on a machine
whose browser opened the same URLs fine. It was none of the things it said.

Every HTTP client here is pinned to Conscrypt, and Conscrypt takes its trust anchors
from the JDK's `lib/security/cacerts` snapshot (its `Platform.getDefaultCertKeyStore()`
asks the JDK's PKIX trust manager for its accepted issuers). That snapshot no longer
contains the legacy Comodo/Sectigo **AAA Certificate Services** root — verified against
Temurin 17.0.20 and 21.0.12: both ship USERTrust RSA and the Sectigo R46 root, neither
ships AAA — while GitHub's CDN still serves chains that end at a Sectigo root
cross-signed by AAA. So the app rejected every GitHub-family host with a certificate
error, and the retry ladder turned that one verdict into a minute of "Fetching repo…".

The fix is `Http.trustStore()`: the anchors are assembled explicitly, once per
process, from three sources — the JDK's own store, the **Windows certificate store**
(`Windows-ROOT`: literally what Chrome/Edge trust on that machine, and the reason a
corporate/AV inspection root now works too), and `desktop/src/main/resources/cacerts-extra.pem`,
which ships AAA (provenance and the `curl`/`openssl` recipe are in the file's header).
Nothing is trusted blindly — every anchor comes from a public CA program and
certificate *validation* is unchanged.

Conscrypt's own behaviour is why the file is parsed by hand: `CertificateFactory.generateCertificates`
yields **nothing at all, silently**, for a stream holding text it does not understand,
and that file carries a comment header. The first build of this shipped with
`extra=0` — the extra anchor was never loaded — and CI passed anyway because the
Windows store happened to hold the same root. `TlsTrustSelfTest` now asserts against
the FILE as well as the merged store (`extraAnchorCount()`, `extraAnchorFingerprints()`),
so that failure cannot pass unnoticed again; `Http.trustStoreReport()` prints
`anchors=N (jdk=… windows=… extra=…)` on every run.

Certificate rejections are also now *classified* (`isCertTrustFailure`) and separated
from our own race-cancellation noise, so the failure summary says
`this machine's certificate store doesn't trust the site's CA chain` — a sentence the
user can act on — instead of `(3) Unacceptable certificate … (2) InterruptedException`,
and `humanMessage` puts that reason first.

**2. Repo fetches stop after one honest verdict (and are fast).** The ladder's
`Walk` is now route-keyed (`url|p` through the system proxy, `url|d` direct): a host
that failed for a stack-INDEPENDENT reason is not asked again on the next pass, and
`Walk.hopeless()` ends the ladder outright when every candidate on a direct pass was
rejected by the certificate store — a trust decision does not depend on the TLS
version and cannot be retried away. The windows came down with it
(`REPO_FETCH_DEADLINE_MS` 75s → 30s, origin 12s → 9s, mirror 25s → 15s, later passes
15s/12s, tail rescue 40s → 15s, `DOWNLOAD_BUDGET_MS` 90s → 60s) and `raceDownload`
clamps its window to whatever is left of the budget. Timing is logged
(`net-ladder(repo): served by pass … in Xms`, `net-fetch: gave up on … after Xms`), so
"it took a minute" is now a number in the log rather than an impression. Measured on
CI: a repo manifest in 22–30 ms once a stack is known, and a 404 answered in 4 s
instead of a minute.

**3. Installs and uninstalls are instant when the bytes are already here.** The
published sha256 IS the identity of a build, so `ExtCache` (a capped, LRU-trimmed
folder under `filesDir/cache/extensions`, keyed by that sha256) keeps the verified
bytes of every extension this machine has ever installed. `installPlugin` therefore
downloads nothing when (a) the destination already matches the repo's published hash,
or (b) the cache holds those exact bytes — which covers a reinstall, a repair, a
re-add from another repo, and an add-after-uninstall (uninstall deliberately leaves
the cached copy, since the same build is routinely re-added). `install:` /
`uninstall: <name> in Xms` is logged with the elapsed time, and the downloaded bytes
are cached only *after* `verifyDownload` has passed (zip header + published hash).

**4. "Play" starts the fastest server it can actually reach.** New setting
**Settings → Playback → "Play straight away — pick the fastest working server for me"**
(on by default; `AppStore.playFastest`). With it on, `DetailScreen.playBest` probes up
to `MAX_PROBE_SOURCES` (6) usable sources CONCURRENTLY with `Http.streamLatencyMs`
— a 2-byte ranged GET on a 3-second-timeout client — under a `PROBE_BUDGET_MS` (2.5 s)
budget, and starts the one that answered fastest; every probe is in flight at once, so
the cost is one round-trip, not a sum. Torrents, YouTube ids, browser-only links and
signed single-use URLs are never probed (a probe would burn the token the player
needs) — those keep the provider's order, as does everything when the setting is off.
The player is still handed every source found, so its Source menu can switch servers
mid-playback; the race only decides where playback begins. The outcome is logged
(`play: server race -> a=312ms, b=no answer … winner=…`).

**5. Closing the app no longer leaves the player frozen on screen.** The video is
mpv's own Win32 window, adopted by the app — so it does not die with the app, and
abandoning it left mpv's last frame sitting over the desktop. `WinShell.parkAndHide`
(`ShowWindow(SW_HIDE)` + `SetWindowPos` to `PARKED_X/Y`) is called FIRST in
`PlayerWindow.closeInternal()` and from `PlayerWindow.parkSurface()` (any thread), and
`DesktopPlayer.shutdown()` — park, close IPC, close the window layer, then kill mpv —
runs from `Main`'s `setOnCloseRequest`, from `Application.stop()` and from a JVM
shutdown hook. Nothing on that path touches `Fx` any more (a `Platform.runLater` after
the toolkit is gone throws, and the player would survive). The kill itself is explicit
and waited on: descendants → `destroy()` → 1.5 s → `destroyForcibly()` → 1 s
(`player: mpv pid=… alive=false (stopped in Xms)`). `EmbedSelfTest` now asserts both
halves: `parkAndHide` takes mpv's window off the screen, and the window is GONE once
the process is killed.

## Stage 9 — the certificate wall, the player that stayed black, the episode pager

### Why extension repos would not load (the real cause, after several wrong ones)

Every HTTP client here runs on **Conscrypt**, and Conscrypt's verifier
(`TrustManagerImpl`) builds its paths with `ChainStrengthAnalyzer`, which refuses
**any certificate in the peer's chain whose own signature is md2/md4/md5/SHA-1**.
GitHub's CDN still serves chains that include the legacy Comodo/Sectigo
**AAA Certificate Services** root (itself SHA-1-signed) on some networks, so every
GitHub-family host — every repo, every extension download, every mirror — died with

    Unacceptable certificate: CN=AAA Certificate Services, O=Comodo CA Limited …

Adding AAA to the trust store could never fix that: the refusal happens while the
path is being *built*, before any anchor is consulted. That is why two earlier
attempts (shipping the root, then merging the Windows store) changed nothing.

The fix is to take Conscrypt's verifier out of the path entirely:
`Http.HikariTrustManager` is a plain `X509TrustManager` backed by the JDK's own
PKIX validator (`CertPathValidator` first, then a `CertPathBuilder` repair pass over
the peer's own certificates), and Conscrypt delegates to a supplied manager
(`Platform.checkServerTrusted`), so its SHA-1 rule is not consulted at all. The
anchors are merged from the JDK store, the **Windows** store and the shipped
`cacerts-extra.pem`; a chain that genuinely cannot be verified now says which
certificate and why instead of one friendly sentence for every case.

`TlsTrustSelfTest` section 5 generates a SHA-1-signed certificate with keytool in CI
and asserts the whole story: our verifier is the one in use, an unanchored SHA-1
chain is rejected and classified as a trust failure, `Http.trustCertificates` writes
it into `~/.hikari/extra-trusted.pem`, and the same chain then verifies.

- A certificate failure no longer blacklists the host for the rest of the run, and
  the failure summary names the hosts that were asked.
- Extensions screen, on a repo that will not load: **Try again**, **Diagnose
  network** (a real report — OS/clock, anchor counts, proxy, every compatibility
  pass with its full exception chain, the served chain with signature algorithms
  and SHA-1 flags, whether the anchors hold that issuer — with Copy and Save to
  file) and, for a certificate failure only, **Trust this network's certificate…**
  (per machine, reversible, shows subject/issuer/validity/sha256 first).
- Settings → Updates shows the build identity — version, build time, commit — with
  **Copy build info**, and the sidebar shows it too. CI stamps `desktop/Build.kt`
  on every build (`0.1.<run number>`, UTC time, short sha). Before this, every
  published exe claimed to be "0.1.0", so there was no way to tell which build a
  user was running.

### Why the repos still would not load after the certificate fix

The certificate was one wall; behind it was another, and it had nothing to do with
the network. `Http.fetchRepoJson` accepted only CloudStream/Hikari shapes
(`plugins`, `pluginLists`) and **returned null in silence for everything else**,
and the Extensions screen read every repo through the kind of the box the URL had
been pasted into:

- a **Nuvio** repo (`manifest.json` with a `scrapers` array — `D3adlyRocket/
  All-In-One-Nuvio`, 65 scrapers) was fetched perfectly and then thrown away;
- a **Mihon/Aniyomi** index (`index.json`, a bare JSON ARRAY — `codegeasse1/
  codegeasse-mihon-extension`, 89 entries) failed one step earlier, on
  `JSONObject(text)`;
- and because the only *recorded* failure was then one third-party mirror's
  expired certificate, the user was told his machine's certificate store did not
  trust the site's CA chain — for a repo file that had arrived fine.

Now: every repo shape is accepted (`plugins`, `pluginLists`, `scrapers`, or an
index array), the SHAPE of what comes back decides how it is read — not the box it
was pasted into — and the repo is re-filed under the kind its content names, so
its install path (jar / js / apk) matches as well. Every unusable answer is
recorded as such instead of a silent null, and a mirror's certificate failure can
no longer end the ladder or be reported as the file's own fate: `Walk.hopeless`
now needs the file's OWN host to fail that way, and the mirrors to agree.
`RepoFetchSelfTest` fetches the exact two URLs from the reports and asserts the
shapes, so "this repo will not load" cannot come back unnoticed.

### The player that went black (and the second window)

- `--keep-open=yes` on every mpv launch: a stream that ends keeps its last frame and
  the app keeps its controls, instead of mpv exiting at EOF and `MpvIpc.send`
  failing with "the player stopped reading its command pipe".
- The previous mpv is parked off-screen and **waited for** before the next one starts
  (`DesktopPlayer.killPrevious`), so two video windows cannot be on screen at once.
- If the player process dies under a live layer, `PlayerWindow.onPlayerDied` hands it
  to `DesktopPlayer.recoverFromPlayerDeath`: retry once on a freshly resolved URL,
  otherwise show the reason with Retry — never an unexplained black rectangle.
- If mpv's window cannot be adopted, mpv's own controls are re-enabled and the layer
  says why, rather than leaving a black area under a dead overlay.

### "This machine cannot draw the video inside the app window" — the window was not there yet

A report showed a working mpv in a window of its own, the app's layer carrying the
"cannot draw the video inside the app window" note, and a stream still on
"Loading GeoDailymotion…". Nothing was broken — the app had simply given up.

mpv was launched with `--force-window=yes`. Its manual says what that does:

> The window is created only after initialization … This can be a problem if
> initialization doesn't work perfectly, such as **when opening URLs with bad
> network connection**.

and mpv's own built-in `[network]` profile therefore uses `--force-window=immediate`.
On a slow (or hung) stream, `yes` meant no window for as long as initialisation took,
so `PlayerWindow.attachProcess` spent its whole 10 s budget looking for a window that
did not exist yet, and then announced that this machine could not embed the player —
while mpv was fine and playing. On CI the window always appeared at once because the
embed test plays a **local file**, which initialises instantly. That is why the test
was green and the user's machine was not.

Three things changed, not one:

- **`--force-window=immediate`** on every launch (app and embed test), so the window
  exists from startup and can be adopted before the stream has even answered.
- **The wait is no longer a deadline.** `attachProcess` keeps looking for as long as
  the player process lives (120 ms, then 500 ms, then every 2 s), and a window that
  turns up late is adopted anyway — the note is taken back down
  (`showOwnWindowNote`/`clearNote`) and mpv's own chrome is switched off again, so a
  slow window ends up embedded rather than explained away. The window is searched for
  by process id *with mpv's own `--title` winning outright* — never by title alone,
  which could seize another application's window.
- **The fallback tells the truth and can be diagnosed.** `--no-osc` means mpv has no
  on-screen controller *in the process at all*, so the old
  `script-message osc-visibility auto` did nothing and "its controls are back on" was
  false; the fallback now sets `osc`, `border` and `input-default-bindings` as
  runtime **properties**. And every explanation (and the failure pane) carries
  **Copy player report**: the build, the machine, the exact mpv command line, the app
  window and candidate window handles, the whole timestamped adoption trace with the
  reason each attempt was refused, and the tails of `mpv.log` and
  `hikari-player.log` — written to `~/.hikari/player-report.txt` as well as copied,
  so a failure that only exists on the user's machine can be read instead of guessed
  at. `WinShell.describeWindows` lists what Windows thought that process's windows
  were, which is the question a "no window" report raises.

`EmbedSelfTest` now proves it: it launches mpv on a **TCP server that accepts the
connection and never answers** and asserts the window exists and can be restyled and
placed while the stream is still loading (on CI: window at 32,90 960x540, ~0.2 s
after launch). That assertion fails under `--force-window=yes`, which is the point.

### Adopting the WRONG window: mpv owns more than one

`--force-window=immediate` found a second, older bug that `--force-window=yes` had
been hiding. mpv owns **several windows in one process**, and the one the app was
picking was not always the video window. The embed test's own diagnostic printed it:

    windows of mpv's pid: 1. hwnd=0x40072 visible 960x540 class=mpv  title="HikariEmbedTest"
                          2. hwnd=0x2016a hidden  768x519 class=mpv-smtc title="mpv smtc"
                          3. hwnd=0x501e0 hidden  0x0     class=MSCTFIME UI
                          4. hwnd=0x2017c hidden  0x0     class=IME

`mpv-smtc` is mpv's System-Media-Transport-Controls window. It belongs to the same
process, it is visible (timing-dependently), and it can be larger than the video
window — so "the largest visible window of that process" dressed up the helper and
left the app showing a placed window with **nothing drawn in it**, while mpv painted
the picture in the window nobody had adopted. That is exactly the symptom the pixel
check had been reporting as `NO VIDEO`, and it was there long before this change: the
old, narrower test image (a gradient with a big white centre) made it look like a
flat sampled area instead.

The fix is to stop guessing by size alone. `WinShell.findWindowOf` now takes a
class preference, and the player asks for mpv's **video** window: `MPV_VIDEO_CLASS =
"mpv"`. `HELPER_CLASSES` (`mpv-smtc`, `IME`, `MSCTFIME UI`) are never candidates, at
any step of the search. The full order is: exact title match (mpv's `--title`), then
a window of the preferred class, then the largest visible window, then any window at
all. The process id is always part of the search — a window that merely shares the
episode's title is somebody else's.

`EmbedSelfTest` asserts both halves: that mpv exposes a window of class `mpv`, and
that `findWindowOf` returns *that* window rather than a helper, with the window list
printed either way. With it, the pixel check confirms the picture inside the adopted
window again (`spread=152`, `vo-configured=true`).

- The player layer also refuses to call a window adopted when the **app's own** window
  could not be identified: with no owner there is nothing to glue the video to, and
  "adopted" would only have stripped mpv's chrome and left the picture wherever mpv
  chose to put it. Each refusal is recorded with its reason.

### The episode pager (as asked)

- **30 episodes per page**, a range picker listing the whole season in 30s
  ("1 - 30", "31 - 60", …), **Prev 30** / **Next 30**, and a search box that searches
  the whole season — so episode 187 of 379 is one keystroke away, whatever page is
  showing.
- The picker listens to its **value**, not `onAction`: JavaFX only fires onAction for
  a range the *user* picked, so a range selected by code (the page an episode lives
  on) would silently not load the episodes it names. Selecting an episode jumps to
  its page.
- `UiShotTest` asserts the whole thing on a synthetic 379-episode season (tiles
  1..30, then 121..150 after picking a range, then 151..180 after Next 30, then 187
  after searching) and writes the three PNGs, so the pager cannot regress silently.
  It drives the real `renderEpisodes`/`renderEpisodeGrid` path through
  `DetailScreenTestSeam`, and the repo-error card through a repo that cannot load.

### CI

- New steps: **Stamp the build** (rewrites `desktop/Build.kt`) and **Make a
  SHA-1-signed test certificate** (keytool) for the TLS self-test.
- **The release is pruned after every publish**: the newest three installers are kept
  and the rest deleted. 37 assets / 6.2 GB had piled up on `continuous`.

## Stage 10 — instant installs, and the bars float over the picture (done)

Four things the user reported after 0.1.214, and what each one actually was.

### 1. "Installing an extension takes up to a minute"

Two separate causes, and both had to go — the honest answer is that the wait was
real work plus a real wait, and each needed its own fix.

**The wait: the download ladder spent 45 s on a blocked origin before trying a
single mirror.** `Http.downloadToRobust` raced the *authoritative* hosts first and
the CDN/proxy mirrors only after that wave's window closed — and that window was
`MAIN_RACE_MS = 45_000`. On a network that filters GitHub the origin does not
fail fast, it hangs, so an install sat in wave 1 for 45 s and then raced the
mirrors with what was left of the 60 s budget. That is the one-minute spinner
exactly. The origins now get `ORIGIN_FIRST_MS = 12_000` alone, and the second wave
races origins **and** mirrors with the rest of the budget: a working origin is
unchanged (it wins wave 1 in about a second), a dead one costs 12 s instead of 45,
and a slow-but-working one still finishes — it is in the second wave too.

**The work: dex → JVM translation ran on the visible path of every install.**
Measured on CI: **4.3 s** to translate one 88 KB `.cs3` (an 88 KB archive holds a
~225 KB dex), and it grows with the plugin. Three changes in `DexJar`:

- the cache is keyed on a **sha256 of the content**, not on the path + mtime, so a
  reinstall — the same bytes written to the same file name with a new timestamp —
  skips the translation entirely, and the converted jar is kept in
  `~/.hikari/dexjars` (the temp directory is only the fallback), where a temp
  cleaner will not delete it;
- dex2jar is pointed at a **zip target** (`Dex2jar.to` writes a zip whenever the
  path is not an existing directory), and the classes are streamed from it
  straight into the output jar — no directory of `.class` files, no second walk
  over them;
- the class-file **version is patched at its fixed offset** in the header (minor
  at bytes 4..5, major at 6..7 → 50) instead of an ASM
  `ClassReader`→`ClassWriter` round trip per class, which re-parsed and re-encoded
  every one of them to change two bytes;
- and the translation is serialised **per content**, so an install and the first
  catalog fetch that uses the extension cannot both translate the same dex.

**And the row no longer waits for any of it.** `installPlugin` is two-phase: the
extension is registered from the manifest *inside its own archive*
(`pluginClassName` for a CS3 plugin, `mainClass` — one or a list — for a Hikari
one), which is one zip entry and no class loading at all, so the row flips to
"installed" the moment the bytes are on disk and verified. The real load — the
translation — then runs behind it in `finishSetup`, which reconciles the registered
provider list with what the plugin actually holds (a bundle can register several
providers from one entry point, and only the load knows) and takes the optimistic
registration back with a visible reason if the plugin cannot be loaded after all.

The screen itself stopped rebuilding itself: `pluginRow` used to build a fresh
`HBox` every time any plugin's state changed, and every state change called
`fillPlugins()` (144 rows, each asking the store for its providers) or
`renderAll()`. Rows are now built once and their changing parts (badges, the
action slot, the error line) are rewritten in place by `refreshRow`, and the
seconds a job has been running are shown in that row (`Installing… 7s`) off a
one-second ticker that stops itself when nothing is working.

One related bug fell out of the same report: `providersFor` compared a plugin's
URL literally, but the candidate loop records whichever file it actually fetched
(`.hiki` is swapped for `.jar` on purpose) — so a plugin listed as `x.hiki` kept
answering "Install" after installing it. The spellings now include the
`.hiki`/`.cs3`/`.jar` siblings.

### 2. "The video is cropped while the buttons are showing"

The player's video is **mpv's own window**, glued over the app window, and Windows
draws an owned window above everything its owner paints. That has a consequence
the layout could not work around: a bar drawn by the app's JavaFX scene can never
appear over the picture, so making the bars overlay the video inside the layout
was never possible. What the user saw was the honest consequence — with the bars
up, the video area was smaller than the window, and the picture was inset.

The bars now live in **two small transparent windows of their own**
(`PlayerWindow.overlaysOn`), placed over the picture and put directly above the
video window in the z-order. That last part is subtler than it looks: Win32 has no
"insert above X". `SetWindowPos`'s `hWndInsertAfter` names the window that is to
*precede* (sit above) the one being moved, so handing it the video window puts the
bar **below** the video — which is what the first version did, and what the pixels
said: the bar's area measured as pure video. `WinShell.placeAbove` now looks up the
window immediately above the video and inserts the bar below *that* (falling back
to `HWND_TOP` only when the video is already the topmost window). The video area is
the whole window, so the picture is the same size with the controls up and with
them away: showing the bars covers the bottom of the frame and hiding them
uncovers it, and nothing is ever resized. `WinShell.makeOverlayWindow` dresses each
window as a never-activated tool window — never activated matters, because the
player's own keyboard shortcuts (space, Esc, F) are handled by the app window's
scene, and a click on a floating button that took the focus would silently stop
them working.

Each floating window is sized from the **bar's own** preferred size, not from the
stage's. Asking the stage was the second half of the same bug: `sizeToScene()` does
nothing to a window that is already on screen, so the stage kept whatever size it
was first shown with and the bar was placed *the height of the whole window* —
a translucent sheet over the picture, with every scene-level check still passing
(the video area really was the whole window). The bar's height is now pinned to its
own computed preferred height and clamped to a sane range, and both tests assert the
bar is a short strip at an edge (`UiShotTest`, and `FloatingBarsSelfTest` against
the window's real rectangle).

The bars keep their own translucent, frameless look in that mode
(`.player-floating` in `theme.css`), and the whole thing falls back to the old
in-layout bars on any machine where the video cannot be embedded — the same state
a non-Windows build runs in.

`FloatingBarsSelfTest` (CI) proves it with real mpv and real screen pixels: the
video window's rectangle equals the app window's, the bar's rectangle is at the
window's bottom edge **and is a bar's height** (54px, checked against the window),
the bar window is above the video window in the z-order, the bar's area is
measurably darker than the picture behind it (it is translucent on purpose), that
same area becomes the picture again when the bars are hidden, and the video window
does not move or resize when they come and go.
`UiShotTest` keeps its scene snapshots (with the bars pinned back into the layout,
since a window of its own is not in the scene) and adds a geometry check that the
video area still fills the player layer.

### the control channel, when mpv stops draining its pipe

mpv stops servicing its command pipe while it is busy building a video output: on
the CI runner a single write sat inside `WriteFile` for **eight minutes**, and
`FloatingBarsSelfTest`'s probe shows the same pipe answering a fresh connection in
200 ms once mpv is idle again (it also shows mpv accepts a second client). The
player used to answer that with `fail(...)` — the connection was declared dead and
every control was gone for the rest of the stream. That is the shape of the
reported "pause worked once, then pressing it again did nothing", so `MpvIpc.send`
now treats a slow pipe as a **slow** pipe: the connection is left alone (replies
and property observations keep flowing), the command is queued, the queue is bounded
(newest kept) and drains in order when the pipe moves again — so a `cycle pause`
followed by the `set_property` that fixes its result still ends on the right state.
The channel only gives up when it is actually closed.


### 3. "Pause works, but clicking it again does not resume"

`cycle pause` was posted fire-and-forget, so a dropped or stale command was
invisible — a second click that did nothing looked exactly like a button whose
handler never ran. `PlayerWindow.togglePause` now reads `pause` from mpv, sends the
toggle, re-reads it after 400 ms, and if the value has not moved states the intent
outright (`set_property pause <the opposite>`); a failure says so in the status
chip. Every command the player sends is kept in `MpvIpc.recentLog()` (skipping the
polled `get_property` traffic) and is carried in the player report, so "did the
click even reach mpv?" is answerable from a user's copy of it. Because the fallback
states an absolute value *after* the toggle, the two commands also land on the right
state if they are delivered late (see the pipe note above).

`FloatingBarsSelfTest` checks this against a **controlled control channel**
(`FakeMpvChannel`: a real `MpvIpc` client over a real unix socket, with a real
mpv-style command/reply loop on the other end and a switch for the interesting
failure). It presses the control bar's own play/pause button and asserts the channel
goes playing → paused → playing, and then that a toggle command which is *dropped*
— answered, but with no effect, which is exactly what a lost command looks like from
the player's side — is noticed and recovered from, ending paused. mpv's own pipe is
not usable for this on the CI runner (see the pipe note above), which is why the
channel is a stand-in; the probe that establishes that runs in the same test and
prints its measurement.


### 4. "The player's X closed the whole app"

The player's strip carried the app's own window controls, so the X sat in the
player looking like "close the player" and took the whole app (and the playback)
down. The player now has its own two controls: minimise, and a close that closes
the PLAYER (`requestClose`, the same thing the back arrow and Esc do).

## Stage 11 — the install that could never work, the dead back arrow, and the player's Quality button (done)

### 1. "ALL extensions fail to install" — one line of `mirrorVariants` was the whole cause

This is the one that had been "fixed" for several builds, so the important part is
what it actually was.

The official Hikari repo (`codegeasse1/hikari-extensions`, 142 extensions)
publishes **every extension as a GitHub RELEASE ASSET**:
`https://github.com/codegeasse1/hikari-extensions/releases/download/continuous/<name>.jar`.
`Http.mirrorVariants` only knew how to build mirrors for a **raw file path** — it
parsed the URL with `parseGhTarget` (which matches
`raw.githubusercontent.com/u/r/ref/p` and `github.com/u/r/raw|blob/ref/p` only),
and when that parse failed it returned an **empty list**. A `/releases/download/`
URL matches neither, so every extension in that repo had exactly ONE candidate URL
and **no mirror at all**. On a network where github.com is blocked or TLS-filtered
there was nothing else to try — which is precisely the two messages in the bug
reports: *"Download failed for S1CG — no mirror served the file"* and *"the TLS
handshake is being blocked by this network"*. Not a slow install, not a flaky
mirror: a list of length one.

The fix is that a GitHub URL the repo parser cannot decompose is now handed to the
proxy frontdoors, which are the only mirrors a release asset can have:

- `mirrorVariants` returns `githubFrontdoors(base)` whenever `isGithubUrl(base)`
  (a new host test over `github.com`, `raw.githubusercontent.com`,
  `objects.githubusercontent.com`, `release-assets.githubusercontent.com`,
  `github-releases.githubusercontent.com`, `codeload.github.com`,
  `gist.githubusercontent.com`) instead of the empty list it used to return;
- `githubFrontdoors` is unchanged in shape — `ghfast.top`, `ghproxy.net`,
  `gh-proxy.com`, `ghproxy.cc`, `gh.llkk.cc`, `github.moeyy.xyz`,
  `hub.gitmirror.com`, each handed the **full** URL — but is now generated for
  release assets as well as raw paths;
- release assets are deliberately **not** offered the jsDelivr/githack mirrors
  (those publish branch files only, so they can only ever 404 — offering them
  would just lengthen every race);
- and since a release asset's *authoritative* list is github.com on its own, it
  gets `RELEASE_ORIGIN_FIRST_MS = 4_000` instead of the usual 12 s before the
  frontdoors join the race. Waiting a full origin window on a host with no
  alternative is a pure delay.

Measured on CI, against the live repo (`ExtensionInstallSelfTest`, new):

```
— a release asset: …/hikari-extensions/releases/download/continuous/anime.jar
    origins (1):  github.com/…/anime.jar
    mirrors (7):  ghfast.top/https://github.com/…  ghproxy.net/…  gh-proxy.com/…
                  ghproxy.cc/…  gh.llkk.cc/…  github.moeyy.xyz/…  hub.gitmirror.com/…
  OK   a release asset is its own first candidate
  OK   a release asset has at least 3 mirrors
  OK   a release asset can go through a GitHub frontdoor
  OK   a release asset is NOT offered a CDN mirror that cannot serve it
— the live Hikari repo lists 142 extensions
  OK   no extension in the live repo is left with a single candidate URL
— downloading the Anime extension: ok=true in 402ms, 405736 bytes
  OK   what landed is a real archive (PK header), not an error page
  OK   a real extension arrives in seconds, not minutes
— downloading a Nuvio scraper: 15572 bytes in 215ms
```

### 2. The last resort: the OS's own HTTP stack

There is a class of Windows machine where **nothing** inside the JVM can fetch the
file while the browser fetches it fine — a TLS-inspecting filter, a driver-level
firewall, a certificate the JVM's stack refuses. Every in-JVM pass (`Conscrypt`,
the pinned trust store, the TLS-1.2 rescue, the no-proxy rescue) presents the same
chain to the same trust decision, so no amount of ladder-walking rescues it.

`Http` now has a final phase that runs `curl.exe` (System32 first, then PATH) or
`powershell.exe` — Schannel and the Windows certificate store, i.e. literally the
stack the browser uses, in a **separate process**, so none of this JVM's TLS,
trust store, proxy handling or DNS is involved. It races the same candidate list
(`OS_PARALLELISM = 4`, `OS_WINDOW_MS = 25_000`), rejects an HTML error page the
same way the in-JVM paths do (`looksLikeHtmlFile`), and is bounded by whatever is
left of the 60 s install budget. It is only ever reached after every other pass has
failed, so a healthy network pays nothing for it.

It is also wired into `fetchStringRobust`/`fetchBytesRobust`/`fetchRepoJson`, so a
**repo** that only the OS stack can reach loads too. The CI runner is Windows, so
this is not a claim: `— the OS HTTP client on this machine: C:\Windows\System32\curl.exe`
→ `— OS client fetch of the release asset: ok in 354ms, 405736 bytes`.

### 3. The detail banner's back arrow did nothing

The arrow had a tooltip. A JavaFX tooltip is its own popup window, and a click that
arrives while one is up is spent dismissing the popup instead of pressing the
button — a hazard this repo already documents on `Ui.tooltip`, and precisely a
one-click control that reads as "the back button does not work". Two more things
were load-bearing and are now explicit:

- the arrow carries **no** tooltip (the shell's top bar already spells "Back" out
  above it), has `accessibleText = "Back"`, and `id = "heroBackBtn"`;
- it is added to the banner's `StackPane` **last**, so nothing the banner draws —
  the title block, the poster card, the scrims — can cover it or take its clicks;
- `AppShell.back()` no longer steps back **into** the screen already showing (a
  Detail opened from a Detail made the arrow a visible no-op), and exposes
  `AppShell.current` so a test can tell "it navigated" from "it did nothing".

The UI-shot test now proves it with a **real mouse**: it finds the arrow, reports
the topmost pickable node at its centre and fails if anything covers it, then
dwells 1.4 s (longer than the 900 ms tooltip delay, so a tooltip would be up) and
clicks it with `java.awt.Robot`:

```
UiShotTest: back arrow: visible=true disabled=false centre on screen=Point2D [x = 271.0, y = 95.5]
UiShotTest: clicked the banner's back arrow with the real mouse
UiShotTest: OK   the banner's back arrow navigates back (now desktop.ui.Screen$Home@…)
```

### 4. The player's Quality button

The player had Source, Audio and Subs but no quality picker. It now has a
**Quality** pill between Source and Audio, built from mpv's own `track-list`
(`vid`) — not from the provider's source list, because the track list is what the
file actually contains, which is also where the Android player reads it from. Rows
read `Auto (adaptive)` first, then every video rendition as
`1080p · 1920x1080 · 4.2 Mbps` (resolution first — that is what is being chosen
between — with the bitrate only when the stream declares one). Choosing one sends
`set_property vid <id>`; `Auto` sends `vid auto`. The pill greys out and empties
when the stream publishes no video tracks (nothing to choose between), and it
collapses with Audio/Subs on a narrow window like the rest of the pickers.

`FloatingBarsSelfTest` drives this through the fake channel's real command loop:
`FakeMpvChannel` now answers `track-list` and `vid`, and the test asserts the rows
are `[Auto (adaptive), 1080p …, 720p …]`, that picking row 2 puts
`set_property vid 2` on the wire and the picture's own `vid` becomes `2`, and that
row 0 hands it back to `auto`.

### 5. "Clicking Hikari shows only the Hikari extensions" — the engine filter

The Android app's picker has a chip row (`All | Aniyomi | CloudStream | Hikari | …`).
The desktop Installed list now has the same control, built from the engines that
are actually **installed** (so the chips are exactly the engines in use), with
`All` first. A universal scraper is labelled `Scraper` rather than `Hikari` — it is
a different kind of thing, and two chips with the same name would be a worse filter
than none. Selecting a chip narrows the list and only the list: rebuilding the
whole page on every chip click is what would drop the chip row mid-click.

The test registers four fake providers across three engines, asserts the chips, and
fires the Hikari chip:

```
UiShotTest: engine chips = All | CloudStream | Hikari | Nuvio | Stremio
UiShotTest: OK   the engine chips are on screen without scrolling
UiShotTest: with the Hikari chip on, test rows = [ZZ Test Hikari One, ZZ Test Hikari Two]
UiShotTest: OK   picking Hikari leaves only the Hikari extensions
```

**And the Installed list moved to the top of the page.** The chips worked from the
first build of them, passed their test, and were still unfindable: the Installed
section sat at the *bottom* of the Extensions page, below the composer and every
repo card, so on a machine with a handful of repos the whole feature was below the
fold. The screenshot taken for the CI run showed the composer's repo-kind chips
(`Hikari repo | CloudStream repo | …`) and no sign of the new row — which is what
"it works but nobody can find it" looks like. The Installed section (heading,
filter field, engine chips, rows) now comes directly under the page header, above
the composer and the repo list, exactly as the Android picker has the chips above
the list. The test asserts the chips are inside their `ScrollPane`'s own viewport
(`inScrollViewport`) rather than merely present in the scene graph, because that is
the difference that made this invisible.

### 6. "Everything instant" — the two that were still waiting on the network

- **Uninstall / reload / toggle** no longer call `renderAll()`. Those actions
  rewrite the store and the file on the spot and repaint *only* the installed list
  and the header; the provider-list refresh (which re-instantiates the extensions
  that are left) runs behind them. Removing a row used to rebuild the whole
  screen — every plugin row, every repo card — before the row disappeared.
- **Opening a title** no longer waits for `metaFor` before it paints. The catalog's
  own `MediaItem` already carries the title, year, genres, overview and artwork, so
  the banner, the title block and the Synopsis/Details panels are drawn **before any
  network call**, in the same frame as the click; `metaFor` then only *enriches*
  what is already on screen, and the Episodes section drops in when the season
  lands. The sources panel shows its own `Fetching sources…` spinner, which is the
  one thing that genuinely has to wait.

## Stage 12 — the second install failure, the provider sheet on Home, and the Play button that was never clickable (done)

### 1. "Neither extension will install" — a jsDelivr URL still had a mirror list of one

Stage 11 taught `mirrorVariants` about release assets. The same class of hole was
left open one step over: an extension URL that is served **from a CDN**.

The All-in-One-Nuvio repo (`D3adlyRocket/All-in-One-Nuvio`, since renamed to
`NuvioPlugin/All-in-One-Nuvio`) serves its `manifest.json` from whichever mirror
answers first, and its entries carry **relative** paths (`providers/allanime.js`).
That is fine on paper — except the relative path was joined onto the *winning
mirror's* base, so in practice the plugin URL came out as
`cdn.jsdelivr.net/gh/…@main/providers/allanime.js`. `parseGhTarget` did not know a
jsDelivr URL, so `mirrorVariants` again returned a list of exactly ONE candidate —
and on a network where jsDelivr is blocked, "Download failed — check the URL".

- `parseGhTarget` now decomposes **every published form of a GitHub file**: raw,
  raw-path, `github.com/…/raw|blob/`, jsDelivr (`cdn.`, `fastly.`, `gcore.`,
  `testingcf.jsdelivr.net`), jsDelivr's `bcdn` form, and githack. They all reduce
  to `{owner, repo, ref, path}`, so they all yield the same family.
- `Http.canonicalGithubFileUrl(url)` is the one public entry point for "what is the
  authoritative URL for this file": it maps any of those forms to
  `https://raw.githubusercontent.com/<owner>/<repo>/<ref>/<path>`.
- `originVariants` puts the **canonical raw** URL first when the caller's URL was a
  CDN/frontdoor form (the CDN copy is the one that can be stale or blocked on the
  user's network), and keeps the caller's URL first otherwise, so nothing that
  worked before changed order.
- `mirrorVariants` returns the whole family (minus the base itself) instead of
  whichever single form happened to be recognised.
- `ExtensionsScreen` resolves a manifest entry's relative `filename` against
  `Http.canonicalGithubFileUrl(manifest.url).substringBeforeLast('/')`, so the
  plugin URL is built on raw.githubusercontent.com **no matter which mirror served
  the manifest** — which is the actual bug the user was hitting.
- `ExtensionInstallSelfTest` §1 now walks a jsDelivr URL, a githack URL and a
  frontdoor-wrapped URL as well as the raw ones, and §3b walks the real
  All-in-One-Nuvio repo end to end: manifest served → 65 scrapers → canonical raw
  plugin URL → that plugin's bytes downloaded (19 281 B) → 12 mirrors for the
  jsDelivr form.

### 2. The provider picker on Home — the Android provider sheet, in the desktop toolbar

The catalog's source used to be chosen from a plain combo box holding one flat,
alphabetical list of every enabled provider. With six engines and a hundred
providers that is a scroller you cannot search, and it decides which catalog the
whole home page is built from.

`desktop/ui/ProviderPicker.kt` is the Android sheet: a filter field, **one chip per
engine that actually has providers** (`All | CloudStream | Hikari | Nuvio |
Stremio | …`), and the list, with `All providers` as the first row and an engine
badge on every row. It replaces the combo box in `HomeScreen`'s toolbar and calls
back on every pick, so the page reloads from the new provider. Details that matter:

- it is a `Popup` — a window with a scene of its own — so its stylesheets are
  copied from the main scene at show time (the sheet is drawn entirely in
  looked-up colours);
- `setProviders` runs on every load, keeps the user's choice, and drops a selection
  whose provider was just uninstalled;
- chips are built for engines that have providers *right now*, so a chip can never
  filter to an empty list.
- `UiShotTest` drives it through the picker's own hooks (a `Popup` is not reachable
  through the app's scene graph): it opens the sheet, requires `All` + one chip per
  engine, requires `All providers` as the first row, presses the `Hikari` chip and
  requires only Hikari rows, picks a row and requires the button text and the
  **catalog source** to change, then requires that pick to survive its own reload.

### 3. The Play button: an invisible, stretched box was lying over the whole banner

Reported three times now ("the Play button is unclickable", and before that "the
back arrow is dead"). Both are the same failure: in JavaFX **paint order is click
priority**, and a container that draws nothing still takes the click.

`buildHero` put the "Add to library" cluster in
`topRight = VBox(8.0, favouriteButton)` with `StackPane.setAlignment(topRight,
TOP_RIGHT)`. A StackPane resizes a child up to the child's **max** size, a VBox's
max size is unbounded, and nothing said otherwise — so that little container was
stretched over the **entire banner**. It was added after the title block, which put
it above the Play button in the paint order, and it has no background, so it drew
nothing at all: the screenshot looked perfect while every click landed on it instead
of on Play, Download, or anything else under it. The test now names it:

```
UiShotTest: over the Play button, topmost first:
UiShotTest:     Button[id=heroPlayBtn cls=button.btn-play] scene=(264,324 104x43) …
```

The fix, all of it defensive:

- `topRight` is sized to its own content (`maxWidth`/`maxHeight =
  `Region.USE_PREF_SIZE`) — a container is never allowed to be bigger than what it
  holds;
- the artwork (`heroImage`) and the poster card (`heroPosterFrame`) are
  `isMouseTransparent` — decoration must never be a pick target on a banner with
  buttons on it;
- `heroBody` (the title/meta/chips/**Play + Download** block) is added LAST, just
  before the back arrow, so nothing but the arrow can be painted over it.

`UiShotTest` now prints **every node covering the button's centre, topmost first**
(with class, id, style classes, scene bounds and the picking flags) so this can
never be guessed at again; it fails if the topmost node is not the button or a
child of it, then clicks it with a real `java.awt.Robot` press/release and requires
`DetailScreenTestSeam.playPresses >= 1`. A screenshot cannot tell "the button is
there" from "the button is there but dead" — this can.

### 4. The player's bar: the seek bar's budget is measured, not assumed

"the player button are not have full text also the time in player video time not
showing full" — the pickers read `Qua…`, `Au…`, `Sub…`, the clock `0…`.

`applyResponsive` sized the seek bar from a hard-coded `width - 480.0`, and that 480
was written when the bar had **three** pickers. Adding the Quality pill silently
took ~90 px out of the row, so the seek bar kept its full length and the *pickers*
were laid out narrower than their own labels, which JavaFX draws with an ellipsis.

- the budget is now **measured**: every visible, managed control on the bar (skipping
  the seek bar) contributes its `prefWidth(-1)`, plus the row's spacing, and the seek
  bar gets `width - need - 10` clamped to `[90, 620]`. Anything added to the bar from
  now on is accounted for automatically;
- the source/quality/audio/subtitle menus and the two time labels carry
  `minWidth = Region.USE_PREF_SIZE`: a pill whose text no longer fits is worse than
  a shorter seek bar, because "Qua…" is indistinguishable from a different label;
- below 620 px the **total**-time label drops out (the elapsed time and the seek bar
  still say where playback is) rather than squeezing everything else.

`UiShotTest.squeezedBarControls` compares each control's laid-out width with its
computed preferred width on the wide bar *and* on the 460 px one — a control's
`text` is unchanged by truncation, so measuring is the only way to catch this from a
test.

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
