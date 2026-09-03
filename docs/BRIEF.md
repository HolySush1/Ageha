# Claude Code Prompt — Ageha (Kotatsu-Redo Desktop Port)

> Paste everything below the line into Claude Code in an empty project directory.
> Drop the logo image into that directory first, as `brand/ageha-logo-source.jpg`.

---

## Project

Build **Ageha** (アゲハ — Japanese for *swallowtail butterfly*), a native desktop manga reader for Windows, Linux and macOS that reproduces the experience of the Android app at `https://github.com/Kotatsu-Redo/Kotatsu-Redo`.

Language: Kotlin. Build: Gradle Kotlin DSL. Target: JDK 21. Application ID / root package: `app.ageha`. Repo/dir name: `ageha`.

## The single most important constraint — read this before writing any code

**Do not decompile, repackage, wrap, or "convert" the APK.** There is no valid path from an APK to a desktop binary. Every attempt at this is wasted effort and I will reject it.

**Do not copy, port, vendor, or reimplement the manga source parsers.** There are 1200+ of them, they break weekly, and they are the entire reason this project would otherwise be unmaintainable.

Instead, the parsers are consumed as an external library:

- Repo: `https://github.com/Kotatsu-Redo/kotatsu-parsers-redo` (fork of `KotatsuApp/kotatsu-parsers`)
- It is a **Kotlin/JVM + Android** library — plain JVM is a first-class target, no Android runtime needed
- Distributed via JitPack. Verify the exact group coordinate for the Redo fork before pinning it; upstream uses `com.github.KotatsuApp:kotatsu-parsers:$version`, the fork is most likely `com.github.Kotatsu-Redo:kotatsu-parsers-redo:$version` — **check JitPack and the fork's README, don't guess**
- Entry point is `mangaLoaderContext.newParserInstance(MangaParserSource.X)`, where `MangaLoaderContext` is an interface I must implement for desktop. The upstream repo ships a **non-Android `MangaLoaderContext` example** — find it, read it, base the desktop implementation on it

**Consequence:** when sources break or new ones land, updating this app is a dependency version bump. Not a rewrite. Every architectural decision must protect that property. If you ever find yourself about to hand-write a site-specific scraper, stop and tell me why.

## Step 0 — Install supporting skills

Before anything else, install these. All are free and open source; install only these four, because loading more slows Claude Code down and makes its behaviour less predictable.

```
# UI/UX design intelligence (MIT)
/plugin marketplace add nextlevelbuilder/ui-ux-pro-max-skill
/plugin install ui-ux-pro-max@ui-ux-pro-max-skill

# Compose / Compose Multiplatform expertise
git clone https://github.com/azlekov/compose-skill ~/.claude/skills/compose-skill

# Live, version-correct library docs (MIT, free tier, no key needed to start)
npx skills add intellectronica/agent-skills@context7

# Workflow discipline: TDD, code review, git
npx skills add obra/superpowers
```

### Overrides — these beat any skill's default

The Compose and KMP skills are written for mobile-first Kotlin Multiplatform. Several of their defaults are wrong for this project. When a skill's guidance conflicts with the list below, **the list below wins** — and say so out loud when you notice a conflict rather than silently picking one.

1. **Networking is OkHttp, never Ktor.** Every KMP skill will steer you to Ktor. `kotatsu-parsers-redo` is built on OkHttp and its `MangaLoaderContext` expects an OkHttp stack. Substituting Ktor breaks the parsers at runtime, not at compile time, so this mistake is expensive to find. Ktor may be used for unrelated side services only, if at all.
2. **This is a JVM desktop app, not a multiplatform app.** No `commonMain`/`iosMain` source sets, no `expect`/`actual` scaffolding, no iOS or Android targets. A single JVM target. Ignore skill guidance about SKIE, Swift interop, or shared mobile ViewModels entirely.
3. **DI is Koin, not Hilt** — as most of these skills will correctly tell you anyway.
4. **ui-ux-pro-max has no Compose Desktop stack.** Take its design system output as authoritative and translate it to Compose yourself. Never let the absence of a Compose stack push the project toward React, Electron, or any web technology.
5. Use Context7 to check current signatures for Compose Multiplatform, Room KMP, Coil 3 and OkHttp before writing against them. These APIs have moved recently and your training data may be stale.

## Step 1 — Investigate before proposing anything

Do not scaffold yet. First:

1. `git clone` both `Kotatsu-Redo/Kotatsu-Redo` and `Kotatsu-Redo/kotatsu-parsers-redo` into `./reference/` (gitignored).
2. Read the parsers library: the `MangaLoaderContext` interface, the non-Android implementation example, `MangaParserSource`, and the core model types (`Manga`, `MangaChapter`, `MangaPage`, `MangaTag`, `SortOrder`, filter/listing APIs, `MangaLoaderException`).
3. Identify every `MangaLoaderContext` member that is Android-specific on the app side — especially **JavaScript evaluation** (`evaluateJs` or equivalent), cookie storage, and User-Agent handling. The Android app backs JS execution with a WebView; desktop has no WebView. This is the hardest single problem in the project.
4. Skim the Android app for feature scope and data model: library/favourites, history, downloads, tracking (Shikimori / AniList / MyAnimeList / Kitsu), the sync server protocol, backup/restore format, and reader modes (standard + webtoon).
5. Check the licence. The project is GPL-3.0; this port must be GPL-3.0, must credit upstream, and must document changes.

Then write `docs/FINDINGS.md` and `docs/ARCHITECTURE.md` and **stop for my review.** Include in FINDINGS:

- Confirmed JitPack coordinate and latest version
- The full `MangaLoaderContext` surface I must implement, member by member
- Your recommended solution for JS evaluation, with at least three options costed (e.g. JCEF via JetBrains Runtime, headless Chrome via Playwright/CDP, GraalJS or Rhino for simple cases, or a hybrid that only spins up a browser for parsers that demand it). State how many sources actually need it, if you can determine that.
- Which parser APIs are stable vs. likely to churn across versions

## Proposed stack (challenge it in ARCHITECTURE.md if you disagree)

- **UI:** Compose Multiplatform for Desktop, Material 3. Not a phone layout — a real desktop app: resizable grid library view, keyboard shortcuts, multi-pane, proper window management. See the branding section below — the visual design is not left to your defaults.
- **HTTP:** OkHttp (the parsers library requires it), with a persistent cookie jar and a disk cache.
- **Images:** Coil 3 (multiplatform) or a Compose-Desktop-appropriate loader with aggressive disk caching and preloading for the reader.
- **Database:** Room 2.7+ (it supports desktop JVM with a bundled SQLite driver) or SQLDelight. Prefer whichever lets me stay closest to the Android app's schema, because that makes backup import viable.
- **DI:** Koin. (The Android app uses Hilt/Dagger, which is not a good fit here — do not fight this.)
- **Concurrency:** Coroutines + Flow.
- **Packaging:** see the update system below.

## Module layout

```
:core:model         # shared types, thin adapters over parser models
:core:network       # OkHttp stack, cookie jar, UA, rate limiting, proxy support
:core:database      # Room/SQLDelight, entities, DAOs, migrations
:core:parsers       # MangaLoaderContext impl + the version-tolerant parser facade (see below)
:core:js            # JS evaluation backend, pluggable, lazily initialised
:core:designsystem  # theme, colour tokens, typography, spacing, shared components
:feature:library    # saved manga, categories, favourites
:feature:explore    # source browsing, search, filters
:feature:reader     # page reader: paged + webtoon + double-page
:feature:downloads  # offline download queue, CBZ read/write
:feature:tracking   # Shikimori / AniList / MAL / Kitsu
:feature:settings
:feature:updates    # the update engine
:app:desktop        # Compose entry point, window/tray, packaging config
```

Nothing outside `:core:parsers` may import a parsers-library type directly. Everything goes through the facade. This is what stops a parser API change from spraying compile errors across twelve modules.

---

## Branding and visual design

### Use the ui-ux-pro-max skill

Design work goes through **ui-ux-pro-max** (`nextlevelbuilder/ui-ux-pro-max-skill`). If it isn't installed, install it first:

```
/plugin marketplace add nextlevelbuilder/ui-ux-pro-max-skill
/plugin install ui-ux-pro-max@ui-ux-pro-max-skill
```

Use it to generate and persist a design system before building any screen — run its `search.py` in `--design-system` mode with `--persist -p "Ageha"`, and query it per-domain (style, typography, chart, UX rules) as you go.

**Known gap, handle it deliberately:** the skill's stack-specific snippets cover React/Vue/Flutter/SwiftUI/JavaFX and similar, but **not Compose Multiplatform for Desktop**. So take its *design system* output — palette ramps, type scale, spacing, elevation, motion, the 99 UX rules — as authoritative, and translate it yourself into Compose `MaterialTheme` tokens in `:core:designsystem`. Do not take its component code verbatim and do not let the absence of a Compose stack push you into a web stack. If the skill's recommendation conflicts with anything specified below, what's below wins.

### Identity

- **Name:** Ageha. Display it as `Ageha` in Latin contexts; `アゲハ` may appear as a secondary mark. Window title, About dialog, installer, and binary names all use Ageha.
- **Logo:** `brand/ageha-logo-source.jpg` — a swallowtail butterfly rendered as a vermillion *hanko* seal / woodblock stamp, with visible ink texture, rough stamped edges and a small circle beneath the body.
- **Concept:** a stamped seal is a mark of ownership and a bookmark. Lean into that — it's a reading app, and the collection is the user's.

### Logo asset work

The source is a raster JPG on a fake checkerboard, so it has **no real alpha channel**. Before it can be used:

1. Vectorise to clean SVG, key the background out, produce a true transparent PNG master at 1024px.
2. Produce the full icon matrix: `.ico` (Windows, 16–256), `.icns` (macOS, with the correct rounded-square padding Apple expects), and PNG set for Linux (16/24/32/48/64/128/256/512).
3. **The fine wing veining will turn to mud below ~32px.** Draw a simplified small-size variant — silhouette plus the circle, no interior linework — and use it for the 16/24/32 slots and the system tray icon.
4. Keep a monochrome single-colour variant for tray, disabled states and watermarks.

### Colour

**Seed / primary brand colour: `#2B3A67`** — a deep indigo, close to traditional Japanese *kon* (紺). This is the app's identity colour and appears in the title bar, navigation rail, primary actions and installer chrome.

Feed `#2B3A67` into Material 3 as the **seed colour** and generate the full tonal palette from it. Do not paste the raw hex everywhere:

- In the light theme it works directly as primary.
- In the dark theme it is far too dark for `primary` — use the derived tone-80 range for primary and reserve the deep value for `surface`/`primaryContainer`. Verify with a contrast checker, don't eyeball it.

Supporting palette, derived from the logo:

- **Accent — vermillion `#C8402B`-ish** (sample the exact value off the logo). This is the *hanko* red. Use it sparingly and with intent: unread badges, the active reading indicator, new-chapter markers, destructive confirms. Indigo and vermillion is a deliberate traditional pairing — it stops working the moment vermillion becomes a general-purpose highlight.
- **Paper — warm off-white** (roughly `#F5F1E8`), the washi tone from the logo's negative space. Light-theme surfaces, not pure white.
- **Sumi — near-black `#1A1A1D`** for dark-theme base and text, warmer than pure black.
- Neutrals derived from the indigo tonal ramp so greys are subtly blue rather than dead.

Generate both light and dark themes, plus an AMOLED-black variant. All three must pass WCAG AA on body text.

### Type and texture

- Ask ui-ux-pro-max for a pairing that reads as restrained and editorial rather than techy — a humanist or transitional serif for titles and manga names, a clean neutral sans for UI chrome and metadata. Must ship with CJK coverage, since titles will regularly be Japanese, Korean and Chinese; specify the fallback chain explicitly.
- Texture is an accent, not a wallpaper. Empty states, the About screen, the splash and the loading indicator may use the stamped/ink motif. Lists and the reader stay clean.
- Motion: quiet and quick. Page transitions under 200ms. No bouncing, no staggered reveals in the library grid — this app gets opened many times a day and showy animation becomes irritating fast.

### Hard rule — the reader is exempt

Inside the reader view, brand colour must not touch the page. Chrome auto-hides, the background is user-selectable neutral (black / grey / paper white), and nothing indigo or vermillion tints the artwork. Brand identity lives in the library and navigation, never over someone's manga.

Write all of this up as `docs/DESIGN.md` with the resolved token values, and implement it once in `:core:designsystem`. No screen defines its own colours.

---

## THE UPDATE SYSTEM — the part I care most about

Three independent layers. Build them so that **routine source updates never require a human or an AI to touch application code.**

### Layer 1 — Runtime parser updates (the important one)

The parsers library is a plain JAR. Load it dynamically instead of only baking it in at compile time.

- Ship a bundled fallback JAR (a known-good pinned version) inside the app.
- On launch and on a schedule, check for a newer parsers build (GitHub releases/tags on `kotatsu-parsers-redo`, or JitPack). Download the JAR + its transitive deps to `~/.kotatsu-desktop/parsers/<version>/`, verify a checksum.
- Load it in an isolated `URLClassLoader`, parent-last for the parser classes but sharing OkHttp/Kotlin stdlib with the host to avoid duplicate-class hell. Document the classloader delegation policy explicitly in code comments — this is where it will go wrong.
- **Compatibility gate:** before activating a downloaded JAR, run a reflective self-check that every method and type the facade depends on still exists with the expected signature. If the check fails, refuse to activate, keep the current version, and surface a clear notice: "Parsers vX.Y needs an app update." Never crash-loop into a bad JAR.
- Keep the last known-good version on disk and support one-click rollback plus a manual "pin version" setting.
- The facade in `:core:parsers` is the only thing doing reflection or interface adaptation. It must degrade gracefully: a new source it doesn't recognise should still be usable through the generic interface, not crash.

Write this layer first and test it hardest. Include tests that load two different parser JAR versions in the same JVM and assert both isolation and correct fallback.

### Layer 2 — Application self-update

The desktop app updates itself when *app* code genuinely changes.

- Use **Hydraulic Conveyor**. It is free for any project under an OSI-approved licence, and this project is GPL-3.0, so it qualifies. Activate the free licence by setting `app.vcs-url` in `conveyor.conf` to the GitHub repo — there is no signup or account. The free open-source licence carries one obligation: the README must link to the Conveyor website and note that it's used for packaging. Add that.
- Conveyor gives signed delta updates across Win/macOS/Linux, bundles a jlink-optimised JVM, and needs no code changes. If it ever becomes unavailable, fall back to `jpackage` plus a small custom updater reading a signed JSON manifest from GitHub Releases.
- **Code-signing certificates are a separate, non-free cost** and are not covered by the Conveyor licence. Ship unsigned initially — Windows SmartScreen and macOS Gatekeeper will warn users, so document the workaround in the README. Note SignPath's open-source signing programme and Certum's open-source certificates in `docs/RELEASING.md` as options for later, but do not block release on either.
- Signature verification and atomic replace-on-restart are required once signing is in place.
- Settings toggle: automatic / notify only / manual.

### Layer 3 — CI automation, so I never do this by hand

In `.github/workflows/`:

- **`parsers-watch.yml`** — scheduled (every 6h). Polls the parsers repo for a new version. If found: bump the pinned version, run the full test suite including a live smoke test against ~15 representative sources, and open a PR that auto-merges on green. On red, open an issue with the failing diff.
- **`release.yml`** — on tag, build and publish installers for Windows (msi/exe), macOS (dmg, both arches), and Linux (AppImage + deb), plus the update feed.
- **`source-smoke.yml`** — nightly. Exercises search → details → chapter list → page URLs against a sample of sources and reports breakage. This is how I learn a source died without a user telling me.

Document the whole flow in `docs/UPDATING.md`, written for a human who forgot how any of this works.

---

## Migration from Android

Implement **import of the Android app's backup file** (the zip Kotatsu-Redo's backup/restore produces): library, categories, favourites, history, and reading positions. Users must be able to move to desktop without losing progress. Also wire up the existing sync server protocol (`kotatsu-syncserver`) if feasible — flag it if the protocol turns out to be Android-coupled.

## Reader requirements

This is a manga reader; the reader is the product. Non-negotiable:

- Paged mode (LTR / RTL), double-page spread with cover-offset handling, and continuous vertical webtoon mode
- Aggressive next-page prefetch and a memory-bounded bitmap cache; no stutter on large webtoon strips
- Zoom/pan, fit-width / fit-height / original, per-manga persisted preferences
- Full keyboard control (arrows, space, page up/down, F for fullscreen, escape) and mouse wheel
- Reading position persisted per chapter and restored exactly
- Selectable neutral background (black / dark grey / paper) independent of app theme, with auto-hiding chrome
- CBZ/CBR local file reading, matching the Android app's third-party archive support

## Working agreement

- **Milestones, not one giant dump.** After each, run the build, run the tests, and stop for my review:
  1. Skills installed, then `FINDINGS.md` + `ARCHITECTURE.md` (no app code)
  2. Gradle skeleton + `:core:parsers` facade + JVM `MangaLoaderContext` + a CLI that searches one source and prints results — proving the parsers work on desktop before any UI exists
  3. Layer 1 dynamic parser loading, with tests
  4. Database + library/history persistence
  5. `DESIGN.md` + `:core:designsystem` + logo/icon asset pipeline + a theme gallery screen showing every token in light/dark/AMOLED — reviewed before any real screen is built
  6. Compose UI: explore + library
  7. Reader
  8. Downloads, tracking, settings
  9. Layer 2 + 3 packaging and CI
- Don't stub silently. If something can't be done, say so and explain why.
- Every module gets tests. Networked tests are tagged and excluded from the default run.
- Conventional commits, small commits, `CHANGELOG.md` maintained.
- If you hit a decision with real tradeoffs (JS engine choice, Room vs SQLDelight, Conveyor vs jpackage), stop and ask rather than picking silently.

Start with Step 0, then Step 1. Do not write application code until I've approved `ARCHITECTURE.md`.
