plugins {
	alias(libs.plugins.ksp)
	alias(libs.plugins.room)
}

dependencies {
	api(project(":core:model"))
	api(libs.room.runtime)
	implementation(libs.sqlite.bundled)
	implementation(libs.kotlinx.coroutines.core)
	ksp(libs.room.compiler)
}

room {
	// The schema is exported so it can be diffed against the Android app's. Backup import only
	// works while the two agree, and a silent divergence would not show up until a user's import
	// half-succeeded. docs/ARCHITECTURE.md 1.3.
	schemaDirectory("$projectDir/schemas")
}
