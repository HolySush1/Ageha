plugins {
	alias(libs.plugins.kotlin.serialization)
}

dependencies {
	api(libs.okhttp)
	implementation(libs.okio)
	implementation(libs.kotlinx.coroutines.core)
	implementation(libs.kotlinx.serialization.json)
}
