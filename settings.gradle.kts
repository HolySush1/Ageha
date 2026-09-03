pluginManagement {
	repositories {
		mavenCentral()
		gradlePluginPortal()
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

// Modules arrive at their milestone. Feature modules (:feature:library, :feature:explore,
// :feature:reader, ...) and :core:designsystem land at milestones 5-8; they are documented
// in docs/ARCHITECTURE.md 2 but not declared here until they contain something.
include(
	":core:model",
	":core:network",
	":core:js",
	":core:jvmcontext",
	":core:parsers",
	":app:cli",
)
