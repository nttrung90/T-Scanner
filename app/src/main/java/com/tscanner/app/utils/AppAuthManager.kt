package com.tscanner.app.utils

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.util.Log
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
import com.tscanner.app.data.model.UserProfile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

object AppAuthManager {

    private const val TAG = "AppAuthManager"
    private const val PREFS_NAME = "tscanner_auth_prefs"
    private const val KEY_USER_PROFILE = "key_user_profile"
    private const val KEY_VIP_ACCOUNT_PREFIX = "vip_account_"

    /**
     * Web Client ID from Google Cloud Console (OAuth 2.0 Web Application Client ID, project: t-scanner-508413).
     */
    var webClientId: String = "284912111014-s5jqvjdden73n6mqc3kofs2c67dbf58d.apps.googleusercontent.com"

    private val _currentUser = MutableLiveData<UserProfile?>(null)
    val currentUser: LiveData<UserProfile?> = _currentUser

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
                val profile = parseUserProfileFromJson(savedJson)
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

    fun hasDrivePermission(context: Context): Boolean {
        val account = GoogleSignIn.getLastSignedInAccount(context) ?: return false
        return GoogleSignIn.hasPermissions(account, com.google.android.gms.common.api.Scope(DRIVE_FILE_SCOPE))
    }

    fun getGoogleDriveSignInIntent(context: Context): Intent {
        val gso = GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
            .requestScopes(com.google.android.gms.common.api.Scope(DRIVE_FILE_SCOPE))
            .requestEmail()
            .requestProfile()
            .build()
        return GoogleSignIn.getClient(context, gso).signInIntent
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
     * Creates Google Sign-In intent as reliable fallback if Credential Manager fails.
     */
    fun getGoogleSignInIntent(context: Context): Intent {
        val gso = GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
            .requestIdToken(webClientId)
            .requestScopes(com.google.android.gms.common.api.Scope(DRIVE_FILE_SCOPE))
            .requestEmail()
            .requestProfile()
            .build()
        return GoogleSignIn.getClient(context, gso).signInIntent
    }

    /**
     * Handles result from classic Google Sign-In intent.
     */
    fun handleGoogleSignInResult(
        context: Context,
        data: Intent?,
        onSuccess: (UserProfile) -> Unit,
        onError: (String) -> Unit
    ) {
        try {
            val task = GoogleSignIn.getSignedInAccountFromIntent(data)
            val account = task.getResult(ApiException::class.java)
            if (account != null) {
                val profile = UserProfile(
                    id = account.id ?: account.email ?: "google_user",
                    email = account.email ?: "",
                    displayName = account.displayName ?: "Google User",
                    givenName = account.givenName,
                    familyName = account.familyName,
                    photoUrl = account.photoUrl?.toString(),
                    idToken = account.idToken,
                    isVip = false,
                    tier = com.tscanner.app.data.model.VipTier.FREE
                )
                // Load VIP status strictly bound to this account ID
                loadVipForUser(context, profile)
                saveUser(context, profile)
                _currentUser.value = profile
                onSuccess(profile)
            } else {
                onError("Không thể lấy thông tin tài khoản Google")
            }
        } catch (e: ApiException) {
            Log.e(TAG, "GoogleSignIn ApiException: status=${e.statusCode} - ${e.message}", e)
            val errorMsg = when (e.statusCode) {
                10 -> "Lỗi cấu hình Google Cloud (Mã 10: SHA-1 debug chưa khớp hoặc chưa thêm email vào Test users)"
                12500 -> "Lỗi xác thực Google Play (Mã 12500: Thiết bị chưa sẵn sàng)"
                12501 -> "Đã hủy đăng nhập Google"
                else -> "Đăng nhập Google thất bại (Mã lỗi: ${e.statusCode})"
            }
            if (e.statusCode != 12501) {
                onError(errorMsg)
            }
        } catch (e: Exception) {
            Log.e(TAG, "handleGoogleSignInResult error", e)
            onError(e.localizedMessage ?: "Lỗi xác thực không xác định")
        }
    }

    /**
     * Initiates Google Sign-In using AndroidX Credential Manager (with GetSignInWithGoogleOption),
     * and seamlessly falls back to classic GoogleSignIn Intent if needed.
     */
    fun signInWithGoogle(
        activity: Activity,
        coroutineScope: CoroutineScope,
        onFallbackToIntent: () -> Unit,
        onSuccess: (UserProfile) -> Unit,
        onError: (String) -> Unit
    ) {
        val isPlaceholderClientId = webClientId.startsWith("YOUR_WEB_CLIENT_ID")

        coroutineScope.launch {
            try {
                val credentialManager = CredentialManager.create(activity)

                val signInWithGoogleOption = GetSignInWithGoogleOption.Builder(webClientId)
                    .build()

                val googleIdOption = GetGoogleIdOption.Builder()
                    .setFilterByAuthorizedAccounts(false)
                    .setServerClientId(webClientId)
                    .setAutoSelectEnabled(false)
                    .build()

                val request = GetCredentialRequest.Builder()
                    .addCredentialOption(signInWithGoogleOption)
                    .addCredentialOption(googleIdOption)
                    .build()

                val result = credentialManager.getCredential(activity, request)
                val credential = result.credential

                if (credential is CustomCredential &&
                    credential.type == GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL
                ) {
                    val googleIdTokenCredential = GoogleIdTokenCredential.createFrom(credential.data)
                    val profile = UserProfile(
                        id = googleIdTokenCredential.id,
                        email = googleIdTokenCredential.id,
                        displayName = googleIdTokenCredential.displayName
                            ?: googleIdTokenCredential.givenName
                            ?: "Google User",
                        givenName = googleIdTokenCredential.givenName,
                        familyName = googleIdTokenCredential.familyName,
                        photoUrl = googleIdTokenCredential.profilePictureUri?.toString(),
                        idToken = googleIdTokenCredential.idToken,
                        isVip = false,
                        tier = com.tscanner.app.data.model.VipTier.FREE
                    )

                    loadVipForUser(activity, profile)
                    saveUser(activity, profile)
                    withContext(Dispatchers.Main) {
                        _currentUser.value = profile
                        onSuccess(profile)
                    }
                } else {
                    withContext(Dispatchers.Main) {
                        onFallbackToIntent()
                    }
                }
            } catch (e: GetCredentialCancellationException) {
                Log.d(TAG, "User cancelled Google Sign-In")
            } catch (e: GetCredentialException) {
                Log.w(TAG, "CredentialManager failed: ${e.type} - ${e.message}. Triggering intent fallback.")
                withContext(Dispatchers.Main) {
                    // Fallback to classic GoogleSignIn Intent
                    onFallbackToIntent()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Sign in error", e)
                withContext(Dispatchers.Main) {
                    onFallbackToIntent()
                }
            }
        }
    }

    /**
     * Sign out user from Google and clear local session.
     */
    fun signOut(
        context: Context,
        coroutineScope: CoroutineScope,
        onComplete: () -> Unit
    ) {
        coroutineScope.launch {
            try {
                // Cancel pending background backup jobs for this user to avoid leaking data
                androidx.work.WorkManager.getInstance(context).cancelAllWork()
            } catch (e: Exception) {
                Log.e(TAG, "Failed to cancel work on sign out", e)
            }
            try {
                val credentialManager = CredentialManager.create(context)
                credentialManager.clearCredentialState(ClearCredentialStateRequest())
            } catch (e: Exception) {
                Log.e(TAG, "Failed to clear credential state", e)
            }
            try {
                val gso = GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN).build()
                GoogleSignIn.getClient(context, gso).signOut()
            } catch (e: Exception) {
                Log.e(TAG, "Failed to sign out GoogleSignIn client", e)
            } finally {
                clearSavedUser(context)
                withContext(Dispatchers.Main) {
                    _currentUser.value = null
                    onComplete()
                }
            }
        }
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

    fun setUserVipStatus(context: Context, isVip: Boolean) {
        setUserVipTier(
            context,
            if (isVip) com.tscanner.app.data.model.VipTier.VIP else com.tscanner.app.data.model.VipTier.FREE
        )
    }

    /**
     * Convenience method to sign in with a demo/mock account for development/testing
     * before production Google Cloud Console Client ID is set up.
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
        loadVipForUser(context, demoUser)
        saveUser(context, demoUser)
        _currentUser.value = demoUser
        onComplete(demoUser)
    }

    private fun loadVipForUser(context: Context, profile: UserProfile) {
        val prefs = getPrefs(context)
        val tierId = prefs.getString("${KEY_VIP_ACCOUNT_PREFIX}${profile.id}_tier", null)
        val purchasedAt = prefs.getLong("${KEY_VIP_ACCOUNT_PREFIX}${profile.id}_purchased_at", -1L).takeIf { it > 0 }
        val expiresAt = prefs.getLong("${KEY_VIP_ACCOUNT_PREFIX}${profile.id}_expires_at", -1L).takeIf { it > 0 }

        if (tierId != null && expiresAt != null && System.currentTimeMillis() <= expiresAt) {
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
    ) {
        val prefs = getPrefs(context)
        prefs.edit()
            .putString("${KEY_VIP_ACCOUNT_PREFIX}${userId}_tier", tier.id)
            .putLong("${KEY_VIP_ACCOUNT_PREFIX}${userId}_purchased_at", purchasedAt ?: -1L)
            .putLong("${KEY_VIP_ACCOUNT_PREFIX}${userId}_expires_at", expiresAt ?: -1L)
            .apply()
    }

    private fun getPrefs(context: Context): SharedPreferences {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    private fun saveUser(context: Context, profile: UserProfile) {
        try {
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
            getPrefs(context).edit().putString(KEY_USER_PROFILE, json.toString()).apply()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to serialize UserProfile", e)
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
}
