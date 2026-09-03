import app.ageha.build.CheckParsersWallTask
import org.gradle.api.artifacts.result.ResolvedArtifactResult

plugins {
	alias(libs.plugins.kotlin.jvm) apply false
	alias(libs.plugins.kotlin.serialization) apply false
}

/** The only modules permitted to see parsers-library types. See [CheckParsersWallTask]. */
val parsersWallExemptions = setOf(":core:parsers", ":core:jvmcontext")
val parsersCoordinate = "com.github.Kotatsu-Redo:kotatsu-parsers-redo"

subprojects {
	// :core and :app are grouping directories with no sources of their own.
	if (childProjects.isNotEmpty()) return@subprojects

	apply(plugin = "org.jetbrains.kotlin.jvm")

	group = "app.ageha"
	version = "0.1.0-SNAPSHOT"

	extensions.configure<org.jetbrains.kotlin.gradle.dsl.KotlinJvmProjectExtension>("kotlin") {
		jvmToolchain(21)
	}

	tasks.withType<Test>().configureEach {
		useJUnitPlatform {
			// Networked tests hit live manga sources, so they are opt-in:
			//   ./gradlew test -PwithNetwork
			if (!project.hasProperty("withNetwork")) {
				excludeTags("network")
			}
		}
		testLogging {
			events("passed", "skipped", "failed")
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
