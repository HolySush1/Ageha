package app.ageha.feature.settings

import app.ageha.core.parsers.LockVerification
import app.ageha.core.parsers.ParsersInstallation
import app.ageha.core.parsers.ParsersState
import app.ageha.core.parsers.ParsersUpdateService
import app.ageha.core.parsers.UpdateOutcome
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * How a downloaded parsers build is handled once it has been checked.
 *
 * The brief asks for this as a user setting, and the three options mean genuinely different
 * things rather than being degrees of the same one.
 */
enum class UpdatePolicy(val label: String, val detail: String) {
	AUTOMATIC(
		"Install automatically",
		"New parser builds are checked, verified and activated on their own.",
	),
	NOTIFY(
		"Notify me",
		"Ageha checks and tells you, and waits for you to say yes.",
	),
	MANUAL(
		"Manual only",
		"Ageha never checks on its own. Sources will break as sites change.",
	),
}

data class ParsersUiState(
	val activeVersion: String = "",
	val bundledVersion: String = "",
	val sourceCount: Int = 0,
	val state: ParsersState = ParsersState(),
	val policy: UpdatePolicy = UpdatePolicy.NOTIFY,
	val isChecking: Boolean = false,
	val lastOutcome: UpdateOutcome? = null,
	val verification: LockVerification? = null,
) {
	val isPinned: Boolean get() = state.pinnedVersion != null
	val canRollBack: Boolean get() = state.lastKnownGoodVersion != null
	val rejectedCount: Int get() = state.rejected.size
}

/**
 * The user-facing side of Layer 1.
 *
 * The update engine was built at milestone 3 and has been running headless since; this is where it
 * becomes visible. Three of its behaviours only make sense to a user if the screen states them,
 * so this exists as much to *report* as to act:
 *
 *  - A build can be **refused**, and refusal is a designed outcome rather than an error. The screen
 *    says which build was refused and why, and that the sources the user has still work.
 *  - A **pin** means Ageha reports what is available but does not act on it. Without saying so, a
 *    pinned install looks like a broken update checker.
 *  - Verification runs before *every* load, not only after a download, because a build can be
 *    corrupted on disk between launches.
 */
class ParsersViewModel(
	private val installation: ParsersInstallation,
	private val updates: ParsersUpdateService,
	private val bundledVersion: String,
	private val activeVersion: String,
	private val sourceCount: Int,
	private val scope: CoroutineScope,
) {

	private val _state = MutableStateFlow(
		ParsersUiState(
			activeVersion = activeVersion,
			bundledVersion = bundledVersion,
			sourceCount = sourceCount,
			state = installation.read(),
			verification = installation.verify(activeVersion),
		),
	)
	val state: StateFlow<ParsersUiState> = _state.asStateFlow()

	private var checkJob: Job? = null

	fun setPolicy(policy: UpdatePolicy) {
		_state.update { it.copy(policy = policy) }
	}

	/**
	 * Look for a newer build.
	 *
	 * Never activates as a side effect, whatever the policy says. Activation is a separate call,
	 * because "check" and "install" are different promises and a checker that installs is a
	 * checker nobody trusts.
	 */
	fun checkForUpdate() {
		if (_state.value.isChecking) return
		checkJob?.cancel()
		_state.update { it.copy(isChecking = true, lastOutcome = null) }
		checkJob = scope.launch {
			val outcome = updates.checkForUpdate()
			_state.update {
				it.copy(isChecking = false, lastOutcome = outcome, state = installation.read())
			}
			if (outcome is UpdateOutcome.Ready && _state.value.policy == UpdatePolicy.AUTOMATIC) {
				activate(outcome.version)
			}
		}
	}

	/**
	 * Make a checked build the active one.
	 *
	 * Takes effect on the next launch rather than immediately. Swapping the classloader under a
	 * reader that is mid-chapter would be a much larger piece of work than it looks -- every open
	 * page url, every in-flight request and every cached client belongs to the old build -- and
	 * "restart to finish" is a trade any desktop user already understands.
	 */
	fun activate(version: String) {
		scope.launch {
			installation.activate(version)
			_state.update { it.copy(state = installation.read()) }
		}
	}

	fun rollBack() {
		scope.launch {
			installation.rollBack()
			_state.update { it.copy(state = installation.read()) }
		}
	}

	/** Fix the version. Checks still report; nothing acts. */
	fun pin(version: String?) {
		scope.launch {
			val current = installation.read()
			installation.write(current.copy(pinnedVersion = version))
			_state.update { it.copy(state = installation.read()) }
		}
	}

	fun reverify() {
		_state.update { it.copy(verification = installation.verify(it.activeVersion)) }
	}

	/** What to tell the user about the last check, in one sentence. */
	fun describe(outcome: UpdateOutcome?): String = when (outcome) {
		null -> ""
		is UpdateOutcome.UpToDate -> "Up to date; ${outcome.version} is the newest build."
		is UpdateOutcome.Pinned ->
			"Pinned to ${outcome.version}. Ageha will keep reporting new builds but will not install one."
		is UpdateOutcome.Ready ->
			"Build ${outcome.version} passed its checks and offers ${outcome.sourceCount} sources."
		is UpdateOutcome.Rejected ->
			// The wording matters. A refused build is not a failure the user caused or can fix,
			// and their sources are still working -- saying so is the difference between a notice
			// and an alarm.
			"Build ${outcome.version} was refused and not installed: ${outcome.reason}. " +
				"Your sources are unaffected and still working."
		is UpdateOutcome.PreviouslyRejected ->
			"Build ${outcome.version} was refused earlier (${outcome.reason}) and will not be retried."
		is UpdateOutcome.CheckFailed ->
			"Could not check for updates: ${outcome.reason}."
	}
}
