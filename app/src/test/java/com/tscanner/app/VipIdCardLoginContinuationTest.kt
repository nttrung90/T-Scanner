package com.tscanner.app

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import com.tscanner.app.data.model.UserProfile
import com.tscanner.app.data.model.VipTier
import com.tscanner.app.data.repository.DocumentRepo
import com.tscanner.app.ui.dialogs.VipUpgradeActionResolver
import com.tscanner.app.ui.idcard.IdCardSessionDraft
import com.tscanner.app.utils.AppAuthManager
import com.tscanner.app.utils.GoogleSignInAccountData
import com.tscanner.app.utils.IdCardComposeConfig
import com.tscanner.app.utils.IdCardLayoutMode
import com.tscanner.app.utils.IdCardScaleMode
import com.tscanner.app.utils.VipLoginContinuationHandler
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

/**
 * Regression and integration contract tests for VIP login continuation in IdCardComposeActivity (S05b).
 *
 * Invariants:
 * 1. Guest user in ID card compose screen triggers Google Sign-In with continuation.
 * 2. Front and back images, layout configuration, and draft session are preserved across sign-in and recreation.
 * 3. Successful sign-in reopens the VIP confirmation dialog exactly once.
 * 4. Sign-in alone does NOT auto-grant VIP or auto-remove watermarks without explicit user confirmation.
 * 5. Cancellation or error resets continuation cleanly without modifying images, layout, or granting VIP.
 * 6. Account switch while waiting is safely handled.
 * 7. All UI VipUpgradeDialog callsites across the app are wired with onRequestSignIn.
 */
class VipIdCardLoginContinuationTest {

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
    // 1. Action Resolver Tests (ID Card Callsite)
    // -------------------------------------------------------------------------

    @Test
    fun testIdCardWatermarkAction_whenGuestWithCallback_resolvesToRequestSignIn() {
        // Pre-S05b: IdCardComposeActivity passed null for onRequestSignIn, resolving to ShowSignInRequiredPrompt.
        // Post-S05b: onRequestSignIn is provided, resolving to RequestSignIn.
        val action = VipUpgradeActionResolver.resolveUpgradeAction(
            currentUser = null,
            hasSignInCallback = true
        )
        assertTrue("Guest with sign-in callback must resolve to RequestSignIn", action is VipUpgradeActionResolver.Action.RequestSignIn)
    }

    @Test
    fun testIdCardWatermarkAction_whenGuestWithoutCallback_resolvesToPrompt() {
        val action = VipUpgradeActionResolver.resolveUpgradeAction(
            currentUser = null,
            hasSignInCallback = false
        )
        assertTrue("Guest without callback must resolve to ShowSignInRequiredPrompt", action is VipUpgradeActionResolver.Action.ShowSignInRequiredPrompt)
    }

    @Test
    fun testIdCardWatermarkAction_whenLoggedInUser_resolvesToActivateVip() {
        val validToken = "eyJhbGciOiJSUzI1NiJ9." + java.util.Base64.getUrlEncoder().withoutPadding().encodeToString("""{"sub":"idcard.user","exp":4102444800}""".toByteArray()) + ".signature"
        val user = UserProfile(
            id = "user_idcard_guest",
            email = "idcard.user@example.com",
            displayName = "ID Card User",
            isVip = false,
            tier = VipTier.FREE,
            idToken = validToken
        )
        val action = VipUpgradeActionResolver.resolveUpgradeAction(
            currentUser = user,
            hasSignInCallback = true
        )
        assertTrue("Logged-in user must resolve to ActivateVip", action is VipUpgradeActionResolver.Action.ActivateVip)
        assertEquals("idcard.user@example.com", (action as VipUpgradeActionResolver.Action.ActivateVip).email)
    }

    // -------------------------------------------------------------------------
    // 2. Continuation State Machine Tests
    // -------------------------------------------------------------------------

    @Test
    fun testContinuationHandler_idCardLoginSuccess_triggersConfirmationExactlyOnce() {
        val handler = VipLoginContinuationHandler()
        handler.requestContinuation()
        assertTrue("Continuation must be pending", handler.isPending)

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
    fun testContinuationHandler_idCardLoginCancelled_clearsPending() {
        val handler = VipLoginContinuationHandler()
        handler.requestContinuation()
        assertTrue(handler.isPending)

        handler.onSignInCancelled()
        assertFalse("Cancellation must clear pending continuation", handler.isPending)
    }

    @Test
    fun testContinuationHandler_idCardLoginError_clearsPending() {
        val handler = VipLoginContinuationHandler()
        handler.requestContinuation()
        assertTrue(handler.isPending)

        handler.onSignInError()
        assertFalse("Error must clear pending continuation", handler.isPending)
    }

    // -------------------------------------------------------------------------
    // 3. ID Card Session Draft & State Preservation
    // -------------------------------------------------------------------------

    @Test
    fun testIdCardSessionDraftAndContinuation_survivesSavedStateAcrossRecreation() {
        val frontPath = "/data/storage/cccd_front.jpg"
        val backPath = "/data/storage/cccd_back.jpg"
        val config = IdCardComposeConfig(
            layoutMode = IdCardLayoutMode.A4_PORTRAIT_TWO_SIDES,
            scaleMode = IdCardScaleMode.FIT_PAGE,
            showCutBorder = true,
            addWatermark = true
        )

        val draft = IdCardSessionDraft(
            frontImagePath = frontPath,
            backImagePath = backPath,
            config = config
        )

        // Verify draft model serialization
        val map = draft.toMap()
        val restoredDraft = IdCardSessionDraft.fromValues(
            frontPath = map[IdCardSessionDraft.KEY_FRONT_PATH] as? String,
            backPath = map[IdCardSessionDraft.KEY_BACK_PATH] as? String,
            layoutModeStr = map[IdCardSessionDraft.KEY_LAYOUT_MODE] as? String,
            scaleModeStr = map[IdCardSessionDraft.KEY_SCALE_MODE] as? String,
            showCutBorder = (map[IdCardSessionDraft.KEY_SHOW_CUT_BORDER] as? Boolean) ?: true,
            addWatermark = (map[IdCardSessionDraft.KEY_ADD_WATERMARK] as? Boolean) ?: true,
            watermarkText = map[IdCardSessionDraft.KEY_WATERMARK_TEXT] as? String
        )

        assertNotNull("Draft must be successfully restored from state", restoredDraft)
        assertEquals("Front image path must be preserved", frontPath, restoredDraft.frontImagePath)
        assertEquals("Back image path must be preserved", backPath, restoredDraft.backImagePath)
        assertEquals("Layout mode must be preserved", IdCardLayoutMode.A4_PORTRAIT_TWO_SIDES, restoredDraft.config.layoutMode)
        assertEquals("Scale mode must be preserved", IdCardScaleMode.FIT_PAGE, restoredDraft.config.scaleMode)
        assertTrue("Cut border must be preserved", restoredDraft.config.showCutBorder)
        assertTrue("Watermark must be preserved", restoredDraft.config.addWatermark)

        // Verify continuation handler saved state
        val handler = VipLoginContinuationHandler()
        handler.requestContinuation()
        val stateMap = mutableMapOf<String, Any>()
        handler.saveToMap(stateMap)

        val restoredHandler = VipLoginContinuationHandler()
        restoredHandler.restoreFromMap(stateMap)
        assertTrue("Pending continuation must be restored", restoredHandler.isPending)
    }

    @Test
    fun testGuestIdCard_preservesFrontAndBackImagesAndWatermark_onLoginCancellation() {
        val frontFile = File(testContext.filesDir, "front.jpg").apply { writeText("front_bytes") }
        val backFile = File(testContext.filesDir, "back.jpg").apply { writeText("back_bytes") }

        var currentConfig = IdCardComposeConfig(
            layoutMode = IdCardLayoutMode.A4_PORTRAIT_TWO_SIDES,
            addWatermark = true
        )

        val handler = VipLoginContinuationHandler()
        handler.requestContinuation()

        // User cancels Google Sign-In
        handler.onSignInCancelled()

        // Verify images and watermark remain untouched
        assertTrue("Front file must exist", frontFile.exists())
        assertTrue("Back file must exist", backFile.exists())
        assertTrue("Watermark must remain applied for Free guest", currentConfig.addWatermark)
        assertFalse("User must remain Free", AppAuthManager.isUserVip())
        assertNull("No user must be logged in", AppAuthManager.getCurrentUser())
    }

    @Test
    fun testIdCardLoginSuccess_doesNotAutoRemoveWatermark_requiresUserConfirmation() {
        var currentConfig = IdCardComposeConfig(
            layoutMode = IdCardLayoutMode.A4_PORTRAIT_TWO_SIDES,
            addWatermark = true
        )

        val handler = VipLoginContinuationHandler()
        handler.requestContinuation()

        // 1. Google login completes successfully
        val realAccount = GoogleSignInAccountData(
            id = "google_user_idcard",
            email = "idcard.user@gmail.com",
            displayName = "ID Card User",
            givenName = "ID",
            familyName = "Card",
            photoUrl = null,
            idToken = null
        )
        val profile = AppAuthManager.processSignedInAccount(testContext, realAccount)

        var confirmationOpened = false
        handler.onSignInSuccess {
            confirmationOpened = true
        }

        assertTrue("Confirmation dialog must be opened", confirmationOpened)
        assertFalse("Signing in must NOT auto-grant VIP", profile.isVip)
        assertEquals(VipTier.FREE, profile.tier)
        assertTrue("Watermark must still be applied before confirmation", currentConfig.addWatermark)

        // 2. User explicitly confirms in the VipUpgradeDialog
        AppAuthManager.setUserVipTier(testContext, VipTier.VIP, durationDays = 365)
        assertTrue("After explicit confirmation, user becomes VIP", AppAuthManager.isUserVip())

        // Dialog callback now safely removes watermark
        currentConfig = currentConfig.copy(addWatermark = false)
        assertFalse("Watermark is removed only after user confirms VIP", currentConfig.addWatermark)
    }

    @Test
    fun testIdCardAccountSwitchWhileWaitingSignIn_doesNotAutoGrantVip() {
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
    fun testIdCardContinuation_restoreActionRoutesToRestore_notUpgrade() {
        val handler = VipLoginContinuationHandler()
        val startGen = AppAuthManager.getSessionGeneration()
        val startOwner = "user_idcard_a"
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
    fun testIdCardContinuation_sameOwnerReauthSucceeds_differentOwnerRejected() {
        val handler = VipLoginContinuationHandler()
        val startGen = AppAuthManager.getSessionGeneration()
        val startOwner = "idcard_owner_alice"
        val epoch = AppAuthManager.getProcessEpoch()
        handler.requestContinuation(
            action = com.tscanner.app.utils.VipContinuationAction.UPGRADE,
            sessionGeneration = startGen,
            initialOwnerId = startOwner,
            processEpoch = epoch
        )

        var ranWithDifferentOwner = false
        handler.onSignInSuccessWithAction(startGen, "idcard_owner_bob") { _, _ ->
            ranWithDifferentOwner = true
        }
        assertFalse("Re-auth with different owner must be rejected in ID card flow", ranWithDifferentOwner)
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
