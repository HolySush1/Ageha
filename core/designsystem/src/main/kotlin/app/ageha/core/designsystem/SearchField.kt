package app.ageha.core.designsystem

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue

/**
 * Text and caret for a search box whose query lives in a view model.
 *
 * It exists because of one bug, and the bug is worth explaining, because the obvious
 * implementation -- `TextField(value = state.query, onValueChange = viewModel::search)` -- is what
 * every one of these call sites used, and it is broken. It is broken for `BasicTextField` exactly
 * as it is for `OutlinedTextField`: the decoration differs, the `String` overload underneath does
 * not. A screen that draws its own chrome around a bare `BasicTextField` is not opting out of this
 * problem, only out of the fix, which is how it came back on the source picker.
 *
 * The `String` overload of a text field does not store a caret. It keeps one internally and pairs
 * it with whatever `value` it is handed on each composition. Every query in this app is hoisted
 * into a view model as a `MutableStateFlow` and read back through `stateIn`, so the echo arrives a
 * frame *after* the keystroke. For that one frame the field is composed with the new caret
 * position and the **old** text; the caret is out of bounds for that text, so it is clamped -- to
 * zero. The next frame brings the new text with the caret now sitting at the front of it. Type
 * "abc" and you get "cba". It is not a race and not intermittent: it happens on every keystroke.
 *
 * So this owns a [TextFieldValue] -- text *and* caret together -- and is the source of truth for
 * as long as the user is typing. Reach it through [rememberSearchFieldState]; [AgehaSearchField]
 * is the same thing with Material decoration already on it.
 *
 * ## Why "differs from what I last emitted" is not enough
 *
 * The first version of this adopted any incoming value that differed from the last text it
 * emitted. That reads as correct and has the original bug hiding inside it, because it cannot tell
 * *"somebody cleared the query"* from *"upstream has not caught up with me yet"* -- both arrive as
 * a value that is not what this field last sent. Type a character, and the very next composition
 * can carry the **previous** text; the field then adopts it, wiping the character and putting the
 * caret at the end of the older, shorter string. The next frame brings the real text back. The
 * visible symptom is the caret landing behind the letter you just typed, reported exactly that
 * way.
 *
 * So the field tracks what it is *waiting to hear back*. While an echo is outstanding, upstream is
 * behind and is ignored. Once upstream matches, the wait ends and genuine outside changes -- a
 * cleared query, one restored from saved state -- are adopted again.
 */
@Stable
class SearchFieldState internal constructor(initial: String, private val emit: (String) -> Unit) {

	/** What the field should display. Hand this straight to a text field's `value`. */
	var value: TextFieldValue by mutableStateOf(TextFieldValue(initial, TextRange(initial.length)))
		private set

	/** The last upstream value acted on, so a composition that re-reads the same one does no work. */
	private var lastSeen by mutableStateOf(initial)

	/**
	 * The text sent upstream and not yet heard back. Non-null means upstream is behind us and
	 * anything it says about the query is stale.
	 */
	private var awaitingEcho by mutableStateOf<String?>(null)

	/**
	 * Call this from the text field's `onValueChange`.
	 *
	 * Every change to the *text* is published, including one that returns the box to the text
	 * upstream last showed. Suppressing that case -- which an earlier version did, to avoid
	 * echoing -- drops the edit outright when it lands before the previous one has been heard
	 * back: type a letter, delete it quickly, and the box is empty while the list stays filtered
	 * by a query no longer on screen. Caret-only moves publish nothing, since upstream holds no
	 * caret and would only echo its own text back at us.
	 */
	fun onValueChange(edited: TextFieldValue) {
		val isEdit = edited.text != value.text
		value = edited
		if (isEdit) {
			awaitingEcho = edited.text
			emit(edited.text)
		}
	}

	/** Reconciles with what upstream currently holds. Called on every composition. */
	internal fun sync(incoming: String) {
		if (incoming == lastSeen) return
		lastSeen = incoming
		when {
			// Upstream has caught up with our own keystroke. Nothing to adopt -- the text is
			// already ours and the caret is already where the user put it.
			incoming == awaitingEcho -> awaitingEcho = null

			// Upstream agrees with what is on screen by some other route. Equally nothing to do,
			// and it clears the wait so a later outside change is not ignored forever -- which is
			// what would happen if a view model ever normalised the query it was handed.
			incoming == value.text -> awaitingEcho = null

			// Still echoing older text while the user keeps typing. Ignoring it is the whole fix.
			awaitingEcho != null -> Unit

			// A genuine outside change. Caret to the end, because that is where someone handed a
			// pre-filled search box wants to continue from.
			else -> value = TextFieldValue(incoming, TextRange(incoming.length))
		}
	}
}

/**
 * The state behind a search box, for screens that draw their own chrome around a `BasicTextField`.
 *
 * @param value the query as upstream holds it.
 * @param onValueChange called with each edit. Read [SearchFieldState] for why the field cannot
 *   simply be handed [value] on every composition.
 */
@Composable
fun rememberSearchFieldState(value: String, onValueChange: (String) -> Unit): SearchFieldState {
	// Read through this rather than captured directly, so the state does not pin whichever lambda
	// the first composition happened to pass.
	val emit by rememberUpdatedState(onValueChange)
	val state = remember { SearchFieldState(value) { emit(it) } }
	state.sync(value)
	return state
}

/**
 * Every Material-decorated search box in Ageha. See [SearchFieldState] for what it guards against.
 *
 * @param onSubmit if given, Enter calls it. Searching a remote source on every keystroke would be
 *   one request per character to somebody else's server, so the screens that query a website pass
 *   this and the ones that filter a list already in memory do not.
 */
@Composable
fun AgehaSearchField(
	value: String,
	onValueChange: (String) -> Unit,
	placeholder: String,
	modifier: Modifier = Modifier,
	onSubmit: (() -> Unit)? = null,
	enabled: Boolean = true,
) {
	val field = rememberSearchFieldState(value, onValueChange)

	OutlinedTextField(
		value = field.value,
		onValueChange = field::onValueChange,
		singleLine = true,
		enabled = enabled,
		leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
		placeholder = { Text(placeholder, style = MaterialTheme.typography.bodyMedium) },
		textStyle = MaterialTheme.typography.bodyMedium,
		// Enter is caught in preview rather than through `KeyboardActions`, so that it works the
		// same whether or not the platform decides this field has an IME action.
		modifier = modifier.onPreviewKeyEvent { event ->
			if (onSubmit != null && event.type == KeyEventType.KeyDown && event.key == Key.Enter) {
				onSubmit()
				true
			} else {
				false
			}
		},
	)
}
