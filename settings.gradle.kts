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

// Modules arrive at their milestone. :feature:tracking is the last one outstanding -- it needs
// OAuth clients registered with Shikimori, AniList, MyAnimeList and Kitsu, which is the project
// owner's to do; see docs/ARCHITECTURE.md 9. :feature:updates was folded into :feature:settings,
// because the update engine lives in :core:parsers and needed a screen rather than a module.
include(
	":core:model",
	":core:network",
	":core:js",
	":core:jvmcontext",
	":core:backup",
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
