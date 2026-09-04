package app.ageha.core.sync

import app.ageha.core.network.AgehaPaths
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermission
import java.nio.file.attribute.PosixFilePermissions

/**
 * Who Ageha syncs as, and where.
 *
 * The Android app keeps this in `AccountManager`, which desktop has no equivalent of. There is no
 * cross-platform system keychain reachable from a plain JVM without a native dependency per
 * platform -- the exact cost that got QuickJS rejected in favour of Rhino -- so this is a file.
 *
 * ## The password is stored, and that is a decision, not an oversight
 *
 * The protocol issues a bearer token that expires, and refreshes it by re-sending the email and
 * password (the Android app's `SyncAuthenticator` does precisely this). So the choice is:
 *
 *  - **Store the password.** A background sync recovers from an expired token on its own.
 *  - **Store only the token.** Nothing sensitive is at rest beyond a credential that expires, but
 *    every expiry interrupts the user to ask for a password again -- including a sync at startup,
 *    which is the one that should be invisible.
 *
 * Ageha stores it, matching the Android app, and [isPasswordStored] makes that visible in the UI
 * rather than leaving it to be discovered. What is *not* done is pretending otherwise: there is no
 * obfuscation here, because encrypting a file with a key sitting beside it is a decoration that
 * makes a reviewer relax and protects nobody. The file is plain JSON, and it is protected by the
 * filesystem or not at all.
 *
 * [restrict] narrows it to owner-read/write on any filesystem with POSIX permissions. On Windows
 * that call does not apply, and the file leans on `%LOCALAPPDATA%` already being restricted to the
 * user profile -- which is the same protection the cookie jar and the database already rely on.
 *
 * Anyone unwilling to have a password at rest should leave [password] unset: everything works
 * until the token expires, and then Ageha asks for it again.
 */
@Serializable
data class SyncAccount(
	/** Base url of the sync server, no trailing slash. */
	val syncUrl: String,
	val email: String,
	/** Bearer token from the last successful authentication, if any. */
	val token: String? = null,
	/** Optional. See the class comment before changing anything about this field. */
	val password: String? = null,
) {

	val isPasswordStored: Boolean get() = !password.isNullOrEmpty()
}

/**
 * Reads and writes the sync account.
 *
 * Writes go through a temporary file and a rename, as [app.ageha.core.network.AgehaPaths]'s other
 * neighbours do: a half-written account file reads as a corrupt one, and the cost of that is a
 * user who appears to have been signed out.
 */
class SyncAccountStore(
	private val file: File = File(AgehaPaths.dataDir, "sync.json"),
) {

	private val json = Json {
		prettyPrint = true
		ignoreUnknownKeys = true
		encodeDefaults = true
	}

	fun load(): SyncAccount? {
		if (!file.exists()) return null
		// A corrupt account file signs the user out rather than refusing to start. They can sign
		// in again; they cannot recover an application that will not open.
		return runCatching { json.decodeFromString<SyncAccount>(file.readText()) }.getOrNull()
	}

	fun save(account: SyncAccount) {
		file.parentFile?.mkdirs()
		val temporary = File(file.parentFile, file.name + ".tmp")
		temporary.writeText(json.encodeToString(account))
		restrict(temporary)
		if (!temporary.renameTo(file)) {
			// Windows will not rename onto an existing file. The temporary is already complete at
			// this point, so a failure here leaves the previous account file intact.
			file.delete()
			temporary.renameTo(file)
		}
		restrict(file)
	}

	/** Sign out. Removes the file entirely rather than blanking fields inside it. */
	fun clear() {
		file.delete()
	}

	/**
	 * Narrow the file to its owner where the filesystem can express that.
	 *
	 * Best-effort by design: a filesystem without POSIX permissions throws
	 * `UnsupportedOperationException` here, and on Windows -- where that is every filesystem -- the
	 * file inherits the user-profile ACL on `%LOCALAPPDATA%`, the same protection already standing
	 * between another user and the cookie jar or the library database. Failing the save over it
	 * would trade a real feature for no additional protection.
	 */
	private fun restrict(target: File) {
		runCatching {
			Files.setPosixFilePermissions(
				target.toPath(),
				PosixFilePermissions.asFileAttribute(
					setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE),
				).value(),
			)
		}
	}
}
