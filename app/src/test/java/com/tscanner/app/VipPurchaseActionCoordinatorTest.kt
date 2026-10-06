package com.tscanner.app

import android.app.Activity
import android.content.pm.ApplicationInfo
import com.tscanner.app.data.model.UserProfile
import com.tscanner.app.data.model.VipTier
import com.tscanner.app.ui.dialogs.VipUpgradeDialog
import com.tscanner.app.utils.AppAuthManager
import com.tscanner.app.utils.BillingManager
import com.tscanner.app.utils.VipPurchaseActionCoordinator
import com.tscanner.app.utils.VipPurchaseLauncher
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
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * Unit tests verifying Defect F01 resolution via [VipPurchaseActionCoordinator].
 *
 * Verifies invariants:
 * - Failures, disconnections, missing activities, or empty product caches NEVER grant trial VIP.
 * - Coalesces rapid multiple clicks (double-clicks).
 * - Reports clear loading / error / retry states.
 * - Guarantees exactly one terminal completion callback per action invocation.
 * - Dev trial is strictly blocked in release builds and isolated from the purchase button.
 */
class VipPurchaseActionCoordinatorTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var testContext: BillingTestContext
    private lateinit var fakeLauncher: FakeVipPurchaseLauncher
    private lateinit var coordinator: VipPurchaseActionCoordinator

    private val loggedInUser = UserProfile(
        id = "user_test_123",
        email = "tester@example.com",
        displayName = "Tester",
        idToken = "eyJhbGciOiJSUzI1NiJ9.eyJzdWIiOiJ0ZXN0ZXIiLCJleHAiOjQxMDI0NDQ4MDB9.synthetic"
    )

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
        AppAuthManager.setCurrentUserForTesting(loggedInUser)
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

    private class FakeVipPurchaseLauncher : VipPurchaseLauncher {
        override var connectionState: BillingManager.ConnectionState = BillingManager.ConnectionState.CONNECTED
        var productAvailable: Boolean = true
        var returnLaunchSuccess: Boolean = true
        var launchErrorToEmit: String? = null
        var lastLaunchedActivity: Activity? = null
        var lastLaunchedProductId: String? = null
        var launchCount = 0

        var startConnectionCount = 0
        var returnStartConnectionResult = true

        var queryProductsCount = 0
        var returnQueryProductsResult = true

        override fun isProductDetailsAvailable(productId: String): Boolean = productAvailable

        var launchAuthRequiredToEmit: Boolean = false
        var onQueryProductsHook: (() -> Unit)? = null

        override fun launchBillingFlow(
            activity: Activity,
            productId: String,
            onError: ((String) -> Unit)?
        ): Boolean {
            return launchBillingFlow(activity, productId, onAuthRequired = null, onError = onError)
        }

        override fun launchBillingFlow(
            activity: Activity,
            productId: String,
            onAuthRequired: (() -> Unit)?,
            onError: ((String) -> Unit)?
        ): Boolean {
            launchCount++
            lastLaunchedActivity = activity
            lastLaunchedProductId = productId
            if (launchAuthRequiredToEmit) {
                onAuthRequired?.invoke()
                return false
            }
            if (launchErrorToEmit != null) {
                onError?.invoke(launchErrorToEmit!!)
            }
            return returnLaunchSuccess
        }

        override fun startConnection(onComplete: ((Boolean) -> Unit)?) {
            startConnectionCount++
            if (returnStartConnectionResult) {
                connectionState = BillingManager.ConnectionState.CONNECTED
            }
            onComplete?.invoke(returnStartConnectionResult)
        }

        override fun queryProducts(onComplete: ((Boolean) -> Unit)?) {
            queryProductsCount++
            onQueryProductsHook?.invoke()
            productAvailable = returnQueryProductsResult
            onComplete?.invoke(returnQueryProductsResult)
        }
    }

    private class TestActivity(
        private val finishing: Boolean = false,
        private val destroyed: Boolean = false
    ) : Activity() {
        override fun isFinishing(): Boolean = finishing
        override fun isDestroyed(): Boolean = destroyed
    }

    @Test
    fun testUpgrade_whenDisconnected_initiatesReconnectAndDoesNotGrantVip() {
        fakeLauncher.connectionState = BillingManager.ConnectionState.DISCONNECTED
        fakeLauncher.returnStartConnectionResult = false // Reconnect fails

        val listener = RecordingListener()
        val activity = TestActivity()

        coordinator.onUpgradeClicked(
            activity = activity,
            currentUser = loggedInUser,
            hasSignInCallback = false,
            listener = listener
        )

        assertEquals("Must initiate reconnection", 1, fakeLauncher.startConnectionCount)
        assertEquals("Must report loading", 1, listener.loadingMessages.size)
        assertEquals("Must report error upon connection failure", 1, listener.errorMessages.size)
        assertTrue(listener.canRetryList.first())
        assertEquals(0, listener.launchSuccessCount)

        // INVARIANT F01: Entitlement MUST NOT be granted/mutated
        val user = AppAuthManager.getCurrentUser()
        assertNotNull(user)
        assertFalse("VIP must NOT be granted when disconnected", user!!.isVipActive)
        assertEquals(VipTier.FREE, user.tier)
    }

    @Test
    fun testUpgrade_whenConnecting_awaitsConnectionAndLaunchesWhenReady() {
        fakeLauncher.connectionState = BillingManager.ConnectionState.CONNECTING
        fakeLauncher.returnStartConnectionResult = true

        val listener = RecordingListener()
        val activity = TestActivity()

        coordinator.onUpgradeClicked(
            activity = activity,
            currentUser = loggedInUser,
            hasSignInCallback = false,
            listener = listener
        )

        assertEquals("Must await connection", 1, fakeLauncher.startConnectionCount)
        assertEquals(1, fakeLauncher.launchCount)
        assertEquals(1, listener.launchSuccessCount)
        assertEquals(0, listener.errorMessages.size)
    }

    @Test
    fun testUpgrade_whenActivityMissing_reportsErrorAndDoesNotGrantVip() {
        val listener = RecordingListener()

        coordinator.onUpgradeClicked(
            activity = null, // Missing activity!
            currentUser = loggedInUser,
            hasSignInCallback = false,
            listener = listener
        )

        assertEquals(0, fakeLauncher.launchCount)
        assertEquals("Must report error for missing activity", 1, listener.errorMessages.size)
        assertTrue(listener.errorMessages.first().contains("không khả dụng"))

        // INVARIANT F01: Entitlement MUST NOT be granted
        assertFalse("VIP must NOT be granted when activity is missing", AppAuthManager.getCurrentUser()!!.isVipActive)
    }

    @Test
    fun testUpgrade_whenActivityFinishing_reportsErrorAndDoesNotGrantVip() {
        val listener = RecordingListener()
        val finishingActivity = TestActivity(finishing = true)

        coordinator.onUpgradeClicked(
            activity = finishingActivity,
            currentUser = loggedInUser,
            hasSignInCallback = false,
            listener = listener
        )

        assertEquals(0, fakeLauncher.launchCount)
        assertEquals(1, listener.errorMessages.size)
        assertFalse(AppAuthManager.getCurrentUser()!!.isVipActive)
    }

    @Test
    fun testUpgrade_whenLaunchErrorAndReturnsFalse_emitsSingleErrorWithoutDuplicateCompletion() {
        fakeLauncher.launchErrorToEmit = "Billing service error 6"
        fakeLauncher.returnLaunchSuccess = false

        val listener = RecordingListener()
        val activity = TestActivity()

        coordinator.onUpgradeClicked(
            activity = activity,
            currentUser = loggedInUser,
            hasSignInCallback = false,
            listener = listener
        )

        assertEquals(1, fakeLauncher.launchCount)
        assertEquals("Must emit EXACTLY ONE error despite onError callback + false return", 1, listener.errorMessages.size)
        assertEquals("Billing service error 6", listener.errorMessages.first())
        assertEquals(0, listener.launchSuccessCount)

        // INVARIANT F01: Never grant VIP on launch failure
        assertFalse("VIP must NOT be granted on launch failure", AppAuthManager.getCurrentUser()!!.isVipActive)
    }

    @Test
    fun testUpgrade_whenProductNotCached_queriesProductsAndDoesNotGrantTrial() {
        fakeLauncher.productAvailable = false
        fakeLauncher.returnQueryProductsResult = false // Products failed to load

        val listener = RecordingListener()
        val activity = TestActivity()

        coordinator.onUpgradeClicked(
            activity = activity,
            currentUser = loggedInUser,
            hasSignInCallback = false,
            listener = listener
        )

        assertEquals(1, fakeLauncher.queryProductsCount)
        assertEquals(0, fakeLauncher.launchCount)
        assertEquals(1, listener.errorMessages.size)
        assertTrue(listener.errorMessages.first().contains("Sản phẩm VIP chưa sẵn sàng"))

        // INVARIANT F01: Never fall back to trial when cache is empty
        assertFalse("Empty cache must NOT grant trial VIP", AppAuthManager.getCurrentUser()!!.isVipActive)
    }

    @Test
    fun testUpgrade_whenProductNotCached_reloadsSuccessfullyThenLaunches() {
        fakeLauncher.productAvailable = false
        fakeLauncher.returnQueryProductsResult = true // Reload succeeds

        val listener = RecordingListener()
        val activity = TestActivity()

        coordinator.onUpgradeClicked(
            activity = activity,
            currentUser = loggedInUser,
            hasSignInCallback = false,
            listener = listener
        )

        assertEquals(1, fakeLauncher.queryProductsCount)
        assertEquals(1, fakeLauncher.launchCount)
        assertEquals(1, listener.launchSuccessCount)
        assertEquals(0, listener.errorMessages.size)
    }

    @Test
    fun testUpgrade_rapidDoubleClicks_processesOnlyOneAction() {
        val l1 = RecordingListener()
        val l2 = RecordingListener()
        val activity = TestActivity()

        lateinit var lockedCoordinator: VipPurchaseActionCoordinator
        var secondClickAccepted = false

        val testLauncher = object : VipPurchaseLauncher {
            override val connectionState = BillingManager.ConnectionState.CONNECTED
            override fun isProductDetailsAvailable(productId: String) = false
            override fun launchBillingFlow(activity: Activity, productId: String, onError: ((String) -> Unit)?) = true
            override fun startConnection(onComplete: ((Boolean) -> Unit)?) {}
            override fun queryProducts(onComplete: ((Boolean) -> Unit)?) {
                // While query is executing (first action in flight), second click arrives!
                lockedCoordinator.onUpgradeClicked(activity, loggedInUser, false, listener = l2)
                secondClickAccepted = (l2.loadingMessages.isNotEmpty() || l2.launchSuccessCount > 0 || l2.errorMessages.isNotEmpty())
                onComplete?.invoke(true)
            }
        }
        lockedCoordinator = VipPurchaseActionCoordinator(testLauncher)
        lockedCoordinator.onUpgradeClicked(activity, loggedInUser, false, listener = l1)

        assertFalse("Second concurrent click must be dropped while first is in flight", secondClickAccepted)
        assertEquals(1, l1.launchSuccessCount)
    }

    @Test
    fun testUpgrade_guestWithSignInCallback_requestsSignInWithoutGrantingVip() {
        AppAuthManager.setCurrentUserForTesting(null)
        val listener = RecordingListener()
        val activity = TestActivity()

        coordinator.onUpgradeClicked(
            activity = activity,
            currentUser = null, // Guest
            hasSignInCallback = true,
            listener = listener
        )

        assertEquals(0, fakeLauncher.launchCount)
        assertEquals(1, listener.requestSignInCount)
        assertEquals(0, listener.showSignInPromptCount)
        assertEquals(0, listener.errorMessages.size)
        assertNull(AppAuthManager.getCurrentUser())
    }

    @Test
    fun testUpgrade_guestWithoutSignInCallback_promptsSignInWithoutGrantingVip() {
        AppAuthManager.setCurrentUserForTesting(null)
        val listener = RecordingListener()
        val activity = TestActivity()

        coordinator.onUpgradeClicked(
            activity = activity,
            currentUser = null, // Guest
            hasSignInCallback = false,
            listener = listener
        )

        assertEquals(0, fakeLauncher.launchCount)
        assertEquals(0, listener.requestSignInCount)
        assertEquals(1, listener.showSignInPromptCount)
        assertNull(AppAuthManager.getCurrentUser())
    }

    @Test
    fun testDevTrial_blockedInNonDebuggableContext() {
        // Create context with FLAG_DEBUGGABLE cleared (Release build simulation)
        val releaseContext = object : android.content.ContextWrapper(testContext) {
            private val appInfo = ApplicationInfo().apply {
                flags = 0 // Not debuggable!
            }
            override fun getApplicationInfo(): ApplicationInfo = appInfo
        }

        val dialog = VipUpgradeDialog(releaseContext)
        val trialResult = dialog.activateDevTrialForTesting("test@example.com")

        assertFalse("Dev trial MUST return false in non-debuggable release build", trialResult)
        // Entitlement must not be granted:
        val user = AppAuthManager.getCurrentUser()
        assertNotNull(user)
        assertFalse("VIP must NOT be granted in release build via trial", user!!.isVipActive)
    }

    @Test
    fun testUpgrade_tokenExpiresDuringQueryProducts_routesToRequestSignInAndClearsBusy() {
        val listener = RecordingListener()
        val activity = TestActivity()
        val expiredJwt = "eyJhbGciOiJSUzI1NiJ9.eyJzdWIiOiJ0ZXN0ZXIiLCJleHAiOjEwMDAwMDAwMDB9.synthetic"

        fakeLauncher.productAvailable = false // triggers queryProducts
        fakeLauncher.onQueryProductsHook = {
            // Token expires while products are being queried from Google Play
            AppAuthManager.setCurrentUserForTesting(loggedInUser.copy(idToken = expiredJwt))
        }

        coordinator.onUpgradeClicked(
            activity = activity,
            currentUser = loggedInUser,
            hasSignInCallback = true,
            listener = listener
        )

        assertEquals("Must trigger onRequestSignIn when token expires during queryProducts", 1, listener.requestSignInCount)
        assertEquals("Must not launch billing flow", 0, fakeLauncher.launchCount)
        assertFalse("Coordinator must not remain busy", coordinator.isActionInProgress())
    }

    @Test
    fun testUpgrade_sessionChangesDuringQueryProducts_routesToErrorAndClearsBusy() {
        val listener = RecordingListener()
        val activity = TestActivity()

        fakeLauncher.productAvailable = false
        fakeLauncher.onQueryProductsHook = {
            // Session switches to a different user during query
            AppAuthManager.setCurrentUserForTesting(UserProfile("other_user", "other@example.com", "Other"))
        }

        coordinator.onUpgradeClicked(
            activity = activity,
            currentUser = loggedInUser,
            hasSignInCallback = true,
            listener = listener
        )

        assertEquals("Must report error on session change", 1, listener.errorMessages.size)
        assertTrue("Error message must indicate session change", listener.errorMessages.first().contains("Phiên người dùng đã thay đổi"))
        assertEquals("Must not launch billing flow", 0, fakeLauncher.launchCount)
        assertFalse("Coordinator must not remain busy", coordinator.isActionInProgress())
    }

    @Test
    fun testUpgrade_launchFlowEmitsAuthRequired_routesToRequestSignInAndClearsBusy() {
        val listener = RecordingListener()
        val activity = TestActivity()

        fakeLauncher.launchAuthRequiredToEmit = true

        coordinator.onUpgradeClicked(
            activity = activity,
            currentUser = loggedInUser,
            hasSignInCallback = true,
            listener = listener
        )

        assertEquals("Must route to onRequestSignIn on launch auth required", 1, listener.requestSignInCount)
        assertEquals("Must not report generic error when auth required was handled", 0, listener.errorMessages.size)
        assertEquals("Must record launch attempt", 1, fakeLauncher.launchCount)
        assertFalse("Coordinator must not remain busy", coordinator.isActionInProgress())
    }

    @Test
    fun testUpgrade_launchFlowEmitsAuthRequired_withoutSignInCallback_routesToShowSignInPrompt() {
        val listener = RecordingListener()
        val activity = TestActivity()

        fakeLauncher.launchAuthRequiredToEmit = true

        coordinator.onUpgradeClicked(
            activity = activity,
            currentUser = loggedInUser,
            hasSignInCallback = false,
            listener = listener
        )

        assertEquals("Must route to onShowSignInPrompt when no callback provided", 1, listener.showSignInPromptCount)
        assertEquals("Must not report generic error", 0, listener.errorMessages.size)
        assertFalse("Coordinator must not remain busy", coordinator.isActionInProgress())
    }

    @Test
    fun testUpgrade_duplicateProductCallback_mustNotLaunchTwiceAndTerminalExactlyOnce() {
        val manualQueries = mutableListOf<(Boolean) -> Unit>()
        var launches = 0
        val customLauncher = object : VipPurchaseLauncher {
            override val connectionState = BillingManager.ConnectionState.CONNECTED
            override fun isProductDetailsAvailable(productId: String) = false
            override fun launchBillingFlow(activity: Activity, productId: String, onError: ((String) -> Unit)?): Boolean {
                launches++
                return true
            }
            override fun startConnection(onComplete: ((Boolean) -> Unit)?) { onComplete?.invoke(true) }
            override fun queryProducts(onComplete: ((Boolean) -> Unit)?) {
                if (onComplete != null) manualQueries.add(onComplete)
            }
        }
        val customCoordinator = VipPurchaseActionCoordinator(customLauncher)
        val listener = RecordingListener()
        val activity = TestActivity()

        customCoordinator.onUpgradeClicked(activity, loggedInUser, true, listener = listener)
        assertEquals(1, manualQueries.size)
        // Fire product query callback twice
        manualQueries.single().invoke(true)
        manualQueries.single().invoke(true)

        assertEquals("Must launch billing flow exactly once", 1, launches)
        assertEquals("Must emit launch success exactly once", 1, listener.launchSuccessCount)
        assertEquals("Must not emit error", 0, listener.errorMessages.size)
        assertFalse("Coordinator must not remain busy", customCoordinator.isActionInProgress())
    }

    @Test
    fun testUpgrade_lateProductCallbackFromOldAction_mustNotClearNewActionBusyOrLaunch() {
        val manualQueries = mutableListOf<(Boolean) -> Unit>()
        var launches = 0
        val customLauncher = object : VipPurchaseLauncher {
            override val connectionState = BillingManager.ConnectionState.CONNECTED
            override fun isProductDetailsAvailable(productId: String) = false
            override fun launchBillingFlow(activity: Activity, productId: String, onError: ((String) -> Unit)?): Boolean {
                launches++
                return true
            }
            override fun startConnection(onComplete: ((Boolean) -> Unit)?) { onComplete?.invoke(true) }
            override fun queryProducts(onComplete: ((Boolean) -> Unit)?) {
                if (onComplete != null) manualQueries.add(onComplete)
            }
        }
        val customCoordinator = VipPurchaseActionCoordinator(customLauncher)
        val l1 = RecordingListener()
        val l2 = RecordingListener()
        val activity = TestActivity()

        // Action 1
        customCoordinator.onUpgradeClicked(activity, loggedInUser, true, listener = l1)
        val oldQueryCallback = manualQueries.first()
        oldQueryCallback.invoke(false) // Action 1 terminates with error
        assertEquals(1, l1.errorMessages.size)
        assertFalse(customCoordinator.isActionInProgress())

        // Action 2 starts
        customCoordinator.onUpgradeClicked(activity, loggedInUser, true, listener = l2)
        assertTrue(customCoordinator.isActionInProgress())

        // Late callback from Action 1 arrives while Action 2 is in progress
        oldQueryCallback.invoke(true)
        assertTrue("Late callback from old action must not clear new action busy flag", customCoordinator.isActionInProgress())
        assertEquals("Late callback must not trigger launch", 0, launches)
    }

    @Test
    fun testUpgrade_duplicateStartConnectionCallback_executesFlowOnlyOnce() {
        val manualConnections = mutableListOf<(Boolean) -> Unit>()
        var launches = 0
        val customLauncher = object : VipPurchaseLauncher {
            override var connectionState = BillingManager.ConnectionState.DISCONNECTED
            override fun isProductDetailsAvailable(productId: String) = true
            override fun launchBillingFlow(activity: Activity, productId: String, onError: ((String) -> Unit)?): Boolean {
                launches++
                return true
            }
            override fun startConnection(onComplete: ((Boolean) -> Unit)?) {
                if (onComplete != null) manualConnections.add(onComplete)
            }
            override fun queryProducts(onComplete: ((Boolean) -> Unit)?) { onComplete?.invoke(true) }
        }
        val customCoordinator = VipPurchaseActionCoordinator(customLauncher)
        val listener = RecordingListener()
        val activity = TestActivity()

        customCoordinator.onUpgradeClicked(activity, loggedInUser, true, listener = listener)
        assertEquals(1, manualConnections.size)

        // Simulate connection established, then duplicate invocation of onComplete callback
        customLauncher.connectionState = BillingManager.ConnectionState.CONNECTED
        manualConnections.single().invoke(true)
        manualConnections.single().invoke(true)

        assertEquals("Must launch billing flow exactly once", 1, launches)
        assertEquals(1, listener.launchSuccessCount)
        assertFalse(customCoordinator.isActionInProgress())
    }

    @Test
    fun testUpgrade_lateErrorOrAuthCallbackAfterLaunchSuccess_doesNotEmitDuplicateTerminal() {
        var capturedOnError: ((String) -> Unit)? = null
        var capturedOnAuthRequired: (() -> Unit)? = null
        val customLauncher = object : VipPurchaseLauncher {
            override val connectionState = BillingManager.ConnectionState.CONNECTED
            override fun isProductDetailsAvailable(productId: String) = true
            override fun launchBillingFlow(
                activity: Activity,
                productId: String,
                onAuthRequired: (() -> Unit)?,
                onError: ((String) -> Unit)?
            ): Boolean {
                capturedOnAuthRequired = onAuthRequired
                capturedOnError = onError
                return true // Success returned synchronously
            }
            override fun launchBillingFlow(activity: Activity, productId: String, onError: ((String) -> Unit)?): Boolean =
                launchBillingFlow(activity, productId, onAuthRequired = null, onError = onError)
            override fun startConnection(onComplete: ((Boolean) -> Unit)?) { onComplete?.invoke(true) }
            override fun queryProducts(onComplete: ((Boolean) -> Unit)?) { onComplete?.invoke(true) }
        }
        val customCoordinator = VipPurchaseActionCoordinator(customLauncher)
        val listener = RecordingListener()
        val activity = TestActivity()

        customCoordinator.onUpgradeClicked(activity, loggedInUser, true, listener = listener)
        assertEquals(1, listener.launchSuccessCount)

        // Late callbacks fired after launchSuccess
        capturedOnError?.invoke("Late error")
        capturedOnAuthRequired?.invoke()

        assertEquals("Must still have exactly one launch success", 1, listener.launchSuccessCount)
        assertEquals("Late error must be suppressed by terminal lock", 0, listener.errorMessages.size)
        assertEquals("Late auth required must be suppressed by terminal lock", 0, listener.requestSignInCount)
        assertFalse(customCoordinator.isActionInProgress())
    }
}
