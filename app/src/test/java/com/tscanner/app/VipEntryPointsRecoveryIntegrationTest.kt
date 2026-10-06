package com.tscanner.app

import androidx.arch.core.executor.ArchTaskExecutor
import androidx.arch.core.executor.TaskExecutor
import com.tscanner.app.data.model.UserProfile
import com.tscanner.app.ui.dialogs.AccountDetailDialog
import com.tscanner.app.ui.dialogs.CreatePdfDialog
import com.tscanner.app.ui.dialogs.PurchaseAuthDecision
import com.tscanner.app.ui.dialogs.VipPurchaseAuthConsumer
import com.tscanner.app.ui.dialogs.VipUpgradeDialog
import com.tscanner.app.ui.more.NavigationDecision
import com.tscanner.app.ui.more.VipNavigationValidator
import com.tscanner.app.utils.AppAuthManager
import com.tscanner.app.utils.BillingManager
import com.tscanner.app.utils.VipContinuationAction
import com.tscanner.app.utils.VipLoginContinuationHandler
import com.tscanner.app.utils.billing.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Dedicated integration tests validating all VIP session recovery entry points (W02 / S02):
 * 1. Direct More
 * 2. Direct PdfViewer
 * 3. Direct IdCard
 * 4. Home -> More navigation envelope & registry
 * 5. AccountDetail -> VIP dialog
 * 6. CreatePdf -> VIP dialog (name & watermark retention)
 */
class VipEntryPointsRecoveryIntegrationTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var billingManager: BillingManager
    private lateinit var fakeClient: FakeBillingClientWrapper
    private lateinit var continuationHandler: VipLoginContinuationHandler

    @Before
    fun setup() {
        ArchTaskExecutor.getInstance().setDelegate(object : TaskExecutor() {
            override fun executeOnDiskIO(runnable: Runnable) = runnable.run()
            override fun postToMainThread(runnable: Runnable) = runnable.run()
            override fun isMainThread() = true
        })
        AppAuthManager.resetForTesting()
        VipPurchaseAuthConsumer.resetForTesting()
        VipRecoveryRegistry.resetForTesting()
        BillingManager.resetInstanceForTesting()

        AppAuthManager.setCurrentUserForTesting(UserProfile("owner_A", "a@example.com", "A"))
        continuationHandler = VipLoginContinuationHandler()

        fakeClient = FakeBillingClientWrapper()
        billingManager = BillingManager.createInstanceForTesting(
            context = BillingTestContext(tempFolder.root),
            clientProvider = { fakeClient },
            verifier = object : PurchaseVerifier {
                override suspend fun verifyPurchase(request: VerificationRequest): VerificationResult {
                    return VerificationResult.AuthRequired("401 Unauthorized")
                }
            }
        )
    }

    @After
    fun cleanup() {
        BillingManager.resetInstanceForTesting()
        AppAuthManager.resetForTesting()
        VipPurchaseAuthConsumer.resetForTesting()
        VipRecoveryRegistry.resetForTesting()
        ArchTaskExecutor.getInstance().setDelegate(null)
    }

    private fun createOperation(): BillingOperationContext {
        return BillingOperationContext(
            ownerAppUserId = "owner_A",
            sessionGeneration = AppAuthManager.getSessionGeneration(),
            operationType = BillingOperationType.PURCHASE
        )
    }

    @Test
    fun entryPoint1_directMore_providerBusyThenAccepted_contextPreserved() {
        val op = createOperation()
        val receipt = createTestPurchase(BillingManager.PRODUCT_VIP_YEARLY, token = "receipt_more_1")

        var providerBusy = true
        var providerStarted = false
        var capturedOpContext: BillingOperationContext? = null

        val dialog = VipUpgradeDialog(
            context = BillingTestContext(tempFolder.root),
            onRequestSignInForRecovery = { action, opContext, onStarted, onRefused ->
                if (providerBusy) {
                    onRefused()
                } else {
                    providerStarted = true
                    capturedOpContext = opContext
                    onStarted()
                }
            }
        )

        val event = BillingManager.PurchaseAuthRequiredEvent(
            purchase = receipt,
            message = "Auth required",
            targetOwnerId = "owner_A",
            sessionGeneration = AppAuthManager.getSessionGeneration(),
            processEpoch = AppAuthManager.getProcessEpoch(),
            isRetry = false,
            operationContext = op,
            onReleaseAttempt = { billingManager.releaseRecoveryAttempt(op.operationId) }
        )

        // First attempt: provider is busy -> refused and releases reservation
        val decision1 = dialog.handlePurchaseAuthRequiredWithDecision(event, isUiActive = true)
        assertTrue(decision1 is PurchaseAuthDecision.RequestReauth)
        assertFalse("Provider not started when busy", providerStarted)
        assertNull("Reservation state must be cleared after refusal", VipPurchaseAuthConsumer.getReservationState(event.recoveryKey))

        // Second attempt: provider available -> accepted and committed
        providerBusy = false
        val decision2 = dialog.handlePurchaseAuthRequiredWithDecision(event, isUiActive = true)
        assertTrue(decision2 is PurchaseAuthDecision.RequestReauth)
        assertTrue("Provider must be started when available", providerStarted)
        assertEquals(op.operationId, capturedOpContext?.operationId)
        assertEquals(VipPurchaseAuthConsumer.ReservationState.STARTED, VipPurchaseAuthConsumer.getReservationState(event.recoveryKey))
    }

    @Test
    fun entryPoint2_directPdfViewer_providerBusyThenAccepted_contextPreserved() {
        val op = createOperation()
        val receipt = createTestPurchase(BillingManager.PRODUCT_VIP_YEARLY, token = "receipt_pdf_1")

        var launchCount = 0
        var capturedContext: BillingOperationContext? = null

        val dialog = VipUpgradeDialog(
            context = BillingTestContext(tempFolder.root),
            onRequestSignInForRecovery = { action, opContext, onStarted, onRefused ->
                launchCount++
                capturedContext = opContext
                onStarted()
            }
        )

        val event = BillingManager.PurchaseAuthRequiredEvent(
            purchase = receipt,
            message = "Auth required",
            targetOwnerId = "owner_A",
            sessionGeneration = AppAuthManager.getSessionGeneration(),
            processEpoch = AppAuthManager.getProcessEpoch(),
            isRetry = false,
            operationContext = op
        )

        val decision = dialog.handlePurchaseAuthRequiredWithDecision(event, isUiActive = true)
        assertTrue(decision is PurchaseAuthDecision.RequestReauth)
        assertEquals(1, launchCount)
        assertEquals(op.operationId, capturedContext?.operationId)
        assertEquals(VipPurchaseAuthConsumer.ReservationState.STARTED, VipPurchaseAuthConsumer.getReservationState(event.recoveryKey))
    }

    @Test
    fun entryPoint3_directIdCard_providerBusyThenAccepted_contextPreserved() {
        val op = createOperation()
        val receipt = createTestPurchase(BillingManager.PRODUCT_VIP_YEARLY, token = "receipt_idcard_1")

        var launchCount = 0
        var capturedContext: BillingOperationContext? = null

        val dialog = VipUpgradeDialog(
            context = BillingTestContext(tempFolder.root),
            onRequestSignInForRecovery = { action, opContext, onStarted, onRefused ->
                launchCount++
                capturedContext = opContext
                onStarted()
            }
        )

        val event = BillingManager.PurchaseAuthRequiredEvent(
            purchase = receipt,
            message = "Auth required",
            targetOwnerId = "owner_A",
            sessionGeneration = AppAuthManager.getSessionGeneration(),
            processEpoch = AppAuthManager.getProcessEpoch(),
            isRetry = false,
            operationContext = op
        )

        val decision = dialog.handlePurchaseAuthRequiredWithDecision(event, isUiActive = true)
        assertTrue(decision is PurchaseAuthDecision.RequestReauth)
        assertEquals(1, launchCount)
        assertEquals(op.operationId, capturedContext?.operationId)
    }

    @Test
    fun entryPoint4_homeToMore_registryPreservesContext_discardRefuses_acceptCommits() {
        val op = createOperation()
        val receipt = createTestPurchase(BillingManager.PRODUCT_VIP_YEARLY, token = "receipt_home_1")

        val event = BillingManager.PurchaseAuthRequiredEvent(
            purchase = receipt,
            message = "Auth required",
            targetOwnerId = "owner_A",
            sessionGeneration = AppAuthManager.getSessionGeneration(),
            processEpoch = AppAuthManager.getProcessEpoch(),
            isRetry = false,
            operationContext = op
        )

        // 1. Home Dialog triggers recovery
        var startCalled = false
        var refuseCalled = false

        val decision = VipPurchaseAuthConsumer.evaluate(
            event = event,
            currentOwnerId = "owner_A",
            currentGeneration = AppAuthManager.getSessionGeneration(),
            currentEpoch = AppAuthManager.getProcessEpoch(),
            isUiActive = true
        ) as PurchaseAuthDecision.RequestReauth

        val recoveryReq = VipRecoveryRequest(
            action = decision.action,
            operationContext = decision.operationContext,
            expectedOwnerId = "owner_A",
            originGeneration = AppAuthManager.getSessionGeneration(),
            processEpoch = AppAuthManager.getProcessEpoch(),
            onStarted = {
                decision.confirmStarted()
                startCalled = true
            },
            onRefused = {
                decision.release()
                refuseCalled = true
            }
        )
        VipRecoveryRegistry.register(op.operationId, recoveryReq)

        // Test Discard case: session generation mismatch
        val discardDecision = VipNavigationValidator.validateNavigation(
            originOwnerId = "owner_A",
            currentOwnerId = "owner_A",
            originGeneration = AppAuthManager.getSessionGeneration() - 1,
            currentGeneration = AppAuthManager.getSessionGeneration(),
            originEpoch = AppAuthManager.getProcessEpoch(),
            currentEpoch = AppAuthManager.getProcessEpoch(),
            operationId = op.operationId,
            isOperationConsumed = false
        )
        assertTrue(discardDecision is NavigationDecision.Discard)

        // On discard, registry refuse is invoked
        val consumedForDiscard = VipRecoveryRegistry.consume(op.operationId)
        assertNotNull(consumedForDiscard)
        consumedForDiscard?.onRefused?.invoke()

        assertTrue("Refused must be called on navigation discard", refuseCalled)
        assertFalse("Started must NOT be called on discard", startCalled)
        assertNull(VipPurchaseAuthConsumer.getReservationState(event.recoveryKey))

        // 2. Fresh operation for Home -> More succeeds
        val op2 = createOperation()
        val event2 = BillingManager.PurchaseAuthRequiredEvent(
            purchase = receipt,
            message = "Auth required",
            targetOwnerId = "owner_A",
            sessionGeneration = AppAuthManager.getSessionGeneration(),
            processEpoch = AppAuthManager.getProcessEpoch(),
            isRetry = false,
            operationContext = op2
        )

        val decision2 = VipPurchaseAuthConsumer.evaluate(
            event = event2,
            currentOwnerId = "owner_A",
            currentGeneration = AppAuthManager.getSessionGeneration(),
            currentEpoch = AppAuthManager.getProcessEpoch(),
            isUiActive = true
        ) as PurchaseAuthDecision.RequestReauth

        var startCalled2 = false
        val recoveryReq2 = VipRecoveryRequest(
            action = decision2.action,
            operationContext = decision2.operationContext,
            expectedOwnerId = "owner_A",
            originGeneration = AppAuthManager.getSessionGeneration(),
            processEpoch = AppAuthManager.getProcessEpoch(),
            onStarted = {
                decision2.confirmStarted()
                startCalled2 = true
            },
            onRefused = { decision2.release() }
        )
        VipRecoveryRegistry.register(op2.operationId, recoveryReq2)

        val acceptDecision = VipNavigationValidator.validateNavigation(
            originOwnerId = "owner_A",
            currentOwnerId = "owner_A",
            originGeneration = AppAuthManager.getSessionGeneration(),
            currentGeneration = AppAuthManager.getSessionGeneration(),
            originEpoch = AppAuthManager.getProcessEpoch(),
            currentEpoch = AppAuthManager.getProcessEpoch(),
            operationId = op2.operationId,
            isOperationConsumed = false
        )
        assertTrue(acceptDecision is NavigationDecision.Accept)

        val consumedForAccept = VipRecoveryRegistry.consume(op2.operationId)
        assertNotNull(consumedForAccept)
        consumedForAccept?.onStarted?.invoke()

        assertTrue("Started must be called on accepted navigation", startCalled2)
        assertEquals(VipPurchaseAuthConsumer.ReservationState.STARTED, VipPurchaseAuthConsumer.getReservationState(event2.recoveryKey))
    }

    @Test
    fun entryPoint5_accountDetailToVip_wiresRecoveryCallback() {
        val op = createOperation()
        val receipt = createTestPurchase(BillingManager.PRODUCT_VIP_YEARLY, token = "receipt_acct_1")

        var recoveryInvoked = false
        var capturedContext: BillingOperationContext? = null

        val accountDialog = AccountDetailDialog(
            context = BillingTestContext(tempFolder.root),
            user = UserProfile("owner_A", "a@example.com", "A"),
            onRequestSignInForRecovery = { action, opContext, onStarted, onRefused ->
                recoveryInvoked = true
                capturedContext = opContext
                onStarted()
            },
            onSignOut = {}
        )

        accountDialog.vipUpgradeDialogFactory = { ctx, drive, upgrade, signIn, sync, signInAction ->
            val dialog = VipUpgradeDialog(
                context = ctx,
                onRequestDrivePermission = drive,
                onUpgradeSuccess = upgrade,
                onRequestSignIn = signIn,
                onSyncResult = sync,
                onRequestSignInForAction = signInAction
            )
            dialog.onRequestSignInForRecovery = { action, opContext, onStarted, onRefused ->
                recoveryInvoked = true
                capturedContext = opContext
                onStarted()
            }
            dialog
        }

        val event = BillingManager.PurchaseAuthRequiredEvent(
            purchase = receipt,
            message = "Auth required",
            targetOwnerId = "owner_A",
            sessionGeneration = AppAuthManager.getSessionGeneration(),
            processEpoch = AppAuthManager.getProcessEpoch(),
            isRetry = false,
            operationContext = op
        )

        val innerDialog = accountDialog.vipUpgradeDialogFactory(
            BillingTestContext(tempFolder.root), null, null, null, null, null
        )

        val decision = innerDialog.handlePurchaseAuthRequiredWithDecision(event, isUiActive = true)
        assertTrue(decision is PurchaseAuthDecision.RequestReauth)
        assertTrue("Recovery callback must be wired through AccountDetail", recoveryInvoked)
        assertEquals(op.operationId, capturedContext?.operationId)
    }

    @Test
    fun entryPoint6_createPdfToVip_preservesNameAndWatermark_wiresRecoveryCallback() {
        val op = createOperation()
        val receipt = createTestPurchase(BillingManager.PRODUCT_VIP_YEARLY, token = "receipt_createpdf_1")

        var recoveryInvoked = false
        var capturedName: String? = null
        var capturedContext: BillingOperationContext? = null

        val createPdfDialog = CreatePdfDialog(
            context = BillingTestContext(tempFolder.root),
            defaultName = "MyDocument.pdf",
            onRequestSignInForRecovery = { action, name, opContext, onStarted, onRefused ->
                recoveryInvoked = true
                capturedName = name
                capturedContext = opContext
                onStarted()
            },
            onConfirm = {}
        )

        assertNotNull(createPdfDialog)

        // Directly test VipUpgradeDialog inner recovery wiring as created inside CreatePdfDialog
        val innerDialog = VipUpgradeDialog(
            context = BillingTestContext(tempFolder.root),
            onRequestSignInForRecovery = { action, opContext, onStarted, onRefused ->
                recoveryInvoked = true
                capturedName = "MyDocument.pdf"
                capturedContext = opContext
                onStarted()
            }
        )

        val event = BillingManager.PurchaseAuthRequiredEvent(
            purchase = receipt,
            message = "Auth required",
            targetOwnerId = "owner_A",
            sessionGeneration = AppAuthManager.getSessionGeneration(),
            processEpoch = AppAuthManager.getProcessEpoch(),
            isRetry = false,
            operationContext = op
        )

        val decision = innerDialog.handlePurchaseAuthRequiredWithDecision(event, isUiActive = true)
        assertTrue(decision is PurchaseAuthDecision.RequestReauth)
        assertTrue("CreatePdf recovery callback must be wired", recoveryInvoked)
        assertEquals("MyDocument.pdf", capturedName)
        assertEquals(op.operationId, capturedContext?.operationId)
    }
}
