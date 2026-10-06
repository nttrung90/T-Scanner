package com.tscanner.app.utils

import android.util.Log
import androidx.annotation.VisibleForTesting
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicLong

/**
 * Coordinates serialization between asynchronous provider sign-out cleanup
 * and subsequent sign-in attempts (U01/P1 & F01).
 *
 * State transition rules:
 * - Each logout operation has an ID, coroutine state, and set of active provider Tasks.
 * - Coroutine completion/cancellation/failure only changes the coroutine state; it does NOT mark provider Tasks completed.
 * - Provider callbacks must carry operation ID and Task ID; a late callback for operation A only completes task A, never task B.
 * - Starting a new operation B does not erase pending tasks of operation A or prematurely release waiters.
 * - Woken waiters always re-read the authoritative state; completion of any internal signal deferred does not grant login permission.
 * - CancellationException propagates properly through waiters.
 */
object LogoutCoordinator {
    private const val TAG = "LogoutCoordinator"
    const val DEFAULT_TASK_ID = "default_provider_task"

    enum class CoroutineState {
        RUNNING,
        COMPLETED,
        CANCELLED,
        FAILED
    }

    data class OperationRecord(
        val operationId: Long,
        @Volatile var coroutineState: CoroutineState = CoroutineState.RUNNING,
        val pendingTasks: MutableSet<String> = mutableSetOf(),
        val completedTasks: MutableSet<String> = mutableSetOf()
    ) {
        fun isFinished(): Boolean =
            coroutineState != CoroutineState.RUNNING && pendingTasks.isEmpty()
    }

    private val lock = Any()
    private val operationCounter = AtomicLong(0L)

    @Volatile
    private var activeOperationId: Long = 0L

    private val operations = mutableMapOf<Long, OperationRecord>()

    private var completionSignal: CompletableDeferred<Unit> = CompletableDeferred()

    @VisibleForTesting
    var cleanupTimeoutMs: Long = 5000L

    fun startLogout(): Long = synchronized(lock) {
        val opId = operationCounter.incrementAndGet()
        // Clean up any historical operations that are already fully finished
        operations.values.removeAll { it.isFinished() }

        activeOperationId = opId
        val record = OperationRecord(operationId = opId)
        operations[opId] = record
        notifyStateChangeLocked()
        Log.d(TAG, "Started logout operation #$opId (total active operations: ${operations.size})")
        opId
    }

    fun markProviderTaskStarted(
        operationId: Long? = null,
        taskId: String = DEFAULT_TASK_ID
    ): String = synchronized(lock) {
        val targetOpId = operationId ?: activeOperationId
        val record = operations.getOrPut(targetOpId) {
            OperationRecord(operationId = targetOpId)
        }
        record.pendingTasks.add(taskId)
        record.completedTasks.remove(taskId)
        notifyStateChangeLocked()
        Log.d(TAG, "Provider task '$taskId' started for op #$targetOpId (pending: ${record.pendingTasks.size})")
        taskId
    }

    fun markProviderTaskCompleted(
        operationId: Long? = null,
        taskId: String = DEFAULT_TASK_ID
    ): Unit = synchronized(lock) {
        val targetOpId = operationId ?: activeOperationId
        val record = operations[targetOpId]
        if (record != null) {
            val removed = record.pendingTasks.remove(taskId)
            record.completedTasks.add(taskId)
            if (record.isFinished()) {
                operations.remove(targetOpId)
            }
            notifyStateChangeLocked()
            Log.d(TAG, "Provider task '$taskId' completed for op #$targetOpId (wasPending: $removed, remainingPending: ${record.pendingTasks.size})")
        } else {
            // Idempotent completion for already finished/unknown op
            Log.d(TAG, "markProviderTaskCompleted: op #$targetOpId not found (idempotent)")
            notifyStateChangeLocked()
        }
    }

    fun onCleanupCompleted(operationId: Long): Unit = synchronized(lock) {
        val record = operations[operationId]
        if (record != null) {
            record.coroutineState = CoroutineState.COMPLETED
            if (record.isFinished()) {
                operations.remove(operationId)
            }
            notifyStateChangeLocked()
            Log.d(TAG, "Cleanup coroutine completed for op #$operationId (remainingPendingTasks: ${record.pendingTasks.size})")
        } else {
            notifyStateChangeLocked()
        }
    }

    fun onCleanupCancelled(operationId: Long): Unit = synchronized(lock) {
        val record = operations[operationId]
        if (record != null) {
            record.coroutineState = CoroutineState.CANCELLED
            if (record.isFinished()) {
                operations.remove(operationId)
            }
            notifyStateChangeLocked()
            Log.d(TAG, "Cleanup coroutine cancelled for op #$operationId (remainingPendingTasks: ${record.pendingTasks.size})")
        } else {
            notifyStateChangeLocked()
        }
    }

    fun onCleanupFailed(operationId: Long, throwable: Throwable? = null): Unit = synchronized(lock) {
        val record = operations[operationId]
        if (record != null) {
            record.coroutineState = CoroutineState.FAILED
            if (record.isFinished()) {
                operations.remove(operationId)
            }
            notifyStateChangeLocked()
            Log.w(TAG, "Cleanup coroutine failed for op #$operationId: ${throwable?.message}")
        } else {
            notifyStateChangeLocked()
        }
    }

    fun isCleaningProvider(): Boolean = synchronized(lock) {
        isCleaningProviderLocked()
    }

    fun hasPendingProviderTasks(): Boolean = synchronized(lock) {
        operations.values.any { it.pendingTasks.isNotEmpty() }
    }

    fun getActiveOperationId(): Long = activeOperationId

    private fun isCleaningProviderLocked(): Boolean {
        return operations.values.any { !it.isFinished() }
    }

    private fun notifyStateChangeLocked() {
        if (!completionSignal.isCompleted) {
            completionSignal.complete(Unit)
        }
        completionSignal = CompletableDeferred()
    }

    private fun getOrCreateCompletionSignalLocked(): CompletableDeferred<Unit> {
        if (completionSignal.isCompleted) {
            completionSignal = CompletableDeferred()
        }
        return completionSignal
    }

    suspend fun awaitProviderCleanup(timeoutMs: Long = cleanupTimeoutMs): Boolean {
        currentCoroutineContext().ensureActive()

        if (timeoutMs <= 0) {
            return synchronized(lock) { !isCleaningProviderLocked() }
        }

        val startTime = System.currentTimeMillis()
        var remainingMs = timeoutMs

        while (true) {
            val (isClean, signal) = synchronized(lock) {
                if (!isCleaningProviderLocked()) {
                    Pair(true, null)
                } else {
                    Pair(false, getOrCreateCompletionSignalLocked())
                }
            }

            if (isClean) {
                return true
            }

            if (remainingMs <= 0) {
                return synchronized(lock) { !isCleaningProviderLocked() }
            }

            val completed = try {
                withTimeoutOrNull(remainingMs) {
                    signal?.await()
                } != null
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                false
            }

            if (!completed) {
                return synchronized(lock) { !isCleaningProviderLocked() }
            }

            remainingMs = timeoutMs - (System.currentTimeMillis() - startTime)
            if (remainingMs <= 0) {
                return synchronized(lock) { !isCleaningProviderLocked() }
            }
        }
    }

    @VisibleForTesting
    fun resetForTesting(): Unit {
        synchronized(lock) {
            operations.clear()
            activeOperationId = 0L
            cleanupTimeoutMs = 5000L
            notifyStateChangeLocked()
        }
    }
}
