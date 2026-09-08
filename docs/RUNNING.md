# Running Ageha

For the person who installed this and then didn't touch it for six months. No Gradle, no Kotlin,
nothing you need a terminal for. If you want to *build* it rather than run it, that's
[CONTRIBUTING](../README.md#building) instead.

---

## 1. Install it

### Windows

Double-click **`Ageha-0.1.0.msi`**.

It installs for **you only** — no administrator password, nothing in `Program Files`, nothing that
touches other accounts on the machine. It takes about a minute, mostly spent unpacking the bundled
Java runtime.

You will get a scary warning first. That's expected — see [§2](#2-the-scary-warning).

When it finishes you'll have:

- a **desktop shortcut** and a **Start menu entry**, both called Ageha
- an entry in **Settings → Apps → Installed apps**, for when you want it gone

### macOS and Linux

**There are none, and there will be none.** Ageha is a Windows application. This is a deliberate
scope decision rather than a gap waiting to be filled — see `CLAUDE.md`.

---

## 2. The scary warning

**Ageha is not code-signed.** A signing certificate costs real money every year, and this is a free
GPL project. So the operating system doesn't know who wrote it, and says so in the strongest terms
it has.

This is worth understanding rather than just clicking through: the warning does **not** mean
Windows found something wrong with the file. It means nobody paid to have their name attached to
it. Those are very different claims, and the dialog is not good at saying which one it's making.

### Windows — "Windows protected your PC"

A blue box appears. There is no obvious "run anyway" button, which is deliberate.

1. Click the small **More info** link in the box.
2. A **Run anyway** button appears underneath.
3. Click it.

You'll see this on the `.msi` the first time. You should **not** see it every time you launch the
app afterwards.

If instead you get a browser warning while downloading ("this file isn't commonly downloaded"),
that's the same situation wearing a different hat — choose Keep.

### When you should actually worry

If you got Ageha from anywhere other than the project's own GitHub releases page, stop and delete
it. The warning above is normal for an unsigned build; it is not a reason to trust a copy from
somewhere unexpected.

---

## 3. First launch

Ageha opens on the **Library**, which is empty. This is the part that surprises people, so:

**Ageha ships with about 1,360 manga sources and starts with roughly 210 of them on.** Those 210
are the ones in English or serving many languages at once, minus anything 18+ and anything the
parser library itself flags as currently broken. The other ~1,150 stay off: other languages, adult
sources, and the known-broken ones.

Nothing is contacted until you open a source. A source being switched on means it appears in
Explore and is included when you search everything at once — not that Ageha is talking to it in
the background.

**You can change all of it**, and the first run is a reasonable moment to:

1. **Explore** in the left rail (or `Ctrl+3`), which opens on your enabled sources.
2. Flip the **toggle** on the right of any row to turn that source off, or on.
3. Click the **row itself** to browse it.
4. **Filters** → **All sources** lists the whole 1,360, including everything that is off. That's
   where you turn on another language, or an 18+ source, if you want one.

Once you have made a choice about a source it is yours: Ageha never revisits one you have switched
on or off, including after an update that adds new sources.

Two other things you'll notice on first launch:

- **It takes a few seconds to start.** It's loading the parser library that knows how to read those
  1,360 sites. Subsequent launches are quicker.
- **Some sources are marked "known broken"** in red. That's upstream's own flag, shown rather than
  hidden, so you find out here instead of by watching a source fail.

---

## 4. Reading, and getting back to where you were

- Click a manga to open its details, then a chapter to read it.
- **Space** or **Page Down** turns the page forward. Arrow keys follow the reading direction —
  manga is right-to-left by default, so **Left** is forward and **Right** is back. Space always
  means forward, whichever mode you're in.
- **F** for fullscreen, **Escape** to close the reader, **H** to show or hide the bar at the top
  and bottom.
- The bar with the page count **fades out after a couple of seconds**. That's not a glitch; move
  the mouse or press **H** to bring it back.

**Continue reading** (`Ctrl+2`) is the screen that matters. Everything you've opened is there, most
recent first, and clicking an entry puts you back on the exact page you stopped on. If you finished
a chapter, it opens the *next* one at page 1 instead — which is almost always what you wanted.

There's a search box on that screen too. It filters what you've already read, instantly, with no
network involved.

### Opening a file you already have

**File → Open comic archive** reads a `.cbz` from disk. It doesn't get added to your library, but
your place in it is still remembered.

`.cbr` files will not open — those are RAR archives, and Ageha will say so rather than failing
vaguely. Repackaging as `.cbz` works.

---

## 5. Where your stuff lives

**Windows:**

| What | Where |
|---|---|
| Your library, history, reading positions, settings | `%LOCALAPPDATA%\Ageha` |
| Cached pages, covers, downloaded parser updates | `%LOCALAPPDATA%\Ageha\cache` |
| The app itself | `%LOCALAPPDATA%\Ageha Reader` |

Paste `%LOCALAPPDATA%\Ageha` into the File Explorer address bar to get there.

Those first two are deliberately **not** the same folder as the app. Uninstalling Ageha removes
`Ageha Reader` and leaves your library completely alone — which is worth knowing, because the
first version of the installer got this wrong and would have deleted it.

**To back up everything that matters:** copy `ageha.db` out of `%LOCALAPPDATA%\Ageha`. That single
file is your library, favourites, history and reading positions. Or use **File → Export backup**,
which writes the same thing as a zip the Android app can also read.

**To start completely fresh:** close Ageha, delete the `%LOCALAPPDATA%\Ageha` folder, open it
again. You'll get the first-launch experience from [§3](#3-first-launch).

**To free up disk space without losing anything:** delete just the `cache` folder inside it. Ageha
rebuilds what it needs. It can reach a few hundred megabytes if you read a lot.

On Linux the equivalents are `~/.local/share/ageha` and `~/.cache/ageha`; on macOS,
`~/Library/Application Support/Ageha` and `~/Library/Caches/Ageha`.

---

## 6. When something goes wrong

**A source stopped working.** Usually the website changed and the parser hasn't caught up. Settings
(`Ctrl+,`) → **Sources and updates** → **Check now**. Sources come from a shared library that
updates independently of Ageha, so this is normally the fix and doesn't need a new app version.
If a new build turns out to be worse, **Roll back** on the same screen returns to the previous one.

**Titles show as empty rectangles (□□□).** A missing font, not a broken source. On Windows and
macOS this shouldn't happen. On Linux, install your distribution's Noto CJK package — though the
Linux builds also carry a bundled font for exactly this, so it should be rare. Settings →
Appearance names any script it has no font for.

**It won't start at all.** Try deleting the `cache` folder first, since that's the part that can
be rebuilt and costs you nothing. If it still won't start, move `ageha.db` somewhere safe and let
Ageha create a fresh one — that tells you whether the database is the problem, without throwing it
away.

(A damaged `preferences.json` is *not* a cause: Ageha falls back to defaults rather than refusing
to start, so you lose a window size and nothing else.)

**Reinstalling** is safe. It replaces the app and doesn't touch your library.

---

## 7. What isn't done yet

Being straight about the edges, so you don't go looking for things that aren't there:

- **Windows only, by design.** There is no macOS or Linux build and none is planned. Windows on
  ARM runs the x64 installer under emulation.
- **Nothing is signed**, hence [§2](#2-the-scary-warning).
- **The updater doesn't self-update yet.** Ageha will tell you a new version exists; installing it
  means downloading the new `.msi` and running it.
- **Sync against a real server** is written but has never been pointed at a live deployment.
- **Backup import** handles library, categories, favourites, history and reading positions. Four
  less common sections of an Android backup are skipped, and the import report names them rather
  than passing over them silently.
