package app.ageha.desktop

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import app.ageha.core.model.AgehaChapter
import app.ageha.core.model.AgehaManga

/** The two top-level places in Ageha. Reached by the rail, or by Ctrl+1 and Ctrl+2. */
enum class Section(val label: String, val shortcutHint: String) {
	LIBRARY("Library", "Ctrl+1"),
	EXPLORE("Explore", "Ctrl+2"),
	DOWNLOADS("Downloads", "Ctrl+3"),
	SETTINGS("Settings", "Ctrl+,"),
}

/** A screen within a section. */
@Immutable
sealed interface Destination {
	data object Library : Destination
	data object Sources : Destination
	data object Downloads : Destination
	data object Settings : Destination
	data class Browse(val sourceName: String) : Destination
	data class Details(val manga: AgehaManga) : Destination

	/**
	 * The reader.
	 *
	 * Carries the chapter as well as the manga because a chapter list is not addressable by index
	 * across branches -- the same manga read on a different scanlation branch has a different
	 * chapter at position 5.
	 */
	data class Read(val manga: AgehaManga, val chapter: AgehaChapter) : Destination
}

/**
 * Navigation, as a back stack per section.
 *
 * Per section rather than one global stack, because the two sections are genuinely separate places
 * and a desktop user switches between them constantly. A single stack would mean flicking to the
 * library and back dumps you at the top of Explore instead of where you were, which is the phone
 * behaviour people tolerate on a phone because there is only one thing on screen at a time.
 *
 * Deliberately not a navigation library. Ageha has four destinations, one of which carries a
 * parcelable-free Kotlin object; a routing library would add string routes, a serialization
 * requirement and a dependency to solve a problem this does not have.
 */
class Navigator {

	var section by mutableStateOf(Section.LIBRARY)
		private set

	private val libraryStack = mutableStateListOf<Destination>(Destination.Library)
	private val exploreStack = mutableStateListOf<Destination>(Destination.Sources)
	private val downloadsStack = mutableStateListOf<Destination>(Destination.Downloads)
	private val settingsStack = mutableStateListOf<Destination>(Destination.Settings)

	private val stack get() = when (section) {
		Section.LIBRARY -> libraryStack
		Section.EXPLORE -> exploreStack
		Section.DOWNLOADS -> downloadsStack
		Section.SETTINGS -> settingsStack
	}

	val current: Destination get() = stack.last()

	val canGoBack: Boolean get() = stack.size > 1

	fun switchTo(target: Section) {
		section = target
	}

	fun push(destination: Destination) {
		// Pushing the destination you are already on is a no-op rather than a duplicate entry.
		// Double-clicking a manga in a grid is easy to do and should not need two Escapes.
		if (stack.last() == destination) return
		stack.add(destination)
	}

	fun back(): Boolean {
		if (!canGoBack) return false
		stack.removeAt(stack.lastIndex)
		return true
	}

	/** Jump to a source's listing from anywhere, switching section as needed. */
	fun openSource(sourceName: String) {
		section = Section.EXPLORE
		push(Destination.Browse(sourceName))
	}

	/** Open a manga in whichever section the user is already in, so back returns where they were. */
	fun openManga(manga: AgehaManga) {
		push(Destination.Details(manga))
	}

	/** Open a chapter in the reader, keeping the details screen underneath to come back to. */
	fun read(manga: AgehaManga, chapter: AgehaChapter) {
		push(Destination.Read(manga, chapter))
	}

	/** True when the current destination wants the whole window -- no rail, no chrome. */
	val isImmersive: Boolean get() = current is Destination.Read

	fun resetToRoot() {
		while (stack.size > 1) stack.removeAt(stack.lastIndex)
	}
}
