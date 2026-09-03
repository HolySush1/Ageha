import app.ageha.build.CheckParsersWallTask
import org.gradle.api.artifacts.result.ResolvedArtifactResult

plugins {
	alias(libs.plugins.kotlin.jvm) apply false
	alias(libs.plugins.kotlin.serialization) apply false
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
