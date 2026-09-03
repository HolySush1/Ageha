plugins {
	alias(libs.plugins.compose)
	alias(libs.plugins.compose.compiler)
}

dependencies {
	// `compose.desktop.currentOs` pulls the Skia backend for whichever machine is building. That
	// is correct for development and for :app:cli's test runs; the release build overrides it per
	// target platform in the packaging config (milestone 9).
	implementation(compose.desktop.currentOs)
	api(compose.material3)
	api(compose.materialIconsExtended)
}
