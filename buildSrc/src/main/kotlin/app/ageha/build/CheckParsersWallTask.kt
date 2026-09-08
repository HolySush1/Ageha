package app.ageha.build

import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import org.gradle.api.provider.SetProperty
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.TaskAction

/**
 * Fails the build if a module outside the facade can see the parsers library.
 *
 * This is CLAUDE.md rule 5 and docs/ARCHITECTURE.md 2, enforced rather than merely agreed. The
 * rule is what stops an upstream API change from spraying compile errors across a dozen modules,
 * and rules of that kind do not survive a hurried afternoon unless the build enforces them.
 *
 * It lives in buildSrc rather than in the root build script because the configuration cache
 * cannot serialise a task type declared inside a script.
 */
abstract class CheckParsersWallTask : DefaultTask() {

	/**
	 * Declared as an input so Gradle builds the dependency jars before this runs. The files
	 * themselves are never read -- resolving the classpath early is what the earlier version of
	 * this task got wrong.
	 */
	@get:InputFiles
	abstract val classpath: ConfigurableFileCollection

	@get:Input
	abstract val componentIds: ListProperty<String>

	/** The *project* path. Note this cannot be read off the task, whose path is a different thing. */
	@get:Input
	abstract val projectPath: Property<String>

	@get:Input
	abstract val forbiddenCoordinate: Property<String>

	@get:Input
	abstract val exemptions: SetProperty<String>

	@TaskAction
	fun check() {
		val project = projectPath.get()
		if (project in exemptions.get()) return
		val offenders = componentIds.get().filter { it.startsWith(forbiddenCoordinate.get()) }
		if (offenders.isEmpty()) return
		throw GradleException(
			buildString {
				appendLine("$project has the parsers library on its compile classpath: $offenders")
				appendLine()
				appendLine("Only ${exemptions.get()} may import parsers-library types (CLAUDE.md rule 5).")
				appendLine("Route this through the :core:parsers facade instead. If the facade genuinely")
				appendLine("cannot express what you need, widen the facade -- do not widen the wall.")
			},
		)
	}
}
