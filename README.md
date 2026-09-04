# Ageha (アゲハ)

A desktop manga reader for Windows, Linux and macOS. Kotlin, Compose Multiplatform, JDK 21.

Ageha is a desktop port of [Kotatsu-Redo](https://github.com/Kotatsu-Redo/Kotatsu-Redo), an Android
manga reader. It reads from the same 1360 manga sources by consuming the same parser library, so
keeping up with the web is a dependency bump rather than a rewrite.

**Status: milestone 9 of 9.** The application runs: library, source browsing, a reader, downloads,
settings and Android backup import. Tracking is the one deliverable not built — it needs OAuth
clients registered per service, which is explained in
[docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) §7b.

![The design system](docs/design-gallery.png)

## Running it

```
./gradlew :app:desktop:run             # the application
./gradlew :app:desktop:run --args=--gallery   # the design system gallery
```

## What it does

- **Library** — shelves with live counts, a grid that reflows with the window, continue-reading,
  filter and sort.
- **Explore** — all 1360 sources, enabled individually, with paged browsing and search per source.
- **Reader** — paged left-to-right and right-to-left, double-page spreads with cover-offset
  handling, continuous webtoon, zoom and pan, four fit modes, auto-hiding chrome, and full keyboard
  control. Reading position is restored exactly.
- **Downloads** — offline chapters written as ordinary CBZ, readable in any comic reader.
- **Settings** — themes, reader defaults, and the parsers update engine: check, roll back, pin.
- **Backup import and export** — the Android app's own format, both directions. Import reports
  exactly what it could not restore; export writes a file that also restores back onto a phone.
- **Sync** — reading history, favourites and categories against a
  [kotatsu-syncserver](https://github.com/KotatsuApp/kotatsu-syncserver) you run yourself, using
  the same protocol as the Android app. There is no Ageha-hosted service and no default address.

## The command line

Everything the UI does is also reachable without it, which is how the source layer is tested.

```
./gradlew :app:cli:installDist
./app/cli/build/install/cli/bin/cli sources
./app/cli/build/install/cli/bin/cli search MANGADEX "frieren"
./app/cli/build/install/cli/bin/cli smoke --sample 25   # exercise real sources end to end
./app/cli/build/install/cli/bin/cli import backup.zip   # import an Android backup
./app/cli/build/install/cli/bin/cli export              # write one, dated, to the current directory
./app/cli/build/install/cli/bin/cli sync                # sync with a kotatsu-syncserver
```

## Migrating from the Android app

Export a backup from Kotatsu-Redo and use **File → Import Android backup**, or `cli import
<backup.zip>`. Library, favourites, categories, reading history and reading positions come across;
the import says exactly what it could not restore rather than reporting success over a partial one.

It runs the other way too. **File → Export backup**, or `cli export`, writes the same format, so a
desktop library restores onto a phone as readily as it arrived from one — and, more to the point,
copies to another machine or a backup drive. Downloaded chapters are not in the archive; they are
ordinary CBZ files, and copying the folder moves them.

## Sync

Optional, off until configured, and pointed at a server you host. **Settings → Sync**, or
`cli sync login <address> <email>`. Ageha speaks the same protocol as the Android app, so a phone
and a desktop stay in step through one server; reading history, favourites and categories travel
both ways, including deletions.

Two things worth knowing before you use it. Ageha syncs at startup and when asked, **not on a
timer** — the protocol is not incremental, so a periodic sync would re-send your whole library each
time. And if you let it remember your password, that password is stored in a file in Ageha's data
folder, protected by your user account and nothing stronger: desktop has no keychain a plain JVM
can reach without shipping a native library for every platform. Declining is supported, and costs a
prompt whenever the session expires.

## Architecture in one paragraph

Manga sources are never implemented here. They come from
[kotatsu-parsers-redo](https://github.com/Kotatsu-Redo/kotatsu-parsers-redo), a library of 1360
site parsers, consumed through JitPack and loaded in an isolated classloader so a newer build can
replace it without a new version of Ageha. Ageha implements the host side of that library's
`MangaLoaderContext` and reaches it through a narrow typed bridge. No other module may import a
parser-library type, and the build fails if one tries. See
[docs/ARCHITECTURE.md](docs/ARCHITECTURE.md); the investigation behind it is in
[docs/FINDINGS.md](docs/FINDINGS.md), and the visual design in [docs/DESIGN.md](docs/DESIGN.md).

## Known limitation: JavaScript

Ageha ships a sandboxed [Rhino](https://github.com/mozilla/rhino) engine, which covers the ~257
sources that fall back to an anti-bot script. Around **20** of the 1360 need a real *browser*
engine, because the parser library's `evaluateJs` is a headless-browser contract rather than a
script-engine one — the scripts load a page, wait for the site's own JavaScript to render, and read
the resulting DOM. Those sources report a clear "needs the browser component" message with an
offer to install it, rather than failing vaguely. The other ~1340 are unaffected.

## Building

Needs JDK 21. The Gradle wrapper handles the rest.

```
./gradlew build                 # compile and test
./gradlew test                  # tests, excluding networked ones
./gradlew test -PwithNetwork    # include tests that hit live sources
./gradlew :app:desktop:renderShell   # draw the real app headlessly, without a display
```

Brand assets and colour tokens are generated, not hand-edited:

```
./gradlew :tools:brandkit:generateBrandAssets
```

## Packaging

Ageha's installers are built with **[Hydraulic Conveyor](https://www.hydraulic.dev/)**, which
produces signed, self-updating packages for Windows, macOS and Linux from a single machine.
Conveyor is free for projects under an OSI-approved licence; Ageha is GPL-3.0, so it qualifies.

```
conveyor make site                                  # every platform
conveyor -Kapp.machines=windows.amd64 make windows-installer
```

The configuration is [conveyor.conf](conveyor.conf); the release process, including the current
lack of code signing and the routes to fixing that, is in [docs/RELEASING.md](docs/RELEASING.md).

**Releases are currently unsigned.** Windows SmartScreen and macOS Gatekeeper will warn on first
run. [docs/RELEASING.md](docs/RELEASING.md) explains how to get past it and what signing would
take.

## Keeping up with the web

Sources break weekly and the fix is almost always upstream, so this is automated. Every six hours a
workflow checks for a new parsers build, runs the full suite against it, and opens a pull request
that merges itself on green. Nightly, another exercises a random sample of real sources end to end.
[docs/UPDATING.md](docs/UPDATING.md) explains the whole thing for someone who has forgotten how it
works.

## Licence and attribution

Ageha is licensed under the **GNU General Public License v3.0**. See [LICENSE](LICENSE).

It is a derivative work of GPL-3.0 projects by the Kotatsu authors and the Kotatsu-Redo
maintainers. Attribution and a summary of changes are in [NOTICE.md](NOTICE.md), as GPL-3.0
requires.
