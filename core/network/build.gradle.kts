plugins {
	alias(libs.plugins.kotlin.serialization)
}

dependencies {
	// For BrowserCookie, which the jar accepts from the browser component. `api` because it is in
	// the jar's public signature.
	api(project(":core:model"))
	api(libs.okhttp)
	implementation(libs.okio)
	implementation(libs.kotlinx.coroutines.core)
	implementation(libs.kotlinx.serialization.json)
}
