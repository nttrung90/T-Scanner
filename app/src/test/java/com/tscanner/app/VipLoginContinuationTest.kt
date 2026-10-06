package com.tscanner.app

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import android.os.Bundle
import com.tscanner.app.data.model.DocumentItem
import com.tscanner.app.data.model.UserProfile
import com.tscanner.app.data.model.VipTier
import com.tscanner.app.data.repository.DocumentRepo
import com.tscanner.app.ui.dialogs.VipUpgradeActionResolver
import com.tscanner.app.utils.AppAuthManager
import com.tscanner.app.utils.GoogleSignInAccountData
import com.tscanner.app.utils.VipLoginContinuationHandler
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class VipLoginContinuationTest {

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
        DocumentRepo.resetInstanceForTesting()
    }

    @After
    fun tearDown() {
        AppAuthManager.resetForTesting()
        DocumentRepo.resetInstanceForTesting()
        androidx.arch.core.executor.ArchTaskExecutor.getInstance().setDelegate(null)
    }

    // -------------------------------------------------------------------------
    // Action Resolver Tests (Contract between Dialog and Caller)
    // -------------------------------------------------------------------------

    @Test
    fun testActionResolver_guestWithCallback_resolvesToRequestSignIn() {
        val action = VipUpgradeActionResolver.resolveUpgradeAction(
            currentUser = null,
            hasSignInCallback = true
        )
        assertTrue("Guest with sign-in callback must resolve to RequestSignIn", action is VipUpgradeActionResolver.Action.RequestSignIn)
    }

    @Test
    fun testActionResolver_guestWithoutCallback_resolvesToShowSignInRequiredPrompt() {
        val action = VipUpgradeActionResolver.resolveUpgradeAction(
            currentUser = null,
            hasSignInCallback = false
        )
        assertTrue("Guest without callback must resolve to ShowSignInRequiredPrompt", action is VipUpgradeActionResolver.Action.ShowSignInRequiredPrompt)
    }

    @Test
    fun testActionResolver_loggedInUser_resolvesToActivateVip() {
        val validToken = "eyJhbGciOiJSUzI1NiJ9." + java.util.Base64.getUrlEncoder().withoutPadding().encodeToString("""{"sub":"alice","exp":4102444800}""".toByteArray()) + ".signature"
        val user = UserProfile(
            id = "user_alice",
            email = "alice@example.com",
            displayName = "Alice",
            isVip = false,
            tier = VipTier.FREE,
            idToken = validToken
        )
        val action = VipUpgradeActionResolver.resolveUpgradeAction(
            currentUser = user,
            hasSignInCallback = true
        )
        assertTrue("Logged in user must resolve to ActivateVip", action is VipUpgradeActionResolver.Action.ActivateVip)
        assertEquals("alice@example.com", (action as VipUpgradeActionResolver.Action.ActivateVip).email)
    }

    // -------------------------------------------------------------------------
    // Continuation Handler State Machine Tests
    // -------------------------------------------------------------------------

    @Test
    fun testContinuationHandler_initialState_isNotPending() {
        val handler = VipLoginContinuationHandler()
        assertFalse("New continuation handler must start in non-pending state", handler.isPending)
    }

    @Test
    fun testContinuationHandler_requestContinuation_setsPendingTrue() {
        val handler = VipLoginContinuationHandler()
        handler.requestContinuation()
        assertTrue("Requesting continuation must set isPending to true", handler.isPending)
    }

    @Test
    fun testContinuationHandler_onSignInSuccess_opensConfirmationAndResetsPending() {
        val handler = VipLoginContinuationHandler()
        handler.requestContinuation()

        var confirmationOpened = false
        handler.onSignInSuccess {
            confirmationOpened = true
        }

        assertTrue("Confirmation dialog must be opened when continuation is pending", confirmationOpened)
        assertFalse("isPending must be reset to false after opening confirmation", handler.isPending)

        // Subsequent sign in completion must NOT re-open confirmation
        var reOpened = false
        handler.onSignInSuccess {
            reOpened = true
        }
        assertFalse("Confirmation must not open twice without a new request", reOpened)
    }

    @Test
    fun testContinuationHandler_onSignInSuccess_whenNotPending_doesNotOpenConfirmation() {
        val handler = VipLoginContinuationHandler()
        var opened = false
        handler.onSignInSuccess {
            opened = true
        }
        assertFalse("Should not open confirmation when sign in was not initiated for VIP continuation", opened)
    }

    @Test
    fun testContinuationHandler_onSignInCancelled_resetsPending() {
        val handler = VipLoginContinuationHandler()
        handler.requestContinuation()
        handler.onSignInCancelled()
        assertFalse("Cancelling sign-in must clear pending continuation flag", handler.isPending)
    }

    @Test
    fun testContinuationHandler_onSignInError_resetsPending() {
        val handler = VipLoginContinuationHandler()
        handler.requestContinuation()
        handler.onSignInError()
        assertFalse("Sign-in error must clear pending continuation flag", handler.isPending)
    }

    @Test
    fun testContinuationHandler_savedStateRestoration_preservesPending() {
        val originalHandler = VipLoginContinuationHandler()
        originalHandler.requestContinuation()

        val stateMap = mutableMapOf<String, Any>()
        originalHandler.saveToMap(stateMap)

        val restoredHandler = VipLoginContinuationHandler()
        restoredHandler.restoreFromMap(stateMap)

        assertTrue("Pending continuation flag must be preserved across state save/restore (e.g. rotation)", restoredHandler.isPending)
    }

    @Test
    fun testContinuationHandler_supportsRestoreAction_andResetsPendingAfterExecution() {
        val handler = VipLoginContinuationHandler()
        handler.requestContinuation(com.tscanner.app.utils.VipContinuationAction.RESTORE, sessionGeneration = 42L)
        assertTrue(handler.isPending)
        assertEquals(com.tscanner.app.utils.VipContinuationAction.RESTORE, handler.pendingAction)
        assertEquals(42L, handler.pendingSessionGeneration)

        var executedAction: com.tscanner.app.utils.VipContinuationAction? = null
        handler.onSignInSuccessWithAction { action ->
            executedAction = action
        }

        assertEquals(com.tscanner.app.utils.VipContinuationAction.RESTORE, executedAction)
        assertFalse("Pending flag must reset after execution", handler.isPending)

        // Idempotency: second call must not execute
        var secondRun = false
        handler.onSignInSuccessWithAction {
            secondRun = true
        }
        assertFalse(secondRun)
    }

    @Test
    fun testContinuationHandler_stateSaveAndRestore_preservesActionAndSessionGen() {
        val original = VipLoginContinuationHandler()
        original.requestContinuation(com.tscanner.app.utils.VipContinuationAction.RESTORE, sessionGeneration = 101L)

        val map = mutableMapOf<String, Any>()
        original.saveToMap(map)

        val restored = VipLoginContinuationHandler()
        restored.restoreFromMap(map)

        assertTrue(restored.isPending)
        assertEquals(com.tscanner.app.utils.VipContinuationAction.RESTORE, restored.pendingAction)
        assertEquals(101L, restored.pendingSessionGeneration)
    }

    @Test
    fun testContinuationHandler_bundleMethods_handleNullAndDefaultSafely() {
        val handler = VipLoginContinuationHandler()
        handler.requestContinuation()

        val bundle = Bundle()
        handler.saveInstanceState(bundle)

        val restored = VipLoginContinuationHandler()
        restored.restoreInstanceState(null)
        assertFalse("Restoring from null bundle should default to false", restored.isPending)

        restored.restoreInstanceState(bundle)
        // Bundle on JVM unit tests with returnDefaultValues=true returns default false without throwing
        assertFalse(restored.isPending)
    }

    @Test
    fun testContinuationHandler_sessionGenerationMismatch_dropsContinuation() {
        val handler = VipLoginContinuationHandler()
        handler.requestContinuation(com.tscanner.app.utils.VipContinuationAction.RESTORE, sessionGeneration = 5L)

        var executed = false
        // Caller passes session generation 6L (e.g. session changed due to logout or different user login)
        handler.onSignInSuccessWithAction(currentSessionGeneration = 6L) {
            executed = true
        }

        assertFalse("Continuation must be dropped on session generation mismatch", executed)
        assertFalse("Pending flag must be cleared", handler.isPending)
    }

    @Test
    fun testContinuationHandler_targetProductId_preservedAcrossContinuation() {
        val handler = VipLoginContinuationHandler()
        handler.requestContinuation(
            action = com.tscanner.app.utils.VipContinuationAction.UPGRADE,
            sessionGeneration = 10L,
            targetProductId = "vip_yearly"
        )

        assertEquals("vip_yearly", handler.targetProductId)

        var observedAction: com.tscanner.app.utils.VipContinuationAction? = null
        var observedProduct: String? = null
        handler.onSignInSuccessWithAction(currentSessionGeneration = 10L) { action, product ->
            observedAction = action
            observedProduct = product
        }

        assertEquals(com.tscanner.app.utils.VipContinuationAction.UPGRADE, observedAction)
        assertEquals("vip_yearly", observedProduct)
        assertFalse(handler.isPending)
    }

    @Test
    fun testContinuationHandler_actionNone_doesNotExecuteCallback() {
        val handler = VipLoginContinuationHandler()
        handler.requestContinuation(action = com.tscanner.app.utils.VipContinuationAction.NONE)

        var executed = false
        handler.onSignInSuccessWithAction {
            executed = true
        }

        assertFalse("Action NONE must not invoke callback", executed)
        assertFalse(handler.isPending)
    }

    @Test
    fun testContinuationHandler_exactlyOnceDispatch_noDuplicateOnFallback() {
        val handler = VipLoginContinuationHandler()
        handler.requestContinuation(com.tscanner.app.utils.VipContinuationAction.RESTORE, sessionGeneration = 1L)

        var dispatchCount = 0
        // First completion (e.g. Credential Manager or fallback result)
        handler.onSignInSuccessWithAction(currentSessionGeneration = 1L) {
            dispatchCount++
        }
        assertEquals(1, dispatchCount)

        // Second completion (e.g. duplicate callback from late provider)
        handler.onSignInSuccessWithAction(currentSessionGeneration = 1L) {
            dispatchCount++
        }
        assertEquals("Continuation must NOT run a second time", 1, dispatchCount)
    }

    @Test
    fun testContinuationHandler_staleAttempt_mismatchedRequestId_dropsContinuation() {
        val handler = VipLoginContinuationHandler()
        handler.requestContinuation(
            action = com.tscanner.app.utils.VipContinuationAction.UPGRADE,
            sessionGeneration = 1L,
            originatingRequestId = 100L
        )

        var executed = false
        // Callback arrives with mismatched attemptRequestId (e.g. 99L)
        handler.onSignInSuccessWithAction(
            currentSessionGeneration = 1L,
            currentOwnerId = "any_user",
            attemptRequestId = 99L
        ) { _, _ ->
            executed = true
        }

        assertFalse("Stale attempt with mismatched request ID must NOT dispatch", executed)
        assertTrue("Active continuation must remain pending when an unrelated attempt arrives", handler.isPending)
    }

    @Test
    fun testContinuationHandler_accountSwitch_A_to_B_dropsContinuation() {
        val handler = VipLoginContinuationHandler()
        // Initiated by user A
        handler.requestContinuation(
            action = com.tscanner.app.utils.VipContinuationAction.RESTORE,
            sessionGeneration = 2L,
            initialOwnerId = "user_a"
        )

        var executed = false
        // Successfully logged in, but as user B
        handler.onSignInSuccessWithAction(
            currentSessionGeneration = 2L,
            currentOwnerId = "user_b"
        ) { _, _ ->
            executed = true
        }

        assertFalse("Continuation must be rejected when authenticated account mismatches initial owner", executed)
        assertFalse(handler.isPending)
    }

    @Test
    fun testContinuationHandler_logoutBeforeSuccess_dropsContinuation() {
        val handler = VipLoginContinuationHandler()
        handler.requestContinuation(
            action = com.tscanner.app.utils.VipContinuationAction.UPGRADE,
            sessionGeneration = 1L,
            initialOwnerId = "user_a"
        )

        var executed = false
        // User logged out before callback arrived: currentOwnerId is null
        handler.onSignInSuccessWithAction(
            currentSessionGeneration = 2L,
            currentOwnerId = null
        ) { _, _ ->
            executed = true
        }

        assertFalse("Continuation must NOT dispatch after user logged out", executed)
        assertFalse(handler.isPending)
    }

    @Test
    fun testContinuationHandler_processDeath_restoringObsoleteEpoch_resetsPending() {
        val original = VipLoginContinuationHandler()
        original.requestContinuation(
            action = com.tscanner.app.utils.VipContinuationAction.UPGRADE,
            sessionGeneration = 1L,
            processEpoch = "old_killed_process_epoch"
        )

        val map = mutableMapOf<String, Any>()
        original.saveToMap(map)

        // Process restarted: AppAuthManager has a newly generated process epoch
        val restored = VipLoginContinuationHandler()
        restored.restoreFromMap(map)

        assertFalse("Pending continuation from previous process death must NOT be replayed", restored.isPending)
    }

    @Test
    fun testContinuationHandler_rotation_sameProcessEpoch_preservesAndExecutesContinuation() {
        val original = VipLoginContinuationHandler()
        original.requestContinuation(
            action = com.tscanner.app.utils.VipContinuationAction.RESTORE,
            sessionGeneration = 1L,
            initialOwnerId = null, // guest
            processEpoch = AppAuthManager.getProcessEpoch()
        )

        val map = mutableMapOf<String, Any>()
        original.saveToMap(map)

        // Activity rotated within same process
        val restored = VipLoginContinuationHandler()
        restored.restoreFromMap(map)

        assertTrue("Rotation must preserve pending continuation", restored.isPending)
        assertEquals(com.tscanner.app.utils.VipContinuationAction.RESTORE, restored.pendingAction)

        var executedAction: com.tscanner.app.utils.VipContinuationAction? = null
        // Guest logs in successfully (session generation becomes 2L, owner becomes "new_user")
        restored.onSignInSuccessWithAction(
            currentSessionGeneration = 2L,
            currentOwnerId = "new_user"
        ) { action, _ ->
            executedAction = action
        }

        assertEquals("Restored continuation must execute on successful login", com.tscanner.app.utils.VipContinuationAction.RESTORE, executedAction)
        assertFalse(restored.isPending)
    }

    @Test
    fun testContinuationHandler_cancelledWithMatchingAttemptId_resetsPending() {
        val handler = VipLoginContinuationHandler()
        handler.requestContinuation(
            action = com.tscanner.app.utils.VipContinuationAction.UPGRADE,
            originatingRequestId = 42L
        )

        // Cancellation with non-matching attempt ID is ignored
        handler.onSignInCancelled(attemptRequestId = 99L)
        assertTrue("Cancellation with mismatched ID must not cancel this continuation", handler.isPending)

        // Cancellation with matching attempt ID resets
        handler.onSignInCancelled(attemptRequestId = 42L)
        assertFalse("Cancellation with matching ID must reset pending", handler.isPending)
    }

    // -------------------------------------------------------------------------
    // Document Safety & Entitlement Separation
    // -------------------------------------------------------------------------

    @Test
    fun testGuestDocumentsAndFreeStatePreserved_whenContinuationCancelled() {
        val repo = DocumentRepo.getInstance(testContext)
        val guestPdf = File(testContext.filesDir, "contract.pdf").apply { writeText("contract original draft") }
        repo.addDocument(DocumentItem(id = "doc_contract", title = "Contract", ownerId = null, pdfPath = guestPdf.absolutePath, isSynced = false))

        val handler = VipLoginContinuationHandler()
        handler.requestContinuation()

        // User cancels Google Sign-In
        handler.onSignInCancelled()

        // Assert user remains FREE and guest document is preserved
        assertFalse("User must remain free on cancelled sign-in", AppAuthManager.isUserVip())
        assertNull("No user must be logged in", AppAuthManager.getCurrentUser())
        val docAfter = repo.getDocument("doc_contract")
        assertNotNull("Guest document must exist", docAfter)
        assertNull("Guest document ownerId must remain null", docAfter?.ownerId)
        assertEquals("contract original draft", File(docAfter?.pdfPath!!).readText())
    }

    @Test
    fun testGuestDocumentsClaimed_afterContinuationSuccess() {
        val repo = DocumentRepo.getInstance(testContext)
        val guestPdf = File(testContext.filesDir, "id_scan.pdf").apply { writeText("id scan bytes") }
        repo.addDocument(DocumentItem(id = "doc_id_scan", title = "ID Scan", ownerId = null, pdfPath = guestPdf.absolutePath, isSynced = false))

        val handler = VipLoginContinuationHandler()
        handler.requestContinuation()

        // Real Google login completes
        val realAccount = GoogleSignInAccountData(
            id = "google_user_555",
            email = "real.user555@gmail.com",
            displayName = "Real User 555",
            givenName = "Real",
            familyName = "User",
            photoUrl = null,
            idToken = null
        )
        val profile = AppAuthManager.processSignedInAccount(testContext, realAccount)

        var confirmationOpened = false
        handler.onSignInSuccess {
            confirmationOpened = true
        }

        assertTrue("Confirmation dialog must be opened", confirmationOpened)
        assertFalse("Signing in alone must NOT auto-grant VIP", profile.isVip)
        assertEquals(VipTier.FREE, profile.tier)

        // Document is safely claimed by the real Google user
        val docAfter = repo.getDocument("doc_id_scan")
        assertNotNull("Document must exist", docAfter)
        assertEquals("google_user_555", docAfter?.ownerId)
        assertEquals("id scan bytes", File(docAfter?.pdfPath!!).readText())
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

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
}
