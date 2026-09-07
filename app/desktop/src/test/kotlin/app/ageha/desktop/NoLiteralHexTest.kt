package app.ageha.desktop

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

/**
 * Colours are declared in `:core:designsystem` and nowhere else.
 *
 * CLAUDE.md rule 7 says so, and the Ember & Glass handoff says the same thing in its own words --
 * *"no literal hex in component styles. Only `var(--…)`"* -- which is that rule written for a
 * stylesheet. This is the Kotlin equivalent, and it exists because the rule is invisible when it is
 * broken: a screen that hardcodes one grey looks completely fine until the theme changes underneath
 * it, at which point one label in one state is the wrong colour and nobody knows why.
 *
 * That is not hypothetical. The reader's two page placeholders carried `Color(0xFF9A9A9A)` for
 * months. It looked right, because it was chosen against the black background everyone develops
 * on -- and it is barely legible on Paper and invisible on White, which are two of the four
 * backgrounds that screen offers. This test would have caught it the day it was written.
 *
 * ## What is scanned, and what is not
 *
 * Feature modules and the desktop app: everywhere a *screen* lives. `:core:designsystem` is exempt
 * by definition, because declaring colours is its job, and `:tools:brandkit` is exempt because it
 * generates them. Tests are exempt too -- a test asserting something about a particular colour has
 * to be able to name one.
 */
class NoLiteralHexTest {

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
	 * `Color(0x…)`, in any spacing.
	 *
	 * Deliberately narrow. It does not try to catch `Color(1f, 0f, 0f)` or a named constant,
	 * because the failure this guards is *transcribing a hex value out of a design* -- which
	 * always arrives in this shape, and which is what the handoff's own review rule names.
	 */
	private val literalHex = Regex("""Color\s*\(\s*0x""")

	/**
	 * Prose is not a component style.
	 *
	 * A comment explaining *why* a colour moved into the design system has to be able to quote the
	 * value it replaced, and the reader's placeholders carry exactly such a comment. Matching a
	 * line-comment or a KDoc line would make this test fail on its own documentation, which is the
	 * kind of rule people delete rather than obey.
	 *
	 * Line-based rather than a real parse: a block comment opened mid-line is not a shape this
	 * codebase uses, and a Kotlin parser to catch one would be a large amount of machinery for a
	 * case that does not occur.
	 */
	private fun String.isComment(): Boolean {
		val t = trimStart()
		return t.startsWith("//") || t.startsWith("*") || t.startsWith("/*")
	}

	private fun sourceFiles(): List<File> = scanned
		.map { File(repoRoot, it) }
		.filter { it.isDirectory }
		.flatMap { root -> root.walkTopDown().filter { it.isFile && it.extension == "kt" } }
		// A test may name a colour; naming one is what it is for.
		.filterNot { it.path.replace('\\', '/').contains("/src/test/") }

	@Test
	fun `no screen declares its own colour`() {
		val offenders = sourceFiles().flatMap { file ->
			file.readLines().withIndex()
				.filter { (_, line) -> !line.isComment() && literalHex.containsMatchIn(line) }
				.map { (index, line) ->
					val relative = file.relativeTo(repoRoot).path.replace('\\', '/')
					"$relative:${index + 1}  ${line.trim()}"
				}
		}

		assertTrue(offenders.isEmpty()) {
			buildString {
				appendLine("Colours belong in :core:designsystem (CLAUDE.md rule 7).")
				appendLine()
				appendLine("Add the value to AgehaSkin or the generated tokens and read it back")
				appendLine("through AgehaTheme.skin or MaterialTheme.colorScheme, so it follows")
				appendLine("every theme instead of being right only in the one it was chosen in:")
				appendLine()
				offenders.forEach { appendLine("  $it") }
			}
		}
	}

	/**
	 * The scan is actually looking at something.
	 *
	 * A path typo, a module rename, or a working directory that is not what this assumes would all
	 * make the test above pass by examining zero files -- the failure mode of every convention test
	 * written against a directory listing, and one that passes silently forever.
	 */
	@Test
	fun `the scan reaches real source`() {
		val count = sourceFiles().size
		assertTrue(count > 20) {
			"expected to scan the feature and desktop sources, found $count .kt files under " +
				"$repoRoot -- the paths in `scanned` are probably wrong"
		}
	}
}
