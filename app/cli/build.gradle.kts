plugins {
	application
}

dependencies {
	implementation(project(":core:parsers"))
	implementation(project(":core:js"))
	implementation(project(":core:browser"))
	implementation(project(":core:network"))
	implementation(project(":core:model"))
	implementation(project(":core:backup"))
	implementation(project(":core:sync"))
	implementation(project(":core:database"))
	// For the default source set and the repository that applies it -- see the `defaults` command.
	implementation(project(":core:data"))
	implementation(libs.kotlinx.coroutines.core)
}

application {
	mainClass.set("app.ageha.cli.MainKt")
}

// The wall check proves this module cannot see the parsers library. That is the point of the
// milestone: the CLI reaches 1360 sources without importing a single parsers-library type.
