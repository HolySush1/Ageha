plugins {
	alias(libs.plugins.compose)
	alias(libs.plugins.compose.compiler)
	alias(libs.plugins.kotlin.serialization)
	// Packaging. Generates the Conveyor config fragment describing this module's classpath and
	// JVM, so conveyor.conf does not have to restate anything the build already knows.
	alias(libs.plugins.conveyor)
}

dependencies {
	implementation(project(":core:designsystem"))
	implementation(project(":core:data"))
	implementation(project(":core:image"))
	implementation(project(":core:parsers"))
	implementation(project(":core:network"))
	implementation(project(":core:js"))
	implementation(project(":core:database"))
	implementation(project(":feature:library"))
	implementation(project(":feature:explore"))
	implementation(project(":feature:reader"))
	implementation(project(":feature:settings"))
	implementation(project(":feature:downloads"))
	implementation(project(":core:backup"))
	implementation(compose.desktop.currentOs)
	implementation(libs.koin.core)
	implementation(libs.okhttp)
	implementation(libs.kotlinx.coroutines.core)
	implementation(libs.kotlinx.serialization.json)
	runtimeOnly(libs.sqlite.bundled)

	// Skia is native, so Compose Desktop ships a different artifact per platform. `currentOS`
	// above is right for running and testing here; these are what Conveyor needs to build an
	// installer for a machine that is not this one. Without them, a Linux build made on Windows
	// would carry Windows Skia and fail at first paint.
	"linuxAmd64"(compose.desktop.linux_x64)
	"linuxAarch64"(compose.desktop.linux_arm64)
	"macAmd64"(compose.desktop.macos_x64)
	"macAarch64"(compose.desktop.macos_arm64)
	"windowsAmd64"(compose.desktop.windows_x64)
	"windowsAarch64"(compose.desktop.windows_arm64)
}

compose.desktop {
	application {
		mainClass = "app.ageha.desktop.MainKt"
	}
}

/** Renders the theme gallery to docs/design-gallery.png for review off this machine. */
tasks.register<JavaExec>("renderGallery") {
	group = "brand"
	description = "Renders the theme gallery to a PNG without opening a window."
	mainClass.set("app.ageha.desktop.GalleryRenderKt")
	classpath = sourceSets["main"].runtimeClasspath
	args(File(rootProject.projectDir, "docs/design-gallery.png").absolutePath)
}

/** Renders the real shell headlessly. Proves the graph builds and the screens compose. */
tasks.register<JavaExec>("renderShell") {
	group = "verification"
	description = "Renders the application shell to PNGs without a display."
	mainClass.set("app.ageha.desktop.ShellRenderKt")
	classpath = sourceSets["main"].runtimeClasspath
	args(layout.buildDirectory.dir("shell").get().asFile.absolutePath)
}
