# Ageha (アゲハ)

A desktop manga reader for Windows, Linux and macOS. Kotlin, Compose Multiplatform, JDK 21.

Ageha is a desktop port of [Kotatsu-Redo](https://github.com/Kotatsu-Redo/Kotatsu-Redo), an Android
manga reader. It reads from the same 1360 manga sources by consuming the same parser library, so
keeping up with the web is a dependency bump rather than a rewrite.

**Status: early. Milestone 2 of 9.** There is no user interface yet. What exists is the source
layer and a CLI that proves it works on desktop.

## What works today

```
./gradlew :app:cli:installDist
./app/cli/build/install/cli/bin/cli sources
./app/cli/build/install/cli/bin/cli search MANGADEX "frieren"
./app/cli/build/install/cli/bin/cli details MANGADEX "sousou no frieren"
./app/cli/build/install/cli/bin/cli pages MANGADEX "sousou no frieren"
```

That is a real search, against a real site, through the real parser library, on a plain JVM.

## Architecture in one paragraph

Manga sources are never implemented here. They come from
[kotatsu-parsers-redo](https://github.com/Kotatsu-Redo/kotatsu-parsers-redo), a library of 1360
site parsers, consumed through JitPack. Ageha implements the host side of that library's
`MangaLoaderContext` and hides the whole thing behind a facade in `:core:parsers`. No other module
may import a parser-library type, and the build fails if one tries. See
[docs/ARCHITECTURE.md](docs/ARCHITECTURE.md); the investigation that produced it is in
[docs/FINDINGS.md](docs/FINDINGS.md).

## Known limitation: JavaScript

Around 20 of the 1360 sources need a real browser engine, because the parser library's
`evaluateJs` is a headless-browser contract rather than a script-engine one -- the scripts load a
page, wait for the site's own JavaScript to render, and read the resulting DOM. Ageha ships with
no JavaScript backend today, so those sources report a clear "needs the browser component"
message instead of failing vaguely. An optional, on-demand Playwright backend arrives in
Milestone 8. The other ~1340 sources are unaffected.

## Building

Needs JDK 21. The Gradle wrapper handles the rest.

```
./gradlew build          # compile and test
./gradlew test           # tests, excluding networked ones
./gradlew test -PwithNetwork   # include tests that hit live sources
```

## Licence and attribution

Ageha is licensed under the **GNU General Public License v3.0**. See [LICENSE](LICENSE).

It is a derivative work of GPL-3.0 projects by the Kotatsu authors and the Kotatsu-Redo
maintainers. Attribution and a summary of changes are in [NOTICE.md](NOTICE.md), as GPL-3.0
requires.
