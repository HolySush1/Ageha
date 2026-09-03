plugins {
	alias(libs.plugins.compose)
	alias(libs.plugins.compose.compiler)
}

dependencies {
	implementation(project(":core:designsystem"))
	implementation(compose.desktop.currentOs)
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
