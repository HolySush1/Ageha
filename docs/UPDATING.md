# How Ageha stays current

Written for someone who has forgotten how any of this works — probably the person who built it,
eight months from now, at the point where something has broken.

There are **three independent update paths** and they solve different problems. Confusing them is
the main way this gets debugged in the wrong place.

| Layer | What it updates | How often | Needs a new Ageha release? |
|---|---|---|---|
| 1 | The **parsers** — the 1360 site scrapers | Continuously, upstream | **No** |
| 2 | **Ageha itself** — the application | When app code changes | Yes, that is the point |
| 3 | **CI** — the automation that drives 1 and 2 | Never, it is the driver | No |

---

## Layer 1 — parsers

**This is the one that matters.** Manga sites change their markup constantly, and when one breaks
the fix is almost always a commit to `kotatsu-parsers-redo`, not to Ageha. Layer 1 exists so that
fix reaches users without anybody cutting a release.

### How it works

The parsers library is a plain JAR. Ageha loads it in an isolated `URLClassLoader` and reaches it
through a narrow typed bridge (`ParserBridge`), so a newer JAR can replace the current one at
launch without recompiling anything.

1. **Check.** `ParsersUpdateService` resolves upstream `HEAD` and asks JitPack for a build of that
   commit. There are no version tags upstream, so **the version is a commit SHA**.
2. **Fetch.** The JAR and its transitive dependencies are downloaded to
   `<cache>/parsers/<sha>/`, and a `lock.json` records a SHA-256 for every file.
3. **Gate.** Before anything depends on it, the build is loaded in a throwaway classloader and a
   sample of sources is constructed and exercised reflectively. If the host contract has moved —
   which it does; `evaluateJs` gained a third parameter once already — the build is **refused**.
4. **Activate.** Only a build that passed is written to `state.json` as active. It takes effect on
   the next launch.

### Things that look like bugs and are not

- **"An update was found and not installed."** Look at the notice. A build can be *refused*, and
  refusal is a designed outcome, not an error: the user's sources keep working on the build they
  have. Refused builds are recorded in `state.json` and never retried.
- **"The update checker says a new build exists but does nothing."** The install is pinned. A pin
  means Ageha reports what is available and does not act on it, which is the entire point of a
  pin. Settings → Sources and updates → Unpin.
- **"It downloaded but the app still says the old version."** Activation takes effect at next
  launch. Swapping the classloader under a reader that is mid-chapter would mean invalidating every
  open page URL, in-flight request and cached client belonging to the old build.
- **JitPack returns 429.** JitPack builds on demand and rate-limits anonymous callers rather than
  queueing. The poll treats 429 as "not yet", and so should you. This is not an outage.

### Where to look when it is actually broken

| Symptom | Look at |
|---|---|
| Every source fails the same way | `:core:network`, then `:core:jvmcontext` |
| One source fails | Upstream. Ageha cannot fix a parser and must not try. |
| A build is always refused | `CompatibilityGate` — read the reason; usually `MangaLoaderContext` moved |
| Refuses to load after a crash | The lock verification failed; the build is corrupt on disk. It falls back to the bundled build on its own. |

### Doing it by hand

```
cli parsers            # which build is loaded
cli parsers check      # look for a newer one, fetch and gate it
cli parsers rollback   # go back to the last known good build
```

---

## Layer 2 — the application

Ageha's own updates go through **Hydraulic Conveyor**, which builds signed, self-updating packages
for all three platforms from one machine and hosts the update feed alongside the release.

Cutting a release is a tag:

```
git tag v0.2.0 && git push origin v0.2.0
```

`release.yml` then runs the full suite, builds every target, and publishes to the GitHub release
the tag created — so the URL a user downloads from is the URL their updater polls.

The version comes **from the tag**, not from `build.gradle.kts`, so a release cannot be cut with a
version that disagrees with what it is tagged.

See [RELEASING.md](RELEASING.md) for signing, which is not done yet and is the one thing standing
between a release and a clean first-run experience.

### The settings toggle, and what it honestly controls

The brief asks for an automatic / notify / manual toggle here, and it exists — under
**Settings → Sources and updates → Ageha itself**. What it controls needs saying plainly, because
the obvious reading is wrong.

**Ageha cannot install its own updates, and the toggle does not pretend to.** On all three
platforms the *installer* owns that: MSIX on Windows, the bundle's updater on macOS, apt on Linux.
Each is configured when the package is built, and none exposes a runtime switch. A settings
checkbox claiming to turn automatic installation on and off would be a lie told by a checkbox.

What Ageha genuinely controls is whether it *looks*, and whether it *tells you*:

| Setting | Behaviour |
|---|---|
| Check quietly (default) | Asks GitHub at startup, says nothing. The installer updates you anyway. |
| Check and tell me | Asks at startup, and posts a notice when a newer release exists. |
| Never check | No request is made at all. A "Check for updates" button remains. |

"Never check" means *no request*, not a request whose answer is hidden. A setting by that name
that still contacted GitHub would be the same lie in a different place.

The check is an unauthenticated read of `releases/latest` — a public endpoint on a public
repository, so a token would be a credential Ageha would have to hold for no gain. That caps it at
60 requests an hour per address, which one check per launch cannot reach. A repository with no
releases answers 404, which is reported as "could not check" rather than as an error, because it is
the state this project is in right now.

Versions compare **numerically**. The string comparison is the trap: `"0.10.0" < "0.9.0"`
lexicographically, which would tell everyone on 0.9 to upgrade to 0.10 and everyone on 0.10 that
they were ahead of it. `AgehaVersion.CURRENT` is checked against the project version by
`./gradlew :app:desktop:checkAppVersion`, the same guard `:core:parsers` puts on its bundled
parsers version, so the two cannot drift.

---

## Layer 3 — the automation

Four workflows in `.github/workflows/`.

### `parsers-watch.yml` — every six hours

Polls upstream for a new commit. If there is one: bumps the pin in `gradle/libs.versions.toml`,
runs the full suite, runs a live smoke test, and opens a PR labelled `parsers`.

- **Green** → the PR is opened with the smoke result reported. It is safe to merge.
- **Red** → no PR. An issue is opened instead, one per bad build rather than one per run — six runs
  a day against the same broken upstream would otherwise be twenty-four issues by tomorrow.
- The build step **retries five times with a two-minute wait**, because JitPack may still be
  building the commit that was just asked for.

### `source-smoke.yml` — nightly

Exercises a random sample of real sources end to end: list → details → pages → resolve one page
URL. Stops at the first stage that fails and reports *which*, because "MANGADEX failed at pages"
is a diagnosis and "MANGADEX failed" is not.

**It does not fail for dead sources.** At any time a large share of the 1360 are down, blocked or
serving nothing; a run of 12 sources during development found 9 failures across three different
causes, and that was a completely ordinary night. It fails only when the failures are both
numerous **and alike** — that is what a broken Ageha looks like, and what a broken internet does
not.

The sample is **seeded and the seed is printed**, so a failing run can be reproduced exactly:

```
cli smoke --sample 25 --seed 20260903
```

### `release.yml` — on a tag

Covered under Layer 2.

### `ci.yml` — every push

The ordinary build, on Linux, Windows **and** macOS. All three, because the parts this project
leans on are exactly the parts that differ: file locking and renaming on Windows, path handling
everywhere, and the bundled SQLite and Skia natives. A Linux-only CI would have missed every
Windows bug found so far — and there have been several, all of them about open file handles.

It also runs `renderShell`, which draws the real application against the real graph with no
display. A green unit suite does not catch a window that opens blank.

---

## When something is wrong, in order

1. **Is it one source, or all of them?** One is upstream's problem. All is yours.
2. **Read the refusal.** The gate says why it refused a build. It is usually right.
3. **Check the lock.** `cli parsers` reports whether the active build verifies. A corrupt build
   falls back to the bundled one silently and by design, which can look like "updates stopped
   working".
4. **Reproduce the smoke run.** With the seed from the log, the exact same sample is exercised.
5. **Only then suspect Ageha.** The wall check, the compatibility gate and the classloader boundary
   exist so that upstream churn cannot reach the rest of the codebase. If it did anyway, that is
   the interesting bug.
