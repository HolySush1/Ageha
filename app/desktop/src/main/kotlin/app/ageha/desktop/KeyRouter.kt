package app.ageha.desktop

import androidx.compose.ui.input.key.KeyEvent

/**
 * Lets the current screen claim the keyboard.
 *
 * Compose Desktop delivers key events at the window, but the bindings that matter belong to
 * whichever screen is showing -- and the reader's bindings depend on its own state, since the
 * arrow keys follow the reading direction. Rather than teach the window about reader modes, a
 * screen installs a handler here and removes it when it goes away.
 *
 * The window still handles its own shortcuts *after* consulting this, so a screen can override
 * a global binding while it is open (Escape, for one) without the window losing it afterwards.
 */
class KeyRouter {

	private var handler: ((KeyEvent) -> Boolean)? = null

	fun install(handler: (KeyEvent) -> Boolean) {
		this.handler = handler
	}

	fun clear() {
		handler = null
	}

	/** True when the current screen consumed the event. */
	fun dispatch(event: KeyEvent): Boolean = handler?.invoke(event) ?: false
}
