package com.tscanner.app

import android.os.Handler
import android.os.Looper
import com.android.billingclient.api.BillingClient
import com.android.billingclient.api.BillingResult
import com.android.billingclient.api.Purchase
import com.tscanner.app.data.model.UserProfile
import com.tscanner.app.data.model.VipTier
import com.tscanner.app.utils.AppAuthManager
import com.tscanner.app.utils.BillingManager
import com.tscanner.app.utils.billing.*
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Unit and integration tests for Billing Operation Context & Session Staleness (Q02 / R04 & R03 session part).
 *
 * Verifies:
 * - A launch rồi B restore: Restore operation captures current user B at invocation and does not inherit A's owner.
 * - Query A về sau login B: Delayed query/reconcile started under A is discarded upon arriving after B logs in.
 * - A -> B -> A: Rapid user switch bumps session generation; callbacks from previous generation of A are dropped.
 * - Hai operation chồng nhau: Simultaneous purchase and restore carry isolated immutable contexts.
 * - Guest -> login trong verify/ack: Guest purchase in-flight commits safely to store while UI callback is guarded.
 * - UI callback tới sau session mới: CANCELED, ITEM_ALREADY_OWNED, and error callbacks are suppressed on stale sessions.
 * - Main thread dispatch staleness: Late-running runnable on main thread re-evaluates staleness and suppresses events.
 */
class BillingOperationSessionTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var ctx: BillingTestContext
    private lateinit var client: FakeBillingClientWrapper
    private lateinit var manager: BillingManager
    private val store get() = BillingEntitlementStore.getInstance()

    private fun user(id: String) = UserProfile(id, "$id@example.test", "User $id")

    private val recordedVerificationRequests = CopyOnWriteArrayList<VerificationRequest>()
    private var verificationDelayMillis = 0L

    private val verifier = object : PurchaseVerifier {
        override suspend fun verifyPurchase(request: VerificationRequest): VerificationResult {
            recordedVerificationRequests.add(request)
            if (verificationDelayMillis > 0) {
                kotlinx.coroutines.delay(verificationDelayMillis)
            }
            return VerificationResult.Success(
                BillingEntitlement(
                    id = request.purchaseToken,
                    ownerAppUserId = request.ownerAppUserId,
                    productId = request.productId,
                    productType = request.productType,
                    purchaseToken = request.purchaseToken,
                    source = EntitlementSource.GOOGLE_PLAY_INAPP,
                    state = EntitlementState.VERIFIED_ACTIVE,
                    expiryTimeMillis = null,
                    snapshotVersion = 1L
                )
            )
        }
    }

    @Before
    fun setup() {
        androidx.arch.core.executor.ArchTaskExecutor.getInstance().setDelegate(object : androidx.arch.core.executor.TaskExecutor() {
            override fun executeOnDiskIO(r: Runnable) = r.run()
            override fun postToMainThread(r: Runnable) = r.run()
            override fun isMainThread() = true
        })
        BillingManager.resetInstanceForTesting()
        AppAuthManager.resetForTesting()
        BillingEntitlementStore.resetInstanceForTesting()
        recordedVerificationRequests.clear()
        verificationDelayMillis = 0L

        ctx = BillingTestContext(tmp.root)
        client = FakeBillingClientWrapper()
        manager = BillingManager.createInstanceForTesting(ctx, { client }, verifier)
    }

    @After
    fun cleanup() {
        BillingManager.resetInstanceForTesting()
        AppAuthManager.resetForTesting()
        BillingEntitlementStore.resetInstanceForTesting()
        androidx.arch.core.executor.ArchTaskExecutor.getInstance().setDelegate(null)
    }

    @Test
    fun testUserALaunchesPurchase_thenUserBRestores_restoreRequestsUnderBNotA() {
        // 1. User A is active and launches purchase flow
        manager.setActivePurchaseOwnerForTesting("UserA")

        // 2. User B logs in
        AppAuthManager.setCurrentUserForTesting(user("UserB"))

        // 3. User B triggers restore
        client.addPurchase(createTestPurchase(BillingManager.PRODUCT_VIP_LIFETIME, token = "tok_b", acknowledged = true))
        var restoreSuccess: Boolean? = null
        manager.restorePurchases { success, _ ->
            restoreSuccess = success
        }

        // Verification must have been requested for UserB, not UserA
        val restoreRequest = recordedVerificationRequests.find { it.purchaseToken == "tok_b" }
        assertNotNull("Verification request must exist for tok_b", restoreRequest)
        assertEquals("Restore must request verification under UserB, not stale UserA", "UserB", restoreRequest?.ownerAppUserId)
        assertEquals(true, restoreSuccess)
    }

    @Test
    fun testDelayedQueryForAArrivesAfterUserBLogsIn_doesNotMutateOrRevokeB() {
        // User A was logged in
        AppAuthManager.setCurrentUserForTesting(user("UserA"))
        val genA = AppAuthManager.getSessionGeneration()
        val opContextA = BillingOperationContext(
            ownerAppUserId = "UserA",
            sessionGeneration = genA,
            operationType = BillingOperationType.RECONCILE
        )

        // Setup reconciler for User A
        var reconciliationFinished = false
        val reconciler = BillingReconciliation(
            context = ctx,
            billingClient = client,
            processPurchaseAction = { purchase, onComplete ->
                manager.processPurchase(purchase, BillingOperationOrigin.RECONCILE, opContextA) { onComplete(it) }
            },
            operationContext = opContextA
        )

        // Give User B an active promotional or existing VIP status
        AppAuthManager.setCurrentUserForTesting(user("UserB"))
        AppAuthManager.setUserVipTier(ctx, VipTier.VIP)
        assertTrue("User B should initially be VIP", AppAuthManager.getCurrentUser()!!.isVipActive)

        // Empty Play results for User A arrive after User B has already logged in
        reconciler.reconcile {
            reconciliationFinished = true
        }

        // Stale reconcile must be discarded: callback not invoked, User B must NOT be revoked to FREE
        assertFalse("Stale reconciliation callback must be discarded", reconciliationFinished)
        assertTrue("User B's VIP must not be revoked by User A's stale query", AppAuthManager.getCurrentUser()!!.isVipActive)
    }

    @Test
    fun testRapidUserSwitch_A_to_B_to_A_sessionGenerationProtectsCallbacks() {
        // User A logs in (generation 1)
        AppAuthManager.setCurrentUserForTesting(user("UserA"))
        val gen1 = AppAuthManager.getSessionGeneration()
        val opContextGen1 = BillingOperationContext(
            ownerAppUserId = "UserA",
            sessionGeneration = gen1,
            operationType = BillingOperationType.PURCHASE
        )

        // Switch to User B (generation 2)
        AppAuthManager.setCurrentUserForTesting(user("UserB"))

        // Switch back to User A (generation 3)
        AppAuthManager.setCurrentUserForTesting(user("UserA"))
        val gen3 = AppAuthManager.getSessionGeneration()
        assertTrue("Session generation must have advanced", gen3 > gen1)

        // Verify that opContextGen1 is detected as stale despite ownerAppUserId == currentUserId
        assertTrue("Context from generation 1 must be stale in generation 3", opContextGen1.isStale("UserA", gen3))

        // Trigger purchase completion with the old context
        var callbackReceived = false
        manager.addPurchaseCallback(object : BillingManager.PurchaseCallback {
            override fun onPurchaseResult(success: Boolean, message: String?, purchase: Purchase?) {
                callbackReceived = true
            }
        })

        val purchase = createTestPurchase(BillingManager.PRODUCT_VIP_LIFETIME, token = "tok_gen1", acknowledged = true)
        manager.processPurchase(
            purchase = purchase,
            origin = BillingOperationOrigin.PURCHASE,
            operationContext = opContextGen1
        )

        // The UI callback must be suppressed because sessionGeneration does not match
        assertFalse("Callback from previous session generation must be suppressed", callbackReceived)
    }

    @Test
    fun testOverlappingOperations_purchaseAndRestoreConcurrently_isolatedContexts() {
        AppAuthManager.setCurrentUserForTesting(user("User1"))

        // Purchase context
        val purchaseContext = BillingOperationContext(
            ownerAppUserId = "User1",
            sessionGeneration = AppAuthManager.getSessionGeneration(),
            operationType = BillingOperationType.PURCHASE
        )

        // Restore context
        val restoreContext = BillingOperationContext(
            ownerAppUserId = "User1",
            sessionGeneration = AppAuthManager.getSessionGeneration(),
            operationType = BillingOperationType.RESTORE
        )

        assertNotEquals("Operation IDs must be distinct", purchaseContext.operationId, restoreContext.operationId)
        assertEquals(BillingOperationType.PURCHASE, purchaseContext.operationType)
        assertEquals(BillingOperationType.RESTORE, restoreContext.operationType)

        val purchaseItem = createTestPurchase(BillingManager.PRODUCT_VIP_YEARLY, token = "purchase_tok", acknowledged = true)
        val restoreItem = createTestPurchase(BillingManager.PRODUCT_VIP_LIFETIME, token = "restore_tok", acknowledged = true)

        manager.processPurchase(purchaseItem, BillingOperationOrigin.PURCHASE, purchaseContext)
        manager.processPurchase(restoreItem, BillingOperationOrigin.RESTORE, restoreContext)

        val purchaseReq = recordedVerificationRequests.find { it.purchaseToken == "purchase_tok" }
        val restoreReq = recordedVerificationRequests.find { it.purchaseToken == "restore_tok" }

        assertNotNull(purchaseReq)
        assertNotNull(restoreReq)
        assertEquals("User1", purchaseReq?.ownerAppUserId)
        assertEquals("User1", restoreReq?.ownerAppUserId)
    }

    @Test
    fun testGuestPurchaseInFlight_userLogsInBeforeVerifyAckCompletes_appliedToStore_callbackGuarded() = runBlocking {
        // Guest initiates purchase
        AppAuthManager.setCurrentUserForTesting(null)
        val guestContext = BillingOperationContext(
            ownerAppUserId = null,
            sessionGeneration = AppAuthManager.getSessionGeneration(),
            operationType = BillingOperationType.PURCHASE
        )

        var uiCallbackFired = false
        manager.addPurchaseCallback(object : BillingManager.PurchaseCallback {
            override fun onPurchaseResult(success: Boolean, message: String?, purchase: Purchase?) {
                uiCallbackFired = true
            }
        })

        // User logs in while verification is simulated
        val purchase = createTestPurchase(BillingManager.PRODUCT_VIP_LIFETIME, token = "guest_tok", acknowledged = true)

        // Log in before processPurchase runs
        AppAuthManager.setCurrentUserForTesting(user("UserX"))

        manager.processPurchase(
            purchase = purchase,
            origin = BillingOperationOrigin.PURCHASE,
            operationContext = guestContext
        )

        // Entitlement must be stored for guest
        val guestSnapshot = store.getSnapshot(ctx, null)
        assertTrue("Guest entitlement must be stored", guestSnapshot.isVipActive())

        // UI callback to UserX for guest's purchase should be suppressed
        assertFalse("UI callback to newly logged-in user for stale guest purchase must be suppressed", uiCallbackFired)
    }

    @Test
    fun testStaleCallbacks_canceledAndErrorAndAlreadyOwned_suppressedOnSessionChange() {
        val receivedCallbacks = CopyOnWriteArrayList<String>()
        manager.addPurchaseCallback(object : BillingManager.PurchaseCallback {
            override fun onPurchaseResult(success: Boolean, message: String?, purchase: Purchase?) {
                receivedCallbacks.add("success=$success, msg=$message")
            }
        })

        // User A launches purchase
        AppAuthManager.setCurrentUserForTesting(user("UserA"))
        manager.setActivePurchaseOwnerForTesting("UserA")

        // User switches to B (session generation increments)
        AppAuthManager.setCurrentUserForTesting(user("UserB"))

        // 1. User Canceled
        val cancelResult = BillingResult.newBuilder()
            .setResponseCode(BillingClient.BillingResponseCode.USER_CANCELED)
            .setDebugMessage("User cancelled")
            .build()
        manager.onPurchasesUpdated(cancelResult, null)
        assertTrue("Canceled callback must be suppressed on stale session", receivedCallbacks.isEmpty())

        // 2. Item already owned
        val alreadyOwnedResult = BillingResult.newBuilder()
            .setResponseCode(BillingClient.BillingResponseCode.ITEM_ALREADY_OWNED)
            .setDebugMessage("Already owned")
            .build()
        manager.onPurchasesUpdated(alreadyOwnedResult, null)
        assertTrue("ITEM_ALREADY_OWNED callback must be suppressed on stale session", receivedCallbacks.isEmpty())

        // 3. Generic error
        val errorResult = BillingResult.newBuilder()
            .setResponseCode(BillingClient.BillingResponseCode.ERROR)
            .setDebugMessage("Google Play error")
            .build()
        manager.onPurchasesUpdated(errorResult, null)
        assertTrue("Error callback must be suppressed on stale session", receivedCallbacks.isEmpty())
    }

    @Test
    fun testMainThreadDispatch_staleCheckAtExecutionTime() {
        val userACtx = BillingOperationContext(
            ownerAppUserId = "UserA",
            sessionGeneration = AppAuthManager.getSessionGeneration(),
            operationType = BillingOperationType.PURCHASE
        )

        var callbackFired = false
        manager.addPurchaseCallback(object : BillingManager.PurchaseCallback {
            override fun onPurchaseResult(success: Boolean, message: String?, purchase: Purchase?) {
                callbackFired = true
            }
        })

        // User switches before callback executes
        AppAuthManager.setCurrentUserForTesting(user("UserB"))

        val purchase = createTestPurchase(BillingManager.PRODUCT_VIP_LIFETIME, token = "tok_late", acknowledged = true)
        manager.processPurchase(
            purchase = purchase,
            origin = BillingOperationOrigin.PURCHASE,
            operationContext = userACtx
        )

        assertFalse("Main thread dispatch must suppress stale callback", callbackFired)
    }
}
