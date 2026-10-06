package com.tscanner.app.utils.billing

import android.util.Log
import com.android.billingclient.api.BillingClient
import com.android.billingclient.api.BillingClientStateListener
import com.android.billingclient.api.BillingResult
import com.tscanner.app.utils.BillingManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Coordinates connection lifecycle, request coalescing, and waiter queueing
 * for Google Play BillingClient (F07).
 *
 * Guarantees:
 * 1. Concurrent connection calls while CONNECTING are coalesced into pendingWaiters.
 * 2. All waiters are notified exactly once on setup finish, disconnect, or teardown.
 * 3. Stale callbacks from earlier generations/disconnections are dropped safely.
 * 4. Teardown (close) permanently closes the coordinator and prevents zombie reconnects.
 * 5. Reconnection attempts are strictly finite.
 */
class BillingConnectionCoordinator(
    private val clientProvider: () -> BillingManager.BillingClientWrapper,
    private val onConnected: () -> Unit = {},
    private val onDisconnected: () -> Unit = {},
    val maxReconnectAttempts: Int = 3
) {
    companion object {
        private const val TAG = "BillingConnectionCoord"
    }

    private val lock = Any()
    private val pendingWaiters = mutableListOf<(Boolean) -> Unit>()

    private val _connectionState = MutableStateFlow(BillingManager.ConnectionState.DISCONNECTED)
    val connectionState: StateFlow<BillingManager.ConnectionState> = _connectionState.asStateFlow()
    val state: BillingManager.ConnectionState
        get() = _connectionState.value

    @Volatile
    var generation: Long = 0L
        private set

    @Volatile
    var reconnectAttempts: Int = 0
        private set

    val isReady: Boolean
        get() = _connectionState.value == BillingManager.ConnectionState.CONNECTED && clientProvider().isReady

    val pendingWaitersCount: Int
        get() = synchronized(lock) { pendingWaiters.size }

    /**
     * Connects to Google Play Billing service.
     * Coalesces concurrent calls when CONNECTING.
     * If already CONNECTED, immediately returns true.
     * If CLOSED, immediately returns false.
     */
    fun startConnection(onSetupFinished: ((Boolean) -> Unit)? = null) {
        var directResult: Boolean? = null
        val currentGen: Long

        synchronized(lock) {
            when (_connectionState.value) {
                BillingManager.ConnectionState.CONNECTED -> {
                    directResult = true
                    currentGen = generation
                }
                BillingManager.ConnectionState.CLOSED -> {
                    directResult = false
                    currentGen = generation
                }
                BillingManager.ConnectionState.CONNECTING -> {
                    if (onSetupFinished != null) {
                        pendingWaiters.add(onSetupFinished)
                    }
                    currentGen = generation
                }
                BillingManager.ConnectionState.DISCONNECTED -> {
                    _connectionState.value = BillingManager.ConnectionState.CONNECTING
                    currentGen = ++generation
                    if (onSetupFinished != null) {
                        pendingWaiters.add(onSetupFinished)
                    }

                    try {
                        val client = clientProvider()
                        client.startConnection(object : BillingClientStateListener {
                            override fun onBillingSetupFinished(billingResult: BillingResult) {
                                handleBillingSetupFinished(currentGen, billingResult)
                            }

                            override fun onBillingServiceDisconnected() {
                                handleBillingServiceDisconnected(currentGen)
                            }
                        })
                    } catch (e: Exception) {
                        Log.e(TAG, "Error initiating BillingClient connection (gen $currentGen)", e)
                        _connectionState.value = BillingManager.ConnectionState.DISCONNECTED
                        val waiters = drainWaiters()
                        waiters.forEach { it.invoke(false) }
                        return
                    }
                }
            }
        }

        if (directResult != null) {
            onSetupFinished?.invoke(directResult)
        }
    }

    /**
     * Handles connection result from BillingClient.
     * Enforces monotonic generation check to discard stale callbacks.
     */
    fun handleBillingSetupFinished(callbackGen: Long, billingResult: BillingResult) {
        val waitersToNotify: List<(Boolean) -> Unit>
        val isSuccess: Boolean

        synchronized(lock) {
            if (_connectionState.value == BillingManager.ConnectionState.CLOSED) {
                Log.w(TAG, "Ignoring setup callback for gen $callbackGen because coordinator is CLOSED")
                return
            }
            if (callbackGen != generation) {
                Log.w(TAG, "Ignoring stale setup callback for gen $callbackGen (current: $generation)")
                return
            }

            if (billingResult.responseCode == BillingClient.BillingResponseCode.OK) {
                Log.i(TAG, "BillingClient connected successfully (gen $callbackGen)")
                _connectionState.value = BillingManager.ConnectionState.CONNECTED
                reconnectAttempts = 0
                isSuccess = true
            } else {
                Log.w(TAG, "Billing setup failed with responseCode: ${billingResult.responseCode} (gen $callbackGen)")
                _connectionState.value = BillingManager.ConnectionState.DISCONNECTED
                isSuccess = false
            }

            waitersToNotify = drainWaiters()
        }

        if (isSuccess) {
            try {
                onConnected()
            } catch (e: Exception) {
                Log.e(TAG, "Error in onConnected callback", e)
            }
        }

        for (waiter in waitersToNotify) {
            try {
                waiter.invoke(isSuccess)
            } catch (e: Exception) {
                Log.e(TAG, "Error notifying setup waiter", e)
            }
        }
    }

    /**
     * Handles service disconnection.
     * Invalidates current generation so late callbacks are ignored.
     */
    fun handleBillingServiceDisconnected(callbackGen: Long) {
        val waitersToNotify: List<(Boolean) -> Unit>

        synchronized(lock) {
            if (_connectionState.value == BillingManager.ConnectionState.CLOSED) return
            if (callbackGen != generation) {
                Log.w(TAG, "Ignoring stale disconnect callback for gen $callbackGen (current: $generation)")
                return
            }

            Log.w(TAG, "Billing service disconnected (gen $callbackGen)")
            _connectionState.value = BillingManager.ConnectionState.DISCONNECTED
            generation++
            waitersToNotify = drainWaiters()
        }

        for (waiter in waitersToNotify) {
            try {
                waiter.invoke(false)
            } catch (e: Exception) {
                Log.e(TAG, "Error notifying waiter on disconnect", e)
            }
        }

        try {
            onDisconnected()
        } catch (e: Exception) {
            Log.e(TAG, "Error in onDisconnected callback", e)
        }
    }

    /**
     * Finite retry with attempt counter guard.
     * Returns true if retry connection was initiated, false if max retries exceeded or closed.
     */
    fun retryConnection(onSetupFinished: ((Boolean) -> Unit)? = null): Boolean {
        synchronized(lock) {
            if (_connectionState.value == BillingManager.ConnectionState.CLOSED) return false
            if (reconnectAttempts >= maxReconnectAttempts) {
                Log.e(TAG, "Max reconnect attempts reached ($maxReconnectAttempts)")
                return false
            }
            reconnectAttempts++
        }
        startConnection(onSetupFinished)
        return true
    }

    /**
     * Closes the coordinator and cancels all pending waiters.
     */
    fun close() {
        val waitersToNotify: List<(Boolean) -> Unit>
        synchronized(lock) {
            if (_connectionState.value == BillingManager.ConnectionState.CLOSED) return
            _connectionState.value = BillingManager.ConnectionState.CLOSED
            generation++
            waitersToNotify = drainWaiters()
        }

        for (waiter in waitersToNotify) {
            try {
                waiter.invoke(false)
            } catch (e: Exception) {
                Log.e(TAG, "Error notifying waiter on close", e)
            }
        }
    }

    private fun drainWaiters(): List<(Boolean) -> Unit> {
        val copy = ArrayList(pendingWaiters)
        pendingWaiters.clear()
        return copy
    }
}
