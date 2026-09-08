package app.ageha.desktop

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.io.File

/**
 * Every animation in a screen goes through the motion helpers, so the reduced-motion switch can
 * reach it.
 *
 * ## Why this test exists
 *
 * Settings -> Appearance -> Motion -> Reduced works by making `motionTween`, `snappySpring` and
 * `settleSpring` return an instant spec. That covers every call site that uses them -- and does
 * nothing whatsoever for one written as `tween(140)`, which will keep animating happily while the
 * setting claims otherwise.
 *
 * That is a *worse* failure than having no setting at all. A user with a vestibular sensitivity
 * turns it on, most of the interface stops moving, one screen does not, and they have no way to
 * tell whether they misunderstood the switch or the application is broken. The one thing an
 * accessibility control must never be is partly true.
 *
 * It is also invisible in review. `tween(AgehaMotion.QUICK_MS)` and
 * `motionTween(AgehaMotion.QUICK_MS)` differ by six characters, both compile, and both look
 * completely correct in a diff.
 *
 * ## What is scanned, and what is not
 *
 * Feature modules and the desktop app: everywhere a *screen* lives. `:core:designsystem` is exempt
 * because that is where the helpers are implemented, and implementing `motionTween` requires
 * calling `tween`. Tests are exempt for the same reason they are in `NoLiteralHexTest`.
 *
 * This is the same shape of rule, and the same shape of test, as the one that keeps colours in the
 * design system. Both guard a property that is invisible until the day it matters.
 */
class MotionThroughTokensTest {

	/**
	 * Walking up from the working directory rather than taking a system property.
	 *
	 * Gradle sets the working directory to the module, and a module's depth differs between
	 * `:app:desktop` and a `:feature:*`. Finding `settings.gradle.kts` is the one landmark that
	 * identifies the root from anywhere inside it.
	 */
	private val repoRoot: File by lazy {
		generateSequence(File(".").absoluteFile) { it.parentFile }
			.firstOrNull { File(it, "settings.gradle.kts").isFile }
			?: error("could not find the repository root from ${File(".").absolutePath}")
	}

	private val scanned = listOf("feature", "app/desktop/src/main")

	/**
	 * A bare `tween(` or `spring(` call.
	 *
	 * Deliberately narrow, and anchored on a non-word character so `motionTween(`, `snappySpring(`
	 * and `settleSpring(` -- which all end in one of these names -- do not match themselves. What
	 * is being caught is a screen reaching for Compose's spec builders directly, which is always
	 * this shape.
	 *
	 * **`snap` is deliberately allowed**, and the distinction is the whole point of the rule rather
	 * than an exception to it. What this test guards is animation that the reduced-motion switch
	 * cannot reach -- and `snap` *is* what that switch turns everything into. A screen that asks
	 * for an instant change has already arrived where the setting would send it, so there is
	 * nothing left to defeat. The navigation pill uses one on purpose: the first placement of its
	 * selection indicator is not a movement and must not be animated in either mode.
	 */
	private val bareSpec = Regex("""(?<![\w.])(tween|spring)\s*\(""")

	/** Prose is not a call site. Same reasoning as `NoLiteralHexTest.isComment`. */
	private fun String.isComment(): Boolean {
		val t = trimStart()
		return t.startsWith("//") || t.startsWith("*") || t.startsWith("/*")
	}

	private fun sourceFiles(): List<File> = scanned
		.map { File(repoRoot, it) }
		.filter { it.isDirectory }
		.flatMap { root -> root.walkTopDown().filter { it.isFile && it.extension == "kt" } }
		.filterNot { it.path.replace('\\', '/').contains("/src/test/") }
		// Generated sources are not written by anyone and cannot be fixed by anyone.
		.filterNot { it.path.replace('\\', '/').contains("/build/") }

	@Test
	@DisplayName("no screen builds an animation spec the reduced-motion switch cannot reach")
	fun everyAnimationIsSwitchable() {
		val offenders = sourceFiles().flatMap { file ->
			file.readLines().withIndex()
				.filter { (_, line) -> !line.isComment() && bareSpec.containsMatchIn(line) }
				.map { (index, line) ->
					val relative = file.relativeTo(repoRoot).path.replace('\\', '/')
					"$relative:${index + 1}  ${line.trim()}"
				}
		}

		assertTrue(offenders.isEmpty()) {
			buildString {
				appendLine("Animation specs in a screen must come from the motion helpers in")
				appendLine(":core:designsystem -- motionTween, snappySpring or settleSpring.")
				appendLine()
				appendLine("A bare tween or spring keeps animating when the user has set")
				appendLine("Settings > Appearance > Motion to Reduced, which makes that setting")
				appendLine("partly true -- the one thing an accessibility control must never be:")
				appendLine()
				offenders.forEach { appendLine("  $it") }
			}
		}
	}

	/**
	 * The scan is actually looking at something.
	 *
	 * A path typo, a module rename, or a working directory that is not what this assumes would all
	 * make the test above pass by examining zero files -- the failure mode of every convention
	 * test written against a directory listing, and one that passes silently forever.
	 */
	@Test
	@DisplayName("the scan reaches real source")
	fun theScanReachesRealSource() {
		val count = sourceFiles().size
		assertTrue(count > 20) {
			"expected to scan the feature and desktop sources, found $count .kt files under " +
				"$repoRoot -- the paths in `scanned` are probably wrong"
		}
	}
}
