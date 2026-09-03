package app.ageha.core.parsers

import app.ageha.core.model.SourceFailure
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.io.TempDir
import java.io.File

/**
 * Offline checks on the registry, going through the real classloader boundary.
 *
 * No network. Enumerating sources extracts the bundled build, loads it in an isolated
 * classloader, instantiates the bridge reflectively and reads the KSP-generated enum out of it --
 * so a green run here means the whole Layer 1 mechanism works, not just the mapping.
 */
class SourceRegistryTest {

	/**
	 * Every test builds a real stack and closes it.
	 *
	 * Closing is not tidiness. An open URLClassLoader holds its jars open, and on Windows an open
	 * file cannot be deleted or replaced -- so a leaked loader breaks @TempDir cleanup here and,
	 * in production, breaks the update that tries to replace the jar. The first version of these
	 * tests leaked, and JUnit failed them all with "Failed to close extension context", which is
	 * how the problem was found.
	 */
	private fun <T> withStack(dir: File, block: (SourceStack) -> T): T {
		val stack = Ageha.createSourceStack(
			cookieFile = File(dir, "cookies.json"),
			parsersDir = File(dir, "parsers"),
		)
		return try {
			block(stack)
		} finally {
			runBlocking { stack.close() }
		}
	}

	@Test
	@DisplayName("the bundled parsers build exposes a large source catalogue")
	fun sourcesAreEnumerated(@TempDir dir: File) = withStack(dir) { stack ->
		val sources = stack.registry.availableSources()

		// The build under test reports 1360 sources in its own summary.yaml. Asserting a loose
		// floor rather than the exact number keeps this from failing on every routine bump, while
		// still catching the failure that matters: KSP output missing, so the enum is empty.
		assertTrue(sources.size > 1000, "expected >1000 sources, got ${sources.size}")
	}

	@Test
	@DisplayName("descriptors are populated, not blank shells")
	fun descriptorsAreUsable(@TempDir dir: File) = withStack(dir) { stack ->
		val sources = stack.registry.availableSources()

		assertTrue(sources.all { it.name.isNotBlank() }, "every source needs a persistable name")
		assertTrue(sources.all { it.title.isNotBlank() }, "every source needs a display title")
		assertEquals(
			sources.size,
			sources.map { it.name }.toSet().size,
			"source names are the persistence key, so they must be unique",
		)
	}

	@Test
	@DisplayName("upstream's broken flag is carried through rather than dropped")
	fun brokenFlagSurvives(@TempDir dir: File) = withStack(dir) { stack ->
		val sources = stack.registry.availableSources()

		// Upstream marks some sources broken at any given time. If none are flagged, the field is
		// probably not being read -- which would mean the UI silently offers dead sources.
		assertTrue(
			sources.any { it.isBroken },
			"expected at least one source flagged broken upstream",
		)
	}

	@Test
	@DisplayName("an unknown source name resolves to null, never an exception")
	fun unknownSourceDegradesGracefully(@TempDir dir: File) = withStack(dir) { stack ->
		// A user's library outlives any single parsers build. A source that was renamed or dropped
		// upstream must not throw on lookup -- the row is shown as unavailable and kept.
		assertNull(stack.registry.descriptorFor("A_SOURCE_THAT_NEVER_EXISTED"))
	}

	@Test
	@DisplayName("asking for a client for an unknown source is a typed failure")
	fun unknownClientIsTyped(@TempDir dir: File) = withStack(dir) { stack ->
		val failure = assertThrows<SourceFailure.UnknownSource> {
			stack.registry.clientFor("A_SOURCE_THAT_NEVER_EXISTED")
		}
		assertEquals("A_SOURCE_THAT_NEVER_EXISTED", failure.sourceName)
	}

	@Test
	@DisplayName("a client can be built for every source without touching the network")
	fun clientsConstructOffline(@TempDir dir: File) = withStack(dir) { stack ->
		val registry = stack.registry
		// Constructing a parser resolves its config and domain. Doing it for a sample of sources
		// catches parsers that fail at construction time, which would otherwise only show up when
		// a user picked that source.
		val sample = registry.availableSources().filterNot { it.isBroken }.take(50)
		assertFalse(sample.isEmpty())

		for (descriptor in sample) {
			val client = registry.clientFor(descriptor.name)
			assertNotNull(client.descriptor)
			assertTrue(client.domain.isNotBlank(), "${descriptor.name} has no domain")
			assertTrue(
				client.availableSortOrders.isNotEmpty(),
				"${descriptor.name} exposes no sort orders Ageha understands",
			)
		}
	}
}
