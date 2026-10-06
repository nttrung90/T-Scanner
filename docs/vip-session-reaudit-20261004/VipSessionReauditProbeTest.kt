package com.tscanner.app

import android.app.Activity
import androidx.arch.core.executor.ArchTaskExecutor
import androidx.arch.core.executor.TaskExecutor
import com.tscanner.app.data.model.UserProfile
import com.tscanner.app.data.repository.DocumentRepo
import com.tscanner.app.utils.*
import org.junit.*
import org.junit.Assert.*
import org.junit.rules.TemporaryFolder
import java.util.Base64

/** Independent probes of production boundaries; no Google/Play calls. */
class VipSessionReauditProbeTest {
    @get:Rule val folder = TemporaryFolder()
    private lateinit var context: BillingTestContext
    private fun jwt(owner: String) = "e30." + Base64.getUrlEncoder().withoutPadding()
        .encodeToString("""{"sub":"$owner","exp":4102444800}""".toByteArray()) + ".synthetic"
    private fun account(owner: String) = GoogleSignInAccountData(
        id = owner, email = "$owner@example.com", displayName = owner,
        givenName = null, familyName = null, photoUrl = null, idToken = jwt(owner))
    private fun profile(owner: String) = UserProfile(id = owner, email = "$owner@example.com",
        displayName = owner, idToken = jwt(owner))

    @Before fun setup() {
        ArchTaskExecutor.getInstance().setDelegate(object : TaskExecutor() {
            override fun executeOnDiskIO(runnable: Runnable) = runnable.run()
            override fun postToMainThread(runnable: Runnable) = runnable.run()
            override fun isMainThread() = true
        })
        context = BillingTestContext(folder.root)
        AppAuthManager.resetForTesting()
        DocumentRepo.resetInstanceForTesting()
        AppAuthManager.postLoginHook = { _, _ -> }
    }
    @After fun cleanup() {
        AppAuthManager.resetForTesting()
        DocumentRepo.resetInstanceForTesting()
        ArchTaskExecutor.getInstance().setDelegate(null)
    }
    private fun guestContinuation(action: VipContinuationAction) {
        val handler = VipLoginContinuationHandler()
        val before = AppAuthManager.getSessionGeneration()
        handler.requestContinuation(action, before)
        val attempt = AppAuthManager.createSignInAttemptForTesting()
        assertNotNull(AppAuthManager.commitSignedInAccount(context, attempt, account("A")))
        assertNotEquals(before, AppAuthManager.getSessionGeneration())
        var dispatched: VipContinuationAction? = null
        // Same calls as MoreFragment: request before login, consume with post-commit generation.
        handler.onSignInSuccessWithAction(AppAuthManager.getSessionGeneration()) { a -> dispatched = a }
        assertEquals("Valid guest login must resume its own requested action", action, dispatched)
    }
    @Test fun P01_guestUpgradeSurvivesSuccessfulLoginGenerationChange() = guestContinuation(VipContinuationAction.UPGRADE)
    @Test fun P02_guestRestoreSurvivesSuccessfulLoginGenerationChange() = guestContinuation(VipContinuationAction.RESTORE)

    @Test fun C01_sameOwnerReauthContinuesOnce() {
        AppAuthManager.setCurrentUserForTesting(profile("A"))
        val handler = VipLoginContinuationHandler()
        handler.requestContinuation(VipContinuationAction.RESTORE, AppAuthManager.getSessionGeneration())
        val attempt = AppAuthManager.createReauthAttemptForTesting("A")
        assertNotNull(AppAuthManager.commitSignedInAccount(context, attempt, account("A")))
        var calls = 0
        repeat(2) { handler.onSignInSuccessWithAction(AppAuthManager.getSessionGeneration()) { _ -> calls++ } }
        assertEquals(1, calls)
    }
    @Test fun C02_boundReauthRejectsOtherOwner() {
        AppAuthManager.setCurrentUserForTesting(profile("A"))
        val attempt = AppAuthManager.createReauthAttemptForTesting("A")
        assertThrows(AccountMismatchException::class.java) {
            AppAuthManager.commitSignedInAccount(context, attempt, account("B"))
        }
        assertEquals("A", AppAuthManager.getCurrentUser()?.id)
    }
    @Test fun P03_unboundAttemptUsedByPdfAndIdCardAllowsAccountReplacement() {
        AppAuthManager.setCurrentUserForTesting(profile("A"))
        // These hosts omit expectedOwnerId. This exercises the resulting production commit;
        // host call-site omission is verified separately in the audit report.
        val attempt = AppAuthManager.createSignInAttemptForTesting()
        AppAuthManager.commitSignedInAccount(context, attempt, account("B"))
        assertEquals("Reauth host must preserve A when B is selected", "A", AppAuthManager.getCurrentUser()?.id)
    }

    private class Launcher : VipPurchaseLauncher {
        override val connectionState = BillingManager.ConnectionState.CONNECTED
        var launches = 0
        val queries = mutableListOf<(Boolean) -> Unit>()
        override fun isProductDetailsAvailable(productId: String) = false
        override fun launchBillingFlow(activity: Activity, productId: String, onError: ((String) -> Unit)?): Boolean {
            launches++; return true
        }
        override fun startConnection(onComplete: ((Boolean) -> Unit)?) { onComplete?.invoke(true) }
        override fun queryProducts(onComplete: ((Boolean) -> Unit)?) { queries.add(onComplete!!) }
    }
    private class Listener : VipPurchaseActionCoordinator.Listener {
        var terminals = 0
        override fun onLoading(message: String) {}
        override fun onError(message: String, canRetry: Boolean) { terminals++ }
        override fun onLaunchSuccess() { terminals++ }
        override fun onRequestSignIn() { terminals++ }
        override fun onShowSignInPrompt() { terminals++ }
    }
    @Test fun P04_duplicateProductCallbackMustNotLaunchTwice() {
        val user = profile("A")
        AppAuthManager.setCurrentUserForTesting(user)
        val launcher = Launcher()
        val coordinator = VipPurchaseActionCoordinator(launcher)
        val listener = Listener()
        coordinator.onUpgradeClicked(Activity(), user, true, listener = listener)
        launcher.queries.single().invoke(true)
        launcher.queries.single().invoke(true)
        assertEquals("One action must launch at most once", 1, launcher.launches)
        assertEquals(1, listener.terminals)
    }
    @Test fun P05_oldProductCallbackMustNotFinishNewAction() {
        val user = profile("A")
        AppAuthManager.setCurrentUserForTesting(user)
        val launcher = Launcher()
        val coordinator = VipPurchaseActionCoordinator(launcher)
        coordinator.onUpgradeClicked(Activity(), user, true, listener = Listener())
        val old = launcher.queries.single()
        old(false)
        coordinator.onUpgradeClicked(Activity(), user, true, listener = Listener())
        assertTrue(coordinator.isActionInProgress())
        old(true)
        assertTrue("Late callback from completed action must not clear new action busy flag", coordinator.isActionInProgress())
        assertEquals(0, launcher.launches)
    }
    @Test fun P06_backend401MustRemainAnAuthFailureNotNetworkFailure() = kotlinx.coroutines.runBlocking {
        var requests = 0
        val verifier = com.tscanner.app.utils.billing.PlayPurchaseVerifier(
            backendUrl = "https://audit.invalid", tokenProvider = { jwt("A") },
            httpTransport = { _, _, _, _ ->
                requests++
                com.tscanner.app.utils.billing.VerifierHttpResponse(401, """{"message":"Authentication required"}""")
            })
        val result = verifier.verifyPurchase(com.tscanner.app.utils.billing.VerificationRequest(
            ownerAppUserId = "A", productId = BillingManager.PRODUCT_VIP_YEARLY,
            productType = "subs", purchaseToken = "synthetic-receipt"))
        assertEquals(1, requests)
        assertFalse("Backend 401 must reach auth recovery, not the network-error branch: $result",
            result is com.tscanner.app.utils.billing.VerificationResult.TransientError)
    }
}
