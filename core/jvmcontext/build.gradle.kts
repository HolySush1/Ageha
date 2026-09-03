dependencies {
	// These cross the classloader boundary, so both sides must resolve them to the same class.
	// ParsersClassLoader delegates every one of these packages to the parent.
	api(project(":core:model"))
	api(project(":core:js"))
	api(project(":core:network"))

	// compileOnly, deliberately, and this is the load-bearing line in the file.
	//
	// This module implements MangaLoaderContext, so at runtime it must be loaded by the same
	// classloader as whatever parsers build is active -- never by the application classloader.
	// Its jar is handed to ParsersClassLoader alongside the parsers jar. Promoting this to
	// `implementation` would put both on the app classpath, the child loader would shadow them,
	// and the two copies would diverge in a way that only shows up as LinkageError after an
	// update. See ParsersClassLoader's class comment.
	compileOnly(libs.kotatsu.parsers)

	// Transitive deps of the parsers library. Android excludes org.json because the platform
	// ships it; on the JVM we must supply it or parsers fail at runtime. docs/FINDINGS.md 2.
	compileOnly(libs.json)
	compileOnly(libs.androidx.collection)
	implementation(libs.jsoup)
	implementation(libs.kotlinx.coroutines.core)

	// Tests run this module's code directly, so they need at runtime what compileOnly withheld.
	testImplementation(libs.kotatsu.parsers)
	testImplementation(libs.json)
	testImplementation(libs.androidx.collection)
}
