package app.ageha.feature.reader

import coil3.network.HttpException
import coil3.network.NetworkResponse
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.io.FileNotFoundException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

/**
 * The line under a page that would not load.
 *
 * Its one job is to make a failure diagnosable at a glance. ComicK's images were refused with
 * nothing on screen to say so; "HTTP 403 from cdn1.comicknew.pictures" names the fault and the
 * server in one line.
 */
class ImageFailureReasonTest {

	private val url = "https://cdn1.comicknew.pictures/solo-leveling/0.webp"

	@Test
	fun `a refused image names the status and the server that refused it`() {
		assertEquals(
			"HTTP 403 from cdn1.comicknew.pictures",
			imageFailureReason(HttpException(NetworkResponse(code = 403)), url),
		)
	}

	@Test
	fun `a timeout says so, and names what it was waiting for`() {
		assertEquals(
			"Timed out waiting for cdn1.comicknew.pictures",
			imageFailureReason(SocketTimeoutException(), url),
		)
	}

	@Test
	fun `an unreachable host is not reported as a refusal`() {
		assertEquals("Could not reach cdn1.comicknew.pictures", imageFailureReason(UnknownHostException(), url))
	}

	/**
	 * There is no such thing as "nothing useful to add".
	 *
	 * This case used to assert null, and null draws no line -- which is how a page came to show
	 * "Page 40 could not be loaded" and nothing else, above a Retry that failed the same way every
	 * time. A reader looking at that cannot tell a refused image from a corrupt one from a codec
	 * this build does not have, and neither can a bug report. The exception's own name is worse
	 * than a sentence written for the case and far better than silence.
	 */
	@Test
	fun `an unrecognised failure still names itself`() {
		assertEquals(
			"FileNotFoundException",
			imageFailureReason(FileNotFoundException(), "file:///C:/manga/001.png"),
		)
	}

	@Test
	fun `an unrecognised failure carries its message where it has one`() {
		assertEquals(
			"IllegalStateException: unsupported image format",
			imageFailureReason(IllegalStateException("unsupported image format"), url),
		)
	}

	/**
	 * Coil wraps what the network layer threw, so the interesting exception is usually a cause
	 * rather than the top of the stack. Reading only the top reported a refused image as whatever
	 * generic wrapper happened to be outermost.
	 */
	@Test
	fun `a status buried in the cause chain is still found`() {
		assertEquals(
			"HTTP 403 from cdn1.comicknew.pictures",
			imageFailureReason(
				RuntimeException("wrapped", HttpException(NetworkResponse(code = 403))),
				url,
			),
		)
	}

	@Test
	fun `a url that will not parse still gets its status`() {
		assertEquals("HTTP 429", imageFailureReason(HttpException(NetworkResponse(code = 429)), "not a url"))
	}
}
