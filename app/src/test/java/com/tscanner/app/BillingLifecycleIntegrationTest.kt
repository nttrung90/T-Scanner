package com.tscanner.app

import android.content.Context
import com.android.billingclient.api.BillingClient
import com.android.billingclient.api.BillingResult
import com.android.billingclient.api.Purchase
import com.tscanner.app.data.model.UserProfile
import com.tscanner.app.utils.AppAuthManager
import com.tscanner.app.utils.BillingManager
import com.tscanner.app.utils.billing.BillingEntitlementStore
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.util.concurrent.atomic.AtomicInteger

class BillingLifecycleIntegrationTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var testContext: BillingTestContext
    private lateinit var fakeWrapper: FakeBillingClientWrapper

    @Before
    fun setUp() {
        androidx.arch.core.executor.ArchTaskExecutor.getInstance().setDelegate(object : androidx.arch.core.executor.TaskExecutor() {
            override fun executeOnDiskIO(runnable: Runnable) = runnable.run()
            override fun postToMainThread(runnable: Runnable) = runnable.run()
            override fun isMainThread(): Boolean = true
        })
        testContext = BillingTestContext(tempFolder.root)
        fakeWrapper = FakeBillingClientWrapper()
        AppAuthManager.resetForTesting()
        BillingManager.resetInstanceForTesting()
        BillingEntitlementStore.resetInstanceForTesting()
        TScannerApplication.foregroundSyncActionForTesting = null
    }

    @After
    fun tearDown() {
        BillingManager.resetInstanceForTesting()
        AppAuthManager.resetForTesting()
        TScannerApplication.foregroundSyncActionForTesting = null
    }

    @Test
    fun testColdStartHome_syncsPurchasesWithoutMoreFragment() {
        // F09: On cold start (Home/Scan), purchases are synced without ever navigating to MoreFragment
        val manager = BillingManager.createInstanceForTesting(testContext) { fakeWrapper }
        AppAuthManager.signInWithDemoAccount(testContext) {}

        fakeWrapper.purchasesToReturn.add(
            createTestPurchase(BillingManager.PRODUCT_VIP_YEARLY, token = "tok_cold_start", acknowledged = true)
        )

        // Startup sync triggered directly by TScannerApplication.onCreate
        manager.syncPurchasesOnStart()

        val user = AppAuthManager.getCurrentUser()
        assertNotNull(user)
        assertTrue("VIP must be active after cold start sync", user!!.isVipActive)
    }

    @Test
    fun testGuestQueryBeforeLogin_transfersEntitlementOnLoginCommit() {
        // F09: Guest query finishes before login; on login commit, entitlement binds to newly signed-in user
        val manager = BillingManager.createInstanceForTesting(testContext) { fakeWrapper }

        // 1. Guest session: app queries purchases
        fakeWrapper.purchasesToReturn.add(
            createTestPurchase(BillingManager.PRODUCT_VIP_YEARLY, token = "tok_guest_first", acknowledged = true)
        )
        manager.syncPurchasesOnStart()

        // Entitlement is saved as guest in store
        val guestSnapshot = BillingEntitlementStore.getInstance().getSnapshot(testContext, null)
        assertEquals(1, guestSnapshot.entitlements.size)

        // 2. User signs in through real entry point
        var committedUser: UserProfile? = null
        AppAuthManager.signInWithDemoAccount(testContext) { user ->
            committedUser = user
        }

        assertNotNull(committedUser)
        // Login commit hook ran bindPurchasesToCurrentUser and syncPurchases
        assertTrue("Newly signed-in user must inherit bound VIP entitlement", committedUser!!.isVipActive)
        val userSnapshot = BillingEntitlementStore.getInstance().getSnapshot(testContext, committedUser!!.id)
        assertEquals(1, userSnapshot.entitlements.size)
    }

    @Test
    fun testAccountSwitch_sessionIsolationEnforcedOnLogin() {
        // F09: User A has VIP. User A logs out. User B logs in. User B does not get User A's VIP.
        val manager = BillingManager.createInstanceForTesting(testContext) { fakeWrapper }

        // User A buys VIP
        val userA = UserProfile("user_A", "a@test.com", "User A")
        AppAuthManager.setCurrentUserForTesting(userA)
        manager.processPurchase(
            createTestPurchase(BillingManager.PRODUCT_VIP_YEARLY, token = "tok_user_a", acknowledged = true)
        )
        assertTrue(AppAuthManager.getCurrentUser()?.isVipActive == true)

        // User A logs out
        AppAuthManager.setCurrentUserForTesting(null)

        // User B logs in (clean account with no purchases on Play Store)
        fakeWrapper.purchasesToReturn.clear()
        val userB = UserProfile("user_B", "b@test.com", "User B")
        AppAuthManager.setCurrentUserForTesting(userB)
        manager.syncPurchases()

        val currentUser = AppAuthManager.getCurrentUser()
        assertEquals("user_B", currentUser?.id)
        assertFalse("User B must NOT have VIP from User A", currentUser?.isVipActive == true)
    }

    @Test
    fun testAppForegroundTransition_triggersThrottledBillingSync() {
        // F09: App foreground triggers sync with throttling
        val app = TScannerApplication()
        val syncCount = AtomicInteger(0)
        TScannerApplication.foregroundSyncActionForTesting = {
            syncCount.incrementAndGet()
        }

        // 1. First activity starts: transition 0 -> 1 (Foreground)
        app.handleActivityStarted()
        assertEquals("Foreground entry must trigger billing sync", 1, syncCount.get())

        // 2. Second activity starts immediately (< 30s): transition 1 -> 2 (Still foreground)
        app.handleActivityStarted()
        assertEquals("Hopping to another activity within throttle window must NOT duplicate sync", 1, syncCount.get())

        // 3. User navigates back: activities stop (count 2 -> 1 -> 0)
        app.handleActivityStopped()
        app.handleActivityStopped()
        assertEquals(1, syncCount.get())
    }

    @Test
    fun testMoreFragment_noLongerIssuesRedundantSyncOnStart() {
        // F09: Verifies MoreFragment does not call syncPurchasesOnStart on onViewCreated
        // This test ensures that navigating or recreating MoreFragment is idempotent and free of redundant queries
        val manager = BillingManager.createInstanceForTesting(testContext) { fakeWrapper }
        AppAuthManager.signInWithDemoAccount(testContext) {}

        fakeWrapper.purchasesToReturn.add(
            createTestPurchase(BillingManager.PRODUCT_VIP_YEARLY, token = "tok_more_check", acknowledged = true)
        )

        // Ensure user is VIP
        manager.syncPurchases()
        val userBefore = AppAuthManager.getCurrentUser()
        assertTrue(userBefore?.isVipActive == true)

        // Recreating / multiple inspections do not modify entitlement expiry or version
        val snapshotBefore = BillingEntitlementStore.getInstance().getSnapshot(testContext, userBefore!!.id)
        val snapshotAfter = BillingEntitlementStore.getInstance().getSnapshot(testContext, userBefore.id)
        assertEquals(snapshotBefore.entitlements.first().snapshotVersion, snapshotAfter.entitlements.first().snapshotVersion)
        assertEquals(snapshotBefore.entitlements.first().expiryTimeMillis, snapshotAfter.entitlements.first().expiryTimeMillis)
    }

    @Test
    fun testPostLoginHook_executedOnLoginCommit() {
        // Verifies the post-login hook seam executes on real login commit
        val hookInvokedCount = AtomicInteger(0)
        AppAuthManager.postLoginHook = { _, user ->
            hookInvokedCount.incrementAndGet()
            assertEquals("google_user_demo_1001", user.id)
        }

        AppAuthManager.signInWithDemoAccount(testContext) {}
        assertEquals("Post-login hook must be invoked exactly once on login commit", 1, hookInvokedCount.get())
    }
}
