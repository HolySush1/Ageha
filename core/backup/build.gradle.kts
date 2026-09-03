plugins {
	alias(libs.plugins.kotlin.serialization)
}

dependencies {
	api(project(":core:model"))
	api(project(":core:database"))
	implementation(libs.kotlinx.serialization.json)
	implementation(libs.kotlinx.coroutines.core)
}
