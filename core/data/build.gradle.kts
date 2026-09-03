dependencies {
	api(project(":core:model"))
	api(project(":core:database"))
	implementation(project(":core:parsers"))
	implementation(libs.kotlinx.coroutines.core)
	testImplementation(project(":core:database"))
	testImplementation(libs.sqlite.bundled)
}
