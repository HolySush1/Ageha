dependencies {
	api(project(":core:js"))
	implementation(project(":core:model"))
	implementation(libs.kotlinx.coroutines.core)
	// Reads DevTools replies, and re-encodes a page's answer exactly as `JSON.stringify` would --
	// which org.json does not: it escapes `</` as `<\/`, and parsers decoding HTML by hand do not
	// undo that. Already on the app's classpath through :core:network.
	implementation(libs.kotlinx.serialization.json)
	// The JCEF *Java* bindings only -- a few hundred KB, and shipped. The ~200MB of native
	// Chromium is not a dependency at all: jcefmaven downloads it into the user's data directory
	// on demand, which is what makes the browser component optional rather than a tripling of the
	// installer for the 1,340 sources that never need it.
	implementation(libs.jcef)
}
