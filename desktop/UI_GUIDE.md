# Hikari Desktop — UI layer guide

Read this before touching anything under `desktop/src/main/kotlin/desktop/ui/`.

## Layout of the UI layer

| File | Role |
|---|---|
| `theme.css` | The design system. Layer 1 = design tokens as JavaFX looked-up colours on `.root`; layer 2 = every control styled from those tokens. |
| `Theme.kt` | Owns the two things that *select* a theme (light/dark, accent preset) and exposes the spacing/type scales. |
| `Icons.kt` | The whole icon set, built from JavaFX primitives on a 24x24 grid. |
| `Components.kt` (`Ui`) | Buttons, chips, fields, badges, section headers, empty states, skeletons, toasts. |
| `PosterCard.kt` | The poster card + `PosterRail` (headed horizontal rail). |
| `PosterGrid.kt` | Virtualised poster grid. |
| `AppShell.kt` | Sidebar + top bar + content host + toasts + keyboard shortcuts. |
| `WindowChrome.kt` | Resize edges, window controls, drag-to-maximise. |

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
5. **Text is `Theme.label(text, size, bold, dim)`** — only the size is inline, the
   colour comes from CSS.

## Adding a screen

1. Create `XScreen.kt` in `desktop/ui/` with a `class XScreenView { val root: Region; fun onShown() {} }`.
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

## What is not built yet

Screens present: Home, Search, Library, Downloads, Extensions, Settings, Detail.
Downloads has real storage reporting but no download engine; the Android
`DownloadEngine` is a plain OkHttp m3u8/MP4 downloader and ports directly. The
player still launches mpv as a child process — driving it over
`--input-ipc-server` is what unlocks a real OSD, timeline and track selection.
