# Releasing Ageha

## Cutting a release

```
git tag v0.2.0
git push origin v0.2.0
```

`release.yml` runs the full suite, builds every target with Conveyor, and publishes the packages
and the update feed to the GitHub release the tag created.

The version comes from the tag, not from `build.gradle.kts`. A release therefore cannot be cut with
a version that disagrees with what it is tagged — which is the failure mode of every scheme where
the two are maintained separately.

## What gets built

| Platform | Package | Architectures |
|---|---|---|
| Windows | `.msi` and an online installer `.exe` | x64, arm64 |
| macOS | `.dmg`, notarizable | x64, Apple Silicon |
| Linux | `.deb`, `.rpm`, tarball, and an APT/YUM repository | x64, arm64 |

Every one bundles a jlink-trimmed JDK 21, so users install nothing else.

**arm64 is not optional.** Apple Silicon is the majority of Macs sold, and Windows on ARM is no
longer a rounding error. Compose Desktop ships a different Skia native per platform *and*
architecture, which is why `app/desktop/build.gradle.kts` declares all six rather than relying on
whatever the CI runner happens to be — a Linux build made on Windows would otherwise carry Windows
Skia and fail at first paint.

## Building and installing locally on Windows

Conveyor cannot run on a normal Windows account here -- its build needs symlink permission a
non-elevated user does not have -- so local packaging goes through jpackage instead:

```
./gradlew :app:desktop:packageMsi --no-configuration-cache
```

`--no-configuration-cache` is required, not optional: the Compose plugin's `downloadWix` task calls
`Task.project` at execution time, which Gradle's configuration cache forbids. That task also
downloads WiX, so its first run needs the network -- `--offline` fails there and nowhere else.

The MSI lands in `app/desktop/build/compose/binaries/main/msi/`.

### An MSI cannot upgrade over the same version

Installing `Ageha-0.1.0.msi` while `Ageha 0.1.0` is installed fails with **1638**, *"another version
of this product is already installed"*. jpackage does not set `AllowSameVersionUpgrades` in its WiX
template and Compose's `nativeDistributions` block does not expose it, so there are two ways round:

- **Bump the version.** `packageVersion` in `app/desktop/build.gradle.kts`. This is what a real
  release does, and it is why the problem never shows up in CI.
- **Uninstall, then install**, for a local rebuild at the same version:

  ```
  msiexec /x "{PRODUCT-CODE}" /qn     # from ...\Uninstall\, DisplayName "Ageha"
  msiexec /i "Ageha-0.1.0.msi" /qn
  ```

  **This is safe for the library.** Binaries install to `%LOCALAPPDATA%\Ageha Reader`; the database,
  cookies and preferences live in `%LOCALAPPDATA%\Ageha` -- deliberately separate, for exactly this
  reason. Back up `preferences.json` first anyway when the new build renames an enum the old one
  wrote: `PreferencesStore.migrate` rewrites what it recognises, and rewrites it in place.

## Conveyor's licence

Conveyor is free for projects under an OSI-approved licence. Ageha is GPL-3.0, so it qualifies, and
the licence is activated purely by `app.vcs-url` in `conveyor.conf` — there is no signup, no
account and no key.

It carries **one obligation**: the README must link to Conveyor and note that it is used for
packaging. [README.md](../README.md) discharges this in its "Packaging" section. If that section is
ever rewritten, the link has to survive.

## Code signing — not done, and what it would take

**Releases are currently unsigned.** This is the one thing standing between a release and a clean
first-run experience, and it is a *cost* problem rather than a technical one: code-signing
certificates are a separate paid expense that Conveyor's free open-source licence does not cover.

### What users see

- **Windows** — SmartScreen shows "Windows protected your PC". The workaround is More info → Run
  anyway. This must be in the release notes, or the download simply looks broken.
- **macOS** — Gatekeeper refuses to open it. The workaround is right-click → Open, or
  `xattr -d com.apple.quarantine /Applications/Ageha.app`. On Apple Silicon an unsigned app also
  needs an ad-hoc signature, which Conveyor applies automatically.
- **Linux** — nothing. Linux does not have this problem.

### Getting a certificate

Three routes, cheapest effort first:

1. **[SignPath Foundation](https://signpath.org/)** — free code signing for open-source projects.
   Requires an application, a review of the project, and signing through their infrastructure
   rather than with a key you hold. Best fit for a GPL project with public CI, and the route to try
   first.
2. **[Certum Open Source](https://www.certum.eu/en/cert_offer_en_open_source_cs/)** — an
   inexpensive certificate for open-source developers, roughly €25–35/year plus a one-off hardware
   token. It is a real EV-adjacent certificate on a physical token, which means CI cannot use it
   without a cloud HSM or a self-hosted runner with the token attached.
3. **A commercial certificate** — a few hundred per year. Only worth it if the project takes money.

macOS is separate from all three: notarization requires an **Apple Developer Program** membership
(US$99/year) whatever else is done. There is no free route.

### Once a certificate exists

Conveyor needs no code changes. The keys go in `conveyor.conf` or, better, in CI secrets:

```
app.windows.signing-key = ${env.WINDOWS_SIGNING_KEY}
app.mac.notarization {
  app-specific-password = ${env.APPLE_APP_PASSWORD}
  team-id = ...
  apple-id = ...
}
```

Signature verification and atomic replace-on-restart are already part of what Conveyor's updater
does; they simply have nothing to verify against yet.

## Before tagging

- [ ] `./gradlew build` green on all three platforms (CI does this on every push)
- [ ] `./gradlew :app:desktop:renderShell` green — the app composes against the real graph
- [ ] `cli smoke --sample 25` looks ordinary — some sources dead, in assorted ways
- [ ] `CHANGELOG.md` updated
- [ ] The signing status is stated in the release notes, while it is still unsigned
