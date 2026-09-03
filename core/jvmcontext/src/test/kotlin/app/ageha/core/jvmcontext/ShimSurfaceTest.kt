package app.ageha.core.jvmcontext

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.koitharu.kotatsu.parsers.MangaLoaderContext
import java.lang.reflect.Modifier

/**
 * The size of the shim, held to a number.
 *
 * Every member Ageha implements against `MangaLoaderContext` is a place upstream can move and
 * break us. That is not hypothetical: `evaluateJs` gained a third parameter between the build
 * `kotatsu-dl` targets and the one Ageha ships, and each such change needs an app release no
 * amount of facade tolerance absorbs.
 *
 * So the count is a metric rather than an accident. This test fails when it changes in either
 * direction. Growing it is sometimes correct -- upstream adds an abstract member and there is no
 * choice -- but it should be a decision someone made, recorded in `docs/ARCHITECTURE.md`, not
 * something that drifted upward one convenience override at a time.
 */
class ShimSurfaceTest {

	/**
	 * Members of `MangaLoaderContext` that `AgehaMangaLoaderContext` implements.
	 *
	 * See `docs/ARCHITECTURE.md` 4.5 for the breakdown and for which of these can go away.
	 */
	private val expectedShimSize = 13

	/**
	 * Counted by signature, not by name.
	 *
	 * `evaluateJs` and `interceptWebViewRequests` are each overridden twice, and each overload is
	 * its own break point -- `evaluateJs` gaining a parameter is precisely how upstream broke
	 * `kotatsu-dl`. Counting names would have hidden that.
	 */
	private fun overriddenMembers(): List<String> {
		val upstream = MangaLoaderContext::class.java
		val ours = AgehaMangaLoaderContext::class.java

		fun signature(method: java.lang.reflect.Method) =
			method.name + "/" + method.parameterTypes.size

		val upstreamSignatures = upstream.declaredMethods
			.filterNot { it.isSynthetic || it.isBridge }
			.map(::signature)
			.toSet()

		return ours.declaredMethods
			.filterNot { it.isSynthetic || it.isBridge }
			.filter { Modifier.isPublic(it.modifiers) }
			.map(::signature)
			.filter { it in upstreamSignatures }
			.distinct()
			.sorted()
	}

	@Test
	@DisplayName("the shim surface has not changed size without anyone noticing")
	fun shimSurfaceIsTracked() {
		val members = overriddenMembers()
		assertEquals(
			expectedShimSize,
			members.size,
			"The shim surface changed. This is a compatibility-risk metric, not a detail: " +
				"update expectedShimSize and the count in docs/ARCHITECTURE.md 4.5, and say why " +
				"in the commit. Current members: " + members,
		)
	}

	@Test
	@DisplayName("every abstract member upstream declares is implemented")
	fun noAbstractMemberIsLeftUnimplemented() {
		// The failure this prevents is AbstractMethodError, mid-session, in front of a user. The
		// compiler catches it for the bundled build; the compatibility gate catches it for a
		// downloaded one. This asserts the bundled case directly.
		val unimplemented = MangaLoaderContext::class.java.declaredMethods
			.filter { Modifier.isAbstract(it.modifiers) }
			.filterNot { it.isSynthetic || it.isBridge }
			.filter { abstractMethod ->
				AgehaMangaLoaderContext::class.java.declaredMethods.none {
					it.name == abstractMethod.name &&
						it.parameterTypes.size == abstractMethod.parameterTypes.size
				}
			}
			.map { it.name }

		assertTrue(unimplemented.isEmpty(), "unimplemented abstract members: " + unimplemented)
	}
}
