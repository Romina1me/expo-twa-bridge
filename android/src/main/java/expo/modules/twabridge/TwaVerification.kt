package expo.modules.twabridge

/**
 * How the module treats Digital Asset Links verification before a TWA launch.
 *
 * - [STRICT]: the launch only proceeds after every requested origin passed
 *   `handle_all_urls` verification. Failures and timeouts reject.
 * - [PERMISSIVE]: the TWA intent is launched without the pre-check gate; the
 *   browser still verifies the origin itself and decides whether to show the
 *   URL as a verified TWA or with browser UI.
 */
internal enum class VerificationMode(val value: String) {
  STRICT("strict"),
  PERMISSIVE("permissive");

  companion object {
    fun fromValue(value: String): VerificationMode? =
      entries.firstOrNull { it.value == value.lowercase() }
  }
}

internal sealed interface VerificationDecision {
  /** The origin passed verification; the launch is verified. */
  data object Verified : VerificationDecision

  /** Verification could not be confirmed; continue without claiming success. */
  data object ProceedUnverified : VerificationDecision

  /** A transient failure occurred; submit the check again. */
  data object Retry : VerificationDecision

  /** Verification permanently failed; reject with a typed error. */
  data class Reject(val code: String, val message: String) : VerificationDecision
}

/**
 * Pure verification policy so the strict/permissive rules are testable without
 * an Activity or a browser session.
 */
internal object VerificationPolicy {

  fun requiresPreLaunchVerification(mode: VerificationMode): Boolean =
    mode == VerificationMode.STRICT

  fun onResult(
    mode: VerificationMode,
    validated: Boolean,
    attempt: Int,
    maxAttempts: Int,
    failureCode: String,
    failureMessage: String
  ): VerificationDecision = when {
    validated -> VerificationDecision.Verified
    // Permissive never blocks navigation on verification; it only declines to
    // claim the launch was verified.
    mode == VerificationMode.PERMISSIVE -> VerificationDecision.ProceedUnverified
    attempt < maxAttempts -> VerificationDecision.Retry
    else -> VerificationDecision.Reject(failureCode, failureMessage)
  }

  fun onTimeout(mode: VerificationMode): VerificationDecision = when (mode) {
    VerificationMode.STRICT -> VerificationDecision.Reject(
      "ERR_TWA_VERIFICATION_TIMEOUT",
      "Digital Asset Links verification timed out."
    )
    VerificationMode.PERMISSIVE -> VerificationDecision.ProceedUnverified
  }
}
