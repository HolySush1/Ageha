package app.ageha.desktop

import app.ageha.core.designsystem.AgehaThemeMode
import app.ageha.core.designsystem.MotionPreference
import app.ageha.core.designsystem.CardStyle
import app.ageha.core.designsystem.ReaderBackground
import app.ageha.core.model.PageScale
import app.ageha.core.model.ReaderMode
import app.ageha.core.network.AgehaPaths
import app.ageha.feature.settings.AppUpdatePolicy
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

/**
 * The handful of things Ageha remembers between launches, other than the library.
 *
 * A JSON file rather than a table. These are small, they are read exactly once at startup, and
 * they must be readable when the database is not -- the window has to open even if the library is
 * corrupt or locked, otherwise the user has no way to reach the settings that would let them fix
 * it. Room's own preferences table lands with the settings screen in milestone 8, for values that
 * belong with the data rather than with the window.
 */
@Serializable
data class Preferences(
	val theme: AgehaThemeMode = AgehaThemeMode.SYSTEM,
	/**
	 * Whether Ageha animates.
	 *
	 * Defaults to following Windows rather than to on, because Windows has already asked -- see
	 * `SystemMotion`. A user who turned animation off system-wide should not have to find this
	 * row at all; the row exists for the two cases where they disagree with their own OS about
	 * this one application.
	 */
	val motion: MotionPreference = MotionPreference.SYSTEM,
	val readerBackground: ReaderBackground = ReaderBackground.BLACK,
	/**
	 * Two pages side by side in paged mode.
	 *
	 * Off by default. It only helps on a window wide enough for two pages at a readable size, and
	 * a reader who opens Ageha on a laptop and gets two postage stamps will conclude the app is
	 * broken rather than that a setting is wrong.
	 */
	val doublePage: Boolean = false,
	/** Whether the first page stands alone in double-page mode. See PageLayout for why. */
	val coverOffset: Boolean = true,
	/**
	 * How wide the webtoon strip is drawn, as a multiple of the source's own pixel width.
	 *
	 * 1.0 means one image pixel per screen pixel -- what the source actually published, and the
	 * only width that is never an interpolation of somebody else's line art. It is stored rather
	 * than being per-chapter because the first thing a reader on a large monitor does is widen the
	 * strip, and having to do that again at every chapter break would make the control feel broken.
	 *
	 * Clamped where it is used, not here: the strip is also capped to the window, so the same
	 * stored number gives a sensible width on a laptop and on a 27-inch display.
	 */
	val webtoonZoom: Float = 1f,
	val window: WindowGeometry = WindowGeometry(),
	/**
	 * Whether the library's shelf rail is folded down to a strip of initials.
	 *
	 * Stored rather than kept as screen state, and that is the whole reason it is in this file: a
	 * rail that unfolded itself on every launch is a control that does not stay where it was put,
	 * which is worse than not having the control at all. Expanded by default -- someone who has
	 * never seen the rail should meet it with its shelf names showing.
	 */
	val libraryRailCollapsed: Boolean = false,
	val lastCategoryId: Int = -1,
	val minimiseToTray: Boolean = false,
	/**
	 * Run a sync when the window opens, if an account is configured.
	 *
	 * On by default, and harmless when it is not: with no account the engine returns
	 * `NotConfigured` without touching the network. Startup is the one moment a sync is genuinely
	 * wanted -- it is when another device's changes are most likely to be waiting -- and it is
	 * also the moment the user is least interested in being asked.
	 */
	val syncOnStart: Boolean = true,
	/**
	 * Whether Ageha looks for a newer Ageha, and whether it says so.
	 *
	 * Defaults to checking quietly. It does *not* control installation -- the installer owns that
	 * and exposes no runtime switch. See [AppUpdatePolicy]. (This used to say "on all three
	 * platforms". There is one; see CLAUDE.md 9.)
	 */
	val appUpdatePolicy: AppUpdatePolicy = AppUpdatePolicy.AUTOMATIC,
	/**
	 * Whether the source picker hides sources upstream has flagged broken.
	 *
	 * On by default. A broken source cannot return anything, so leaving thirty of them scattered
	 * through a list of 1360 costs the user attention for no possible gain -- and the
	 * "Known broken" view is still there for the one case where they matter, which is working out
	 * why a source that used to work no longer does.
	 */
	val hideBrokenSources: Boolean = true,
	/**
	 * Whether the source picker shows adult sources.
	 *
	 * Off by default, and this is the direction the default has to point. Ageha opens on a desktop
	 * that may be in an office or a shared room, and a catalogue that volunteers pornography to
	 * someone scrolling for a manga source is a much worse failure than one that makes an
	 * interested user find a switch. The switch is one click away in the picker's filter menu and
	 * again in Settings.
	 *
	 * This governs *visibility in the picker*, not capability: a source the user has deliberately
	 * enabled keeps working everywhere, global search included. Suppressing results from a source
	 * the user turned on would make it look broken rather than filtered.
	 */
	val showAdultSources: Boolean = false,
	/** The picker's language filter, as an upstream tag. Null means every language. */
	val sourceLanguage: String? = null,
	/**
	 * Whether a site's bot check is put in front of the user at once, rather than tried quietly.
	 *
	 * On by default, and deliberately. The alternative spends six seconds attempting the check in a
	 * window nobody can see, which is the app answering "are you a person?" on the user's behalf --
	 * at precisely the moment a site has asked for a person. If a check is going to be passed in
	 * Ageha's name, the person whose name it is should be watching it happen.
	 *
	 * An honest consequence, worth stating because it looks like a bug: most Cloudflare managed
	 * challenges really do clear themselves in a real browser, so a window will sometimes appear,
	 * clear and close without being touched. That was always happening; this only stops hiding it.
	 *
	 * Off restores the older behaviour -- hidden for six seconds, shown only if it is still stuck.
	 */
	val showChecksImmediately: Boolean = true,
	/**
	 * The reader mode a manga gets when it has never been given one of its own.
	 *
	 * The handoff's `opts.mode`, and it is a *default* rather than a global: the reader's own chip
	 * still writes a per-manga override, because a webtoon and a scanlated tankoubon want
	 * different modes and one setting for both would make one of them wrong every time.
	 */
	val defaultReaderMode: ReaderMode = ReaderMode.DEFAULT,
	/** The handoff's `opts.fit`. How a page is scaled when the reader opens. */
	val defaultPageScale: PageScale = PageScale.FIT_PAGE,
	/** The handoff's `opts.grid`: how dense the library shelf is. */
	val cardStyle: CardStyle = CardStyle.COVER,
	/**
	 * The handoff's `opts.nsfwBlur`. Blurs adult-rated covers until the pointer is on them.
	 *
	 * Off by default, unlike [showAdultSources], and the two point opposite ways on purpose:
	 * adult *sources* are hidden until asked for because the catalogue is 1360 sites the user has
	 * not chosen, while an adult title in the *library* is one they deliberately added. Blurring
	 * their own shelf by default would be the app second-guessing a decision already made.
	 */
	val blurAdultCovers: Boolean = false,
	/**
	 * The handoff's `opts.parallel`: simultaneous downloads per source.
	 *
	 * Per source rather than overall, which is the number that actually matters -- see
	 * `DownloadQueue`. Two by default, because the cost of being impolite to a small site is not
	 * a slow download, it is a block affecting every Ageha user of that source.
	 */
	val parallelDownloads: Int = 2,
	/**
	 * The handoff's `opts.preload`: how many pages ahead the reader fetches.
	 *
	 * Ahead of the **bottom edge of the window**, not of the page the position is recorded
	 * against. In webtoon mode those are different pages -- often several apart -- and counting
	 * from the recorded one spent part of this budget on artwork already on screen, which made the
	 * setting look like it did nothing. See `ReaderScreen.PreloadPages`.
	 *
	 * Zero means the whole chapter. Six by default: enough that a fast reader never waits on a
	 * page turn, few enough that opening a chapter does not fetch forty images for someone who
	 * will close it after two.
	 */
	val preloadPages: Int = 6,
)

/**
 * Where the window was last time.
 *
 * A desktop app that opens in the middle of the screen at a default size every launch is a desktop
 * app that has not been finished. Position is stored alongside size because on a multi-monitor
 * setup "same size, wrong monitor" is barely better than nothing.
 */
@Serializable
data class WindowGeometry(
	val width: Int = 1280,
	val height: Int = 860,
	val x: Int? = null,
	val y: Int? = null,
	val isMaximized: Boolean = false,
)

/**
 * Reads and writes [Preferences].
 *
 * Writes go through a temporary file and an atomic rename. A half-written preferences file is
 * indistinguishable from a corrupt one, and the cost of getting it wrong is an app that will not
 * start -- so the write either lands whole or does not land.
 */
class PreferencesStore(private val file: File = File(AgehaPaths.dataDir, "preferences.json")) {

	private val json = Json {
		prettyPrint = true
		// A preferences file written by a newer Ageha must not stop an older one from starting.
		// Unknown keys are dropped on read and lost on the next write, which is the right trade
		// for settings -- unlike for a backup, where losing an unknown section loses user data.
		ignoreUnknownKeys = true
		encodeDefaults = true
	}

	fun load(): Preferences {
		if (!file.exists()) return Preferences()
		return runCatching { json.decodeFromString<Preferences>(migrate(file.readText())) }
			// A corrupt file falls back to defaults rather than refusing to start. The user loses
			// a window size; the alternative is losing the application.
			.getOrElse { Preferences() }
	}

	/**
	 * Rewrites values this version of Ageha no longer has a name for.
	 *
	 * `ignoreUnknownKeys` covers an unknown *key*; it does nothing for an unknown enum *value*,
	 * which throws. So the one file every existing installation has -- a `theme` of `DARK`, from
	 * before the two skins replaced it -- would take the whole file down with it and land the
	 * user on defaults, losing their window geometry and every other setting along the way. That
	 * is a poor trade for a rename, so `DARK` becomes `EMBER`, which is the skin it most nearly
	 * already was.
	 *
	 * String surgery rather than a custom serializer: there is one value to fix, the rewritten
	 * text is discarded as soon as it has been parsed, and the next `save` writes the new name,
	 * so this fires once per installation and then never again.
	 */
	private fun migrate(text: String): String =
		text.replace("\"theme\": \"DARK\"", "\"theme\": \"EMBER\"")

	fun save(preferences: Preferences) {
		runCatching {
			file.parentFile?.mkdirs()
			val temporary = File(file.parentFile, "${file.name}.tmp")
			temporary.writeText(json.encodeToString(preferences))
			if (!temporary.renameTo(file)) {
				// Windows will not rename onto an existing file. Deleting first opens a window
				// where neither file is complete, which is why the temporary is written first and
				// why a failure here leaves the *old* file in place.
				file.delete()
				temporary.renameTo(file)
			}
		}
	}
}
