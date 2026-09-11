package app.ageha.core.designsystem

import app.ageha.core.model.JsCapability
import app.ageha.core.model.SourceFailure
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * Which button each failure gets.
 *
 * Every assertion here is a remedy that has to be able to work. The panel's most prominent control
 * has twice been one that could not: first a button wired to nothing, then remedies that sent the
 * user somewhere their effort was thrown away -- a check passed in their own browser, whose cookies
 * Ageha never sees, and an install offered for problems an install does not touch.
 */
class FailureCopyTest {

	private val source = "COMICK_FUN"

	@Test
	fun `a check to pass is passed in Ageha's browser, not the user's`() {
		val copy = describe(SourceFailure.ChallengeRequired(source, "https://comick.live/"))
		assertEquals(FailureCopy.Remedy.CLEAR_CHECK, copy.remedy)
	}

	@Test
	fun `a refused request is offered the same, since it is usually bot protection`() {
		val copy = describe(SourceFailure.Blocked(source, 403, "https://comick.live/api/search"))
		assertEquals(FailureCopy.Remedy.CLEAR_CHECK, copy.remedy)
	}

	@Test
	fun `a page that needs a browser engine is offered the browser component`() {
		val copy = describe(SourceFailure.MissingJsRuntime(source, JsCapability.PAGE_CONTEXT))
		assertEquals(FailureCopy.Remedy.INSTALL_BROWSER, copy.remedy)
	}

	/** The one tier that ships with the app: missing it is a bug, and installing fixes nothing. */
	@Test
	fun `a missing script engine is reported, not offered an install`() {
		val copy = describe(SourceFailure.MissingJsRuntime(source, JsCapability.PLAIN_SCRIPT))
		assertEquals(FailureCopy.Remedy.REPORT, copy.remedy)
	}
}
