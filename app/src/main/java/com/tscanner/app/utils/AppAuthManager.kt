package com.tscanner.app.utils

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.util.Log
import androidx.annotation.VisibleForTesting
import androidx.credentials.ClearCredentialStateRequest
import androidx.credentials.CredentialManager
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.credentials.exceptions.GetCredentialException
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.android.gms.common.api.ApiException
import com.google.android.libraries.identity.googleid.GetGoogleIdOption
import com.google.android.libraries.identity.googleid.GetSignInWithGoogleOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import com.tscanner.app.R
import com.tscanner.app.data.model.UserProfile
import com.tscanner.app.data.repository.DocumentRepo
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume
import org.json.JSONObject

open class AuthReauthenticationException(message: String) : Exception(message)
class AccountMismatchException(message: String) : AuthReauthenticationException(message)
class ExpiredCredentialException(message: String) : AuthReauthenticationException(message)

object AppAuthManager {

    private const val TAG = "AppAuthManager"
    private const val PREFS_NAME = "tscanner_auth_prefs"
    private const val KEY_USER_PROFILE = "key_user_profile"
    private const val KEY_VIP_ACCOUNT_PREFIX = "vip_account_"

    @VisibleForTesting
    var postAuthSyncDispatcher: CoroutineDispatcher? = null

    fun interface GoogleSignOutProvider {
        fun signOut(context: Context): com.google.android.gms.tasks.Task<Void>?
    }

    private class DefaultGoogleSignOutProvider : GoogleSignOutProvider {
        override fun signOut(context: Context): com.google.android.gms.tasks.Task<Void>? {
            val gso = GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN).build()
            return GoogleSignIn.getClient(context, gso).signOut()
        }
    }

    @VisibleForTesting
    var googleSignOutProvider: GoogleSignOutProvider = DefaultGoogleSignOutProvider()

    fun interface CredentialClearProvider {
        suspend fun clearCredentialState(context: Context)
    }

    private class DefaultCredentialClearProvider : CredentialClearProvider {
        override suspend fun clearCredentialState(context: Context) {
            val credentialManager = CredentialManager.create(context)
            credentialManager.clearCredentialState(ClearCredentialStateRequest())
        }
    }

    @VisibleForTesting
    var credentialClearProvider: CredentialClearProvider = DefaultCredentialClearProvider()

    @VisibleForTesting
    var googleSignOutAction: (suspend (Context) -> Unit)? = null

    /**
     * Web Client ID from Google Cloud Console (OAuth 2.0 Web Application Client ID, project: t-scanner-508413).
     */
    var webClientId: String = "284912111014-s5jqvjdden73n6mqc3kofs2c67dbf58d.apps.googleusercontent.com"

    private val _currentUser = MutableLiveData<UserProfile?>(null)
    val currentUser: LiveData<UserProfile?> = _currentUser

    private val sessionGeneration = java.util.concurrent.atomic.AtomicLong(1L)
    private val isSignInInProgress = java.util.concurrent.atomic.AtomicBoolean(false)
    private val signInRequestGeneration = java.util.concurrent.atomic.AtomicLong(0L)
    private val driveRequestGeneration = java.util.concurrent.atomic.AtomicLong(0L)
    private val activeLoginAttempt = java.util.concurrent.atomic.AtomicReference<GoogleLoginAttempt?>(null)
    private val pendingDriveAuthAttempt = java.util.concurrent.atomic.AtomicReference<DriveAuthorizationAttempt?>(null)
    private val pendingDriveAttempts = mutableMapOf<Long, DriveAuthorizationAttempt>()
    private val consumedDriveRequestIds = mutableSetOf<Long>()
    @Volatile
    private var processEpoch: String = java.util.UUID.randomUUID().toString()
    private val authStateLock = Any()

    fun getProcessEpoch(): String = processEpoch

    data class DriveAuthSessionSnapshot(
        val userId: String,
        val userEmail: String,
        val sessionGeneration: Long
    )

    fun isSignInInProgress(): Boolean = activeLoginAttempt.get() != null || isSignInInProgress.get()

    fun getActiveSignInAttempt(): GoogleLoginAttempt? = activeLoginAttempt.get()

    private fun matchesActiveAttemptLocked(token: GoogleLoginAttempt?): Boolean {
        if (token == null) return false
        val active = activeLoginAttempt.get() ?: return false
        val currentProcessEpoch = processEpoch
        return active.requestId == token.requestId &&
               active.initialSessionGeneration == token.initialSessionGeneration &&
               active.expectedOwnerId == token.expectedOwnerId &&
               sessionGeneration.get() == token.initialSessionGeneration &&
               token.processEpoch.isNotEmpty() &&
               active.processEpoch.isNotEmpty() &&
               token.processEpoch == currentProcessEpoch &&
               active.processEpoch == currentProcessEpoch
    }

    private fun clearActiveAttemptIfMatchingLocked(token: GoogleLoginAttempt?): Boolean {
        if (token == null) return false
        val active = activeLoginAttempt.get() ?: return false
        val currentProcessEpoch = processEpoch
        if (active.requestId == token.requestId &&
            active.initialSessionGeneration == token.initialSessionGeneration &&
            active.expectedOwnerId == token.expectedOwnerId &&
            token.processEpoch.isNotEmpty() &&
            active.processEpoch.isNotEmpty() &&
            token.processEpoch == currentProcessEpoch &&
            active.processEpoch == currentProcessEpoch
        ) {
            activeLoginAttempt.set(null)
            isSignInInProgress.set(false)
            return true
        }
        return false
    }

    fun cancelSignInProgress(token: GoogleLoginAttempt? = null) {
        synchronized(authStateLock) {
            if (token == null) {
                activeLoginAttempt.set(null)
                isSignInInProgress.set(false)
            } else {
                clearActiveAttemptIfMatchingLocked(token)
            }
        }
    }

    fun isAttemptValid(token: GoogleLoginAttempt?): Boolean {
        if (token == null) return false
        synchronized(authStateLock) {
            return matchesActiveAttemptLocked(token)
        }
    }

    fun getSessionGeneration(): Long = sessionGeneration.get()

    fun getSessionToken(): String? = _currentUser.value?.idToken

    fun getSessionOwnerId(): String? = _currentUser.value?.id

    fun nextSessionGeneration(): Long {
        synchronized(authStateLock) {
            activeLoginAttempt.set(null)
            isSignInInProgress.set(false)
            pendingDriveAttempts.clear()
            consumedDriveRequestIds.clear()
            pendingDriveAuthAttempt.set(null)
            return sessionGeneration.incrementAndGet()
        }
    }

    private fun notifyUserSessionChanged(context: Context?, previousUserId: String?) {
        val nextGen = sessionGeneration.incrementAndGet()
        Log.d(TAG, "User session changed to generation $nextGen (previousUserId=$previousUserId)")
        CloudBackupManager.cancelActiveCloudTasks(context, previousUserId)
    }

    private var isInitialized = false

    /**
     * Initializes authentication state from persistent storage.
     */
    fun init(context: Context) {
        if (isInitialized) return
        val prefs = getPrefs(context)
        val savedJson = prefs.getString(KEY_USER_PROFILE, null)
        if (!savedJson.isNullOrEmpty()) {
            try {
                var profile = parseUserProfileFromJson(savedJson)
                // If profile was stored with legacy email-as-id, attempt to upgrade to canonical sub:
                val canonicalSub = extractSubFromIdToken(profile.idToken)
                if (!canonicalSub.isNullOrBlank() && canonicalSub != profile.id && profile.id == profile.email) {
                    Log.i(TAG, "Detected legacy profile with email as id; upgrading to canonical sub: $canonicalSub")
                    profile = profile.copy(id = canonicalSub)
                    migrateLegacyIdentity(context, canonicalSub, profile.email)
                    saveUser(context, profile)
                } else if (profile.id != profile.email && profile.email.isNotBlank()) {
                    migrateLegacyIdentity(context, profile.id, profile.email)
                }
                loadVipForUser(context, profile)
                _currentUser.value = profile
                Log.d(TAG, "Restored logged-in user: ${profile.email}, isVip=${profile.isVipActive}")
                checkAndEnforceVipExpiration(context)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to restore user profile", e)
            }
        }
        // Clean any stale mock drive IDs
        CloudBackupManager.cleanMockDriveBackups(context)
        isInitialized = true
    }

    fun isLoggedIn(): Boolean = _currentUser.value != null

    fun getCurrentUser(): UserProfile? = _currentUser.value

    fun isUserVip(): Boolean = _currentUser.value?.isVipActive == true

    const val DRIVE_FILE_SCOPE = "https://www.googleapis.com/auth/drive.file"

    @VisibleForTesting
    var drivePermissionOverrideForTesting: Boolean? = null

    fun hasDrivePermission(context: Context): Boolean {
        drivePermissionOverrideForTesting?.let { return it }
        val user = _currentUser.value ?: return false
        val account = GoogleSignIn.getLastSignedInAccount(context) ?: return false
        if (!account.email.equals(user.email, ignoreCase = true)) {
            return false
        }
        return GoogleSignIn.hasPermissions(account, com.google.android.gms.common.api.Scope(DRIVE_FILE_SCOPE))
    }

    fun buildGoogleDriveSignInOptions(user: UserProfile?): GoogleSignInOptions {
        val builder = GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
            .requestScopes(com.google.android.gms.common.api.Scope(DRIVE_FILE_SCOPE))
            .requestEmail()
            .requestProfile()
        if (user != null && user.email.isNotBlank()) {
            builder.setAccountName(user.email)
        }
        return builder.build()
    }

    fun createDriveAuthorizationAttempt(): DriveAuthorizationAttempt? {
        val user = _currentUser.value ?: return null
        if (user.email.isBlank()) return null
        synchronized(authStateLock) {
            val reqId = driveRequestGeneration.incrementAndGet()
            val attempt = DriveAuthorizationAttempt(
                requestId = reqId,
                userId = user.id,
                userEmail = user.email,
                sessionGeneration = sessionGeneration.get(),
                processEpoch = processEpoch
            )
            pendingDriveAttempts[reqId] = attempt
            pendingDriveAuthAttempt.set(attempt)
            return attempt
        }
    }

    fun cancelDriveAuthorizationAttempt(attempt: DriveAuthorizationAttempt?) {
        if (attempt == null) return
        synchronized(authStateLock) {
            if (attempt.processEpoch.isNotEmpty() && attempt.processEpoch != processEpoch) {
                return
            }
            pendingDriveAttempts.remove(attempt.requestId)
            consumedDriveRequestIds.add(attempt.requestId)
            if (pendingDriveAuthAttempt.get()?.requestId == attempt.requestId) {
                pendingDriveAuthAttempt.set(null)
            }
        }
    }

    fun getGoogleDriveSignInIntent(
        context: Context,
        attempt: DriveAuthorizationAttempt? = null,
        onAttemptCreated: ((DriveAuthorizationAttempt) -> Unit)? = null
    ): Intent {
        val user = _currentUser.value
        val targetAttempt = attempt ?: createDriveAuthorizationAttempt()
        if (targetAttempt != null) {
            onAttemptCreated?.invoke(targetAttempt)
            pendingDriveAuthAttempt.set(targetAttempt)
        }
        val options = buildGoogleDriveSignInOptions(user)
        return GoogleSignIn.getClient(context, options).signInIntent
    }

    fun getGoogleDriveSignInIntent(context: Context): Intent =
        getGoogleDriveSignInIntent(context, attempt = null, onAttemptCreated = null)

    /**
     * Handles result from Drive authorization consent intent with lifecycle-aware resultCode (V06/S04).
     * Dispatches through DriveAuthorizationResultRouter to guarantee:
     * 1. Errors in RESULT_CANCELED are parsed and reported.
     * 2. Pure cancellation is handled quietly.
     * 3. Account mismatch is rejected.
     * 4. Stale sessions / user switched while consent open are discarded.
     * 5. Callbacks are invoked at most once (single invocation guarantee).
     * 6. Requires a valid, active request token; unsolicited/uncorrelated results without a request are safely discarded (R04).
     * 7. Single-consume semantics: an attempt cannot be consumed more than once across copies and reconstructions.
     */
    fun handleDrivePermissionResult(
        context: Context,
        resultCode: Int,
        data: Intent?,
        attempt: DriveAuthorizationAttempt?,
        parser: GoogleSignInAccountParser = DefaultGoogleSignInAccountParser(),
        onSuccess: () -> Unit,
        onCancelled: () -> Unit = {},
        onError: (String) -> Unit
    ) {
        val currentUser = _currentUser.value
        if (currentUser == null) {
            onError("Chưa đăng nhập tài khoản")
            return
        }

        if (attempt == null) {
            Log.w(TAG, "Discarding Drive authorization result: missing explicit host token")
            return
        }

        if (attempt.processEpoch.isBlank() || attempt.processEpoch != processEpoch) {
            Log.w(TAG, "Discarding Drive authorization result: token belongs to an obsolete process epoch (tokenEpoch=${attempt.processEpoch}, currentEpoch=$processEpoch)")
            return
        }

        val currentGen = sessionGeneration.get()
        if (currentGen != attempt.sessionGeneration) {
            Log.w(TAG, "Discarding Drive authorization result: session generation changed (current=$currentGen, attempt=${attempt.sessionGeneration})")
            return
        }

        if (currentUser.id != attempt.userId || !currentUser.email.equals(attempt.userEmail, ignoreCase = true)) {
            Log.w(TAG, "Discarding Drive authorization result: account mismatch (current=${currentUser.email}, attempt=${attempt.userEmail})")
            val msg = context.getString(R.string.drive_permission_account_mismatch)
            onError(msg)
            return
        }

        val consumedLocally: Boolean = synchronized(authStateLock) {
            if (consumedDriveRequestIds.contains(attempt.requestId)) {
                Log.w(TAG, "Discarding duplicate Drive authorization result: attempt ${attempt.requestId} was already consumed in registry")
                return@synchronized false
            }
            val pending = pendingDriveAttempts[attempt.requestId]
            if (pending == null) {
                Log.w(TAG, "Discarding Drive authorization result: attempt ${attempt.requestId} is not in pending registry")
                return@synchronized false
            }
            if (pending.processEpoch != attempt.processEpoch ||
                pending.sessionGeneration != attempt.sessionGeneration ||
                pending.userId != attempt.userId ||
                !pending.userEmail.equals(attempt.userEmail, ignoreCase = true)
            ) {
                Log.w(TAG, "Discarding Drive authorization result: attempt ${attempt.requestId} metadata mismatch in registry")
                return@synchronized false
            }
            pendingDriveAttempts.remove(attempt.requestId)
            consumedDriveRequestIds.add(attempt.requestId)
            attempt.consume()
            true
        }

        if (!consumedLocally) {
            return
        }

        DriveAuthorizationResultRouter.dispatchResult(
            context = context,
            resultCode = resultCode,
            data = data,
            expectedUserEmail = attempt.userEmail,
            currentSessionGeneration = currentGen,
            expectedSessionGeneration = attempt.sessionGeneration,
            parser = parser,
            onSuccess = {
                Log.i(TAG, "Drive permission successfully granted and validated for ${currentUser.email} (requestId=${attempt.requestId})")
                onSuccess()
            },
            onCancelled = onCancelled,
            onError = onError
        )
    }

    /**
     * Backward-compatible overload without attempt parameter for legacy callers.
     * Rejects execution if caller does not provide an explicit attempt token (no singleton borrowing).
     */
    @Deprecated("Callers must provide explicit attempt token from launch", ReplaceWith("handleDrivePermissionResult(context, resultCode, data, attempt, parser, onSuccess, onCancelled, onError)"))
    fun handleDrivePermissionResult(
        context: Context,
        resultCode: Int,
        data: Intent?,
        parser: GoogleSignInAccountParser = DefaultGoogleSignInAccountParser(),
        onSuccess: () -> Unit,
        onCancelled: () -> Unit = {},
        onError: (String) -> Unit
    ) {
        handleDrivePermissionResult(
            context = context,
            resultCode = resultCode,
            data = data,
            attempt = null, // Do NOT borrow singleton request!
            parser = parser,
            onSuccess = onSuccess,
            onCancelled = onCancelled,
            onError = onError
        )
    }

    /**
     * Backward-compatible overload for existing callers without resultCode.
     * Rejects execution if caller does not provide an explicit attempt token (no singleton borrowing).
     */
    @Deprecated("Callers must provide explicit attempt token from launch")
    fun handleDrivePermissionResult(
        context: Context,
        data: Intent?,
        onSuccess: () -> Unit,
        onError: (String) -> Unit
    ) {
        handleDrivePermissionResult(
            context = context,
            resultCode = if (data != null) Activity.RESULT_OK else Activity.RESULT_CANCELED,
            data = data,
            attempt = null, // Do NOT borrow singleton request!
            parser = DefaultGoogleSignInAccountParser(),
            onSuccess = onSuccess,
            onCancelled = {},
            onError = onError
        )
    }

    /**
     * Backward-compatible overload with injectable parser for tests.
     */
    fun handleDrivePermissionResult(
        context: Context,
        data: Intent?,
        parser: GoogleSignInAccountParser,
        onSuccess: () -> Unit,
        onError: (String) -> Unit
    ) {
        handleDrivePermissionResult(
            context = context,
            resultCode = if (data != null) Activity.RESULT_OK else Activity.RESULT_CANCELED,
            data = data,
            attempt = null,
            parser = parser,
            onSuccess = onSuccess,
            onCancelled = {},
            onError = onError
        )
    }

    /**
     * Unified orchestration for post-login / post-authorization sync (V09).
     * Enqueues batch backup for unsynced documents and syncs remote Drive catalog.
     *
     * Invariants (V03):
     * 1. Identity login and Drive authorization are decoupled.
     * 2. Free users never trigger cloud backup or catalog sync.
     * 3. VIP users who have not granted Drive permission do NOT enqueue backup,
     *    avoiding guaranteed authorization failure.
     */
    /**
     * Executes post-authorization synchronization:
     * - Uploads unsynced local documents to Drive (VIP only).
     * - Synchronizes Drive catalog to local database (VIP only).
     *
     * Invariants (V03 & S07):
     * 1. Identity login and Drive authorization are decoupled.
     * 2. Free users never trigger cloud backup or catalog sync.
     * 3. VIP users who have not granted Drive permission do NOT enqueue backup,
     *    avoiding guaranteed authorization failure.
     * 4. S07: Preconditions are checked synchronously on caller thread; I/O operations
     *    (Room DB query, batch snapshot copy, and catalog sync) are dispatched to
     *    [ioDispatcher] on the active session scope so the UI caller thread is NEVER blocked.
     * 5. S07: Captures expected session generation and expected user ID; aborts gracefully
     *    if user signed out or switched before or during background dispatch.
     */
    fun runPostAuthorizationSync(
        context: Context,
        hasDrivePermissionProvider: (Context) -> Boolean = { hasDrivePermission(it) },
        onResult: ((SyncCatalogResult) -> Unit)? = null
    ) {
        runPostAuthorizationSyncInternal(
            context = context,
            hasDrivePermissionProvider = hasDrivePermissionProvider,
            ioDispatcher = postAuthSyncDispatcher ?: CloudBackupManager.ioDispatcher,
            onResult = onResult
        )
    }

    /**
     * Convenience overload for UI callers passing only context and onResult.
     */
    fun runPostAuthorizationSync(
        context: Context,
        onResult: (SyncCatalogResult) -> Unit
    ) {
        runPostAuthorizationSync(context, { hasDrivePermission(it) }, onResult)
    }

    /**
     * Test-friendly overload allowing explicit dispatcher specification.
     */
    fun runPostAuthorizationSync(
        context: Context,
        hasDrivePermissionProvider: (Context) -> Boolean = { hasDrivePermission(it) },
        ioDispatcher: CoroutineDispatcher,
        onResult: ((SyncCatalogResult) -> Unit)? = null
    ) {
        runPostAuthorizationSyncInternal(
            context = context,
            hasDrivePermissionProvider = hasDrivePermissionProvider,
            ioDispatcher = ioDispatcher,
            onResult = onResult
        )
    }

    private fun runPostAuthorizationSyncInternal(
        context: Context,
        hasDrivePermissionProvider: (Context) -> Boolean,
        ioDispatcher: CoroutineDispatcher,
        onResult: ((SyncCatalogResult) -> Unit)?
    ) {
        val user = _currentUser.value
        if (user == null) {
            Log.d(TAG, "Post-authorization sync skipped: user not signed in")
            onResult?.invoke(SyncCatalogResult.Skipped(SyncCatalogResult.Skipped.REASON_NOT_LOGGED_IN))
            return
        }
        if (!user.isVipActive) {
            Log.d(TAG, "Post-authorization sync skipped: user is not VIP")
            onResult?.invoke(SyncCatalogResult.Skipped(SyncCatalogResult.Skipped.REASON_NOT_VIP))
            return
        }
        if (!hasDrivePermissionProvider(context)) {
            Log.d(TAG, "Post-authorization sync skipped: VIP user has not granted Drive permission")
            onResult?.invoke(SyncCatalogResult.AuthRequired("Chưa cấp quyền Google Drive"))
            return
        }

        val expectedUserId = user.id
        val expectedSessionGen = sessionGeneration.get()
        val scope = CloudBackupManager.getOrCreateSessionScope()

        scope.launch(ioDispatcher) {
            if (!isActive || sessionGeneration.get() != expectedSessionGen || _currentUser.value?.id != expectedUserId) {
                Log.d(TAG, "Post-authorization sync aborted: session changed or user signed out before I/O")
                return@launch
            }

            try {
                val repo = DocumentRepo.getInstance(context)
                val unsynced = repo.getUnsyncedDocuments(expectedUserId)
                if (unsynced.isNotEmpty() && isActive && sessionGeneration.get() == expectedSessionGen && _currentUser.value?.id == expectedUserId) {
                    CloudBackupManager.enqueueBatchBackup(context, unsynced, expectedUserId, expectedSessionGen)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to query or enqueue unsynced documents during post-authorization sync", e)
            }

            if (isActive && sessionGeneration.get() == expectedSessionGen && _currentUser.value?.id == expectedUserId) {
                CloudBackupManager.syncCatalogFromDriveWithResult(context) { result ->
                    onResult?.invoke(result)
                }
            }
        }
    }

    /**
     * Checks if VIP subscription has expired. If expired, automatically downgrades to FREE.
     * Returns true if user was downgraded.
     */
    fun checkAndEnforceVipExpiration(context: Context): Boolean {
        val user = _currentUser.value ?: return false
        if (user.isVip && user.vipExpiresAt != null && System.currentTimeMillis() > user.vipExpiresAt!!) {
            Log.d(TAG, "VIP subscription has expired. Downgrading to FREE.")
            user.isVip = false
            user.tier = com.tscanner.app.data.model.VipTier.FREE
            user.vipPurchasedAt = null
            user.vipExpiresAt = null
            saveVipForUser(context, user.id, user.tier, null, null)
            saveUser(context, user)
            _currentUser.postValue(user)
            return true
        }
        return false
    }

    /**
     * Builds Google Sign-In options strictly for identity authentication.
     * Crucially does NOT request Drive scopes (DRIVE_FILE_SCOPE), keeping
     * identity login independent from Drive authorization (V03).
     */
    fun buildGoogleSignInOptions(): GoogleSignInOptions {
        return GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
            .requestIdToken(webClientId)
            .requestEmail()
            .requestProfile()
            .build()
    }

    /**
     * Creates Google Sign-In intent as reliable fallback if Credential Manager fails.
     */
    fun getGoogleSignInIntent(context: Context): Intent {
        val gso = buildGoogleSignInOptions()
        return GoogleSignIn.getClient(context, gso).signInIntent
    }

    /**
     * Handles result from Google Sign-In activity result via GoogleSignInResultRouter.
     * Routes both success and failure intents (even when resultCode == RESULT_CANCELED),
     * ensuring that GMS status codes (e.g. 10, 12500) are not swallowed.
     */
    /**
     * Handles result from Google Sign-In activity result via GoogleSignInResultRouter.
     * Routes both success and failure intents (even when resultCode == RESULT_CANCELED),
     * ensuring that GMS status codes (e.g. 10, 12500) are not swallowed.
     */
    fun handleGoogleSignInResult(
        context: Context,
        resultCode: Int,
        data: Intent?,
        attempt: GoogleLoginAttempt?,
        parser: GoogleSignInAccountParser = DefaultGoogleSignInAccountParser(),
        onSuccess: (UserProfile) -> Unit,
        onCancelled: () -> Unit = {},
        onError: (String) -> Unit
    ) {
        if (attempt == null || !isAttemptValid(attempt)) {
            Log.w(TAG, "Discarding Google Sign-In result: attempt is null, cancelled, stale, or mismatch (attempt=$attempt, active=${activeLoginAttempt.get()})")
            return
        }

        try {
            GoogleSignInResultRouter.dispatchResult(
                context = context,
                resultCode = resultCode,
                data = data,
                parser = parser,
                onSuccess = { accountData ->
                    val profile = try {
                        commitSignedInAccount(context, attempt, accountData)
                    } catch (e: kotlin.coroutines.cancellation.CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to commit signed-in account in classic handler", e)
                        onError(e.localizedMessage ?: context.getString(R.string.google_signin_error_no_account))
                        null
                    }
                    if (profile != null) {
                        try {
                            onSuccess(profile)
                        } catch (e: kotlin.coroutines.cancellation.CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            Log.e(TAG, "Exception in classic onSuccess callback", e)
                        }
                    } else {
                        Log.w(TAG, "Sign-in commit rejected due to invalidated attempt/session")
                    }
                },
                onCancelled = {
                    synchronized(authStateLock) {
                        clearActiveAttemptIfMatchingLocked(attempt)
                    }
                    onCancelled()
                },
                onError = { errorMsg ->
                    synchronized(authStateLock) {
                        clearActiveAttemptIfMatchingLocked(attempt)
                    }
                    onError(errorMsg)
                }
            )
        } finally {
            synchronized(authStateLock) {
                clearActiveAttemptIfMatchingLocked(attempt)
            }
        }
    }

    /**
     * Backward-compatible entry point for existing callers or tests without attempt token.
     * Rejects execution if caller does not provide an explicit attempt token (no singleton borrowing).
     */
    @Deprecated("Callers must provide explicit attempt token from launch", ReplaceWith("handleGoogleSignInResult(context, resultCode, data, attempt, parser, onSuccess, onCancelled, onError)"))
    fun handleGoogleSignInResult(
        context: Context,
        resultCode: Int,
        data: Intent?,
        parser: GoogleSignInAccountParser = DefaultGoogleSignInAccountParser(),
        onSuccess: (UserProfile) -> Unit,
        onCancelled: () -> Unit = {},
        onError: (String) -> Unit
    ) {
        handleGoogleSignInResult(
            context = context,
            resultCode = resultCode,
            data = data,
            attempt = null,
            parser = parser,
            onSuccess = onSuccess,
            onCancelled = onCancelled,
            onError = onError
        )
    }

    /**
     * Overloaded backward-compatible entry point for existing callers or tests.
     * Rejects execution if caller does not provide an explicit attempt token (no singleton borrowing).
     */
    @Deprecated("Callers must provide explicit attempt token from launch")
    fun handleGoogleSignInResult(
        context: Context,
        data: Intent?,
        onSuccess: (UserProfile) -> Unit,
        onError: (String) -> Unit
    ) {
        handleGoogleSignInResult(
            context = context,
            resultCode = Activity.RESULT_OK,
            data = data,
            attempt = null,
            parser = DefaultGoogleSignInAccountParser(),
            onSuccess = onSuccess,
            onCancelled = {},
            onError = onError
        )
    }

    /**
     * Converts parsed GoogleSignInAccountData into a UserProfile and initializes session.
     */
    fun processSignedInAccount(
        context: Context,
        account: GoogleSignInAccountData
    ): UserProfile {
        synchronized(authStateLock) {
            val profile = processSignedInAccountInternal(context, account)
            activeLoginAttempt.set(null)
            isSignInInProgress.set(false)
            return profile
        }
    }

    private fun processSignedInAccountInternal(
        context: Context,
        account: GoogleSignInAccountData
    ): UserProfile {
        val email = account.email ?: ""
        val canonicalId = resolveCanonicalGoogleId(account.id, account.idToken, email)
        val profile = UserProfile(
            id = canonicalId,
            email = email,
            displayName = account.displayName ?: "Google User",
            givenName = account.givenName,
            familyName = account.familyName,
            photoUrl = account.photoUrl,
            idToken = account.idToken,
            isVip = false,
            tier = com.tscanner.app.data.model.VipTier.FREE
        )
        // Migrate any legacy entitlement or documents under email to canonicalId
        migrateLegacyIdentity(context, canonicalId, email)
        // Claim any unowned guest documents created before login
        DocumentRepo.getInstance(context).claimGuestDocuments(canonicalId)
        // Load VIP status strictly bound to this account ID
        loadVipForUser(context, profile)
        val saved = saveUser(context, profile)
        if (!saved) {
            Log.e(TAG, "Failed to persist user profile during login commit")
            throw IllegalStateException("Không thể lưu thông tin đăng nhập. Vui lòng thử lại.")
        }
        val previousUserId = _currentUser.value?.id
        if (previousUserId != canonicalId) {
            notifyUserSessionChanged(context, previousUserId)
        }
        _currentUser.value = profile
        onLoginCommitted(context, profile)
        return profile
    }

    /**
     * Atomically validates that the attempt token is still current and valid,
     * commits the signed-in account if valid, and releases the attempt lock.
     * Returns null if the attempt is stale or invalidated.
     */
    fun commitSignedInAccount(
        context: Context,
        token: GoogleLoginAttempt,
        account: GoogleSignInAccountData
    ): UserProfile? {
        synchronized(authStateLock) {
            if (!isAttemptValid(token)) {
                Log.d(TAG, "Discarding sign-in commit: attempt $token is no longer valid (active=${activeLoginAttempt.get()}, sessionGen=${sessionGeneration.get()})")
                return null
            }

            val isReauth = !token.expectedOwnerId.isNullOrBlank()

            // Validate new credential freshness before commit
            if (isReauth) {
                val tokenStr = account.idToken
                if (tokenStr.isNullOrBlank() || com.tscanner.app.utils.billing.PlayPurchaseVerifier.isTokenExpired(tokenStr)) {
                    Log.w(TAG, "Rejecting reauthentication commit: credential token is missing or expired")
                    clearActiveAttemptIfMatchingLocked(token)
                    throw ExpiredCredentialException("Phiên xác thực đã hết hạn. Vui lòng xác thực lại tài khoản Google để tiếp tục.")
                }
            } else if (!account.idToken.isNullOrBlank() && com.tscanner.app.utils.billing.PlayPurchaseVerifier.isTokenExpired(account.idToken)) {
                Log.w(TAG, "Rejecting sign-in commit: incoming idToken is expired")
                clearActiveAttemptIfMatchingLocked(token)
                throw ExpiredCredentialException("Phiên xác thực đã hết hạn. Vui lòng thử lại.")
            }

            // If this attempt is a reauthentication, strictly verify owner identity BEFORE any commit side effects
            if (isReauth) {
                val expectedOwner = token.expectedOwnerId!!
                val incomingEmail = account.email ?: ""
                val incomingCanonicalId = resolveCanonicalGoogleId(account.id, account.idToken, incomingEmail)
                val matchesOwner = incomingCanonicalId == expectedOwner ||
                    (incomingEmail.isNotBlank() && incomingEmail.equals(expectedOwner, ignoreCase = true))
                if (!matchesOwner) {
                    Log.w(TAG, "Rejecting reauthentication commit: owner mismatch (expected=${sanitizeIdForLog(expectedOwner)}, incoming=${sanitizeIdForLog(incomingCanonicalId)})")
                    clearActiveAttemptIfMatchingLocked(token)
                    throw AccountMismatchException("Tài khoản không khớp. Vui lòng chọn đúng tài khoản Google đang sử dụng.")
                }
            }

            val profile = processSignedInAccountInternal(context, account)
            clearActiveAttemptIfMatchingLocked(token)
            return profile
        }
    }

    /**
     * Initiates Google Sign-In using AndroidX Credential Manager (with GetSignInWithGoogleOption),
     * and seamlessly falls back to classic GoogleSignIn Intent if needed (at most once).
     *
     * Invariants:
     * - Request contains only a single GetSignInWithGoogleOption (no GetGoogleIdOption).
     * - Enforces single in-flight sign-in (debouncing).
     * - Stale request completion does not overwrite newer sessions.
     * - User cancellation (GetCredentialCancellationException) and coroutine CancellationException do not fallback.
     * - Does not hold or invoke callbacks on destroyed/finishing Activities.
     * - Technical errors fallback to Intent exactly once.
     */
    /**
     * Initiates reauthentication for the currently logged-in account.
     * Guarantees (E01):
     * - Does NOT call signOut(), clear credential state, or clear local profile/data.
     * - Bounds the attempt to [expectedOwnerId].
     * - Rejects credentials from any account other than [expectedOwnerId] BEFORE committing or side effects.
     */
    fun reauthenticateWithGoogle(
        activity: Activity,
        coroutineScope: CoroutineScope,
        expectedOwnerId: String,
        credentialClient: GoogleCredentialClient = DefaultGoogleCredentialClient(),
        mainDispatcher: kotlinx.coroutines.CoroutineDispatcher = Dispatchers.Main,
        onFallbackToIntent: () -> Unit,
        onSuccess: (UserProfile) -> Unit,
        onCancelled: () -> Unit = {},
        onError: (String) -> Unit,
        onAttemptCreated: ((GoogleLoginAttempt) -> Unit)? = null
    ): Boolean {
        return signInWithGoogle(
            activity = activity,
            coroutineScope = coroutineScope,
            credentialClient = credentialClient,
            mainDispatcher = mainDispatcher,
            expectedOwnerId = expectedOwnerId,
            onFallbackToIntent = onFallbackToIntent,
            onSuccess = onSuccess,
            onCancelled = onCancelled,
            onError = onError,
            onAttemptCreated = onAttemptCreated
        )
    }

    fun signInWithGoogle(
        activity: Activity,
        coroutineScope: CoroutineScope,
        credentialClient: GoogleCredentialClient = DefaultGoogleCredentialClient(),
        mainDispatcher: kotlinx.coroutines.CoroutineDispatcher = Dispatchers.Main,
        onFallbackToIntent: () -> Unit,
        onSuccess: (UserProfile) -> Unit,
        onCancelled: () -> Unit = {},
        onError: (String) -> Unit,
        onAttemptCreated: ((GoogleLoginAttempt) -> Unit)? = null,
        expectedOwnerId: String? = null
    ): Boolean {
        val attemptToken: GoogleLoginAttempt
        synchronized(authStateLock) {
            if (activeLoginAttempt.get() != null || isSignInInProgress.get()) {
                Log.d(TAG, "Sign-in already in progress. Ignoring duplicate request.")
                return false
            }
            val currentGen = sessionGeneration.get()
            val currentRequestId = signInRequestGeneration.incrementAndGet()
            attemptToken = GoogleLoginAttempt(
                requestId = currentRequestId,
                initialSessionGeneration = currentGen,
                processEpoch = processEpoch,
                expectedOwnerId = expectedOwnerId
            )
            activeLoginAttempt.set(attemptToken)
            isSignInInProgress.set(true)
        }

        onAttemptCreated?.invoke(attemptToken)

        val job = coroutineScope.launch {
            if (activity.isFinishing || activity.isDestroyed) {
                synchronized(authStateLock) {
                    clearActiveAttemptIfMatchingLocked(attemptToken)
                }
                return@launch
            }

            // Step 0: Await in-flight provider cleanup (serialization with logout)
            val cleanupComplete = LogoutCoordinator.awaitProviderCleanup()
            if (!cleanupComplete) {
                Log.w(TAG, "Aborting sign-in: previous provider sign out is still pending in background")
                synchronized(authStateLock) {
                    clearActiveAttemptIfMatchingLocked(attemptToken)
                }
                withContext(mainDispatcher) {
                    if (!activity.isFinishing && !activity.isDestroyed) {
                        onError("Đang hoàn tất đăng xuất tài khoản trước, vui lòng thử lại sau.")
                    }
                }
                return@launch
            }

            // Post-wait validation (Requirement 4):
            // Login waits for authoritative state; verify attempt and host activity are still valid
            // before initiating provider SDK UI. If another logout invalidated the request, abort.
            if (!isAttemptValid(attemptToken) || activity.isFinishing || activity.isDestroyed) {
                Log.d(TAG, "Attempt invalidated during provider cleanup wait: requestId=${attemptToken.requestId}")
                synchronized(authStateLock) {
                    clearActiveAttemptIfMatchingLocked(attemptToken)
                }
                return@launch
            }

            // Step 1: Provider SDK Boundary
            // Strictly catch only provider exceptions; do not wrap commit or post-auth callbacks in this boundary.
            var providerExceptionCause: String? = null
            val accountData: GoogleSignInAccountData? = try {
                Log.i(TAG, "[AuthLifecycle] stage=REQUEST status=START attempt=${attemptToken.requestId} generation=${attemptToken.initialSessionGeneration}")
                val request = GoogleCredentialRequestFactory.createExplicitSignInRequest(webClientId)
                credentialClient.getCredential(activity, request)
            } catch (e: kotlin.coroutines.cancellation.CancellationException) {
                synchronized(authStateLock) {
                    clearActiveAttemptIfMatchingLocked(attemptToken)
                }
                Log.i(TAG, "[AuthLifecycle] stage=PROVIDER status=LIFECYCLE_CANCEL attempt=${attemptToken.requestId}")
                throw e
            } catch (e: GetCredentialCancellationException) {
                val sanitizedType = sanitizeForLog(e.type)
                val sanitizedReason = sanitizeForLog(e.message)
                Log.i(TAG, "[AuthLifecycle] stage=PROVIDER status=CANCELLED attempt=${attemptToken.requestId} type=$sanitizedType reason=$sanitizedReason")
                synchronized(authStateLock) {
                    clearActiveAttemptIfMatchingLocked(attemptToken)
                }
                withContext(mainDispatcher) {
                    if (!activity.isFinishing && !activity.isDestroyed) {
                        Log.i(TAG, "[AuthLifecycle] stage=UI status=DISPATCH_CANCELLED attempt=${attemptToken.requestId}")
                        onCancelled()
                    }
                }
                return@launch
            } catch (e: androidx.credentials.exceptions.NoCredentialException) {
                val sanitizedType = sanitizeForLog(e.type)
                val sanitizedReason = sanitizeForLog(e.message)
                providerExceptionCause = "NoCredentialException($sanitizedType): $sanitizedReason"
                Log.w(TAG, "[AuthLifecycle] stage=PROVIDER status=NO_CREDENTIAL attempt=${attemptToken.requestId} type=$sanitizedType reason=$sanitizedReason. Triggering fallback.")
                null
            } catch (e: GetCredentialException) {
                val sanitizedType = sanitizeForLog(e.type)
                val sanitizedReason = sanitizeForLog(e.message)
                providerExceptionCause = "GetCredentialException($sanitizedType): $sanitizedReason"
                Log.w(TAG, "[AuthLifecycle] stage=PROVIDER status=TECHNICAL_ERROR attempt=${attemptToken.requestId} type=$sanitizedType reason=$sanitizedReason. Triggering fallback.")
                null
            } catch (e: Exception) {
                val sanitizedReason = sanitizeForLog(e.message)
                providerExceptionCause = "${e.javaClass.simpleName}: $sanitizedReason"
                Log.e(TAG, "[AuthLifecycle] stage=PROVIDER status=UNEXPECTED attempt=${attemptToken.requestId} errorClass=${e.javaClass.simpleName} reason=$sanitizedReason. Triggering fallback.", e)
                null
            }

            // Step 2: Handle Provider Success vs Fallback
            if (accountData != null) {
                Log.i(TAG, "[AuthLifecycle] stage=PROVIDER status=SUCCESS attempt=${attemptToken.requestId}")
                // Stale completion check
                if (!isAttemptValid(attemptToken)) {
                    Log.w(TAG, "[AuthLifecycle] stage=STALE_REJECTION stage_context=PROVIDER_RESULT attempt=${attemptToken.requestId}")
                    synchronized(authStateLock) {
                        clearActiveAttemptIfMatchingLocked(attemptToken)
                    }
                    return@launch
                }

                // Step 2a: Commit Boundary
                // Local commit errors must never trigger fallback to GoogleSignIn Intent.
                val profile = try {
                    Log.i(TAG, "[AuthLifecycle] stage=COMMIT status=START attempt=${attemptToken.requestId}")
                    commitSignedInAccount(activity, attemptToken, accountData)
                } catch (e: kotlin.coroutines.cancellation.CancellationException) {
                    synchronized(authStateLock) {
                        clearActiveAttemptIfMatchingLocked(attemptToken)
                    }
                    throw e
                } catch (e: Exception) {
                    Log.e(TAG, "[AuthLifecycle] stage=COMMIT status=FAILURE attempt=${attemptToken.requestId} errorClass=${e.javaClass.simpleName} reason=${sanitizeForLog(e.message)}", e)
                    synchronized(authStateLock) {
                        clearActiveAttemptIfMatchingLocked(attemptToken)
                    }
                    withContext(mainDispatcher) {
                        if (!activity.isFinishing && !activity.isDestroyed) {
                            Log.i(TAG, "[AuthLifecycle] stage=UI status=DISPATCH_ERROR attempt=${attemptToken.requestId}")
                            onError(e.localizedMessage ?: activity.getString(R.string.google_signin_error_no_account))
                        }
                    }
                    return@launch
                }

                if (profile != null) {
                    Log.i(TAG, "[AuthLifecycle] stage=COMMIT status=SUCCESS attempt=${attemptToken.requestId} accountId=${sanitizeIdForLog(profile.id)}")
                    // Step 2b: Post-Auth Callback Boundary
                    // UI/sync errors in onSuccess must NOT trigger fallback or report authentication failure.
                    withContext(mainDispatcher) {
                        if (!activity.isFinishing && !activity.isDestroyed) {
                            try {
                                Log.i(TAG, "[AuthLifecycle] stage=UI status=DISPATCH_SUCCESS attempt=${attemptToken.requestId}")
                                onSuccess(profile)
                            } catch (e: kotlin.coroutines.cancellation.CancellationException) {
                                throw e
                            } catch (e: Exception) {
                                Log.e(TAG, "[AuthLifecycle] stage=UI status=DISPATCH_SUCCESS_EXCEPTION attempt=${attemptToken.requestId}", e)
                            }
                        }
                    }
                } else {
                    Log.w(TAG, "[AuthLifecycle] stage=STALE_REJECTION stage_context=COMMIT attempt=${attemptToken.requestId}")
                }
            } else {
                // Step 3: Fallback to classic GoogleSignIn Intent (at most once)
                Log.w(TAG, "[AuthLifecycle] stage=FALLBACK_LAUNCH status=START attempt=${attemptToken.requestId} initialCause=${providerExceptionCause ?: "NO_ACCOUNT_DATA"}")
                if (!isAttemptValid(attemptToken)) {
                    Log.w(TAG, "[AuthLifecycle] stage=STALE_REJECTION stage_context=FALLBACK attempt=${attemptToken.requestId}")
                    synchronized(authStateLock) {
                        clearActiveAttemptIfMatchingLocked(attemptToken)
                    }
                    return@launch
                }
                synchronized(authStateLock) {
                    val active = activeLoginAttempt.get()
                    if (active != null && active.requestId == attemptToken.requestId) {
                        active.markFallbackActive()
                    }
                }
                attemptToken.markFallbackActive()
                withContext(mainDispatcher) {
                    if (!activity.isFinishing && !activity.isDestroyed) {
                        try {
                            onFallbackToIntent()
                        } catch (e: kotlin.coroutines.cancellation.CancellationException) {
                            synchronized(authStateLock) {
                                clearActiveAttemptIfMatchingLocked(attemptToken)
                            }
                            throw e
                        } catch (ex: Exception) {
                            Log.e(TAG, "[AuthLifecycle] stage=FALLBACK_LAUNCH status=FAILED attempt=${attemptToken.requestId} initialCause=${providerExceptionCause ?: "NO_ACCOUNT_DATA"} error=${sanitizeForLog(ex.message)}", ex)
                            synchronized(authStateLock) {
                                clearActiveAttemptIfMatchingLocked(attemptToken)
                            }
                            onError(ex.localizedMessage ?: "Failed to launch Google Sign-In fallback")
                        }
                    } else {
                        synchronized(authStateLock) {
                            clearActiveAttemptIfMatchingLocked(attemptToken)
                        }
                    }
                }
            }
        }

        job.invokeOnCompletion { cause ->
            synchronized(authStateLock) {
                val active = activeLoginAttempt.get()
                val isFallback = (active != null && active.requestId == attemptToken.requestId && active.isFallbackActive()) || attemptToken.isFallbackActive()
                if (cause != null || !isFallback) {
                    clearActiveAttemptIfMatchingLocked(attemptToken)
                    Log.d(TAG, "Released sign-in lock for attempt ${attemptToken.requestId} on job completion (cause=$cause)")
                }
            }
        }
        return true
    }

    /**
     * Sign out user from Google and clear local session.
     */
    fun signOut(
        context: Context,
        coroutineScope: CoroutineScope,
        mainDispatcher: kotlinx.coroutines.CoroutineDispatcher = Dispatchers.Main,
        onComplete: () -> Unit = {}
    ) {
        val appContext = context.applicationContext ?: context
        val previousUserId = _currentUser.value?.id
        val logoutSessionGen: Long
        val logoutOpId = LogoutCoordinator.startLogout()
        synchronized(authStateLock) {
            activeLoginAttempt.set(null)
            isSignInInProgress.set(false)
            pendingDriveAttempts.clear()
            consumedDriveRequestIds.clear()
            pendingDriveAuthAttempt.set(null)
            notifyUserSessionChanged(appContext, previousUserId)
            logoutSessionGen = sessionGeneration.get()
            clearSavedUser(appContext)
            try {
                _currentUser.value = null
            } catch (e: IllegalStateException) {
                _currentUser.postValue(null)
            }
        }
        val cleanupJob = coroutineScope.launch {
            var completedNormally = false
            try {
                // Cancel pending background backup jobs for this user to avoid leaking data
                try {
                    val wm = androidx.work.WorkManager.getInstance(appContext)
                    if (previousUserId != null) {
                        wm.cancelAllWorkByTag("owner_$previousUserId")
                    } else {
                        wm.cancelAllWorkByTag("cloud_backup")
                    }
                } catch (e: kotlin.coroutines.cancellation.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to cancel work on sign out", e)
                }

                val canRunProviderCleanup = synchronized(authStateLock) {
                    sessionGeneration.get() == logoutSessionGen && _currentUser.value == null
                }

                if (canRunProviderCleanup) {
                    val customAction = googleSignOutAction
                    if (customAction != null) {
                        val taskId = "custom_sign_out_$logoutOpId"
                        LogoutCoordinator.markProviderTaskStarted(logoutOpId, taskId)
                        try {
                            customAction.invoke(appContext)
                            LogoutCoordinator.markProviderTaskCompleted(logoutOpId, taskId)
                        } catch (e: kotlin.coroutines.cancellation.CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            LogoutCoordinator.markProviderTaskCompleted(logoutOpId, taskId)
                            Log.e(TAG, "Custom sign out provider delegate failed", e)
                        }
                    } else {
                        try {
                            credentialClearProvider.clearCredentialState(appContext)
                        } catch (e: kotlin.coroutines.cancellation.CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            Log.e(TAG, "Failed to clear credential state", e)
                        }

                        val taskId = "google_sign_out_$logoutOpId"
                        val task: com.google.android.gms.tasks.Task<Void>? = try {
                            googleSignOutProvider.signOut(appContext)
                        } catch (e: kotlin.coroutines.cancellation.CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            Log.e(TAG, "Synchronous failure creating Google sign-out task", e)
                            null
                        }

                        if (task != null) {
                            val taskExecutor = java.util.concurrent.Executor { it.run() }
                            LogoutCoordinator.markProviderTaskStarted(logoutOpId, taskId)
                            task.addOnCompleteListener(taskExecutor) {
                                LogoutCoordinator.markProviderTaskCompleted(logoutOpId, taskId)
                            }
                            try {
                                kotlinx.coroutines.withTimeoutOrNull(3000L) {
                                    kotlinx.coroutines.suspendCancellableCoroutine<Unit> { cont ->
                                        task.addOnCompleteListener(taskExecutor) {
                                            if (cont.isActive) cont.resume(Unit)
                                        }
                                    }
                                }
                            } catch (e: kotlin.coroutines.cancellation.CancellationException) {
                                // Task remains running in background! Do NOT mark complete here.
                                throw e
                            } catch (e: Exception) {
                                Log.e(TAG, "Failed waiting for Google sign-out task", e)
                            }
                        }
                    }
                } else {
                    Log.d(TAG, "Skipping provider signOut: newer session or user already active (logoutGen=$logoutSessionGen, currentGen=${sessionGeneration.get()})")
                }
                completedNormally = true
            } catch (e: kotlin.coroutines.cancellation.CancellationException) {
                Log.d(TAG, "Sign out cleanup coroutine cancelled")
                throw e
            } finally {
                if (completedNormally) {
                    LogoutCoordinator.onCleanupCompleted(logoutOpId)
                }
                synchronized(authStateLock) {
                    if (sessionGeneration.get() == logoutSessionGen) {
                        clearSavedUser(appContext)
                        try {
                            _currentUser.value = null
                        } catch (e: IllegalStateException) {
                            _currentUser.postValue(null)
                        }
                    } else {
                        Log.d(TAG, "Preserving new session: skipping clearSavedUser in late logout finally block")
                    }
                }
                withContext(kotlinx.coroutines.NonCancellable) {
                    withContext(mainDispatcher) {
                        onComplete()
                    }
                }
            }
        }

        cleanupJob.invokeOnCompletion { cause ->
            if (cause is kotlin.coroutines.cancellation.CancellationException) {
                LogoutCoordinator.onCleanupCancelled(logoutOpId)
            } else if (cause != null) {
                LogoutCoordinator.onCleanupFailed(logoutOpId, cause)
            } else {
                LogoutCoordinator.onCleanupCompleted(logoutOpId)
            }
        }
    }

    fun signOut(
        context: Context,
        coroutineScope: CoroutineScope,
        onComplete: () -> Unit
    ) {
        signOut(context, coroutineScope, Dispatchers.Main, onComplete)
    }

    /**
     * Sets or updates VIP status and tier for the currently logged in user for a specific duration (default 365 days / 1 year).
     * Properly extends existing valid subscription from its current expiry date.
     */
    fun setUserVipTier(context: Context, tier: com.tscanner.app.data.model.VipTier, durationDays: Int = 365) {
        val user = _currentUser.value ?: return
        val now = System.currentTimeMillis()
        user.tier = tier
        user.isVip = (tier != com.tscanner.app.data.model.VipTier.FREE)
        if (user.isVip) {
            user.vipPurchasedAt = user.vipPurchasedAt ?: now
            val currentExpiry = user.vipExpiresAt ?: 0L
            val baseTime = maxOf(now, currentExpiry)
            user.vipExpiresAt = baseTime + (durationDays * 24 * 60 * 60 * 1000L)
        } else {
            user.vipPurchasedAt = null
            user.vipExpiresAt = null
        }
        saveVipForUser(context, user.id, user.tier, user.vipPurchasedAt, user.vipExpiresAt)
        saveUser(context, user)
        _currentUser.postValue(user)
    }

    /**
     * Projects a committed entitlement snapshot directly to the in-memory user profile and persists it.
     * Does NOT commit to BillingEntitlementStore (eliminates double-write bug F07).
     */
    fun projectSnapshotToProfile(context: Context, snapshot: com.tscanner.app.utils.billing.UserEntitlementSnapshot): Boolean {
        val user = _currentUser.value
        if (user != null && (snapshot.ownerAppUserId == null || user.id == snapshot.ownerAppUserId)) {
            val isVip = snapshot.isVipActive()
            val isLifetime = snapshot.isLifetimeActive()
            val tier = snapshot.getHighestActiveTier()
            val activeEntitlements = snapshot.getActiveEntitlements()

            user.isVip = isVip
            user.tier = tier
            user.vipExpiresAt = if (isLifetime) null else activeEntitlements.mapNotNull { it.expiryTimeMillis }.maxOrNull()
            user.vipPurchasedAt = activeEntitlements.minOfOrNull { it.purchaseTimeMillis } ?: user.vipPurchasedAt

            val vipSaved = saveVipForUser(context, user.id, user.tier, user.vipPurchasedAt, user.vipExpiresAt)
            val userSaved = saveUser(context, user)
            _currentUser.postValue(user)
            return vipSaved && userSaved
        }
        return true
    }

    /**
     * Applies an authoritative, verified entitlement snapshot to the user profile without relative duration accumulation.
     * Idempotent: replaying the same snapshot produces the exact same absolute expiration.
     */
    fun applyEntitlementSnapshot(context: Context, snapshot: com.tscanner.app.utils.billing.UserEntitlementSnapshot): Boolean {
        val store = com.tscanner.app.utils.billing.BillingEntitlementStore.getInstance()
        val applyResult = store.applySnapshotTyped(context, snapshot)
        if (applyResult !is com.tscanner.app.utils.billing.ApplySnapshotResult.Success) {
            Log.w(TAG, "applyEntitlementSnapshot: Failed to apply snapshot to store for user '${snapshot.ownerAppUserId}'")
            return false
        }
        return projectSnapshotToProfile(context, applyResult.snapshot)
    }

    /**
     * Convenience method to apply a single entitlement to the current user or guest.
     */
    fun applyEntitlement(context: Context, entitlement: com.tscanner.app.utils.billing.BillingEntitlement): Boolean {
        val snapshot = com.tscanner.app.utils.billing.UserEntitlementSnapshot(
            ownerAppUserId = entitlement.ownerAppUserId ?: _currentUser.value?.id,
            entitlements = listOf(entitlement)
        )
        return applyEntitlementSnapshot(context, snapshot)
    }

    /**
     * Gets the full entitlement snapshot for the specified user or current user.
     */
    fun getEntitlementSnapshot(context: Context, userId: String? = _currentUser.value?.id): com.tscanner.app.utils.billing.UserEntitlementSnapshot {
        return com.tscanner.app.utils.billing.BillingEntitlementStore.getInstance().getSnapshot(context, userId)
    }

    fun setUserVipStatus(context: Context, isVip: Boolean) {
        setUserVipTier(
            context,
            if (isVip) com.tscanner.app.data.model.VipTier.VIP else com.tscanner.app.data.model.VipTier.FREE
        )
    }

    /**
     * Convenience method to sign in with a demo/mock account for internal development/testing.
     *
     * Invariants (V04):
     * Strictly isolates data. Never claims or modifies guest documents or other users' documents.
     * Guest documents remain unowned (ownerId = null) so legitimate Google account sign-ins
     * can claim them without data loss or theft.
     */
    fun signInWithDemoAccount(context: Context, onComplete: (UserProfile) -> Unit) {
        val demoUser = UserProfile(
            id = "google_user_demo_1001",
            email = "demo.scanner@gmail.com",
            displayName = "Người dùng T-Scanner",
            givenName = "T-Scanner",
            familyName = "User",
            photoUrl = null,
            idToken = "demo_jwt_token_sample",
            isVip = false,
            tier = com.tscanner.app.data.model.VipTier.FREE
        )
        // Strictly isolated: Do NOT claim guest documents. Guest documents must remain ownerless
        // so that real Google account logins can claim them legitimately.
        loadVipForUser(context, demoUser)
        saveUser(context, demoUser)
        val previousUserId = _currentUser.value?.id
        if (previousUserId != demoUser.id) {
            notifyUserSessionChanged(context, previousUserId)
        }
        _currentUser.value = demoUser
        onLoginCommitted(context, demoUser)
        onComplete(demoUser)
    }

    @VisibleForTesting
    var postLoginHook: ((Context, UserProfile) -> Unit)? = null

    private fun onLoginCommitted(context: Context, profile: UserProfile) {
        val hook = postLoginHook
        if (hook != null) {
            hook(context, profile)
            return
        }
        try {
            val billingManager = com.tscanner.app.utils.BillingManager.getInstance(context)
            billingManager.bindPurchasesToCurrentUser(context)
            billingManager.syncPurchases()
        } catch (e: Throwable) {
            Log.e(TAG, "Error in post-login billing sync", e)
        }
    }

    private fun loadVipForUser(context: Context, profile: UserProfile) {
        val store = com.tscanner.app.utils.billing.BillingEntitlementStore.getInstance()
        val snapshot = store.getSnapshot(context, profile.id)

        if (snapshot.entitlements.isNotEmpty()) {
            val isVip = snapshot.isVipActive()
            val isLifetime = snapshot.isLifetimeActive()
            val tier = snapshot.getHighestActiveTier()
            val activeEntitlements = snapshot.getActiveEntitlements()

            profile.isVip = isVip
            profile.tier = tier
            profile.vipExpiresAt = if (isLifetime) null else activeEntitlements.mapNotNull { it.expiryTimeMillis }.maxOrNull()
            profile.vipPurchasedAt = activeEntitlements.minOfOrNull { it.purchaseTimeMillis }
            return
        }

        val prefs = getPrefs(context)
        if (profile.id != profile.email && profile.email.isNotBlank()) {
            val canonicalTier = prefs.getString("${KEY_VIP_ACCOUNT_PREFIX}${profile.id}_tier", null)
            val legacyTier = prefs.getString("${KEY_VIP_ACCOUNT_PREFIX}${profile.email}_tier", null)
            if (canonicalTier == null && legacyTier != null) {
                migrateLegacyIdentity(context, profile.id, profile.email)
            }
        }
        val tierId = prefs.getString("${KEY_VIP_ACCOUNT_PREFIX}${profile.id}_tier", null)
        val purchasedAt = prefs.getLong("${KEY_VIP_ACCOUNT_PREFIX}${profile.id}_purchased_at", -1L).takeIf { it > 0 }
        val expiresAt = prefs.getLong("${KEY_VIP_ACCOUNT_PREFIX}${profile.id}_expires_at", -1L).takeIf { it > 0 }

        val isLegacyActive = tierId != null && (expiresAt == null || System.currentTimeMillis() <= expiresAt)
        if (isLegacyActive) {
            profile.tier = com.tscanner.app.data.model.VipTier.fromId(tierId)
            profile.isVip = (profile.tier != com.tscanner.app.data.model.VipTier.FREE)
            profile.vipPurchasedAt = purchasedAt
            profile.vipExpiresAt = expiresAt
        } else {
            profile.tier = com.tscanner.app.data.model.VipTier.FREE
            profile.isVip = false
            profile.vipPurchasedAt = null
            profile.vipExpiresAt = null
        }
    }

    private fun saveVipForUser(
        context: Context,
        userId: String,
        tier: com.tscanner.app.data.model.VipTier,
        purchasedAt: Long?,
        expiresAt: Long?
    ): Boolean {
        val prefs = getPrefs(context)
        return prefs.edit()
            .putString("${KEY_VIP_ACCOUNT_PREFIX}${userId}_tier", tier.id)
            .putLong("${KEY_VIP_ACCOUNT_PREFIX}${userId}_purchased_at", purchasedAt ?: -1L)
            .putLong("${KEY_VIP_ACCOUNT_PREFIX}${userId}_expires_at", expiresAt ?: -1L)
            .commit()
    }

    private fun getPrefs(context: Context): SharedPreferences {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    private fun saveUser(context: Context, profile: UserProfile): Boolean {
        return try {
            val json = JSONObject().apply {
                put("id", profile.id)
                put("email", profile.email)
                put("displayName", profile.displayName)
                put("givenName", profile.givenName ?: "")
                put("familyName", profile.familyName ?: "")
                put("photoUrl", profile.photoUrl ?: "")
                put("idToken", profile.idToken ?: "")
                put("isVip", profile.isVip)
                put("tier", profile.tier.id)
                put("loginTime", profile.loginTime)
                put("vipPurchasedAt", profile.vipPurchasedAt ?: -1L)
                put("vipExpiresAt", profile.vipExpiresAt ?: -1L)
            }
            getPrefs(context).edit().putString(KEY_USER_PROFILE, json.toString()).commit()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to serialize UserProfile", e)
            false
        }
    }

    private fun clearSavedUser(context: Context) {
        getPrefs(context).edit().remove(KEY_USER_PROFILE).apply()
    }

    private fun parseUserProfileFromJson(jsonStr: String): UserProfile {
        val json = JSONObject(jsonStr)
        val isVip = json.optBoolean("isVip", false)
        val tier = com.tscanner.app.data.model.VipTier.fromId(
            json.optString("tier", if (isVip) "vip" else "free")
        )
        val purchasedAt = json.optLong("vipPurchasedAt", -1L).let { if (it > 0) it else null }
        val expiresAt = json.optLong("vipExpiresAt", -1L).let { if (it > 0) it else null }
        val isExpired = expiresAt != null && System.currentTimeMillis() > expiresAt
        val effectiveTier = if (isExpired) com.tscanner.app.data.model.VipTier.FREE else tier
        val effectiveIsVip = (effectiveTier != com.tscanner.app.data.model.VipTier.FREE)

        return UserProfile(
            id = json.getString("id"),
            email = json.getString("email"),
            displayName = json.getString("displayName"),
            givenName = json.optString("givenName").ifEmpty { null },
            familyName = json.optString("familyName").ifEmpty { null },
            photoUrl = json.optString("photoUrl").ifEmpty { null },
            idToken = json.optString("idToken").ifEmpty { null },
            isVip = effectiveIsVip,
            tier = effectiveTier,
            loginTime = json.optLong("loginTime", System.currentTimeMillis()),
            vipPurchasedAt = purchasedAt,
            vipExpiresAt = expiresAt
        )
    }

    /**
     * Extracts the Google account numeric subject ID ("sub") from an ID token JWT payload.
     * Uses java.util.Base64.getUrlDecoder() which is available on Android API 26+ and standard JVM.
     */
    fun extractSubFromIdToken(idToken: String?): String? {
        if (idToken.isNullOrBlank()) return null
        val parts = idToken.split(".")
        if (parts.size < 2) return null
        return try {
            var payload = parts[1]
            val rem = payload.length % 4
            if (rem > 0) {
                payload += "=".repeat(4 - rem)
            }
            val payloadBytes = java.util.Base64.getUrlDecoder().decode(payload)
            val json = JSONObject(String(payloadBytes, Charsets.UTF_8))
            val sub = json.optString("sub")
            if (sub.isNotBlank()) sub else null
        } catch (e: Exception) {
            Log.w(TAG, "Failed to extract sub from idToken: ${e.message}")
            null
        }
    }

    /**
     * Resolves the canonical Google account identity.
     * Priority:
     * 1. Google numeric subject ID extracted from idToken JWT ("sub")
     * 2. accountId passed by GoogleSignInAccount (also the permanent numeric account ID)
     * 3. Fallback identifier (email or generic user ID)
     */
    fun resolveCanonicalGoogleId(accountId: String?, idToken: String?, fallbackId: String?): String {
        val subFromToken = extractSubFromIdToken(idToken)
        if (!subFromToken.isNullOrBlank()) {
            return subFromToken
        }
        if (!accountId.isNullOrBlank()) {
            return accountId
        }
        if (!fallbackId.isNullOrBlank()) {
            return fallbackId
        }
        return "google_user"
    }

    /**
     * Safely migrates VIP entitlement and document catalog ownership from a legacy identity
     * (such as email-based ID from Credential Manager) to the canonical Google Account ID.
     * Invariant: Only migrates when the authenticated user's verified email matches the legacy identity.
     */
    fun migrateLegacyIdentity(context: Context, canonicalId: String, email: String) {
        if (canonicalId.isBlank() || email.isBlank() || canonicalId == email) {
            return
        }
        val prefs = getPrefs(context)
        val legacyTier = prefs.getString("${KEY_VIP_ACCOUNT_PREFIX}${email}_tier", null)
        val canonicalTier = prefs.getString("${KEY_VIP_ACCOUNT_PREFIX}${canonicalId}_tier", null)

        // If legacy VIP exists and canonical VIP is not yet set:
        if (legacyTier != null && canonicalTier == null) {
            val purchasedAt = prefs.getLong("${KEY_VIP_ACCOUNT_PREFIX}${email}_purchased_at", -1L)
            val expiresAt = prefs.getLong("${KEY_VIP_ACCOUNT_PREFIX}${email}_expires_at", -1L)

            prefs.edit()
                .putString("${KEY_VIP_ACCOUNT_PREFIX}${canonicalId}_tier", legacyTier)
                .putLong("${KEY_VIP_ACCOUNT_PREFIX}${canonicalId}_purchased_at", purchasedAt)
                .putLong("${KEY_VIP_ACCOUNT_PREFIX}${canonicalId}_expires_at", expiresAt)
                .apply()
            Log.i(TAG, "Successfully migrated VIP entitlement from legacy identity '$email' to canonical '$canonicalId'")
        }

        // Migrate DocumentRepo catalog ownership from email to canonicalId
        try {
            val count = DocumentRepo.getInstance(context).migrateOwnerId(legacyOwnerId = email, canonicalOwnerId = canonicalId)
            if (count > 0) {
                Log.i(TAG, "Migrated ownership for $count documents from '$email' to canonical '$canonicalId'")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to migrate document catalog ownership", e)
        }
    }

    @VisibleForTesting
    fun resetForTesting() {
        val previousUserId = _currentUser.value?.id
        synchronized(authStateLock) {
            sessionGeneration.set(1L)
            isSignInInProgress.set(false)
            signInRequestGeneration.set(0L)
            driveRequestGeneration.set(0L)
            activeLoginAttempt.set(null)
            pendingDriveAuthAttempt.set(null)
            pendingDriveAttempts.clear()
            consumedDriveRequestIds.clear()
            processEpoch = java.util.UUID.randomUUID().toString()
            drivePermissionOverrideForTesting = null
            postAuthSyncDispatcher = null
            googleSignOutAction = null
            googleSignOutProvider = DefaultGoogleSignOutProvider()
            credentialClearProvider = DefaultCredentialClearProvider()
            LogoutCoordinator.resetForTesting()
            CloudBackupManager.cancelActiveCloudTasks(null, previousUserId)
            _currentUser.value = null
            isInitialized = false
            postLoginHook = null
        }
    }

    @VisibleForTesting
    fun createSignInAttemptForTesting(): GoogleLoginAttempt {
        synchronized(authStateLock) {
            val attempt = GoogleLoginAttempt(
                requestId = signInRequestGeneration.incrementAndGet(),
                initialSessionGeneration = sessionGeneration.get(),
                processEpoch = processEpoch
            )
            activeLoginAttempt.set(attempt)
            isSignInInProgress.set(true)
            return attempt
        }
    }

    @VisibleForTesting
    fun createReauthAttemptForTesting(expectedOwnerId: String): GoogleLoginAttempt {
        synchronized(authStateLock) {
            val attempt = GoogleLoginAttempt(
                requestId = signInRequestGeneration.incrementAndGet(),
                initialSessionGeneration = sessionGeneration.get(),
                processEpoch = processEpoch,
                expectedOwnerId = expectedOwnerId
            )
            activeLoginAttempt.set(attempt)
            isSignInInProgress.set(true)
            return attempt
        }
    }

    @VisibleForTesting
    fun setPendingSignInAttemptForTesting(attempt: GoogleLoginAttempt?) {
        synchronized(authStateLock) {
            activeLoginAttempt.set(attempt)
            isSignInInProgress.set(attempt != null)
        }
    }

    @VisibleForTesting
    fun getActiveSignInAttemptForTesting(): GoogleLoginAttempt? = activeLoginAttempt.get()

    @VisibleForTesting
    fun setPendingDriveAuthSessionForTesting(snapshot: DriveAuthSessionSnapshot?) {
        if (snapshot == null) {
            synchronized(authStateLock) {
                pendingDriveAuthAttempt.set(null)
            }
        } else {
            synchronized(authStateLock) {
                val reqId = driveRequestGeneration.incrementAndGet()
                val attempt = DriveAuthorizationAttempt(
                    requestId = reqId,
                    userId = snapshot.userId,
                    userEmail = snapshot.userEmail,
                    sessionGeneration = snapshot.sessionGeneration,
                    processEpoch = processEpoch
                )
                pendingDriveAuthAttempt.set(attempt)
                pendingDriveAttempts[reqId] = attempt
            }
        }
    }

    @VisibleForTesting
    fun getPendingDriveAuthSessionForTesting(): DriveAuthSessionSnapshot? {
        val attempt = pendingDriveAuthAttempt.get() ?: return null
        return DriveAuthSessionSnapshot(attempt.userId, attempt.userEmail, attempt.sessionGeneration)
    }

    @VisibleForTesting
    fun setPendingDriveAuthAttemptForTesting(attempt: DriveAuthorizationAttempt?) {
        synchronized(authStateLock) {
            pendingDriveAuthAttempt.set(attempt)
            if (attempt != null) {
                pendingDriveAttempts[attempt.requestId] = attempt
            }
        }
    }

    @VisibleForTesting
    fun getPendingDriveAuthAttemptForTesting(): DriveAuthorizationAttempt? = pendingDriveAuthAttempt.get()

    @VisibleForTesting
    fun createDriveAuthorizationAttemptForTesting(): DriveAuthorizationAttempt? = createDriveAuthorizationAttempt()

    @VisibleForTesting
    fun setVipForUserForTesting(
        context: Context,
        userId: String,
        tier: com.tscanner.app.data.model.VipTier,
        purchasedAt: Long?,
        expiresAt: Long?
    ) {
        saveVipForUser(context, userId, tier, purchasedAt, expiresAt)
    }

    @VisibleForTesting
    fun loadVipForUserForTesting(context: Context, profile: UserProfile) {
        loadVipForUser(context, profile)
    }

    @VisibleForTesting
    fun isSignInInProgressForTesting(): Boolean = isSignInInProgress.get()

    @VisibleForTesting
    fun setCurrentUserForTesting(profile: UserProfile?) {
        val previousUserId = _currentUser.value?.id
        if (previousUserId != profile?.id) {
            sessionGeneration.incrementAndGet()
        }
        _currentUser.value = profile
    }

    /**
     * Sanitizes error and log messages by removing emails, JWT tokens, Bearer headers,
     * and limiting length to ensure no sensitive credentials leak into logcat.
     */
    fun sanitizeForLog(message: String?): String {
        if (message.isNullOrBlank()) return "none"
        return message
            .replace(Regex("[a-zA-Z0-9._%+-]+@[a-zA-Z0-9.-]+\\.[a-zA-Z]{2,}"), "[EMAIL_REDACTED]")
            .replace(Regex("ey[A-Za-z0-9_-]{10,}\\.[A-Za-z0-9_-]{10,}\\.[A-Za-z0-9_-]{10,}"), "[TOKEN_REDACTED]")
            .replace(Regex("(Bearer\\s+)[A-Za-z0-9_.-]+", RegexOption.IGNORE_CASE), "$1[TOKEN_REDACTED]")
            .take(200)
    }

    /**
     * Sanitizes account/user IDs by masking sensitive identifiers.
     */
    fun sanitizeIdForLog(id: String?): String {
        if (id.isNullOrBlank()) return "none"
        return if (id.length <= 4) "***" else "${id.take(4)}***"
    }
}
