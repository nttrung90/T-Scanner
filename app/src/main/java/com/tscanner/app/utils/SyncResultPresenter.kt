package com.tscanner.app.utils

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.util.Log
import android.widget.Toast
import com.tscanner.app.R
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Lifecycle-safe presentation coordinator for SyncCatalogResult across all app surfaces (S06b/G06).
 *
 * Invariants:
 * 1. Skipped (NotVip / NotLoggedIn) is completely silent (no fake failure toast).
 * 2. Success with added files notifies user and triggers document list refresh.
 * 3. Partial sync informs user with count and partial status.
 * 4. AuthRequired surfaces actionable permission grant prompt without auto-looping consent dialogs.
 * 5. Failure surfaces the error message with actionable retry prompt.
 * 6. Tap action on prompts invokes callback at most once.
 * 7. When host Activity is destroyed or finishing, UI presentation and action lambdas are discarded.
 * 8. Stale session results (from prior generations) are ignored and do not affect the new session.
 * 9. Debounces identical notifications within a brief window, but does not block actionable retry.
 */
object SyncResultPresenter {

    private const val TAG = "SyncResultPresenter"
    private const val DEBOUNCE_WINDOW_MS = 2000L

    @Volatile
    private var lastPresentedTimeMs = 0L
    @Volatile
    private var lastPresentedKey: String? = null

    @Synchronized
    fun shouldPresent(key: String, nowMs: Long = System.currentTimeMillis()): Boolean {
        if (key == lastPresentedKey && (nowMs - lastPresentedTimeMs) < DEBOUNCE_WINDOW_MS) {
            return false
        }
        lastPresentedKey = key
        lastPresentedTimeMs = nowMs
        return true
    }

    data class ActionPrompt(
        val message: String,
        val actionLabel: String?,
        val onAction: (() -> Unit)?,
        val targetSessionGeneration: Long? = null,
        val targetUserId: String? = null
    )

    @androidx.annotation.VisibleForTesting
    var toastPresenter: ((Context, String, Int) -> Unit)? = null

    @androidx.annotation.VisibleForTesting
    var actionPresenter: ((Context, ActionPrompt) -> Unit)? = null

    @androidx.annotation.VisibleForTesting
    fun resetForTesting() {
        lastPresentedTimeMs = 0L
        lastPresentedKey = null
        toastPresenter = null
        actionPresenter = null
    }

    fun findActivity(context: Context?): Activity? {
        var ctx = context
        while (ctx is ContextWrapper) {
            if (ctx is Activity) return ctx
            ctx = ctx.baseContext
        }
        return null
    }

    private fun showToast(context: Context, text: String, duration: Int) {
        val custom = toastPresenter
        if (custom != null) {
            custom(context, text, duration)
            return
        }
        try {
            Toast.makeText(context, text, duration).show()
        } catch (e: Throwable) {
            Log.w(TAG, "Cannot show Toast in current context: ${e.message}")
        }
    }

    private fun showActionablePrompt(
        context: Context,
        message: String,
        actionLabel: String?,
        targetSessionGen: Long?,
        targetUserId: String?,
        isHostValid: (() -> Boolean)?,
        onAction: (() -> Unit)?
    ) {
        val safeAction: (() -> Unit)? = if (onAction != null) {
            val executed = AtomicBoolean(false)
            val actionLambda: () -> Unit = actionLambda@{
                // Tap-time verification:
                // 1. Session generation verification
                val currentGen = AppAuthManager.getSessionGeneration()
                if (targetSessionGen != null && currentGen != targetSessionGen) {
                    Log.w(TAG, "Discarding action tap: session generation changed since render (renderGen=$targetSessionGen, currentGen=$currentGen)")
                    return@actionLambda
                }

                // 2. User ID verification
                val currentUser = AppAuthManager.getCurrentUser()
                if (targetUserId != null && currentUser?.id != targetUserId) {
                    Log.w(TAG, "Discarding action tap: user changed since render (renderUser=$targetUserId, currentUser=${currentUser?.id})")
                    return@actionLambda
                }

                // 3. Host validity check
                val currentActivity = findActivity(context)
                if (currentActivity != null && (currentActivity.isFinishing || currentActivity.isDestroyed)) {
                    Log.w(TAG, "Discarding action tap: host activity is finishing or destroyed")
                    return@actionLambda
                }
                if (isHostValid != null && !isHostValid()) {
                    Log.w(TAG, "Discarding action tap: host view or fragment is no longer valid")
                    return@actionLambda
                }

                // 4. Single-invocation consumption
                if (executed.compareAndSet(false, true)) {
                    lastPresentedKey = null // Clear debounce so retry results can surface
                    onAction()
                }
            }
            actionLambda
        } else null

        val prompt = ActionPrompt(message, actionLabel, safeAction, targetSessionGen, targetUserId)

        val custom = actionPresenter
        if (custom != null) {
            custom(context, prompt)
            return
        }

        val activity = findActivity(context)
        if (activity != null) {
            if (activity.isFinishing || activity.isDestroyed) {
                Log.d(TAG, "Activity is finishing or destroyed, skipping actionable prompt")
                return
            }
            val rootView = activity.findViewById<android.view.View>(android.R.id.content)
            if (rootView != null) {
                try {
                    val snackbar = com.google.android.material.snackbar.Snackbar.make(
                        rootView,
                        message,
                        com.google.android.material.snackbar.Snackbar.LENGTH_LONG
                    )
                    if (actionLabel != null && safeAction != null) {
                        snackbar.setAction(actionLabel) {
                            safeAction()
                        }
                    }
                    snackbar.show()
                    return
                } catch (e: Throwable) {
                    Log.w(TAG, "Cannot show Snackbar: ${e.message}")
                }
            }
        }
        showToast(context.applicationContext ?: context, message, Toast.LENGTH_LONG)
    }

    private fun resolveString(context: Context, resId: Int, vararg formatArgs: Any): String {
        return try {
            if (formatArgs.isEmpty()) {
                context.getString(resId)
            } else {
                context.getString(resId, *formatArgs)
            }
        } catch (e: Throwable) {
            "res_$resId"
        }
    }

    /**
     * Primary presentation entry point for production UI callers (H04b).
     * Enforces explicit origin session generation, origin user ID, and host validity predicate.
     */
    fun present(
        context: Context,
        result: SyncCatalogResult,
        expectedSessionGeneration: Long,
        expectedUserId: String?,
        isHostValid: (() -> Boolean)?,
        onRequestDrivePermission: (() -> Unit)? = null,
        onRetry: (() -> Unit)? = null,
        onDocumentsAdded: ((Int) -> Unit)? = null
    ) {
        val currentGen = AppAuthManager.getSessionGeneration()

        // Invariant: Ignore stale session results
        if (expectedSessionGeneration != currentGen) {
            Log.d(TAG, "Ignoring stale sync result from session generation $expectedSessionGeneration (current=$currentGen)")
            return
        }

        // Invariant: Discard presentation if host activity is already destroyed or finishing
        val activity = findActivity(context)
        if (activity != null && (activity.isFinishing || activity.isDestroyed)) {
            Log.d(TAG, "Host activity is finishing or destroyed, discarding presentation")
            return
        }
        if (isHostValid != null && !isHostValid()) {
            Log.d(TAG, "Host view/fragment is not valid, discarding presentation")
            return
        }

        val appContext = context.applicationContext ?: context
        when (result) {
            is SyncCatalogResult.Success -> {
                if (result.addedCount > 0) {
                    onDocumentsAdded?.invoke(result.addedCount)
                    if (shouldPresent("success_${result.addedCount}")) {
                        val text = resolveString(appContext, R.string.synced_docs_from_drive_format, result.addedCount)
                        showToast(appContext, text, Toast.LENGTH_SHORT)
                    }
                } else {
                    Log.d(TAG, "Sync success with 0 new files, keeping UI quiet")
                }
            }
            is SyncCatalogResult.Partial -> {
                if (result.addedCount > 0) {
                    onDocumentsAdded?.invoke(result.addedCount)
                }
                if (shouldPresent("partial_${result.addedCount}_${result.partialDriveFiles}")) {
                    val text = resolveString(appContext, R.string.sync_catalog_partial_format, result.addedCount, result.partialDriveFiles)
                    showToast(appContext, text, Toast.LENGTH_SHORT)
                }
            }
            is SyncCatalogResult.AuthRequired -> {
                Log.w(TAG, "Sync catalog requires Drive permission: ${result.error}")
                if (shouldPresent("auth_required")) {
                    val message = resolveString(appContext, R.string.sync_catalog_auth_required)
                    val actionLabel = if (onRequestDrivePermission != null) {
                        resolveString(appContext, R.string.grant_permission)
                    } else null
                    showActionablePrompt(
                        context = context,
                        message = message,
                        actionLabel = actionLabel,
                        targetSessionGen = expectedSessionGeneration,
                        targetUserId = expectedUserId,
                        isHostValid = isHostValid,
                        onAction = onRequestDrivePermission
                    )
                }
            }
            is SyncCatalogResult.Failure -> {
                Log.e(TAG, "Sync catalog failure: ${result.error}")
                if (shouldPresent("failure_${result.error}")) {
                    val message = resolveString(appContext, R.string.sync_catalog_failed_format, result.error)
                    val actionLabel = if (onRetry != null) {
                        resolveString(appContext, R.string.retry)
                    } else null
                    showActionablePrompt(
                        context = context,
                        message = message,
                        actionLabel = actionLabel,
                        targetSessionGen = expectedSessionGeneration,
                        targetUserId = expectedUserId,
                        isHostValid = isHostValid,
                        onAction = onRetry
                    )
                }
            }
            is SyncCatalogResult.Skipped -> {
                Log.d(TAG, "Sync skipped: ${result.reason}")
            }
        }
    }

    /**
     * Testing convenience overload allowing default parameters.
     */
    @androidx.annotation.VisibleForTesting
    fun present(
        context: Context,
        result: SyncCatalogResult,
        onRequestDrivePermission: (() -> Unit)? = null,
        onRetry: (() -> Unit)? = null,
        onDocumentsAdded: ((Int) -> Unit)? = null,
        expectedSessionGeneration: Long? = null,
        expectedUserId: String? = null,
        isHostValid: (() -> Boolean)? = null
    ) {
        val currentGen = expectedSessionGeneration ?: AppAuthManager.getSessionGeneration()
        val currentUser = expectedUserId ?: AppAuthManager.getCurrentUser()?.id
        present(
            context = context,
            result = result,
            expectedSessionGeneration = currentGen,
            expectedUserId = currentUser,
            isHostValid = isHostValid,
            onRequestDrivePermission = onRequestDrivePermission,
            onRetry = onRetry,
            onDocumentsAdded = onDocumentsAdded
        )
    }
}
