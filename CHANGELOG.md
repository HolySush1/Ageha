# Changelog

All notable changes to Ageha are recorded here.
Format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/);
this project uses [Conventional Commits](https://www.conventionalcommits.org/).

## [Unreleased]

### Added

- **Milestone 1 -- investigation.** `docs/FINDINGS.md` and `docs/ARCHITECTURE.md`, from reading
  `kotatsu-parsers-redo` at `434030d481`, `Kotatsu-Redo`, and `kotatsu-dl`.
- **Milestone 2 -- the source layer and a CLI that proves it.**
  - `:core:model` -- Ageha's own manga types and a typed `SourceFailure` hierarchy.
  - `:core:network` -- OkHttp 5 stack, persistent cookie jar, per-host rate limiting.
  - `:core:js` -- pluggable `JsRuntime` with `NoJsRuntime` as the shipping default.
  - `:core:jvmcontext` -- the JVM `MangaLoaderContext`, AWT bitmaps, parser interceptor dispatch.
  - `:core:parsers` -- the version-tolerant facade. The only way into sources.
  - `:app:cli` -- `sources`, `search`, `details` and `pages` against live sites.
  - A Gradle check that fails the build if any module outside the facade can see the parsers
    library.

- **Milestone 3 -- Layer 1 dynamic parser loading.**
  - `ParsersClassLoader` loads a parsers build in isolation, so a newer one can replace it without
    restarting Ageha.
  - `ParserBridge` is the only thing crossing that boundary. No parser type does.
  - `CompatibilityGate` decides whether a build is safe to run before anything depends on it, and
    treats refusal as a designed outcome rather than an error.
  - `ParsersInstallation` tracks the active build, the one to roll back to, a user pin, and the
    builds already refused.
  - `ParsersUpdateService` resolves upstream's HEAD, waits for JitPack, resolves the build's own
    dependencies from its POM, and gates what it fetches.
  - `cli parsers [status|check|rollback]`.

- **Milestone 4 -- database, persistence and Android backup import.**
  - `:core:backup` imports a Kotatsu-Redo Android backup archive: history, favourites, categories
    and sources, with reading position restored exactly. Moved ahead of any UI deliberately, since
    it exercises every column of the schema against data the Android app actually wrote.
  - Reading is lenient (unknown fields and unknown sections are tolerated, so a backup from a newer
    Android app still imports what it can); writing is strict (one transaction, everything or
    nothing).
  - The result names what it could not restore. Sections Ageha does not support yet, entries it
    does not recognise, and individual rows dropped -- such as a favourite referencing a category
    the backup never defined, which would otherwise fail the whole import on a foreign key.
  - `cli import <backup.zip>` and `cli library`.
- **Milestone 4 -- the database.** `:core:database` on Room 2.8 with the bundled
  SQLite driver, which is what proves Room works off Android. Schema declared at **version 28** to
  match the Android app rather than replaying 27 migrations that could never run here. Eight
  entities so far (manga, tags, manga_tags, chapters, history, favourites, favourite_categories,
  sources); the remaining nine arrive with the features that use them. Schema is exported to
  `core/database/schemas/` so it can be diffed against the Android app's.

### Changed

- **The approved parent-first classloader allowlist was abandoned before it was built.** It
  transitively froze `AbstractMangaParser` through `LinkResolver`, could not express the
  KSP-generated enum living in the models' own package, and would have made every change to
  `Manga` require an app release. Replaced by a narrow typed bridge with the parsers library and
  Ageha's `MangaLoaderContext` implementation loaded together in the child. `docs/ARCHITECTURE.md`
  4.1 records what was tried and why it changed.
- The parsers-library exemption narrowed from two modules to one. `:core:parsers` no longer names
  a parser type at all.
- `:core:jvmcontext` is now `compileOnly` against the parsers library and never reaches the
  application runtime classpath.

### Fixed

- **Two coexisting builds shared one OkHttp cache directory.** Every child context built its own
  client against the default cache dir, so the compatibility gate -- which constructs a second
  context while the live one is serving -- put two `Cache` instances on one directory on every
  update check. OkHttp calls that an error, and classloader isolation does not help because the
  directory is shared regardless. The parent now owns one client and injects it; the gate gets a
  cache-less view of it.
- **Downloaded builds are now locked and verified.** A per-build `lock.json` records a SHA-256 for
  every file, verified before every load rather than only after download. Missing, modified and
  unexpected files all fail. Downloads are additionally checked against the repository's published
  `.sha1` where one exists.
- The shim surface is now a tracked metric: 13 members, held to a test that fails in either
  direction. It went from 14 when `getPreferredLocales` turned out to be overridden with an
  implementation identical to the upstream default.
- `SourceFailure.Blocked` and HTTP status classification, so a 403 from bot protection no longer
  reports as an unreachable network.
- The HTTP stack is now shut down on close. OkHttp holds a dispatcher pool, live sockets and an
  open cache journal, and the open journal prevents an update from replacing the build it is
  updating on Windows.
- `selfCheck` no longer swallows `LinkageError`. `runCatching` catches `Error` too, which reported
  a wholesale version mismatch as "25 sources are broken" and pointed at the wrong thing entirely.

- **Milestone 5 -- the design system.**
  - `:core:designsystem` -- one Material 3 theme in light, dark and AMOLED, a desktop-tuned type
    scale, a 4dp spacing scale, shape and motion tokens, and the reader's brand-free backgrounds.
  - `:tools:brandkit` -- build-time only. Derives the tonal palettes from the brand seed using
    Google's colour science and writes them out as literal hex, and rebuilds every icon and logo
    asset from `brand/ageha-logo-source.jpg`.
  - `:app:desktop` -- the theme gallery, showing every token in all three themes side by side.
    `renderGallery` writes the same view to a PNG with no window, so review does not require this
    machine.
  - `docs/DESIGN.md` with the resolved token values and the reasoning behind each departure from
    stock Material 3.
  - The vermillion accent is exposed as three components rather than as a colour, so it cannot
    become a general-purpose highlight.
  - 117 new tests: 66 WCAG contrast pairs across the three themes, the palette's structural rules,
    the reader's freedom from brand colour, and the hand-written `.ico`/`.icns` writers parsed back
    byte by byte.

- **Milestone 6 -- the desktop UI: explore and library.**
  - `:core:data` -- repositories over the database and the source facade. Every source call comes
    back as a `CatalogResult`, so a site being down is a value a screen renders rather than an
    exception that unwinds it.
  - `:core:image` -- Coil 3 on **OkHttp**, sharing the source stack's client so covers travel with
    the same cookies and User-Agent the listing did.
  - `:feature:library` -- shelves in a persistent rail with live counts, a grid that reflows with
    the window, continue-reading, filter and sort.
  - `:feature:explore` -- the 1360-source picker with per-source enable, one-source browsing with
    paging, and a two-pane details screen.
  - `:app:desktop` -- the application shell: navigation rail, per-section back stacks, keyboard
    shortcuts, a menu bar, remembered window geometry, and Koin wiring with an ordered shutdown.
  - `renderShell` draws the real application headlessly against the real graph, so a build that
    would open a blank window fails in CI instead.

- **Milestone 7 -- the reader.**
  - `:feature:reader` -- paged mode left-to-right and right-to-left, double-page spreads with
    cover-offset handling, continuous vertical webtoon mode, zoom and pan about the pointer, four
    fit modes, auto-hiding chrome, and full keyboard control.
  - Reading position is persisted per manga and restored exactly, including the webtoon strip's
    scroll fraction -- a page index alone is not a position when a page is twelve thousand pixels
    tall.
  - Schema **v29** adds the Android app's `preferences` table, by auto-migration, so per-manga
    reader mode survives a backup round trip.
  - CBZ archives are read straight out of the zip, through the same image pipeline as remote
    sources. CBR is refused with a reason the user can act on rather than a generic failure.

- **Milestone 8 -- settings, downloads and the JavaScript engine.**
  - `:core:js` gains a **real backend**: Rhino, serving the `PLAIN_SCRIPT` tier, sandboxed and
    time-bounded. This is what makes the ~257 conditionally-JS sources work.
  - `:feature:settings` -- appearance, reader defaults, backup import, and the first user-facing
    view of Layer 1: active build, update policy, check, roll back, pin, and what the JavaScript
    engine can and cannot do.
  - `:feature:downloads` and `ChapterDownloader` -- offline chapters written as ordinary CBZ, two
    at a time per source, with a queue that reports what it skipped and why.
  - A native file picker, `Ctrl+3` for downloads and `Ctrl+,` for settings.
  - **Tracking is not built.** It needs OAuth clients registered per service; see
    `docs/ARCHITECTURE.md` 7b.

- **Milestone 9 -- packaging and CI.**
  - `conveyor.conf` -- signed, self-updating installers for six targets from one machine. The
    icon ladder is handed over explicitly rather than generated, so the simplified small-size mark
    survives.
  - `.github/workflows/` -- `ci.yml` on all three operating systems, `parsers-watch.yml` every six
    hours, `source-smoke.yml` nightly, `release.yml` on a tag.
  - `cli smoke` -- exercises a random sample of real sources end to end, seeded so a failing run
    can be reproduced exactly.
  - `docs/UPDATING.md` and `docs/RELEASING.md`.

- **Local comic archives, end to end.** File > Open comic archive reads a CBZ straight into the
  reader, through the same image pipeline as a remote source. Reading position persists for local
  files too, since the archive's id is derived from its absolute path.
- **Application notices.** Backup import and archive opening now report their outcome *in the
  window* instead of on stdout, which a windowed application does not have. The backup importer's
  full account -- restored, unsupported, unrecognised, and every dropped row with its reason --
  is shown monospaced and stays until dismissed.

### Fixed

- **Cover and page images used Coil's default loader, not Ageha's.** The configured loader was
  registered in DI and handed to Compose nowhere, so every `AsyncImage` silently fell back to a
  default with neither the archive fetcher nor Ageha's OkHttp client -- meaning no cookie jar, no
  User-Agent and no per-source `Referer` on any image request. Found by rendering the reader
  against a real CBZ and getting a blank page.
- **The archive fetcher never matched.** Coil runs its mappers before consulting fetchers, so the
  `cbz://` model had already become a `coil3.Uri` and a factory matching only `String` declined
  every request.
- **Reading anything not already favourited saved no history.** `history.manga_id` is an enforced
  foreign key and the manga row was only ever written by favouriting, so reading from search, from
  a listing, or from a local file failed the constraint and recorded nothing.
- **Shutdown raced the database.** Cancelling the application scope does not wait for work already
  inside a query, so closing could throw `connection is closed` from a background thread. Shutdown
  now joins the cancelled scope with a bounded grace period, and the reader's final position write
  is `NonCancellable` so the last page turn survives quitting.

### Notes

- Sources are addressed and persisted **by name string, never by enum or ordinal**.
  `MangaParserSource` is generated at build time by KSP, so its constants differ between parser
  builds.
- Ageha ships with **no JavaScript backend**. Around 20 of 1360 sources need one and report a
  specific, actionable failure rather than a generic error.
- Layer 1 does **not** promise that source updates never need an app release, and the docs no
  longer imply it. `evaluateJs` gaining a third parameter upstream is proof the host contract
  moves. The promise is narrower and honest: routine source updates never need one, and when the
  contract does move, the running build keeps working and the user is told once.
- The colour tokens are **generated and committed**, not hand-picked. Google's colour-science
  library is a build-time dependency of `:tools:brandkit` and is not on the application's
  classpath.
- All three themes pass WCAG AA on body text, asserted on every build rather than checked once.
- Nothing brand-coloured reaches the reader, and a test enforces it: reader colours carry under 6%
  chroma and are never a design-system token.
- Fonts are **not** bundled yet. The families are an explicit preference chain resolved against
  what is installed, and the gallery reports per-script CJK coverage so the gap is visible.
- Sources start **disabled**. Ageha ships 1360 and only ever contacts the ones a user turns on.
- The library grid uses `GridCells.Adaptive`, not a fixed column count: this is a resizable
  desktop window, not a phone.
- A source failure never clears results already on screen, and a non-transient failure stops the
  pager rather than retrying into a block.
- The reader's arrow keys follow the **reading direction**: in right-to-left mode Left advances.
  Space and Page Down always mean forward, as they do in any document.
- `ReaderMode` ids are the Android app's and are **not** ordinals -- STANDARD is 1, WEBTOON is 2,
  REVERSED is 3, VERTICAL is 4.
- Webtoon mode uses a lazy list. That was flagged as a risk in ARCHITECTURE 1.4 and the risk is
  not yet closed: it has not been profiled against a real 200-page strip.
- The JavaScript engine is **Rhino, not QuickJS**. The tier it serves is pure ES5 computation, and
  Rhino removes six native binaries from the packaging problem. `JsRuntime` is unchanged, so the
  choice is reversible.
- Scripts run with no Java bridge and a wall-clock deadline, because the script is served by the
  site being scraped.
- Downloads are limited to **two at a time per source**. The cost of being impolite to a small site
  is not a slow download; it is a block affecting every Ageha user of that source.
- A `.cbz` on disk is always complete: downloads are written to `.part` and renamed on success.
- The nightly smoke test fails only when failures are numerous **and alike**. A rate-only gate was
  tried first and tripped on an ordinary night: 9 of 12 sources failed, across three unrelated
  causes, which is simply the state of the scanlation web.
- CI runs on Linux, Windows **and** macOS. Every platform-specific bug found in this project so far
  has been a Windows file-handle problem, which a Linux-only CI would have missed.
- Releases are **unsigned**. Certificates are a separate paid cost; `docs/RELEASING.md` lists the
  routes and the workarounds users need meanwhile.
