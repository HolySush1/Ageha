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

**Latin is bundled. CJK is not, except on Linux.** Those are two different questions and they got
two different answers.

The Ember & Glass handoff names its faces by weight — Archivo 400/500/600/700/800 for anything
operated or read as prose, JetBrains Mono 400/500/700 for anything read as *data* — and specifies a
scale built on them down to the half-pixel. Neither is on a typical machine, so a preference chain
would have delivered the design to almost nobody. They ship as classpath resources of
`:core:designsystem`: **1.8MB**, both SIL Open Font Licence 1.1, credited in `NOTICE.md`.

The earlier argument here was against bundling on a ~40MB figure. That figure was about **CJK** —
full pan-CJK faces run 10–20MB each and Ageha needs four scripts' worth — and it still holds, so
CJK is still resolved from the system, with one 16MB fallback shipped to the Linux packages alone
where a machine may genuinely have nothing to fall back to. 1.8MB of Latin is a different question
with a different answer.

`BundledTypeTest` fails if any weight goes missing. That matters because the fallback is silent by
design: `AgehaFonts.bundle` returns null for a missing or corrupt resource and the app degrades to
a system sans rather than refusing to start, which is right at runtime and useless as a signal. A
dropped file, a renamed weight or a committed LFS pointer would otherwise produce an app that runs,
looks plausible, and matches the handoff nowhere.

**UI** (chrome, titles, body, manga names): **Archivo**, bundled. Falls back to the sans chain
below only if the resources are absent.

**Data** (counts, hosts, page positions, the uppercase micro-labels): **JetBrains Mono**, bundled.
Not routed through the CJK fallback, because everything in this role is ASCII by construction —
`Ch 214 / 260`, `mangadex.org`, `MY LIBRARY` — and a proportional pan-CJK face here would cost the
tabular alignment that is the entire reason the role is monospaced.

There is no serif role any more. A manga title in the handoff's grid is Archivo SemiBold at 12.5px,
not an editorial serif; `AgehaFonts.serif` survives as an alias so the gallery and the About dialog
do not churn.

Compose Desktop resolves a family by name through Skia's font manager, which also supplies
automatic per-glyph fallback — a Japanese title inside a Latin-only family still renders. The CJK
chains are therefore about the *quality and consistency* of that fallback, except on Linux, where a
machine with no CJK font installed genuinely has nothing to fall back to and will show tofu.

**Legacy chains**, still used when a bundled resource is missing:

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

## 8. Glass

The chrome is translucent: a floating navigation pill, breadcrumb and filter bars, menus and
dialogs, all drawn over a live backdrop. Implemented in `AgehaGlass.kt` and `AgehaBackdrop.kt`.

### 8.1 There is no backdrop filter, and that shapes everything

Compose Desktop's `Modifier.blur` blurs a composable's **own content**, not what is behind it.
There is no `backdrop-filter` and no `UIGlassEffect`. Every glassmorphism recipe written for the
web or for SwiftUI assumes that primitive, so none of them port.

What Ageha does instead is the two-layer construction that predates backdrop filters:

1. `AgehaBackdrop` draws **one gradient**, built from the active scheme, low in the stack.
2. Panels are translucent fills over it.

Depth comes from the fill, the hairline specular edge and the cast shadow — all of which
`Modifier` can express — rather than from a per-panel blur that Compose Desktop cannot.

### 8.2 The backdrop is the theme, and nothing else

It was not always. The first version drew the most recent Continue Reading cover here, blurred at
48dp and scrimmed to 78% — the app as a room furnished with your own library. The idea was good and
the execution was wrong on three counts, each sufficient on its own:

- **It made Appearance a half-truth.** Choosing Light and getting a window tinted by whatever you
  last read is a setting that does not settle the question it claims to settle.
- **The source material could not carry it.** Sources serve cover thumbnails a few hundred pixels
  wide; scaled to fill a 1280×860 window they are mush, and blur disguises only so much of that
  before the whole window looks out of focus rather than deliberately soft.
- **The background changed when nothing the user did should have changed it.** Finishing a chapter
  re-tinted the entire application.

So the backdrop is `surfaceDim → surface → primaryContainer` at 35%, painted over an opaque
`surface`, and it changes only when the theme does. The cover art moved to the one place it can be
shown at the resolution it was actually published at: the Continue Reading hero (§8.4).

### 8.3 The alphas come from contrast, not from a screenshot

The usual glassmorphism figure is 10–30% white. At that opacity a panel takes its contrast from
whatever happens to sit behind it — which is to say it has no contrast guarantee at all.

The backdrop bounds the problem: it is three of the scheme's own tokens, so backdrop luminance sits
within a step or two of `surface` by construction. That premise is what lets the tones be as
transparent as they are:

| Tone | Fill | Alpha | Used for |
|---|---|---|---|
| `CHROME` | `surfaceContainer` | 0.58 | Navigation pill, breadcrumb and filter bars |
| `PANEL` | `surfaceContainerHigh` | 0.66 | Cards, inline panels, the library shelf rail |
| `RAISED` | `surfaceContainerHighest` | 0.88 | Menus, popovers, dialogs |

`RAISED` is nearly opaque because it is the one tone that floats over **content** rather than over
the backdrop, so it gets none of the palette's help and must hold its own over an arbitrary grid of
covers.

`AgehaContrastTest` composites the whole stack over each of the backdrop's gradient stops in all
three themes — and `RAISED` over pure black and pure white besides — holding every result to the
same 4.5:1 floor as any other body text. It is the test that stops someone lowering an alpha
because it looked better on one screenshot.

### 8.4 Cover colour, where the artwork can carry it

The Continue Reading hero is the one component in Ageha whose colours come from an image rather
than from the palette. `CoverAccent` samples the cover at 24×36, averages it **in linear light**
(averaging sRGB directly lands visibly dark on the black-ink-on-white-paper covers that are most of
them), keeps the hue, clamps saturation to 0.14–0.46, and moves lightness onto the theme's rail —
0.20 in a dark theme, 0.84 in a light one. It then *measures* the contrast of paper and sumi
against the result and uses whichever clears AA.

The cover itself is drawn beside that fill at its own 2:3, never scaled past the panel's height.
That is the whole difference from the banner it replaced, which cropped a portrait into a letterbox
and upscaled a thumbnail to the width of the window.

`CoverAccentTest` runs the derivation over the RGB cube in both themes and fails on any seed whose
panel drops below 4.5:1 — including the faded metadata line, which is the number that actually
binds the constants above.

### 8.5 No new colour, and not in the reader

Glass introduces no hex. Fills come from the active scheme's container ramp, so it follows light,
dark and AMOLED for free and §2's rule holds. The only non-scheme values are the white and black
alphas on the specular edge, which are light rather than pigment.

The reader draws no backdrop and no glass. Brand colour behind a page is the tinted-wash mistake
§6 exists to prevent, one layer further back — and the same `isImmersive` check suppresses both the
navigation and the backdrop.

---

## 9. On the ui-ux-pro-max and liquid-glass-design skills

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
4. **Its Compose stack table is effectively empty.** Queried for surface, elevation and layering
   guidance it returns nothing; queried for `compose` at all it returns two rows, "use
   `mutableStateOf`" and "use `animate*AsState`". Its `glassmorphism` entry is CSS
   `backdrop-filter`. This is CLAUDE.md rule 6 being correct in advance.
5. **`liquid-glass-design` is iOS 26 only, and none of it is used.** It documents
   `.glassEffect()`, `UIGlassEffect`, `GlassEffectContainer` and WidgetKit rendering modes. None
   exist off Apple platforms, and CLAUDE.md rule 4 rules out Swift interop regardless. What was
   taken is its *material* argument — translucency over a live backdrop, a hairline specular edge,
   layered depth, and glass reserved for chrome rather than sprayed over everything — all of which
   §8 implements in Compose from scratch. **Said out loud, as CLAUDE.md requires.**

---

## 10. Open items

- **CJK fonts are not bundled off Linux.** Deliberate (§3). The Latin faces the design is drawn in
  *are* bundled now, so the interface looks the same everywhere; a Linux machine without a CJK
  package still shows tofu in titles, and the gallery names the missing script rather than failing
  silently.
- **Glass has no backdrop blur** and cannot have one on Compose Desktop (§11.3). The translucency,
  the specular edge and the shadow stand in for it.
- **`FontFamily(String)` is experimental** on Compose Desktop. It is the only way to honour a named
  preference chain; the fallback to the generic family means an API removal degrades rather than
  breaks the build.
- **The 48px crossover** is a judgement call. Reviewable in the gallery's icon ladder.
- **AMOLED uses true black**, which is the one place CLAUDE.md's "never pure black in chrome" is
  set aside — an unlit pixel is that variant's entire reason to exist. Confined to the backmost
  surfaces; every elevated container stays on the sumi ramp so cards and sheets remain visible as
  separate objects.

---

## 11. Ember and Glass — the two skins

`design_handoff_ageha/` (README, WIRING, `skins.css`, an interactive HTML prototype) is a
high-fidelity aesthetic layer for the reader Ageha already was: two switchable skins, one component
vocabulary, five screens. Colours, type, spacing, radii and states are declared final, and this
section records how they were taken and where they were not.

### 11.1 They are two materials, not two palettes

Ember is **flat**: opaque panels, warm near-black, small radii, `--blur: none`. Glass is **frosted**:
translucent panels over a cool blue-grey, fully round pills, a 22px backdrop blur.

The first implementation gave both the same translucent fill and the same specular edge at
different alphas, which made Ember read as a dimmer Glass rather than as a different material — and
the contrast between them is most of what the handoff is for. `AgehaSkin.isFlat` splits them:

| | Ember | Glass |
|---|---|---|
| Panel fill | opaque | translucent (`GlassTone` alphas) |
| Edge | plain `--line` hairline | specular gradient |
| Pill radius | 10dp | fully round |
| Chip radius | 5dp | fully round |
| Accent seed | `#d9432f` | `#8b7ff2` |

### 11.2 The accents are seeds, not literals

`#d9432f` and `#8b7ff2` never appear as fills. They go through `:tools:brandkit`'s
`PaletteGenerator` like every other Ageha colour, and `AgehaContrastTest` holds the results to the
same AA floor as the brand themes. The **one** exception is the title bar's skin switcher, which
has to show both skins at once — exactly one of two swatches can come from the active theme, so
`AgehaSkin.EmberSwatch` and `GlassSwatch` are literal, live in the design system, and are used
nowhere else.

### 11.3 The blur does not exist, and cannot

`--blur: blur(22px)` is a `backdrop-filter`. Compose Desktop has none: `Modifier.blur` blurs a
composable's *own* content, not what is behind it (§8.1). Glass is therefore translucency over
`AgehaBackdrop`, a hairline specular edge, and a cast shadow — the two-layer construction that
predates backdrop filters. **This is a real fidelity gap**, recorded rather than papered over.

### 11.4 Where the handoff was not followed

Each of these is a deliberate departure, not an omission. The list is much shorter than it was:
the select popovers, the filter rail, the command panel, the single-column library and the
Downloads pause/resume controls were all departures once and are not any more.

| Handoff | What Ageha does | Why |
|---|---|---|
| Settings -> **Tracking** (AniList / MAL / Kitsu) | Not built. The rail's seventh slot is **Sync**, Ageha's own. | CLAUDE.md rule 9 puts external tracking permanently out of scope. WIRING.md itself notes it needs an OAuth flow the mockup lacks. |
| Reader progress fill, page ticks and **Close** in `--accent` | Neutral, from `ReaderChrome` | Rule 8: nothing brand-coloured touches the reader. `ReaderNeutralityTest` measures every reader colour for hue and would fail the build. |
| Card position reads `Ch 214 / 260` | A percentage | A `LibraryEntry` has no chapter *total*. The Android schema this database stays compatible with has no per-chapter read table, and a total only exists after a source has been asked for a fresh list. |
| Source health: `Healthy / Slow 1.8s / Broken 404` | `Healthy / Broken` | There is no latency probe. Inventing a number with nothing behind it is worse than reporting upstream's own broken flag. |
| Storage bar: "of 64 GB used" | The volume's real capacity | One filesystem call, and it makes the bar mean something: the same library is nothing on a 2TB desktop and a problem on a 128GB laptop. |
| Trash deletes on the first click | Confirms, naming the chapter count | WIRING.md flags the instant delete as wrong. These are files kept deliberately for offline reading, and there is no undo. |
| Reader is a layer "above everything" | The 38dp title bar stays | The handoff describes a browser prototype with no window to manage. Hiding it takes minimise and close from someone mid-chapter to hide 38px they are not looking at. Fullscreen is the real immersion path. |
| Nav pill: 5 items | 4 items and the search button | Continue Reading became the Library banner, which is where the handoff puts it. It keeps Ctrl+2 and the shelf header's link. |
| Filter rail: **four** toggles, including *Show unverified mirrors* | Three | There is no unverified-mirror tier in the parsers library. A switch that changes nothing is worse than a shorter rail. |
| Settings: **Wi-Fi only**, **Image quality**, **Volume keys** | Absent | A desktop JVM cannot portably ask whether a connection is metered; Ageha stores what the source served rather than recompressing it; and there are no volume keys. Same rule as above. |
| Reading mode as one **Single / Double / Long strip** segment | A Paged/Long strip segment, with pairing and direction as their own rows | Ageha's reader mode is per *manga* and the pairing is global. Folding them into one control would make a reader who set one webtoon to strip mode stop pairing pages in every tankoubon. |
| Downloads: paused work resumes from the last finished page | Resumes by restarting the chapter | `ChapterDownloader` writes into one `ZipOutputStream` and deletes its `.part` on cancellation -- which is what guarantees a `.cbz` on disk is never a partial download wearing a finished name. Page-level resume means staging loose files and trades that guarantee for a directory of orphans after a crash. **A real gap**, recorded rather than papered over. |
| Free up space: "delete read chapters, oldest first" | Oldest *written* first, per-title selection | The download inventory is the filesystem, keyed by a sanitised title with no id to join read state on. Same intent, with the data that exists. |

### 11.5 What the handoff asked for and now exists

Recorded because these were the gaps, and a design record that only lists departures stops being
useful the moment they are closed.

- **The command panel.** 620dp, 74dp from the top, mono field, `ESC` cap, five rows, full-bleed
  invisible backdrop, `Ctrl+K`. It does what WIRING.md describes and what neither of the two
  controls it replaced could do alone: filter the library from the first keystroke, then fan out to
  the enabled sources on a 150ms debounce and append those results underneath.
- **Select popovers.** `AgehaSelect`: a ghost button, a panel 6dp below it aligned to its right
  edge, Escape and outside-click to close, a rotating caret, an accent tick on the chosen row. The
  earlier note here argued radio groups were better; they are, *given room*, and the handoff's row
  layout does not have it.
- **The filter rail.** 292dp, toggle rows over `--line` dividers, language chips, the note box.
- **"All N chapters"** on the Continue banner, opening the details screen's chapter list.
- **Pause all / Resume all, per-row Pause/Resume, and Free up space** on Downloads.
- **Licences**, which was `TextButton(onClick = {})` -- the one genuinely dead control in the app.
- **Six settings with real backends**: reading mode and direction (a fallback on
  `ReaderRepository.observeMode`), page fit, preload depth (both the url half and the image half),
  card style, 18+ cover blur, and parallel downloads (pushed into the live queue, not merely
  stored).

### 11.6 The rule the handoff and CLAUDE.md agree on

> *"No literal hex in component styles. Only `var(--…)`."*

That is rule 7 written for a stylesheet, and `NoLiteralHexTest` enforces the Kotlin form of it
across `feature/` and `app/desktop`. It found one real offender on its first run: the reader's page
placeholders carried `Color(0xFF9A9A9A)`, chosen against the black background everyone develops on
and barely legible on Paper — two of the four backgrounds that screen offers.

### 11.7 Window chrome

The window is undecorated so the title bar can carry what a native caption cannot: a context line
with live counts, the skin switcher, and the app's own mark at 21dp. What a caption did for free is
written back in `TitleBar.kt` and `WindowResize.kt` — dragging, double-click to maximise, three
window buttons, eight resize edges anchored to the drag origin so they do not drift.

**One thing does not come back.** Edge-drag Aero Snap is driven by non-client hit-testing that an
undecorated window has opted out of; restoring it needs `WM_NCHITTEST` over JNI. `Win`+arrow still
snaps, because the shell handles that path rather than the window.

The native `MenuBar` went with it. Compose's is a Swing `JMenuBar` inside the frame, which under a
custom caption renders as a native strip belonging to another application. Nothing it carried was
lost: navigation and the theme picker duplicated the pill and the switcher, backup already lived in
Settings, and "Open comic archive" moved there rather than surviving only on Ctrl+O.
