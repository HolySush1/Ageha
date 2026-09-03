package app.ageha.core.source

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.io.InputStream
import java.io.Reader

/**
 * Guards on the shape of the classloader boundary.
 *
 * Both of these are architectural commitments that are cheap to break by accident and expensive
 * to discover later, so they are asserted rather than written down and hoped for.
 */
class BridgeSurfaceTest {

	private val bridgeTypes = listOf(
		ParserBridge::class.java,
		MangaSourceRegistry::class.java,
		MangaSourceClient::class.java,
	)

	@Test
	@DisplayName("no parser-library type appears anywhere in the boundary's signatures")
	fun boundaryIsFreeOfParserTypes() {
		// The entire design rests on this. If a parsers type reached a boundary signature, the
		// parent would have to load it, and we would be back to the frozen-library problem that
		// the allowlist design failed on.
		val offenders = bridgeTypes.flatMap { type ->
			type.methods.flatMap { method ->
				(listOf(method.returnType) + method.parameterTypes).map { method.name to it.name }
			}
		}.filter { (_, typeName) -> typeName.startsWith("org.koitharu.") }

		assertTrue(offenders.isEmpty(), "parser types on the boundary: " + offenders)
	}

	@Test
	@DisplayName("page image bytes never cross the boundary")
	fun readerHotPathCarriesNoBytes() {
		// The reader fetches images itself over HTTP, using imageRequestHeaders(), and never
		// through the bridge. That is what keeps model mapping a per-chapter cost rather than a
		// per-page or per-byte one: pages() maps a chapter's page list once, pageUrl() returns a
		// String and maps nothing, and the bytes are never seen here at all.
		//
		// A method returning a stream or a byte array would quietly move megabytes through the
		// mapping layer on every page turn, so the shape is asserted rather than trusted.
		val byteCarrying = setOf(
			InputStream::class.java,
			Reader::class.java,
			ByteArray::class.java,
			ByteArray::class.java.componentType,
		)

		val offenders = bridgeTypes.flatMap { type ->
			type.methods.map { it.name to it.returnType }
		}.filter { (_, returnType) ->
			returnType in byteCarrying ||
				InputStream::class.java.isAssignableFrom(returnType) ||
				returnType.name.startsWith("okhttp3.Response")
		}

		assertTrue(offenders.isEmpty(), "byte-carrying methods on the boundary: " + offenders)
	}
}
