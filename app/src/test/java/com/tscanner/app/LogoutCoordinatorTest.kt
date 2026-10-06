package com.tscanner.app

import com.tscanner.app.utils.LogoutCoordinator
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Unit tests for [LogoutCoordinator] operation & task state machine (J01).
 *
 * Verifies:
 * 1. Task pending + coroutine completed still gates.
 * 2. Task complete unlocks waiter.
 * 3. Logout A -> B when waiter A is waiting does not unlock prematurely.
 * 4. Task A late callback does not complete Task B.
 * 5. Canceled-before-provider-start does not deadlock.
 * 6. Timeout waiter does not change task state.
 * 7. Duplicate completion is idempotent.
 */
class LogoutCoordinatorTest {

    @Before
    fun setUp() {
        LogoutCoordinator.resetForTesting()
    }

    @After
    fun tearDown() {
        LogoutCoordinator.resetForTesting()
    }

    @Test
    fun taskPendingAndCoroutineCompletedStillGates() {
        runBlocking {
            val op = LogoutCoordinator.startLogout()
            LogoutCoordinator.markProviderTaskStarted(op, "task_1")
            LogoutCoordinator.onCleanupCompleted(op)

            assertTrue("Provider task should be considered cleaning/pending", LogoutCoordinator.isCleaningProvider())
            assertTrue("Pending task should exist", LogoutCoordinator.hasPendingProviderTasks())
            assertFalse("Waiter must gate while provider task is alive", LogoutCoordinator.awaitProviderCleanup(10))
        }
    }

    @Test
    fun taskCompleteUnlocksWaiter() {
        runBlocking {
            val op = LogoutCoordinator.startLogout()
            LogoutCoordinator.markProviderTaskStarted(op, "task_1")
            LogoutCoordinator.onCleanupCompleted(op)

            // Later, provider SDK task completes
            LogoutCoordinator.markProviderTaskCompleted(op, "task_1")

            assertFalse("Should no longer be cleaning provider", LogoutCoordinator.isCleaningProvider())
            assertFalse("Should no longer have pending provider tasks", LogoutCoordinator.hasPendingProviderTasks())
            assertTrue("Waiter must unlock when both coroutine and tasks are complete", LogoutCoordinator.awaitProviderCleanup(10))
        }
    }

    @Test
    fun logoutAToBWhileWaiterAIsWaitingDoesNotUnlockPrematurely() {
        runBlocking {
            val opA = LogoutCoordinator.startLogout()
            val waiter = async(start = CoroutineStart.UNDISPATCHED) {
                LogoutCoordinator.awaitProviderCleanup(50)
            }

            // Operation B replaces operation A while waiter is waiting
            val opB = LogoutCoordinator.startLogout()

            // Waiter A must NOT be unlocked with true while op B is still active
            assertFalse("Waiter A must not grant login while operation B is active", waiter.await())

            // Clean up op B: op A is still running in background, so login must still be gated
            LogoutCoordinator.onCleanupCompleted(opB)
            assertFalse("Login must still be gated while op A is still running", LogoutCoordinator.awaitProviderCleanup(10))

            // Once op A also explicitly completes, subsequent login is allowed
            LogoutCoordinator.onCleanupCompleted(opA)
            assertTrue("After both op A and op B complete, subsequent login is allowed", LogoutCoordinator.awaitProviderCleanup(10))
        }
    }

    @Test
    fun taskALateCallbackDoesNotCompleteTaskB() {
        runBlocking {
            val opA = LogoutCoordinator.startLogout()
            LogoutCoordinator.markProviderTaskStarted(opA, "shared_task_id")

            val opB = LogoutCoordinator.startLogout()
            LogoutCoordinator.markProviderTaskStarted(opB, "shared_task_id")

            // Late callback from op A completes its own task
            LogoutCoordinator.markProviderTaskCompleted(opA, "shared_task_id")
            LogoutCoordinator.onCleanupCompleted(opA)

            // Op B coroutine completes, but its task is still pending
            LogoutCoordinator.onCleanupCompleted(opB)

            assertTrue("Op B's task must still be pending", LogoutCoordinator.hasPendingProviderTasks())
            assertFalse("Login must remain gated because Op B's task was NOT completed by Op A", LogoutCoordinator.awaitProviderCleanup(10))

            // Op B's task completes
            LogoutCoordinator.markProviderTaskCompleted(opB, "shared_task_id")
            assertTrue("Now login can proceed", LogoutCoordinator.awaitProviderCleanup(10))
        }
    }

    @Test
    fun canceledBeforeProviderStartDoesNotDeadlock() {
        runBlocking {
            val op = LogoutCoordinator.startLogout()
            // Coroutine cancelled before any provider task started
            LogoutCoordinator.onCleanupCancelled(op)

            assertFalse("Should not be cleaning", LogoutCoordinator.isCleaningProvider())
            assertFalse("No tasks should be pending", LogoutCoordinator.hasPendingProviderTasks())
            assertTrue("Should unlock immediately without deadlock", LogoutCoordinator.awaitProviderCleanup(10))
        }
    }

    @Test
    fun timeoutWaiterDoesNotChangeTaskState() {
        runBlocking {
            val op = LogoutCoordinator.startLogout()
            LogoutCoordinator.markProviderTaskStarted(op, "task_still_running")

            // Waiter times out
            val result = LogoutCoordinator.awaitProviderCleanup(10)
            assertFalse("Waiter should have timed out and returned false", result)

            // Task state must be completely unchanged by the waiter timeout
            assertTrue("Task must still be recorded as pending", LogoutCoordinator.hasPendingProviderTasks())
            assertTrue("Coordinator must still be cleaning provider", LogoutCoordinator.isCleaningProvider())
        }
    }

    @Test
    fun duplicateCompletionIsIdempotent() {
        runBlocking {
            val op = LogoutCoordinator.startLogout()
            LogoutCoordinator.markProviderTaskStarted(op, "task_1")

            // Complete task multiple times
            LogoutCoordinator.markProviderTaskCompleted(op, "task_1")
            LogoutCoordinator.markProviderTaskCompleted(op, "task_1")

            // Complete coroutine multiple times
            LogoutCoordinator.onCleanupCompleted(op)
            LogoutCoordinator.onCleanupCompleted(op)

            assertFalse(LogoutCoordinator.isCleaningProvider())
            assertTrue(LogoutCoordinator.awaitProviderCleanup(10))
        }
    }

    @Test
    fun reverseCompletionOrderStillGatesUntilAllFinished() {
        runBlocking {
            val opA = LogoutCoordinator.startLogout()
            val opB = LogoutCoordinator.startLogout()

            // Complete opA first, opB is still running
            LogoutCoordinator.onCleanupCompleted(opA)
            assertFalse("Login must gate while opB is still running", LogoutCoordinator.awaitProviderCleanup(10))

            // Now complete opB
            LogoutCoordinator.onCleanupCompleted(opB)
            assertTrue("Both completed, login is allowed", LogoutCoordinator.awaitProviderCleanup(10))
        }
    }

    @Test
    fun operationAWithPendingTaskAndBFinishedStillGatesUntilTaskCompletes() {
        runBlocking {
            val opA = LogoutCoordinator.startLogout()
            LogoutCoordinator.markProviderTaskStarted(opA, "task_a")

            val opB = LogoutCoordinator.startLogout()
            LogoutCoordinator.onCleanupCompleted(opB)

            // OpA coroutine completes, but task_a is still pending
            LogoutCoordinator.onCleanupCompleted(opA)
            assertFalse("Login must gate while task_a is still pending", LogoutCoordinator.awaitProviderCleanup(10))

            // task_a completes
            LogoutCoordinator.markProviderTaskCompleted(opA, "task_a")
            assertTrue("All tasks and coroutines complete, login is allowed", LogoutCoordinator.awaitProviderCleanup(10))
        }
    }

    @Test
    fun operationBCancelledBeforeStartWhileAIsRunningStillGatesUntilACompletes() {
        runBlocking {
            val opA = LogoutCoordinator.startLogout()
            val opB = LogoutCoordinator.startLogout()

            // OpB cancelled before provider start
            LogoutCoordinator.onCleanupCancelled(opB)

            // OpA is still running -> must gate
            assertFalse("Login must gate while opA is still running", LogoutCoordinator.awaitProviderCleanup(10))

            // OpA finishes
            LogoutCoordinator.onCleanupCompleted(opA)
            assertTrue("OpA complete, login is allowed", LogoutCoordinator.awaitProviderCleanup(10))
        }
    }

    @Test
    fun multipleConcurrentWaitersAllUnlockWhenOperationsComplete() {
        runBlocking {
            val opA = LogoutCoordinator.startLogout()
            val opB = LogoutCoordinator.startLogout()

            val waiter1 = async(start = CoroutineStart.UNDISPATCHED) { LogoutCoordinator.awaitProviderCleanup(200) }
            val waiter2 = async(start = CoroutineStart.UNDISPATCHED) { LogoutCoordinator.awaitProviderCleanup(200) }
            val waiter3 = async(start = CoroutineStart.UNDISPATCHED) { LogoutCoordinator.awaitProviderCleanup(200) }

            // Partial completion does not release
            LogoutCoordinator.onCleanupCompleted(opA)

            // All operations complete
            LogoutCoordinator.onCleanupCompleted(opB)

            assertTrue("Waiter 1 must unlock", waiter1.await())
            assertTrue("Waiter 2 must unlock", waiter2.await())
            assertTrue("Waiter 3 must unlock", waiter3.await())
        }
    }

    @Test
    fun timeoutThenSubsequentCompletionAllowsFutureLogin() {
        runBlocking {
            val opA = LogoutCoordinator.startLogout()

            // Waiter times out
            val timedOut = LogoutCoordinator.awaitProviderCleanup(10)
            assertFalse("Should time out", timedOut)

            // opA subsequently completes
            LogoutCoordinator.onCleanupCompleted(opA)

            // Subsequent waiter succeeds immediately
            assertTrue("Subsequent waiter must succeed", LogoutCoordinator.awaitProviderCleanup(10))
        }
    }
}

