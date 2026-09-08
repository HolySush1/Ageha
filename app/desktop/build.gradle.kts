import java.net.URI
import java.security.MessageDigest

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

	// Skia is native, so Compose Desktop ships a different artifact per platform, and Conveyor
	// needs the one for the machine it is packaging rather than the one this build happens to run
	// on. Ageha ships Windows on x64 and nothing else (CLAUDE.md 9), so that is the only extra
	// native declared.
	//
	// This used to list all six. Each entry is around 40MB that every packaging build downloads,
	// and five of them were for platforms nobody has run the app on -- including a windows.aarch64
	// that could never be packaged at all, because no JDK vendor publishes a Windows/ARM64 21.
	"windowsAmd64"(compose.desktop.windows_x64)
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

			/*
			 * MSI, not EXE.
			 *
			 * jpackage's EXE target is a self-extracting wrapper around the same MSI, and the MSI
			 * is the one Windows can upgrade in place, repair, and remove from Settings without
			 * the original file. For an app nobody has signed, it is also the one whose publisher
			 * and version a suspicious user can inspect before running it.
			 */
			targetFormats(org.jetbrains.compose.desktop.application.dsl.TargetFormat.Msi)

			windows {
				/*
				 * Installs into the user's own profile, not Program Files.
				 *
				 * No administrator prompt, which matters twice over: Ageha is unsigned, so an
				 * elevation dialog for an unverified publisher is exactly the moment a sensible
				 * person cancels -- and a per-user install is one the same person can remove
				 * again without one. It also lets the app update itself later without asking.
				 */
				perUserInstall = true

				/*
				 * Install under `Programs\`, NOT beside the user's data.
				 *
				 * jpackage derives the install directory from the package name, which for a
				 * per-user install put the binaries in `%LOCALAPPDATA%\Ageha` -- byte for byte
				 * the directory `AgehaPaths.dataDir` keeps the database, cookies and preferences
				 * in. Installing dropped `app\`, `runtime\` and `Ageha.exe` on top of somebody's
				 * library, and uninstalling then removed the install directory *and everything
				 * else in it*: verified, on a real install and a real uninstall, and the whole
				 * profile went.
				 *
				 * `%LOCALAPPDATA%\Programs\Ageha` is the convention every other per-user
				 * Windows app follows, and it keeps the two apart, which is the actual
				 * requirement: uninstalling an app must not take the reading history with it.
				 *
				 * One path segment, because a nested one does not survive the trip.
				 *
				 * `Programs\Ageha` would match what VS Code and friends do, and there is no way
				 * to say it. jpackage takes its arguments in an @argfile whose parser treats a
				 * backslash as an escape, so a single backslash vanishes and the app installs to
				 * `%LOCALAPPDATA%\ProgramsAgeha` -- close enough to right in a build log to
				 * miss. Doubling it gets a real backslash as far as WiX, which then fails with
				 * light.exe exit code 204, as does a forward slash. All three were tried.
				 *
				 * So: one segment, next to the data directory rather than around it. The folder
				 * name differs from the display name, which is ordinary on Windows -- VS Code
				 * installs to "Microsoft VS Code".
				 */
				installationPath = "Ageha Reader"

				// A Start-menu entry and a desktop shortcut, because this is the only way most
				// people will ever launch it.
				menu = true
				menuGroup = "Ageha"
				shortcut = true

				// Constant for the life of the product, and load-bearing: Windows matches
				// installs by this UUID, so changing it turns an upgrade into a second copy
				// sitting alongside the first. Generated once; never regenerate it.
				upgradeUuid = "9f2b41d6-3a7e-4c58-9b0d-6e1f5a7c2d84"

				iconFile.set(rootProject.layout.projectDirectory.file("brand/generated/ageha.ico"))
			}

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

/*
 * The bundled CJK font. Built and tested, no longer shipped -- see the dependency block below.
 *
 * Windows and macOS both ship CJK coverage, so Skia's per-glyph fallback already has somewhere to
 * go and bundling buys them nothing. A Linux machine with no CJK font package installed has
 * nothing to fall back to and shows tofu where a Japanese, Korean or Chinese title should be --
 * which, for a manga reader, is a large share of the library.
 *
 * `AgehaTypography` used to record "bundling costs ~40MB on every platform to help a subset of
 * one". That arithmetic was right about the cost and wrong about the shape of the fix: this ships
 * one 16MB file to the two machines that need it and nothing to the other four.
 *
 * One file rather than four. `NotoSansCJKjp-Regular.otf` is the Japanese-preferred build of the
 * pan-CJK family, which means it carries the whole shared ideograph set plus kana plus hangul --
 * verified, not assumed, by `CjkFontTest`. Its Latin is complete too, which is what lets the app
 * fall back to it wholesale rather than trying to route text by script.
 */
val cjkFontUrl =
	"https://github.com/notofonts/noto-cjk/raw/Sans2.004/Sans/OTF/Japanese/NotoSansCJKjp-Regular.otf"

/** Pinned by tag *and* by hash. A tag can be moved; a hash cannot. */
val cjkFontSha256 = "68a3fc98800b2a27b371f2fb79991daf3633bd89309d4ffaa6946fd587f375b5"

val downloadCjkFont by tasks.registering {
	group = "brand"
	description = "Downloads the Noto Sans CJK face bundled with the Linux packages."
	val target = layout.buildDirectory.file("fonts/NotoSansCJKjp-Regular.otf")
	val url = cjkFontUrl
	val expected = cjkFontSha256
	outputs.file(target)

	// The digest is inlined at both use sites rather than extracted to a helper. A build-script
	// function is a "Gradle script object reference", which the configuration cache refuses to
	// serialize -- so factoring this out is what breaks the build, not what tidies it.
	val digest: (File) -> String = { file ->
		MessageDigest.getInstance("SHA-256")
			.digest(file.readBytes())
			.joinToString("") { byte -> "%02x".format(byte) }
	}

	// Content-addressed, so re-running is free and a moved tag is caught rather than absorbed.
	outputs.upToDateWhen {
		val file = target.get().asFile
		file.exists() && digest(file) == expected
	}
	doLast {
		val file = target.get().asFile
		file.parentFile.mkdirs()
		if (!file.exists() || digest(file) != expected) {
			logger.lifecycle("Downloading the bundled CJK font (16MB, once)...")
			URI(url).toURL().openStream().use { input -> file.outputStream().use(input::copyTo) }
		}
		val actual = digest(file)
		if (actual != expected) {
			file.delete()
			throw GradleException(
				"The CJK font at $url hashed to $actual, expected $expected. " +
					"The upstream tag has moved or the download was corrupted; refusing to " +
					"package a font nobody has checked.",
			)
		}
	}
}

/**
 * The font, as a jar on the classpath.
 *
 * A jar rather than a loose file next to the binary, because a classpath resource is found the
 * same way on every machine and in tests -- whereas a loose file has to be located relative to an
 * install directory that differs between running from Gradle, from a `.deb` and from a tarball.
 */
val cjkFontJar by tasks.registering(Jar::class) {
	group = "brand"
	description = "Packages the bundled CJK font as a classpath resource."
	archiveBaseName.set("ageha-fonts-cjk")
	destinationDirectory.set(layout.buildDirectory.dir("fonts"))
	from(downloadCjkFont) { into("fonts") }
}

dependencies {
	// Not shipped, and deliberately still built and tested.
	//
	// This font existed so Linux packages had CJK coverage; Windows has it from the system, so
	// with Linux out of scope (CLAUDE.md 9) nothing ships it any more. It stays on the *test*
	// classpath rather than being deleted because `AgehaFonts` still has a bundled-resource path,
	// `CjkFontTest` is what proves one file covers four scripts, and a fallback chain that is
	// never exercised is one that quietly rots. Removing the path as well as the platform would be
	// a larger change than this rule asks for.
	testRuntimeOnly(files(cjkFontJar))
}
