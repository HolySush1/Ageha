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

### Notes

- Sources are addressed and persisted **by name string, never by enum or ordinal**.
  `MangaParserSource` is generated at build time by KSP, so its constants differ between parser
  builds.
- Ageha ships with **no JavaScript backend**. Around 20 of 1360 sources need one and report a
  specific, actionable failure rather than a generic error.
