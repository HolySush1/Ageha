pluginManagement {
	repositories {
		mavenCentral()
		gradlePluginPortal()
		// The Room Gradle plugin is published to Google's Maven, not to Central or the plugin
		// portal. Scoped to androidx so nothing else can resolve from here by accident.
		google {
			content {
				includeGroupByRegex("androidx\\..*")
				includeGroupByRegex("com\\.google\\..*")
			}
		}
	}
}

dependencyResolutionManagement {
	repositoriesMode = RepositoriesMode.FAIL_ON_PROJECT_REPOS
	repositories {
		mavenCentral()
		// androidx.collection is a transitive dependency of the parsers library. It is pure JVM
		// and works fine off Android, but it is published to Google's Maven repo, not Central.
		// Scoped to androidx so an unrelated dependency cannot silently resolve from here.
		google {
			content {
				includeGroupByRegex("androidx\\..*")
			}
		}
		// The parsers library is published only via JitPack. See docs/FINDINGS.md 1.
		maven("https://jitpack.io") {
			content {
				includeGroup("com.github.Kotatsu-Redo")
			}
		}
	}
}

rootProject.name = "ageha"

// Modules arrive at their milestone. Two planned in the brief were never created, deliberately:
// :feature:tracking, because external tracking services are out of scope for good (CLAUDE.md 9)
// and Continue Reading lives in :feature:library instead; and :feature:updates, because the update
// engine lives in :core:parsers and needed a screen rather than a module of its own.
//
// :core:sync is not in the brief's list either. It arrived once the brief's open question about
// the kotatsu-syncserver protocol was actually answered: the wire format is portable and only the
// Android app's plumbing around it is not. See docs/ARCHITECTURE.md 7d.
include(
	":core:model",
	":core:network",
	":core:js",
	":core:jvmcontext",
	":core:backup",
	":core:sync",
	":core:database",
	":core:parsers",
	":core:designsystem",
	":core:image",
	":core:data",
	":feature:library",
	":feature:explore",
	":feature:reader",
	":feature:settings",
	":feature:downloads",
	":app:cli",
	":app:desktop",
	// Not shipped. Build-time asset and token generation; see docs/DESIGN.md 2.
	":tools:brandkit",
)
