plugins {
	alias(libs.plugins.compose)
	alias(libs.plugins.compose.compiler)
}

dependencies {
	implementation(project(":core:designsystem"))
	implementation(project(":core:data"))
	implementation(compose.desktop.currentOs)
	implementation(libs.kotlinx.coroutines.core)
}
