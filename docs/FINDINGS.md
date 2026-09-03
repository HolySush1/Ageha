# FINDINGS — Ageha investigation (Step 1)

Investigation date: **2026-09-03**.
No application code has been written. This document and `ARCHITECTURE.md` are the Milestone 1 deliverable.

## 0. What was actually read

Cloned into `./reference/` (gitignored):

| Repo | Commit read | Why |
|---|---|---|
| `Kotatsu-Redo/kotatsu-parsers-redo` | `434030d4811c83dff6191437d8170721379b436d` (2026-08-24) | the parser library we consume |
| `Kotatsu-Redo/Kotatsu-Redo` | HEAD (pushed 2026-08-25) | the Android app we are reproducing |
| `KotatsuApp/kotatsu-dl` | HEAD | **the non-Android `MangaLoaderContext` reference the brief asked me to find** |

`kotatsu-dl` is the "Non-Android implementation" the parsers README links to. It is a real, shipping, pure-JVM consumer of this exact library — the single most useful artifact in this investigation. It is also **behind the fork**, which turns out to matter a great deal (§4).

Licence: all three are **GPL-3.0** (confirmed via the GitHub API `spdx_id` field and the `LICENSE` files). Ageha must be GPL-3.0, must credit upstream, and must document changes. No obstacle.

---

## 1. JitPack coordinate — confirmed, and the brief's guess needs a correction

**Use `com.github.Kotatsu-Redo:kotatsu-parsers-redo:434030d481`.**

Evidence:

- `GET https://jitpack.io/api/builds/com.github.Kotatsu-Redo/kotatsu-parsers-redo` returns a full build list, and `"434030d4811c83dff6191437d8170721379b436d" : "ok"` — our HEAD builds green there.
- The fork's README is an **unmodified copy of upstream's** and still documents `com.github.KotatsuApp:kotatsu-parsers`. It is not a source of truth for the fork. (This is why the brief was right to say "don't guess".)

Two things worth flagging, because both would have bitten us:

**a) The Android app pins a different, stale coordinate.** `reference/Kotatsu-Redo/app/build.gradle:135` reads:

```groovy
implementation("com.github.clquwu:kotatsu-parsers-redo:$parsersVersion") {
    exclude group: 'org.json', module: 'json'
}
```

`clquwu/kotatsu-parsers-redo` returns **HTTP 301 Moved Permanently** from the GitHub API — the repo was transferred to the `Kotatsu-Redo` org. JitPack still serves already-built artifacts under the old coordinate (`434030d481` is `ok` there too), but it is a redirect-backed ghost. New commits should be expected to build under the org coordinate, not the personal one. **We track the org.** Our `parsers-watch.yml` polls `Kotatsu-Redo/kotatsu-parsers-redo`.

**b) There are no version tags at all.** `git ls-remote --tags` returns nothing, and JitPack's `/latestOk` endpoint returns `{ }`. Every "version" is a commit SHA (`434030d481`) or a `master-<sha>-1` snapshot. Consequences we have to design around:

- There is no semver, so **no ordering and no compatibility signal in the version string.** "Newer" can only mean "later commit on master".
- Layer 1's update check cannot be "compare version numbers". It must be "resolve `master`'s current SHA via the GitHub API, ask JitPack to build it, poll until `ok`". That is a materially different design from the one the brief sketches, and it is why the compatibility gate is not optional.
- `jitpack.yml` pins `openjdk17`, and the library sets `jvmToolchain(17)`. We target JDK 21, which is fine (17 bytecode runs on 21), but our own toolchain must not be *below* 17.

## 2. The dependency surface we inherit

From `gradle/libs.versions.toml` in the fork:

| Dep | Version the library builds against | Note |
|---|---|---|
| Kotlin | 2.2.10 | our build should match or exceed |
| kotlinx-coroutines | 1.10.2 | |
| **OkHttp** | **5.1.0** | the brief's OkHttp rule is not a preference — see below |
| Okio | 3.16.0 | |
| **jsoup** | 1.21.2 | declared `api`, so **jsoup types are in the public API** |
| org.json | 20240303 | Android excludes it; **desktop must include it** |
| androidx.collection | 1.5.0 | yes, an AndroidX artifact — it is pure-JVM and resolves fine off Maven Central |

Three consequences:

- **The OkHttp rule in `CLAUDE.md` is structural, not stylistic.** `MangaLoaderContext` declares `public abstract val httpClient: OkHttpClient` and `public abstract val cookieJar: CookieJar` — literal OkHttp types. Worse, `MangaParser` *extends `okhttp3.Interceptor`*: every parser is an interceptor you are expected to install into the client for its own requests. There is no seam where Ktor could be substituted. Any skill that suggests otherwise is wrong about this project, and I will say so each time it comes up.
- `kotatsu-dl` pins OkHttp **4.12.0** against an older parsers build. The fork is on **5.x**. We build against 5.1.0 and do not copy `kotatsu-dl`'s Gradle file verbatim.
- `androidx.collection` and `org.json` must be on the classpath of the **dynamically loaded** parser JAR too (§6).

## 3. `MangaLoaderContext` — the full surface, member by member

Source: `src/main/kotlin/org/koitharu/kotatsu/parsers/MangaLoaderContext.kt`. It is an `abstract class`, not an interface — so we subclass it, and **new abstract members added upstream are a compile break, while new `open` members are silently inherited**. That asymmetry drives the Layer 1 compatibility gate.

### Must implement (abstract)

| Member | Desktop difficulty | Plan |
|---|---|---|
| `val httpClient: OkHttpClient` | trivial | our `:core:network` stack |
| `val cookieJar: CookieJar` | easy | persistent jar on disk; `kotatsu-dl`'s `InMemoryCookieJar` is the shape, we add persistence |
| `getConfig(source): MangaSourceConfig` | trivial | `MangaSourceConfig` is a **one-method interface**: `operator fun <T> get(key: ConfigKey<T>): T`. Back it with our settings store. |
| `getDefaultUserAgent(): String` | easy | Android returns the *WebView's* real UA. We must return the UA of whatever browser we actually drive, or sites will fingerprint a mismatch between the UA and our TLS/JS behaviour. |
| `evaluateJs(script)` *(deprecated)* | **hard** | §4 |
| `evaluateJs(baseUrl, script, timeout)` | **hardest thing in the project** | §4 |
| `redrawImageResponse(response, redraw)` | easy | `ImageIO.read` → redraw → re-encode. `kotatsu-dl` does exactly this in ~8 lines. |
| `createBitmap(width, height)` | easy | `BufferedImage`. |

The `Bitmap` abstraction is deliberately tiny and is *not* an Android type:

```kotlin
public interface Bitmap {
    public val width: Int
    public val height: Int
    public fun drawBitmap(sourceBitmap: Bitmap, src: Rect, dst: Rect)
}
```

Three members plus a `Rect` data class. This is descrambling support for sources that ship shuffled image tiles. A `BufferedImage`-backed implementation is maybe 30 lines. **Not a risk.**

### Have defaults, may override (`open`)

`encodeBase64` / `decodeBase64` (the base class already defaults to `java.util.Base64`, so the JVM default is correct), `getPreferredLocales()`, `requestBrowserAction()`, `requestCloudflareVerification()`, `interceptWebViewRequests(url, script, timeout)`, `interceptWebViewRequests(url, config)`, `captureWebViewUrls(pageUrl, urlPattern, timeout)`.

The last four all throw `UnsupportedOperationException` by default. **We can ship without them and lose exactly the sources that need them** — which is a legitimate staged plan, and I would rather do that than block the whole app on a browser integration.

### Non-abstract helpers we get free

`newParserInstance(source)`, `newLinkResolver(link)`.

---

## 4. JavaScript evaluation — the central finding, and it is worse than the brief assumed

### The brief's framing needs updating

The brief says "the Android app backs JS execution with a WebView; desktop has no WebView", implying we need a JS engine. **That is not what the contract requires.** I read the call sites. `evaluateJs(baseUrl, script, timeout)` is a *headless browser* contract, not a *script engine* contract.

Representative call site — `site/en/BatCave.kt:150`:

```javascript
(() => new Promise((resolve) => {
    const finish = () => resolve(document.documentElement ? document.documentElement.outerHTML : "");
    if (document.readyState === "complete") { setTimeout(finish, 200); }
    else { window.addEventListener("load", () => setTimeout(finish, 200), { once: true }); }
    setTimeout(finish, 3000);
}))();
```

That script needs, at minimum: a live `document` for `baseUrl`, a real page-load lifecycle (`readyState`, the `load` event), `window`, timers, and a JS runtime the host polls until it yields a non-empty value. `ComixParser` additionally waits on `document.querySelector('script#initial-data')` being populated — i.e. it waits for the **site's own client-side JS to execute and render**. `WebViewHelper.getLocalStorageValue` reads `window.localStorage` for a specific origin.

**A JS engine cannot satisfy this.** Nashorn, GraalJS, Rhino and QuickJS have no DOM, no origin, no network stack, no localStorage. Bolting a jsdom equivalent onto a JVM JS engine is the "hand-write a scraper" failure mode wearing a different costume.

**Therefore `kotatsu-dl`'s approach does not port.** It uses standalone Nashorn:

```kotlin
override suspend fun evaluateJs(baseUrl: String, script: String): String? =
    scriptEngineManager.getEngineByName("nashorn").eval(script)?.toString()
```

Note the signature: **two parameters**. The fork's is `evaluateJs(baseUrl, script, timeout)` — three. `kotatsu-dl` was written against an older upstream where `baseUrl` was ignorable and the scripts were pure computation. The Redo fork has since moved this method from "run some JS" to "drive a browser", and added `interceptWebViewRequests` / `captureWebViewUrls` / `requestCloudflareVerification` on top. **`kotatsu-dl` is a correct model for everything except JS, and a trap for JS specifically.**

### How many sources actually need it

Measured at commit `434030d481` (`.github/summary.yaml` reports **1360 total sources**):

| Tier | What it needs | Sources |
|---|---|---|
| **A — nothing** | plain OkHttp + jsoup | ~1080 (~79%) |
| **B — pure JS, no DOM** | the deprecated `evaluateJs(script)` form | up to **257** (`MangaReaderParser` subclasses), **conditional** |
| **C — real browser** | page load + in-page JS, request interception, localStorage, or interactive Cloudflare | **~20 concrete sources (~1.5%)** |

Tier C, exhaustively (the union of `evaluateJs` with a baseUrl, `interceptWebViewRequests`, `captureWebViewUrls`, `requestBrowserAction`, `requestCloudflareVerification` and `WebViewHelper`):

`ComickFun`, `Koharu`, `LunarAnime`, `AllManga`, `BatCave`, `Comikuro`, `Comix`, `Kagane`, `MangaCloud`, `MangaGeko`, `Mangago`, `ReadComicOnline`, `YaoiMangaOnline`, `MadaraDex`, `RaijinScans`, `LeitorDeManga`, `MangaLivre`, `YomuMangas`, `ZenManga`, `YuriGarden`, plus the 4 `LibSocialParser` subclasses (localStorage only).

Tier B deserves emphasis because it looks scarier than it is. `MangaReaderParser.kt:397` is a **fallback path**, not the happy path:

```kotlin
private suspend fun Document.getNetShieldCookie(): Cookie? = runCatchingCancellable {
    val script = select("script").firstNotNullOfOrNull { s -> s.html().takeIf { it.contains("slowAES.decrypt") } }
        ?: return@runCatchingCancellable null
    val min = webClient.httpGet("https://$domain/min.js").parseRaw()
    val res = context.evaluateJs(min + "\n\n" + script.replace(Regex("document.cookie\\s*=\\s*"), "return "))
```

It only fires when a site serves a "NetShield"/slowAES anti-bot interstitial. The JS is pure AES computation with the `document.cookie` assignment rewritten to a `return`. **QuickJS or GraalJS handles this exactly.** So 257 sources need a JS engine *sometimes*, and none of them need a browser for it.

That split is the whole design: **a fast, always-loaded, sandboxed JS engine for Tier B, and a heavyweight, lazily-started browser for Tier C.**

### The options, costed

#### Tier C candidates

**C1. JCEF via `me.friwi:jcefmaven:135.0.20`** — *recommended, when we get there*

- Real Chromium in-process. Full DOM, localStorage, request interception via `CefRequestHandler`, and a visible window we can show the user for Cloudflare — which is exactly what `requestBrowserAction` / `requestCloudflareVerification` are for, and what the Android app does by attaching its WebView to the foreground Activity.
- `jcefmaven` downloads and unpacks native CEF at first run, so it works on a **stock JDK 21** — we do *not* need the JetBrains Runtime. (Compose Desktop does not ship JBR; assuming JCEF "comes with the runtime" is a common and wrong assumption.)
- One object serves both headless evaluation *and* the interactive captcha window.
- **Cost:** ~150–250 MB of native CEF per platform. This is the real price. Conveyor's delta updates soften it after the first install, but the initial download roughly triples. It also adds three native binary sets to the release matrix.
- **Risk:** JCEF's off-screen-rendering interop with Compose Desktop is the fiddliest part. Mitigated by the fact that only the *interactive* captcha window needs interop at all; headless evaluation needs none.

**C2. Playwright for Java (`com.microsoft.playwright:playwright:1.52.0`)**

- Best-in-class API for exactly this: `page.navigate(url)`, `page.evaluate(script)` — and Playwright's `evaluate` **awaits returned Promises natively**, which is precisely the shape every Tier C script has. Request interception via `page.route`. localStorage via `page.evaluate`.
- **Cost:** ships an external browser (~120–180 MB) *plus* a Node.js driver process. That is a second runtime inside a Conveyor-packaged JVM app, and it makes packaging materially harder — Conveyor is very good at JVM apps and is not designed to babysit a Node sidecar. Downloading browsers at first run (Playwright's default) is a poor desktop-app experience and will trip corporate proxies.
- **Verdict:** the nicest API, the worst packaging story. Strong fallback if C1's Compose interop turns out to be a swamp.

**C3. Selenium/CDP against a browser the user already has**

- Near-zero download. Attach to installed Chrome/Edge/Chromium.
- **Cost:** we do not control the browser version, it may not exist at all (common on Linux), driver/browser version skew is a permanent support burden, and driving the user's *default profile* is invasive — while launching a separate profile loses precisely the cookies that make Cloudflare pass.
- **Verdict:** acceptable as an opt-in fallback ("use my installed Chrome"), not as the default.

**C4. Ship nothing; disable Tier C sources**

- 1340 of 1360 sources work. The ~20 that do not are marked unavailable with an explanatory message.
- **Cost:** zero, but it includes some popular sources (Mangago, ComickFun, ReadComicOnline).
- **Verdict:** this is the correct **Milestone 2–8 posture**. Ship without a browser, prove everything else, then add the browser as an isolated optional module in its own milestone.

#### Tier B candidates

**B1. QuickJS via `io.webfolder:quickjs:1.1.0`** — *recommended*

- **This is what the parsers library's own test suite uses.** `MangaLoaderContextMock`, in the library's `src/test`, implements `evaluateJs` as `QuackContext.create().use { it.evaluate(script)?.toString() }`. If upstream's tests pass with QuickJS, our Tier B path passes with QuickJS by construction. That is the strongest fidelity argument available.
- ~1 MB, fast startup, sandboxed by default (no filesystem, no network).
- **Risk:** it is a native library and 1.1.0 is not recently updated. Worth confirming it loads on Windows and macOS ARM before committing.

**B2. GraalJS (`org.graalvm.polyglot:js:24.2.1`)**

- Pure-JVM option available (interpreted mode on a stock JDK), actively maintained, ECMAScript 2024, excellent sandboxing controls.
- **Cost:** ~50 MB of dependencies, and interpreted-mode performance is well below QuickJS for short scripts. Irrelevant here — these scripts run once per anti-bot challenge.
- **Verdict:** the safer long-term maintenance bet; QuickJS is the higher-fidelity bet. Either is reversible behind our `JsRuntime` interface.

**B3. Nashorn** — rejected. Removed from the JDK, the standalone artifact is minimally maintained, ES5-only. `kotatsu-dl` uses it only because its parsers predate modern syntax in these scripts.

### Recommendation

A three-tier, lazily-initialised `:core:js` with a `JsRuntime` interface and swappable backends:

1. **`NoJsRuntime`** (default, Milestones 2–4) — throws `UnsupportedOperationException`, exactly like the base class does. Tier C sources surface as "unavailable on this platform". Zero download.
2. **`QuickJsRuntime`** — satisfies `evaluateJs(script)`. Small, matches upstream's own tests, unlocks the Tier B fallback path for 257 sources.
3. **`BrowserRuntime` (JCEF)** — an **optional, user-installed component**, downloaded on demand the first time a Tier C source is used, behind a clear "this source needs a browser engine (~200 MB). Download?" prompt. Satisfies everything, including the interactive Cloudflare window.

Making the browser opt-in and on-demand is the design decision I feel most strongly about: it keeps the base installer small, keeps the release matrix simple, keeps 98.5% of sources working, and it is honest with the user about why a handful of sources cost more.

### One thing I could not fully resolve

Android's `WebView.evaluateJavascript` returns the **JSON-encoded** result and does **not** await Promises — a Promise stringifies to `{}`. Yet every Tier C script returns a Promise, and `WebViewExecutor` polls the script every second until the result is non-blank (and `{}` is not blank). I cannot reconcile these from source alone; either modern Chromium WebView resolves the promise, or these sources are partly broken on Android too. Two implications:

- The desktop implementation must return a **JSON-encoded string** — parsers call a locally-defined `decodeWebViewString()` to strip it (`LunarAnime.kt:708`, `Comikuro.kt:384`). Getting this wrong breaks those parsers subtly rather than loudly.
- Whichever browser we pick must **await promises**, which Playwright does natively and JCEF needs explicit help with (CDP `Runtime.evaluate` with `awaitPromise: true`). I would want to verify this against a live source before calling Tier C done.

---

## 5. Which parser APIs are stable, and which will churn

### Stable — safe for the facade to depend on

- `MangaParser`'s core verbs: `getList(offset, order, filter)`, `getDetails(manga)`, `getPages(chapter)`, `getPageUrl(page)`, `getFilterOptions()`, `getRequestHeaders()`, `domain`, `source`.
- `MangaPage` (4 fields), `MangaTag` (3 fields), `MangaChapter` — small, boring, no churn signal.
- `MangaLoaderContext`'s HTTP surface: `httpClient`, `cookieJar`, `getConfig`, `getDefaultUserAgent`.
- `Bitmap` / `Rect`.
- `MangaSourceConfig` — one method. Cannot really churn.

### Actively churning — the facade must isolate these

- **`Manga` is mid-migration.** It carries a whole `@Deprecated("Use other constructor")` constructor plus deprecated `author`, `altTitle` and `isNsfw` accessors, superseded by `authors: Set<String>`, `altTitles: Set<String>` and `contentRating: ContentRating?`. The old shape will be deleted. **Our domain model must not mirror `Manga` field-for-field** — map it, in one place, in `:core:parsers`.
- **Search and filtering are mid-migration too.** `getList(query: MangaSearchQuery)` and `searchQueryCapabilities` are `@Deprecated("Too complex")`, replaced by `getList(offset, order, filter)` and `filterCapabilities`. Use only the new pair.
- **`evaluateJs` gained a parameter** between the version `kotatsu-dl` targets and the fork. It will keep moving. Adapt it behind our own interface.
- **`interceptWebViewRequests` / `captureWebViewUrls` / `InterceptionConfig` are brand new and unpolished** — the Android implementation for them contains a comment reading `// This is the key part that was missing!`. Expect signature changes.
- **`resolveLink` is `@InternalParsersApi`.** Do not touch it.

### The finding that most shapes Layer 1

**`MangaParserSource` does not exist in the source tree.** It is **generated at build time by KSP** (`kotatsu-parsers-ksp/.../ParserProcessor.kt`) from `@MangaSourceParser` annotations, together with a `MangaParserSource.newParser(context)` factory. The generated enum's constants — and therefore the complete list of sources — **differ between every JAR build.**

Everything follows from this:

- We must **never** compile against a named constant like `MangaParserSource.MANGADEX`. Any such reference becomes a `NoSuchFieldError` the first time a source is renamed or removed upstream.
- The facade enumerates sources **reflectively** (`values()`), reading `title`, `locale`, `contentType` and `isBroken` off each constant.
- Sources are persisted by their **enum name string**, and an unknown name on load must degrade to "source unavailable", never crash.
- This directly satisfies the brief's requirement that "a new source it doesn't recognise should still be usable through the generic interface": because we never enumerate statically, a new source is automatically usable. Good news — but only if we get it right on day one. Retrofitting it later means touching every persisted row.
- `isBroken` is already carried on the enum. Surface it in the UI; upstream is telling us which sources are dead.

Also relevant: every parser is wrapped in `MangaParserWrapper` by the generated factory, so we never see raw parser instances.

---

## 6. Layer 1 (dynamic parser loading) — what the investigation changes

The brief's design is sound. Three corrections from evidence:

1. **There are no versions to compare** (§1). The update check is SHA-based against `Kotatsu-Redo/kotatsu-parsers-redo`'s `master`, then a JitPack build poll. Store the resolved SHA plus a checksum, not a version number.
2. **The classloader must share more than the brief assumes** — but not by sharing parser types. `MangaLoaderContext` is an abstract class we *subclass*, so our subclass and the JAR's base class must be the *same* `Class` object or we get `LinkageError`. The conclusion drawn here at Milestone 1, that `org.koitharu.kotatsu.parsers.**` should therefore be parent-first, **was tried and abandoned at Milestone 3**: `LinkResolver` transitively freezes `AbstractMangaParser`, the generated enum shares a package with the models, and sharing the models means every `Manga` change needs an app release. The subclass now lives in the child alongside the library, and the two sides meet at an Ageha-typed bridge instead. See `ARCHITECTURE.md` §4.1.
3. **Transitive deps must be resolved, not assumed.** `org.json` and `androidx.collection` are `implementation` dependencies of the library. Android excludes `org.json` because the platform provides it; **we must not.** Confirmed the hard way at Milestone 3: the first isolated load failed with `NoClassDefFoundError: androidx/collection/SparseArrayCompat`. Resolving the POM rather than listing them by hand also turned up **`androidx.annotation`**, which nothing in any documentation mentions — which is the argument for resolving rather than listing, in one artifact.

The compatibility gate the brief asks for maps cleanly onto §5: reflectively assert that `MangaLoaderContext` declares no abstract member we do not override, and that each facade-critical method signature still exists. Both are cheap and catch exactly the failure modes above.

---

## 7. Scope of the Android app (skim)

`app/src/main/kotlin/org/koitharu/kotatsu/`: `alternatives, backups, bookmarks, browser, core, details, download, explore, favourites, filter, history, image, list, local, main, picker, reader, remotelist, scrobbling, search, settings, stats, suggestions, sync, tracker, widget`.

**Database:** Room, `DATABASE_VERSION = 28`, 17 entities (`MangaEntity, TagEntity, HistoryEntity, MangaTagsEntity, ChapterEntity, FavouriteCategoryEntity, FavouriteEntity, MangaPrefsEntity, TrackEntity, TrackLogEntity, SuggestionEntity, BookmarkEntity, ScrobblingEntity, MangaSourceEntity, StatsEntity, LocalMangaIndexEntity, SourcePresetEntity`) and 27 hand-written migrations. Staying schema-compatible is very achievable if we start at v28 and never write our own migrations 1–27.

**Backup format — good news, it is simple.** A zip whose entries are named by `BackupSection`: `index, history, categories, favourites, settings, reader_grid, bookmarks, sources, scrobbling, statistics, saved_filters`. Each entry is `kotlinx.serialization` JSON. `index` carries `app_id`, `app_version` and `created_at`. Import is a zip read plus a JSON decode into about ten DTOs — a day of work, not a research project. This is the migration path and it is cheap. **I recommend building it early** (Milestone 4) rather than late; it is the difference between "try it" and "switch to it".

**Sync server:** a `sync/` package exists. I have not read the protocol closely enough to say whether it is Android-coupled. Flagging as an open question, not a blocker.

**Scrobbling:** `scrobbling/` covers the trackers named in the brief. All are OAuth plus REST — no Android coupling expected.

---

## 8. Decisions -- resolved 2026-09-03

All four were answered. Recorded here because Milestone 3 onward depends on them.

| # | Question | Decision |
|---|---|---|
| 1 | Tier C browser | **Playwright for Java**, optional and downloaded on demand. Not JCEF. |
| 2 | Tier B engine | **QuickJS** (`io.webfolder:quickjs`). |
| 3 | Room or SQLDelight | **Room 2.7+ (JVM)**, staying close to the Android schema. |
| 4 | Backup import timing | **Immediately after the database milestone, before any UI.** It validates the schema. |

Two consequences were called out with the decisions and are carried here so they are not
rediscovered later.

### Playwright and Conveyor (decision 1)

Choosing Playwright means the packaging problem in 4/C2 is now ours to solve rather than to avoid.
Concretely, for Milestone 8/9:

- Playwright for Java is **not a self-contained JVM dependency**. It ships a Node.js driver process
  and downloads browser binaries at first use. Conveyor packages JVM applications and bundles a
  jlink-optimised JVM; it has no notion of a Node sidecar or of a second binary tree that appears
  after installation.
- So the browser component must be **outside the Conveyor package**, installed into
  `AgehaPaths.dataDir` on demand and versioned by Ageha rather than by the installer. Conveyor
  packages Ageha; Ageha packages the browser.
- That in turn means Ageha owns what Conveyor would otherwise have handled: integrity checking the
  download, keeping it working across app updates, and removing it on uninstall. Conveyor does not
  clean up files it never installed, so an uninstall that leaves ~200 MB of browser behind is the
  default outcome unless we handle it.
- `PLAYWRIGHT_BROWSERS_PATH` must be pinned at our own directory. Left unset, Playwright writes to
  a per-user cache outside anywhere Ageha manages.
- The driver is a **child process**, so it needs a supervised lifecycle: killed on exit, killed on
  crash, and never left orphaned. A leaked Node process holding a browser open is both a memory
  leak and, to the user, an unexplained background process.

### QuickJS native packaging (decision 2)

`io.webfolder:quickjs` is a JNI binding, so the artifact carries **per-platform native libraries**
and is not the pure-JVM dependency the rest of the stack is:

- Every release platform needs its native present and loadable: Windows x64, macOS x64, macOS
  **aarch64** (Apple Silicon), Linux x64. macOS aarch64 is the one most likely to be missing --
  version 1.1.0 predates Apple Silicon being universal, and the brief's own advice applies: verify
  before committing, do not assume.
- Because this is a compiled native, the failure mode is `UnsatisfiedLinkError` at load, on the
  user's machine, on a platform we did not test. `:core:js` must therefore treat "QuickJS failed to
  load" as a supported state and fall back to `NoJsRuntime` rather than failing to start. The
  `JsRuntime` interface already allows this: capabilities are declared, not assumed.
- jlink and Conveyor need the natives extracted where the JVM can find them. Native libraries
  inside a jar are extracted to a temp directory at runtime by default, which is fragile on
  Windows and blocked outright on hardened Linux setups mounting `/tmp` as `noexec`.
- If macOS aarch64 turns out to be unsupported, **GraalJS is the fallback** for that platform
  specifically. It is pure JVM, so it has none of these problems, and the `JsRuntime` interface
  makes it a per-platform substitution rather than a rewrite.

Neither of these blocks anything before Milestone 8.
