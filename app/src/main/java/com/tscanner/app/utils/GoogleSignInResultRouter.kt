package com.tscanner.app.utils

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.util.Log
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.common.api.ApiException
import com.tscanner.app.R
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.cancellation.CancellationException

/**
 * Parsed Google Account representation separated from the Android/GMS SDK.
 */
data class GoogleSignInAccountData(
    val id: String?,
    val email: String?,
    val displayName: String?,
    val givenName: String?,
    val familyName: String?,
    val photoUrl: String?,
    val idToken: String?,
    val grantedScopes: Set<String> = emptySet()
)

/**
 * Interface abstracting Google Sign-In SDK parser for testing and production seams.
 */
interface GoogleSignInAccountParser {
    @Throws(Exception::class)
    fun parseAccountFromIntent(data: Intent?): GoogleSignInAccountData?
}

/**
 * Default production parser delegating directly to GoogleSignIn SDK.
 */
class DefaultGoogleSignInAccountParser : GoogleSignInAccountParser {
    override fun parseAccountFromIntent(data: Intent?): GoogleSignInAccountData? {
        if (data == null) return null
        val task = GoogleSignIn.getSignedInAccountFromIntent(data)
        val account = task.getResult(ApiException::class.java) ?: return null
        val grantedScopes = try {
            account.grantedScopes.map { it.scopeUri }.toSet()
        } catch (e: Exception) {
            emptySet()
        }
        return GoogleSignInAccountData(
            id = account.id,
            email = account.email,
            displayName = account.displayName,
            givenName = account.givenName,
            familyName = account.familyName,
            photoUrl = account.photoUrl?.toString(),
            idToken = account.idToken,
            grantedScopes = grantedScopes
        )
    }
}

/**
 * Standardized result of a Google Sign-In attempt.
 */
sealed class GoogleSignInResult {
    data class Success(val accountData: GoogleSignInAccountData) : GoogleSignInResult()
    object Cancelled : GoogleSignInResult()
    data class Failure(val statusCode: Int?, val errorMessage: String) : GoogleSignInResult()
}

/**
 * Router that routes Activity results from Google Sign-In intent into typed outcomes,
 * ensuring:
 * 1. Errors returned with RESULT_CANCELED are properly parsed and not swallowed.
 * 2. Pure user cancellation (null intent + RESULT_CANCELED or 12501) ends quietly without error popups.
 * 3. Abnormal/malformed results do not crash and report failure.
 * 4. Callbacks are guaranteed to be called at most once (never called twice).
 * 5. Configuration error code 10 is described neutrally without speculating about debug SHA-1 or test users.
 */
object GoogleSignInResultRouter {
    private const val TAG = "GoogleSignInRouter"

    const val STATUS_DEVELOPER_ERROR = 10
    const val STATUS_SIGN_IN_FAILED = 12500
    const val STATUS_SIGN_IN_CANCELLED = 12501

    fun routeResult(
        context: Context?,
        resultCode: Int,
        data: Intent?,
        parser: GoogleSignInAccountParser = DefaultGoogleSignInAccountParser()
    ): GoogleSignInResult {
        // Case 1: Null intent
        if (data == null) {
            return if (resultCode == Activity.RESULT_CANCELED) {
                Log.i(TAG, "[AuthLifecycle] stage=FALLBACK_RESULT status=USER_CANCELLED_NULL_INTENT")
                // User pressed back or dismissed dialog without returning an intent
                GoogleSignInResult.Cancelled
            } else {
                Log.w(TAG, "[AuthLifecycle] stage=FALLBACK_RESULT status=EMPTY_INTENT_ERROR resultCode=$resultCode")
                val errorMsg = context?.getString(R.string.google_signin_error_no_account)
                    ?: "Không thể lấy thông tin tài khoản Google (Dữ liệu trả về trống)"
                GoogleSignInResult.Failure(statusCode = null, errorMessage = errorMsg)
            }
        }

        // Case 2: Intent present - parse via parser
        return try {
            val account = parser.parseAccountFromIntent(data)
            if (account != null) {
                Log.i(TAG, "[AuthLifecycle] stage=FALLBACK_RESULT status=SUCCESS")
                GoogleSignInResult.Success(account)
            } else {
                Log.w(TAG, "[AuthLifecycle] stage=FALLBACK_RESULT status=NO_ACCOUNT")
                val errorMsg = context?.getString(R.string.google_signin_error_no_account)
                    ?: "Không thể lấy thông tin tài khoản Google"
                GoogleSignInResult.Failure(statusCode = null, errorMessage = errorMsg)
            }
        } catch (e: ApiException) {
            val code = e.statusCode
            // Log status code and message only; never log ID token or sensitive credentials
            Log.w(TAG, "[AuthLifecycle] stage=FALLBACK_RESULT status=API_EXCEPTION statusCode=$code message=${AppAuthManager.sanitizeForLog(e.message)}")
            if (code == STATUS_SIGN_IN_CANCELLED) {
                GoogleSignInResult.Cancelled
            } else {
                val errorMsg = formatErrorMessage(context, code)
                GoogleSignInResult.Failure(statusCode = code, errorMessage = errorMsg)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "[AuthLifecycle] stage=FALLBACK_RESULT status=UNEXPECTED error=${AppAuthManager.sanitizeForLog(e.message)}", e)
            val errorMsg = e.localizedMessage
                ?: (context?.getString(R.string.google_signin_error_no_account) ?: "Lỗi xác thực không xác định")
            GoogleSignInResult.Failure(statusCode = null, errorMessage = errorMsg)
        }
    }

    fun formatErrorMessage(context: Context?, statusCode: Int): String {
        return when (statusCode) {
            STATUS_DEVELOPER_ERROR -> {
                context?.getString(R.string.google_signin_error_config_10)
                    ?: "Lỗi cấu hình dịch vụ Google (Mã lỗi: 10). Vui lòng kiểm tra lại cấu hình ứng dụng trên Google Play hoặc Google Cloud Console."
            }
            STATUS_SIGN_IN_FAILED -> {
                context?.getString(R.string.google_signin_error_play_services_12500)
                    ?: "Lỗi xác thực Google Play (Mã lỗi: 12500). Vui lòng kiểm tra dịch vụ Google Play trên thiết bị."
            }
            else -> {
                if (context != null) {
                    try {
                        context.getString(R.string.google_signin_error_general_format, statusCode)
                    } catch (_: Exception) {
                        "Đăng nhập Google thất bại (Mã lỗi: $statusCode)"
                    }
                } else {
                    "Đăng nhập Google thất bại (Mã lỗi: $statusCode)"
                }
            }
        }
    }

    /**
     * Dispatches the result guaranteeing single callback invocation.
     */
    fun dispatchResult(
        context: Context?,
        resultCode: Int,
        data: Intent?,
        parser: GoogleSignInAccountParser = DefaultGoogleSignInAccountParser(),
        onSuccess: (GoogleSignInAccountData) -> Unit,
        onCancelled: () -> Unit,
        onError: (String) -> Unit
    ) {
        val completed = AtomicBoolean(false)
        val result = routeResult(context, resultCode, data, parser)
        when (result) {
            is GoogleSignInResult.Success -> {
                if (completed.compareAndSet(false, true)) {
                    Log.i(TAG, "[AuthLifecycle] stage=UI status=FALLBACK_DISPATCH_SUCCESS")
                    onSuccess(result.accountData)
                }
            }
            is GoogleSignInResult.Cancelled -> {
                if (completed.compareAndSet(false, true)) {
                    Log.i(TAG, "[AuthLifecycle] stage=UI status=FALLBACK_DISPATCH_CANCELLED")
                    onCancelled()
                }
            }
            is GoogleSignInResult.Failure -> {
                if (completed.compareAndSet(false, true)) {
                    Log.i(TAG, "[AuthLifecycle] stage=UI status=FALLBACK_DISPATCH_ERROR statusCode=${result.statusCode}")
                    onError(result.errorMessage)
                }
            }
        }
    }
}
