import app.ageha.build.CheckParsersWallTask
import org.gradle.api.artifacts.result.ResolvedArtifactResult

plugins {
	alias(libs.plugins.kotlin.jvm) apply false
	alias(libs.plugins.kotlin.serialization) apply false
	alias(libs.plugins.ksp) apply false
	alias(libs.plugins.room) apply false
	alias(libs.plugins.compose) apply false
	alias(libs.plugins.compose.compiler) apply false
}

/**
 * The only module permitted to see parsers-library types.
 *
 * It was two until Milestone 3. Moving the parsers behind a classloader boundary meant
 * :core:parsers stopped needing to name a parsers type at all -- it reaches the library through
 * ParserBridge instead -- so the exemption narrowed to the one module that genuinely implements
 * against the library. A narrower exemption is a stronger guarantee, so it is worth keeping tight.
 */
val parsersWallExemptions = setOf(":core:jvmcontext")
val parsersCoordinate = "com.github.Kotatsu-Redo:kotatsu-parsers-redo"

subprojects {
	// :core and :app are grouping directories with no sources of their own.
	if (childProjects.isNotEmpty()) return@subprojects

	apply(plugin = "org.jetbrains.kotlin.jvm")

	group = "app.ageha"
	version = "0.3.2"

	extensions.configure<org.jetbrains.kotlin.gradle.dsl.KotlinJvmProjectExtension>("kotlin") {
		jvmToolchain(21)
	}

	// Every dependency gets *this* project's Kotlin stdlib, whatever it asked for.
	//
	// Gradle resolves version conflicts by picking the highest, so a single library depending on a
	// newer Kotlin silently upgrades the stdlib for the whole build -- and the failure surfaces as
	// "Unresolved reference 'apply'", pointing at our own source rather than at the dependency
	// that caused it. Forcing it turns a confusing compile error into an obvious version conflict.
	configurations.configureEach {
		resolutionStrategy {
			force("org.jetbrains.kotlin:kotlin-stdlib:${rootProject.libs.versions.kotlin.get()}")
		}
	}

	tasks.withType<Test>().configureEach {
		testLogging {
			events("passed", "skipped", "failed")
		}

		/*
		 * Tag filtering, applied only to the standard `test` task.
		 *
		 * Scoped by name rather than to every Test task, because a task that opts *in* to a tag
		 * must not have this add the matching exclusion behind it: JUnit resolves include-and-
		 * exclude of the same tag as excluded, so `:app:desktop:e2e` ran zero tests and reported
		 * success. A test task that passes by running nothing is worse than one that fails.
		 */
		if (name != "test") return@configureEach

		useJUnitPlatform {
			// Networked tests hit live manga sources, so they are opt-in:
			//   ./gradlew test -PwithNetwork
			if (!project.hasProperty("withNetwork")) {
				excludeTags("network")
			}
			// The end-to-end journey is never part of an ordinary run. It has a task of its own,
			// `:app:desktop:e2e`, which gives it the scratch profile directory it needs.
			//
			// Excluded here rather than merely for being slow: it boots the whole application, and
			// starting the application installs Coil's *process-global* singleton image loader.
			// `SingletonImageLoader.setSafe` keeps the first loader it is given, so whichever test
			// booted an application first would decide what every later test in the same JVM saw
			// -- which is exactly how ImageLoaderWiringTest started failing on an unrelated
			// change. A test that quietly rewrites global state for its neighbours does not belong
			// in the same run as them.
			excludeTags("e2e")
		}
	}

	dependencies {
		add("testImplementation", rootProject.libs.junit.api)
		add("testImplementation", rootProject.libs.junit.params)
		add("testImplementation", rootProject.libs.kotlinx.coroutines.test)
		add("testRuntimeOnly", rootProject.libs.junit.engine)
		add("testRuntimeOnly", rootProject.libs.junit.launcher)
	}

	// Captured out here on purpose: inside a `tasks.register { }` block the receiver is the task,
	// so `path` would silently mean the task's path rather than the project's.
	val modulePath = path
	val compileClasspath = configurations.named("compileClasspath")

	val wallCheck = tasks.register<CheckParsersWallTask>("checkParsersWall") {
		group = "verification"
		description = "Fails if a module outside the facade depends on the parsers library."
		classpath.from(compileClasspath)
		componentIds.set(
			compileClasspath
				.flatMap { it.incoming.artifacts.resolvedArtifacts }
				.map { artifacts: Set<ResolvedArtifactResult> ->
					artifacts.map { it.id.componentIdentifier.displayName }
				},
		)
		projectPath.set(modulePath)
		forbiddenCoordinate.set(parsersCoordinate)
		exemptions.set(parsersWallExemptions)
	}

	tasks.named("check") { dependsOn(wallCheck) }
}
