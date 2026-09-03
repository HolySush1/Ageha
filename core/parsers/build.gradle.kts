plugins {
	alias(libs.plugins.kotlin.serialization)
}

dependencies {
	api(project(":core:model"))
	api(project(":core:js"))
	api(project(":core:network"))
	implementation(libs.kotlinx.serialization.json)
	implementation(libs.kotlinx.coroutines.core)

	// Note what is absent: the parsers library, and :core:jvmcontext. This module runs on the
	// application classloader and must not be able to name a parsers type -- it reaches the
	// library only through ParserBridge, across a classloader boundary. The wall check in the root
	// build file now passes here without an exemption, which it could not before.
}

// ---------------------------------------------------------------------------------------------
// Staging the bundled parsers build
//
// Ageha ships a known-good parsers build inside itself, as the fallback the update engine falls
// back to. It travels as a resource and is extracted on first run, because a classloader needs a
// real file URL -- and because that makes the bundled build take exactly the same code path as a
// downloaded one.
//
// Two jars are staged. The parsers library, and :core:jvmcontext's own jar, which implements
// MangaLoaderContext and so must be loaded with it rather than beside it.
// ---------------------------------------------------------------------------------------------

val bundledParsers: Configuration by configurations.creating {
	isTransitive = false
	isCanBeConsumed = false
}

val bundledBridge: Configuration by configurations.creating {
	isTransitive = false
	isCanBeConsumed = false
}

/**
 * The parsers library's own dependencies, resolved rather than assumed.
 *
 * These load in the child alongside the parsers build, not on the application classpath, so each
 * build travels with the dependency versions it was compiled against. androidx.collection and
 * org.json are the ones that bite: Android excludes org.json because the platform provides it, and
 * a JVM host that forgets to supply it gets NoClassDefFoundError from deep inside a parser.
 */
val bundledParsersLibs: Configuration by configurations.creating {
	isTransitive = true
	isCanBeConsumed = false
}

dependencies {
	bundledParsers(libs.kotatsu.parsers)
	bundledBridge(project(":core:jvmcontext"))
	bundledParsersLibs(libs.kotatsu.parsers)
}

/** Groups the parent already provides, and which must therefore not be duplicated in the child. */
val sharedWithParent = setOf(
	"org.jetbrains.kotlin",
	"org.jetbrains.kotlinx",
	"org.jetbrains",
	"com.squareup.okhttp3",
	"com.squareup.okio",
)

val stageBundledParsers by tasks.registering(Sync::class) {
	description = "Stages the bundled parsers build, its bridge and its dependencies as resources."

	val childLibs = bundledParsersLibs.incoming.artifacts.resolvedArtifacts.map { artifacts ->
		artifacts.filter { artifact ->
			val id = artifact.id.componentIdentifier
			val group = (id as? org.gradle.api.artifacts.component.ModuleComponentIdentifier)?.group
			// Keep the parsers jar itself out; it is staged separately under a stable name.
			group != null && group !in sharedWithParent &&
				!artifact.file.name.startsWith("kotatsu-parsers-redo")
		}.map { it.file }
	}

	from(bundledParsers) { rename { "kotatsu-parsers.jar" } }
	from(bundledBridge) { rename { "ageha-bridge.jar" } }
	from(childLibs) { into("libs") }
	into(layout.buildDirectory.dir("bundled-resources/app/ageha/bundled"))

	// A jar cannot be listed like a directory at runtime, so the extractor is handed an index
	// instead of being asked to enumerate one.
	val indexFile = layout.buildDirectory.file("bundled-resources/app/ageha/bundled/index.txt")
	outputs.file(indexFile)
	doLast {
		val names = childLibs.get().map { "libs/" + it.name }.sorted()
		val all = listOf("kotatsu-parsers.jar", "ageha-bridge.jar") + names
		indexFile.get().asFile.printWriter().use { out -> all.forEach(out::println) }
	}
}

sourceSets.named("main") {
	resources.srcDir(layout.buildDirectory.dir("bundled-resources"))
}

tasks.named("processResources") { dependsOn(stageBundledParsers) }

val checkBundledVersion by tasks.registering {
	description = "Fails if BundledParsers.VERSION disagrees with the staged parsers jar."
	val declared = libs.versions.parsers.get()
	val source = layout.projectDirectory
		.file("src/main/kotlin/app/ageha/core/parsers/BundledParsers.kt").asFile
	inputs.file(source)
	inputs.property("declared", declared)
	outputs.upToDateWhen { true }
	doLast {
		val found = Regex("""const val VERSION = "([^"]+)"""")
			.find(source.readText())?.groupValues?.get(1)
		if (found != declared) {
			throw GradleException(
				"BundledParsers.VERSION is \"$found\" but gradle/libs.versions.toml pins " +
					"\"$declared\". They name the same build and must match.",
			)
		}
	}
}

tasks.named("check") { dependsOn(checkBundledVersion) }
