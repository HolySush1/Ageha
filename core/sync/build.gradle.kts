plugins {
	alias(libs.plugins.kotlin.serialization)
}

dependencies {
	api(project(":core:database"))
	api(project(":core:network"))
	implementation(libs.kotlinx.coroutines.core)
	implementation(libs.kotlinx.serialization.json)
	implementation(libs.okhttp)
	testImplementation(libs.sqlite.bundled)
}
