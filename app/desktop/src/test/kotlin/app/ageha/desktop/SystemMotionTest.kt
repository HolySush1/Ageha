package app.ageha.desktop

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * Reading the Windows animation setting, and every way that can go wrong.
 *
 * Only the parsing is tested, and deliberately so. The half of [SystemMotion] that spawns
 * `reg.exe` has one interesting behaviour -- it swallows everything -- and a test for that would
 * be a test of `ProcessBuilder`. The half that can genuinely be *wrong* is the text handling, and
 * it is wrong in ways that would never show up on the machine it was written on: a Windows build
 * that separates columns with a tab, a localised `reg.exe`, a value type nobody expected.
 *
 * The asymmetry in the expected answers is the point. Every unclear case resolves to "keep
 * animating", because this is a preference rather than a permission: reading it wrong in that
 * direction leaves a setting one click from being fixed, and reading it wrong in the other takes
 * the interface away from somebody who never asked for that.
 */
class SystemMotionTest {

	@Test
	@DisplayName("MinAnimate 0 means Windows has asked for animation to be turned down")
	fun animationOff() {
		val output = """
			HKEY_CURRENT_USER\Control Panel\Desktop\WindowMetrics
			    MinAnimate    REG_SZ    0
		""".trimIndent()
		assertFalse(SystemMotion.parseMinAnimate(output)!!, "0 means animations are off")
	}

	@Test
	@DisplayName("MinAnimate 1 means animation is on")
	fun animationOn() {
		val output = """
			HKEY_CURRENT_USER\Control Panel\Desktop\WindowMetrics
			    MinAnimate    REG_SZ    1
		""".trimIndent()
		assertTrue(SystemMotion.parseMinAnimate(output)!!, "1 means animations are on")
	}

	/**
	 * The separator is not load-bearing.
	 *
	 * `reg.exe` prints four spaces between columns on some builds and a tab on others, and there
	 * is no documented guarantee either way. A parser written against a fixed column offset works
	 * perfectly on the machine it was written on and returns nonsense somewhere else, which is the
	 * exact failure mode this file exists to catch.
	 */
	@Test
	@DisplayName("tabs separate the columns just as well as spaces")
	fun tabSeparated() {
		val output = "HKEY_CURRENT_USER\\Control Panel\\Desktop\\WindowMetrics\n" +
			"\tMinAnimate\tREG_SZ\t0"
		assertFalse(SystemMotion.parseMinAnimate(output)!!, "tab-separated output should parse")
	}

	@Test
	@DisplayName("a missing key is unknown rather than off")
	fun missingKey() {
		val output = "ERROR: The system was unable to find the specified registry key or value."
		assertNull(SystemMotion.parseMinAnimate(output), "no value means no answer")
	}

	@Test
	@DisplayName("empty output is unknown rather than off")
	fun emptyOutput() {
		assertNull(SystemMotion.parseMinAnimate(""), "nothing to read means no answer")
	}

	/**
	 * A value this does not understand is not the same as a value meaning "off".
	 *
	 * `MinAnimate` is documented as 0 or 1, but it is a `REG_SZ` -- a *string* -- and nothing stops
	 * another tool writing something else into it. Treating an unrecognised string as falsy is how
	 * a user who has never touched this setting ends up with an application that has silently
	 * stopped animating.
	 */
	@Test
	@DisplayName("an unrecognised value is unknown rather than off")
	fun unexpectedValue() {
		val output = "    MinAnimate    REG_SZ    yes"
		assertNull(SystemMotion.parseMinAnimate(output), "an unknown value should not mean off")
	}

	/**
	 * The live probe answers, whatever it answers.
	 *
	 * Not an assertion about *this* machine's setting -- CI runners and developer machines differ,
	 * and pinning it would make the test a report on the runner. What is asserted is that the call
	 * returns rather than throwing or hanging, which is the contract the window's first frame
	 * depends on.
	 */
	@Test
	@DisplayName("asking the system returns an answer rather than throwing")
	fun probeIsSafe() {
		SystemMotion.allowsMotion()
	}
}
