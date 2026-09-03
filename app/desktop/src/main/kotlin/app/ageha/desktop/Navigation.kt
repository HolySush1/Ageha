package app.ageha.desktop

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import app.ageha.core.model.AgehaManga

/** The two top-level places in Ageha. Reached by the rail, or by Ctrl+1 and Ctrl+2. */
enum class Section(val label: String, val shortcutHint: String) {
	LIBRARY("Library", "Ctrl+1"),
	EXPLORE("Explore", "Ctrl+2"),
}

/** A screen within a section. */
@Immutable
sealed interface Destination {
	data object Library : Destination
	data object Sources : Destination
	data class Browse(val sourceName: String) : Destination
	data class Details(val manga: AgehaManga) : Destination
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

	private val stack get() = if (section == Section.LIBRARY) libraryStack else exploreStack

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

	fun resetToRoot() {
		while (stack.size > 1) stack.removeAt(stack.lastIndex)
	}
}
