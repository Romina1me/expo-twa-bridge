package expo.modules.twabridge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TwaVerificationTest {

  @Test
  fun `verification mode parses known values only`() {
    assertEquals(VerificationMode.STRICT, VerificationMode.fromValue("strict"))
    assertEquals(VerificationMode.PERMISSIVE, VerificationMode.fromValue("PERMISSIVE"))
    assertNull(VerificationMode.fromValue("webview"))
  }

  @Test
  fun `only strict requires the pre launch verification gate`() {
    assertTrue(VerificationPolicy.requiresPreLaunchVerification(VerificationMode.STRICT))
    assertFalse(VerificationPolicy.requiresPreLaunchVerification(VerificationMode.PERMISSIVE))
  }

  @Test
  fun `successful verification is verified in both modes`() {
    assertEquals(
      VerificationDecision.Verified,
      VerificationPolicy.onResult(
        mode = VerificationMode.STRICT,
        validated = true,
        attempt = 1,
        maxAttempts = 3,
        failureCode = "ERR_TWA_VERIFICATION_FAILED",
        failureMessage = "failed"
      )
    )
    assertEquals(
      VerificationDecision.Verified,
      VerificationPolicy.onResult(
        mode = VerificationMode.PERMISSIVE,
        validated = true,
        attempt = 1,
        maxAttempts = 3,
        failureCode = "ERR_TWA_VERIFICATION_FAILED",
        failureMessage = "failed"
      )
    )
  }

  @Test
  fun `strict retries a temporary verification failure`() {
    assertEquals(
      VerificationDecision.Retry,
      VerificationPolicy.onResult(
        mode = VerificationMode.STRICT,
        validated = false,
        attempt = 1,
        maxAttempts = 3,
        failureCode = "ERR_TWA_VERIFICATION_FAILED",
        failureMessage = "failed"
      )
    )
  }

  @Test
  fun `strict makes three attempts before rejecting`() {
    val decisions = (1..3).map { attempt ->
      VerificationPolicy.onResult(
        mode = VerificationMode.STRICT,
        validated = false,
        attempt = attempt,
        maxAttempts = 3,
        failureCode = "ERR_TWA_VERIFICATION_FAILED",
        failureMessage = "failed"
      )
    }

    assertEquals(
      listOf(VerificationDecision.Retry, VerificationDecision.Retry),
      decisions.take(2)
    )
    assertTrue(decisions[2] is VerificationDecision.Reject)
  }

  @Test
  fun `strict retries a per attempt verification timeout`() {
    assertEquals(
      VerificationDecision.Retry,
      VerificationPolicy.onResult(
        mode = VerificationMode.STRICT,
        validated = false,
        attempt = 1,
        maxAttempts = 3,
        failureCode = "ERR_TWA_VERIFICATION_TIMEOUT",
        failureMessage = "timeout"
      )
    )
  }

  @Test
  fun `strict rejects a permanent verification failure`() {
    val decision = VerificationPolicy.onResult(
      mode = VerificationMode.STRICT,
      validated = false,
      attempt = 3,
      maxAttempts = 3,
      failureCode = "ERR_TWA_VERIFICATION_FAILED",
      failureMessage = "assetlinks missing"
    )

    assertTrue(decision is VerificationDecision.Reject)
    decision as VerificationDecision.Reject
    assertEquals("ERR_TWA_VERIFICATION_FAILED", decision.code)
    assertEquals("assetlinks missing", decision.message)
  }

  @Test
  fun `permissive proceeds unverified on a temporary or permanent failure`() {
    assertEquals(
      VerificationDecision.ProceedUnverified,
      VerificationPolicy.onResult(
        mode = VerificationMode.PERMISSIVE,
        validated = false,
        attempt = 1,
        maxAttempts = 3,
        failureCode = "ERR_TWA_VERIFICATION_FAILED",
        failureMessage = "failed"
      )
    )
    assertEquals(
      VerificationDecision.ProceedUnverified,
      VerificationPolicy.onResult(
        mode = VerificationMode.PERMISSIVE,
        validated = false,
        attempt = 3,
        maxAttempts = 3,
        failureCode = "ERR_TWA_VERIFICATION_FAILED",
        failureMessage = "failed"
      )
    )
  }

  @Test
  fun `strict rejects a verification timeout`() {
    val decision = VerificationPolicy.onTimeout(VerificationMode.STRICT)

    assertTrue(decision is VerificationDecision.Reject)
    decision as VerificationDecision.Reject
    assertEquals("ERR_TWA_VERIFICATION_TIMEOUT", decision.code)
  }

  @Test
  fun `permissive does not reject a verification timeout`() {
    assertEquals(
      VerificationDecision.ProceedUnverified,
      VerificationPolicy.onTimeout(VerificationMode.PERMISSIVE)
    )
  }
}
