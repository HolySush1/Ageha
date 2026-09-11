package app.ageha.feature.reader

import coil3.network.HttpException
import coil3.network.NetworkResponse
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
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

	@Test
	fun `a failure with nothing useful to add adds no line`() {
		assertNull(imageFailureReason(FileNotFoundException(), "file:///C:/manga/001.png"))
	}

	@Test
	fun `a url that will not parse still gets its status`() {
		assertEquals("HTTP 429", imageFailureReason(HttpException(NetworkResponse(code = 429)), "not a url"))
	}
}
