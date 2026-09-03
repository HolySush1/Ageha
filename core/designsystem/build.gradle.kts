plugins {
	alias(libs.plugins.compose)
	alias(libs.plugins.compose.compiler)
}

dependencies {
	// The brief puts "shared components" in this module, and a shared manga cover has to know what
	// a manga is. :core:model is pure data with no behaviour and no parsers types -- the wall in
	// the root build still applies here -- so the dependency costs nothing and keeps every screen
	// from growing its own cover card.
	api(project(":core:model"))
	api(project(":core:image"))

	// `compose.desktop.currentOs` pulls the Skia backend for whichever machine is building. That
	// is correct for development and for :app:cli's test runs; the release build overrides it per
	// target platform in the packaging config (milestone 9).
	implementation(compose.desktop.currentOs)
	// Named directly rather than through the plugin's `compose.material3` shorthand, which is
	// deprecated now that Material 3 versions independently of the Compose plugin.
	api(libs.compose.material3)
	api(libs.compose.material.icons.core)
}
