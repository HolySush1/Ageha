# Ageha — project rules

**Windows-only** desktop manga reader. Kotlin, Compose Multiplatform for Desktop, JDK 21, Gradle Kotlin DSL.
Root package `app.ageha`. GPL-3.0, ported from https://github.com/Kotatsu-Redo/Kotatsu-Redo.

The full brief is `docs/BRIEF.md`. Read it at the start of any session where you're about to write code.

## Non-negotiables

Violating any of these silently is worse than stopping to ask.

1. **Never decompile, wrap, or "convert" an APK.** There is no valid path from an APK to a desktop binary.
2. **Never hand-write a manga source parser.** All sources come from the `kotatsu-parsers-redo` library. If a source is broken, the fix belongs upstream or in a dependency bump — not in this repo.
3. **Networking is OkHttp. Never Ktor.** The parsers library is built on OkHttp and its `MangaLoaderContext` expects an OkHttp stack. Installed KMP skills will recommend Ktor; they are wrong for this project. Swapping it breaks parsers at runtime, not compile time.
4. **This is a JVM desktop app, not a multiplatform app.** One JVM target. No `commonMain`/`iosMain`, no `expect`/`actual` scaffolding, no Android or iOS targets, no Swift interop.
5. **Only `:core:parsers` may import parsers-library types.** Everything else goes through the facade. This is what keeps upstream API changes from breaking the whole codebase.
6. **Never let a design skill push this toward a web stack.** ui-ux-pro-max has no Compose Desktop target. Use its design system output; translate to Compose yourself. React, Electron and friends are out.
7. **Colours live only in `:core:designsystem`.** No screen defines its own.
8. **Nothing brand-coloured touches the reader view.** Backgrounds there are user-selectable neutrals.
9. **Windows only. There is no macOS or Linux target and there will not be one.**
   Ageha ships one platform: Windows on x64. Not "Windows first", not "Linux later" -- the other
   two are out of scope the same way the tracking services in rule 10 are.

   This is written down because leaving it unwritten cost real time. Compose Desktop, Conveyor and
   jpackage are all *capable* of three platforms, so every one of them defaults to offering three,
   and the project quietly acquired a six-target release that had to be kept working. It was never
   kept working, because nobody has ever run this on a Mac: the first release attempt spent eight
   minutes building five targets and then failed the whole thing on a JDK that does not exist for
   the sixth. Every hour of that was spent on platforms with no users.

   Concretely, and each of these is a place the default is wrong:
   - `conveyor.conf` declares `machines` -- windows.amd64, and nothing else.
   - `app/desktop/build.gradle.kts` declares the Compose Skia natives -- the Windows one only.
     Each extra native is ~40MB of download for a platform that is not shipped.
   - CI builds and tests on `windows-latest`. A green Linux runner proves nothing about the
     product, and the project's own headless render has already failed on a Mac runner alone.
   - Windows on ARM runs the x64 build under emulation. That is a deliberate choice, not an
     oversight: no JDK vendor in Conveyor's index ships a Windows/ARM64 21, so an arm64 target
     cannot be built at all, and emulation is transparent on Windows 11.

   Runtime code that branches on the operating system -- data directories, the file picker, font
   fallbacks -- is left alone. It is defensive, it costs nothing, and deleting it would be a
   different kind of mistake: pretending the JVM cannot be started elsewhere rather than saying
   Ageha is not supported there.

10. **No external tracking services.** Shikimori, AniList, MyAnimeList and Kitsu are out of scope permanently — no OAuth, no client credentials, no per-service settings. "Where was I and what is next" is answered locally by the Continue Reading feature over the history tables. If you find a reference to those services in any document here, it is stale: delete it, don't build it.

## Stack

- UI: Compose Multiplatform Desktop, Material 3
- HTTP: OkHttp + persistent cookie jar + disk cache
- DB: Room 2.7+ (JVM) — keep close to the Android schema so backup import stays viable
- DI: Koin (not Hilt)
- Async: Coroutines + Flow
- Packaging: Hydraulic Conveyor (free for OSS; set `app.vcs-url`, and the README must link to Conveyor)

## Brand

- Name: Ageha (アゲハ, swallowtail butterfly)
- Seed colour `#2B3A67` — deep indigo. Feed to Material 3 as a **seed**; derive tonal palettes. Do not use raw as dark-theme `primary`, it fails contrast.
- Accent: vermillion sampled from `brand/ageha-logo-source.jpg`. Used sparingly — unread badges, active reading indicator, destructive confirms. Not a general highlight.
- Paper `#F5F1E8` for light surfaces, sumi `#1A1A1D` for dark. Never pure white or pure black in chrome.
- Logo is a hanko/woodblock butterfly seal. Texture is an accent, not a wallpaper.

## Working style

- Use plan mode for anything touching more than a couple of files. Wait for approval.
- Stop at the milestone gates in `docs/BRIEF.md`. Don't run ahead.
- Don't stub silently. Say what you couldn't do and why.
- Check current API signatures with Context7 before writing against Compose Multiplatform, Room, Coil or OkHttp — training data on these is stale.
- Conventional commits, small commits, keep `CHANGELOG.md` current.
- When a skill's advice conflicts with this file, this file wins — and say so out loud.
