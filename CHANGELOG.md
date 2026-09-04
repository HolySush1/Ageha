# Changelog

All notable changes to Ageha are recorded here.
Format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/);
this project uses [Conventional Commits](https://www.conventionalcommits.org/).

## [Unreleased]

### Added

- **A real Windows installer, built and verified by installing it.** `./gradlew :app:desktop:packageMsi`
  produces `Ageha-<version>.msi`: a per-user install needing no administrator prompt, with a Start
  menu entry, a desktop shortcut, and an entry in Apps & Features. Installed, launched, and
  uninstalled on a real machine rather than inspected in a build log.
- **A CJK font, bundled with the Linux packages only.** Windows and macOS ship CJK coverage and get
  nothing extra; Linux packages carry one 16MB `NotoSansCJKjp-Regular.otf`, which `CjkFontTest`
  verifies covers Japanese kana and kanji, Korean hangul, both Chinese variants and Latin. Fetched
  at build time from a tag-and-hash-pinned URL rather than committed. `AgehaFonts` uses it only
  where a script has no system font, so a Linux user with their own Noto package keeps Inter and
  Source Serif; Settings > Appearance says which is in play.

- **An end-to-end journey test that drives the real app.** `:app:desktop:e2e` boots the whole
  application, renders the real shell, and walks the path a person actually takes: open a chapter,
  turn pages with the keyboard, close the application, open a *new* one against the same profile,
  and follow the Continue Reading entry back. It asserts on the page image on screen, not on the
  view model. `-PwithNetwork` adds a second journey against live MangaDex -- enable the source,
  browse it, open a manga, open a chapter. Three of the four bugs from the previous session were
  invisible to unit tests and only appeared once something rendered; this is the harness that
  renders it. Excluded from `check`, because booting an application installs Coil's process-global
  singleton and would decide what every later test in the JVM saw.
- **`AGEHA_DATA_DIR` / `-Dageha.data.dir`** points Ageha at a different profile directory. The
  end-to-end test, the shell and gallery renderers, and the webtoon profiler now all use scratch
  profiles under `build/`. Before this, generating a screenshot wrote a sample CBZ into whatever
  reading history was on the machine.

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

- **Uninstalling Ageha deleted the user's entire library.** jpackage derives the install directory
  from the package name, so a per-user install landed in `%LOCALAPPDATA%\Ageha` -- byte for byte
  the directory `AgehaPaths` keeps the database, cookies and preferences in. Installing dropped
  `app\`, `runtime\` and `Ageha.exe` on top of somebody's library, and uninstalling removed the
  directory and everything else in it. Found by installing the MSI and then uninstalling it, with
  the profile backed up first; the binaries now live in `%LOCALAPPDATA%\Ageha Reader` and the
  full install, launch and uninstall cycle has been re-run to confirm the library survives.
- **`conveyor.conf` had never parsed.** It used `/* */` block comments, which HOCON does not have,
  so the file that was "only ever syntax-checked" failed on the first line Conveyor read. Also
  pinned `app.version`, since the Gradle plugin hands over `0.1.0-SNAPSHOT` and no installer
  format accepts a snapshot suffix. `conveyor json` now resolves the whole configuration, and
  confirms the CJK font reaches the two Linux machines and none of the other four.

- **The reader claimed "1 / 0" while a chapter was still loading.** Everything the status bar shows
  is derived from the page list, but it was drawn outside the `isLoading` guard -- so a chapter
  opened from a live source displayed "1 / 0" and "Chapter 1 of 0", confidently and wrongly, for
  as long as the source took to answer. Now it waits for pages; the top bar, whose Close button is
  exactly what someone wants during a slow load, still does not. Found by the end-to-end driver.
- **A search on Explore with no sources enabled said "No sources match".** On a fresh installation
  nothing is enabled, so *every* search of the enabled sources comes back empty -- and the message
  sent the user looking for a source that was sitting right there, switched off. It now says how
  many sources match in the full catalogue and offers to show them. The empty-list state already
  did this; the searched state did not, which is the state a new user reaches first.
- **`createDistributable` and `runDistributable` had never worked.** jpackage rejects a version
  that is not strictly numeric-dotted, and the project version carries `-SNAPSHOT`, so every
  invocation failed with `Version [0.1.0-SNAPSHOT] contains invalid component [0-SNAPSHOT]`.
  Conveyor does not go through jpackage and so never hit it, which is why it went unnoticed: the
  shipping path worked and the local one did not.
- **`:app:desktop:e2e` passed by running nothing.** The root build applied `excludeTags` to every
  `Test` task, including the one that opts *in* to the `e2e` tag, and JUnit resolves
  include-and-exclude of the same tag as excluded. The tag filtering is now scoped to the standard
  `test` task. A test task that reports success without running a test is worse than one that
  fails.

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
  - A native file picker and `Ctrl+,` for settings.

- **A webtoon profile.** `./gradlew :app:desktop:webtoonProfile` builds a real 200-page strip,
  scrolls it end to end through the real reader with real scroll events, and reports frame times,
  heap and how much of the strip actually resolved. This closes the open risk from
  `docs/ARCHITECTURE.md` 1.4: every frame lands inside the 60Hz budget and the heap peaks near
  30MB against the ~1.5GB that holding all 200 decoded pages would need, so the stock `LazyColumn`
  stays and no custom layout is needed.
- **A test that Compose draws through Ageha's image loader**, covering the singleton handoff, the
  archive fetcher's ordering ahead of the network, and -- by putting an interceptor on the client
  and watching it fire -- that requests really go through Ageha's OkHttp stack rather than the one
  Coil registers for itself.

- **Milestone 9 -- packaging and CI.**
  - `conveyor.conf` -- signed, self-updating installers for six targets from one machine. The
    icon ladder is handed over explicitly rather than generated, so the simplified small-size mark
    survives.
  - `.github/workflows/` -- `ci.yml` on all three operating systems, `parsers-watch.yml` every six
    hours, `source-smoke.yml` nightly, `release.yml` on a tag.
  - `cli smoke` -- exercises a random sample of real sources end to end, seeded so a failing run
    can be reproduced exactly.
  - `docs/UPDATING.md` and `docs/RELEASING.md`.

- **Continue Reading.** Everything read, most recent first, with cover, title, source and the
  chapter stopped on. Reached by `Ctrl+2`, and the most recent few appear as a shelf on the library
  screen.
  - **Opening an entry resumes the exact page** -- unless that page was the last of its chapter, in
    which case the *next* chapter opens at page one. When there is no next chapter the entry
    reopens where it was and says it is caught up.
  - **A quick search that is actually quick.** Filtering by title runs over a list already in
    memory: no network, no database round trip, no debounce. It is the one search in Ageha that
    answers as fast as it is typed.
  - **An entry whose source is gone stays put, marked unavailable**, and offers a search for the
    same title across every enabled source. Sources disappear on a parsers downgrade and whenever
    upstream retires a site; the reading history belongs to the user, not to the source.
  - Entries can be removed one at a time, or all at once from Settings > Library. Both are soft
    deletes, so a future sync cannot resurrect what was cleared.
  - Schema **v30** adds `history.page_count` -- Ageha's own column, the first divergence from the
    Android schema. Without it "was that the last page of the chapter" is unanswerable. Additive
    and defaulted, so Android backups still import and simply report the count as unknown.
  - `ReaderRepository` now stores the chapter list alongside the reading position, guarded so it is
    not rewritten on every page turn. The `chapters` table had existed since Milestone 4 with
    nothing writing to it; it is what lets the last chapter be *named* and the next one *found*
    with no network call.
- **One app version, in `:core:model`.** Four unrelated places need it -- the backup index, sync's
  `X-App-Version` header, the `added_in` column recording which release first saw a source, and the
  update check -- and three had grown their own copy while this work was going on. Two constants
  that must agree and do not have to are a bug with a delay on it. `NAME` and `CODE` are separate
  because two formats Ageha does not own insist on an integer, and `CODE` is not derived from
  `NAME`: deriving it would mean inventing an encoding and then being stuck with it.
  - `added_in` now records a real release instead of a hardcoded 0, which was waiting on versioning
    that the packaging milestone was supposed to bring and did not.

- **Layer 2 gets its settings toggle**, which the brief asked for and which was the last thing on
  its list still missing. Settings > Sources and updates > Ageha itself: check quietly, check and
  tell me, or never check.
  - **It does not claim to control installation, because Ageha cannot.** On all three platforms
    the installer owns that -- MSIX, the macOS bundle updater, apt -- each configured at package
    time with no runtime switch. A checkbox claiming otherwise would be a lie. What the toggle
    controls is whether Ageha looks and whether it tells you, and the panel says so in those words.
  - "Never check" makes **no request at all**, rather than making one and hiding the answer.
  - Versions compare numerically. Lexicographically `"0.10.0" < "0.9.0"`, which would tell everyone
    on 0.9 to upgrade to 0.10 and everyone on 0.10 that they were ahead of it.
  - `AgehaVersion.CURRENT` is guarded by `:app:desktop:checkAppVersion` against the project
    version -- the same guard `:core:parsers` puts on its bundled parsers version, and verified to
    actually fail when the two disagree.

- **Sync, against a self-hosted kotatsu-syncserver.** The brief asked for this "if feasible" and
  to flag it if the protocol turned out to be Android-coupled. It is not: the protocol is four
  POSTs of JSON over OkHttp, and it is the Android app's *implementation* -- `AccountManager`,
  `ContentProviderClient`, `AbstractThreadedSyncAdapter` -- that is Android, none of which is on
  the wire. `docs/FINDINGS.md` 7 records the evidence; the question had been open since
  Milestone 1.
  - `:core:sync` -- the wire format, the HTTP client, the account store and the merge engine.
    Reading history, favourites and categories travel both ways, so a desktop and a phone stay in
    step through the same server the Android app uses.
  - **The payload carries tombstones, and a backup deliberately does not.** This is the whole
    reason `deleted_at` has been in the schema since Milestone 4 with nothing using it. A sync
    payload without tombstones cannot express a deletion, so every other device pushes the deleted
    row straight back.
  - **Tombstones are collected after the exchange, never before.** A tombstone the server has not
    seen is a deletion that has not propagated; collecting it early deletes the *deletion*, and the
    next sync restores what the user removed. The four-day window is the Android app's, matched on
    purpose -- two clients collecting on different schedules is how a deletion comes back.
  - An expired token is refreshed once and the retry carries the new one. Explicitly, rather than
    through an OkHttp `Authenticator` as upstream does: an `Authenticator` is a blocking callback
    on a connection thread, and re-entering suspending code from one deadlocks against a slow
    server.
  - Settings > Sync, and `cli sync [run|status|login|logout]`. `login` reads the password from the
    console with echo off and refuses a piped stdin, because a password given as an argument lands
    in shell history and in the process table.
  - Syncs at startup and on demand, **not on a timer**. The protocol is not incremental, so a
    desktop app left open all day would spend it re-sending the whole library.
  - Twelve tests drive the engine through a real socket against a server that speaks the protocol,
    covering the payload shape, tombstones both ways, 204-is-not-empty, token refresh, an
    unreachable host and a malformed reply.

- **Backup export.** Ageha writes the Android app's own backup format, so the migration runs both
  ways and a desktop library is no longer trapped in one file on one disk. `File > Export backup`,
  a button in Settings > Library, and `cli export [file]` for a scripted nightly copy.
  - **The archive is upstream's, not Ageha's own**, which is what makes it restore onto a phone as
    readily as onto another desktop -- and means the format has exactly one reader here, so the
    round trip is testable end to end rather than by inspection.
  - Written to a `.part` and renamed on success, the discipline downloads already use. A
    half-written archive that looks like a backup is discovered at restore time, which is the one
    moment there is nothing to fall back on.
  - Streamed in windows of 64 rows. A backup embeds the full manga record with its tags inside
    every history row and again inside every favourite row, so building one in memory first is the
    difference between an export that works on a real library and one that works on a small one.
  - **Every paged dump carries a primary-key tiebreaker, which upstream's do not.** `updated_at`,
    `created_at` and `sort_key` are all non-unique, and paging a tie with `LIMIT`/`OFFSET` lets one
    row appear in two windows and another in none. The count written looks right either way; the
    damage only shows on restore.
  - Tombstones stay home. Soft-deleted history and favourites are excluded, because the importer
    clears `deleted_at` on the way in -- exporting a tombstone would resurrect a deletion as data.
  - Only enabled sources are written, as upstream does. Ageha ships 1360 disabled, and the rest
    would be a copy of the source list.
  - Sections Ageha holds no data for -- `bookmarks`, `settings`, `scrobbling`, `statistics`,
    `saved_filters` -- are absent rather than written empty. Absent reads as "nothing to say";
    empty reads as "none of those", which for settings is a claim a restoring app could act on.

- **Cross-source search.** One title against every enabled source at once, four requests in flight,
  grouped by source and skipping sources that cannot take a search term at all.

- **Local comic archives, end to end.** File > Open comic archive reads a CBZ straight into the
  reader, through the same image pipeline as a remote source. Reading position persists for local
  files too, since the archive's id is derived from its absolute path.
- **Application notices.** Backup import and archive opening now report their outcome *in the
  window* instead of on stdout, which a windowed application does not have. The backup importer's
  full account -- restored, unsupported, unrecognised, and every dropped row with its reason --
  is shown monospaced and stays until dismissed.

### Changed

- **External tracking was cut from scope and replaced by Continue Reading.** Shikimori, AniList,
  MyAnimeList and Kitsu are gone from the brief and from `CLAUDE.md`. All four need an OAuth client
  registered by the project owner -- credentials that cannot be invented, committed to a public GPL
  repository, or tested without being real -- and writing them blind would have shipped four
  untested network clients. The question they were for, *where was I and what is next*, is answered
  from rows Ageha already stores, with no account and no network. See `docs/ARCHITECTURE.md` 7b.
- **Section shortcuts renumbered** to make room: Continue Reading is `Ctrl+2`, Explore `Ctrl+3`,
  Downloads `Ctrl+4`. Library stays `Ctrl+1` and Settings stays `Ctrl+,`.

### Fixed

- **The index of every real Android backup was silently unreadable.** The Android app writes each
  section through one `writeJsonArray` helper, `index` included, so the entry is an array holding
  one object. Ageha decoded it as a bare object and swallowed the failure with `getOrNull()`, so
  `result.index` was null for every genuine archive ever imported and the app version that wrote a
  file was never available. The tests passed throughout, because the fixture wrote the shape the
  parser wanted rather than the shape the app produces -- the bug was invisible from inside the
  project and only upstream's writer shows it. Both shapes are accepted now, the fixture writes the
  real one, and the failure to read an index is still not fatal: it is provenance, not data.

- **Webtoon mode never resolved page urls past the first few.** `resolveAround` was called from
  `goToPage` and nowhere else, and scrolling a continuous strip does not go through `goToPage` -- so
  a chapter opened, resolved five pages, and every page after that stayed a loading spinner for as
  long as it was open. Found by the new 200-page profile, which reported 5 pages resolved and 195
  pending *while posting excellent frame times*, because scrolling past placeholders is cheap.
- **Changing the reader mode failed for anything not in the library.** `preferences.manga_id` is an
  enforced foreign key and `setMode` wrote no manga row, so switching to webtoon mode on something
  opened from search, from a listing or from a local file threw a constraint violation instead of
  changing the mode. The twin of the history bug fixed above it; `setMode` now takes the manga and
  writes the row first, exactly as `savePosition` does.

- **The reader's final position could be lost on quit, or written into a closing database.** The
  flush used `scope.launch(NonCancellable)`, and `NonCancellable` is a `Job` -- so `launch` took it
  as the *parent* and detached the write from the application scope entirely. Shutdown's `join()`
  then had nothing to wait for and closed the database mid-transaction. Switching to a plain launch
  fixes that and opens the opposite hole: a coroutine cancelled before it is dispatched never runs
  at all. It is now `launch(start = UNDISPATCHED) { withContext(NonCancellable) { … } }`, which is
  both a child of the scope and already running before the cancellation lands. Found by the shell
  render, which began throwing `statement is closed` once the position write grew a second
  statement. Pinned by a test that fails for either mistake.

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

- **The sync password is stored in a file, and the app says so.** Desktop has no system keychain a
  plain JVM can reach without a native library per platform. The protocol refreshes an expired
  token by re-sending the password, so remembering it is what makes a startup sync silent. The file
  is restricted to its owner where the filesystem can express that, and leans on the user-profile
  ACL on Windows where it cannot -- the same protection the cookie jar and the database already
  have. It is deliberately *not* encrypted with a key stored beside it, which protects nobody.
  Declining to store it is supported and costs a prompt when the token expires.
- **A bare sync hostname is normalised to `https://`, where the Android app assumes `http://`.**
  That request carries a password.

- **`history.page_count` does not survive a backup.** It is Ageha's own column (schema 30) and the
  archive format has no field for it. Inventing one would produce a file the Android app does not
  understand, for a value that reads as "unknown" anyway and degrades to resuming the exact saved
  page. A round trip forgets it, exactly as an Android import arrives without it.
- **Local archives are exported like anything else.** A CBZ opened from disk gets a history entry
  whose url is an absolute path, and that path is unlikely to resolve on another machine. It is
  kept rather than filtered: the position survives when the path does match, and Continue Reading
  already shows an entry it cannot open as unavailable instead of failing. Dropping them would
  guarantee the loss that keeping them only risks.

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
- Fonts are **not** bundled, and now deliberately rather than pending. ~40MB of CJK faces would buy
  nothing on Windows or macOS, where Skia's per-glyph fallback already reaches past the chosen
  family and the system ships coverage anyway; it buys something only on a Linux machine with no
  CJK font, where the distribution's own font package is the right fix. The families stay an
  explicit preference chain resolved against what is installed, and Settings > Appearance names any
  script with no font **in red**, so a user seeing boxes knows it is a missing font and not a
  broken source.
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
