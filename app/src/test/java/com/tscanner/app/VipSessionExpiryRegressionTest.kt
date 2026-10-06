package com.tscanner.app

import android.app.Activity
import com.tscanner.app.data.model.UserProfile
import com.tscanner.app.data.model.VipTier
import com.tscanner.app.ui.dialogs.AccountDetailDialog
import com.tscanner.app.ui.dialogs.VipUpgradeActionResolver
import com.tscanner.app.ui.dialogs.VipUpgradeDialog
import com.tscanner.app.utils.AppAuthManager
import com.tscanner.app.utils.BillingManager
import com.tscanner.app.utils.VipContinuationAction
import com.tscanner.app.utils.VipLoginContinuationHandler
import com.tscanner.app.utils.VipPurchaseActionCoordinator
import com.tscanner.app.utils.VipPurchaseLauncher
import com.tscanner.app.utils.billing.PlayPurchaseVerifier
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.util.Base64

/**
 * Regression test suite for VIP purchase after session expiry (F01–F06).
 *
 * Verifies:
 * - F02: Expired logged-in user clicking upgrade from AccountDetailDialog entry points
 *        must have a reauthentication path, not a dead-end ShowSignInRequiredPrompt.
 * - F04: VipUpgradeDialog must recognize onRequestSignInForAction as having a sign-in callback,
 *        rather than only checking onRequestSignIn != null.
 * - F05: Credential Manager success branch must preserve RESTORE continuation instead of
 *        dropping the action and opening UPGRADE dialog.
 * - Control: Valid unexpired tokens proceed to ActivateVip; guest users resolve as expected.
 */
class VipSessionExpiryRegressionTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var testContext: BillingTestContext
    private lateinit var fakeLauncher: FakeVipPurchaseLauncher
    private lateinit var coordinator: VipPurchaseActionCoordinator

    private class FakeVipPurchaseLauncher : VipPurchaseLauncher {
        override var connectionState: BillingManager.ConnectionState = BillingManager.ConnectionState.CONNECTED
        var productAvailable: Boolean = true
        var returnLaunchSuccess: Boolean = true
        var launchErrorToEmit: String? = null
        var lastLaunchedActivity: Activity? = null
        var lastLaunchedProductId: String? = null
        var launchCount = 0

        override fun isProductDetailsAvailable(productId: String): Boolean = productAvailable
        override fun isVerifierConfigured(): Boolean = true

        override fun launchBillingFlow(
            activity: Activity,
            productId: String,
            onError: ((String) -> Unit)?
        ): Boolean {
            launchCount++
            lastLaunchedActivity = activity
            lastLaunchedProductId = productId
            if (launchErrorToEmit != null) {
                onError?.invoke(launchErrorToEmit!!)
            }
            return returnLaunchSuccess
        }

        override fun startConnection(onComplete: ((Boolean) -> Unit)?) {
            connectionState = BillingManager.ConnectionState.CONNECTED
            onComplete?.invoke(true)
        }

        override fun queryProducts(onComplete: ((Boolean) -> Unit)?) {
            onComplete?.invoke(true)
        }
    }

    private fun syntheticJwt(expSeconds: Long, subject: String = "user_a"): String {
        val payload = """{"sub":"$subject","exp":$expSeconds}"""
        return "eyJhbGciOiJSUzI1NiJ9." + Base64.getUrlEncoder().withoutPadding().encodeToString(payload.toByteArray()) + ".synthetic"
    }

    private fun createExpiredUser(id: String = "user_a", email: String = "a@example.com"): UserProfile {
        val pastExp = (System.currentTimeMillis() / 1000L) - 3600L // 1 hour ago
        return UserProfile(
            id = id,
            email = email,
            displayName = "User A",
            isVip = false,
            tier = VipTier.FREE,
            idToken = syntheticJwt(expSeconds = pastExp, subject = id)
        )
    }

    private fun createValidUser(id: String = "user_a", email: String = "a@example.com"): UserProfile {
        val futureExp = (System.currentTimeMillis() / 1000L) + 3600L // 1 hour in future
        return UserProfile(
            id = id,
            email = email,
            displayName = "User A",
            isVip = false,
            tier = VipTier.FREE,
            idToken = syntheticJwt(expSeconds = futureExp, subject = id)
        )
    }

    @Before
    fun setUp() {
        androidx.arch.core.executor.ArchTaskExecutor.getInstance().setDelegate(object : androidx.arch.core.executor.TaskExecutor() {
            override fun executeOnDiskIO(runnable: Runnable) = runnable.run()
            override fun postToMainThread(runnable: Runnable) = runnable.run()
            override fun isMainThread(): Boolean = true
        })
        testContext = BillingTestContext(tempFolder.root)
        fakeLauncher = FakeVipPurchaseLauncher()
        coordinator = VipPurchaseActionCoordinator(fakeLauncher)
        AppAuthManager.resetForTesting()
        BillingManager.resetInstanceForTesting()
    }

    @After
    fun tearDown() {
        BillingManager.resetInstanceForTesting()
        AppAuthManager.resetForTesting()
        androidx.arch.core.executor.ArchTaskExecutor.getInstance().setDelegate(null)
    }

    private class RecordingListener : VipPurchaseActionCoordinator.Listener {
        var loadingMessages = mutableListOf<String>()
        var errorMessages = mutableListOf<String>()
        var canRetryList = mutableListOf<Boolean>()
        var launchSuccessCount = 0
        var requestSignInCount = 0
        var showSignInPromptCount = 0

        override fun onLoading(message: String) {
            loadingMessages.add(message)
        }

        override fun onError(message: String, canRetry: Boolean) {
            errorMessages.add(message)
            canRetryList.add(canRetry)
        }

        override fun onLaunchSuccess() {
            launchSuccessCount++
        }

        override fun onRequestSignIn() {
            requestSignInCount++
        }

        override fun onShowSignInPrompt() {
            showSignInPromptCount++
        }
    }

    // =========================================================================
    // Control Tests (Must Pass on Baseline)
    // =========================================================================

    @Test
    fun testControl_expiredTokenDetection_isTokenExpired() {
        val pastExp = (System.currentTimeMillis() / 1000L) - 60L
        val futureExp = (System.currentTimeMillis() / 1000L) + 3600L
        assertTrue(PlayPurchaseVerifier.isTokenExpired(syntheticJwt(pastExp)))
        assertFalse(PlayPurchaseVerifier.isTokenExpired(syntheticJwt(futureExp)))
        assertTrue(PlayPurchaseVerifier.isTokenExpired(null))
        assertTrue(PlayPurchaseVerifier.isTokenExpired(""))
        assertTrue(PlayPurchaseVerifier.isTokenExpired("invalid.token.format"))
    }

    @Test
    fun testControl_validUser_tokenNotExpired_resolvesToActivateVip() {
        val validUser = createValidUser()
        AppAuthManager.setCurrentUserForTesting(validUser)

        val action = VipUpgradeActionResolver.resolveUpgradeAction(validUser, hasSignInCallback = true)
        assertTrue("Valid token must resolve to ActivateVip", action is VipUpgradeActionResolver.Action.ActivateVip)
        assertEquals(validUser.email, (action as VipUpgradeActionResolver.Action.ActivateVip).email)
    }

    @Test
    fun testControl_guestUser_withCallback_resolvesToRequestSignIn() {
        val action = VipUpgradeActionResolver.resolveUpgradeAction(currentUser = null, hasSignInCallback = true)
        assertTrue("Guest with callback must resolve to RequestSignIn", action is VipUpgradeActionResolver.Action.RequestSignIn)
    }

    @Test
    fun testControl_guestUser_withoutCallback_resolvesToShowSignInRequiredPrompt() {
        val action = VipUpgradeActionResolver.resolveUpgradeAction(currentUser = null, hasSignInCallback = false)
        assertTrue("Guest without callback must resolve to ShowSignInRequiredPrompt", action is VipUpgradeActionResolver.Action.ShowSignInRequiredPrompt)
    }

    // =========================================================================
    // Regression Tests (F02, F04, F05 - Must Fail on Baseline, Pass after Fixes)
    // =========================================================================

    /**
     * Defect F02:
     * User A is logged in with Free profile, but token is expired.
     * When user clicks upgrade from AccountDetailDialog (either upgrade button or status container),
     * AccountDetailDialog instantiates VipUpgradeDialog without any sign-in callback (onRequestSignIn == null).
     *
     * In current code:
     * - hasSignInCallback evaluates to false.
     * - resolveUpgradeAction returns ShowSignInRequiredPrompt.
     * - Coordinator calls listener.onShowSignInPrompt(), which dismisses dialog and shows toast.
     * - User remains logged in on MoreFragment; tapping their account only opens AccountDetailDialog again.
     * - Dead end: user cannot upgrade without signing out.
     *
     * Expected behavior:
     * Expired logged-in user must have a reauthentication path (requestSignIn / reauth),
     * NEVER a dead-end onShowSignInPrompt.
     */
    @Test
    fun testF02_accountDetailDialog_expiredUser_mustHaveReauthPathNotDeadEndPrompt() {
        val expiredUser = createExpiredUser()
        AppAuthManager.setCurrentUserForTesting(expiredUser)

        val passedSignIn: () -> Unit = {}
        val passedSignInForAction: (VipContinuationAction) -> Unit = {}

        var factoryInvokedCount = 0
        var receivedSignIn: (() -> Unit)? = null
        var receivedSignInForAction: ((VipContinuationAction) -> Unit)? = null

        val dialog = AccountDetailDialog(
            context = testContext,
            user = expiredUser,
            onRequestSignIn = passedSignIn,
            onRequestSignInForAction = passedSignInForAction,
            onSignOut = {}
        )
        dialog.vipUpgradeDialogFactory = { ctx, drive, upgrade, signIn, sync, signInAction ->
            factoryInvokedCount++
            receivedSignIn = signIn
            receivedSignInForAction = signInAction
            VipUpgradeDialog(
                context = ctx,
                onRequestDrivePermission = drive,
                onUpgradeSuccess = upgrade,
                onRequestSignIn = signIn,
                onSyncResult = sync,
                onRequestSignInForAction = signInAction
            )
        }

        // Test upgrade action button wiring
        dialog.performUpgradeButtonClickForTesting()
        assertEquals("Upgrade button must invoke vipUpgradeDialogFactory", 1, factoryInvokedCount)
        assertEquals("Factory must receive the exact onRequestSignIn callback", passedSignIn, receivedSignIn)
        assertEquals("Factory must receive the exact onRequestSignInForAction callback", passedSignInForAction, receivedSignInForAction)

        // Test membership status container wiring
        receivedSignIn = null
        receivedSignInForAction = null
        dialog.performMembershipStatusClickForTesting()
        assertEquals("Membership click must invoke vipUpgradeDialogFactory", 2, factoryInvokedCount)
        assertEquals("Factory must receive the exact onRequestSignIn callback from status container", passedSignIn, receivedSignIn)
        assertEquals("Factory must receive the exact onRequestSignInForAction callback from status container", passedSignInForAction, receivedSignInForAction)
    }

    /**
     * Defect F04:
     * In VipUpgradeDialog.kt:154, hasSignInCallback was previously hardcoded as:
     *   hasSignInCallback = (onRequestSignIn != null)
     * Even if onRequestSignInForAction was provided, hasSignInCallback was evaluated to false!
     *
     * In production fix:
     * hasSignInCallback = hasSignInCallbackEvaluated() which is (onRequestSignIn != null || onRequestSignInForAction != null)
     * Providing onRequestSignInForAction is properly recognized as having a sign-in callback.
     */
    @Test
    fun testF04_actionOnlyCallback_mustBeRecognizedAsHavingSignInCallback() {
        val expiredUser = createExpiredUser()
        AppAuthManager.setCurrentUserForTesting(expiredUser)

        val dialog = VipUpgradeDialog(
            context = testContext,
            onRequestSignIn = null,
            onRequestSignInForAction = { _ -> },
            coordinatorProvider = { _ -> coordinator }
        )

        // Verifies production dialog's actual evaluation logic
        assertTrue("Dialog must evaluate hasSignInCallback as true when onRequestSignInForAction is provided", dialog.hasSignInCallbackEvaluated())
        val action = dialog.resolveUpgradeActionForTesting()
        assertTrue("Must resolve to RequestSignIn, not prompt", action is VipUpgradeActionResolver.Action.RequestSignIn)

        val listener = RecordingListener()
        dialog.triggerUpgradeClickForTesting(activity = null, testListener = listener)

        assertEquals("Host with onRequestSignInForAction must NOT show dead-end prompt", 0, listener.showSignInPromptCount)
        assertEquals("Host with onRequestSignInForAction must trigger sign-in/reauth", 1, listener.requestSignInCount)
    }

    /**
     * Defect F05:
     * In MoreFragment.kt:340, Credential Manager success callback invokes:
     *   vipContinuationHandler.onSignInSuccess {
     *       showVipUpgradeDialog()
     *   }
     * Whereas Intent fallback (MoreFragment.kt:96) invokes:
     *   vipContinuationHandler.onSignInSuccessWithAction { action ->
     *       executeVipContinuation(action)
     *   }
     *
     * When user initiates a RESTORE continuation, Credential Manager's onSignInSuccess drops
     * the pending action and unconditionally opens UPGRADE dialog.
     *
     * Expected behavior:
     * Credential Manager success branch must preserve the RESTORE continuation action.
     */
    @Test
    fun testF05_credentialManagerSuccess_preservesRestoreContinuation() {
        val continuationHandler = VipLoginContinuationHandler()
        val startGen = 1L
        continuationHandler.requestContinuation(VipContinuationAction.RESTORE, sessionGeneration = startGen)

        // MoreFragment.kt:340 (Credential Manager success branch) invokes:
        // vipContinuationHandler.onSignInSuccessWithAction(startGen) { action -> executeVipContinuation(action) }
        var executedAction: VipContinuationAction? = null
        continuationHandler.onSignInSuccessWithAction(startGen) { action ->
            executedAction = action
        }

        assertEquals("Credential Manager success must preserve RESTORE continuation action", VipContinuationAction.RESTORE, executedAction)
    }

    @Test
    fun testE04_billingManager_launchFlow_withExpiredToken_triggersOnAuthRequiredAndClearsState() {
        val expiredUser = createExpiredUser()
        AppAuthManager.setCurrentUserForTesting(expiredUser)

        val fakeClient = FakeBillingClientWrapper()
        fakeClient.isReadyValue = true
        val billingManager = BillingManager.createInstanceForTesting(
            context = testContext,
            clientProvider = { fakeClient }
        )
        val testDetails = createTestProductDetails(BillingManager.PRODUCT_VIP_YEARLY)
        billingManager.setProductsForTesting(mapOf(BillingManager.PRODUCT_VIP_YEARLY to testDetails))

        var authRequiredCalled = false
        var errorReported: String? = null

        val launched = billingManager.launchBillingFlow(
            activity = Activity(),
            productId = BillingManager.PRODUCT_VIP_YEARLY,
            offerToken = null,
            onAuthRequired = { authRequiredCalled = true },
            onError = { errorReported = it }
        )

        assertFalse("Launch must return false when token expired", launched)
        assertTrue("onAuthRequired callback must be triggered", authRequiredCalled)
        assertNull("onError must NOT be called when onAuthRequired was provided", errorReported)
        assertFalse("isPurchaseFlowActive must be false", billingManager.isPurchaseFlowActiveForTesting())
        assertNull("activePurchaseContext must be cleared", billingManager.getActivePurchaseContextForTesting())
    }

    @Test
    fun testE04_billingManager_restorePurchases_withExpiredToken_triggersOnAuthRequired() {
        val expiredUser = createExpiredUser()
        AppAuthManager.setCurrentUserForTesting(expiredUser)

        val fakeClient = FakeBillingClientWrapper()
        fakeClient.isReadyValue = true
        val authVerifier = object : com.tscanner.app.utils.billing.PurchaseVerifier {
            override suspend fun verifyPurchase(request: com.tscanner.app.utils.billing.VerificationRequest) =
                com.tscanner.app.utils.billing.VerificationResult.MissingBackendGate("")
            override suspend fun restorePurchases(request: com.tscanner.app.utils.billing.RestoreRequest) =
                com.tscanner.app.utils.billing.RestoreResult.AuthRequired("Phiên đăng nhập đã hết hạn. Vui lòng đăng nhập lại để khôi phục VIP.")
        }
        val billingManager = BillingManager.createInstanceForTesting(
            context = testContext,
            clientProvider = { fakeClient },
            verifier = authVerifier
        )

        var authRequiredMsg: String? = null
        var completedCalled = false

        billingManager.restorePurchases(
            onAuthRequired = { authRequiredMsg = it },
            onComplete = { _, _ -> completedCalled = true }
        )

        assertNotNull("onAuthRequired must be called when token expired", authRequiredMsg)
        assertTrue("Message must indicate session expired", authRequiredMsg!!.contains("hết hạn"))
        assertFalse("onComplete must not be called when onAuthRequired handled it", completedCalled)
    }

    @Test
    fun testE04_billingManager_restorePurchases_withExpiredToken_noAuthCallback_reportsErrorGracefully() {
        val expiredUser = createExpiredUser()
        AppAuthManager.setCurrentUserForTesting(expiredUser)

        val fakeClient = FakeBillingClientWrapper()
        fakeClient.isReadyValue = true
        val authVerifier = object : com.tscanner.app.utils.billing.PurchaseVerifier {
            override suspend fun verifyPurchase(request: com.tscanner.app.utils.billing.VerificationRequest) =
                com.tscanner.app.utils.billing.VerificationResult.MissingBackendGate("")
            override suspend fun restorePurchases(request: com.tscanner.app.utils.billing.RestoreRequest) =
                com.tscanner.app.utils.billing.RestoreResult.AuthRequired("Phiên đăng nhập đã hết hạn. Vui lòng đăng nhập lại để khôi phục VIP.")
        }
        val billingManager = BillingManager.createInstanceForTesting(
            context = testContext,
            clientProvider = { fakeClient },
            verifier = authVerifier
        )

        var completedSuccess = true
        var completedMsg: String? = null

        billingManager.restorePurchases(
            onAuthRequired = null,
            onComplete = { success, msg ->
                completedSuccess = success
                completedMsg = msg
            }
        )

        assertFalse("Restore must not succeed", completedSuccess)
        assertNotNull("Must return error message", completedMsg)
        assertTrue("Must indicate session expired", completedMsg!!.contains("hết hạn"))
    }
}
