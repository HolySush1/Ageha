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
	implementation(project(":core:sync"))
	implementation(compose.desktop.currentOs)
	implementation(libs.koin.core)
	implementation(libs.okhttp)
	implementation(libs.kotlinx.coroutines.core)
	implementation(libs.kotlinx.serialization.json)
	runtimeOnly(libs.sqlite.bundled)

	// Drives the real shell in the end-to-end test: semantics-based finders, synthetic clicks and
	// typing, and an idle-aware clock. `runComposeUiTest` is the non-Rule entry point, so this
	// does not drag JUnit 4 into a JUnit 5 project.
	testImplementation(compose.desktop.uiTestJUnit4)

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

		nativeDistributions {
			packageName = "Ageha"
			description = "A desktop manga reader"
			vendor = "Ageha"
			copyright = "GPL-3.0-or-later"

			/*
			 * jpackage's own version, which is not the project's.
			 *
			 * It rejects anything that is not strictly numeric-dotted -- "0.1.0-SNAPSHOT" fails
			 * with `Version [0.1.0-SNAPSHOT] contains invalid component [0-SNAPSHOT]`, which is
			 * what `createDistributable` and `runDistributable` did on every invocation before
			 * this. Conveyor does not go through jpackage and so never hit it, which is exactly
			 * why it went unnoticed: the shipping path worked and the local one did not.
			 */
			packageVersion = project.version.toString().substringBefore("-SNAPSHOT")

			// jlink strips whatever is not asked for. These are the same modules conveyor.conf
			// names, and for the same reasons: AWT for the file picker, icon pipeline and font
			// enumeration; java.sql for the bundled SQLite driver. Omitting either produces a
			// build that starts and then fails the first time a user opens a file dialog.
			modules("java.desktop", "java.sql", "java.naming")
		}
	}
}


/*
 * Every tool below runs the real application graph, and therefore opens a real profile.
 *
 * Pointed at `build/` rather than at the user's own directory. `renderShell` opens a sample CBZ
 * and `webtoonProfile` scrolls a 200-page strip, and both of those land in reading history -- so
 * before this, generating a screenshot silently added entries to whatever library was on the
 * machine. See AgehaPaths for the override itself.
 */
fun JavaExec.useScratchProfile(name: String) {
	val dir = layout.buildDirectory.dir("profiles/$name")
	systemProperty("ageha.data.dir", dir.get().asFile.absolutePath)
}

/** Renders the theme gallery to docs/design-gallery.png for review off this machine. */
tasks.register<JavaExec>("renderGallery") {
	group = "brand"
	description = "Renders the theme gallery to a PNG without opening a window."
	mainClass.set("app.ageha.desktop.GalleryRenderKt")
	classpath = sourceSets["main"].runtimeClasspath
	useScratchProfile("gallery")
	args(File(rootProject.projectDir, "docs/design-gallery.png").absolutePath)
}

/**
 * Profiles the webtoon reader on a real 200-page strip.
 *
 * The open risk from `docs/ARCHITECTURE.md` 1.4. Not part of `check`: it takes a minute and its
 * output is a measurement to read, not a threshold to fail on -- a frame-time assertion on shared
 * CI hardware would be a flaky test rather than a guard.
 */
tasks.register<JavaExec>("webtoonProfile") {
	group = "verification"
	description = "Scrolls a 200-page webtoon strip and reports frame times and heap."
	mainClass.set("app.ageha.desktop.WebtoonProfileKt")
	classpath = sourceSets["main"].runtimeClasspath
	useScratchProfile("webtoon")
	args(layout.buildDirectory.dir("profile").get().asFile.absolutePath)
}

/** Renders the real shell headlessly. Proves the graph builds and the screens compose. */
tasks.register<JavaExec>("renderShell") {
	group = "verification"
	description = "Renders the application shell to PNGs without a display."
	mainClass.set("app.ageha.desktop.ShellRenderKt")
	classpath = sourceSets["main"].runtimeClasspath
	useScratchProfile("shell")
	args(layout.buildDirectory.dir("shell").get().asFile.absolutePath)
}

/**
 * Keep `AgehaVersion.CURRENT` honest.
 *
 * The same guard `:core:parsers` puts on `BundledParsers.VERSION`, for the same reason: a constant
 * that has to agree with something else in the build will eventually stop agreeing with it, and
 * the symptom here would be an app that tells every user they are running a version they are not.
 * The project version carries a `-SNAPSHOT` suffix between releases, which is not part of the
 * released version number, so it is stripped before comparing.
 */
val checkAppVersion by tasks.registering {
	group = "verification"
	description = "Fails if AgehaVersion.NAME disagrees with the project version."
	val declared = project.version.toString().substringBefore("-SNAPSHOT")
	val source = rootProject.layout.projectDirectory
		.file("core/model/src/main/kotlin/app/ageha/core/model/AgehaVersion.kt").asFile
	inputs.file(source)
	inputs.property("declared", declared)
	outputs.upToDateWhen { true }
	doLast {
		val found = Regex("""const val NAME = "([^"]+)"""")
			.find(source.readText())?.groupValues?.get(1)
		if (found != declared) {
			throw GradleException(
				"AgehaVersion.NAME is \"$found\" but the project version is \"$declared\". " +
					"They name the same release and must match.",
			)
		}
	}
}

tasks.named("check") { dependsOn(checkAppVersion) }

/**
 * Print the runtime classpath, one entry per line.
 *
 * The end-to-end driver launches Ageha with `java` directly rather than through `run`, because it
 * needs to set `AGEHA_DATA_DIR` per launch and to own the process it later kills. Gradle's run
 * task gives it neither.
 */
tasks.register("printRuntimeClasspath") {
	group = "verification"
	description = "Prints the runtime classpath for launching the app outside Gradle."
	val cp = sourceSets["main"].runtimeClasspath
	doLast { cp.forEach { println(it.absolutePath) } }
}

/**
 * The end-to-end journey: launch, find, read, close, reopen, resume.
 *
 * A task of its own rather than part of `check`, for two reasons. It takes minutes, because it
 * boots the whole application twice and waits on real I/O each time. And it needs a profile
 * directory of its own -- pointed at `build/` below -- so that a test which reads manga cannot
 * write into the library somebody actually uses.
 *
 *   ./gradlew :app:desktop:e2e                 # local archive, deterministic
 *   ./gradlew :app:desktop:e2e -PwithNetwork   # adds the live MangaDex journey
 */
val e2eProfile = layout.buildDirectory.dir("e2e-profile")

val e2e by tasks.registering(Test::class) {
	group = "verification"
	description = "Drives the real app end to end: read a chapter, reopen, resume where it left off."
	testClassesDirs = sourceSets["test"].output.classesDirs
	classpath = sourceSets["test"].runtimeClasspath

	// Wiped each run. The point of the test is what survives a restart *within* one run; anything
	// left over from the last one would be a false pass waiting to happen.
	val profileDir = e2eProfile
	doFirst { profileDir.get().asFile.deleteRecursively() }
	systemProperty("ageha.data.dir", profileDir.get().asFile.absolutePath)

	useJUnitPlatform {
		includeTags("e2e")
		// The live-source phase is opt-in, exactly as every other networked test in the build is.
		if (!project.hasProperty("withNetwork")) excludeTags("network")
	}
	testLogging { events("passed", "skipped", "failed") }

	// Never up-to-date. It exercises live state and the network; "no inputs changed" is not a
	// reason to believe it would still pass.
	outputs.upToDateWhen { false }
}
