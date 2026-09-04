package app.ageha.feature.settings

import app.ageha.core.sync.SyncAccount
import app.ageha.core.sync.SyncAccountStore
import app.ageha.core.sync.SyncApi
import app.ageha.core.sync.SyncApiException
import app.ageha.core.sync.SyncEngine
import app.ageha.core.sync.SyncOutcome
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * The sync screen's state.
 *
 * Deliberately holds no password after sign-in has happened: what is on screen is whether an
 * account exists, not what it is. The stored copy lives in [SyncAccountStore] and the UI says so
 * rather than showing it back.
 */
data class SyncUiState(
	val syncUrl: String = "",
	val email: String = "",
	val isSignedIn: Boolean = false,
	val isPasswordStored: Boolean = false,
	val isBusy: Boolean = false,
	/** Last thing that happened, for the user to read. Cleared when a new action starts. */
	val message: String? = null,
	val isError: Boolean = false,
	val syncOnStart: Boolean = true,
)

/**
 * Sign-in, sign-out and running a sync.
 *
 * Sign-in **authenticates before saving**, rather than storing whatever was typed and discovering
 * at the next sync that it was wrong. The address, the account and the password are three separate
 * ways to get this wrong, and the moment to find out is while the user is still looking at the
 * form that produced them.
 */
class SyncViewModel(
	private val api: SyncApi,
	private val engine: SyncEngine,
	private val accounts: SyncAccountStore,
	private val scope: CoroutineScope,
	syncOnStart: Boolean = true,
	private val onSyncOnStartChanged: (Boolean) -> Unit = {},
) {

	private val _state = MutableStateFlow(SyncUiState(syncOnStart = syncOnStart))
	val state: StateFlow<SyncUiState> = _state.asStateFlow()

	init {
		reload()
	}

	fun reload() {
		val account = accounts.load()
		_state.update {
			it.copy(
				syncUrl = account?.syncUrl ?: it.syncUrl,
				email = account?.email ?: it.email,
				isSignedIn = account != null,
				isPasswordStored = account?.isPasswordStored == true,
			)
		}
	}

	fun setSyncOnStart(enabled: Boolean) {
		_state.update { it.copy(syncOnStart = enabled) }
		onSyncOnStartChanged(enabled)
	}

	fun signIn(syncUrl: String, email: String, password: String, rememberPassword: Boolean) {
		if (syncUrl.isBlank() || email.isBlank() || password.isBlank()) {
			_state.update { it.copy(message = "Address, email and password are all needed.", isError = true) }
			return
		}
		val normalised = normaliseUrl(syncUrl)
		_state.update { it.copy(isBusy = true, message = null, isError = false) }
		scope.launch {
			try {
				val token = api.authenticate(normalised, email, password)
				accounts.save(
					SyncAccount(
						syncUrl = normalised,
						email = email,
						token = token,
						// Not storing it is a supported choice, and it costs a prompt whenever the
						// token expires rather than breaking anything.
						password = password.takeIf { rememberPassword },
					),
				)
				_state.update {
					it.copy(
						syncUrl = normalised,
						email = email,
						isSignedIn = true,
						isPasswordStored = rememberPassword,
						isBusy = false,
						message = "Signed in.",
						isError = false,
					)
				}
			} catch (e: SyncApiException) {
				_state.update { it.copy(isBusy = false, message = e.message, isError = true) }
			} catch (e: java.io.IOException) {
				_state.update {
					it.copy(
						isBusy = false,
						message = "Could not reach " + normalised + ": " + e.message,
						isError = true,
					)
				}
			}
		}
	}

	fun signOut() {
		accounts.clear()
		_state.update {
			it.copy(isSignedIn = false, isPasswordStored = false, message = "Signed out.", isError = false)
		}
	}

	fun syncNow() {
		_state.update { it.copy(isBusy = true, message = null, isError = false) }
		scope.launch {
			val outcome = engine.sync()
			_state.update {
				it.copy(
					isBusy = false,
					message = outcome.describe(),
					isError = outcome is SyncOutcome.Failed,
					isPasswordStored = accounts.load()?.isPasswordStored == true,
				)
			}
		}
	}

	/**
	 * Accept `sync.example.org` as readily as `https://sync.example.org`.
	 *
	 * The Android app assumes `http://` for a bare host. Ageha assumes `https://` instead: this
	 * request carries a password, and defaulting a credential to cleartext because the user
	 * omitted five characters is not a default worth matching. Anyone genuinely running the server
	 * over plain HTTP on a home network can type the scheme.
	 */
	private fun normaliseUrl(raw: String): String {
		val trimmed = raw.trim().trimEnd('/')
		return if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) {
			trimmed
		} else {
			"https://" + trimmed
		}
	}
}
