# Ageha — design system

Milestone 5. This is the document the brief gates on: *"reviewed before any real screen is built."*

Everything here is implemented once, in `:core:designsystem`. **No screen defines its own colours,
type, spacing or motion.** The colour tokens are generated, not hand-picked — see §2.5 for how to
regenerate them.

![The theme gallery](design-gallery.png)

Run it yourself: `./gradlew :app:desktop:run` opens the gallery in a window;
`./gradlew :app:desktop:renderGallery` re-renders the PNG above without one.

---

## 1. Identity

**Ageha** (アゲハ, swallowtail butterfly). Latin `Ageha` everywhere it is named — window title,
About, installer, binaries. `アゲハ` may appear as a secondary mark only.

The logo is a *hanko* seal: a woodblock stamp with visible ink texture and rough pressed edges.
Two things follow from looking at it closely, and both shape the whole system:

**It is a negative.** The square is inked; the butterfly is the paper showing through. The mark is
the *stamp*, not a butterfly. So the app icon is a square seal, not a silhouette floating on
transparency, and the small-size variant keeps the square because at 16px the square is what
carries the identity.

**A seal is a mark of ownership, and a bookmark.** That is the concept the app leans on. The
library is the user's collection; the reading position is where they left their mark.

---

## 2. Colour

### 2.1 The three inputs

| | Value | Where it comes from |
|---|---|---|
| Seed | `#2B3A67` | Specified. Deep indigo, close to traditional *kon* (紺). |
| Accent | `#B93723` | **Measured** off the logo: the mean of all 117,453 pixels above 0.45 saturation. |
| Paper | `#F5F1E8` | Specified. The logo's own washi measures `#F0ECE3`; see below. |
| Sumi | `#1A1A1D` | Specified. Warm near-black. |

The brief guessed the vermillion at `#C8402B`. The measured value is `#B93723` — noticeably
deeper. Measuring wins for a colour whose entire job is to sit next to the seal without clashing
with it. `BrandSamplingTest` re-derives it from the image on every build, so a re-export or a
re-crop of the source cannot shift the accent silently.

Paper is the one place the brief's value beats the measurement. `#F0ECE3` is what the scan says;
`#F5F1E8` is one step lighter and leaves a little more headroom above it for elevated surfaces.
The two are within a just-noticeable difference, and the test asserts they stay that close.

### 2.2 Derivation

The seed is fed to Material 3 as a **seed**, and the full tonal palettes are derived from it using
Google's own colour science (HCT/CAM16), variant `TONAL_SPOT`, contrast level 0.

- **Primary** — indigo hue, chroma clamped to the 36–48 band so tone 40 (light) and tone 80 (dark)
  both stay recognisably *kon*.
- **Secondary** — indigo hue at chroma 16. Deliberately muted so it recedes.
- **Tertiary** — the vermillion's own hue and chroma.
- **Neutrals** — see §2.3.

The derivation runs at build time in `:tools:brandkit` and writes literal hex into
`AgehaColorTokens.kt`, which is committed. The colour library is **not** a dependency of the
shipped app: the inputs are two constants and the output is a few hundred bytes, so recomputing
them on every launch would be pure cost. Committing them also means a palette change shows up in a
diff as a colour change rather than as a library bump.

### 2.3 Four deliberate departures from stock Material 3

Each of these is asserted by `AgehaPaletteTest`, so none of them can be undone by accident.

**1. Light neutrals are warm; dark neutrals are cool.**
The brief asks for both *"paper, not pure white"* for light surfaces and *"neutrals derived from
the indigo ramp so greys are subtly blue rather than dead."* Those pull opposite ways at the light
end, because paper is warm and indigo is not. Splitting them by theme honours each rule where it
was written for: paper is warm because paper is warm, and a night surface reads better cool.

**2. Paper and sumi are pinned, not derived.**
Material would put light `surface` at neutral tone 98 — a near-white barely warmed. The brief names
paper as *the* light surface and sumi as *the* dark one, so `background` and `surface` are set to
those exact values in each theme and the rest of the ramp is left to Material. Two overrides is a
small enough surface to reason about; a dozen would not be.

**3. No pure white, no pure black.**
Applied as one uniform rule rather than a handful of hand-picked fixes: any generated token that
resolves to `#FFFFFF` becomes paper, any that resolves to `#000000` becomes sumi. This catches
roles nobody thinks to check — Material makes `onPrimary` pure white, so a paper-toned label on an
indigo fill is the seal's own figure-and-ground relationship, and it still clears AA at 5.7:1.

Three exemptions, each for a stated reason:
- `scrim` stays true black. It is a translucent dimming overlay; a warm scrim reads as a *stain*
  over artwork rather than as dimming.
- The AMOLED variant's backmost surfaces are true black, because an unlit pixel is the entire
  purpose of that variant.
- `surfaceContainerLowest` in light is `#FDF9F0`, computed by lightening paper in place rather than
  by asking the palette for tone 99. HCT cannot hold chroma that close to white and returns
  `#FFFBFF` — a *cool* near-white, which is worse than the problem it was solving.

**4. One red, not two.**
`error` is the same palette as `tertiary`. Material would otherwise hand `error` its own generic
red landing a few degrees of hue from the hanko vermillion: two reds close enough to look like a
mistake but far enough apart to look sloppy. The meanings are separated by **form** instead — see
§5.

### 2.4 Resolved tokens

`*(as dark)*` means the AMOLED variant inherits the dark value unchanged.

| Role | Light | Dark | AMOLED |
|---|---|---|---|
| `primary` | `#4B5C92` | `#B4C5FF` | *(as dark)* |
| `onPrimary` | `#F5F1E8` | `#1B2D60` | *(as dark)* |
| `primaryContainer` | `#DBE1FF` | `#334478` | *(as dark)* |
| `onPrimaryContainer` | `#334478` | `#DBE1FF` | *(as dark)* |
| `inversePrimary` | `#B4C5FF` | `#4B5C92` | *(as dark)* |
| `secondary` | `#595E72` | `#C1C5DD` | *(as dark)* |
| `onSecondary` | `#F5F1E8` | `#2B3042` | *(as dark)* |
| `secondaryContainer` | `#DDE1F9` | `#414659` | *(as dark)* |
| `onSecondaryContainer` | `#414659` | `#DDE1F9` | *(as dark)* |
| `tertiary` | `#AF301D` | `#FFB4A6` | *(as dark)* |
| `onTertiary` | `#F5F1E8` | `#660700` | *(as dark)* |
| `tertiaryContainer` | `#FFDAD4` | `#8D1606` | *(as dark)* |
| `onTertiaryContainer` | `#8D1606` | `#FFDAD4` | *(as dark)* |
| `background` | `#F5F1E8` | `#1A1A1D` | `#000000` |
| `onBackground` | `#1C1C14` | `#E4E2E6` | *(as dark)* |
| `surface` | `#F5F1E8` | `#1A1A1D` | `#000000` |
| `onSurface` | `#1C1C14` | `#E4E2E6` | *(as dark)* |
| `surfaceVariant` | `#E7E3CC` | `#45464F` | *(as dark)* |
| `onSurfaceVariant` | `#494737` | `#C5C6D0` | *(as dark)* |
| `surfaceTint` | `#4B5C92` | `#B4C5FF` | *(as dark)* |
| `inverseSurface` | `#323128` | `#E4E2E6` | *(as dark)* |
| `inverseOnSurface` | `#F5F1E3` | `#303034` | *(as dark)* |
| `error` | `#AF301D` | `#FFB4A6` | *(as dark)* |
| `onError` | `#F5F1E8` | `#660700` | *(as dark)* |
| `errorContainer` | `#FFDAD4` | `#8D1606` | *(as dark)* |
| `onErrorContainer` | `#8D1606` | `#FFDAD4` | *(as dark)* |
| `outline` | `#7A7865` | `#8F909A` | *(as dark)* |
| `outlineVariant` | `#CAC7B1` | `#45464F` | *(as dark)* |
| `scrim` | `#000000` | `#000000` | *(as dark)* |
| `surfaceBright` | `#FDF9EC` | `#39393C` | *(as dark)* |
| `surfaceDim` | `#DEDACD` | `#131316` | `#000000` |
| `surfaceContainer` | `#F2EEE0` | `#1F1F23` | `#17171B` |
| `surfaceContainerHigh` | `#ECE8DB` | `#292A2D` | `#1F1F23` |
| `surfaceContainerHighest` | `#E6E2D5` | `#343438` | `#292A2D` |
| `surfaceContainerLow` | `#F8F4E6` | `#1B1B1F` | `#101114` |
| `surfaceContainerLowest` | `#FDF9F0` | `#0D0E11` | `#000000` |

### 2.5 Contrast

**All three themes pass WCAG AA on body text.** Not asserted by eye: `AgehaContrastTest` computes
the ratio for 66 foreground/background pairs — every `on*` role against its surface, plus
`onSurface` against every step of the container ramp — and fails the build below 4.5:1. The gallery
prints the same ratios live so a reviewer can see *how much* headroom a pair has, not merely that
it passed. `outline` is separately held to the 3:1 non-text threshold.

The container-ramp pairs matter more here than in a stock Material app, because pinning paper and
sumi moves `surface` off the tone Material's own contrast curves assumed.

### 2.6 Regenerating

```
./gradlew :tools:brandkit:generatePalette   # rewrites AgehaColorTokens.kt and prints this table
./gradlew :tools:brandkit:generateIcons     # rebuilds every icon from the source logo
./gradlew :tools:brandkit:generateBrandAssets   # both
```

`:tools:brandkit` is build-time only and is not on the application's classpath.

---

## 3. Type

**Nothing is bundled yet, and that is a decision rather than an omission.** Full CJK faces run
10–20MB each and Ageha needs four scripts' worth; bundling would put ~40MB of binary in the
repository before the design has been reviewed. Instead the families are an explicit ordered
preference chain resolved against what is actually installed, and the gallery *reports what it
found* so the gap is visible rather than theoretical. Bundling a subset lands with packaging in
milestone 9, when there is an installer to put it in.

Compose Desktop resolves a family by name through Skia's font manager, which also supplies
automatic per-glyph fallback — a Japanese title inside a Latin-only family still renders. The CJK
chains are therefore about the *quality and consistency* of that fallback, except on Linux, where a
machine with no CJK font installed genuinely has nothing to fall back to and will show tofu.

**Serif** (titles, manga names): Source Serif 4 → Source Serif Pro → Charter → Iowan Old Style →
Noto Serif → Georgia → DejaVu Serif → generic serif.

**Sans** (chrome, metadata): Inter → Inter Variable → Segoe UI Variable Text → Segoe UI → SF Pro
Text → Helvetica Neue → Noto Sans → Cantarell → Ubuntu → DejaVu Sans → generic sans.

**CJK**, per script — reported per script because coverage is not all-or-nothing:

| Script | Chain |
|---|---|
| Japanese | Hiragino Sans → Yu Gothic UI → Yu Gothic → Meiryo → Noto Sans CJK JP → Noto Sans JP → Source Han Sans JP → MS Gothic |
| Korean | Apple SD Gothic Neo → Malgun Gothic → Noto Sans CJK KR → Noto Sans KR → Source Han Sans KR |
| Chinese (Simplified) | PingFang SC → Microsoft YaHei UI → Microsoft YaHei → Noto Sans CJK SC → Noto Sans SC → Source Han Sans SC |
| Chinese (Traditional) | PingFang TC → Microsoft JhengHei UI → Microsoft JhengHei → Noto Sans CJK TC → Noto Sans TC → Source Han Sans TC |

### 3.1 Scale — retuned for a desktop, not a phone

Material's defaults are drawn for a phone held at arm's length. On a monitor the same sizes read as
oversized: `bodyLarge` at 16sp is a comfortable phone paragraph and a shouty desktop list row.
Everything is a step or two down, with line heights tightened to match, because the library grid's
job is to show a lot of covers at once.

| Role | Family | Size / line |
|---|---|---|
| `displayLarge` / `Medium` / `Small` | serif | 48/56, 38/46, 30/38 |
| `headlineLarge` / `Medium` / `Small` | serif | 26/33, 22/28, 19/25 |
| `titleLarge` | serif | 17/23 medium |
| `titleMedium` / `Small` | sans | 14/19, 12/16 medium |
| `bodyLarge` / `Medium` / `Small` | sans | 14/20, 13/18, 12/16 |
| `labelLarge` / `Medium` / `Small` | sans | 13/17, 11/15, 10/14 medium |

Three roles Material has no slot for:

- **`mangaTitle`** — serif, 13/17 medium. A manga title is not `titleLarge`: it is frequently long,
  frequently Japanese, and gets two lines under a cover and no more. Truncation is *not* in the
  token — `TextStyle` in this Compose version has no `overflow` — so call sites pass
  `maxLines = 2` with `TextOverflow.Ellipsis`.
- **`metadata`** — sans, 11/15. Source name, chapter count, last-read date. Deliberately quiet.
- **`readerHud`** — sans, 12/16, **tabular figures**. A page counter that reflows as the number
  ticks past 9 is a small irritation that compounds over a few hundred pages.

---

## 4. Spacing, shape and motion

**Spacing** is a 4dp scale with t-shirt names rather than numbers, because a number invites
arithmetic and `spacing.md * 1.5` is how a design system starts leaking. If a gap is not on the
scale, use the nearest step.

`xxs` 2 · `xs` 4 · `sm` 8 · `md` 12 · `lg` 16 · `xl` 24 · `xxl` 32 · `xxxl` 48, plus
`gridGutter` 12 and `minCoverWidth` 132.

The scale starts tight because **this is a desktop app**: no 48dp touch minimum, a precise pointer,
and a library grid that has to fit a lot of covers on a 27-inch display without looking like a
stretched phone app.

**Shape** is restrained. Material 3's radii are drawn for phones, where a 28dp dialog corner looks
right beside a rounded screen; on a desktop window next to native chrome the same radius reads as a
toy. Ageha's are roughly half: 2 / 4 / 6 / 10 / 14dp. Covers get 3dp — manga art is rectangular and
rounding it crops the artwork.

**Motion** is quiet and quick. 90ms hover and press · 140ms default · **190ms** screen transitions
(the brief's ceiling, and a ceiling rather than a target) · 260ms reader-chrome fade, deliberately
slower because that one is a withdrawal the reader should barely notice. Standard easing in,
accelerating easing out. Nothing bounces, staggers or overshoots: Ageha is opened many times a day
by someone who wants to resume a chapter, and every animation is a tax paid repeatedly.

---

## 5. The vermillion accent — and its only three uses

Indigo and vermillion is a deliberate traditional pairing, and it stops working the instant
vermillion becomes a general-purpose highlight — at which point it is just a second brand colour
and the seal stops meaning anything.

So the accent is **not exposed as a colour token any screen can reach for.** It is exposed as three
components in `AgehaAccent`, and adding a fourth is a design decision rather than an implementation
detail.

Because `error` is the same red (§2.3), the two meanings are separated by form:

- **A small mark** — a dot, a short bar, a thin underline — means *unread, new, or currently
  reading*. `UnreadBadge`, `NewChapterDot`, `ActiveReadingIndicator`.
- **A filled surface** — a solid button, a filled banner — means *destructive or failed*.
  `destructiveButtonColors()`.

Nobody mistakes a 6dp dot for a delete button. Neither is ever the only signal: both carry a label
or a count, because colour alone is not an accessible signal in the first place.

`destructiveButtonColors()` is for genuinely irreversible actions only. A destructive-looking button
on a reversible action teaches people to ignore the colour, which costs exactly when it matters.

---

## 6. The reader is exempt — and this is enforced

**Nothing brand-coloured touches the reader view.** Not indigo, not vermillion, no tint of either:
not a wash behind the page, not a coloured letterbox around a narrow page, not a themed scrollbar.
Brand identity belongs in the library and the navigation; over somebody's manga it is a stain on
someone else's art.

This is why `ReaderBackground` lives **outside** `ColorScheme` entirely rather than as extra roles
inside it. A reader background is not a theme colour — it is chosen independently of light/dark and
persists per user — and keeping it structurally separate means no future screen can reach for
`MaterialTheme.colorScheme.surface` in the reader and quietly succeed.

Four user-selectable neutrals: **Black** `#000000` (default) · **Dark grey** `#202020` ·
**Paper** `#EDEAE4` · **White** `#FFFFFF`. Chrome derives from the chosen background, not from the
theme, so the overlay stays legible on white and unobtrusive on black. Scrims are neutral greys at
low alpha — a tinted scrim shifts the artwork's colour, which is the same mistake wearing a hat.

`ReaderNeutralityTest` enforces two things:

1. **No hue.** Every reader colour is under 6% chroma. Paper measures 3.5%; for scale the indigo
   seed measures 24% and the vermillion 60%.
2. **No token reuse.** No reader colour is *any* design-system token in any theme. The reader's
   paper `#EDEAE4` is deliberately not the brand's paper `#F5F1E8` — reusing it would work today
   and silently couple the reader to the brand, so that a future change to the brand's warmth would
   change what someone sees behind their manga.

> An earlier version of test 2 measured RGB distance from the brand colours instead. It failed on
> the reader's neutral grey foreground — because the indigo seed is itself fairly dark and not
> especially saturated, so a plain grey of similar lightness lands close to it in RGB space while
> carrying no hue at all. Distance-from-brand punishes greys for existing. What matters is that a
> reader colour never *is* a theme colour, and that can be checked exactly.

---

## 7. Logo and icon pipeline

The source is a raster JPEG on a **fake** checkerboard — it has no alpha channel, just a drawn grid
standing in for one. `:tools:brandkit` rebuilds every asset from it.

### 7.1 Keying the background

The butterfly's washi interior and the fake checkerboard are both very light and only about 13
units apart in red-minus-blue. Keying on **colour** would either eat the butterfly or leave grey
squares behind. So the key runs on **connectivity**: flood-fill inward from the border across
neutral, light pixels. The butterfly is enclosed by ink on every side, so it cannot be reached.
Edge pixels get partial alpha from how many neighbours the key removed — without it the stamp gets
a hard jagged border that reads as a bad cut-out.

The washi is kept **opaque**, not knocked through. A hanko's ink is the mark and the paper is
whatever it is stamped on, but an *icon* has to be self-contained: a seal with its own washi
backing reads correctly on any window background, in any theme, in any OS icon context.

### 7.2 The simplified small-size variant

The brief predicted the wing veining would turn to mud below ~32px, and it does. The small variant
is the seal's outline, the butterfly as a flat silhouette, and the disc — no interior linework.

Getting there took two corrections worth recording, because both produced plausible-looking files
with no butterfly in them:

- **The butterfly is not one region.** The ink veins cut it into wings, body and antennae — seven-odd
  disconnected patches of washi. A plain connected-component pass finds fragments and no butterfly.
  A morphological **close** at radius 6 bridges the veins (they are only a few pixels wide) and the
  fragments become one silhouette. Radius is bounded on the other side too: too large and the close
  reaches across the ink to the disc below and fuses them.
- **JPEG blur along the seal's outer edge** leaves a hairline of desaturated pixels that reads as
  washi and forms a closed ring around the entire stamp. Left in, it is the second-largest knockout,
  and filling its holes turns it into the whole seal. Eroding the seal by 10px before intersecting
  removes the rim without touching anything genuinely interior.

Outlines are traced by **crack following** — walking the unit edges between set and unset pixels —
then relaxed with Ramer–Douglas–Peucker. Epsilons differ on purpose: the seal's outline is
simplified loosely because its roughness is the point, the butterfly tightly because its shape is
what makes the mark recognisable at 16px. The result is 880 points, down from tens of thousands.

Both the butterfly and the disc are **knockouts**, not painted shapes: one path, even-odd fill, so
the surface behind shows through exactly as it does on paper.

### 7.3 What is generated

`brand/generated/` — for the packager:

| File | Contents |
|---|---|
| `ageha-master-1024.png` | Detailed master, real alpha, full ink texture |
| `ageha-mark.svg` | Simplified two-colour mark |
| `ageha-mono.svg` | Single colour, inherits `currentColor` |
| `ageha.ico` | 16–256. DIB below 64, PNG above |
| `ageha.icns` | 16–1024 including every `@2x` type |
| `linux/ageha-{16..512}.png` | Freedesktop icon ladder |
| `tray/tray-{light,dark}-{16,20,24,32}.png` | Monochrome, both polarities |

`core/designsystem/src/main/resources/app/ageha/brand/` — for the running app: window icon, the
tray pair, and the two icon ladders the gallery compares.

Two audiences, written separately on purpose. Packaging wants the full matrix, consumed once at
build time; the app only needs a window icon and the tray pair, loadable from the classpath.
Conflating them is how icon directories rot.

**Crossover:** the simplified mark below 48px, the downscaled master at 48 and above. This is the
one number in the pipeline set by looking rather than measuring, so the gallery renders both
variants at every size and keeps the call reviewable.

**macOS** gets its own treatment: the seal is *placed on* a paper-coloured squircle rather than
clipped into one, which would shave off the rough stamped edge that gives it its character — and
which is also the more honest picture of the thing, a stamp pressed onto paper. The squircle is a
superellipse at exponent 5; a circular-arc corner beside a real macOS icon reads as visibly wrong.

`.ico` and `.icns` are written by hand rather than shelled out to ImageMagick or `iconutil`, which
would make the icons buildable only on a machine that happens to have them — and for `iconutil`,
only on macOS. Both formats fail quietly, so `IconContainersTest` parses them back byte by byte.

---

## 8. On the ui-ux-pro-max skill

The brief asks for design work to go through this skill, so it was run. Three things to report:

1. **The invocation in the brief does not exist in the installed version.** `search.py
   --design-system --persist -p "Ageha"` is not a mode in v2.13.0; the CLI takes
   `--domain {deliverable,style,industry,mockup}` and `--cip-brief`.
2. **Its style database is print-and-branding oriented** — business cards, paper stocks, spot UV
   finishes. Queried for a restrained editorial reading app it returns Corporate Minimal,
   Scandinavian Minimal and Swiss Minimal, whose palettes are `#FFFFFF` / `#0F172A` / `#000000`.
   That directly contradicts CLAUDE.md's "never pure white or pure black in chrome", and the
   project's own colour spec is far more specific than anything the skill has. **CLAUDE.md wins,
   and this is that conflict being said out loud.**
3. **What was taken from it:** the three-layer token architecture (primitive → semantic →
   component), which maps cleanly onto generated tokens → `ColorScheme` roles → the `AgehaAccent`
   components; the 4px spacing base; and its interaction-state model. Its palettes and component
   code were not used, and — as the brief instructs — its lack of a Compose Desktop stack was not
   allowed to push anything toward a web stack.

---

## 9. Open items

- **Fonts are not bundled.** Deliberate (§3); revisit at milestone 9. Linux without a CJK package
  will show tofu, and the gallery names the missing script rather than failing silently.
- **`FontFamily(String)` is experimental** on Compose Desktop. It is the only way to honour a named
  preference chain; the fallback to the generic family means an API removal degrades rather than
  breaks the build.
- **The 48px crossover** is a judgement call. Reviewable in the gallery's icon ladder.
- **AMOLED uses true black**, which is the one place CLAUDE.md's "never pure black in chrome" is
  set aside — an unlit pixel is that variant's entire reason to exist. Confined to the backmost
  surfaces; every elevated container stays on the sumi ramp so cards and sheets remain visible as
  separate objects.
