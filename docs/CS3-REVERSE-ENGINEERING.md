# CS3 Desktop Client — Reverse Engineering Notes

Target: https://github.com/errorcode26/CS3-desktop-client-unofficial (`work` branch).
Date: 2026-09-29. Purpose: bring Hikari Desktop to UI + extension parity with the
reference client while keeping every Hikari source (cloudstream .cs3, hikari .hiki,
nuvio JS, stremio addons, skystream .sky, aniyomi .apk, IPTV).

## 1. Architecture

- Compose Multiplatform desktop app (JDK 21), modules: `:desktop-app` (UI),
  `:plugin-runtime` (dex transcompilation + sandbox), `:player-abstraction` (libmpv
  via JNA), `:android-stubs` (Context/SharedPreferences/Build/DisplayMetrics),
  `:common` (SQLDelight persistence), `:library` (CloudStream contracts).
- Clean Architecture DI (`AppContainer`): repositories + use-case interactors
  (GetWatchHistory, GetAvailablePlugins, InstallPlugin, ...).
- Navigation: Decompose stack (`Config`: Home, Explore, History, Search,
  Extensions, Library, Downloads, Settings, Details, CategoryGrid, Person, Studio,
  FullCast). Amoled dark theme, customizable dock order/position, TopBar with
  profiles + download pill + window controls.

## 2. Why every extension works there

- `plugin-runtime/ExtensionLoader`: manifest.json -> pluginClassName, secure-jar
  cache (`-secure.jar`, transformer-version + class-presence validation), single
  dex2jar gate (`dex2JarLock`), permanent failed-dex memo (`failedDexCache` +
  `.d2j_failed` markers), per-plugin load locks, `URLClassLoader` per plugin with
  registries (`plugins`, `classLoaders`, `classLoaderToJar`,
  `classLoaderToClassNames`), composite classloader for Jackson/kotlin-reflect,
  `getCallingPluginName()` via StackWalker, `unloadPlugin()`, ASM
  `PluginBytecodeTransformer` + `SafePluginClassLoader` + security verifier.
- `DesktopRepositoryManager`: saved repos, remote icons, sync generation,
  `syncAll()` with progress, `downloadPlugin()` to extensions dir, manifest read.
- Player: native `libmpv-2.dll` + `player_bridge.dll` HWND compositor + WebView2
  overlay; C++ fast path for seek/volume; CDP Cloudflare killer.

## 3. UI map (what parity means in Hikari/JavaFX)

- Dock: Home, Explore, Search, Library, Downloads, History, Extensions, Settings.
  Hikari had Home/Search/Library/Downloads/Extensions/Settings; this change adds
  Explore (catalogs across providers) and History (watch history) screens.
- TopBar: title + subtitle, global search (Ctrl+K), activity chip, theme toggle,
  window controls. Unchanged, shortcuts extended (Ctrl+5/6 for new screens).
- Home: hero carousel (auto-slide) + continue-watching + progressive rails.
  Hikari already renders hero + rails progressively; carousel auto-slide is next.
- Details: TMDB-enriched cast/seasons/episodes/trailer. Hikari DetailScreen keeps
  its layout; enrichment passes through existing TmdbMeta.
- Extensions: repo cards + per-plugin rows with install state + error text +
  sync progress. Hikari ExtensionsScreen already does this; loader hardening
  below is what makes the rows actually turn green.
- Player: timeline/volume/skip/sources/stats overlays. Hikari keeps mpv.exe + IPC
  + floating bars; no native DLL swap in this change.
- Theme: Amoled (`amoled` scene class, pure-black canvas) added alongside
  dark/light + accent presets.

## 4. What was ported

- `Cs3PluginManager`: per-plugin load locks, `failedDexCache`/failed markers,
  classloader registries, `createCompositeClassLoader()`, `getCallingPluginName()`,
  `unloadPlugin()`, `clearFailedMarker()` — ExtensionLoader parity.
- `DexJar`: `TRANSFORMER_VERSION` marker (`META-INF/hikari-transformer.version`),
  `checkJarHasTransformerVersion()` + `checkJarHasClass()` cache validation,
  failed-digest memo so a broken dex is not re-translated in a loop.
- UI: Explore + History screens wired into AppShell; AMOLED theme mode.

## 5. Deliberately NOT ported (would break the JavaFX/JDK17/jpackage build)

- Compose Multiplatform UI, Decompose, SQLDelight, Coil, Haze blur, JNA libmpv,
  player_bridge.dll, WebView2/CDP, Discord RPC, TurboChunkDownloader. These need
  a JDK21 + Compose + native-DLL build chain; Hikari stays JavaFX + mpv.exe.

