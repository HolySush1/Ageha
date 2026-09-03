dependencies {
	api(project(":core:model"))
	api(project(":core:js"))
	api(project(":core:network"))

	// One of only two modules allowed to see this. Deliberately `implementation`, not `api`:
	// re-exporting it would put parsers types on every downstream module's compile classpath and
	// dissolve the wall this module sits behind. See the check in the root build file.
	implementation(libs.kotatsu.parsers)

	// Transitive deps of the parsers library. Android excludes org.json because the platform
	// ships it; on the JVM we must supply it or parsers fail at runtime. docs/FINDINGS.md 2.
	implementation(libs.json)
	implementation(libs.androidx.collection)
	implementation(libs.jsoup)
	implementation(libs.kotlinx.coroutines.core)
}
