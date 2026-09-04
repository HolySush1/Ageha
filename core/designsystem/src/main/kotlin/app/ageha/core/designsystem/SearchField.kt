package app.ageha.core.designsystem

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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
 * Every search box in Ageha.
 *
 * It exists because of one bug, and the bug is worth explaining, because the obvious
 * implementation -- `OutlinedTextField(value = state.query, onValueChange = viewModel::search)` --
 * is what every one of these call sites used, and it is broken.
 *
 * The `String` overload of a text field does not store a caret. It keeps one internally and pairs
 * it with whatever `value` it is handed on each composition. Every query in this app is hoisted
 * into a view model as a `MutableStateFlow` and read back through `stateIn`, so the echo arrives a
 * frame *after* the keystroke. For that one frame the field is composed with the new caret
 * position and the **old** text; the caret is out of bounds for that text, so it is clamped -- to
 * zero. The next frame brings the new text with the caret now sitting at the front of it. Type
 * "abc" and you get "cba". It is not a race and not intermittent: it happens on every keystroke.
 *
 * So this owns a [TextFieldValue] -- text *and* caret together -- and treats itself as the source
 * of truth for as long as the user is typing. An external [value] is adopted only when it differs
 * from the last text this field emitted, which is the difference between "the view model echoing
 * my own keystroke back at me" (ignore it, the caret is already right) and "somebody cleared the
 * query" (adopt it).
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
	var field by remember { mutableStateOf(TextFieldValue(value, TextRange(value.length))) }
	var lastEmitted by remember { mutableStateOf(value) }

	// A genuine outside change -- a cleared query, one restored from a saved state -- rather than
	// our own last keystroke coming back around. Caret to the end, because that is where someone
	// handed a pre-filled search box wants to continue from.
	if (value != lastEmitted) {
		lastEmitted = value
		field = TextFieldValue(value, TextRange(value.length))
	}

	OutlinedTextField(
		value = field,
		onValueChange = { edited ->
			field = edited
			if (edited.text != lastEmitted) {
				lastEmitted = edited.text
				onValueChange(edited.text)
			}
		},
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
