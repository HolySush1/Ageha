plugins {
	alias(libs.plugins.compose)
	alias(libs.plugins.compose.compiler)
}

dependencies {
	implementation(project(":core:designsystem"))
	implementation(project(":core:data"))
	implementation(project(":core:image"))
	implementation(compose.desktop.currentOs)
	implementation(libs.kotlinx.coroutines.core)
	// A real database, for the one test that has to prove a write survives shutdown. Faking the
	// repository would prove the fake behaves, not that Room's connection outlives the coroutine.
	testImplementation(project(":core:database"))
	testImplementation(libs.sqlite.bundled)
}
