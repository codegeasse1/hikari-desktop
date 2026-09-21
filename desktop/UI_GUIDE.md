# Hikari Desktop — UI layer guide

Read this before touching anything under `desktop/src/main/kotlin/desktop/ui/`.

## Layout of the UI layer

| File | Role |
|---|---|
| `theme.css` | The design system. Layer 1 = design tokens as JavaFX looked-up colours on `.root`; layer 2 = every control styled from those tokens. |
| `Theme.kt` | Owns the two things that *select* a theme (light/dark, accent preset) and exposes the spacing/type scales. |
| `Icons.kt` | The whole icon set, built from JavaFX primitives on a 24x24 grid. |
| `Components.kt` (`Ui`) | Buttons, chips, fields, badges, section headers, empty states, skeletons, segmented control, rail-with-arrows, toasts. |
| `PosterCard.kt` | The poster card + `PosterRail` (headed horizontal rail with "See all"). |
| `PosterGrid.kt` | Virtualised poster grid (a `ListView` with one cell per row). |
| `AppShell.kt` | Sidebar + top bar (title, global search, window controls) + content host + toasts + back stack + shortcuts. |
| `HomeScreen.kt` | Featured hero banner + one rail per catalog. |
| `SearchScreen.kt` | Query + scope bar + slide-in provider drawer (scrollable multi-select). |
| `DetailScreen.kt` | Banner + facts column + fixed "Watch" panel (episode grid + sources). |
| `CatalogScreen.kt` | The full grid behind a rail's "See all". |
| `LibraryScreen.kt`, `DownloadsScreen.kt`, `SettingsScreen.kt`, `ExtensionsScreen.kt` | The remaining screens. |
| `WindowChrome.kt` | Resize edges, window controls, drag-to-maximise. |
| `desktop/player/PlayerWindow.kt` | The in-app player layer: loading overlay, video surface, transport and Source picker, driven by mpv's JSON IPC. |
| `desktop/player/WinShell.kt` | The raw Win32 calls (via JNA) that embed mpv's video in that window. |
| `desktop/player/DesktopPlayer.kt` | Launches/drives mpv, the HLS relay and the stream fallbacks. |

Outside `desktop/ui/`, but part of this layer's picture: the download queue lives in
`com/hikari/app/download/` — `DownloadModels` (task/status + JSON), `DownloadStore`
(`downloads.json`), `DownloadHttp` (the engine's per-host OkHttp budget),
`DownloadEngine` (HLS/MP4 fetch) and `DownloadsRepository` (the queue, a
`StateFlow` the Downloads screen renders).

## Rules

1. **Never hardcode a colour.** Add it to layer 1 of `theme.css` and reference the
   token. A colour literal outside layer 1 will not follow the light theme.
2. **Never hardcode spacing.** Use `Theme.S1`–`Theme.S6` (4/8/12/16/22/28) and the
   `Theme.POSTER_*` constants, so grids and rails line up across screens.
3. **Build screens from `Ui.*`** rather than hand-styling `Button`/`Label`. If a
   component is missing, add it to `Components.kt` — not inline in a screen.
4. **Icons come from `Icons.of(name, size)`.** Colour is applied by CSS
   (`.h-icon` filled, `.h-icon-out` stroked), so icons re-theme automatically.
   Add new ones as `shapes(name)` branches; unknown names render a placeholder
   square instead of throwing.
5. **Text**: `Theme.label(text, size, bold, dim)` when a one-off size is needed, or
   a plain `Label(text).apply { styleClass.add("<css class>") }` when the size is
   part of the design system (`.hero-title`, `.d-title`, `.watch-title`, `.tiny`, …).
6. **Never put `-fx-*` on a property you animate.** CSS (author stylesheet) wins
   over a programmatic value the next time a node's styles are re-resolved — so
   anything animated (`opacity` of the rail arrows, `translateX` of the drawer,
   `opacity` of the hero image) must be set in Kotlin only, never in `theme.css`.
7. **Re-parenting is fine, re-adding is not.** Nodes are moved between parents when
   a screen re-renders; a `TextField` inside a rebuilt container loses focus, so
   *filter fields rebuild only the list they filter*, never the whole screen.
8. **A region must not decide how small the window can be.** JavaFX lays a child out
   at `max(min, min(available, max))` — so a big *minimum* is what pushes content off
   the window. Two defaults conspire here: a `VBox`'s minimum height is the SUM of
   its children's minima, and an `HBox`'s minimum width is the sum of its children's,
   so a page of rails reports a minimum as large as its whole content and none of it
   scrolls (the scroll pane ends up as big as its content, so there is nothing to
   scroll). Therefore: run every screen root and every container between a screen
   root and a scroll pane through `Ui.fill(...)`, and keep every scroll pane's
   `minWidth`/`minHeight` at 0. `AppShell`'s `centerStack` is pinned to 0/MAX and is
   the only thing that decides the window's minimum size. The same applies
   sideways: `Ui.vScroll` zeroes its content's `minWidth`, and any `Label` that can
   hold long text (a hero title, a status line, a repo name) sets `minWidth = 0`
   and wraps or ellipsizes — an unwrapped `Label`'s minimum IS its whole text
   width, which is what made the home page pan left/right.
9. **Only one long-lived animation in the whole app.** Skeletons share a single
   timeline through `Ui`'s shimmer property. A per-node INDEFINITE `Timeline` (the
   old `Ui.pulse`) is never stopped when the node leaves the scene, and enough of
   them saturate the FX pulse — which users experience as clicks that need several
   attempts. Stop screen-owned animations (e.g. the home hero's auto-rotate) when
   their node leaves the scene.
10. **Tooltips only on small, deliberate targets.** A JavaFX tooltip is its own popup
    window positioned under the pointer; a click that lands on it dismisses the popup
    instead of reaching the control, which is why a wall of tooltipped posters feels
    like it needs three clicks. Poster cards and episode tiles carry no tooltip (their
    labels are visible), and `Ui.tooltip` uses a deliberately long 900ms delay.
11. **Controls inside a clickable container need `Ui.isolateClicks`.** A poster's
    "More" button, the hero's arrows/dots and a source row's buttons all live inside a
    container that opens something — without it, one click does both.

## Screen patterns

- **Hero banner** (`HomeScreen`): a `StackPane` with a clipped rounded rectangle,
  the image bound to the stack width, two scrim layers (vertical + horizontal
  gradients), a bottom-left content column and a bottom-right dot/arrow navigator.
  Auto-rotates every 10s and pauses while hovered. The featured titles come from
  the *selected* provider (the hero is re-seeded whenever the provider selector
  changes) and the overline names that provider.
- **Player** (`desktop/player/PlayerWindow.kt` + `WinShell.kt`): an in-app LAYER,
  not a window — it is mounted into `AppShell.playerHost` (a full-window
  `StackPane` above the body, below the resize edges), so starting a stream reads
  as the app switching to a player view. It carries a loading overlay that stays
  up (with mpv's surface hidden) until mpv reports the file loaded, the transport
  bar, and a **Source** menu for switching servers/qualities mid-playback. mpv is
  given a borderless surface window the app owns as its `--wid`, so there is no
  second, plain mpv window either; the surface is kept glued to the video area by
  `syncSurface` and mpv's child window is kept filling it (`WinShell`, JNA).
  Non-Windows, or no handle, degrades to mpv's own window (the layer then keeps
  an explanatory note over the video area).
- **Rails** (`Ui.rail` + `Ui.railWithArrows`): the vertical mouse wheel is mapped
  to horizontal movement and only consumed while the rail can still move, so the
  page keeps scrolling normally when the rail is at its end.
- **Filter drawer** (`SearchScreen`): a full-height panel inside the screen's
  `StackPane`, `translateX` animated in/out, a dimming `Region` behind it, rows as
  `HBox`es with a check square. Multi-select; an empty selection means "all".
- **Detail** (`DetailScreen`): the banner owns the top — 42% of the window,
  clamped to 300–430px, because it now carries a 2:3 **poster card on the right**
  (sized from the banner width, hidden below ~720px) beside the title, metadata,
  genre chips and Play/Download actions on the left, over a cover-cropped backdrop
  with a right-hand scrim behind the poster.
  Under it an `HBox` of a *scrolling* facts+episodes column (grow) and a fixed
  396px `Watch` panel. Nothing competes for the same axis: the left column owns
  the page's vertical scroll and holds the synopsis, the facts table and the full
  episode grid; the watch panel never leaves the screen and instead scrolls
  *inside* itself, with the source list as its one growing child. Episode tiles
  show the number only (the show title is stripped from the name) and every
  episode is rendered in one wrapping grid — no pager — with a
  "Find episode by number or name…" filter and a "Show more episodes" button
  (240 tiles per page).
- **Downloads** (`DownloadsScreen`): a behaviour panel (storage tiles, where exported
  copies land, the "also save a copy to my Downloads folder" switch, downloads-at-once
  segmented control) above a live queue. The queue rows are rebuilt from the
  `DownloadsRepository.tasks` `StateFlow`, rendered inside `Fx.run { }`, and are
  skipped entirely while the screen is detached (`attached`), because progress
  emissions arrive several times a second.

## Adding a screen

1. Create `XScreen.kt` in `desktop/ui/` with `class XScreenView { val root: Region; fun onShown() {} }`.
2. Wrap content in `Ui.vScroll(body)`; assemble it from `Ui.sectionHeader`,
   `Ui.panel`, `Ui.emptyState`, `PosterGrid`/`PosterRail`.
3. Add an entry to the `Screen` sealed class, to `viewFor`/`titleFor`/`subtitleFor`,
   and (if it belongs in the sidebar) to `sidebar()` + `refreshNav()` in `AppShell`.
4. Do async work on `AppShell.uiScope` and hop back with `Fx.run { }`. Never touch
   JavaFX nodes off the FX thread.
5. Report outcomes with `AppShell.toast(message, kind)` (`""`, `"ok"`, `"error"`).

## Theming model

`theme.css` defines tokens on `.root`. `Theme.setMode(...)` toggles a `.light`
class on the scene root and `Theme.setAccent(key)` swaps an `.accent-*` class —
every rule below re-resolves automatically, so no component needs to know which
theme is active. Persistence: light/dark is read from `AppStore.theme()` (the same
key the Android app writes); the accent preset lives in `java.util.prefs` under
the `hikari-desktop` node.

`Theme.refresh()` re-reads both and re-applies — `AppShell.show()` calls it on every
navigation so a theme change made elsewhere lands without a restart.

## Performance notes

`PosterGrid` is a `ListView` with one cell per row of N posters, so only visible
rows are materialised. Do not replace it with a `FlowPane` of cards: a real library
is thousands of items and thousands of `ImageView`s will not scroll smoothly.

## Ported engines and what is still missing

Downloads is done: the queue, the HLS/MP4 engine (AES-128, byte-range and fMP4
segments), pause/resume, offline playback and export to the user's own
Downloads/Hikari folder all work — see `docs/DESKTOP_PARITY.md` for what the
desktop changed relative to Android (mpv replaces `MediaExtractor`/`MediaMuxer`
for the audio-merge step).

The SkyStream, Nuvio and Aniyomi engines are ported, IPTV playlists are a
provider, and the player is an in-app layer rather than a window: mpv renders into
a surface the app owns (`--wid`, via JNA in `WinShell`), with the app's own
transport — timeline, ±10s, volume, speed, audio/subtitle tracks, next-episode,
and a Source picker — driving it over mpv's
JSON IPC, plus keyboard control (space, ←/→, ↑/↓, F, Esc). The Extensions screen
is where all of them are added. `desktop/UI_GUIDE.md`'s rules apply to any UI
added for them.

Still missing: i18n and the TMDB metadata/ratings/collections/backup features.
See `docs/DESKTOP_PARITY.md` for the state of each and the remaining plan.
