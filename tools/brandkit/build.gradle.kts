/*
 * Build-time only. Nothing in the shipped app depends on this module.
 *
 * It exists so the brand assets and colour tokens are *derived*, reproducibly, from the two
 * inputs that define them -- the seed colour and brand/ageha-logo-source.jpg -- rather than
 * eyeballed and pasted. Its outputs are committed, so a normal build never runs it.
 */
dependencies {
	implementation(rootProject.libs.material.color.utilities)
}

val repoRoot: java.io.File = rootProject.projectDir

/** Regenerates the Material 3 tonal palettes and rewrites the generated token file. */
val generatePalette = tasks.register<JavaExec>("generatePalette") {
	group = "brand"
	description = "Derives the light/dark/AMOLED colour tokens from the brand seed."
	mainClass.set("app.ageha.brandkit.PaletteGeneratorKt")
	classpath = sourceSets["main"].runtimeClasspath
	args(repoRoot.absolutePath)
}

/** Rebuilds every icon and logo asset from brand/ageha-logo-source.jpg. */
val generateIcons = tasks.register<JavaExec>("generateIcons") {
	group = "brand"
	description = "Rebuilds the icon matrix from the source logo."
	mainClass.set("app.ageha.brandkit.IconGeneratorKt")
	classpath = sourceSets["main"].runtimeClasspath
	args(repoRoot.absolutePath)
}

tasks.register("generateBrandAssets") {
	group = "brand"
	description = "Runs the whole brand pipeline: colour tokens and icon matrix."
	dependsOn(generatePalette, generateIcons)
}

/** Ad-hoc: dumps the icon pipeline's intermediate masks for inspection. */
tasks.register<JavaExec>("dumpMasks") {
	group = "brand"
	mainClass.set("app.ageha.brandkit.DebugKt")
	classpath = sourceSets["main"].runtimeClasspath
	args(repoRoot.absolutePath, project.findProperty("dbgOut") as String? ?: "build/masks")
}

tasks.withType<Test>().configureEach {
	// The pipeline's tests read the real source logo, because a test against a synthetic image
	// would prove the code runs and nothing about whether it handles *this* artwork.
	systemProperty("ageha.repoRoot", repoRoot.absolutePath)
}
