package com.tscanner.app

import com.android.billingclient.api.BillingClient
import com.android.billingclient.api.BillingClientStateListener
import com.android.billingclient.api.BillingResult
import com.tscanner.app.utils.BillingManager
import com.tscanner.app.utils.billing.BillingConnectionCoordinator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

class BillingConnectionCoordinatorTest {

    private lateinit var fakeClient: FakeBillingClientWrapper

    @Before
    fun setUp() {
        fakeClient = FakeBillingClientWrapper()
    }

    private class FakeBillingClientWrapper : BillingManager.BillingClientWrapper {
        override var isReady: Boolean = false
        var startConnectionCallCount = 0
        var lastListener: BillingClientStateListener? = null

        override fun startConnection(listener: BillingClientStateListener) {
            startConnectionCallCount++
            lastListener = listener
        }

        override fun endConnection() {
            isReady = false
        }

        override fun queryProductDetailsAsync(
            params: com.android.billingclient.api.QueryProductDetailsParams,
            listener: (BillingResult, com.android.billingclient.api.QueryProductDetailsResult) -> Unit
        ) {}

        override fun launchBillingFlow(
            activity: android.app.Activity,
            params: com.android.billingclient.api.BillingFlowParams
        ): BillingResult {
            return BillingResult.newBuilder().setResponseCode(BillingClient.BillingResponseCode.OK).build()
        }

        override fun acknowledgePurchase(
            params: com.android.billingclient.api.AcknowledgePurchaseParams,
            listener: (BillingResult) -> Unit
        ) {}

        override fun queryPurchasesAsync(
            params: com.android.billingclient.api.QueryPurchasesParams,
            listener: (BillingResult, List<com.android.billingclient.api.Purchase>) -> Unit
        ) {}
    }

    private fun okResult(): BillingResult =
        BillingResult.newBuilder()
            .setResponseCode(BillingClient.BillingResponseCode.OK)
            .build()

    private fun errorResult(code: Int = BillingClient.BillingResponseCode.SERVICE_UNAVAILABLE): BillingResult =
        BillingResult.newBuilder()
            .setResponseCode(code)
            .setDebugMessage("Service failure: $code")
            .build()

    @Test
    fun testStartConnection_whenDisconnected_transitionsToConnecting() {
        val coordinator = BillingConnectionCoordinator(clientProvider = { fakeClient })
        assertEquals(BillingManager.ConnectionState.DISCONNECTED, coordinator.state)

        var completed = false
        coordinator.startConnection { success -> completed = success }

        assertEquals(BillingManager.ConnectionState.CONNECTING, coordinator.state)
        assertEquals(1, fakeClient.startConnectionCallCount)
        assertNotNull(fakeClient.lastListener)
        assertEquals(1, coordinator.pendingWaitersCount)
        assertFalse(completed)

        // Complete setup
        fakeClient.isReady = true
        fakeClient.lastListener!!.onBillingSetupFinished(okResult())

        assertEquals(BillingManager.ConnectionState.CONNECTED, coordinator.state)
        assertTrue(completed)
        assertEquals(0, coordinator.pendingWaitersCount)
    }

    @Test
    fun testConcurrentWaiters_coalescedAndAllNotifiedOnSuccess() {
        // Regression #1 & #2: Multiple callers (e.g. restore + query) during CONNECTING
        val coordinator = BillingConnectionCoordinator(clientProvider = { fakeClient })
        val waiter1Result = AtomicBoolean(false)
        val waiter2Result = AtomicBoolean(false)

        coordinator.startConnection { success -> waiter1Result.set(success) }
        coordinator.startConnection { success -> waiter2Result.set(success) }

        // Must coalesce into a single underlying client startConnection call
        assertEquals(1, fakeClient.startConnectionCallCount)
        assertEquals(2, coordinator.pendingWaitersCount)
        assertEquals(BillingManager.ConnectionState.CONNECTING, coordinator.state)

        // Trigger success
        fakeClient.isReady = true
        fakeClient.lastListener!!.onBillingSetupFinished(okResult())

        assertEquals(BillingManager.ConnectionState.CONNECTED, coordinator.state)
        assertTrue("Waiter 1 must complete with true", waiter1Result.get())
        assertTrue("Waiter 2 must complete with true", waiter2Result.get())
        assertEquals(0, coordinator.pendingWaitersCount)
    }

    @Test
    fun testRestoreDuringConnecting_receivesFailureOnSetupError() {
        // Regression #1: Waiter queued during CONNECTING receives failure if setup fails
        val coordinator = BillingConnectionCoordinator(clientProvider = { fakeClient })
        var receivedResult: Boolean? = null

        coordinator.startConnection { success -> receivedResult = success }
        assertEquals(BillingManager.ConnectionState.CONNECTING, coordinator.state)

        // Trigger failure
        fakeClient.lastListener!!.onBillingSetupFinished(errorResult())

        assertEquals(BillingManager.ConnectionState.DISCONNECTED, coordinator.state)
        assertEquals(false, receivedResult)
        assertEquals(0, coordinator.pendingWaitersCount)
    }

    @Test
    fun testAlreadyConnected_immediatelyInvokesWaiterWithTrue() {
        val coordinator = BillingConnectionCoordinator(clientProvider = { fakeClient })
        coordinator.startConnection()
        fakeClient.isReady = true
        fakeClient.lastListener!!.onBillingSetupFinished(okResult())
        assertEquals(BillingManager.ConnectionState.CONNECTED, coordinator.state)

        var directResult: Boolean? = null
        coordinator.startConnection { success -> directResult = success }

        assertEquals(true, directResult)
        assertEquals(1, fakeClient.startConnectionCallCount) // No extra call to client
    }

    @Test
    fun testStaleCallbackGuard_ignoresCallbackFromPreviousGeneration() {
        // Regression #5: Stale callback from earlier generation does not corrupt current state
        val coordinator = BillingConnectionCoordinator(clientProvider = { fakeClient })

        // Generation 1 started
        coordinator.startConnection()
        val gen1Listener = fakeClient.lastListener!!
        val gen1 = coordinator.generation

        // Service disconnected -> state DISCONNECTED, generation advances
        gen1Listener.onBillingServiceDisconnected()
        assertEquals(BillingManager.ConnectionState.DISCONNECTED, coordinator.state)
        val gen2 = coordinator.generation
        assertTrue("Generation must advance on disconnect", gen2 > gen1)

        // Generation 2 started
        var gen2Completed: Boolean? = null
        coordinator.startConnection { success -> gen2Completed = success }
        assertEquals(BillingManager.ConnectionState.CONNECTING, coordinator.state)

        // Stale callback from generation 1 arrives now with OK
        gen1Listener.onBillingSetupFinished(okResult())

        // Must still be CONNECTING for generation 2, gen2 waiter must NOT be completed by gen1 callback
        assertEquals(BillingManager.ConnectionState.CONNECTING, coordinator.state)
        assertEquals(null, gen2Completed)

        // Gen2 callback arrives
        fakeClient.isReady = true
        fakeClient.lastListener!!.onBillingSetupFinished(okResult())
        assertEquals(BillingManager.ConnectionState.CONNECTED, coordinator.state)
        assertEquals(true, gen2Completed)
    }

    @Test
    fun testServiceDisconnected_drainsWaitersWithFalse_andSetsDisconnected() {
        // Regression #3: Disconnect while in flight
        val onDisconnectedFired = AtomicBoolean(false)
        val coordinator = BillingConnectionCoordinator(
            clientProvider = { fakeClient },
            onDisconnected = { onDisconnectedFired.set(true) }
        )

        var waiterCompleted: Boolean? = null
        coordinator.startConnection { success -> waiterCompleted = success }

        // In-flight disconnect
        fakeClient.lastListener!!.onBillingServiceDisconnected()

        assertEquals(BillingManager.ConnectionState.DISCONNECTED, coordinator.state)
        assertEquals(false, waiterCompleted)
        assertTrue(onDisconnectedFired.get())
        assertEquals(0, coordinator.pendingWaitersCount)
    }

    @Test
    fun testRepeatedSetupFailures_allowSubsequentAttempts_withoutReinvokingOldWaiters() {
        // Regression #4: Repeated setup failure resets cleanly
        val coordinator = BillingConnectionCoordinator(clientProvider = { fakeClient })
        val waiter1Invocations = AtomicInteger(0)
        val waiter2Invocations = AtomicInteger(0)

        // Attempt 1 fails
        coordinator.startConnection { success ->
            assertFalse(success)
            waiter1Invocations.incrementAndGet()
        }
        fakeClient.lastListener!!.onBillingSetupFinished(errorResult())
        assertEquals(1, waiter1Invocations.get())
        assertEquals(BillingManager.ConnectionState.DISCONNECTED, coordinator.state)

        // Attempt 2 succeeds
        coordinator.startConnection { success ->
            assertTrue(success)
            waiter2Invocations.incrementAndGet()
        }
        fakeClient.isReady = true
        fakeClient.lastListener!!.onBillingSetupFinished(okResult())

        assertEquals(1, waiter1Invocations.get()) // Old waiter was not reinvoked
        assertEquals(1, waiter2Invocations.get()) // New waiter invoked once
        assertEquals(BillingManager.ConnectionState.CONNECTED, coordinator.state)
    }

    @Test
    fun testTeardownClose_cancelsWaiters_andPreventsNewConnections() {
        // Regression #6: Scope teardown does not revive connection
        val coordinator = BillingConnectionCoordinator(clientProvider = { fakeClient })
        var pendingResult: Boolean? = null

        coordinator.startConnection { success -> pendingResult = success }
        assertEquals(BillingManager.ConnectionState.CONNECTING, coordinator.state)

        coordinator.close()
        assertEquals(BillingManager.ConnectionState.CLOSED, coordinator.state)
        assertEquals(false, pendingResult)

        // New connection attempt on closed coordinator immediately returns false
        var postCloseResult: Boolean? = null
        coordinator.startConnection { success -> postCloseResult = success }
        assertEquals(false, postCloseResult)
        assertEquals(1, fakeClient.startConnectionCallCount) // No new call to client
    }

    @Test
    fun testRetryConnection_finiteAttemptsEnforced() {
        val coordinator = BillingConnectionCoordinator(
            clientProvider = { fakeClient },
            maxReconnectAttempts = 3
        )

        assertTrue(coordinator.retryConnection())
        assertEquals(1, coordinator.reconnectAttempts)
        fakeClient.lastListener!!.onBillingSetupFinished(errorResult())

        assertTrue(coordinator.retryConnection())
        assertEquals(2, coordinator.reconnectAttempts)
        fakeClient.lastListener!!.onBillingSetupFinished(errorResult())

        assertTrue(coordinator.retryConnection())
        assertEquals(3, coordinator.reconnectAttempts)
        fakeClient.lastListener!!.onBillingSetupFinished(errorResult())

        // 4th retry must be rejected due to finite retry limit
        assertFalse(coordinator.retryConnection())
        assertEquals(3, coordinator.reconnectAttempts)
    }
}
