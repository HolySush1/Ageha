plugins {
	alias(libs.plugins.compose)
	alias(libs.plugins.compose.compiler)
}

dependencies {
	implementation(project(":core:model"))
	implementation(compose.desktop.currentOs)
	api(libs.coil.compose)
	// Not coil-network-ktor3. Coil defaults to Ktor everywhere except Android; this project runs
	// one OkHttp stack, shared with the parsers library, and a second HTTP client would mean a
	// second cookie jar, a second cache and a second User-Agent. See CLAUDE.md and docs/DESIGN.md.
	api(libs.coil.network.okhttp)
	implementation(libs.okhttp)
	implementation(libs.kotlinx.coroutines.core)
}
