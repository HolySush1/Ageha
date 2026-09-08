# Attribution and changes

Ageha is licensed under the GNU General Public License v3.0 and is a derivative work of the
projects below, each of which is also GPL-3.0. GPL-3.0 section 5 requires that modified works
carry prominent notices stating that they are modified and when. This file is that notice.

## Upstream projects

| Project | Copyright | Role in Ageha |
|---|---|---|
| [Kotatsu](https://github.com/KotatsuApp/Kotatsu) | Kotatsu authors | the original Android manga reader |
| [kotatsu-parsers](https://github.com/KotatsuApp/kotatsu-parsers) | Kotatsu authors | the original parser library |
| [Kotatsu-Redo](https://github.com/Kotatsu-Redo/Kotatsu-Redo) | Kotatsu-Redo maintainers | the fork Ageha reproduces |
| [kotatsu-parsers-redo](https://github.com/Kotatsu-Redo/kotatsu-parsers-redo) | Kotatsu-Redo maintainers | the parser library Ageha consumes |
| [kotatsu-dl](https://github.com/KotatsuApp/kotatsu-dl) | Kotatsu authors | reference for the non-Android host implementation |

## What Ageha changes

Ageha is a **desktop port**, begun 2026-09-03. It is not a redistribution of any upstream binary
and it contains no decompiled code.

- **The parser library is consumed unmodified**, as a published JitPack artifact
  (`com.github.Kotatsu-Redo:kotatsu-parsers-redo`). No parser is copied, vendored or reimplemented
  here. Source fixes belong upstream.
- **The Android host layer is reimplemented for the JVM.** `MangaLoaderContext` is implemented
  against AWT, OkHttp and a pluggable JavaScript backend, in place of Android's `Bitmap`,
  `WebView` and `SharedPreferences`.
- **JavaScript evaluation differs by necessity.** Android backs `evaluateJs` with a WebView.
  Desktop has none, so Ageha routes it to an optional, pluggable backend and degrades explicitly
  when none is installed.
- **The user interface is new**, written in Compose Multiplatform for Desktop rather than Android
  Views, and is not a port of the Android layouts.
- **The database schema follows the Android app's** (Room, starting at version 28) so that backup
  files from Kotatsu-Redo can be imported.

## Bundled fonts

Ageha ships three typefaces as classpath resources. All are under the
[SIL Open Font Licence 1.1](https://openfontlicense.org), which is compatible with GPL-3.0: the OFL
covers the font files only and places no condition on software that merely embeds them. Each
licence text ships beside the fonts it covers, and none of the files has been modified.

| Font | Copyright | Licence | Role | Shipped to |
|---|---|---|---|---|
| [Archivo](https://github.com/Omnibus-Type/Archivo) | The Archivo Project Authors | OFL-1.1 | interface, titles, body | every platform |
| [JetBrains Mono](https://github.com/JetBrains/JetBrainsMono) | The JetBrains Mono Project Authors | OFL-1.1 | counts, hosts, labels, page positions | every platform |
| [Noto Sans CJK JP](https://github.com/notofonts/noto-cjk) | The Noto Project Authors | OFL-1.1 | CJK fallback where no system font covers it | Built and tested, not shipped |

Archivo and JetBrains Mono live in `core/designsystem/src/main/resources/app/ageha/font/` with
`OFL-Archivo.txt` and `OFL-JetBrainsMono.txt`. They are bundled rather than requested from the
system because Ageha's visual design specifies them by name and weight; see `docs/DESIGN.md` §3.

Structural comparisons against upstream sources, and the reasoning drawn from them, are recorded
in `docs/FINDINGS.md`.
