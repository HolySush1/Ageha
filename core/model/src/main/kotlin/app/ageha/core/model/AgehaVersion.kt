package app.ageha.core.model

/**
 * What version of Ageha this is.
 *
 * Here, in the module everything else depends on, because four unrelated places need it and three
 * of them had grown their own copy: the backup archive's index, the sync protocol's
 * `X-App-Version` header, the `added_in` column that records which release first saw a source, and
 * the update check. Two constants that must agree and do not have to are a bug with a delay on it.
 *
 * ## Two numbers, not one
 *
 * [NAME] is what a person reads and what a git tag says: `0.2.0`. [CODE] is a monotonic integer,
 * and it exists because two formats Ageha does not own insist on one — the backup index's
 * `app_version` and the sync server's `X-App-Version` header are both `Int`, because the Android
 * app puts its `VERSION_CODE` in them. A dotted string cannot go in either.
 *
 * Both are bumped by hand at release, and `./gradlew :app:desktop:checkAppVersion` fails the build
 * if [NAME] drifts from the version in `build.gradle.kts`. A packaged build takes its version from
 * the git tag rather than from here (`docs/UPDATING.md`), so these agree at release time because
 * the tag and the build file move together.
 */
object AgehaVersion {

	/** Human-readable, and what a git tag says. Compared numerically by the update check. */
	const val NAME = "0.3.1"

	/**
	 * Monotonic integer, for the two wire formats that demand one.
	 *
	 * Not derived from [NAME]. Deriving it would mean inventing an encoding — `0.1.0` to `100`,
	 * say — and then being stuck with it the first time a version part exceeds whatever width was
	 * assumed. It is one number, bumped once per release, next to the one it accompanies.
	 */
	const val CODE = 4

	/** `owner/repo`, matching `app.vcs-url` in `conveyor.conf`. */
	const val REPO = "HolySush1/Ageha"
}
