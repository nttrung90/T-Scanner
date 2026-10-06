package com.tscanner.app.utils

import android.os.Bundle
import java.io.Serializable
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Immutable token representing a distinct Google Sign-In or Reauthentication attempt.
 *
 * Encapsulates the monotonically increasing request ID and the session generation
 * at the moment the attempt was initiated, ensuring that credentials or results
 * arriving after session invalidation, cancellation, or from an obsolete attempt
 * are rejected.
 *
 * When [expectedOwnerId] is specified (reauthentication), accounts other than the expected owner
 * are rejected before commit or side effects.
 *
 * @property requestId Monotonically increasing request identifier.
 * @property initialSessionGeneration The session generation when the attempt began.
 * @property processEpoch Monotonically unique UUID of the hosting process instance.
 * @property expectedOwnerId The canonical ID or email of the user expected to authenticate.
 */
data class GoogleLoginAttempt(
    val requestId: Long,
    val initialSessionGeneration: Long,
    val processEpoch: String = "",
    val expectedOwnerId: String? = null
) : Serializable {

    @Transient
    private val isFallbackActive = AtomicBoolean(false)

    fun markFallbackActive(): Boolean = isFallbackActive.compareAndSet(false, true)

    fun isFallbackActive(): Boolean = isFallbackActive.get()

    fun writeToBundle(bundle: Bundle) {
        bundle.putLong(KEY_PENDING_SIGN_IN_REQ_ID, requestId)
        bundle.putLong(KEY_PENDING_SIGN_IN_SESSION_GEN, initialSessionGeneration)
        bundle.putString(KEY_PENDING_SIGN_IN_PROCESS_EPOCH, processEpoch)
        bundle.putString(KEY_PENDING_SIGN_IN_EXPECTED_OWNER_ID, expectedOwnerId)
    }

    companion object {
        private const val serialVersionUID = 1L

        const val KEY_PENDING_SIGN_IN_REQ_ID = "key_pending_sign_in_req_id"
        const val KEY_PENDING_SIGN_IN_SESSION_GEN = "key_pending_sign_in_session_gen"
        const val KEY_PENDING_SIGN_IN_PROCESS_EPOCH = "key_pending_sign_in_process_epoch"
        const val KEY_PENDING_SIGN_IN_EXPECTED_OWNER_ID = "key_pending_sign_in_expected_owner_id"

        fun fromValues(
            requestId: Long,
            sessionGeneration: Long,
            processEpoch: String?,
            expectedOwnerId: String? = null
        ): GoogleLoginAttempt? {
            if (requestId <= 0 || sessionGeneration <= 0 || processEpoch.isNullOrEmpty()) {
                return null
            }
            return GoogleLoginAttempt(
                requestId = requestId,
                initialSessionGeneration = sessionGeneration,
                processEpoch = processEpoch,
                expectedOwnerId = expectedOwnerId
            )
        }

        fun fromBundle(bundle: Bundle?): GoogleLoginAttempt? {
            if (bundle == null) return null
            val reqId = bundle.getLong(KEY_PENDING_SIGN_IN_REQ_ID, -1L)
            val sessionGen = bundle.getLong(KEY_PENDING_SIGN_IN_SESSION_GEN, -1L)
            val epoch = bundle.getString(KEY_PENDING_SIGN_IN_PROCESS_EPOCH)
            val expectedOwner = bundle.getString(KEY_PENDING_SIGN_IN_EXPECTED_OWNER_ID)
            return fromValues(reqId, sessionGen, epoch, expectedOwner)
        }
    }
}
