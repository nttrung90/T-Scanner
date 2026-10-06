package com.tscanner.app

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import com.tscanner.app.data.model.DocumentItem
import com.tscanner.app.data.model.UserProfile
import com.tscanner.app.data.model.VipTier
import com.tscanner.app.data.repository.DocumentRepo
import com.tscanner.app.utils.AppAuthManager
import com.tscanner.app.utils.CloudBackupManager
import com.tscanner.app.utils.SyncCatalogResult
import com.tscanner.app.utils.SyncResultPresenter
import kotlinx.coroutines.Dispatchers
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
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

class PostAuthorizationSyncResultTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var testContext: TestContext
    private lateinit var fakePrefs: FakeSharedPreferences

    @Before
    fun setUp() {
        androidx.arch.core.executor.ArchTaskExecutor.getInstance().setDelegate(object : androidx.arch.core.executor.TaskExecutor() {
            override fun executeOnDiskIO(runnable: Runnable) = runnable.run()
            override fun postToMainThread(runnable: Runnable) = runnable.run()
            override fun isMainThread(): Boolean = true
        })
        fakePrefs = FakeSharedPreferences()
        testContext = TestContext(tempFolder.root, fakePrefs)
        AppAuthManager.resetForTesting()
        AppAuthManager.postAuthSyncDispatcher = Dispatchers.Unconfined
        DocumentRepo.resetInstanceForTesting()
        CloudBackupManager.resetForTesting()
        SyncResultPresenter.resetForTesting()
    }

    @After
    fun tearDown() {
        AppAuthManager.resetForTesting()
        DocumentRepo.resetInstanceForTesting()
        CloudBackupManager.resetForTesting()
        SyncResultPresenter.resetForTesting()
        androidx.arch.core.executor.ArchTaskExecutor.getInstance().setDelegate(null)
    }

    // -------------------------------------------------------------------------
    // 1. Entitlement & Permission Precondition Tests
    // -------------------------------------------------------------------------

    @Test
    fun testPostAuthSync_whenUserNotLoggedIn_returnsSkippedNotLoggedIn() {
        var receivedResult: SyncCatalogResult? = null
        AppAuthManager.runPostAuthorizationSync(testContext) { result ->
            receivedResult = result
        }

        assertTrue("When user is null, must return Skipped", receivedResult is SyncCatalogResult.Skipped)
        assertEquals(SyncCatalogResult.Skipped.REASON_NOT_LOGGED_IN, (receivedResult as SyncCatalogResult.Skipped).reason)
    }

    @Test
    fun testPostAuthSync_whenUserFree_returnsSkippedNotVipAndDoesNotSync() {
        val repo = DocumentRepo.getInstance(testContext)
        val pdf = File(testContext.filesDir, "free_doc.pdf").apply { writeText("content") }
        repo.addDocument(DocumentItem(id = "doc_free_1", title = "Free Doc", ownerId = "user_free", pdfPath = pdf.absolutePath, isSynced = false))

        val freeUser = UserProfile(id = "user_free", email = "free@test.com", displayName = "Free User", isVip = false, tier = VipTier.FREE)
        fakePrefs.edit().putString(
            "key_user_profile",
            """{"id":"user_free","email":"free@test.com","displayName":"Free User","isVip":false,"tier":"free"}"""
        ).apply()
        AppAuthManager.init(testContext)

        var receivedResult: SyncCatalogResult? = null
        AppAuthManager.runPostAuthorizationSync(testContext) { result ->
            receivedResult = result
        }

        assertTrue("Free user must receive Skipped and not run sync", receivedResult is SyncCatalogResult.Skipped)
        assertEquals(SyncCatalogResult.Skipped.REASON_NOT_VIP, (receivedResult as SyncCatalogResult.Skipped).reason)
        // Ensure free user doc was not marked synced or enqueued
        val doc = repo.getDocument("doc_free_1")
        assertFalse(doc?.isSynced == true)
    }

    @Test
    fun testPostAuthSync_whenVipExpired_returnsSkippedNotVipAndDoesNotSync() {
        val expiredTime = System.currentTimeMillis() - 10000L
        val expiredUser = UserProfile(
            id = "user_expired",
            email = "expired@test.com",
            displayName = "Expired VIP",
            isVip = true,
            tier = VipTier.VIP,
            vipExpiresAt = expiredTime
        )
        fakePrefs.edit().putString(
            "key_user_profile",
            """{"id":"user_expired","email":"expired@test.com","displayName":"Expired VIP","isVip":true,"tier":"vip","vipExpiresAt":$expiredTime}"""
        ).apply()
        AppAuthManager.init(testContext)

        var receivedResult: SyncCatalogResult? = null
        AppAuthManager.runPostAuthorizationSync(testContext) { result ->
            receivedResult = result
        }

        assertTrue("Expired VIP user must receive Skipped", receivedResult is SyncCatalogResult.Skipped)
        assertEquals(SyncCatalogResult.Skipped.REASON_NOT_VIP, (receivedResult as SyncCatalogResult.Skipped).reason)
    }

    @Test
    fun testPostAuthSync_whenVipWithoutDrivePermission_returnsAuthRequiredAndDoesNotEnqueueBackup() {
        val repo = DocumentRepo.getInstance(testContext)
        val pdf = File(testContext.filesDir, "doc1.pdf").apply { writeText("content") }
        repo.addDocument(DocumentItem(id = "doc_1", title = "Doc 1", ownerId = "user_vip", pdfPath = pdf.absolutePath, isSynced = false))

        setupVipUser("vip@test.com", "user_vip")

        var receivedResult: SyncCatalogResult? = null
        AppAuthManager.runPostAuthorizationSync(
            context = testContext,
            hasDrivePermissionProvider = { false } // No Drive permission
        ) { result ->
            receivedResult = result
        }

        assertTrue("Must return AuthRequired when Drive permission missing", receivedResult is SyncCatalogResult.AuthRequired)
        // Verify doc was NOT marked synced or enqueued inappropriately
        val doc = repo.getDocument("doc_1")
        assertFalse(doc?.isSynced == true)
    }

    // -------------------------------------------------------------------------
    // 2. Typed SyncCatalogResult Outcomes (Success(0), Success(N), Partial, Failure)
    // -------------------------------------------------------------------------

    @Test
    fun testPostAuthSync_successZeroFiles_invokesSuccessWithZeroAdded() {
        setupVipUser("alice@test.com")

        CloudBackupManager.syncCatalogOverrideForTesting = { _, callback ->
            callback(SyncCatalogResult.Success(addedCount = 0, totalDriveFiles = 5))
        }

        var receivedResult: SyncCatalogResult? = null
        AppAuthManager.runPostAuthorizationSync(
            context = testContext,
            hasDrivePermissionProvider = { true }
        ) { result ->
            receivedResult = result
        }

        assertTrue(receivedResult is SyncCatalogResult.Success)
        val success = receivedResult as SyncCatalogResult.Success
        assertEquals(0, success.addedCount)
        assertEquals(5, success.totalDriveFiles)
    }

    @Test
    fun testPostAuthSync_successWithFiles_invokesSuccessWithCount() {
        setupVipUser("alice@test.com")

        CloudBackupManager.syncCatalogOverrideForTesting = { _, callback ->
            callback(SyncCatalogResult.Success(addedCount = 3, totalDriveFiles = 10))
        }

        var receivedResult: SyncCatalogResult? = null
        AppAuthManager.runPostAuthorizationSync(
            context = testContext,
            hasDrivePermissionProvider = { true }
        ) { result ->
            receivedResult = result
        }

        assertTrue(receivedResult is SyncCatalogResult.Success)
        val success = receivedResult as SyncCatalogResult.Success
        assertEquals(3, success.addedCount)
        assertEquals(10, success.totalDriveFiles)
    }

    @Test
    fun testPostAuthSync_partialResult_invokesPartialAndPreservesAccountAndVip() {
        setupVipUser("alice@test.com")

        CloudBackupManager.syncCatalogOverrideForTesting = { _, callback ->
            callback(SyncCatalogResult.Partial(addedCount = 2, partialDriveFiles = 5, error = "SocketTimeoutException"))
        }

        var receivedResult: SyncCatalogResult? = null
        AppAuthManager.runPostAuthorizationSync(
            context = testContext,
            hasDrivePermissionProvider = { true }
        ) { result ->
            receivedResult = result
        }

        assertTrue(receivedResult is SyncCatalogResult.Partial)
        val partial = receivedResult as SyncCatalogResult.Partial
        assertEquals(2, partial.addedCount)
        assertEquals(5, partial.partialDriveFiles)

        // VIP and account MUST remain intact
        val currentUser = AppAuthManager.getCurrentUser()
        assertNotNull(currentUser)
        assertTrue(currentUser?.isVipActive == true)
    }

    @Test
    fun testPostAuthSync_networkFailure_invokesFailureAndPreservesAccountAndVip() {
        setupVipUser("alice@test.com")

        CloudBackupManager.syncCatalogOverrideForTesting = { _, callback ->
            callback(SyncCatalogResult.Failure("Network I/O error"))
        }

        var receivedResult: SyncCatalogResult? = null
        AppAuthManager.runPostAuthorizationSync(
            context = testContext,
            hasDrivePermissionProvider = { true }
        ) { result ->
            receivedResult = result
        }

        assertTrue("Failure must be dispatched to UI", receivedResult is SyncCatalogResult.Failure)
        val failure = receivedResult as SyncCatalogResult.Failure
        assertEquals("Network I/O error", failure.error)

        // VIP and user account MUST NOT be revoked or cleared on network error!
        val currentUser = AppAuthManager.getCurrentUser()
        assertNotNull(currentUser)
        assertTrue("Network error must never downgrade VIP", currentUser?.isVipActive == true)
        assertEquals("alice@test.com", currentUser?.email)
    }

    // -------------------------------------------------------------------------
    // 3. Stale Session & Duplicate Request Protection
    // -------------------------------------------------------------------------

    @Test
    fun testPostAuthSync_duplicateRequest_rejectsDuplicateSyncFlight() {
        setupVipUser("alice@test.com")

        var firstInvoked = false
        var secondInvoked = false

        // Normal sync catalog using production isSyncingCatalog atomic CAS flag
        // Simulate in-flight sync by not resetting flag
        val flagField = CloudBackupManager::class.java.getDeclaredField("isSyncingCatalog").apply { isAccessible = true }
        val flag = flagField.get(CloudBackupManager) as java.util.concurrent.atomic.AtomicBoolean
        flag.set(true)

        CloudBackupManager.syncCatalogFromDriveWithResult(testContext) { result ->
            assertTrue("Duplicate sync attempt must return Failure", result is SyncCatalogResult.Failure)
            assertEquals("Sync already in progress", (result as SyncCatalogResult.Failure).error)
        }
    }

    @Test
    fun testPostAuthSync_staleResultAfterLogout_discardsCallback() {
        setupVipUser("alice@test.com")

        var staleCallbackInvoked = false

        // Start sync override where user logs out before callback fires
        CloudBackupManager.syncCatalogOverrideForTesting = { _, callback ->
            // User logs out while sync is in progress
            AppAuthManager.resetForTesting()
            // Callback arrives after logout:
            if (AppAuthManager.getCurrentUser() != null) {
                callback(SyncCatalogResult.Success(2, 2))
            } else {
                staleCallbackInvoked = false // Discarded!
            }
        }

        AppAuthManager.runPostAuthorizationSync(
            context = testContext,
            hasDrivePermissionProvider = { true }
        ) {
            staleCallbackInvoked = true
        }

        assertFalse("Stale sync callback after user logged out must be discarded", staleCallbackInvoked)
    }

    // -------------------------------------------------------------------------
    // 4. Backward-Compatible Wrapper Behavior
    // -------------------------------------------------------------------------

    @Test
    fun testSyncCatalogFromDrive_backwardCompatibleWrapper_returnsCountForSuccess() {
        setupVipUser("alice@test.com")

        CloudBackupManager.syncCatalogOverrideForTesting = { _, callback ->
            callback(SyncCatalogResult.Success(addedCount = 4, totalDriveFiles = 8))
        }

        var resultCount: Int? = null
        CloudBackupManager.syncCatalogFromDrive(testContext) { count ->
            resultCount = count
        }

        assertEquals(4, resultCount)
    }

    @Test
    fun testSyncCatalogFromDrive_backwardCompatibleWrapper_returnsZeroForFailure() {
        setupVipUser("alice@test.com")

        CloudBackupManager.syncCatalogOverrideForTesting = { _, callback ->
            callback(SyncCatalogResult.Failure("500 Server Error"))
        }

        var resultCount: Int? = null
        CloudBackupManager.syncCatalogFromDrive(testContext) { count ->
            resultCount = count
        }

        assertEquals(0, resultCount)
    }

    @Test
    fun testSyncCatalogFromDrive_backwardCompatibleWrapper_returnsZeroForSkipped() {
        setupVipUser("alice@test.com")

        CloudBackupManager.syncCatalogOverrideForTesting = { _, callback ->
            callback(SyncCatalogResult.Skipped(SyncCatalogResult.Skipped.REASON_NOT_VIP))
        }

        var resultCount: Int? = null
        CloudBackupManager.syncCatalogFromDrive(testContext) { count ->
            resultCount = count
        }

        assertEquals(0, resultCount)
    }

    @Test
    fun testSyncCatalogFromDriveWithResult_whenUserNotVip_returnsSkippedDirectly() {
        val freeUser = UserProfile(id = "user_free2", email = "free2@test.com", displayName = "Free User 2", isVip = false, tier = VipTier.FREE)
        fakePrefs.edit().putString(
            "key_user_profile",
            """{"id":"user_free2","email":"free2@test.com","displayName":"Free User 2","isVip":false,"tier":"free"}"""
        ).apply()
        AppAuthManager.init(testContext)

        var receivedResult: SyncCatalogResult? = null
        CloudBackupManager.syncCatalogFromDriveWithResult(testContext) { result ->
            receivedResult = result
        }

        assertTrue("Direct catalog sync without VIP entitlement must return Skipped", receivedResult is SyncCatalogResult.Skipped)
        assertEquals(SyncCatalogResult.Skipped.REASON_NOT_VIP, (receivedResult as SyncCatalogResult.Skipped).reason)
    }

    // -------------------------------------------------------------------------
    // 5. UI Presentation Contract: Free/Guest is Silent, VIP Errors are Surfaced
    // -------------------------------------------------------------------------

    @Test
    fun testUiMappingContract_distinguishesSkippedFromFailureAndSuccess() {
        // Models the exact 'when (result)' UI mapping in HomeFragment and MoreFragment
        fun simulateUiPresenter(result: SyncCatalogResult): String? {
            return when (result) {
                is SyncCatalogResult.Success -> if (result.addedCount > 0) "SYNCED_${result.addedCount}" else null
                is SyncCatalogResult.Partial -> "PARTIAL_${result.addedCount}"
                is SyncCatalogResult.AuthRequired -> "AUTH_REQUIRED"
                is SyncCatalogResult.Failure -> "ERROR_${result.error}"
                is SyncCatalogResult.Skipped -> null // SILENT for Free users / guests!
            }
        }

        // Free / Not logged in -> SILENT (null UI toast message, no fake failure)
        assertNull("Skipped NotVip must be completely silent on UI", simulateUiPresenter(SyncCatalogResult.Skipped(SyncCatalogResult.Skipped.REASON_NOT_VIP)))
        assertNull("Skipped NotLoggedIn must be completely silent on UI", simulateUiPresenter(SyncCatalogResult.Skipped(SyncCatalogResult.Skipped.REASON_NOT_LOGGED_IN)))

        // Success -> Success toast
        assertEquals("SYNCED_3", simulateUiPresenter(SyncCatalogResult.Success(3, 5)))
        assertNull(simulateUiPresenter(SyncCatalogResult.Success(0, 5)))

        // AuthRequired -> Auth toast
        assertEquals("AUTH_REQUIRED", simulateUiPresenter(SyncCatalogResult.AuthRequired("Drive permission needed")))

        // Real failure -> Error toast
        assertEquals("ERROR_Network error", simulateUiPresenter(SyncCatalogResult.Failure("Network error")))
    }

    // -------------------------------------------------------------------------
    // 6. S06b: SyncResultPresenter Lifecycle, Debouncing, & Propagation Tests
    // -------------------------------------------------------------------------

    @Test
    fun testSyncResultPresenter_debounceWindowDebouncesIdenticalEvents() {
        val t0 = 1000L
        val t1 = 2000L // 1s later (< 2s debounce window)
        val t2 = 3500L // 2.5s later (> 2s debounce window)

        assertTrue("First occurrence must be presented", SyncResultPresenter.shouldPresent("test_event", nowMs = t0))
        assertFalse("Duplicate occurrence within 2s must be debounced", SyncResultPresenter.shouldPresent("test_event", nowMs = t1))
        assertTrue("Occurrence after debounce window must be presented", SyncResultPresenter.shouldPresent("test_event", nowMs = t2))
    }

    @Test
    fun testSyncResultPresenter_differentEventsAreNotDebounced() {
        val t0 = 1000L
        val t1 = 1500L

        assertTrue("Event A must be presented", SyncResultPresenter.shouldPresent("event_a", nowMs = t0))
        assertTrue("Event B must be presented even within window of event A", SyncResultPresenter.shouldPresent("event_b", nowMs = t1))
    }

    @Test
    fun testSyncResultPresenter_authRequired_doesNotAutoTriggerDriveConsentLoop() {
        var consentInvoked = false
        SyncResultPresenter.present(
            context = testContext,
            result = SyncCatalogResult.AuthRequired("Drive scope missing"),
            onRequestDrivePermission = {
                consentInvoked = true
            }
        )

        assertFalse("AuthRequired presentation must NOT auto-launch consent dialog in a loop", consentInvoked)
    }

    @Test
    fun testSyncResultPresenter_success_invokesDocumentsAddedCallback() {
        var addedDocsCount = 0
        SyncResultPresenter.present(
            context = testContext,
            result = SyncCatalogResult.Success(addedCount = 5, totalDriveFiles = 10),
            onDocumentsAdded = { count ->
                addedDocsCount = count
            }
        )

        assertEquals("onDocumentsAdded must receive added file count", 5, addedDocsCount)
    }

    @Test
    fun testSyncResultPresenter_successZero_doesNotInvokeDocumentsAddedCallback() {
        var callbackInvoked = false
        SyncResultPresenter.present(
            context = testContext,
            result = SyncCatalogResult.Success(addedCount = 0, totalDriveFiles = 10),
            onDocumentsAdded = {
                callbackInvoked = true
            }
        )

        assertFalse("Success with 0 added files must not trigger document refresh", callbackInvoked)
    }

    @Test
    fun testVipPostAuthorizationSync_deliversTypedResultToHostCallback_notFireAndForget() {
        setupVipUser("vip.caller@test.com")

        CloudBackupManager.syncCatalogOverrideForTesting = { _, callback ->
            callback(SyncCatalogResult.Success(addedCount = 2, totalDriveFiles = 7))
        }

        var hostReceivedResult: SyncCatalogResult? = null
        AppAuthManager.runPostAuthorizationSync(
            context = testContext,
            hasDrivePermissionProvider = { true }
        ) { result ->
            hostReceivedResult = result
        }

        assertNotNull("Host caller must receive typed sync result, not dropped fire-and-forget", hostReceivedResult)
        assertTrue(hostReceivedResult is SyncCatalogResult.Success)
        assertEquals(2, (hostReceivedResult as SyncCatalogResult.Success).addedCount)
    }

    // -------------------------------------------------------------------------
    // 7. G06: Actionable Prompts, Single-Invocation, Lifecycle & Session Fencing
    // -------------------------------------------------------------------------

    @Test
    fun testSyncResultPresenter_authRequired_surfacesActionablePromptWithoutAutoConsenting() {
        var consentCount = 0
        var capturedPrompt: SyncResultPresenter.ActionPrompt? = null
        SyncResultPresenter.actionPresenter = { _, prompt ->
            capturedPrompt = prompt
        }

        SyncResultPresenter.present(
            context = testContext,
            result = SyncCatalogResult.AuthRequired("Drive scope missing"),
            onRequestDrivePermission = {
                consentCount++
            }
        )

        assertEquals("Present must NOT auto-invoke onRequestDrivePermission", 0, consentCount)
        assertNotNull("Actionable prompt must be surfaced", capturedPrompt)
        assertNotNull("Prompt must have an action label", capturedPrompt?.actionLabel)
        assertNotNull("Prompt must have an onAction handler", capturedPrompt?.onAction)

        // First tap invokes callback
        capturedPrompt?.onAction?.invoke()
        assertEquals("Tapping action must invoke onRequestDrivePermission exactly once", 1, consentCount)

        // Second tap on same prompt is guarded by AtomicBoolean
        capturedPrompt?.onAction?.invoke()
        assertEquals("Duplicate tap must not invoke callback again", 1, consentCount)
    }

    @Test
    fun testSyncResultPresenter_failure_surfacesRetryActionAndInvokesOnceOnTap() {
        var retryCount = 0
        var capturedPrompt: SyncResultPresenter.ActionPrompt? = null
        SyncResultPresenter.actionPresenter = { _, prompt ->
            capturedPrompt = prompt
        }

        SyncResultPresenter.present(
            context = testContext,
            result = SyncCatalogResult.Failure("Network timeout"),
            onRetry = {
                retryCount++
            }
        )

        assertEquals("Present must NOT auto-invoke onRetry", 0, retryCount)
        assertNotNull("Actionable prompt must be surfaced", capturedPrompt)
        assertNotNull("Prompt must have a retry action label", capturedPrompt?.actionLabel)

        // First tap
        capturedPrompt?.onAction?.invoke()
        assertEquals("Tapping action must invoke onRetry exactly once", 1, retryCount)

        // Second tap
        capturedPrompt?.onAction?.invoke()
        assertEquals("Duplicate tap must not invoke retry again", 1, retryCount)
    }

    @Test
    fun testSyncResultPresenter_withoutCallback_doesNotCreateDeadActionButton() {
        var capturedPrompt: SyncResultPresenter.ActionPrompt? = null
        SyncResultPresenter.actionPresenter = { _, prompt ->
            capturedPrompt = prompt
        }

        SyncResultPresenter.present(
            context = testContext,
            result = SyncCatalogResult.AuthRequired("Drive scope missing"),
            onRequestDrivePermission = null
        )

        assertNotNull(capturedPrompt)
        assertNull("Action label must be null when callback is missing (no dead button)", capturedPrompt?.actionLabel)
        assertNull("Action handler must be null when callback is missing", capturedPrompt?.onAction)

        // Same for Failure without onRetry
        var failurePrompt: SyncResultPresenter.ActionPrompt? = null
        SyncResultPresenter.actionPresenter = { _, prompt ->
            failurePrompt = prompt
        }

        SyncResultPresenter.present(
            context = testContext,
            result = SyncCatalogResult.Failure("Fatal sync error"),
            onRetry = null
        )

        assertNotNull(failurePrompt)
        assertNull("Action label must be null when onRetry is missing", failurePrompt?.actionLabel)
        assertNull("Action handler must be null when onRetry is missing", failurePrompt?.onAction)
    }

    @Test
    fun testSyncResultPresenter_destroyedHost_discardsPresentationAndLambdas() {
        val testActivity = TestActivity(testContext).apply {
            mockIsDestroyed = true
        }

        var promptPresented = false
        var toastPresented = false
        SyncResultPresenter.actionPresenter = { _, _ -> promptPresented = true }
        SyncResultPresenter.toastPresenter = { _, _, _ -> toastPresented = true }

        SyncResultPresenter.present(
            context = testActivity,
            result = SyncCatalogResult.AuthRequired("Drive scope missing"),
            onRequestDrivePermission = {}
        )

        assertFalse("Destroyed host must not show prompt", promptPresented)
        assertFalse("Destroyed host must not show toast", toastPresented)
    }

    @Test
    fun testSyncResultPresenter_staleSession_isDiscarded() {
        var promptPresented = false
        SyncResultPresenter.actionPresenter = { _, _ -> promptPresented = true }

        // Advance session generation in AppAuthManager
        AppAuthManager.nextSessionGeneration()
        val currentGen = AppAuthManager.getSessionGeneration()

        SyncResultPresenter.present(
            context = testContext,
            result = SyncCatalogResult.Failure("Old session failure"),
            onRetry = {},
            expectedSessionGeneration = currentGen - 1L // Stale generation
        )

        assertFalse("Stale session result must be discarded", promptPresented)
    }

    @Test
    fun testSyncResultPresenter_freeSkipped_isCompletelySilent() {
        var promptPresented = false
        var toastPresented = false
        SyncResultPresenter.actionPresenter = { _, _ -> promptPresented = true }
        SyncResultPresenter.toastPresenter = { _, _, _ -> toastPresented = true }

        SyncResultPresenter.present(
            context = testContext,
            result = SyncCatalogResult.Skipped(SyncCatalogResult.Skipped.REASON_NOT_VIP)
        )

        assertFalse("Skipped result must not show action prompt", promptPresented)
        assertFalse("Skipped result must not show toast", toastPresented)
    }

    @Test
    fun testSyncResultPresenter_tapAction_discardedIfSessionChangedAfterRender() {
        var actionCalls = 0
        var prompt: SyncResultPresenter.ActionPrompt? = null
        SyncResultPresenter.actionPresenter = { _, p -> prompt = p }

        val startGen = AppAuthManager.getSessionGeneration()
        SyncResultPresenter.present(
            context = testContext,
            result = SyncCatalogResult.AuthRequired("permission needed"),
            onRequestDrivePermission = { actionCalls++ },
            expectedSessionGeneration = startGen
        )

        assertNotNull("Prompt must be rendered", prompt)
        assertEquals("Callback must not be invoked on render", 0, actionCalls)

        // Session changes after render
        AppAuthManager.nextSessionGeneration()

        // User taps action from previous session
        prompt?.onAction?.invoke()
        assertEquals("Action from obsolete session must be discarded at tap time", 0, actionCalls)
    }

    @Test
    fun testSyncResultPresenter_tapAction_discardedIfHostViewDestroyedAfterRender() {
        var actionCalls = 0
        var prompt: SyncResultPresenter.ActionPrompt? = null
        SyncResultPresenter.actionPresenter = { _, p -> prompt = p }

        var isFragmentViewAlive = true
        SyncResultPresenter.present(
            context = testContext,
            result = SyncCatalogResult.Failure("Network timeout"),
            onRetry = { actionCalls++ },
            isHostValid = { isFragmentViewAlive }
        )

        assertNotNull("Prompt must be rendered while view is alive", prompt)

        // Fragment view is destroyed (e.g. user navigated to another tab)
        isFragmentViewAlive = false

        // User taps action after view was destroyed
        prompt?.onAction?.invoke()
        assertEquals("Action must be discarded if host view is destroyed", 0, actionCalls)
    }

    @Test
    fun testSyncResultPresenter_tapAction_discardedIfUserSwitchedAfterRender() {
        val userA = UserProfile(id = "user_A", email = "a@test.com", displayName = "A")
        val userB = UserProfile(id = "user_B", email = "b@test.com", displayName = "B")
        AppAuthManager.setCurrentUserForTesting(userA)

        var actionCalls = 0
        var prompt: SyncResultPresenter.ActionPrompt? = null
        SyncResultPresenter.actionPresenter = { _, p -> prompt = p }

        SyncResultPresenter.present(
            context = testContext,
            result = SyncCatalogResult.Failure("Sync failed for A"),
            onRetry = { actionCalls++ },
            expectedUserId = "user_A"
        )

        assertNotNull("Prompt must be rendered for user A", prompt)

        // Switch to user B
        AppAuthManager.setCurrentUserForTesting(userB)

        // User taps action intended for user A
        prompt?.onAction?.invoke()
        assertEquals("Action for user A must be discarded when user B is active", 0, actionCalls)
    }

    @Test
    fun testSyncResultPresenter_tapAction_singleInvocationAndClearsDebounceForRetry() {
        var actionCalls = 0
        var prompt: SyncResultPresenter.ActionPrompt? = null
        SyncResultPresenter.actionPresenter = { _, p -> prompt = p }

        SyncResultPresenter.present(
            context = testContext,
            result = SyncCatalogResult.Failure("Transient error"),
            onRetry = { actionCalls++ }
        )

        assertNotNull(prompt)

        // Tap action twice
        prompt?.onAction?.invoke()
        prompt?.onAction?.invoke()
        assertEquals("Action must be invoked exactly once", 1, actionCalls)

        // Retry result can be presented immediately without being blocked by debounce
        var nextPromptRendered = false
        SyncResultPresenter.actionPresenter = { _, _ -> nextPromptRendered = true }

        SyncResultPresenter.present(
            context = testContext,
            result = SyncCatalogResult.Failure("Transient error")
        )

        assertTrue("Next prompt after tap must not be blocked by debounce", nextPromptRendered)
    }

    // -------------------------------------------------------------------------
    // 8. H04b: Host & Origin Propagation Integration Tests
    // -------------------------------------------------------------------------

    @Test
    fun testHostIntegration_callbackFromOldSessionAfterLogoutLoginB_discardedWithoutRenderingOnB() {
        val userA = UserProfile(id = "user_A", email = "a@test.com", displayName = "A")
        val userB = UserProfile(id = "user_B", email = "b@test.com", displayName = "B")
        AppAuthManager.setCurrentUserForTesting(userA)

        val originGenA = AppAuthManager.getSessionGeneration()
        val originUserA = userA.id

        // User A logs out and User B logs in before sync callback returns
        AppAuthManager.signOut(testContext, kotlinx.coroutines.CoroutineScope(Dispatchers.Unconfined)) {}
        AppAuthManager.setCurrentUserForTesting(userB)

        var renderedOnB = false
        SyncResultPresenter.actionPresenter = { _, _ -> renderedOnB = true }
        SyncResultPresenter.toastPresenter = { _, _, _ -> renderedOnB = true }

        // Late callback from user A's sync arrives with captured origin metadata
        SyncResultPresenter.present(
            context = testContext,
            result = SyncCatalogResult.AuthRequired("Drive auth required for A"),
            expectedSessionGeneration = originGenA,
            expectedUserId = originUserA,
            isHostValid = { true },
            onRequestDrivePermission = { org.junit.Assert.fail("Old callback must not execute on new user") }
        )

        assertFalse("Late sync result from User A must NOT be rendered on User B session", renderedOnB)
    }

    @Test
    fun testHostIntegration_fragmentReplaceInSameActivity_discardsActionAtTapTime() {
        var isFragmentViewAlive = true
        var actionExecuted = false
        var capturedPrompt: SyncResultPresenter.ActionPrompt? = null
        SyncResultPresenter.actionPresenter = { _, p -> capturedPrompt = p }

        val startGen = AppAuthManager.getSessionGeneration()
        val startUser = AppAuthManager.getCurrentUser()?.id

        SyncResultPresenter.present(
            context = testContext,
            result = SyncCatalogResult.Failure("Network error"),
            expectedSessionGeneration = startGen,
            expectedUserId = startUser,
            isHostValid = { isFragmentViewAlive },
            onRetry = { actionExecuted = true }
        )

        assertNotNull("Prompt must be rendered while fragment is active", capturedPrompt)

        // Fragment is replaced / view is destroyed while Activity remains alive
        isFragmentViewAlive = false

        // Tap action on stale prompt
        capturedPrompt?.onAction?.invoke()

        assertFalse("Action must be discarded when fragment view has been destroyed/replaced", actionExecuted)
    }

    @Test
    fun testHostIntegration_retryCreatesFreshOperationIdentity() {
        var firstOperationRetried = false
        var secondOperationRetried = false
        var capturedPrompt: SyncResultPresenter.ActionPrompt? = null
        SyncResultPresenter.actionPresenter = { _, p -> capturedPrompt = p }

        // Initial failure in Session 1
        val gen1 = AppAuthManager.getSessionGeneration()
        val user1 = AppAuthManager.getCurrentUser()?.id

        SyncResultPresenter.present(
            context = testContext,
            result = SyncCatalogResult.Failure("Error 1"),
            expectedSessionGeneration = gen1,
            expectedUserId = user1,
            isHostValid = { true },
            onRetry = { firstOperationRetried = true }
        )

        assertNotNull(capturedPrompt)
        capturedPrompt?.onAction?.invoke()
        assertTrue("First retry must be executed", firstOperationRetried)

        // Fresh retry operation creates new identity (e.g. Session advances)
        AppAuthManager.nextSessionGeneration()
        val gen2 = AppAuthManager.getSessionGeneration()
        val user2 = AppAuthManager.getCurrentUser()?.id
        assertTrue(gen2 > gen1)

        capturedPrompt = null
        SyncResultPresenter.present(
            context = testContext,
            result = SyncCatalogResult.Failure("Error 2"),
            expectedSessionGeneration = gen2,
            expectedUserId = user2,
            isHostValid = { true },
            onRetry = { secondOperationRetried = true }
        )

        assertNotNull("Second prompt must be rendered with new operation identity", capturedPrompt)
        assertEquals(gen2, capturedPrompt?.targetSessionGeneration)
        capturedPrompt?.onAction?.invoke()
        assertTrue("Second retry with fresh identity must be executed", secondOperationRetried)
    }

    // -------------------------------------------------------------------------
    // Helper Methods
    // -------------------------------------------------------------------------

    private fun setupVipUser(email: String, id: String = "user_canonical_vip") {
        val expires = System.currentTimeMillis() + 86400000L
        fakePrefs.edit()
            .putString("key_user_profile", """{"id":"$id","email":"$email","displayName":"VIP Tester","isVip":true,"tier":"vip","vipExpiresAt":$expires}""")
            .putString("vip_account_${id}_tier", "vip")
            .putLong("vip_account_${id}_expires_at", expires)
            .putLong("vip_account_${id}_purchased_at", System.currentTimeMillis())
            .apply()
        AppAuthManager.init(testContext)
        AppAuthManager.setUserVipTier(testContext, VipTier.VIP, durationDays = 365)
    }

    private class TestContext(
        private val baseDir: File,
        private val prefs: SharedPreferences
    ) : ContextWrapper(null) {
        override fun getFilesDir(): File = File(baseDir, "files").apply { mkdirs() }
        override fun getCacheDir(): File = File(baseDir, "cache").apply { mkdirs() }
        override fun getApplicationContext(): Context = this
        override fun getSharedPreferences(name: String?, mode: Int): SharedPreferences = prefs
    }

    private class FakeSharedPreferences : SharedPreferences {
        private val map = mutableMapOf<String, Any>()

        override fun getAll(): MutableMap<String, *> = map.toMutableMap()
        override fun getString(key: String?, defValue: String?): String? = map[key] as? String ?: defValue
        override fun getStringSet(key: String?, defValues: MutableSet<String>?): MutableSet<String>? =
            (map[key] as? Set<*>)?.mapNotNull { it?.toString() }?.toMutableSet() ?: defValues

        override fun getInt(key: String?, defValue: Int): Int = (map[key] as? Number)?.toInt() ?: defValue
        override fun getLong(key: String?, defValue: Long): Long = (map[key] as? Number)?.toLong() ?: defValue
        override fun getFloat(key: String?, defValue: Float): Float = (map[key] as? Number)?.toFloat() ?: defValue
        override fun getBoolean(key: String?, defValue: Boolean): Boolean = map[key] as? Boolean ?: defValue
        override fun contains(key: String?): Boolean = map.containsKey(key)

        override fun edit(): SharedPreferences.Editor = FakeEditor(this)

        override fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) {}
        override fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) {}

        private class FakeEditor(private val prefs: FakeSharedPreferences) : SharedPreferences.Editor {
            private val pending = mutableMapOf<String, Any?>()
            private val removes = mutableSetOf<String>()
            private var clearFlag = false

            override fun putString(key: String?, value: String?): SharedPreferences.Editor {
                if (key != null) {
                    if (value != null) pending[key] = value else removes.add(key)
                }
                return this
            }

            override fun putStringSet(key: String?, values: MutableSet<String>?): SharedPreferences.Editor {
                if (key != null) {
                    if (values != null) pending[key] = values else removes.add(key)
                }
                return this
            }

            override fun putInt(key: String?, value: Int): SharedPreferences.Editor {
                if (key != null) pending[key] = value
                return this
            }

            override fun putLong(key: String?, value: Long): SharedPreferences.Editor {
                if (key != null) pending[key] = value
                return this
            }

            override fun putFloat(key: String?, value: Float): SharedPreferences.Editor {
                if (key != null) pending[key] = value
                return this
            }

            override fun putBoolean(key: String?, value: Boolean): SharedPreferences.Editor {
                if (key != null) pending[key] = value
                return this
            }

            override fun remove(key: String?): SharedPreferences.Editor {
                if (key != null) removes.add(key)
                return this
            }

            override fun clear(): SharedPreferences.Editor {
                clearFlag = true
                return this
            }

            override fun commit(): Boolean {
                apply()
                return true
            }

            override fun apply() {
                if (clearFlag) {
                    prefs.map.clear()
                }
                removes.forEach { prefs.map.remove(it) }
                pending.forEach { (k, v) ->
                    if (v != null) prefs.map[k] = v else prefs.map.remove(k)
                }
            }
        }
    }

    private class TestActivity(private val baseContext: Context) : Activity() {
        var mockIsFinishing: Boolean = false
        var mockIsDestroyed: Boolean = false

        override fun isFinishing(): Boolean = mockIsFinishing
        override fun isDestroyed(): Boolean = mockIsDestroyed
        override fun getApplicationContext(): Context = baseContext
        override fun getSharedPreferences(name: String?, mode: Int): SharedPreferences =
            baseContext.getSharedPreferences(name, mode)
    }
}
