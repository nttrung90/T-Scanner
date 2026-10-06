package com.tscanner.app

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import com.android.billingclient.api.Purchase
import com.tscanner.app.data.model.UserProfile
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

/**
 * Unit and integration tests for Monotonic Entitlement Snapshot & Persistence Failure Propagation (Q03 / R05 / R06).
 *
 * Verifies:
 * - Equal-version conflicting payload: Keeps existing state, rejects overwrite, returns ApplySnapshotResult.Conflict.
 * - Equal-version identical payload: Idempotent replay (no-op), produces ApplySnapshotResult.Success.
 * - Strictly newer snapshotVersion overwrites older.
 * - Stale snapshotVersion cannot overwrite newer.
 * - Item owner mismatch with snapshot owner is rejected as conflict.
 * - Persistence failure (commit = false) propagates to caller and does not notify purchase success.
 * - Exception during commit propagates as ApplySnapshotResult.PersistenceFailed without crashing.
 * - Verified inactive receipt (REVOKED/EXPIRED) persists tombstone but never reports active VIP success.
 * - Restart recovery: State committed to disk survives manager/store re-instantiation.
 * - Replay after retry does not accumulate or alter absolute expiry timestamps.
 */
class BillingSnapshotPersistenceTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var ctx: BillingTestContext
    private lateinit var client: FakeBillingClientWrapper
    private lateinit var manager: BillingManager
    private val store get() = BillingEntitlementStore.getInstance()

    private fun user(id: String) = UserProfile(id, "$id@example.test", "User $id")

    private fun testEntitlement(
        owner: String? = "User1",
        version: Long = 1L,
        state: EntitlementState = EntitlementState.VERIFIED_ACTIVE,
        expiry: Long? = null,
        token: String = "test_token"
    ) = BillingEntitlement(
        id = token,
        ownerAppUserId = owner,
        productId = BillingManager.PRODUCT_VIP_LIFETIME,
        productType = "inapp",
        purchaseToken = token,
        source = EntitlementSource.GOOGLE_PLAY_INAPP,
        state = state,
        expiryTimeMillis = expiry,
        snapshotVersion = version
    )

    private val verifier = object : PurchaseVerifier {
        override suspend fun verifyPurchase(request: VerificationRequest): VerificationResult {
            return VerificationResult.Success(
                testEntitlement(
                    owner = request.ownerAppUserId,
                    version = 1L,
                    state = EntitlementState.VERIFIED_ACTIVE,
                    token = request.purchaseToken
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
    fun testEqualVersionConflictingPayload_keepsExistingAndRejectsOverwrite() {
        val revoked = testEntitlement(owner = "User1", version = 2L, state = EntitlementState.REVOKED)
        val active = testEntitlement(owner = "User1", version = 2L, state = EntitlementState.VERIFIED_ACTIVE)

        val firstResult = store.applySnapshotTyped(ctx, UserEntitlementSnapshot("User1", listOf(revoked)))
        assertTrue("First write should succeed", firstResult is ApplySnapshotResult.Success)

        val conflictResult = store.applySnapshotTyped(ctx, UserEntitlementSnapshot("User1", listOf(active)))
        assertTrue("Conflicting equal-version payload must return Conflict", conflictResult is ApplySnapshotResult.Conflict)

        // Store must retain REVOKED state
        val loaded = store.getSnapshot(ctx, "User1")
        assertEquals(EntitlementState.REVOKED, loaded.entitlements.first().state)
        assertFalse("Revoked VIP must not be resurrected", loaded.isVipActive())
    }

    @Test
    fun testEqualVersionIdenticalPayload_idempotentReplayNoOp() {
        val active = testEntitlement(owner = "User1", version = 2L, state = EntitlementState.VERIFIED_ACTIVE)

        val firstResult = store.applySnapshotTyped(ctx, UserEntitlementSnapshot("User1", listOf(active)))
        assertTrue(firstResult is ApplySnapshotResult.Success)

        // Same version, identical payload
        val replayResult = store.applySnapshotTyped(ctx, UserEntitlementSnapshot("User1", listOf(active)))
        assertTrue("Identical replay must succeed idempotently", replayResult is ApplySnapshotResult.Success)

        val loaded = store.getSnapshot(ctx, "User1")
        assertEquals(1, loaded.entitlements.size)
        assertEquals(2L, loaded.entitlements.first().snapshotVersion)
        assertTrue(loaded.isVipActive())
    }

    @Test
    fun testNewerVersion_overwritesOlderVersion() {
        val v2Revoked = testEntitlement(owner = "User1", version = 2L, state = EntitlementState.REVOKED)
        val v3Active = testEntitlement(owner = "User1", version = 3L, state = EntitlementState.VERIFIED_ACTIVE)

        store.applySnapshot(ctx, UserEntitlementSnapshot("User1", listOf(v2Revoked)))
        assertFalse(store.getSnapshot(ctx, "User1").isVipActive())

        val result = store.applySnapshotTyped(ctx, UserEntitlementSnapshot("User1", listOf(v3Active)))
        assertTrue("Strictly newer version must succeed", result is ApplySnapshotResult.Success)

        val loaded = store.getSnapshot(ctx, "User1")
        assertEquals(EntitlementState.VERIFIED_ACTIVE, loaded.entitlements.first().state)
        assertEquals(3L, loaded.entitlements.first().snapshotVersion)
        assertTrue(loaded.isVipActive())
    }

    @Test
    fun testOlderVersion_doesNotOverwriteNewerVersion() {
        val v3Active = testEntitlement(owner = "User1", version = 3L, state = EntitlementState.VERIFIED_ACTIVE)
        val v2Revoked = testEntitlement(owner = "User1", version = 2L, state = EntitlementState.REVOKED)

        store.applySnapshot(ctx, UserEntitlementSnapshot("User1", listOf(v3Active)))

        // Attempting to apply older version v2
        store.applySnapshot(ctx, UserEntitlementSnapshot("User1", listOf(v2Revoked)))

        val loaded = store.getSnapshot(ctx, "User1")
        assertEquals("Newer version must not be downgraded by older snapshot", EntitlementState.VERIFIED_ACTIVE, loaded.entitlements.first().state)
        assertEquals(3L, loaded.entitlements.first().snapshotVersion)
    }

    @Test
    fun testItemOwnerMismatchWithSnapshotOwner_rejectedWithConflict() {
        val mismatchedItem = testEntitlement(owner = "UserB", version = 1L)
        val snapshotForUserA = UserEntitlementSnapshot("UserA", listOf(mismatchedItem))

        val result = store.applySnapshotTyped(ctx, snapshotForUserA)
        assertTrue("Item owner mismatch with snapshot owner must return Conflict", result is ApplySnapshotResult.Conflict)
    }

    @Test
    fun testCommitFailure_returnsPersistenceFailedAndDoesNotReportPurchaseSuccess() {
        val failingContext = object : ContextWrapper(ctx) {
            override fun getApplicationContext(): Context = this
            override fun getSharedPreferences(name: String?, mode: Int): SharedPreferences {
                val real = ctx.getSharedPreferences(name, mode)
                if (name != "tscanner_billing_entitlements") return real
                return object : SharedPreferences by real {
                    override fun edit(): SharedPreferences.Editor {
                        val editor = real.edit()
                        return object : SharedPreferences.Editor by editor {
                            override fun commit(): Boolean = false
                        }
                    }
                }
            }
        }

        val testManager = BillingManager.createInstanceForTesting(failingContext, { client }, verifier)
        AppAuthManager.setCurrentUserForTesting(user("User1"))

        var uiCallbackSuccess: Boolean? = null
        testManager.addPurchaseCallback(object : BillingManager.PurchaseCallback {
            override fun onPurchaseResult(success: Boolean, message: String?, purchase: Purchase?) {
                uiCallbackSuccess = success
            }
        })

        var processComplete: Boolean? = null
        testManager.processPurchase(
            createTestPurchase(BillingManager.PRODUCT_VIP_LIFETIME, acknowledged = true)
        ) { processComplete = it }

        assertEquals("Process completion must report false on persistence failure", false, processComplete)
        assertEquals("UI callback must report failure", false, uiCallbackSuccess)
    }

    @Test
    fun testCommitThrowsException_returnsPersistenceFailed() {
        val throwingContext = object : ContextWrapper(ctx) {
            override fun getApplicationContext(): Context = this
            override fun getSharedPreferences(name: String?, mode: Int): SharedPreferences {
                val real = ctx.getSharedPreferences(name, mode)
                if (name != "tscanner_billing_entitlements") return real
                return object : SharedPreferences by real {
                    override fun edit(): SharedPreferences.Editor {
                        val editor = real.edit()
                        return object : SharedPreferences.Editor by editor {
                            override fun commit(): Boolean = throw SecurityException("Storage permission revoked")
                        }
                    }
                }
            }
        }

        val result = store.applySnapshotTyped(
            throwingContext,
            UserEntitlementSnapshot("User1", listOf(testEntitlement("User1")))
        )
        assertTrue("Exception during commit must result in PersistenceFailed", result is ApplySnapshotResult.PersistenceFailed)
    }

    @Test
    fun testReceiptVerifiedInactive_persistsTombstone_doesNotReportPurchaseSuccess() {
        AppAuthManager.setCurrentUserForTesting(user("User1"))

        // Verifier reports Success, but with state = REVOKED
        val inactiveVerifier = object : PurchaseVerifier {
            override suspend fun verifyPurchase(request: VerificationRequest): VerificationResult {
                return VerificationResult.Success(
                    testEntitlement(
                        owner = "User1",
                        version = 1L,
                        state = EntitlementState.REVOKED,
                        token = request.purchaseToken
                    )
                )
            }
        }
        manager.setPurchaseVerifierForTesting(inactiveVerifier)

        val receivedEvents = CopyOnWriteArrayList<String>()
        manager.addPurchaseCallback(object : BillingManager.PurchaseCallback {
            override fun onPurchaseResult(success: Boolean, message: String?, purchase: Purchase?) {
                receivedEvents.add("success=$success, msg=$message")
            }
        })

        var processSuccess: Boolean? = null
        manager.processPurchase(
            createTestPurchase(BillingManager.PRODUCT_VIP_LIFETIME, token = "revoked_tok", acknowledged = true)
        ) { processSuccess = it }

        assertEquals("Process must not report success for inactive entitlement", false, processSuccess)
        assertFalse("User must not be granted active VIP", AppAuthManager.getCurrentUser()!!.isVipActive)

        // Tombstone state must be preserved in store
        val snapshot = store.getSnapshot(ctx, "User1")
        assertEquals(EntitlementState.REVOKED, snapshot.entitlements.first().state)

        // UI callback must not receive success
        assertTrue("Must report failure for inactive verified entitlement", receivedEvents.any { it.contains("success=false") })
    }

    @Test
    fun testRestartRecovery_persistedSnapshotSurvivesRecreation() {
        val entitlement = testEntitlement(owner = "UserRecover", version = 2L)
        assertTrue(store.applyEntitlement(ctx, entitlement))

        // Simulate app kill & recreation
        BillingEntitlementStore.resetInstanceForTesting()
        BillingManager.resetInstanceForTesting()

        val freshStore = BillingEntitlementStore.getInstance()
        val restoredSnapshot = freshStore.getSnapshot(ctx, "UserRecover")
        assertEquals(1, restoredSnapshot.entitlements.size)
        assertEquals(2L, restoredSnapshot.entitlements.first().snapshotVersion)
        assertTrue(restoredSnapshot.isVipActive())
    }

    @Test
    fun testReplayAfterRetry_preservesOriginalExpiryAndVersion() {
        val fixedExpiry = System.currentTimeMillis() + 86400000L
        val subEntitlement = testEntitlement(
            owner = "UserSub",
            version = 1L,
            expiry = fixedExpiry,
            token = "sub_tok_replay"
        )

        store.applyEntitlement(ctx, subEntitlement)
        val initialExpiry = store.getSnapshot(ctx, "UserSub").entitlements.first().expiryTimeMillis

        // Retry multiple times
        store.applyEntitlement(ctx, subEntitlement)
        store.applyEntitlement(ctx, subEntitlement)

        val finalSnapshot = store.getSnapshot(ctx, "UserSub")
        assertEquals("Expiry must not drift or accumulate across retries", initialExpiry, finalSnapshot.entitlements.first().expiryTimeMillis)
        assertEquals(1L, finalSnapshot.entitlements.first().snapshotVersion)
    }
}
