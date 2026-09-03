# ARCHITECTURE — Ageha

Companion to `FINDINGS.md`. Read that first; this document assumes its conclusions.

Status: **approved 2026-09-03.** Both corrections in this document -- the parent-first API
allowlist (4.1) and string-based source persistence (3) -- were accepted, along with the four
stack decisions now recorded in `FINDINGS.md` 8. Milestone 2 is built and green.

---

## 1. Do I disagree with the proposed stack?

The brief invited a challenge. Mostly I don't — the stack is well chosen and the evidence supports it. Four amendments, in descending order of importance.

### 1.1 `:core:js` should ship as a no-op first, not as a JS engine

The brief treats JS evaluation as a hard problem to be solved before the app works. `FINDINGS.md` §4 shows it is a hard problem that **98.5% of sources do not have**. Building the browser integration first would be optimising the tail before the body exists.

So `:core:js` exists from Milestone 2, but its only implementation is `NoJsRuntime`, which throws exactly what the base class throws. That is not a stub in the sense the brief prohibits — it is the *documented default behaviour of the upstream API*, surfaced honestly in the UI as "this source needs a browser engine".

### 1.2 Add `:core:jvmcontext` — separate the `MangaLoaderContext` implementation from the facade

The brief's `:core:parsers` does two jobs: implement `MangaLoaderContext` (an *outbound* dependency — we hand it to the library) and provide the version-tolerant facade (an *inbound* dependency — we call through it). These have opposite stability profiles. The facade must survive parser API churn by absorbing it; the context implementation must track parser API churn exactly, because it is a subclass and a missed abstract member is a compile error.

Mixing them means every upstream `MangaLoaderContext` change forces a rebuild of the module that is supposed to be the churn firewall.

I propose keeping the brief's module name and adding one:

- `:core:parsers` — the facade. Ageha's own types only in its public API. **Nothing outside it imports a parsers-library type.** (Rule 5 in `CLAUDE.md`, unchanged.)
- `:core:jvmcontext` — the `MangaLoaderContext` subclass, wired from `:core:network`, `:core:js` and settings. Depended on by `:core:parsers` and nothing else.

Both sit behind the same wall, and the build enforces that both, and only both, may see the
parsers library.

### 1.3 Room, and start at schema version 28

`FINDINGS.md` §7: the Android app is Room, `DATABASE_VERSION = 28`, 17 entities, 27 migrations. SQLDelight would mean re-deriving that schema by hand from generated SQL, and re-deriving it again on every upstream migration. Room lets us declare the same entities and get the same DDL.

We declare our schema **starting at version 28** and never write migrations 1→27. Our own migrations start at 29. If a user's Android backup is older, the *Android app* upgrades it before export — not our problem.

### 1.4 Compose Desktop, with one caveat about the reader

Agreed on Compose Multiplatform for Desktop. One thing to flag now rather than at Milestone 7: Compose's `LazyColumn` is not designed for continuous webtoon strips of 200 images at 800×12000px each. The reader's scroll surface will likely need a custom layout over a bounded bitmap cache rather than a stock lazy list. I am noting it as a known risk, not proposing a solution yet — that decision belongs in Milestone 7 with a profiler attached.

---

## 2. Module graph

Unchanged from the brief except for `:core:jvmcontext`.

```
                        :app:desktop
                             │
      ┌──────────┬───────────┼───────────┬──────────────┬─────────────┐
 :feature:    :feature:  :feature:   :feature:      :feature:    :feature:
  library      explore    reader     downloads      tracking     settings
      └──────────┴───────────┼───────────┴──────────────┴─────────────┘
                             │                              :feature:updates
        ┌────────────────────┼────────────────────┐
  :core:designsystem   :core:database        :core:parsers   ◄── the wall
                                                   │
                                          :core:jvmcontext
                                                   │
                             ┌─────────────────────┼──────────┐
                        :core:network         :core:js   :core:model
```

Rules that make the graph mean something:

- **`:core:parsers` and `:core:jvmcontext` are the only modules that may declare the parsers library as a dependency.** Enforced by a Gradle check, not by discipline — a custom task fails the build if any other module's compile classpath contains the parsers artifact. Discipline does not survive a hurried afternoon.
- `:core:model` holds *Ageha's* types (`AgehaManga`, `AgehaChapter`, …). It does not depend on the parsers library and never mirrors its field layout (`FINDINGS.md` §5 — `Manga` is mid-migration).
- `:feature:*` modules never depend on each other. Cross-feature navigation goes through `:app:desktop`.
- `:core:designsystem` depends on nothing but Compose. Colours live only here (`CLAUDE.md` rule 7).

---

## 3. The facade — how it absorbs churn

`:core:parsers` exposes a small, boring surface:

```kotlin
interface MangaSourceRegistry {
    fun availableSources(): List<SourceDescriptor>
    fun descriptorFor(name: String): SourceDescriptor?  // null, not an exception
    fun clientFor(name: String): MangaSourceClient      // throws SourceFailure.UnknownSource
    val parsersVersion: String
}

interface MangaSourceClient {
    val descriptor: SourceDescriptor
    val domain: String
    val availableSortOrders: Set<AgehaSortOrder>
    val filterCapabilities: AgehaFilterCapabilities

    suspend fun list(offset: Int, order: AgehaSortOrder, filter: AgehaFilter): List<AgehaManga>
    suspend fun details(manga: AgehaManga): AgehaManga
    suspend fun pages(chapter: AgehaChapter): List<AgehaPage>
    suspend fun pageUrl(page: AgehaPage): String
    suspend fun filterOptions(): AgehaFilterOptions
    suspend fun relatedManga(seed: AgehaManga): List<AgehaManga>
    fun imageRequestHeaders(): Map<String, String>
}

data class SourceDescriptor(
    val name: String,        // the enum constant name — our persistence key
    val title: String,
    val locale: String?,
    val contentType: AgehaContentType,
    val isBroken: Boolean,   // upstream tells us; we surface it
)
```

Three design points, each traceable to a finding:

**Sources are addressed by string, always.** `MangaParserSource` is KSP-generated and differs
between JAR builds (`FINDINGS.md` §5). The registry enumerates `entries` and reads `name`,
`title`, `locale`, `contentType` and `isBroken`, all of which exist on the generated enum whatever
is in it — but it never references a *constant* by name, because that is what becomes a
`NoSuchFieldError` when upstream renames a source. Lookup is by string and returns `null` on a
miss. Every persisted row stores `name: String`. An unknown name renders as "source unavailable in
this parser version" — never an exception, never a lost favourite.

Milestone 2's registry binds against the compiled artifact, so this needs no reflection yet.
Milestone 3's loads the enum from a child classloader and does the same reading reflectively.
Neither the interface nor any caller changes between them, which is the point.

**Mapping is one-directional and lives in one file.** `ParserModelMapper` converts library `Manga`/`MangaChapter`/`MangaPage` into Ageha types and back. When upstream deletes `Manga.author` in favour of `authors`, exactly one file fails to compile. That is the whole point of the wall.

**Deprecated APIs are never called.** We use `getList(offset, order, filter)` and
`filterCapabilities`; never `getList(query)` or `searchQueryCapabilities`. They are marked
`@Deprecated("Too complex")` and will go.

**Every failure is typed.** No caller sees a parser-library exception or a bare `IOException`;
they see a `SourceFailure` saying *why*, so the UI can offer the right remedy. Of these,
`MissingJsRuntime` carries the most weight: Ageha ships without a JavaScript backend, so about 20
of 1360 sources fail this way by design, and that has to read as "this source needs the browser
component" rather than as a crash or a connectivity problem.

Getting that right needs more than a `catch`. Parsers routinely swallow their own anti-bot
failures — `MangaReaderParser.getNetShieldCookie`, shared by 257 sources, wraps its `evaluateJs`
call in `runCatchingCancellable` and discards the result — and then fail later for a vaguer
reason. So every facade call runs inside a `JsAttemptRecorder` on the coroutine context: the
JavaScript layer records each refusal on its way out, and if the call later fails for any reason,
the recorded refusal is what gets reported. A coroutine-context element rather than a
`ThreadLocal`, because parser calls hop threads freely.

---

## 4. Layer 1 — dynamic parser loading

This is the part the brief cares most about, and the part where the investigation changed the design most.

### 4.1 Classloader delegation — the brief's policy needs inverting

The brief says "parent-last for the parser classes but sharing OkHttp/Kotlin stdlib". **That would not work,** and the reason is worth writing down because it is the failure this whole layer exists to avoid.

`MangaLoaderContext` is an `abstract class`. Our `AgehaMangaLoaderContext` *extends* it. For that to link, our subclass and the JAR's base class must be the same `java.lang.Class` instance — same bytes, same defining classloader. If the child loader defines its own copy of `MangaLoaderContext` (parent-last), then our subclass — loaded by the app loader, extending the app loader's copy — cannot be passed to `newParserInstance`. The result is a `LinkageError` or a `ClassCastException` at the first call, not at load.

The same applies to `OkHttpClient`, `CookieJar`, `Response`, `Interceptor`, `Bitmap`, `MangaSourceConfig` and every model type that crosses the boundary. Which is: nearly all of them.

So the actual policy is **parent-first for everything shared, child-only for what is genuinely private to the JAR**:

| Package prefix | Delegation | Why |
|---|---|---|
| `java.*`, `javax.*`, `jdk.*` | parent-first | platform, mandatory |
| `kotlin.*`, `kotlinx.coroutines.*` | parent-first | `suspend` functions cross the boundary; two `Continuation` classes is a hard failure |
| `okhttp3.*`, `okio.*` | parent-first | `httpClient`, `cookieJar`, `Response`, `Interceptor` all cross |
| `org.jsoup.*` | parent-first | declared `api` in the library — jsoup types are in the public signature |
| `org.json.*`, `androidx.collection.*` | parent-first | `implementation` deps, but cheap to share and avoids two copies |
| `org.koitharu.kotatsu.parsers.**` | **parent-first for the API surface, child-first for site implementations** | see below |
| everything else in the JAR | child-only | the 1300+ site parsers, KSP output, private helpers |

The last row is the subtle one, and it means **we cannot use a plain package-prefix rule.** The API surface (`MangaLoaderContext`, `MangaParser`, the models, `Bitmap`, config types) must come from the parent so subclassing and value-passing work. The site parsers and the generated `MangaParserSource`/`MangaParserFactory` must come from the child so a new JAR actually brings new sources.

Both live under `org.koitharu.kotatsu.parsers.*`. So the delegation decision is made from an **explicit allowlist of API class names**, computed once at load time from the *bundled* JAR's public surface, not from a prefix match. The allowlist is a real artifact in the repo, versioned, and a mismatch between it and the loaded JAR is exactly what the compatibility gate reports.

This is the single most likely place for this project to go wrong. It gets a dedicated document
comment, and the allowlist gets a test that fails loudly when the bundled JAR's API surface
changes.

**If the allowlist becomes unmanageable, stop rather than work around it.** The failure mode to
watch for is the allowlist needing per-class special cases that cannot be derived from the bundled
JAR's public surface, or delegation rules that differ between parser builds. At that point the
approved fallback is to **run the parsers in a separate JVM process and talk to it over IPC**,
which trades a process boundary and a serialisation layer for complete classloader isolation.
That is a bigger change than it sounds and it is not mine to make unilaterally: raise it before
building around a fragile classloader, because a fragile classloader is the worse outcome of the
two and it fails in production rather than in CI.

### 4.2 Update flow

Because there are no version tags (`FINDINGS.md` §1):

```
1. GET api.github.com/repos/Kotatsu-Redo/kotatsu-parsers-redo/commits/master  → sha
2. if sha == active.sha → done
3. GET jitpack.io/api/builds/com.github.Kotatsu-Redo/kotatsu-parsers-redo/<sha10>
   → "ok" | "Error" | building; poll with backoff, give up after N minutes
4. download JAR + POM; resolve transitive deps (org.json, androidx.collection, …)
5. verify checksums; stage into ~/.ageha/parsers/<sha10>/
6. COMPATIBILITY GATE (4.3) — reflective, against the staged JAR
7. on pass: activate, keep previous as last-known-good
   on fail: discard, keep current, surface "Parsers <sha> needs an app update"
```

Never activate an unverified JAR. Never retry a JAR that failed the gate — record the SHA in a denylist so a scheduled check does not re-download it hourly.

### 4.3 The compatibility gate

Reflective, runs against the staged JAR in a throwaway classloader before activation. Three checks:

1. **No unimplemented abstract members.** Enumerate `MangaLoaderContext`'s abstract methods in the staged JAR; assert each is overridden by `AgehaMangaLoaderContext`. This catches "upstream added an abstract method", the failure mode that would otherwise be an `AbstractMethodError` mid-session.
2. **Facade-critical signatures exist.** For each method the facade calls (~12: the `MangaParser` verbs, `newParserInstance`, the model accessors), assert the method exists with the expected parameter and return types.
3. **Smoke instantiation.** Enumerate `MangaParserSource` reflectively, instantiate a small fixed sample of parsers, and assert `availableSortOrders` is non-empty and `domain` is non-blank. No network. Catches KSP output that did not generate.

Failure of any check means: do not activate, keep the running version, tell the user plainly. Crash-looping into a bad JAR is the outcome this exists to prevent.

### 4.3.1 The gate failing is a normal outcome, not an error path

Layer 1 cannot promise that source updates never require an app release, and the design must stop
implying otherwise. The evidence is already in hand: `evaluateJs` went from two parameters to
three between the build `kotatsu-dl` targets and the build we consume. That is a breaking change
to `MangaLoaderContext` itself -- the one interface Ageha implements -- and no amount of facade
tolerance absorbs it, because the facade is on the *calling* side and this is the *implementing*
side. When upstream adds an abstract member or changes a signature we implement, a new app build
is genuinely required.

So the honest promise is narrower and still worth a great deal: **routine source updates -- new
sites, fixed selectors, changed domains -- never require an app release.** Occasionally the host
contract moves, and then it does.

Because that will happen, the gate's rejection path is a designed state rather than an error:

- **Pin and keep running.** The last known-good JAR stays active. The user's library keeps
  working, at the source coverage they already had. Nothing degrades on the day of a bad update.
- **Say exactly what happened, once.** "Parsers `<sha>` needs a newer Ageha" -- naming the SHA, the
  Ageha version in use, and that sources still work meanwhile. Not a modal, not repeated per
  scheduled check, and never phrased as a crash.
- **Stop retrying that SHA.** A rejected SHA goes on a denylist, so a six-hourly check does not
  re-download and re-reject it forever.
- **Offer the one useful action.** If an app update exists, link to it. If not, say so plainly --
  "an Ageha update is needed and is not available yet" is more respectful than an unexplained
  refusal.
- **Keep the door open.** A manual "check again" must work, because the app update may land
  between scheduled checks.

The settings surface follows from this: show the active parsers SHA, when it was fetched, whether
a newer one was rejected and why, and offer pin and rollback. A user who can see that the pin is
deliberate does not file a bug about stale sources.

### 4.4 Tests the brief asked for

- Two different parser JAR SHAs loaded in one JVM; assert isolation (distinct `MangaParserSource` class identities in their respective child loaders) and that shared API types are identical across both.
- **Simulate an incompatible JAR and assert the whole rejection path**, not just the rejection.
  Build a fixture JAR whose `MangaLoaderContext` declares an abstract member Ageha does not
  override -- synthesised rather than downloaded, so the test is hermetic and does not rot when
  upstream moves. Then assert all of it: the gate rejects it, the previously active version is
  still serving sources afterwards, the SHA is on the denylist, a second update check does not
  re-download it, and the surfaced message names the SHA and says sources still work. This is the
  test that proves 4.3.1 is real behaviour rather than a paragraph.
- Kill the process mid-download; assert the staged directory is discarded and the active version is untouched.
- Rollback to last-known-good restores a working registry.

Networked tests are tagged and excluded from the default run, per the working agreement.

---

## 5. Threading

- All parser calls are `suspend` and run on `Dispatchers.IO`.
- The `JsRuntime` is single-threaded and mutex-guarded. Both QuickJS and a browser engine are single-context; concurrent `evaluateJs` calls must serialise. The Android `WebViewExecutor` does exactly this with a `Mutex`, and for the same reason.
- Compose recomposition never touches a parser. ViewModels expose `StateFlow`; the UI collects.
- Image decode happens off the main thread with a bounded bitmap cache sized from available heap.

---

## 6. Networking

One `OkHttpClient` instance, shared. Per `FINDINGS.md` §2 this is not negotiable.

- Persistent cookie jar on disk. Cloudflare clearance cookies must survive restarts, or every launch re-triggers a challenge.
- Disk cache for HTTP responses; a separate, larger cache for page images.
- `MangaParser` **is** an `okhttp3.Interceptor`. Each source's parser is installed as an interceptor for that source's requests only — dispatched by an OkHttp request tag, the way `MangaLoaderContextMock.doRequest` tags `MangaSource::class.java`. Getting this wrong means descrambling and auth headers silently do not apply.
- Rate limiting and a common-headers interceptor, modelled on `kotatsu-dl`'s (`RateLimitInterceptor`, `CommonHeadersInterceptor`). We read them as reference and write our own; they are small.
- Proxy support from settings.
- **We do not copy `kotatsu-dl`'s permissive-SSL trust-all block.** It exists for a CLI debugging tool. Shipping it in a desktop app would silently disable certificate validation for every user.

---

## 7. What the milestones actually produce

Restating the brief's milestones with the findings folded in. Gates unchanged — I stop at each.

| # | Deliverable | Changed by the investigation? |
|---|---|---|
| 1 | Skills installed; `FINDINGS.md` + `ARCHITECTURE.md` | **done, this is it** |
| 2 | Gradle skeleton, `:core:parsers` facade, `:core:jvmcontext`, CLI that searches one source | **done.** 1360 sources enumerated, live search/details/pages against MangaDex and Weeb Central, 26 tests green, wall enforced by the build |
| 3 | Layer 1 dynamic loading + tests | SHA-based, not version-based; allowlist-driven delegation (§4.1) |
| 4 | Database + library/history persistence | Room, start at schema v28 |
| 4b | **Android backup import** | moved here by decision 4: it validates the schema before any UI depends on it |
| 5 | `DESIGN.md`, `:core:designsystem`, icon pipeline, theme gallery | unchanged |
| 6 | Compose UI: explore + library | surface `isBroken` from the descriptor |
| 7 | Reader | webtoon scroll surface flagged as a risk (§1.4) |
| 8 | Downloads, tracking, settings | **+ `:core:js` real backends**: QuickJS, then optional on-demand Playwright |
| 9 | Layer 2 + 3 packaging and CI | `parsers-watch.yml` polls the org repo, resolves SHAs |

---

## 8. Where I expect this to hurt

Written down now so it is not a surprise later.

1. **Classloader delegation (§4.1).** Highest-risk item in the project. Symptoms are `LinkageError`/`ClassCastException` at runtime, never at compile time, and they appear only when the second JAR loads — i.e. not during development.
2. **The browser, if we build it.** ~200 MB, three native binary sets, Compose interop for the captcha window, and Cloudflare actively working against us. Deferred deliberately.
3. **Webtoon reader performance.** Compose Desktop has no equivalent of Android's mature RecyclerView tuning for this shape of content.
4. **Sources breaking the app rather than themselves.** A parser throwing an unexpected exception type must degrade to "this source failed", never propagate to a crash. Every facade call is wrapped.
5. **`getDefaultUserAgent()` fidelity.** Returning a Chrome UA while presenting a JVM TLS fingerprint is exactly what bot detection looks for. If we ship JCEF, return its real UA; if we do not, return a plausible desktop UA and accept that some sources will challenge us more often.
