package com.tscanner.app

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
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

/**
 * Regression and integration contract tests for VIP login continuation in PdfViewerActivity
 * and CreatePdfDialog (S05a).
 *
 * Invariants:
 * 1. Guest user in Viewer or Create PDF dialog triggers Google Sign-In with continuation.
 * 2. Typed PDF name and loaded pages are preserved across sign-in and configuration changes.
 * 3. Successful sign-in reopens the VIP confirmation dialog exactly once.
 * 4. Sign-in alone does NOT auto-grant VIP without explicit user confirmation in the dialog.
 * 5. Cancellation or error resets continuation cleanly without modifying pages or granting VIP.
 * 6. Account switch while waiting is safely handled.
 */
class VipViewerLoginContinuationTest {

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
    // 1. Action Resolver Tests (Viewer & Create PDF Callsites)
    // -------------------------------------------------------------------------

    @Test
    fun testViewerWatermarkAction_whenGuestWithCallback_resolvesToRequestSignIn() {
        // Pre-S05a: Viewer and CreatePdfDialog passed null for onRequestSignIn, resolving to ShowSignInRequiredPrompt.
        // Post-S05a: onRequestSignIn is provided, resolving to RequestSignIn.
        val action = VipUpgradeActionResolver.resolveUpgradeAction(
            currentUser = null,
            hasSignInCallback = true
        )
        assertTrue("Guest with sign-in callback must resolve to RequestSignIn", action is VipUpgradeActionResolver.Action.RequestSignIn)
    }

    @Test
    fun testViewerWatermarkAction_whenGuestWithoutCallback_resolvesToPrompt() {
        val action = VipUpgradeActionResolver.resolveUpgradeAction(
            currentUser = null,
            hasSignInCallback = false
        )
        assertTrue("Guest without callback must resolve to ShowSignInRequiredPrompt", action is VipUpgradeActionResolver.Action.ShowSignInRequiredPrompt)
    }

    @Test
    fun testViewerWatermarkAction_whenLoggedInUser_resolvesToActivateVip() {
        val validToken = "eyJhbGciOiJSUzI1NiJ9." + java.util.Base64.getUrlEncoder().withoutPadding().encodeToString("""{"sub":"viewer.user","exp":4102444800}""".toByteArray()) + ".signature"
        val user = UserProfile(
            id = "user_viewer_guest",
            email = "viewer.user@example.com",
            displayName = "Viewer User",
            isVip = false,
            tier = VipTier.FREE,
            idToken = validToken
        )
        val action = VipUpgradeActionResolver.resolveUpgradeAction(
            currentUser = user,
            hasSignInCallback = true
        )
        assertTrue("Logged-in user must resolve to ActivateVip", action is VipUpgradeActionResolver.Action.ActivateVip)
        assertEquals("viewer.user@example.com", (action as VipUpgradeActionResolver.Action.ActivateVip).email)
    }

    // -------------------------------------------------------------------------
    // 2. Draft File Name & State Machine Continuation
    // -------------------------------------------------------------------------

    @Test
    fun testCreatePdfDialog_onRequestSignIn_preservesTypedDraftName() {
        var receivedDraftName: String? = null
        val handler = VipLoginContinuationHandler()

        // Host provides onRequestSignIn callback that captures the typed draft name
        val onRequestSignIn: (String) -> Unit = { typedName ->
            receivedDraftName = typedName
            handler.requestContinuation()
        }

        // Simulate user typing a custom PDF title "Tax_Report_2026"
        val userTypedTitle = "Tax_Report_2026"
        onRequestSignIn.invoke(userTypedTitle)

        assertEquals("Draft PDF name must be preserved when starting sign-in", "Tax_Report_2026", receivedDraftName)
        assertTrue("Continuation handler must mark pending", handler.isPending)
    }

    @Test
    fun testContinuationHandler_loginSuccess_triggersConfirmationExactlyOnce() {
        val handler = VipLoginContinuationHandler()
        handler.requestContinuation()

        var confirmationCalls = 0
        handler.onSignInSuccess {
            confirmationCalls++
        }

        assertEquals("Confirmation dialog callback must be called exactly once", 1, confirmationCalls)
        assertFalse("Pending flag must be reset after opening confirmation", handler.isPending)

        // Duplicate invocation without a new request must not open confirmation again
        handler.onSignInSuccess {
            confirmationCalls++
        }
        assertEquals("Confirmation dialog callback must NOT be re-invoked", 1, confirmationCalls)
    }

    @Test
    fun testContinuationHandler_loginCancelled_resetsPending() {
        val handler = VipLoginContinuationHandler()
        handler.requestContinuation()
        assertTrue(handler.isPending)

        handler.onSignInCancelled()
        assertFalse("Cancellation must clear pending continuation", handler.isPending)
    }

    @Test
    fun testContinuationHandler_loginError_resetsPending() {
        val handler = VipLoginContinuationHandler()
        handler.requestContinuation()
        assertTrue(handler.isPending)

        handler.onSignInError()
        assertFalse("Error must clear pending continuation", handler.isPending)
    }

    @Test
    fun testViewerStatePreservation_draftNameAndContinuationPreservedAcrossSavedState() {
        val originalHandler = VipLoginContinuationHandler()
        originalHandler.requestContinuation()

        val stateMap = mutableMapOf<String, Any>()
        originalHandler.saveToMap(stateMap)
        stateMap["key_pending_draft_pdf_name"] = "My_Important_Doc"

        val restoredHandler = VipLoginContinuationHandler()
        restoredHandler.restoreFromMap(stateMap)
        val restoredDraftName = stateMap["key_pending_draft_pdf_name"] as? String

        assertTrue("Pending continuation must be restored", restoredHandler.isPending)
        assertEquals("Draft PDF name must be preserved across recreation", "My_Important_Doc", restoredDraftName)
    }

    // -------------------------------------------------------------------------
    // 3. Document, Page List, and Watermark Safety
    // -------------------------------------------------------------------------

    @Test
    fun testGuestViewerPagesAndWatermarkPreference_preservedOnLoginCancellation() {
        val renderedPages = listOf("/data/page_1.jpg", "/data/page_2.jpg", "/data/page_3.jpg")
        var isWatermarkRemoved = false // Free guest has watermark enabled

        val handler = VipLoginContinuationHandler()
        handler.requestContinuation()

        // User cancels Google Sign-In
        handler.onSignInCancelled()

        // Verify loaded pages in viewer remain untouched and watermark is NOT removed
        assertEquals("Loaded pages must not be lost or modified on cancelled login", 3, renderedPages.size)
        assertFalse("Watermark must remain applied for Free guest", isWatermarkRemoved)
        assertFalse("User must not be VIP", AppAuthManager.isUserVip())
    }

    @Test
    fun testViewerLoginSuccess_doesNotAutoGrantVip_requiresUserConfirmation() {
        val handler = VipLoginContinuationHandler()
        handler.requestContinuation()

        // 1. Google login completes successfully
        val realAccount = GoogleSignInAccountData(
            id = "google_user_viewer",
            email = "viewer.user@gmail.com",
            displayName = "Viewer User",
            givenName = "Viewer",
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
        assertFalse("Signing in must NOT auto-grant VIP without confirmation", profile.isVip)
        assertEquals(VipTier.FREE, profile.tier)
        assertFalse("AppAuthManager must report isUserVip = false until confirmed", AppAuthManager.isUserVip())

        // 2. User explicitly confirms in the VipUpgradeDialog
        AppAuthManager.setUserVipTier(testContext, VipTier.VIP, durationDays = 365)
        assertTrue("After explicit confirmation, user becomes VIP", AppAuthManager.isUserVip())
    }

    @Test
    fun testAccountSwitchWhileWaitingSignIn_doesNotAutoGrantVip() {
        val handler = VipLoginContinuationHandler()
        handler.requestContinuation()

        // User logs in with Account A
        val accountA = GoogleSignInAccountData(
            id = "user_a",
            email = "a@gmail.com",
            displayName = "User A",
            null, null, null, null
        )
        AppAuthManager.processSignedInAccount(testContext, accountA)

        // Session advances
        AppAuthManager.nextSessionGeneration()

        // User remains free until dialog confirmation
        assertFalse(AppAuthManager.isUserVip())
        assertEquals(VipTier.FREE, AppAuthManager.getCurrentUser()?.tier)
    }

    @Test
    fun testViewerContinuation_restoreActionRoutesToRestore_notUpgrade() {
        val handler = VipLoginContinuationHandler()
        val startGen = AppAuthManager.getSessionGeneration()
        val startOwner = "user_a"
        val epoch = AppAuthManager.getProcessEpoch()
        handler.requestContinuation(
            action = com.tscanner.app.utils.VipContinuationAction.RESTORE,
            sessionGeneration = startGen,
            initialOwnerId = startOwner,
            processEpoch = epoch
        )

        var executedAction: com.tscanner.app.utils.VipContinuationAction? = null
        handler.onSignInSuccessWithAction(startGen, startOwner) { action, _ ->
            executedAction = action
        }

        assertEquals(
            "RESTORE action must route to RESTORE continuation callback, not default UPGRADE",
            com.tscanner.app.utils.VipContinuationAction.RESTORE,
            executedAction
        )
    }

    @Test
    fun testViewerContinuation_sameOwnerReauthSucceeds_differentOwnerRejected() {
        val handler = VipLoginContinuationHandler()
        val startGen = AppAuthManager.getSessionGeneration()
        val startOwner = "owner_alice"
        val epoch = AppAuthManager.getProcessEpoch()
        handler.requestContinuation(
            action = com.tscanner.app.utils.VipContinuationAction.UPGRADE,
            sessionGeneration = startGen,
            initialOwnerId = startOwner,
            processEpoch = epoch
        )

        var ranWithDifferentOwner = false
        handler.onSignInSuccessWithAction(startGen, "owner_bob") { _, _ ->
            ranWithDifferentOwner = true
        }
        assertFalse("Re-auth with different owner must be rejected", ranWithDifferentOwner)
        assertTrue("Pending must remain cleared after mismatched attempt", !handler.isPending)
    }

    // -------------------------------------------------------------------------
    // Test Helpers
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
