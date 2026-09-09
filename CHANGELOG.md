# Changelog

All notable changes to Ageha are recorded here.
Format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/);
this project uses [Conventional Commits](https://www.conventionalcommits.org/).

## [Unreleased]

### Added

- **"Find another source", on library cards, browse results and the chapter header.** Ageha has
  had cross-source search since 0.2, and reaching it meant retyping a title that is usually a
  romanisation -- so the one search that spans all 1360 sources was the one you had to spell from
  memory. It is now a right-click away on any card, and a button in the chapter header, seeded
  with the title the source itself returned.

  The chapter-header button is deliberately *not* hidden when the chapter list is empty, unlike
  the Download all button beside it. An empty or failed chapter list is precisely when "who else
  has this" is the only useful question left on the screen.

## [0.3.1] - 2026-09-09

### Fixed

- **The reader now actually loads ahead of the scroll.** Pages arrived blank and filled in a beat
  later, which is the one thing a reader must not do. Three faults, and the first is the one that
  mattered:
  - **Page prefetching had never run, in any release.** `Dispatchers.Main` is resolved through a
    `ServiceLoader` and throws on first use when nothing provides it -- and Compose Multiplatform
    1.12's `compose.desktop.currentOs` does not. Coil builds `enqueue`'s coroutine on the main
    dispatcher, so every prefetch threw before fetching a byte, inside the `runCatching` that
    exists to stop one bad page taking down the reader. The "Preload next pages" setting had been
    doing nothing at all. Fixed by adding `kotlinx-coroutines-swing`.
  - **The webtoon strip composed nothing beyond the viewport**, so a page's image request started
    at the instant it became visible -- exactly too late. It now composes three viewports ahead
    and one behind, measured as a fraction of the window rather than as a page count, because a
    webtoon page is not a unit of distance.
  - **Cache warming re-enqueued requests already in flight.** The effect restarted on every url
    resolution, and Coil does not coalesce duplicate requests, so scrolling filled its fetch and
    decode queues with work already in progress and the page on screen waited behind it. Each page
    is now enqueued once.
- `:app:desktop:webtoonProfile` passed no image loader, which disables preloading -- so it had been
  profiling a reader with the read-ahead switched off, and could not have caught any of the above.
  It now runs the real loader and reports how long each page was ready *before* it was reached,
  failing if the strip outruns the read-ahead. On the 200-page profile: 199 of 200 pages ready
  ahead by a median of 306ms, against 0 of 200 before.

### Changed

- Maximum heap raised from 1536MB to 3072MB. Coil sizes its memory cache at a quarter of the heap
  and pages are decoded at full resolution, so the read-ahead window needs the room.

## [0.3.0] - 2026-09-09

### Added

- **Ageha moves now.** Motion was a documented vocabulary and almost nothing else: nineteen
  animated call sites in a source tree of two and a half thousand files, fifteen of them inside two
  settings controls. The result was an app that was *fast* and read as *inert* -- state changes
  teleported, so nothing told you whether you had gone deeper or come back, whether a shelf had
  re-sorted or been replaced, or whether the thing under the pointer was clickable at all.
  - **Screens arrive from the direction they came from.** Pushing into a manga slides in from the
    right, pressing Escape brings the previous screen back from the left, and switching section
    cross-fades with no direction, because sideways is not deeper. This is the change that carries
    the most information: before it, opening something and backing out of it were the same event.
  - **Hover and press feedback on roughly forty surfaces that had none.** Chapter rows, source
    rows, the words in the navigation pill, chips, ghost buttons and library cards all respond to
    the pointer now. One shared modifier, so they respond identically -- forty separate hover
    treatments is the same failure as two cover cards with different corner radii.
  - **The navigation pill's selection slides between words** instead of one highlight going out and
    another coming on two hundred pixels away. The eye follows movement, and the movement is the
    message: it says the selection *went* from Library to Explore.
  - **Covers lift under the pointer** -- four percent, with a shadow that turns a zoom into the
    card coming forward out of the shelf -- and **move to their new slots when a shelf re-sorts**
    instead of teleporting. Watching them travel is what says "the same shelf, reordered" rather
    than "a different shelf". Lists stagger in at 18ms per item as they first appear.
  - **Loading looks like the thing that is coming.** The four screens that answered "still loading"
    with a spinner on a blank window now draw a shimmering skeleton at the real content's
    proportions, so nothing moves when the content lands, and the shape itself previews what is
    arriving. Held back 120ms, so a cached chapter list does not flash a skeleton on its way to
    being instant.
  - **The chapter list scrolls to where you stopped** rather than teleporting there. On a
    900-chapter series the jump is most of the list, and being moved is the answer to "where in
    this am I?" that simply *being* somewhere else does not give.
  - Smaller, and all for the same reason -- a state change nobody sees is a state change nobody
    trusts: reading progress bars grow rather than jump, adult covers reveal rather than flick,
    marking forty chapters read dims them together instead of replacing the list, notices slide in
    from the edge they are anchored to and the stack closes up when one is dismissed, and switching
    Ember to Glass fades up from dim rather than flashing.
- **Settings > Appearance > Motion**, with three states, defaulting to **Follow Windows**.
  - Windows already asks this in Settings > Accessibility > Visual effects > Animation effects, and
    an application that ignores the answer makes its user give it twice. Ageha reads it once per
    launch. Compose Desktop exposes no reduced-motion flag and neither does the JDK, so this goes
    through `reg.exe` to `MinAnimate` -- a proxy, and an honest one, since the supported Win32 call
    needs a native binding this project is not adding for one boolean.
  - **Every failure path keeps animating** -- missing key, unexpected value, a JVM started off
    Windows, the probe hanging. This is a preference rather than a permission: guessing it wrong in
    that direction leaves a setting one click from being fixed, where guessing it wrong the other
    way takes the interface away from somebody who never asked.
  - Reduced turns every animation into an instant state change. The state still changes and the
    screen still redraws; only the interpolation goes. A bare `tween` anywhere in a screen would be
    a hole in that, so `MotionThroughTokensTest` fails the build on one -- the same shape of guard
    as the test that keeps colours out of screens.

### Changed

- **The reader was deliberately left alone.** No page fade, no status-bar animation, no page-turn
  crossfade. Its chrome fade is unchanged at 260ms -- though it now honours the Motion setting like
  everything else. A screen transition into or out of the reader is a plain fade with no slide,
  because sliding a page of somebody's artwork in from the window edge is the
  motion-over-the-reader that CLAUDE.md 8 exists to prevent, one layer further out.
- **`docs/DESIGN.md` section 4 said "nothing bounces, staggers or overshoots", and that is now
  false.** Rewritten rather than quietly violated, with the two springs, the stagger, and the
  reasoning for the change written down -- plus a new 4.2 on reduced motion. The 190ms ceiling on a
  screen transition did not move.
- The headless render now advances a frame clock after the data lands, instead of drawing two
  frames 2.5 seconds apart. Compose animations move by the frame time they are *handed*, not by
  wall clock, so two distant renders look like two frames -- which captured the navigation
  indicator missing entirely and a staggered grid still at zero alpha, on a build where both
  worked. The reader and Continue-hero captures already stepped their clock for the same reason.
- A stale "on all three platforms" in `Preferences.kt` is gone. There is one; see CLAUDE.md 9.

### Fixed

- **The Continue Reading row's hover fill no longer flickers.** It was the one row in the app with
  a hover state and it switched on the frame the pointer crossed the boundary, which strobes when
  someone runs down the list. It crosses now, and uses the same tint every other row uses rather
  than a surface colour of its own.

## [0.2.0] - 2026-09-08

### Added

- **Chapters can be marked read and unread, from a right-click.** On a chapter row it is "Mark
  read up to here" / "Mark unread from here"; on a library cover it is the whole title at once.
  Read chapters recede to the secondary ink, the one you stopped part-way through says `reading`,
  and everything ahead is left unmarked.
  - Both labels say how far they reach, because both reach past the row that was clicked. A menu
    that said only "Mark as read" would look like it had marked the wrong forty chapters.
  - **Read state is a prefix, not a set of flags, and that is stated rather than hidden.** The
    Android schema Ageha stays importable from keeps *one* reading position per manga and has no
    per-chapter table, so marking read moves that position rather than setting flags — which is
    also what upstream does. There is no way to say "read 1–10 and 30–40 but not 11–29", and a
    schema with room to say it is a schema a backup could not cross.
  - Marking read writes a position `resume` reads as *finished*, so continuing afterwards opens
    the chapter **after** the one just marked rather than reopening it.
  - Branch-local, for the same reason resuming is: chapter order only means anything within one
    scanlation branch, so marking read on the English branch says nothing about the French one.
  - Marking a never-opened title read from the grid says so instead of doing nothing. There are no
    chapter rows to point a position at until something has been opened once.
- **A "Continue reading" button on the details screen**, beside "Remove from library", shown once
  a title has been started. It resolves through the same pipeline Continue Reading has always
  used, so it inherits all three of that pipeline's answers — open the reader, fetch a chapter
  list first, or offer a cross-source search for a source this parsers build no longer has —
  rather than re-deriving them and disagreeing.
- **The chapter list opens on the chapter you were last reading**, a couple of rows down from the
  top so there is context above it, and the header states the position as `Chapter 214 · 45%`.
  The number is the half that was missing: a percentage says how much is left and nothing about
  where you are, which for a 900-chapter series is the less useful of the two.
- **Library cards read `Ch 214 · 45%`** rather than a bare percentage. The chapter number comes
  from the history join, so it is the chapter you stopped on rather than a claim about how many
  there are — which is why it is never `Ch 214 / 260`; that total only exists once a source has
  been asked for a fresh list, and a card in a grid has not asked.
- **A home button at the right of the navigation pill**, which returns to the library at the top.
  Not the same thing as the Library word three places to its left: that switches *section*, and
  each section keeps its own back stack, so pressing it from four screens deep returns you to
  whatever details screen you last left the library on. Nothing in Ageha took you to the top.
  It lights only when you are actually there.
- **A "Default English sources" button on Explore**, beside the search box, that turns them all on
  in one press. It carries the count it would enable — "+37", not a bare number, because the sign
  is the part that says this adds rather than replaces — and it is the only *filled* control on a
  screen where everything else is a ghost outline. That is the point: the chips and the Filters
  button narrow what you are looking at, this one changes what you have, and two different kinds
  of consequence should not look alike.
  - It uses `colorScheme.primary` rather than the skin accent. The accent is the handoff's colour
    for dots, rules and borders, held to the 3:1 that non-text elements need; neither skin's
    accent clears 4.5:1 as a fill behind a label, and this fill carries one.
  - It is not drawn at all once every default is already on. A button that stays put with nothing
    left to do teaches people, once, that it does nothing.
  - Pressing it turns on every default that is off, *including* ones switched off earlier — unlike
    the automatic first-run seed, which only ever acts where no choice exists. Somebody who asks
    for the default set by name should get the default set; a button that quietly skipped what you
    once turned off would be one whose result nobody could predict. It never turns anything off,
    which is why it asks for no confirmation.
- **A fresh install now starts with about 210 sources on, instead of none.** The rule is English
  or multi-language, not 18+, and not flagged broken by the parser library — so MangaDex, ComicK,
  MangaReader.To and the ~200 others that qualify are ready to search on first launch. The other
  ~1,150 are still off and still one switch away in Explore → Filters → All sources.
  - It is computed from the catalogue rather than kept as a list of names. A baked list of 210
    constants would be a second table describing the parsers library, going stale in both
    directions the moment that library moved: sources renamed upstream would silently drop out,
    and sources added upstream would never appear.
  - **It only ever fires on a genuinely first run** — an empty sources table. Anything else and a
    parsers update that added English sources would quietly switch them on for someone who had
    curated their own list. Existing installations can opt in with `cli defaults --apply`, which
    adds only sources you have never ruled on and never re-enables one you turned off.
  - This reverses what `docs/RUNNING.md` used to promise, and that section is rewritten rather
    than quietly left standing: Ageha no longer ships with everything off. Nothing is contacted
    until a source is actually opened, which is the part that mattered.
- **One search, on `Ctrl+K` or the magnifier in the navigation pill, and it is the full page.**
  It searches your library *and* every enabled source, grouping each source separately with your
  own shelf listed first. It replaced two controls that each did half of that: the library's own
  search box, which could not reach a source, and a cross-source search screen, which could not
  see your library.
  - This shipped once as a popover command panel and that was a mistake, reported plainly: *"this
    small bar causes me to miss out on a lot of searches"*. It was right. The panel was capped at
    **five rows total — three from your library and two from every enabled source combined.** That
    is a sensible shape for a command palette jumping to a known destination, and the wrong one
    for a fan-out across 1360 sites, where the row you wanted was usually the sixth. The panel is
    gone; the same two shortcuts now open the full page, which caps nothing.
- **Settings is the handoff's screen now**: seven sections down a rail, and rows that put a label
  and a plain-language hint on the left with one control on the right. The controls are segmented
  groups and real popovers rather than stacks of radio buttons — a popover opens below its button,
  closes on Escape or a click outside, and ticks the option you chose.
- **Six settings that previously did nothing now do something.** Reading mode and right-to-left
  (a default for titles you have never set one on), page fit, how many pages are fetched ahead,
  card style, blurring 18+ covers until you point at them, and how many chapters download at once
  — the last of which applies to the queue you are already watching rather than at the next launch.
- **Downloads can be paused.** Pause all / Resume all on the storage card, and a Pause or Resume
  chip on every row. **Free up space** opens a reclaim sheet: oldest downloads first, tick what to
  remove, and it tells you how much that will free before you commit.
- **"All N chapters"** beside Continue reading on the library banner, which opens that title's
  chapter list.
- **Explore has a filter rail** instead of a dropdown menu — a panel down the right-hand side, so
  narrowing the catalogue no longer means reopening the same menu three times while it covers the
  list it is filtering. The language chips moved into it.
- **Licences** in Settings → About actually opens now. It was a button that did nothing.
- **Chapter navigation in the reader.** The bottom bar carries **Prev**, **Chapters** and **Next**
  beside the "Ch 3 of 40" readout — previous and next chapter, and a way to the chapter list of
  whatever you are reading. The list button is not the same as Close: closing returns you to
  wherever the reader was opened from, which is a shelf or a search result as often as it is a
  chapter list. `C` on the keyboard, next to the existing `N` and `P`.
  - The bottom bar now appears as soon as the chrome does, rather than waiting for a page list.
    Its *readouts* still wait — a chapter that has not loaded cannot honestly say "1 / 0" — but a
    chapter that is loading slowly or has failed outright is exactly when someone wants to skip it
    or go back to the list, and hiding the buttons along with the numbers made that a dead end.

- **Two skins, Ember and Glass.** The interface is rebuilt to the Ember & Glass design handoff:
  same layout, two materials. Ember is flat — opaque panels, warm near-black, small radii, a red
  accent. Glass is frosted — translucent panels over a cool blue-grey, fully round pills, a violet
  accent. Switch them from the square/circle control in the title bar or from Settings → Appearance;
  the choice is the same setting in both places. See `docs/DESIGN.md` §11.
- **The design's own typefaces ship with the app.** Archivo for the interface and JetBrains Mono
  for counts, hosts, page positions and the uppercase section labels — 1.8MB, both OFL-1.1, credited
  in `NOTICE.md`. Ageha no longer looks different depending on what happens to be installed.
- **Ageha draws its own title bar.** 38dp: the app's mark, a context line with live counts
  ("Library · 12 titles · 312 chapters cached"), the skin switcher, and window buttons. Dragging,
  double-click to maximise and all eight resize edges are reimplemented, since an undecorated
  window loses them.
- **Downloads shows what is on this device**, not just what is downloading right now. A storage
  card with the real used figure, your volume's real capacity, and pages counted separately from
  the thumbnail cache — the two behave differently and folding them together invites deleting
  chapters to reclaim space the cache would have returned for free. Deleting a title's downloads
  confirms first and reports what it actually reclaimed.
- **The library's Continue banner** carries the title at full size, the chapter you stopped on, and
  two lines of synopsis. Resuming after a fortnight away is mostly a memory problem, and a title
  with a chapter number does not answer "what was this about".
- **The reader's controls are two floating pills** — position, proportion and pages-left over a
  progress bar up top; a chapter pill below whose page ticks are both a readout and a way to seek.

### Changed

- **Ageha is Windows-only, and now says so everywhere.** There is no macOS or Linux build and there
  will not be one. This was always true in practice — nobody has ever run this on a Mac — but the
  project was configured for three platforms and six machines, because Compose Desktop, Conveyor
  and jpackage all default to offering them. The cost was not theoretical: the first real release
  spent eight minutes building five unverified targets and then failed outright on a sixth that
  cannot be built at all, and a red macOS CI runner blocked a Windows-only release. `machines` is
  now `windows.amd64`, CI runs on Windows alone, and only the Windows Skia native is declared
  (each of the other five was ~40MB per packaging build).
  - **Windows on ARM runs the x64 build under emulation**, which Windows 11 does transparently.
    Not a preference: no JDK vendor in Conveyor's index publishes a Windows/AArch64 21, so an
    arm64 package cannot be produced at all.
  - Runtime code that branches on the operating system — data directories, the file picker, font
    fallbacks — is deliberately untouched. It is defensive and costs nothing, and removing it would
    claim the JVM cannot start elsewhere rather than that Ageha is unsupported there.
- **Preloading now counts from the bottom edge of the window rather than from the page the reading
  position is recorded against.** In webtoon mode those are different pages — often several apart
  on a tall window or a zoomed-out strip — so "preload 6" was spending part of its budget on
  artwork already on screen, and past a certain window height it preloaded nothing at all. Pages
  still arrived blank and filled in a beat later, exactly as if the setting were off. It now means
  six pages beyond what you can see, in both modes, at any zoom and any window height. Page *url*
  resolution follows the same edge, so the two halves cannot disagree about where the artwork runs
  out.
- **Clicking a row in Continue Reading opens that title's chapter list** — scrolled to where you
  stopped — instead of jumping straight into the reader. Resuming is one extra click, on the button
  above; everything else the screen could not previously reach (the chapters either side of the one
  you stopped on, what you had already read, the description) is one fewer.
  - An opened archive is the exception and has to be: a CBZ is one chapter with no source behind
    it, so a chapter list would be empty under a failure notice. It still opens the reader.
- **The library is one column now.** The shelf rail down the left edge is gone; your shelves are
  chips above the grid, beside the ordering chips, and the width the rail was using went back to
  the covers. The library's search box went with it — `Ctrl+K` searches your library *and* your
  sources from anywhere, which is what you wanted from it.
- **Continue Reading left the navigation pill.** It is the banner at the top of the Library now,
  which is where you were already looking. `Ctrl+2` and the banner's see-all link still open the
  full list.
- **The menu bar is gone.** Ageha draws its own caption, and a native menu strip underneath it
  looked like a second application. Everything it carried is still reachable: navigation and the
  theme picker were duplicates of the pill and the switcher, backup import and export already lived
  in Settings → Library and backup, and "Open comic archive" moved there too. Every keyboard
  shortcut is unchanged, including `Ctrl+O`.
- **Settings switches are drawn for a pointer**, not a fingertip.
- **The window buttons behave like Windows' own.** They light under the pointer — close fills red
  and inverts its mark, the other two take a quiet raised step — so it is possible to tell which of
  three unlabelled marks you are about to click. The middle one turns into a restore glyph, and
  renames itself "Restore", once the window is maximised. And dragging a maximised window's title
  bar now restores it under the cursor and keeps following, instead of sliding a screen-filling
  frame half off the screen.
- **Every theme is rendered for review, not just the two skins.** `renderShell` covered Ember and
  Glass, which is how a palette ends up with two themes nobody has ever looked at. It now writes
  all four.
- **Reader page placeholders follow the reader background.** The "could not be loaded" text and the
  loading spinner were a fixed grey chosen against black; on the Paper and White backgrounds they
  were barely legible.

### Known limitations

- **Dragging the window to a screen edge no longer triggers Aero Snap on Windows.** Snap is driven
  by non-client hit-testing that an undecorated window has opted out of, and restoring it needs a
  native call Ageha does not make. `Win`+arrow still snaps.
- **Glass has no true backdrop blur.** Compose Desktop cannot blur what is behind a panel, only what
  is inside it. The translucency, the lit edge and the shadow stand in for it.

### Fixed

- **A source repeating itself no longer takes down the screen showing it.** A page of results is
  somebody else's output and nothing upstream promises each manga appears once; a lazy list treats
  a repeated key as a programming error and throws rather than rendering. So one source returning
  the same title twice killed an entire cross-source results page, including the twenty sources
  that had answered correctly. Listings are deduplicated by id where they enter the app, so browse,
  search and every future caller inherit it — and paging still advances by what the source sent,
  because the arithmetic is the source's, not ours.
- **The chapter list no longer forgets a reading position it was shown a moment ago.** It
  subscribes to the history row rather than reading it once, so marking a chapter read redraws the
  markers from what was actually written instead of from an optimistic guess that could disagree
  with it.
- **Searching sources on Explore was broken again, and this time it was the screen's own fault.**
  The picker's search box was rebuilt with the design handoff's glass chrome as a bare
  `BasicTextField` handed the query straight from the view model — the exact call `AgehaSearchField`
  had been written to replace, in a new coat. Drawing your own chrome around a text field opts out
  of the decoration, not out of the caret bug: characters were dropped and reordered as they were
  typed. The caret logic is now a state holder (`rememberSearchFieldState`) that any chrome can
  wrap, and the picker uses it. `SourceSearchTest` drives the real `SourcePickerScreen` against a
  lagging view model and fails against the wiring that shipped — which `SearchFieldTest` could not
  do, since it only ever composed the component the picker had stopped using.
- **Clearing the search box did not always clear the search.** Delete the query quickly enough and
  the field went empty while the list stayed filtered by a query no longer on screen: an edit
  returning the box to the text upstream last showed was suppressed as an echo of it. Every change
  to the text is published now; only caret moves are silent.
- **The search box put the caret behind the letter you had just typed.** `AgehaSearchField` exists
  to prevent exactly this, and had the bug hiding inside its own fix: it adopted any incoming
  `value` that differed from the last text it emitted, which cannot tell *"someone cleared the
  query"* from *"the view model has not caught up yet"*. When a recomposition landed between the
  keystroke and the state update, the field adopted the **previous** text — wiping the character
  and dropping the caret to the end of the shorter string. It now tracks the echo it is waiting
  for and ignores upstream until that arrives. `SearchFieldTest` drives a deliberately lagging
  upstream and fails against the old implementation.
- **The reader's page bar is a scrubber, and it works in webtoon mode.** Press anywhere on it to
  jump there and keep dragging to travel through the chapter. Two things were wrong before: each
  tick carried its own `clickable`, so the only way to move was to hit one 6dp target — and above
  the 40-tick cap most pages had no target at all — and in webtoon mode the strip ignored the seek
  entirely, because it only scrolled on a chapter change. `ReaderUiState.seekId` now distinguishes
  "something asked to go to a page" from "the strip reported where it is", so a seek scrolls and a
  scroll does not fight itself.
- **The storage card's numbers contradicted each other.** It read *"56 MB of 931 GB used"* beside
  *"253 GB free"* — the first says the disk holds 56 MB, the second says 678 GB is gone. 931 GB was
  the volume's capacity, and the bar plotted Ageha's bytes against it, so a 73%-full disk drew as
  empty. The headline now says **"used by Ageha"**, the bar carries a third segment for everything
  else on the volume, and the legend closes the arithmetic: `pages + thumbnails + other + free =
  capacity`.
- **Cross-source search only ever showed you two results.** The command panel behind `Ctrl+K` and
  the nav-pill magnifier drew at most three library rows and two source rows, whatever the sources
  actually returned. Both entry points now open the full-page search, which groups every source
  separately and truncates nothing — and that page now searches your library too, so nothing the
  panel could find is lost. `CommandPanel.kt` is deleted rather than left unreachable; it is in
  git history if the shape is ever wanted again.
- **The skin switcher and the window buttons were not against the right edge.** They floated in the
  middle of the title bar with a gap between them and the corner. The context line carried
  `weight(1f, fill = false)` and was followed by a `Spacer(Modifier.weight(1f))`; a `Row` divides
  its leftover space *between* weighted children, so the spacer only ever received half of it, and
  the half the text declined to fill collected after the last child instead. The text fills now,
  and the close button runs to the frame's own edge — which is what makes the corner the large
  target it is in every other Windows application. `TitleBarTest` measures it.
- **37% of the app's text was being drawn in black, on a near-black window.** Every chapter title
  in the chapter list, most of Settings, half of the details screen — 69 of 187 `Text` call sites.
  - `Text` with no `color` resolves `LocalContentColor`, whose default value is `Color.Black`.
    Material only ever overrides it from inside a `Surface`, and Ageha uses no `Surface` anywhere;
    `MaterialTheme` does not provide it, and handing it a `colorScheme` does nothing for it. So the
    app had been asking for black and getting it.
  - `AgehaTheme` now provides `onSurface` as the default. Fixed there rather than by adding
    `color =` to 69 call sites, because the 70th would have been written black too.
  - It hid because every check the project had — review, the palette's contrast tests, the whole
    screenshot set — verifies colours that are *named* somewhere, and the entire failure was text
    that names none. `DefaultTextColorTest` now asserts the default itself, in every theme, against
    the surfaces it lands on; it fails 14 of its 20 cases against the old code.
  - The details screen — the one where this was most visible — had no screenshot in the review set
    at all. `renderShell` now renders it.
- **The quietest text colour was unreadable in every theme, and invisible in two.** `--ink3` — the
  title bar's context line, every mono count, every settings hint, the meta under every cover, 44
  call sites — measured **1.26:1** in AMOLED and **1.87:1** in Light against surfaces the app
  really draws it on. WCAG asks 4.5:1 of body text and 3:1 of a *non-text* mark; this was under
  both. Ember and Glass were better and still failing, at 2.85:1 and 3.16:1.
  - `PaletteGenerator.textSafeInk` now walks the ink in tone — lighter in a dark theme, darker in a
    light one — until the worst pairing across the whole surface ramp clears 4.5:1, and stops
    there. Hue and chroma are held, so the ink stays warm in Ember and cool in Glass rather than
    collapsing to grey, and it is still quieter than `onSurfaceVariant` above it.
  - Two new factories in `AgehaContrastTest` hold it to that floor and to that ordering. The gap
    that let this ship: the contrast tests covered the roles Material names, and `--ink3` has no
    Material role.
- **A source listing stopped after its first page.** Browsing any source showed twenty titles and
  then nothing, however far you scrolled. The view model was correct; the bug was one composition
  above it. `BrowseScreen` hoisted a `derivedStateOf` out of a `remember(manga.size, hasMore)` and
  collected it from a `LaunchedEffect` keyed only on the source, so the coroutine went on observing
  the *first* derived state forever — the one that had captured `manga.size == 0` and therefore
  evaluated `true` permanently. `snapshotFlow` emits only on change, so it emitted once, while the
  first page was already in flight and the request returned early, and never again.
  - The predicate now reads the state from inside the flow through `rememberUpdatedState`, and
    mirrors the view model's own in-flight guards, so it goes false while a page loads and true
    again for the next one.
  - `BrowsePagingTest` scrolls a real composition through the grid's own `ScrollToIndex` semantics
    and fails if paging stops. Verified against the old code, where it reports exactly the symptom
    that was reported: "2 pages before scrolling, 2 after".

### Changed

- **The window background is the theme now, and only the theme.** The backdrop used to be the most
  recent Continue Reading cover, blurred and scrimmed. Three problems, each enough on its own:
  Settings > Appearance became a half-truth when the window was tinted by whatever you last read;
  a few-hundred-pixel cover thumbnail scaled to fill a 1280×860 window is mush that blur only
  partly hides; and finishing a chapter re-tinted the whole application. It is now
  `surfaceDim → surface → primaryContainer` at 35% over an opaque `surface`, and it changes when
  the theme changes and at no other time.
  - `AgehaGlass.BACKDROP_SCRIM` and `BACKDROP_BLUR` are gone with it. `AgehaContrastTest` no longer
    composites glass over scrimmed black and white — it composites over the backdrop's actual
    gradient stops, because a test asserting a stack the app no longer builds proves nothing about
    the app. `RAISED` is still held to the harsher over-raw-artwork case, which is real: menus open
    over the cover grid.
- **The Continue Reading hero shows the cover instead of stretching it.** It was the cover cropped
  to a wide band and upscaled to the window's width behind a horizontal scrim — a 2:3 portrait
  letterboxed, at the largest size anywhere in the app, from a source-supplied thumbnail. The cover
  is now drawn once at its own proportions, flush right, never scaled past the panel's height, and
  the rest of the panel is filled with the cover's own average colour.
  - `CoverAccent` averages the cover **in linear light** — averaging sRGB directly lands visibly
    dark on the black-ink-on-white-paper covers that are most of them — then keeps the hue, clamps
    saturation, and moves lightness onto the current theme's rail. It *measures* WCAG contrast on
    the result and picks paper or sumi accordingly, so the panel's readability is a computed number
    rather than a hope about the artwork.
  - `CoverAccentTest` runs that end to end over the RGB cube in both themes — 653 cases — and holds
    both the title and the faded metadata line to 4.5:1. The metadata line is the one that binds
    the constants.
  - `renderShell` now draws the hero in both themes against a real decoded cover, because the
    neutral fallback looks entirely plausible in a screenshot and a silent extraction failure would
    otherwise ship.
- **The library shelf rail is glass, and it folds.** It was the last opaque panel in the window
  after the navigation became a floating pill, and it charged a fixed 210dp for a list most people
  change a few times a day. It is now a glass card in the same layer as the rest of the chrome,
  collapsible to a 60dp strip of initials and counts — roughly one more column of covers on a
  laptop. Selection is a filled pill *and* a `primary` bar down the leading edge, because collapsed
  there is no label to read; rows answer the pointer on hover and announce themselves through
  `selectable` with `Role.Tab`. The fold is a preference, not screen state.

### Added

- **A search icon in the navigation pill: one query, every enabled source.** Cross-source search
  existed but had exactly one way in — clicking a Continue Reading entry whose source the current
  parsers build no longer had — which made the broadest search in the app reachable only by
  accident, and only by people whose history had already broken. It is now a magnifier at the end
  of the pill, a **Ctrl+Shift+F** shortcut, and a View menu item.
  - In the pill rather than as a sixth section, because the sections are *places* and search is an
    action taken on the place you are already in. It is pushed onto the current section's stack, so
    Escape returns where you were.
  - The icon carries an accessible name and a tooltip, and lights up while its results are on
    screen — otherwise it would be the one destination you can be looking at while the navigation
    claims you are somewhere else.

- **Sorting the source catalogue out: broken, 18+ and language.** The picker had one exclusive
  three-way filter and a dropdown of raw locale tags. It now has three independent filters in a
  menu beside the view chips. Against the real catalogue that is 296 adult sources and 428 broken
  ones out of 1360, so the defaults matter: **broken hidden, 18+ hidden, every language shown.**
  - "Hide known broken" is a switch rather than a view, so the useful direction finally exists.
    The "Known broken" view still overrides it -- asking to see broken sources and getting an
    empty list because of a switch elsewhere is the app appearing to lie.
  - 18+ is `contentType == HENTAI`, mirroring upstream's own `isNsfwSource` exactly rather than
    guessing wider. `DOUJINSHI`, `IMAGE_SET`, `ARTIST_CG` and `GAME_CG` are filter categories a
    source *offers*, not what a source *is*, and hiding a legitimate catalogue is the worse error.
  - Language shows names and counts -- "English (312)" -- and the counts describe what is
    reachable after the other two filters, so the menu cannot offer a language whose sources are
    all hidden.
  - **What is hidden is always counted out loud**, on the screen and in the empty state. A filter
    that silently removes rows is indistinguishable from a catalogue that never had them.
  - Scope is picker *visibility*, not capability: a source you enabled keeps working everywhere,
    global search included. All three filters persist, and the two switches are mirrored in
    Settings > Sources and updates.
- **Glass chrome, over a live backdrop.** A translucent navigation pill, breadcrumb and filter
  bars, menus and the library's shelf rail, floating over a backdrop drawn low in the stack. The
  84dp navigation rail is gone; the shortcuts it printed under every label moved into tooltips
  rather than disappearing. The library screen opens on a hero for the newest Continue Reading
  entry.
  - Compose Desktop has no `backdrop-filter`, so this is the two-layer construction that predates
    one: one backdrop, drawn once and low, with translucent fills over it. Depth comes from the
    fill, a hairline specular edge and a cast shadow.
  - The alphas are derived from WCAG AA rather than from a screenshot, which is why they are much
    higher than the usual 10-30% glassmorphism figure. `AgehaContrastTest` composites fill over
    backdrop in all three themes on every build.
  - No new colour: fills come from the scheme's container ramp. **The reader draws neither glass
    nor backdrop** -- the same `isImmersive` check suppresses both.
  - *That backdrop was originally the blurred cover of whatever you were last reading; see the
    Changed section below for why it is now derived from the theme alone.*

- **A real Windows installer, built and verified by installing it.** `./gradlew :app:desktop:packageMsi`
  produces `Ageha-<version>.msi`: a per-user install needing no administrator prompt, with a Start
  menu entry, a desktop shortcut, and an entry in Apps & Features. Installed, launched, and
  uninstalled on a real machine rather than inspected in a build log.
- **A CJK font, bundled with the Linux packages only.** Windows and macOS ship CJK coverage and get
  nothing extra; Linux packages carry one 16MB `NotoSansCJKjp-Regular.otf`, which `CjkFontTest`
  verifies covers Japanese kana and kanji, Korean hangul, both Chinese variants and Latin. Fetched
  at build time from a tag-and-hash-pinned URL rather than committed. `AgehaFonts` uses it only
  where a script has no system font, so a Linux user with their own Noto package keeps Inter and
  Source Serif; Settings > Appearance says which is in play.

- **An end-to-end journey test that drives the real app.** `:app:desktop:e2e` boots the whole
  application, renders the real shell, and walks the path a person actually takes: open a chapter,
  turn pages with the keyboard, close the application, open a *new* one against the same profile,
  and follow the Continue Reading entry back. It asserts on the page image on screen, not on the
  view model. `-PwithNetwork` adds a second journey against live MangaDex -- enable the source,
  browse it, open a manga, open a chapter. Three of the four bugs from the previous session were
  invisible to unit tests and only appeared once something rendered; this is the harness that
  renders it. Excluded from `check`, because booting an application installs Coil's process-global
  singleton and would decide what every later test in the JVM saw.
- **`AGEHA_DATA_DIR` / `-Dageha.data.dir`** points Ageha at a different profile directory. The
  end-to-end test, the shell and gallery renderers, and the webtoon profiler now all use scratch
  profiles under `build/`. Before this, generating a screenshot wrote a sample CBZ into whatever
  reading history was on the machine.

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

- **Milestone 4 -- database, persistence and Android backup import.**
  - `:core:backup` imports a Kotatsu-Redo Android backup archive: history, favourites, categories
    and sources, with reading position restored exactly. Moved ahead of any UI deliberately, since
    it exercises every column of the schema against data the Android app actually wrote.
  - Reading is lenient (unknown fields and unknown sections are tolerated, so a backup from a newer
    Android app still imports what it can); writing is strict (one transaction, everything or
    nothing).
  - The result names what it could not restore. Sections Ageha does not support yet, entries it
    does not recognise, and individual rows dropped -- such as a favourite referencing a category
    the backup never defined, which would otherwise fail the whole import on a foreign key.
  - `cli import <backup.zip>` and `cli library`.
- **Milestone 4 -- the database.** `:core:database` on Room 2.8 with the bundled
  SQLite driver, which is what proves Room works off Android. Schema declared at **version 28** to
  match the Android app rather than replaying 27 migrations that could never run here. Eight
  entities so far (manga, tags, manga_tags, chapters, history, favourites, favourite_categories,
  sources); the remaining nine arrive with the features that use them. Schema is exported to
  `core/database/schemas/` so it can be diffed against the Android app's.

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

- **Reader pages were decoded at window size, so zooming magnified a blur.** Coil sizes a decode to
  the composable that asked for it, and off Android it resamples with the three-argument
  `Canvas.drawImageRect`, which takes no `SamplingMode` and so uses no mipmaps. A 2000px scan in a
  900px viewport therefore became a 900px bitmap, badly resampled, and that bitmap was all the
  reader had -- zooming enlarged the raster instead of revealing the detail that had been decoded
  away. Reader pages now go through `AgehaImages.readerRequest`, which decodes at the source's own
  resolution, and are drawn at `FilterQuality.Medium` (mipmapped, cached) rather than the default
  bilinear. Covers deliberately keep the sized request.
- **The webtoon strip was stretched to the width of the window.** A webtoon page is around 800px;
  filling a 1920px window with one is a 2.4x upscale of somebody's line art. The strip is now a
  centred column of the source's own width, never enlarged past it by default. This is also five
  times *faster*: `:app:desktop:webtoonProfile` goes from p50 8.99ms to 1.81ms, because not
  upscaling on every frame is cheaper than upscaling on every frame.
- **Ctrl+wheel did nothing in webtoon mode.** The handler read the wheel on the Main pointer pass,
  by which point the `LazyColumn`'s own scrollable had already consumed the event, so Ctrl+wheel
  scrolled. It is now read on the Initial pass and changes the strip's width -- 25% to 600% of the
  source, remembered in preferences and shown in the top bar. Past the window width the strip
  scrolls sideways (Shift+wheel, or a horizontal wheel) rather than stopping, so zooming in far
  enough to read small print actually works. Scaling the list with a `graphicsLayer` instead was
  tried and rejected: it scales the viewport too, so the edges clip and one notch of wheel travels
  a different distance at every zoom level.
  - The first attempt clamped the strip to the window width, which broke the control it was meant
    to provide: with a source page already as wide as the window, a run of zoom levels all resolved
    to the same width and several notches in either direction did nothing at all. Reported as "the
    webtoon version doesn't allow me to zoom out". The width no longer takes the viewport as an
    input, and `WebtoonStripWidthTest` asserts every notch across the range changes it.
- **A page reserved no height until its image arrived**, so an unloaded page in the webtoon strip
  was zero pixels tall, the list composed a long run of them at once, and the strip lurched every
  time one resolved. Each item now reserves space from an aspect ratio learned as the chapter
  decodes. The file's own comment had claimed this for some time; it is now true.
- **Wheel scrolling stepped a whole notch per frame.** The strip now eases each notch across a few
  frames. It travels exactly the distance the wheel asked for -- deliberately not a fling, which
  would overshoot the panel you were scrolling towards.
- **A zoomed page could not be panned at all.** `ZoomPanState.onContentChanged` was never called
  from anywhere, so the content size stayed zero, both pan bounds computed as zero, and every drag
  was clamped straight back to the origin. Zoom worked and panning silently did nothing.
- **The reader's top bar vanished while you were reaching for it.** The auto-hide timer counted
  page turns and nothing else, so a bar revealed by a click counted down regardless. It now waits
  for the pointer to actually move before starting, and stops entirely while the pointer is over
  the chrome.
- **Typing in any search box put the caret in front of the letter.** Every query is hoisted into a
  view model and read back through a `StateFlow`, so the text a field is handed lags its own
  keystroke; the `String` overload of a text field pairs its internal caret with whatever value it
  is given, and on the frame where the caret is new and the text is still old, the caret is clamped
  to zero. All five search fields now use `AgehaSearchField`, which owns its own `TextFieldValue`
  and adopts an external value only when it is a genuine outside change. Note that this fix is by
  construction and is **not** covered by a reproducing test: `performTextInput` drives a field
  through the semantics layer, below which the desync happens, so the defect cannot be expressed in
  a Compose UI test.

- **Uninstalling Ageha deleted the user's entire library.** jpackage derives the install directory
  from the package name, so a per-user install landed in `%LOCALAPPDATA%\Ageha` -- byte for byte
  the directory `AgehaPaths` keeps the database, cookies and preferences in. Installing dropped
  `app\`, `runtime\` and `Ageha.exe` on top of somebody's library, and uninstalling removed the
  directory and everything else in it. Found by installing the MSI and then uninstalling it, with
  the profile backed up first; the binaries now live in `%LOCALAPPDATA%\Ageha Reader` and the
  full install, launch and uninstall cycle has been re-run to confirm the library survives.
- **`conveyor.conf` had never parsed.** It used `/* */` block comments, which HOCON does not have,
  so the file that was "only ever syntax-checked" failed on the first line Conveyor read. Also
  pinned `app.version`, since the Gradle plugin hands over `0.1.0-SNAPSHOT` and no installer
  format accepts a snapshot suffix. `conveyor json` now resolves the whole configuration, and
  confirms the CJK font reaches the two Linux machines and none of the other four.

- **The reader claimed "1 / 0" while a chapter was still loading.** Everything the status bar shows
  is derived from the page list, but it was drawn outside the `isLoading` guard -- so a chapter
  opened from a live source displayed "1 / 0" and "Chapter 1 of 0", confidently and wrongly, for
  as long as the source took to answer. Now it waits for pages; the top bar, whose Close button is
  exactly what someone wants during a slow load, still does not. Found by the end-to-end driver.
- **A search on Explore with no sources enabled said "No sources match".** On a fresh installation
  nothing is enabled, so *every* search of the enabled sources comes back empty -- and the message
  sent the user looking for a source that was sitting right there, switched off. It now says how
  many sources match in the full catalogue and offers to show them. The empty-list state already
  did this; the searched state did not, which is the state a new user reaches first.
- **`createDistributable` and `runDistributable` had never worked.** jpackage rejects a version
  that is not strictly numeric-dotted, and the project version carries `-SNAPSHOT`, so every
  invocation failed with `Version [0.1.0-SNAPSHOT] contains invalid component [0-SNAPSHOT]`.
  Conveyor does not go through jpackage and so never hit it, which is why it went unnoticed: the
  shipping path worked and the local one did not.
- **`:app:desktop:e2e` passed by running nothing.** The root build applied `excludeTags` to every
  `Test` task, including the one that opts *in* to the `e2e` tag, and JUnit resolves
  include-and-exclude of the same tag as excluded. The tag filtering is now scoped to the standard
  `test` task. A test task that reports success without running a test is worse than one that
  fails.

- **Two coexisting builds shared one OkHttp cache directory.** Every child context built its own
  client against the default cache dir, so the compatibility gate -- which constructs a second
  context while the live one is serving -- put two `Cache` instances on one directory on every
  update check. OkHttp calls that an error, and classloader isolation does not help because the
  directory is shared regardless. The parent now owns one client and injects it; the gate gets a
  cache-less view of it.
- **Downloaded builds are now locked and verified.** A per-build `lock.json` records a SHA-256 for
  every file, verified before every load rather than only after download. Missing, modified and
  unexpected files all fail. Downloads are additionally checked against the repository's published
  `.sha1` where one exists.
- The shim surface is now a tracked metric: 13 members, held to a test that fails in either
  direction. It went from 14 when `getPreferredLocales` turned out to be overridden with an
  implementation identical to the upstream default.
- `SourceFailure.Blocked` and HTTP status classification, so a 403 from bot protection no longer
  reports as an unreachable network.
- The HTTP stack is now shut down on close. OkHttp holds a dispatcher pool, live sockets and an
  open cache journal, and the open journal prevents an update from replacing the build it is
  updating on Windows.
- `selfCheck` no longer swallows `LinkageError`. `runCatching` catches `Error` too, which reported
  a wholesale version mismatch as "25 sources are broken" and pointed at the wrong thing entirely.

- **Milestone 5 -- the design system.**
  - `:core:designsystem` -- one Material 3 theme in light, dark and AMOLED, a desktop-tuned type
    scale, a 4dp spacing scale, shape and motion tokens, and the reader's brand-free backgrounds.
  - `:tools:brandkit` -- build-time only. Derives the tonal palettes from the brand seed using
    Google's colour science and writes them out as literal hex, and rebuilds every icon and logo
    asset from `brand/ageha-logo-source.jpg`.
  - `:app:desktop` -- the theme gallery, showing every token in all three themes side by side.
    `renderGallery` writes the same view to a PNG with no window, so review does not require this
    machine.
  - `docs/DESIGN.md` with the resolved token values and the reasoning behind each departure from
    stock Material 3.
  - The vermillion accent is exposed as three components rather than as a colour, so it cannot
    become a general-purpose highlight.
  - 117 new tests: 66 WCAG contrast pairs across the three themes, the palette's structural rules,
    the reader's freedom from brand colour, and the hand-written `.ico`/`.icns` writers parsed back
    byte by byte.

- **Milestone 6 -- the desktop UI: explore and library.**
  - `:core:data` -- repositories over the database and the source facade. Every source call comes
    back as a `CatalogResult`, so a site being down is a value a screen renders rather than an
    exception that unwinds it.
  - `:core:image` -- Coil 3 on **OkHttp**, sharing the source stack's client so covers travel with
    the same cookies and User-Agent the listing did.
  - `:feature:library` -- shelves in a persistent rail with live counts, a grid that reflows with
    the window, continue-reading, filter and sort.
  - `:feature:explore` -- the 1360-source picker with per-source enable, one-source browsing with
    paging, and a two-pane details screen.
  - `:app:desktop` -- the application shell: navigation rail, per-section back stacks, keyboard
    shortcuts, a menu bar, remembered window geometry, and Koin wiring with an ordered shutdown.
  - `renderShell` draws the real application headlessly against the real graph, so a build that
    would open a blank window fails in CI instead.

- **Milestone 7 -- the reader.**
  - `:feature:reader` -- paged mode left-to-right and right-to-left, double-page spreads with
    cover-offset handling, continuous vertical webtoon mode, zoom and pan about the pointer, four
    fit modes, auto-hiding chrome, and full keyboard control.
  - Reading position is persisted per manga and restored exactly, including the webtoon strip's
    scroll fraction -- a page index alone is not a position when a page is twelve thousand pixels
    tall.
  - Schema **v29** adds the Android app's `preferences` table, by auto-migration, so per-manga
    reader mode survives a backup round trip.
  - CBZ archives are read straight out of the zip, through the same image pipeline as remote
    sources. CBR is refused with a reason the user can act on rather than a generic failure.

- **Milestone 8 -- settings, downloads and the JavaScript engine.**
  - `:core:js` gains a **real backend**: Rhino, serving the `PLAIN_SCRIPT` tier, sandboxed and
    time-bounded. This is what makes the ~257 conditionally-JS sources work.
  - `:feature:settings` -- appearance, reader defaults, backup import, and the first user-facing
    view of Layer 1: active build, update policy, check, roll back, pin, and what the JavaScript
    engine can and cannot do.
  - `:feature:downloads` and `ChapterDownloader` -- offline chapters written as ordinary CBZ, two
    at a time per source, with a queue that reports what it skipped and why.
  - A native file picker and `Ctrl+,` for settings.

- **A webtoon profile.** `./gradlew :app:desktop:webtoonProfile` builds a real 200-page strip,
  scrolls it end to end through the real reader with real scroll events, and reports frame times,
  heap and how much of the strip actually resolved. This closes the open risk from
  `docs/ARCHITECTURE.md` 1.4: every frame lands inside the 60Hz budget and the heap peaks near
  30MB against the ~1.5GB that holding all 200 decoded pages would need, so the stock `LazyColumn`
  stays and no custom layout is needed.
- **A test that Compose draws through Ageha's image loader**, covering the singleton handoff, the
  archive fetcher's ordering ahead of the network, and -- by putting an interceptor on the client
  and watching it fire -- that requests really go through Ageha's OkHttp stack rather than the one
  Coil registers for itself.

- **Milestone 9 -- packaging and CI.**
  - `conveyor.conf` -- signed, self-updating installers for six targets from one machine. The
    icon ladder is handed over explicitly rather than generated, so the simplified small-size mark
    survives.
  - `.github/workflows/` -- `ci.yml` on all three operating systems, `parsers-watch.yml` every six
    hours, `source-smoke.yml` nightly, `release.yml` on a tag.
  - `cli smoke` -- exercises a random sample of real sources end to end, seeded so a failing run
    can be reproduced exactly.
  - `docs/UPDATING.md` and `docs/RELEASING.md`.

- **Continue Reading.** Everything read, most recent first, with cover, title, source and the
  chapter stopped on. Reached by `Ctrl+2`, and the most recent few appear as a shelf on the library
  screen.
  - **Opening an entry resumes the exact page** -- unless that page was the last of its chapter, in
    which case the *next* chapter opens at page one. When there is no next chapter the entry
    reopens where it was and says it is caught up.
  - **A quick search that is actually quick.** Filtering by title runs over a list already in
    memory: no network, no database round trip, no debounce. It is the one search in Ageha that
    answers as fast as it is typed.
  - **An entry whose source is gone stays put, marked unavailable**, and offers a search for the
    same title across every enabled source. Sources disappear on a parsers downgrade and whenever
    upstream retires a site; the reading history belongs to the user, not to the source.
  - Entries can be removed one at a time, or all at once from Settings > Library. Both are soft
    deletes, so a future sync cannot resurrect what was cleared.
  - Schema **v30** adds `history.page_count` -- Ageha's own column, the first divergence from the
    Android schema. Without it "was that the last page of the chapter" is unanswerable. Additive
    and defaulted, so Android backups still import and simply report the count as unknown.
  - `ReaderRepository` now stores the chapter list alongside the reading position, guarded so it is
    not rewritten on every page turn. The `chapters` table had existed since Milestone 4 with
    nothing writing to it; it is what lets the last chapter be *named* and the next one *found*
    with no network call.
- **One app version, in `:core:model`.** Four unrelated places need it -- the backup index, sync's
  `X-App-Version` header, the `added_in` column recording which release first saw a source, and the
  update check -- and three had grown their own copy while this work was going on. Two constants
  that must agree and do not have to are a bug with a delay on it. `NAME` and `CODE` are separate
  because two formats Ageha does not own insist on an integer, and `CODE` is not derived from
  `NAME`: deriving it would mean inventing an encoding and then being stuck with it.
  - `added_in` now records a real release instead of a hardcoded 0, which was waiting on versioning
    that the packaging milestone was supposed to bring and did not.

- **Layer 2 gets its settings toggle**, which the brief asked for and which was the last thing on
  its list still missing. Settings > Sources and updates > Ageha itself: check quietly, check and
  tell me, or never check.
  - **It does not claim to control installation, because Ageha cannot.** On all three platforms
    the installer owns that -- MSIX, the macOS bundle updater, apt -- each configured at package
    time with no runtime switch. A checkbox claiming otherwise would be a lie. What the toggle
    controls is whether Ageha looks and whether it tells you, and the panel says so in those words.
  - "Never check" makes **no request at all**, rather than making one and hiding the answer.
  - Versions compare numerically. Lexicographically `"0.10.0" < "0.9.0"`, which would tell everyone
    on 0.9 to upgrade to 0.10 and everyone on 0.10 that they were ahead of it.
  - `AgehaVersion.CURRENT` is guarded by `:app:desktop:checkAppVersion` against the project
    version -- the same guard `:core:parsers` puts on its bundled parsers version, and verified to
    actually fail when the two disagree.

- **Sync, against a self-hosted kotatsu-syncserver.** The brief asked for this "if feasible" and
  to flag it if the protocol turned out to be Android-coupled. It is not: the protocol is four
  POSTs of JSON over OkHttp, and it is the Android app's *implementation* -- `AccountManager`,
  `ContentProviderClient`, `AbstractThreadedSyncAdapter` -- that is Android, none of which is on
  the wire. `docs/FINDINGS.md` 7 records the evidence; the question had been open since
  Milestone 1.
  - `:core:sync` -- the wire format, the HTTP client, the account store and the merge engine.
    Reading history, favourites and categories travel both ways, so a desktop and a phone stay in
    step through the same server the Android app uses.
  - **The payload carries tombstones, and a backup deliberately does not.** This is the whole
    reason `deleted_at` has been in the schema since Milestone 4 with nothing using it. A sync
    payload without tombstones cannot express a deletion, so every other device pushes the deleted
    row straight back.
  - **Tombstones are collected after the exchange, never before.** A tombstone the server has not
    seen is a deletion that has not propagated; collecting it early deletes the *deletion*, and the
    next sync restores what the user removed. The four-day window is the Android app's, matched on
    purpose -- two clients collecting on different schedules is how a deletion comes back.
  - An expired token is refreshed once and the retry carries the new one. Explicitly, rather than
    through an OkHttp `Authenticator` as upstream does: an `Authenticator` is a blocking callback
    on a connection thread, and re-entering suspending code from one deadlocks against a slow
    server.
  - Settings > Sync, and `cli sync [run|status|login|logout]`. `login` reads the password from the
    console with echo off and refuses a piped stdin, because a password given as an argument lands
    in shell history and in the process table.
  - Syncs at startup and on demand, **not on a timer**. The protocol is not incremental, so a
    desktop app left open all day would spend it re-sending the whole library.
  - Twelve tests drive the engine through a real socket against a server that speaks the protocol,
    covering the payload shape, tombstones both ways, 204-is-not-empty, token refresh, an
    unreachable host and a malformed reply.

- **Backup export.** Ageha writes the Android app's own backup format, so the migration runs both
  ways and a desktop library is no longer trapped in one file on one disk. `File > Export backup`,
  a button in Settings > Library, and `cli export [file]` for a scripted nightly copy.
  - **The archive is upstream's, not Ageha's own**, which is what makes it restore onto a phone as
    readily as onto another desktop -- and means the format has exactly one reader here, so the
    round trip is testable end to end rather than by inspection.
  - Written to a `.part` and renamed on success, the discipline downloads already use. A
    half-written archive that looks like a backup is discovered at restore time, which is the one
    moment there is nothing to fall back on.
  - Streamed in windows of 64 rows. A backup embeds the full manga record with its tags inside
    every history row and again inside every favourite row, so building one in memory first is the
    difference between an export that works on a real library and one that works on a small one.
  - **Every paged dump carries a primary-key tiebreaker, which upstream's do not.** `updated_at`,
    `created_at` and `sort_key` are all non-unique, and paging a tie with `LIMIT`/`OFFSET` lets one
    row appear in two windows and another in none. The count written looks right either way; the
    damage only shows on restore.
  - Tombstones stay home. Soft-deleted history and favourites are excluded, because the importer
    clears `deleted_at` on the way in -- exporting a tombstone would resurrect a deletion as data.
  - Only enabled sources are written, as upstream does. Ageha ships 1360 disabled, and the rest
    would be a copy of the source list.
  - Sections Ageha holds no data for -- `bookmarks`, `settings`, `scrobbling`, `statistics`,
    `saved_filters` -- are absent rather than written empty. Absent reads as "nothing to say";
    empty reads as "none of those", which for settings is a claim a restoring app could act on.

- **Cross-source search.** One title against every enabled source at once, four requests in flight,
  grouped by source and skipping sources that cannot take a search term at all.

- **Local comic archives, end to end.** File > Open comic archive reads a CBZ straight into the
  reader, through the same image pipeline as a remote source. Reading position persists for local
  files too, since the archive's id is derived from its absolute path.
- **Application notices.** Backup import and archive opening now report their outcome *in the
  window* instead of on stdout, which a windowed application does not have. The backup importer's
  full account -- restored, unsupported, unrecognised, and every dropped row with its reason --
  is shown monospaced and stays until dismissed.

### Changed

- **External tracking was cut from scope and replaced by Continue Reading.** Shikimori, AniList,
  MyAnimeList and Kitsu are gone from the brief and from `CLAUDE.md`. All four need an OAuth client
  registered by the project owner -- credentials that cannot be invented, committed to a public GPL
  repository, or tested without being real -- and writing them blind would have shipped four
  untested network clients. The question they were for, *where was I and what is next*, is answered
  from rows Ageha already stores, with no account and no network. See `docs/ARCHITECTURE.md` 7b.
- **Section shortcuts renumbered** to make room: Continue Reading is `Ctrl+2`, Explore `Ctrl+3`,
  Downloads `Ctrl+4`. Library stays `Ctrl+1` and Settings stays `Ctrl+,`.

### Fixed

- **The index of every real Android backup was silently unreadable.** The Android app writes each
  section through one `writeJsonArray` helper, `index` included, so the entry is an array holding
  one object. Ageha decoded it as a bare object and swallowed the failure with `getOrNull()`, so
  `result.index` was null for every genuine archive ever imported and the app version that wrote a
  file was never available. The tests passed throughout, because the fixture wrote the shape the
  parser wanted rather than the shape the app produces -- the bug was invisible from inside the
  project and only upstream's writer shows it. Both shapes are accepted now, the fixture writes the
  real one, and the failure to read an index is still not fatal: it is provenance, not data.

- **Webtoon mode never resolved page urls past the first few.** `resolveAround` was called from
  `goToPage` and nowhere else, and scrolling a continuous strip does not go through `goToPage` -- so
  a chapter opened, resolved five pages, and every page after that stayed a loading spinner for as
  long as it was open. Found by the new 200-page profile, which reported 5 pages resolved and 195
  pending *while posting excellent frame times*, because scrolling past placeholders is cheap.
- **Changing the reader mode failed for anything not in the library.** `preferences.manga_id` is an
  enforced foreign key and `setMode` wrote no manga row, so switching to webtoon mode on something
  opened from search, from a listing or from a local file threw a constraint violation instead of
  changing the mode. The twin of the history bug fixed above it; `setMode` now takes the manga and
  writes the row first, exactly as `savePosition` does.

- **The reader's final position could be lost on quit, or written into a closing database.** The
  flush used `scope.launch(NonCancellable)`, and `NonCancellable` is a `Job` -- so `launch` took it
  as the *parent* and detached the write from the application scope entirely. Shutdown's `join()`
  then had nothing to wait for and closed the database mid-transaction. Switching to a plain launch
  fixes that and opens the opposite hole: a coroutine cancelled before it is dispatched never runs
  at all. It is now `launch(start = UNDISPATCHED) { withContext(NonCancellable) { … } }`, which is
  both a child of the scope and already running before the cancellation lands. Found by the shell
  render, which began throwing `statement is closed` once the position write grew a second
  statement. Pinned by a test that fails for either mistake.

- **Cover and page images used Coil's default loader, not Ageha's.** The configured loader was
  registered in DI and handed to Compose nowhere, so every `AsyncImage` silently fell back to a
  default with neither the archive fetcher nor Ageha's OkHttp client -- meaning no cookie jar, no
  User-Agent and no per-source `Referer` on any image request. Found by rendering the reader
  against a real CBZ and getting a blank page.
- **The archive fetcher never matched.** Coil runs its mappers before consulting fetchers, so the
  `cbz://` model had already become a `coil3.Uri` and a factory matching only `String` declined
  every request.
- **Reading anything not already favourited saved no history.** `history.manga_id` is an enforced
  foreign key and the manga row was only ever written by favouriting, so reading from search, from
  a listing, or from a local file failed the constraint and recorded nothing.
- **Shutdown raced the database.** Cancelling the application scope does not wait for work already
  inside a query, so closing could throw `connection is closed` from a background thread. Shutdown
  now joins the cancelled scope with a bounded grace period, and the reader's final position write
  is `NonCancellable` so the last page turn survives quitting.

### Notes

- **The sync password is stored in a file, and the app says so.** Desktop has no system keychain a
  plain JVM can reach without a native library per platform. The protocol refreshes an expired
  token by re-sending the password, so remembering it is what makes a startup sync silent. The file
  is restricted to its owner where the filesystem can express that, and leans on the user-profile
  ACL on Windows where it cannot -- the same protection the cookie jar and the database already
  have. It is deliberately *not* encrypted with a key stored beside it, which protects nobody.
  Declining to store it is supported and costs a prompt when the token expires.
- **A bare sync hostname is normalised to `https://`, where the Android app assumes `http://`.**
  That request carries a password.

- **`history.page_count` does not survive a backup.** It is Ageha's own column (schema 30) and the
  archive format has no field for it. Inventing one would produce a file the Android app does not
  understand, for a value that reads as "unknown" anyway and degrades to resuming the exact saved
  page. A round trip forgets it, exactly as an Android import arrives without it.
- **Local archives are exported like anything else.** A CBZ opened from disk gets a history entry
  whose url is an absolute path, and that path is unlikely to resolve on another machine. It is
  kept rather than filtered: the position survives when the path does match, and Continue Reading
  already shows an entry it cannot open as unavailable instead of failing. Dropping them would
  guarantee the loss that keeping them only risks.

- Sources are addressed and persisted **by name string, never by enum or ordinal**.
  `MangaParserSource` is generated at build time by KSP, so its constants differ between parser
  builds.
- Ageha ships with **no JavaScript backend**. Around 20 of 1360 sources need one and report a
  specific, actionable failure rather than a generic error.
- Layer 1 does **not** promise that source updates never need an app release, and the docs no
  longer imply it. `evaluateJs` gaining a third parameter upstream is proof the host contract
  moves. The promise is narrower and honest: routine source updates never need one, and when the
  contract does move, the running build keeps working and the user is told once.
- The colour tokens are **generated and committed**, not hand-picked. Google's colour-science
  library is a build-time dependency of `:tools:brandkit` and is not on the application's
  classpath.
- All three themes pass WCAG AA on body text, asserted on every build rather than checked once.
- Nothing brand-coloured reaches the reader, and a test enforces it: reader colours carry under 6%
  chroma and are never a design-system token.
- Fonts are **not** bundled, and now deliberately rather than pending. ~40MB of CJK faces would buy
  nothing on Windows or macOS, where Skia's per-glyph fallback already reaches past the chosen
  family and the system ships coverage anyway; it buys something only on a Linux machine with no
  CJK font, where the distribution's own font package is the right fix. The families stay an
  explicit preference chain resolved against what is installed, and Settings > Appearance names any
  script with no font **in red**, so a user seeing boxes knows it is a missing font and not a
  broken source.
- Sources start **disabled**. Ageha ships 1360 and only ever contacts the ones a user turns on.
- The library grid uses `GridCells.Adaptive`, not a fixed column count: this is a resizable
  desktop window, not a phone.
- A source failure never clears results already on screen, and a non-transient failure stops the
  pager rather than retrying into a block.
- The reader's arrow keys follow the **reading direction**: in right-to-left mode Left advances.
  Space and Page Down always mean forward, as they do in any document.
- `ReaderMode` ids are the Android app's and are **not** ordinals -- STANDARD is 1, WEBTOON is 2,
  REVERSED is 3, VERTICAL is 4.
- Webtoon mode uses a lazy list. That was flagged as a risk in ARCHITECTURE 1.4 and the risk is
  not yet closed: it has not been profiled against a real 200-page strip.
- The JavaScript engine is **Rhino, not QuickJS**. The tier it serves is pure ES5 computation, and
  Rhino removes six native binaries from the packaging problem. `JsRuntime` is unchanged, so the
  choice is reversible.
- Scripts run with no Java bridge and a wall-clock deadline, because the script is served by the
  site being scraped.
- Downloads are limited to **two at a time per source**. The cost of being impolite to a small site
  is not a slow download; it is a block affecting every Ageha user of that source.
- A `.cbz` on disk is always complete: downloads are written to `.part` and renamed on success.
- The nightly smoke test fails only when failures are numerous **and alike**. A rate-only gate was
  tried first and tripped on an ordinary night: 9 of 12 sources failed, across three unrelated
  causes, which is simply the state of the scanlation web.
- CI runs on **Windows only**, which is the platform Ageha ships to (CLAUDE.md 9). It used to run
  three, on the reasoning that every platform-specific bug found here had been a Windows
  file-handle problem — but a green Linux runner proves nothing about the product, and a red macOS
  one had already blocked a Windows-only release.
- Releases are **unsigned**. Certificates are a separate paid cost; `docs/RELEASING.md` lists the
  routes and the workarounds users need meanwhile.
