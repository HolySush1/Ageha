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

- `SourceFailure.Blocked` and HTTP status classification, so a 403 from bot protection no longer
  reports as an unreachable network.
- The HTTP stack is now shut down on close. OkHttp holds a dispatcher pool, live sockets and an
  open cache journal, and the open journal prevents an update from replacing the build it is
  updating on Windows.
- `selfCheck` no longer swallows `LinkageError`. `runCatching` catches `Error` too, which reported
  a wholesale version mismatch as "25 sources are broken" and pointed at the wrong thing entirely.

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
