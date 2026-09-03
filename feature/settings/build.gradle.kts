plugins {
	alias(libs.plugins.compose)
	alias(libs.plugins.compose.compiler)
}

dependencies {
	implementation(project(":core:designsystem"))
	implementation(project(":core:data"))
	// Settings is where Layer 1 gets a face: the parsers version, the update check, rollback and
	// pinning all live here, so this is one of the few feature modules that talks to :core:parsers.
	implementation(project(":core:parsers"))
	implementation(project(":core:backup"))
	implementation(project(":core:js"))
	implementation(compose.desktop.currentOs)
	implementation(libs.kotlinx.coroutines.core)
}
