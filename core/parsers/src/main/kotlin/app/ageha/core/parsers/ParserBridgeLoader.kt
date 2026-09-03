package app.ageha.core.parsers

import app.ageha.core.js.JsRuntime
import app.ageha.core.network.PersistentCookieJar
import app.ageha.core.source.ParserBridge

/**
 * The one reflective call in the whole design.
 *
 * Everything after this returns is an ordinary typed call through [ParserBridge]. Reflection lives
 * at the seam because there is no other way to reach a class the compiler cannot see -- not
 * because the boundary is loosely typed.
 */
internal object ParserBridgeLoader {

	/**
	 * Must match `RealParserBridge.BRIDGE_CLASS_NAME`. It cannot be referenced as a constant: this
	 * module deliberately has no dependency on the module that declares it, since that module must
	 * not reach the application classpath.
	 */
	const val BRIDGE_CLASS_NAME = "app.ageha.core.jvmcontext.RealParserBridge"

	fun instantiate(
		loader: ClassLoader,
		cookieJar: PersistentCookieJar,
		jsRuntime: JsRuntime,
		version: String,
	): ParserBridge {
		val type = Class.forName(BRIDGE_CLASS_NAME, true, loader)

		// Loading through the child must not hand back the parent's copy. If it does, the bridge
		// jar was left off the loader's URLs, and everything downstream would silently run against
		// the bundled build while reporting the new version.
		check(type.classLoader === loader) {
			"$BRIDGE_CLASS_NAME resolved from ${type.classLoader}, not the isolated loader. " +
				"The bridge jar is missing from the child classloader's URLs."
		}

		val constructor = type.getConstructor(
			PersistentCookieJar::class.java,
			JsRuntime::class.java,
			String::class.java,
		)
		val instance = constructor.newInstance(cookieJar, jsRuntime, version)

		// If this cast fails, ParserBridge was loaded twice -- once per side -- which means the
		// delegation policy stopped treating app.ageha.core.source as parent-first.
		return instance as? ParserBridge ?: error(
			"$BRIDGE_CLASS_NAME does not implement the ParserBridge this classloader can see. " +
				"Check ParsersClassLoader.PARENT_FIRST_PREFIXES.",
		)
	}
}
