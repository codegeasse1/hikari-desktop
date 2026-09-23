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
