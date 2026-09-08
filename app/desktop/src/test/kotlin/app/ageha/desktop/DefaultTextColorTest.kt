package app.ageha.desktop

import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.runComposeUiTest
import app.ageha.core.designsystem.AgehaTheme
import app.ageha.core.designsystem.AgehaThemeMode
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.TestFactory
import kotlin.math.pow

/**
 * The ink a `Text` gets when it does not ask for one.
 *
 * ## Why this needs a test of its own
 *
 * `Text` with no `color` resolves [LocalContentColor]. The default value of that composition local
 * is **`Color.Black`**, and Material only ever overrides it from inside a `Surface` -- a container
 * Ageha does not use anywhere. `MaterialTheme` does not provide it, and handing it a `colorScheme`
 * does nothing for it.
 *
 * The result was 69 of 187 `Text` call sites drawing black on a near-black window: every chapter
 * title in the chapter list, most of Settings, half of the details screen. It survived review, the
 * palette's own contrast tests and the whole screenshot set -- because every one of those checks a
 * colour that is *named* somewhere, and the entire failure here is text that names none.
 *
 * So this asserts the default itself, in every theme, against the surfaces it lands on.
 *
 * It lives in `:app:desktop` rather than beside the other palette tests because
 * `:core:designsystem` declares no test dependencies at all, and answering "what would an unstyled
 * `Text` actually be painted in" means composing the theme rather than reading a token out of it.
 */
@OptIn(ExperimentalTestApi::class)
class DefaultTextColorTest {

	private fun luminance(color: Color): Double {
		fun channel(c: Float): Double {
			val s = c.toDouble()
			return if (s <= 0.03928) s / 12.92 else ((s + 0.055) / 1.055).pow(2.4)
		}
		return 0.2126 * channel(color.red) + 0.7152 * channel(color.green) + 0.0722 * channel(color.blue)
	}

	private fun ratio(a: Color, b: Color): Double {
		val la = luminance(a)
		val lb = luminance(b)
		return (maxOf(la, lb) + 0.05) / (minOf(la, lb) + 0.05)
	}

	private data class Resolved(
		val ink: Color,
		val surface: Color,
		val container: Color,
		val highest: Color,
	)

	/** What an unstyled `Text` would actually be painted in, for one theme. */
	private fun resolve(mode: AgehaThemeMode): Resolved {
		var out = Resolved(Color.Unspecified, Color.Unspecified, Color.Unspecified, Color.Unspecified)
		runComposeUiTest {
			setContent {
				AgehaTheme(mode = mode) {
					out = Resolved(
						ink = LocalContentColor.current,
						surface = MaterialTheme.colorScheme.surface,
						container = MaterialTheme.colorScheme.surfaceContainer,
						highest = MaterialTheme.colorScheme.surfaceContainerHighest,
					)
				}
			}
		}
		return out
	}

	/**
	 * The literal regression: the default must not be the composition local's own black.
	 *
	 * Asserted separately from the contrast check below, and deliberately so. In a *light* theme
	 * black passes a contrast test with room to spare while still being the bug -- it would mean
	 * nothing is providing the local at all, and every dark theme is broken.
	 */
	@TestFactory
	fun `the default text colour is never the unprovided black`(): List<DynamicTest> =
		AgehaThemeMode.entries.map { mode ->
			DynamicTest.dynamicTest(mode.name.lowercase()) {
				assertNotEquals(
					Color.Black,
					resolve(mode).ink,
					"$mode leaves LocalContentColor at its default, so every Text without an " +
						"explicit colour is painted black",
				)
			}
		}

	/** And it has to be readable on the surfaces an unstyled `Text` actually sits on. */
	@TestFactory
	fun `the default text colour meets WCAG AA in every theme`(): List<DynamicTest> =
		AgehaThemeMode.entries.flatMap { mode ->
			val r = resolve(mode)
			listOf(
				"surface" to r.surface,
				"surfaceContainer" to r.container,
				"surfaceContainerHighest" to r.highest,
			).map { (label, background) ->
				DynamicTest.dynamicTest("${mode.name.lowercase()}: default text on $label") {
					val measured = ratio(r.ink, background)
					assertTrue(measured >= 4.5) {
						"$mode default text colour on $label is %.2f:1, below the 4.5:1 AA floor"
							.format(measured)
					}
				}
			}
		}
}
