dependencies {
	api(project(":core:model"))
	api(project(":core:js"))
	// Both kept to this module. :core:jvmcontext exposes parsers types in its own signatures
	// (AgehaMangaLoaderContext extends MangaLoaderContext), so re-exporting either of these would
	// leak the library past the facade.
	implementation(project(":core:jvmcontext"))
	implementation(libs.kotatsu.parsers)
	implementation(libs.okhttp)
	// Parsers raise jsoup's HttpStatusException, which the failure classifier reads directly.
	implementation(libs.jsoup)
	implementation(libs.kotlinx.coroutines.core)
}
