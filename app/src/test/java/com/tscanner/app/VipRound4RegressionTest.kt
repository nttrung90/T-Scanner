package com.tscanner.app

import com.tscanner.app.data.model.UserProfile
import com.tscanner.app.utils.AppAuthManager
import com.tscanner.app.utils.BillingManager
import com.tscanner.app.utils.billing.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*
import org.junit.rules.TemporaryFolder

class VipRound4RegressionTest {
    @get:Rule val tmp = TemporaryFolder()
    private lateinit var ctx: BillingTestContext
    private lateinit var client: FakeBillingClientWrapper
    private val store get() = BillingEntitlementStore.getInstance()

    private fun ent(token: String = "receipt", version: Long = 1) = BillingEntitlement(
        id = "GOOGLE_PLAY_SUBSCRIPTION_$token",
        ownerAppUserId = "A",
        productId = BillingManager.PRODUCT_VIP_YEARLY,
        productType = "subs",
        purchaseToken = token,
        state = EntitlementState.VERIFIED_ACTIVE,
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

    private fun request() = VerificationRequest("A", BillingManager.PRODUCT_VIP_YEARLY, "subs", "receipt", clientPurchaseTimeMillis = 1)

    @Test
    fun responseDifferentTokenMustBeRejected() = runBlocking {
        val v = PlayPurchaseVerifier(
            backendUrl = "https://audit.invalid",
            httpTransport = { _, _, _, _ ->
                VerifierHttpResponse(
                    200,
                    """{"status":"SUCCESS","entitlement":{"ownerAppUserId":"A","purchaseToken":"another-token","productId":"tscanner_vip_yearly","productType":"subs","state":"VERIFIED_ACTIVE","expiryTimeMillis":4102444800000,"snapshotVersion":2}}"""
                )
            }
        )
        assertFalse("Response token is not request token", v.verifyPurchase(request()) is VerificationResult.Success)
    }

    @Test
    fun expiredSessionMustNotBePurchaseReady() {
        val raw = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString("""{"sub":"A","exp":1}""".toByteArray())
        AppAuthManager.setCurrentUserForTesting(UserProfile("A", "A@example.test", "A", idToken = "eyJhbGciOiJSUzI1NiJ9.$raw.synthetic"))
        val v = PlayPurchaseVerifier(
            backendUrl = "https://audit.invalid",
            tokenProvider = { AppAuthManager.getSessionToken() },
            ownerProvider = { AppAuthManager.getSessionOwnerId() },
            sessionGenerationProvider = { AppAuthManager.getSessionGeneration() }
        )
        assertFalse("Expired login token still passes purchase preflight", v.isConfigured())
    }

    @Test
    fun emptyCatalogMustReachRemoteRestore() {
        var requests = 0
        val validToken = "eyJhbGciOiJSUzI1NiJ9." + java.util.Base64.getUrlEncoder().withoutPadding().encodeToString("""{"sub":"A","exp":4102444800}""".toByteArray()) + ".synthetic"
        val v = PlayPurchaseVerifier(
            backendUrl = "https://audit.invalid",
            tokenProvider = { validToken },
            httpTransport = { _, _, _, _ ->
                requests++
                VerifierHttpResponse(503, """{"status":"TRANSIENT_ERROR"}""")
            }
        )
        val mgr = BillingManager.createInstanceForTesting(ctx, { client }, v)
        mgr.restorePurchases { _, _ -> }
        assertTrue("Fresh device with no Play purchases never contacts app-account server", requests > 0)
    }

    @Test
    fun rejectionWithoutSnapshotMustNotMintServerVersion() {
        AppAuthManager.applyEntitlement(ctx, ent(version = 10))
        val mgr = BillingManager.createInstanceForTesting(ctx, { client }, object : PurchaseVerifier {
            override suspend fun verifyPurchase(request: VerificationRequest) =
                VerificationResult.Rejected(RejectionReason.PURCHASE_REVOKED, "synthetic rejection without version")
        })
        mgr.processPurchase(createTestPurchase(BillingManager.PRODUCT_VIP_YEARLY, acknowledged = true), BillingOperationOrigin.RESTORE)
        assertEquals("Client manufactured server version from rejection lacking snapshot", 10L, store.getSnapshot(ctx, "A").entitlements.single().snapshotVersion)
    }

    @Test
    fun durableVerifiedEntitlementMustSurviveRedundantClientAckFailure() {
        client.returnBillingResultForAck = com.android.billingclient.api.BillingResult.newBuilder()
            .setResponseCode(com.android.billingclient.api.BillingClient.BillingResponseCode.ERROR).build()
        val mgr = BillingManager.createInstanceForTesting(ctx, { client }, object : PurchaseVerifier {
            override suspend fun verifyPurchase(request: VerificationRequest) =
                VerificationResult.Success(ent(request.purchaseToken))
        })
        mgr.processPurchase(createTestPurchase(BillingManager.PRODUCT_VIP_YEARLY, acknowledged = false), BillingOperationOrigin.RESTORE)
        assertTrue("Server durable grant withheld because duplicate SDK acknowledge failed", store.getSnapshot(ctx, "A").isVipActive())
    }

    @Test
    fun controlCanceledActiveStillGrants() {
        val mgr = BillingManager.createInstanceForTesting(ctx, { client }, object : PurchaseVerifier {
            override suspend fun verifyPurchase(request: VerificationRequest) =
                VerificationResult.Success(ent(request.purchaseToken).copy(state = EntitlementState.CANCELED_ACTIVE))
        })
        var success: Boolean? = null
        mgr.processPurchase(createTestPurchase(BillingManager.PRODUCT_VIP_YEARLY, acknowledged = true), BillingOperationOrigin.RESTORE, onComplete = { success = it })
        assertEquals(true, success)
    }

    @Test
    fun equalVersionDifferentIdMustNotResurrectRevokedToken() {
        store.applyEntitlement(ctx, ent(version = 9).copy(state = EntitlementState.REVOKED))
        store.applyEntitlement(ctx, ent(version = 9).copy(id = "legacy-id"))
        assertFalse("Equal version conflicting state bypasses guard via different id", store.getSnapshot(ctx, "A").isVipActive())
    }

    @Test
    fun rejectionOfOldTokenMustNotRevokeAnotherTokenForSameProduct() {
        AppAuthManager.applyEntitlement(ctx, ent(token = "new-valid-token", version = 10))
        val mgr = BillingManager.createInstanceForTesting(ctx, { client }, object : PurchaseVerifier {
            override suspend fun verifyPurchase(request: VerificationRequest) =
                VerificationResult.Rejected(RejectionReason.PURCHASE_EXPIRED, "expired old purchase")
        })
        mgr.processPurchase(createTestPurchase(BillingManager.PRODUCT_VIP_YEARLY, acknowledged = true), BillingOperationOrigin.RESTORE)
        assertTrue("Expired receipt matched by SKU overwrote different active receipt", store.getSnapshot(ctx, "A").entitlements.any { it.purchaseToken == "new-valid-token" && it.isCurrentlyActive() })
    }

    @Test
    fun canceledDriveWorkerMustNotStartUploadAfterTokenWait() = runBlocking {
        com.tscanner.app.data.repository.DocumentRepo.resetInstanceForTesting()
        val workerJob = kotlinx.coroutines.Job()
        try {
            AppAuthManager.applyEntitlement(ctx, ent())
            val file = java.io.File(ctx.filesDir, "snapshot.pdf").apply { writeText("%PDF-1.4 audit") }
            val repo = com.tscanner.app.data.repository.DocumentRepo.getInstance(ctx)
            repo.addDocument(com.tscanner.app.data.model.DocumentItem(id = "audit-doc", title = "Audit", ownerId = "A", pdfPath = file.absolutePath, contentRevision = 1))
            com.tscanner.app.utils.GoogleDriveBackupWorker.tokenProviderForTesting = { _, _ -> workerJob.cancel(); "synthetic-token" }
            var uploads = 0
            com.tscanner.app.utils.GoogleDriveBackupWorker.driveUploaderForTesting = { _, _, _, _, _ -> uploads++; com.tscanner.app.utils.DriveOperationResult.Success("synthetic-drive-id") }
            val data = androidx.work.Data.Builder().putString("key_doc_id", "audit-doc").putString("key_pdf_path", file.absolutePath).putString("key_snapshot_path", file.absolutePath).putString("key_owner_id", "A").putLong("key_revision", 1).build()
            kotlinx.coroutines.CoroutineScope(workerJob + kotlinx.coroutines.Dispatchers.Unconfined).launch {
                com.tscanner.app.utils.GoogleDriveBackupWorker.performBackup(ctx, data, 0)
            }.join()
            assertEquals("Coroutine canceled during token lookup still started upload", 0, uploads)
        } finally {
            workerJob.cancel()
            com.tscanner.app.utils.GoogleDriveBackupWorker.resetForTesting()
            com.tscanner.app.data.repository.DocumentRepo.resetInstanceForTesting()
        }
    }
}
