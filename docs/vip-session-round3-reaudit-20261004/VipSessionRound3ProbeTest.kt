package com.tscanner.app

import androidx.arch.core.executor.ArchTaskExecutor
import androidx.arch.core.executor.TaskExecutor
import com.tscanner.app.data.model.UserProfile
import com.tscanner.app.utils.*
import com.tscanner.app.utils.billing.*
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*
import java.util.Base64

class VipSessionRound3ProbeTest {
    private fun jwt(exp: Long = 4102444800L) = "e30." + Base64.getUrlEncoder().withoutPadding()
        .encodeToString("""{"sub":"A","exp":$exp}""".toByteArray()) + ".synthetic"
    @Before fun setup() {
        ArchTaskExecutor.getInstance().setDelegate(object : TaskExecutor() {
            override fun executeOnDiskIO(runnable: Runnable) = runnable.run()
            override fun postToMainThread(runnable: Runnable) = runnable.run()
            override fun isMainThread() = true
        })
        AppAuthManager.resetForTesting()
        AppAuthManager.setCurrentUserForTesting(UserProfile("A", "A@example.com", "A", idToken = jwt()))
    }
    @After fun cleanup() {
        AppAuthManager.resetForTesting()
        ArchTaskExecutor.getInstance().setDelegate(null)
    }
    private fun boundHandler(): Pair<VipLoginContinuationHandler, GoogleLoginAttempt> {
        val h = VipLoginContinuationHandler()
        h.requestContinuation(VipContinuationAction.RESTORE, AppAuthManager.getSessionGeneration())
        val attempt = AppAuthManager.createReauthAttemptForTesting("A")
        h.bindAttempt(attempt)
        return h to attempt
    }
    @Test fun P01_oldSuccessMustNotEraseNewPendingRequest() {
        val (h, attempt) = boundHandler()
        var calls = 0
        h.onSignInSuccessWithAction(AppAuthManager.getSessionGeneration(), "A", attempt.requestId - 1) { _, _ -> calls++ }
        assertEquals(0, calls)
        assertTrue("An unrelated success must be ignored, not reset the active continuation", h.isPending)
        h.onSignInSuccessWithAction(AppAuthManager.getSessionGeneration(), "A", attempt.requestId) { _, _ -> calls++ }
        assertEquals(1, calls)
    }
    @Test fun P02_boundContinuationMustRequireOriginatingAttemptAtConsumption() {
        val (h, _) = boundHandler()
        var calls = 0
        // This overload is currently used by MoreFragment's fallback success path.
        h.onSignInSuccessWithAction(AppAuthManager.getSessionGeneration()) { _, _ -> calls++ }
        assertEquals("Bound continuation must not be consumed without its originating request ID", 0, calls)
        assertTrue(h.isPending)
    }
    private fun request() = VerificationRequest("A", BillingManager.PRODUCT_VIP_YEARLY, "subs", "synthetic-receipt")
    @Test fun P03_missingCredentialMustBeAuthRequiredNotReceiptRejection() = runBlocking {
        var calls = 0
        val verifier = PlayPurchaseVerifier(backendUrl = "https://audit.invalid", tokenProvider = { null },
            httpTransport = { _, _, _, _ -> calls++; VerifierHttpResponse(401, "{}") })
        val result = verifier.verifyPurchase(request())
        assertEquals(0, calls)
        assertTrue("Missing login credential must not reject a valid receipt: $result", result is VerificationResult.AuthRequired)
    }
    @Test fun P04_expiredCredentialMustStopBeforeTransport() = runBlocking {
        var calls = 0
        val verifier = PlayPurchaseVerifier(backendUrl = "https://audit.invalid", tokenProvider = { jwt(1) },
            httpTransport = { _, _, _, _ -> calls++; VerifierHttpResponse(401, "{}") })
        val result = verifier.verifyPurchase(request())
        assertTrue(result is VerificationResult.AuthRequired)
        assertEquals("Known expired credential must trigger auth recovery before network", 0, calls)
    }
    @Test fun C01_matchingAttemptDispatchesOnce() {
        val (h, attempt) = boundHandler()
        var calls = 0
        repeat(2) { h.onSignInSuccessWithAction(AppAuthManager.getSessionGeneration(), "A", attempt.requestId) { _, _ -> calls++ } }
        assertEquals(1, calls)
    }
    @Test fun C02_wrongCancellationPreservesPending() {
        val (h, attempt) = boundHandler()
        h.onSignInCancelled(attempt.requestId - 1)
        assertTrue(h.isPending)
    }
    @Test fun C03_backend401WithFreshTokenIsTyped() = runBlocking {
        val verifier = PlayPurchaseVerifier(backendUrl = "https://audit.invalid", tokenProvider = { jwt() },
            httpTransport = { _, _, _, _ -> VerifierHttpResponse(401, "{}") })
        assertTrue(verifier.verifyPurchase(request()) is VerificationResult.AuthRequired)
    }
}
