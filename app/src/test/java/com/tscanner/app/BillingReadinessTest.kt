package com.tscanner.app

import android.app.Activity
import com.tscanner.app.data.model.UserProfile
import com.tscanner.app.utils.AppAuthManager
import com.tscanner.app.utils.BillingManager
import com.tscanner.app.utils.VipPurchaseActionCoordinator
import com.tscanner.app.utils.VipPurchaseLauncher
import com.tscanner.app.utils.billing.NoOpLocalPurchaseVerifier
import com.tscanner.app.utils.billing.PlayPurchaseVerifier
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class BillingReadinessTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var testContext: BillingTestContext

    private val loggedInUser = UserProfile(
        id = "user_ready_test",
        email = "ready@example.com",
        displayName = "Ready User",
        idToken = "eyJhbGciOiJSUzI1NiJ9.eyJzdWIiOiJyZWFkeSIsImV4cCI6NDEwMjQ0NDgwMH0.synthetic"
    )

    @Before
    fun setUp() {
        androidx.arch.core.executor.ArchTaskExecutor.getInstance().setDelegate(object : androidx.arch.core.executor.TaskExecutor() {
            override fun executeOnDiskIO(runnable: Runnable) = runnable.run()
            override fun postToMainThread(runnable: Runnable) = runnable.run()
            override fun isMainThread(): Boolean = true
        })
        testContext = BillingTestContext(tempFolder.root)
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

    private class TestActivity(
        private val finishing: Boolean = false,
        private val destroyed: Boolean = false
    ) : Activity() {
        override fun isFinishing(): Boolean = finishing
        override fun isDestroyed(): Boolean = destroyed
    }

    private class TestLauncher(
        var verifierReady: Boolean = true,
        var productAvailable: Boolean = true
    ) : VipPurchaseLauncher {
        override val connectionState: BillingManager.ConnectionState = BillingManager.ConnectionState.CONNECTED
        var launchCount = 0

        override fun isProductDetailsAvailable(productId: String): Boolean = productAvailable

        override fun isVerifierConfigured(): Boolean = verifierReady

        override fun launchBillingFlow(
            activity: Activity,
            productId: String,
            onError: ((String) -> Unit)?
        ): Boolean {
            launchCount++
            return true
        }

        override fun startConnection(onComplete: ((Boolean) -> Unit)?) {
            onComplete?.invoke(true)
        }

        override fun queryProducts(onComplete: ((Boolean) -> Unit)?) {
            onComplete?.invoke(productAvailable)
        }
    }

    @Test
    fun testBillingManager_isVerifierConfigured_falseForNoOpVerifier() {
        val fakeWrapper = FakeBillingClientWrapper()
        val mgr = BillingManager.createInstanceForTesting(testContext) { fakeWrapper }
        mgr.setPurchaseVerifierForTesting(NoOpLocalPurchaseVerifier())

        assertFalse("NoOpLocalPurchaseVerifier must report isVerifierConfigured = false", mgr.isVerifierConfigured())
    }

    @Test
    fun testBillingManager_isVerifierConfigured_falseForUnconfiguredPlayVerifier() {
        val fakeWrapper = FakeBillingClientWrapper()
        val mgr = BillingManager.createInstanceForTesting(testContext) { fakeWrapper }
        val unconfiguredVerifier = PlayPurchaseVerifier(backendUrl = null, allowLocalFallback = false)
        mgr.setPurchaseVerifierForTesting(unconfiguredVerifier)

        assertFalse("Unconfigured PlayPurchaseVerifier must report isVerifierConfigured = false", mgr.isVerifierConfigured())
    }

    @Test
    fun testBillingManager_isVerifierConfigured_trueForConfiguredPlayVerifier() {
        val fakeWrapper = FakeBillingClientWrapper()
        val mgr = BillingManager.createInstanceForTesting(testContext) { fakeWrapper }
        val configuredVerifier = PlayPurchaseVerifier(backendUrl = "https://backend.example.com", allowLocalFallback = false)
        mgr.setPurchaseVerifierForTesting(configuredVerifier)

        assertTrue("Configured PlayPurchaseVerifier must report isVerifierConfigured = true", mgr.isVerifierConfigured())
    }

    @Test
    fun testVipPurchaseActionCoordinator_blocksPurchase_whenVerifierNotConfigured() {
        val unreadyLauncher = TestLauncher(verifierReady = false)
        val coordinator = VipPurchaseActionCoordinator(unreadyLauncher)
        val listener = RecordingListener()
        val activity = TestActivity()

        coordinator.onUpgradeClicked(
            activity = activity,
            currentUser = loggedInUser,
            hasSignInCallback = true,
            listener = listener
        )

        assertEquals("Billing flow must NOT be launched when verifier is not configured", 0, unreadyLauncher.launchCount)
        assertEquals(1, listener.errorMessages.size)
        assertTrue(listener.errorMessages[0].contains("Dịch vụ xác thực thanh toán hiện chưa sẵn sàng"))
        assertFalse(listener.canRetryList[0])

        // Crucial invariant: never self-grant VIP on preflight failure
        val user = AppAuthManager.getCurrentUser()
        assertNotNull(user)
        assertFalse("User must NOT be self-granted VIP when verifier is unconfigured", user!!.isVipActive)
    }

    @Test
    fun testVipPurchaseActionCoordinator_allowsPurchase_whenVerifierConfigured() {
        val readyLauncher = TestLauncher(verifierReady = true)
        val coordinator = VipPurchaseActionCoordinator(readyLauncher)
        val listener = RecordingListener()
        val activity = TestActivity()

        coordinator.onUpgradeClicked(
            activity = activity,
            currentUser = loggedInUser,
            hasSignInCallback = true,
            listener = listener
        )

        assertEquals("Billing flow should be launched when verifier is configured", 1, readyLauncher.launchCount)
        assertEquals(0, listener.errorMessages.size)
        assertEquals(1, listener.launchSuccessCount)
    }
}
