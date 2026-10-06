package com.tscanner.app

import android.os.Bundle
import com.tscanner.app.utils.AppAuthManager
import com.tscanner.app.utils.GoogleLoginAttempt
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Unit tests verifying [GoogleLoginAttempt] bundle serialization, parsing and restoration,
 * as well as attempt validation in [AppAuthManager] under process epoch constraints (F02).
 */
class GoogleLoginAttemptSerializationTest {

    @Before
    fun setUp() {
        androidx.arch.core.executor.ArchTaskExecutor.getInstance().setDelegate(object : androidx.arch.core.executor.TaskExecutor() {
            override fun executeOnDiskIO(r: Runnable) = r.run()
            override fun postToMainThread(r: Runnable) = r.run()
            override fun isMainThread() = true
        })
        AppAuthManager.resetForTesting()
    }

    @After
    fun tearDown() {
        AppAuthManager.resetForTesting()
        androidx.arch.core.executor.ArchTaskExecutor.getInstance().setDelegate(null)
    }

    @Test
    fun writeToBundle_executesSafely() {
        val attempt = GoogleLoginAttempt(requestId = 42L, initialSessionGeneration = 7L, processEpoch = "test-epoch-uuid")
        val bundle = Bundle()
        attempt.writeToBundle(bundle)
        // Verify no exception on writing to Bundle
    }

    @Test
    fun fromBundle_nullBundle_returnsNull() {
        assertNull(GoogleLoginAttempt.fromBundle(null))
    }

    @Test
    fun fromBundle_stubBundleWithoutEpoch_returnsNull() {
        // On JVM with mockable android.jar, stub bundle returns default null/0, which triggers safe rejection
        val bundle = Bundle()
        assertNull(GoogleLoginAttempt.fromBundle(bundle))
    }

    @Test
    fun fromValues_valid_createsAttempt() {
        val attempt = GoogleLoginAttempt.fromValues(requestId = 42L, sessionGeneration = 7L, processEpoch = "test-epoch-uuid")
        assertNotNull("Valid parameters must produce non-null attempt", attempt)
        assertEquals(42L, attempt?.requestId)
        assertEquals(7L, attempt?.initialSessionGeneration)
        assertEquals("test-epoch-uuid", attempt?.processEpoch)
    }

    @Test
    fun fromValues_missingOrEmptyEpoch_returnsNull() {
        assertNull("Null epoch must return null", GoogleLoginAttempt.fromValues(1L, 1L, null))
        assertNull("Empty epoch must return null", GoogleLoginAttempt.fromValues(1L, 1L, ""))
    }

    @Test
    fun fromValues_invalidRequestId_returnsNull() {
        assertNull("Negative requestId must return null", GoogleLoginAttempt.fromValues(-1L, 1L, "valid-epoch"))
        assertNull("Zero requestId must return null", GoogleLoginAttempt.fromValues(0L, 1L, "valid-epoch"))
    }

    @Test
    fun fromValues_invalidSessionGen_returnsNull() {
        assertNull("Negative sessionGen must return null", GoogleLoginAttempt.fromValues(1L, -1L, "valid-epoch"))
        assertNull("Zero sessionGen must return null", GoogleLoginAttempt.fromValues(1L, 0L, "valid-epoch"))
    }

    @Test
    fun isAttemptValid_sameProcessRecreatedToken_returnsTrue() {
        val active = AppAuthManager.createSignInAttemptForTesting()
        val restored = GoogleLoginAttempt.fromValues(active.requestId, active.initialSessionGeneration, active.processEpoch)
        assertNotNull(restored)
        assertTrue("Restored token in same process epoch must be valid", AppAuthManager.isAttemptValid(restored))
    }

    @Test
    fun isAttemptValid_recreatedTokenWithEmptyEpoch_returnsFalse() {
        val active = AppAuthManager.createSignInAttemptForTesting()
        val emptyEpochToken = GoogleLoginAttempt(active.requestId, active.initialSessionGeneration, processEpoch = "")
        assertFalse("Token with empty epoch must NOT be valid", AppAuthManager.isAttemptValid(emptyEpochToken))
    }

    @Test
    fun isAttemptValid_recreatedTokenWithDifferentEpoch_returnsFalse() {
        val active = AppAuthManager.createSignInAttemptForTesting()
        val foreignEpochToken = GoogleLoginAttempt(active.requestId, active.initialSessionGeneration, processEpoch = "foreign-uuid")
        assertFalse("Token with foreign epoch must NOT be valid", AppAuthManager.isAttemptValid(foreignEpochToken))
    }
}
