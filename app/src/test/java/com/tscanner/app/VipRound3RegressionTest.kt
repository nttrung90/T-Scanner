package com.tscanner.app

import com.tscanner.app.data.model.UserProfile
import com.tscanner.app.utils.AppAuthManager
import com.tscanner.app.utils.BillingManager
import com.tscanner.app.utils.billing.*
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*
import org.junit.rules.TemporaryFolder

class VipRound3RegressionTest {
    @get:Rule val tmp = TemporaryFolder()
    private lateinit var ctx: BillingTestContext
    private lateinit var client: FakeBillingClientWrapper
    private val store get() = BillingEntitlementStore.getInstance()

    private fun ent(
        id: String = "receipt",
        state: EntitlementState = EntitlementState.VERIFIED_ACTIVE,
        version: Long = 1
    ) = BillingEntitlement(
        id = id,
        ownerAppUserId = "A",
        productId = BillingManager.PRODUCT_VIP_YEARLY,
        productType = "subs",
        purchaseToken = "receipt",
        state = state,
        expiryTimeMillis = System.currentTimeMillis() + 86400000,
        snapshotVersion = version
    )

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
        AppAuthManager.setCurrentUserForTesting(UserProfile("A", "A@example.test", "A"))
    }

    @After
    fun cleanup() {
        BillingManager.resetInstanceForTesting()
        AppAuthManager.resetForTesting()
        BillingEntitlementStore.resetInstanceForTesting()
        androidx.arch.core.executor.ArchTaskExecutor.getInstance().setDelegate(null)
    }

    private fun manager(e: BillingEntitlement) = BillingManager.createInstanceForTesting(ctx, { client }, object : PurchaseVerifier {
        override suspend fun verifyPurchase(request: VerificationRequest) = VerificationResult.Success(e)
    })

    @Test
    fun emptyDeviceCatalogMustPreserveServerEntitlement() {
        val e = ent()
        AppAuthManager.applyEntitlement(ctx, e)
        manager(e).restorePurchases { _, _ -> }
        assertTrue("Device Play account absence is not server revocation", store.getSnapshot(ctx, "A").isVipActive())
    }

    @Test
    fun expiredServerIdMustReplaceActiveToken() {
        store.applyEntitlement(ctx, ent(id = "GOOGLE_PLAY_SUBSCRIPTION_receipt"))
        store.applyEntitlement(ctx, ent(id = "receipt", state = EntitlementState.EXPIRED, version = 2))
        assertFalse("Same token remains active under previous ID", store.getSnapshot(ctx, "A").isVipActive())
    }

    @Test
    fun canceledPaidPeriodMustRestoreSuccessfully() {
        var success: Boolean? = null
        manager(ent(state = EntitlementState.CANCELED_ACTIVE)).processPurchase(
            createTestPurchase(BillingManager.PRODUCT_VIP_YEARLY, acknowledged = true),
            BillingOperationOrigin.RESTORE,
            onComplete = { success = it }
        )
        assertEquals(true, success)
    }

    @Test
    fun malformedStateMustNotBecomeActive() = runBlocking {
        val verifier = PlayPurchaseVerifier(
            backendUrl = "https://audit.invalid",
            httpTransport = { _, _, _, _ ->
                VerifierHttpResponse(200, """{"status":"SUCCESS","entitlement":{"ownerAppUserId":"A","state":"UNKNOWN"}}""")
            }
        )
        val result = verifier.verifyPurchase(
            VerificationRequest("A", BillingManager.PRODUCT_VIP_YEARLY, "subs", "receipt", clientPurchaseTimeMillis = 1)
        )
        assertFalse("Unknown state and missing expiry became active lifetime", result is VerificationResult.Success && result.entitlement.isCurrentlyActive())
    }

    @Test
    fun httpEndpointMustNotBeReady() {
        assertFalse(PlayPurchaseVerifier("http://audit.invalid").isConfigured())
    }

    @Test
    fun rejectionMustPersistAuthoritativeRevocation() {
        val initial = ent(version = 1L)
        AppAuthManager.applyEntitlement(ctx, initial)
        val serverTombstone = initial.copy(state = EntitlementState.REVOKED, snapshotVersion = 2L)
        val mgr = BillingManager.createInstanceForTesting(ctx, { client }, object : PurchaseVerifier {
            override suspend fun verifyPurchase(request: VerificationRequest) =
                VerificationResult.Rejected(RejectionReason.PURCHASE_REVOKED, "revoked", tombstone = serverTombstone)
        })
        mgr.processPurchase(createTestPurchase(BillingManager.PRODUCT_VIP_YEARLY, acknowledged = true), BillingOperationOrigin.RESTORE)
        assertFalse("Authoritative revocation ignored by client", store.getSnapshot(ctx, "A").isVipActive())
    }

    @Test
    fun staleActiveSnapshotMustNotReportPurchaseSuccess() {
        store.applyEntitlement(ctx, ent(state = EntitlementState.REVOKED, version = 9))
        var success: Boolean? = null
        manager(ent(version = 1)).processPurchase(
            createTestPurchase(BillingManager.PRODUCT_VIP_YEARLY, acknowledged = true),
            BillingOperationOrigin.RESTORE,
            onComplete = { success = it }
        )
        assertEquals("Ignored stale active snapshot reported as successful restore", false, success)
    }

    @Test
    fun secondStoreCommitFailureMustNotReportSuccess() {
        var storeCommits = 0
        var failProjection = false

        class InterceptingEditor(
            private val delegate: android.content.SharedPreferences.Editor,
            private val onCommit: () -> Boolean
        ) : android.content.SharedPreferences.Editor {
            override fun putString(key: String?, value: String?): android.content.SharedPreferences.Editor { delegate.putString(key, value); return this }
            override fun putStringSet(key: String?, values: MutableSet<String>?): android.content.SharedPreferences.Editor { delegate.putStringSet(key, values); return this }
            override fun putInt(key: String?, value: Int): android.content.SharedPreferences.Editor { delegate.putInt(key, value); return this }
            override fun putLong(key: String?, value: Long): android.content.SharedPreferences.Editor { delegate.putLong(key, value); return this }
            override fun putFloat(key: String?, value: Float): android.content.SharedPreferences.Editor { delegate.putFloat(key, value); return this }
            override fun putBoolean(key: String?, value: Boolean): android.content.SharedPreferences.Editor { delegate.putBoolean(key, value); return this }
            override fun remove(key: String?): android.content.SharedPreferences.Editor { delegate.remove(key); return this }
            override fun clear(): android.content.SharedPreferences.Editor { delegate.clear(); return this }
            override fun commit(): Boolean = onCommit()
            override fun apply() { onCommit() }
        }

        val faulty = object : android.content.ContextWrapper(ctx) {
            override fun getApplicationContext(): android.content.Context = this
            override fun getSharedPreferences(name: String?, mode: Int): android.content.SharedPreferences {
                val real = ctx.getSharedPreferences(name, mode)
                if (name == "tscanner_billing_entitlements") {
                    return object : android.content.SharedPreferences by real {
                        override fun edit(): android.content.SharedPreferences.Editor {
                            return InterceptingEditor(real.edit()) {
                                storeCommits++
                                real.edit().commit()
                            }
                        }
                    }
                }
                if (name == "tscanner_auth_prefs") {
                    return object : android.content.SharedPreferences by real {
                        override fun edit(): android.content.SharedPreferences.Editor {
                            return InterceptingEditor(real.edit()) {
                                if (failProjection) false else real.edit().commit()
                            }
                        }
                    }
                }
                return real
            }
        }
        val mgr = BillingManager.createInstanceForTesting(faulty, { client }, object : PurchaseVerifier {
            override suspend fun verifyPurchase(request: VerificationRequest) = VerificationResult.Success(ent())
        })
        var success: Boolean? = null
        failProjection = true
        mgr.processPurchase(
            createTestPurchase(BillingManager.PRODUCT_VIP_YEARLY, acknowledged = true),
            BillingOperationOrigin.RESTORE,
            onComplete = { success = it }
        )
        assertEquals("Profile projection failed but billing emitted success", false, success)
        assertEquals("Must perform exactly one durable store commit", 1, storeCommits)
    }

    @Test
    fun driveWorkerMustRecheckVipAfterTokenWait() = runBlocking {
        val repoClass = com.tscanner.app.data.repository.DocumentRepo
        repoClass.resetInstanceForTesting()
        try {
            AppAuthManager.applyEntitlement(ctx, ent())
            val file = java.io.File(ctx.filesDir, "snapshot.pdf").apply { writeText("%PDF-1.4 audit") }
            val repo = repoClass.getInstance(ctx)
            repo.addDocument(com.tscanner.app.data.model.DocumentItem(id = "audit-doc", title = "Audit", ownerId = "A", pdfPath = file.absolutePath, contentRevision = 1))
            com.tscanner.app.utils.GoogleDriveBackupWorker.tokenProviderForTesting = { _, _ ->
                AppAuthManager.applyEntitlement(ctx, ent(state = EntitlementState.REVOKED, version = 2))
                "synthetic-token"
            }
            var uploads = 0
            com.tscanner.app.utils.GoogleDriveBackupWorker.driveUploaderForTesting = { _, _, _, _, _ ->
                uploads++
                com.tscanner.app.utils.DriveOperationResult.Success("synthetic-drive-id")
            }
            val data = androidx.work.Data.Builder()
                .putString("key_doc_id", "audit-doc")
                .putString("key_pdf_path", file.absolutePath)
                .putString("key_snapshot_path", file.absolutePath)
                .putString("key_owner_id", "A")
                .putLong("key_revision", 1)
                .build()
            com.tscanner.app.utils.GoogleDriveBackupWorker.performBackup(ctx, data, 0)
            assertEquals("Upload started after authoritative VIP revocation", 0, uploads)
        } finally {
            com.tscanner.app.utils.GoogleDriveBackupWorker.resetForTesting()
            repoClass.resetInstanceForTesting()
        }
    }
}
