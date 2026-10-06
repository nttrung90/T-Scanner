package com.tscanner.app

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.SharedPreferences
import com.google.android.gms.common.api.ApiException
import com.google.android.gms.common.api.Status
import com.tscanner.app.data.model.UserProfile
import com.tscanner.app.data.repository.DocumentRepo
import com.tscanner.app.utils.AppAuthManager
import com.tscanner.app.utils.GoogleSignInAccountData
import com.tscanner.app.utils.GoogleSignInAccountParser
import com.tscanner.app.utils.GoogleSignInResult
import com.tscanner.app.utils.GoogleSignInResultRouter
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
import java.util.concurrent.atomic.AtomicInteger

class GoogleSignInResultRouterTest {

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

    @Test
    fun testRouteResult_success_returnsSuccessWithAccountData() {
        val dummyIntent = Intent()
        val expectedAccount = GoogleSignInAccountData(
            id = "google_user_123",
            email = "user@example.com",
            displayName = "Test User",
            givenName = "Test",
            familyName = "User",
            photoUrl = "https://example.com/photo.png",
            idToken = null
        )

        val fakeParser = object : GoogleSignInAccountParser {
            override fun parseAccountFromIntent(data: Intent?): GoogleSignInAccountData? = expectedAccount
        }

        val result = GoogleSignInResultRouter.routeResult(
            context = testContext,
            resultCode = Activity.RESULT_OK,
            data = dummyIntent,
            parser = fakeParser
        )

        assertTrue("Result must be Success", result is GoogleSignInResult.Success)
        val success = result as GoogleSignInResult.Success
        assertEquals("google_user_123", success.accountData.id)
        assertEquals("user@example.com", success.accountData.email)
        assertEquals("Test User", success.accountData.displayName)
    }

    @Test
    fun testRouteResult_resultCanceledWithStatus10_returnsFailureWithNeutralConfigError() {
        val dummyIntent = Intent()
        val fakeParser = object : GoogleSignInAccountParser {
            override fun parseAccountFromIntent(data: Intent?): GoogleSignInAccountData? {
                throw ApiException(Status(10, "DEVELOPER_ERROR"))
            }
        }

        // Simulates Google Play Services returning RESULT_CANCELED with status 10 in Intent data
        val result = GoogleSignInResultRouter.routeResult(
            context = testContext,
            resultCode = Activity.RESULT_CANCELED,
            data = dummyIntent,
            parser = fakeParser
        )

        assertTrue("Result must be Failure even though resultCode was RESULT_CANCELED", result is GoogleSignInResult.Failure)
        val failure = result as GoogleSignInResult.Failure
        assertEquals("Status code must be 10", 10, failure.statusCode)
        assertTrue("Error message must mention code 10", failure.errorMessage.contains("10"))
        assertFalse("Neutral error message must not speculate solely about debug SHA-1", failure.errorMessage.contains("SHA-1 debug"))
    }

    @Test
    fun testRouteResult_resultCanceledWithStatus12501_returnsCancelled() {
        val dummyIntent = Intent()
        val fakeParser = object : GoogleSignInAccountParser {
            override fun parseAccountFromIntent(data: Intent?): GoogleSignInAccountData? {
                throw ApiException(Status(12501, "SIGN_IN_CANCELLED"))
            }
        }

        val result = GoogleSignInResultRouter.routeResult(
            context = testContext,
            resultCode = Activity.RESULT_CANCELED,
            data = dummyIntent,
            parser = fakeParser
        )

        assertTrue("Result must be Cancelled for status 12501", result is GoogleSignInResult.Cancelled)
    }

    @Test
    fun testRouteResult_nullIntent_resultCanceled_returnsCancelledSilently() {
        val fakeParser = object : GoogleSignInAccountParser {
            override fun parseAccountFromIntent(data: Intent?): GoogleSignInAccountData? {
                throw AssertionError("Parser should not be called when data is null")
            }
        }

        val result = GoogleSignInResultRouter.routeResult(
            context = testContext,
            resultCode = Activity.RESULT_CANCELED,
            data = null,
            parser = fakeParser
        )

        assertTrue("Null intent with RESULT_CANCELED must yield Cancelled", result is GoogleSignInResult.Cancelled)
    }

    @Test
    fun testRouteResult_nullIntent_resultOk_returnsFailureWithoutCrash() {
        val fakeParser = object : GoogleSignInAccountParser {
            override fun parseAccountFromIntent(data: Intent?): GoogleSignInAccountData? {
                throw AssertionError("Parser should not be called when data is null")
            }
        }

        val result = GoogleSignInResultRouter.routeResult(
            context = testContext,
            resultCode = Activity.RESULT_OK,
            data = null,
            parser = fakeParser
        )

        assertTrue("Null intent with RESULT_OK must yield Failure", result is GoogleSignInResult.Failure)
        val failure = result as GoogleSignInResult.Failure
        assertNull("Status code should be null for missing data", failure.statusCode)
    }

    @Test
    fun testRouteResult_parserThrowsGenericException_returnsFailureWithoutCrash() {
        val dummyIntent = Intent()
        val fakeParser = object : GoogleSignInAccountParser {
            override fun parseAccountFromIntent(data: Intent?): GoogleSignInAccountData? {
                throw IllegalStateException("Corrupt serialized account payload")
            }
        }

        val result = GoogleSignInResultRouter.routeResult(
            context = testContext,
            resultCode = Activity.RESULT_OK,
            data = dummyIntent,
            parser = fakeParser
        )

        assertTrue("Generic parser exception must yield Failure", result is GoogleSignInResult.Failure)
        val failure = result as GoogleSignInResult.Failure
        assertTrue("Error message must contain exception message", failure.errorMessage.contains("Corrupt serialized account payload"))
    }

    @Test
    fun testRouteResult_parserReturnsNull_returnsFailure() {
        val dummyIntent = Intent()
        val fakeParser = object : GoogleSignInAccountParser {
            override fun parseAccountFromIntent(data: Intent?): GoogleSignInAccountData? = null
        }

        val result = GoogleSignInResultRouter.routeResult(
            context = testContext,
            resultCode = Activity.RESULT_OK,
            data = dummyIntent,
            parser = fakeParser
        )

        assertTrue("Null account from parser must yield Failure", result is GoogleSignInResult.Failure)
    }

    @Test
    fun testDispatchResult_guaranteesSingleInvocation_neverCallsTwice() {
        val dummyIntent = Intent()
        val fakeParser = object : GoogleSignInAccountParser {
            override fun parseAccountFromIntent(data: Intent?): GoogleSignInAccountData? {
                throw ApiException(Status(10, "DEVELOPER_ERROR"))
            }
        }

        val successCount = AtomicInteger(0)
        val cancelCount = AtomicInteger(0)
        val errorCount = AtomicInteger(0)

        GoogleSignInResultRouter.dispatchResult(
            context = testContext,
            resultCode = Activity.RESULT_CANCELED,
            data = dummyIntent,
            parser = fakeParser,
            onSuccess = { successCount.incrementAndGet() },
            onCancelled = { cancelCount.incrementAndGet() },
            onError = { errorCount.incrementAndGet() }
        )

        assertEquals("onSuccess must not be called", 0, successCount.get())
        assertEquals("onCancelled must not be called", 0, cancelCount.get())
        assertEquals("onError must be called exactly once", 1, errorCount.get())
    }

    @Test
    fun testAppAuthManager_handleGoogleSignInResult_withStatus10Intent_callsOnErrorAndDoesNotChangeUser() {
        val dummyIntent = Intent()
        val fakeParser = object : GoogleSignInAccountParser {
            override fun parseAccountFromIntent(data: Intent?): GoogleSignInAccountData? {
                throw ApiException(Status(10, "DEVELOPER_ERROR"))
            }
        }

        val attempt = AppAuthManager.createSignInAttemptForTesting()
        var receivedError: String? = null
        var successProfile: UserProfile? = null
        var cancelledCalled = false

        AppAuthManager.handleGoogleSignInResult(
            context = testContext,
            resultCode = Activity.RESULT_CANCELED,
            data = dummyIntent,
            attempt = attempt,
            parser = fakeParser,
            onSuccess = { successProfile = it },
            onCancelled = { cancelledCalled = true },
            onError = { receivedError = it }
        )

        assertNull("onSuccess must not be called on error", successProfile)
        assertFalse("onCancelled must not be called on error 10", cancelledCalled)
        assertNotNull("onError must be called", receivedError)
        assertTrue("Error message must contain 10", receivedError!!.contains("10"))
        assertNull("Current user must remain null after sign-in error", AppAuthManager.currentUser.value)
    }

    @Test
    fun testAppAuthManager_handleGoogleSignInResult_with12501_callsOnCancelled() {
        val dummyIntent = Intent()
        val fakeParser = object : GoogleSignInAccountParser {
            override fun parseAccountFromIntent(data: Intent?): GoogleSignInAccountData? {
                throw ApiException(Status(12501, "SIGN_IN_CANCELLED"))
            }
        }

        val attempt = AppAuthManager.createSignInAttemptForTesting()
        var receivedError: String? = null
        var successProfile: UserProfile? = null
        var cancelledCalled = false

        AppAuthManager.handleGoogleSignInResult(
            context = testContext,
            resultCode = Activity.RESULT_CANCELED,
            data = dummyIntent,
            attempt = attempt,
            parser = fakeParser,
            onSuccess = { successProfile = it },
            onCancelled = { cancelledCalled = true },
            onError = { receivedError = it }
        )

        assertNull("onSuccess must not be called", successProfile)
        assertTrue("onCancelled must be called for 12501", cancelledCalled)
        assertNull("onError must not be called for cancellation", receivedError)
        assertNull("Current user must remain null after cancellation", AppAuthManager.currentUser.value)
    }

    @Test
    fun testAppAuthManager_handleGoogleSignInResult_success_updatesCurrentUserAndCallsOnSuccess() {
        val dummyIntent = Intent()
        val expectedAccount = GoogleSignInAccountData(
            id = "google_user_999",
            email = "user999@gmail.com",
            displayName = "User 999",
            givenName = "User",
            familyName = "999",
            photoUrl = null,
            idToken = null
        )

        val fakeParser = object : GoogleSignInAccountParser {
            override fun parseAccountFromIntent(data: Intent?): GoogleSignInAccountData? = expectedAccount
        }

        val attempt = AppAuthManager.createSignInAttemptForTesting()
        var receivedError: String? = null
        var successProfile: UserProfile? = null
        var cancelledCalled = false

        AppAuthManager.handleGoogleSignInResult(
            context = testContext,
            resultCode = Activity.RESULT_OK,
            data = dummyIntent,
            attempt = attempt,
            parser = fakeParser,
            onSuccess = { successProfile = it },
            onCancelled = { cancelledCalled = true },
            onError = { receivedError = it }
        )

        assertNotNull("onSuccess must be called", successProfile)
        assertFalse("onCancelled must not be called", cancelledCalled)
        assertNull("onError must not be called", receivedError)
        assertEquals("google_user_999", successProfile?.id)
        assertEquals("user999@gmail.com", successProfile?.email)
        assertEquals("User 999", successProfile?.displayName)
        assertEquals("Current user in AppAuthManager must be updated", "google_user_999", AppAuthManager.currentUser.value?.id)
    }

    @Test
    fun testAppAuthManager_handleGoogleSignInResult_withoutAttempt_discardsResult() {
        val dummyIntent = Intent()
        val expectedAccount = GoogleSignInAccountData(
            id = "google_user_unauthorized",
            email = "unauthorized@gmail.com",
            displayName = "Unauthorized",
            givenName = "Unauth",
            familyName = "User",
            photoUrl = null,
            idToken = null
        )
        val fakeParser = object : GoogleSignInAccountParser {
            override fun parseAccountFromIntent(data: Intent?): GoogleSignInAccountData? = expectedAccount
        }

        var successCalled = false
        AppAuthManager.handleGoogleSignInResult(
            context = testContext,
            resultCode = Activity.RESULT_OK,
            data = dummyIntent,
            parser = fakeParser,
            onSuccess = { successCalled = true },
            onCancelled = {},
            onError = {}
        )

        assertFalse("Result without active sign-in attempt must be discarded", successCalled)
        assertNull("Current user must remain null", AppAuthManager.currentUser.value)
    }

    @Test
    fun testAppAuthManager_handleGoogleSignInResult_afterCancelledProgress_discardsResult() {
        val dummyIntent = Intent()
        val expectedAccount = GoogleSignInAccountData(
            id = "google_user_cancelled",
            email = "cancelled@gmail.com",
            displayName = "Cancelled",
            givenName = "Cancelled",
            familyName = "User",
            photoUrl = null,
            idToken = null
        )
        val fakeParser = object : GoogleSignInAccountParser {
            override fun parseAccountFromIntent(data: Intent?): GoogleSignInAccountData? = expectedAccount
        }

        val attempt = AppAuthManager.createSignInAttemptForTesting()
        AppAuthManager.cancelSignInProgress(attempt)

        var successCalled = false
        AppAuthManager.handleGoogleSignInResult(
            context = testContext,
            resultCode = Activity.RESULT_OK,
            data = dummyIntent,
            attempt = attempt,
            parser = fakeParser,
            onSuccess = { successCalled = true },
            onCancelled = {},
            onError = {}
        )

        assertFalse("Result after cancelSignInProgress must be discarded", successCalled)
        assertNull("Current user must remain null", AppAuthManager.currentUser.value)
    }

    private class TestContext(
        private val baseDir: File,
        private val prefs: SharedPreferences
    ) : ContextWrapper(null) {
        override fun getFilesDir(): File = File(baseDir, "files").apply { mkdirs() }
        override fun getCacheDir(): File = File(baseDir, "cache").apply { mkdirs() }
        override fun getApplicationContext(): Context = this
        override fun getSharedPreferences(name: String?, mode: Int): SharedPreferences = prefs
        override fun getPackageName(): String = "com.tscanner.app"
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
