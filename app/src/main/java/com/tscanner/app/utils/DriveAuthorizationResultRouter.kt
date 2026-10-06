package com.tscanner.app.utils

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.util.Log
import com.google.android.gms.common.api.ApiException
import com.tscanner.app.R
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Standardized result of a Google Drive authorization attempt (V06).
 */
sealed class DriveAuthorizationResult {
    data class Success(val accountData: GoogleSignInAccountData) : DriveAuthorizationResult()
    object Cancelled : DriveAuthorizationResult()
    data class AccountMismatch(val expectedEmail: String, val grantedEmail: String) : DriveAuthorizationResult()
    object SessionExpiredOrChanged : DriveAuthorizationResult()
    object PermissionDenied : DriveAuthorizationResult()
    data class Failure(val statusCode: Int?, val errorMessage: String) : DriveAuthorizationResult()
}

/**
 * Router that parses and dispatches Activity results from Drive authorization consent intents.
 *
 * Guarantees (V06):
 * 1. Errors returned with RESULT_CANCELED are properly parsed and not swallowed.
 * 2. Pure user cancellation (null intent + RESULT_CANCELED or 12501) ends quietly without error popups.
 * 3. Accounts returned from consent screen MUST match the active session user's email.
 * 4. Stale results from expired sessions, logouts, or switched accounts are safely discarded.
 * 5. Callbacks are invoked at most once (single invocation guarantee).
 */
object DriveAuthorizationResultRouter {
    private const val TAG = "DriveAuthRouter"
    const val DRIVE_FILE_SCOPE = "https://www.googleapis.com/auth/drive.file"

    fun routeResult(
        context: Context?,
        resultCode: Int,
        data: Intent?,
        expectedUserEmail: String?,
        currentSessionGeneration: Long,
        expectedSessionGeneration: Long,
        parser: GoogleSignInAccountParser = DefaultGoogleSignInAccountParser()
    ): DriveAuthorizationResult {
        // 1. Session consistency check: if session generation changed, result is stale
        if (currentSessionGeneration != expectedSessionGeneration || expectedUserEmail.isNullOrBlank()) {
            Log.w(TAG, "Stale Drive authorization result: expected gen $expectedSessionGeneration for $expectedUserEmail, but current is $currentSessionGeneration")
            return DriveAuthorizationResult.SessionExpiredOrChanged
        }

        // 2. Handle null data intent
        if (data == null) {
            return if (resultCode == Activity.RESULT_CANCELED) {
                DriveAuthorizationResult.Cancelled
            } else {
                DriveAuthorizationResult.Failure(
                    statusCode = null,
                    errorMessage = context?.getString(R.string.google_signin_error_no_account)
                        ?: "Không nhận được phản hồi cấp quyền Drive"
                )
            }
        }

        // 3. Parse intent with parser
        return try {
            val account = parser.parseAccountFromIntent(data)
            if (account == null) {
                DriveAuthorizationResult.Failure(
                    statusCode = null,
                    errorMessage = context?.getString(R.string.google_signin_error_no_account)
                        ?: "Không thể lấy thông tin tài khoản Google"
                )
            } else {
                val grantedEmail = account.email
                if (grantedEmail.isNullOrBlank() || !grantedEmail.equals(expectedUserEmail, ignoreCase = true)) {
                    Log.w(TAG, "Drive permission granted to '$grantedEmail', but current logged in user is '$expectedUserEmail'")
                    DriveAuthorizationResult.AccountMismatch(
                        expectedEmail = expectedUserEmail,
                        grantedEmail = grantedEmail ?: ""
                    )
                } else if (!account.grantedScopes.contains(DRIVE_FILE_SCOPE)) {
                    DriveAuthorizationResult.PermissionDenied
                } else {
                    DriveAuthorizationResult.Success(account)
                }
            }
        } catch (e: ApiException) {
            val code = e.statusCode
            Log.w(TAG, "Drive authorization returned ApiException: statusCode=$code, message=${e.message}")
            if (code == GoogleSignInResultRouter.STATUS_SIGN_IN_CANCELLED) {
                DriveAuthorizationResult.Cancelled
            } else {
                val errorMsg = GoogleSignInResultRouter.formatErrorMessage(context, code)
                DriveAuthorizationResult.Failure(statusCode = code, errorMessage = errorMsg)
            }
        } catch (e: kotlin.coroutines.cancellation.CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Unexpected error parsing Drive authorization intent: ${e.message}", e)
            DriveAuthorizationResult.Failure(
                statusCode = null,
                errorMessage = e.localizedMessage ?: "Lỗi cấp quyền Google Drive"
            )
        }
    }

    fun dispatchResult(
        context: Context?,
        resultCode: Int,
        data: Intent?,
        expectedUserEmail: String?,
        currentSessionGeneration: Long,
        expectedSessionGeneration: Long,
        parser: GoogleSignInAccountParser = DefaultGoogleSignInAccountParser(),
        onSuccess: () -> Unit,
        onCancelled: () -> Unit,
        onError: (String) -> Unit
    ) {
        val completed = AtomicBoolean(false)
        val result = routeResult(
            context = context,
            resultCode = resultCode,
            data = data,
            expectedUserEmail = expectedUserEmail,
            currentSessionGeneration = currentSessionGeneration,
            expectedSessionGeneration = expectedSessionGeneration,
            parser = parser
        )
        when (result) {
            is DriveAuthorizationResult.Success -> {
                if (completed.compareAndSet(false, true)) {
                    onSuccess()
                }
            }
            is DriveAuthorizationResult.Cancelled -> {
                if (completed.compareAndSet(false, true)) {
                    onCancelled()
                }
            }
            is DriveAuthorizationResult.AccountMismatch -> {
                if (completed.compareAndSet(false, true)) {
                    val msg = context?.getString(R.string.drive_permission_account_mismatch)
                        ?: "Tài khoản cấp quyền Drive (${result.grantedEmail}) không khớp với tài khoản đang đăng nhập (${result.expectedEmail})"
                    onError(msg)
                }
            }
            is DriveAuthorizationResult.SessionExpiredOrChanged -> {
                Log.w(TAG, "Discarded Drive authorization result due to session change")
            }
            is DriveAuthorizationResult.PermissionDenied -> {
                if (completed.compareAndSet(false, true)) {
                    onError("Quyền truy cập Google Drive bị từ chối")
                }
            }
            is DriveAuthorizationResult.Failure -> {
                if (completed.compareAndSet(false, true)) {
                    onError(result.errorMessage)
                }
            }
        }
    }
}
