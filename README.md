Just a Beta build, and currently not working on it. so next update with all features and stable build i don't even know myself when will come. 


# hikari-desktop
Hikari — universal streaming app for Windows. Desktop port of codegeasse1/hikari (Stremio addons, universal scrapers, CloudStream .cs3 plugins, Hikari extensions, SkyStream .sky extensions, Nuvio providers, Aniyomi .apk extensions, IPTV m3u/m3u8 playlists).

Playback is mpv (bundled), rendering inside the app's own player layer over mpv's
JSON IPC — one slim control bar under the picture, with a Source picker for
switching servers mid-playback.

Developer notes: `docs/DESKTOP_PARITY.md` (what is ported, what is not, and why),
`docs/HIKARI_EXTENSIONS.md` (extension formats), `desktop/UI_GUIDE.md` (layout rules).

## Building and testing

There is no local toolchain — CI is the compiler, and every build is a Windows
runner, so the OS-specific paths (Win32 window adoption, Schannel/curl, signtool)
are exercised on every run.

- **Pushing to `main` runs `.github/workflows/build.yml`**: it compiles, packages,
  signs, runs the self-tests (`desktop/src/main/kotlin/desktop/**SelfTest.kt`,
  `com/hikari/app/**SelfTest.kt`), renders the UI screenshots, and publishes the
  installer to the `continuous` release as `Hikari-0.1.<run number>.exe`. The
  installed app reports that same version (see `desktop/Build.kt`), so a bug report
  can name the build it came from.
- **The UI screenshots are the visual test.** `desktop.uitest.UiShotTestKt ui-shots`
  drives the real screens with a real mouse (`java.awt.Robot`) and asserts what a
  screenshot cannot show: that the banner's Play button actually runs the play path,
  that every control on the player's bar fits its own label and the bar fits the
  window, that the provider sheet opens and its choice survives a reload, and so on.
  Its `ui-shots` artifact is the quickest way to *look* at a build.
- **Iterating**: dispatch `build.yml` against a temporary branch (it publishes from
  any ref, so a throwaway compile-only workflow or branch is worth it for a big
  batch). `ci-compile.yml` is a compile-only check that never publishes anything.

