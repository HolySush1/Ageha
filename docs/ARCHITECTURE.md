# ARCHITECTURE — Ageha

Companion to `FINDINGS.md`. Read that first; this document assumes its conclusions.

Status: **Milestone 3 built and green.** String-based source persistence (§3) was approved and
holds. The parent-first API allowlist described in §4.1 was approved but **did not survive
contact with the library** — §4.1 now records why, and what replaced it. The four stack decisions
are in `FINDINGS.md` §8.

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

We declare our schema **starting at version 28** and never write migrations 1→27. Our own
migrations start at 29. If a user's Android backup is older, the *Android app* upgrades it before
export — not our problem.

Confirmed at Milestone 4: Room 2.8 with `androidx.sqlite:sqlite-bundled` runs on a desktop JVM
with no Android dependency, and the schema exports to `core/database/schemas/` so it can be
diffed against the Android app's. Two things needed saying in code because neither fails at
compile time — the driver must be the bundled one (desktop JVMs have no SQLite), and
`PRAGMA foreign_keys = ON` must be set per connection, because SQLite defaults it off and the
schema leans on `ON DELETE CASCADE` to stop tags, chapters and history outliving their manga.

Eight of the seventeen entities are ported: the ones library, favourites and history need. The
rest arrive with the features that use them, each as a migration rather than by retroactively
editing version 28.

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

- **`:core:jvmcontext` is the only module that may declare the parsers library as a dependency**, and it does so `compileOnly` — it is loaded by `ParsersClassLoader` at runtime, not by the application classloader (§4.1). Enforced by a Gradle check rather than by discipline: a custom task fails the build if any other module's compile classpath contains the parsers artifact. Discipline does not survive a hurried afternoon.
- The shared modules — `:core:model`, `:core:source` types, `:core:js`, `:core:network` — are the only things both sides of the classloader boundary can see, so they must stay free of parser types.
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

Since Milestone 3 the enum is read on the child side of the classloader boundary, by
`RealParserBridge`, which is recompiled and reloaded alongside the parsers jar it talks to — so it
links against whatever build is loaded and needs no reflection to do it. The parent never sees the
enum at all. No caller outside `:core:parsers` changed when this moved, which is the point.

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

### 4.1 Classloader delegation — the approved allowlist did not survive Milestone 3

This section previously described a parent-first allowlist: share the parsers library's own API
types across the classloader boundary and load only the site parsers in the child. **That design
was abandoned during Milestone 3, before it was built.** What follows is what was found, and what
replaced it.

#### Why the allowlist failed

The reasoning behind it was sound. `MangaLoaderContext` is an abstract class Ageha subclasses, so
our subclass and the JAR's base class must be the same `java.lang.Class` — which argued for
sharing the API surface with the parent. Three things then went wrong.

**`LinkResolver` drags in the world.** `MangaLoaderContext.newLinkResolver` returns
`org.koitharu.kotatsu.parsers.util.LinkResolver`, so it must be parent-loaded. `LinkResolver`
imports `AbstractMangaParser` — the base class every one of the 1300+ site parsers extends. Sharing
the boundary types transitively freezes most of the library at whatever version shipped with the
app, which is the opposite of the point.

**The generated enum sits inside the shared package.** `MangaParserSource` is emitted by KSP into
`org.koitharu.kotatsu.parsers.model`, the same package as `Manga` and friends. A `model.**`
prefix rule is therefore impossible: the one class that *must* be child-loaded lives among the
ones that must be parent-loaded, so the rule needs a per-class carve-out on day one, and another
each time upstream generates something new.

**And the fatal one: sharing the models freezes them.** If `Manga` is parent-loaded, a build whose
site parsers were compiled against a changed `Manga` links against ours and raises
`NoSuchMethodError`. So every model change would require an app release — and `FINDINGS.md` §5
records that `Manga` is mid-migration *right now*, shedding a deprecated constructor and three
deprecated accessors. Routine source updates would stop being routine, which is the single
property Layer 1 exists to protect.

Cumulatively that is the "unmanageable" trigger: a hand-curated list of parser internals, needing
maintenance on every upstream change, failing only at runtime and only after an update.

#### What replaced it: a narrow typed bridge

Nothing from the parsers library crosses the boundary at all.

The child classloader gets the parsers jar **and Ageha's own `:core:jvmcontext` jar**, which is the
code that implements `MangaLoaderContext` and speaks parser types. Because they are loaded
together, they are always consistent with each other, and both are free to change together. The
parent holds neither.

The two sides communicate through `app.ageha.core.source.ParserBridge`, an Ageha interface whose
every signature is a JDK type or an Ageha type. The parent constructs the implementation once,
reflectively; everything after that is an ordinary typed method call.

```
  parent (application classloader)          child (ParsersClassLoader)
  ────────────────────────────────          ──────────────────────────────
  :core:parsers  ── ParserBridge ──────────▶ RealParserBridge
  :core:model                                AgehaMangaLoaderContext
  :core:js        shared, parent-first  ───  ParserModelMapper
  :core:network        ▲                     kotatsu-parsers.jar
                       │                     jsoup, org.json, androidx.*
                  one reflective call
```

The delegation rule shrinks from a curated class list to a short package-prefix list — JDK,
Kotlin, OkHttp/Okio, and Ageha's four shared modules. Everything else is child-first. The full
list and the reasoning live in `ParsersClassLoader`'s class comment, which is the authoritative
copy.

**`app.ageha.core.jvmcontext` is deliberately absent from that list**, and adding it is the single
most likely way to break the design: it compiles, it loads, and then it raises `LinkageError` on
first use. `:core:jvmcontext` is `compileOnly` against the parsers library and never reaches the
application runtime classpath. `ClassLoaderIsolationTest` asserts that absence directly rather
than trusting the build file.

#### What this bought

- **Model changes no longer need an app release.** `Manga` can gain and lose fields freely; only a
  change to `ParserBridge` itself — which is Ageha's own code — forces one.
- **The wall narrowed.** `:core:parsers` no longer names a parsers type, so the build's exemption
  list went from two modules to one. A narrower exemption is a stronger guarantee.
- **It is the seam IPC would need anyway.** If the in-process design is ever abandoned for a
  separate parser process, a remote `ParserBridge` is a drop-in for the local one. Building this
  was progress toward either outcome, which is why it was built rather than escalated.

#### What it cost

- One reflective construction, and a constructor signature that no compiler checks. That signature
  is an ABI, and `CompatibilityGate` asserts it before activating anything.
- The bundled parsers build now ships as a resource and is extracted on first run, because a
  classloader needs a real file URL. This is a feature disguised as a cost: the bundled build takes
  exactly the same code path as a downloaded one, so the path that matters most is exercised at
  every launch rather than only during an upgrade.
- The parsers library's own dependencies must be staged into the child too. That list is resolved
  at build time rather than written by hand, which immediately turned up `androidx.annotation` —
  a dependency no documentation mentions and nobody would have guessed.

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

### 4.5 Properties of the boundary, and what they cost

Five questions were put to the bridge design after it was built. The answers are here because
each is a standing property that a later change could break quietly.

#### The HTTP cache has one owner (this was a bug)

Two builds coexist whenever the gate runs: it constructs a second context while the live one is
still serving. Before this was fixed, each context built its own `OkHttpClient` against the
**default** cache directory, so two `Cache` instances shared one directory — which OkHttp's own
documentation calls an error, and which classloader isolation does nothing to prevent, because
the directory is shared regardless of who loaded the class.

The parent now creates exactly one client and injects it. Each context derives from it with
`newBuilder()`, so the cache, connection pool and dispatcher are *the same objects*, not copies;
only the interceptor stack differs. The gate additionally hands its candidate a `cache(null)` view,
so a build about to be discarded cannot write into the cache the live build is reading from. Only
`SourceStack.close` shuts any of it down.

Per-build cache directories were the alternative and were rejected: they would throw away the
entire HTTP cache on every parsers update, which is a large, silent cost for a problem that single
ownership solves outright.

`CompatibilityGateTest.gateDoesNotDisturbTheLiveStack` runs the gate three times against a live
stack and asserts the live one is unaffected.

#### Kotlin stdlib and coroutines are parent-shared, and must be

They are excluded from the child's staged dependencies — `libs/` contains only `jsoup`,
`org.json`, `androidx.collection` and `androidx.annotation`.

They have to be. `MangaSourceClient` declares six `suspend` functions that cross the boundary, so
`Continuation` must resolve to the same class on both sides; two copies of the coroutines runtime
would fail at the first suspension. Sharing is not a convenience here, it is a requirement of the
bridge being suspending at all.

**If a parsers build bumps its stdlib past ours** and uses an API we do not have, its classes link
against our older copy and raise `NoSuchMethodError` — which is a `LinkageError`, which is exactly
what the gate catches and reports as "needs a newer Ageha". The failure is contained by design
rather than avoided. In practice the risk is small: the Kotlin stdlib is strongly
backward-compatible, and we track the same version the library builds against.

#### The shim surface is 13 members, and it is a tracked metric

Every member Ageha implements against `MangaLoaderContext` is a place upstream can move and break
us — `evaluateJs` gaining a third parameter is the precedent. So the number is held to a test,
`ShimSurfaceTest`, which fails in either direction. Growing it is sometimes correct; drifting
upward one convenience override at a time is not.

Counted by signature rather than by name, because each overload is its own break point.

| Group | Count | Can it go down? |
|---|---|---|
| HTTP (`httpClient`, `cookieJar`) | 2 | No. Literal OkHttp types in the upstream contract. |
| Configuration (`getConfig`, `getDefaultUserAgent`) | 2 | No. |
| Images (`createBitmap`, `redrawImageResponse`) | 2 | No, but both are trivial and stable. |
| JavaScript (`evaluateJs` ×2, `interceptWebViewRequests` ×2, `captureWebViewUrls`) | 5 | **This is where the risk lives.** All five are the newest and least settled part of the upstream API. |
| Browser hand-off (`requestBrowserAction`, `requestCloudflareVerification`) | 2 | Possibly one: the second delegates to the first upstream, and we override it only to distinguish a Cloudflare challenge from a login. |

It went from 14 to 13 when this was first measured: `getPreferredLocales` was overridden with an
implementation byte-for-byte identical to the upstream default. Seven of the remaining thirteen
are JavaScript or browser members, which is the same concentration of risk `FINDINGS.md` §5
identified from the other direction.

#### Downloaded builds are locked and verified before every load

Resolving the POM at runtime means Ageha fetches a set of jars whose membership it did not know in
advance — which is how `androidx.annotation` turned up. Verifying only the parsers jar would leave
every transitive dependency unchecked, and they load into the same classloader with the same
privileges: a tampered `json-20240303.jar` runs exactly as freely as a tampered parsers jar.

So each build directory carries a `lock.json` recording a SHA-256 for every file, written while the
build is still staged. Verification is all-or-nothing and runs **before every load**, not only
after download — a build that verified when staged can stop verifying later through a partly
applied app update, disk corruption, or someone dropping a jar in by hand. Missing files,
modified files and *unexpected* files all fail: the classloader is handed everything in `libs/`,
so a file nobody recorded is code nobody vouched for.

At download time each artifact is additionally checked against the repository's own published
`.sha1` where one exists. That is the only point in the flow where provenance can be checked at
all — the lock records what arrived, so it would faithfully record a bad download; the published
digest is what catches a truncated transfer or a mangling proxy as it happens.

An unverified build is stepped over rather than repaired, falling back to the next candidate and
ultimately to the bundled build. The bundled build is the exception: it has nothing to fall back
to, so a failed verification re-extracts it from the application's own resources.

#### Model mapping is per chapter, not per page and never per byte

- `pages()` maps a chapter's page list once — tens to low hundreds of small objects.
- `pageUrl()` is called per page but **maps nothing**: it returns a `String`. Its only per-page
  cost is an LRU lookup and one recorder allocation. Where a source needs a network round trip per
  page, that is the parser's design, not the boundary's.
- **Image bytes never cross the boundary at all.** The reader fetches them itself over HTTP using
  `imageRequestHeaders()`.

`BridgeSurfaceTest` asserts the last point structurally: no method on the boundary may return a
stream, a byte array or an HTTP response. A method that did would move megabytes through the
mapping layer on every page turn, and that is far easier to add by accident than to notice.

The one place bytes are touched is `redrawImageResponse`, used by the handful of sources that
serve pages as shuffled tiles. That decode-redraw-re-encode is inherent to descrambling and runs
on the child side, inside the interceptor chain — it is the parser's work, not mapping.

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
| 3 | Layer 1 dynamic loading + tests | **done.** Bridge-based isolation (§4.1), SHA-based updates, gate with a designed rejection path, two builds proven to coexist in one JVM |
| 4 | Database + library/history persistence | **in progress.** Room 2.8 + bundled SQLite proven on desktop; schema at v28, 8 of 17 entities |
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
