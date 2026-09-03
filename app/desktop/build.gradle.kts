plugins {
	alias(libs.plugins.compose)
	alias(libs.plugins.compose.compiler)
	alias(libs.plugins.kotlin.serialization)
}

dependencies {
	implementation(project(":core:designsystem"))
	implementation(project(":core:data"))
	implementation(project(":core:image"))
	implementation(project(":core:parsers"))
	implementation(project(":core:network"))
	implementation(project(":core:database"))
	implementation(project(":feature:library"))
	implementation(project(":feature:explore"))
	implementation(compose.desktop.currentOs)
	implementation(libs.koin.core)
	implementation(libs.kotlinx.coroutines.core)
	implementation(libs.kotlinx.serialization.json)
	runtimeOnly(libs.sqlite.bundled)
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
