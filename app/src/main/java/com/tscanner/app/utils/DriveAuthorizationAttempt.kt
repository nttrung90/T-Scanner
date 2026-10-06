package com.tscanner.app.utils

import java.io.Serializable

/**
 * Immutable token representing a distinct Google Drive authorization attempt (S04).
 *
 * Encapsulates the unique request ID, the user ID and email at the moment
 * authorization was initiated, and the session generation.
 * Enforces single-consume semantics to prevent duplicate, unsolicited,
 * or interleaved authorization results from granting Drive permissions.
 *
 * Implements [Serializable] to safely survive Activity/Fragment state saving
 * across process recreation without relying on fragile singleton snapshots.
 *
 * @property requestId Monotonically increasing unique request identifier.
 * @property userId Canonical ID of the user who initiated the request.
 * @property userEmail Verified email of the user who initiated the request.
 * @property sessionGeneration The session generation at the moment of request creation.
 */
data class DriveAuthorizationAttempt(
    val requestId: Long,
    val userId: String,
    val userEmail: String,
    val sessionGeneration: Long,
    val processEpoch: String = ""
) : Serializable {

    @Volatile
    private var consumed: Boolean = false

    /**
     * Atomically consumes this authorization attempt once.
     * Returns true if successfully consumed on this call, false if already consumed.
     */
    @Synchronized
    fun consume(): Boolean {
        if (consumed) return false
        consumed = true
        return true
    }

    /**
     * Returns true if this attempt has already been consumed.
     */
    fun isConsumed(): Boolean = consumed

    companion object {
        private const val serialVersionUID = 1L
    }
}
