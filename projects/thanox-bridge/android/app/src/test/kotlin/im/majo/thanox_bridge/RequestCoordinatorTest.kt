package im.majo.thanox_bridge

import java.util.ArrayDeque
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RequestCoordinatorTest {
    @Test
    fun normalOverlappingRequestsRunInOrderAndDeadlineStartsAfterDispatch() {
        val harness = Harness()
        val owner = Any()
        val first = Reply()
        val second = Reply()
        val actions = mutableListOf<String>()
        harness.coordinator.submit(owner, false, { actions += "first"; "one" }, first)
        harness.coordinator.submit(owner, false, { actions += "second"; "two" }, second)

        assertTrue(harness.events.delayed.isEmpty())
        harness.runWork()
        harness.events.runNext()
        assertEquals(listOf(60_000L), harness.events.delayed.map { it.second })
        harness.events.runNext()
        assertEquals(listOf("first"), actions)
        assertEquals(listOf("success:one"), first.outcomes)
        assertTrue(second.outcomes.isEmpty())

        harness.runWork()
        harness.events.runNext()
        harness.events.runNext()
        assertEquals(listOf("first", "second"), actions)
        assertEquals(listOf("success:two"), second.outcomes)
        assertTrue(harness.events.delayed.isEmpty())
    }

    @Test
    fun delayedStartNotificationSchedulesOnlyTheRemainingDeadline() {
        val harness = Harness()
        val first = Reply()
        harness.coordinator.submit(Any(), false, { "done" }, first)
        harness.runWork()

        harness.nowMillis = 20_000
        harness.events.runNext()
        assertEquals(listOf(40_000L), harness.events.delayed.map { it.second })
        harness.events.runNext()
        assertEquals(listOf("success:done"), first.outcomes)
    }

    @Test
    fun startNotificationAfterDeadlineTimesOutAndDiscardsLateWriteResult() {
        val harness = Harness()
        val first = Reply()
        val queued = Reply()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val writes = AtomicInteger()
        harness.coordinator.submit(Any(), true, {
            entered.countDown()
            assertTrue(release.await(5, TimeUnit.SECONDS))
            writes.incrementAndGet()
            "late result"
        }, first)
        harness.coordinator.submit(Any(), true, { writes.addAndGet(100) }, queued)
        val thread = harness.startWork()
        assertTrue(entered.await(5, TimeUnit.SECONDS))

        harness.nowMillis = 60_000
        harness.events.runNext()
        assertTrue(harness.events.delayed.isEmpty())
        assertTrue(first.outcomes.single().contains("operation_timeout"))
        assertTrue(first.outcomes.single().contains("结果不确定"))
        assertTrue(queued.outcomes.single().contains("未执行"))

        release.countDown()
        thread.join(5_000)
        assertFalse(thread.isAlive)
        harness.events.runNext()
        assertEquals(1, writes.get())
        assertEquals(1, first.outcomes.size)
        assertTrue(harness.work.isEmpty())
    }

    @Test
    fun timedOutWriteBlocksQueuedAndNewRequestsUntilLateCompletion() {
        val harness = Harness()
        val owner = Any()
        val first = Reply()
        val queued = Reply()
        val rejected = Reply()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val writes = AtomicInteger()
        harness.coordinator.submit(owner, true, {
            entered.countDown()
            assertTrue(release.await(5, TimeUnit.SECONDS))
            writes.incrementAndGet()
            "late result"
        }, first)
        harness.coordinator.submit(owner, true, { writes.addAndGet(100) }, queued)
        val thread = harness.startWork()
        assertTrue(entered.await(5, TimeUnit.SECONDS))
        harness.events.runNext()
        harness.events.fireTimeout()

        assertEquals(1, first.outcomes.size)
        assertTrue(first.outcomes.single().contains("operation_timeout"))
        assertTrue(first.outcomes.single().contains("结果不确定"))
        assertTrue(first.outcomes.single().contains("可能仍会"))
        assertTrue(first.outcomes.single().contains("重启不能保证取消"))
        assertTrue(queued.outcomes.single().contains("operation_busy"))
        assertTrue(queued.outcomes.single().contains("未执行"))
        harness.coordinator.submit(Any(), false, { "should not run" }, rejected)
        assertTrue(rejected.outcomes.single().contains("operation_busy"))
        assertTrue(rejected.outcomes.single().contains("上一次操作尚未结束"))
        assertEquals(0, writes.get())

        release.countDown()
        thread.join(5_000)
        assertFalse(thread.isAlive)
        harness.events.runNext()
        assertEquals(1, writes.get())
        assertEquals(1, first.outcomes.size)
        assertTrue(harness.work.isEmpty())

        val fresh = Reply()
        harness.coordinator.submit(Any(), false, { "fresh" }, fresh)
        harness.runWork()
        harness.events.runNext()
        harness.events.runNext()
        assertEquals(listOf("success:fresh"), fresh.outcomes)
    }

    @Test
    fun staleTimeoutAfterCompletionCannotReplyTwiceOrBlockNextRequest() {
        val harness = Harness()
        val first = Reply()
        harness.coordinator.submit(Any(), true, { "done" }, first)
        harness.runWork()
        harness.events.runNext()
        val staleTimeout = harness.events.delayed.single().first
        harness.events.runNext()
        staleTimeout.run()
        assertEquals(listOf("success:done"), first.outcomes)

        val next = Reply()
        harness.coordinator.submit(Any(), false, { "next" }, next)
        harness.runWork()
        harness.events.runNext()
        harness.events.runNext()
        assertEquals(listOf("success:next"), next.outcomes)
    }

    @Test
    fun timedOutReadIsNotReportedAsAWriteAndLateErrorIsDiscarded() {
        val harness = Harness()
        val read = Reply()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        harness.coordinator.submit(Any(), false, {
            entered.countDown()
            assertTrue(release.await(5, TimeUnit.SECONDS))
            throw IllegalStateException("late failure")
        }, read)
        val thread = harness.startWork()
        assertTrue(entered.await(5, TimeUnit.SECONDS))
        harness.events.runNext()
        harness.events.fireTimeout()
        assertTrue(read.outcomes.single().contains("operation_timeout"))
        assertFalse(read.outcomes.single().contains("结果不确定"))
        release.countDown()
        thread.join(5_000)
        assertFalse(thread.isAlive)
        harness.events.runNext()
        assertEquals(1, read.outcomes.size)
    }

    @Test
    fun replyFailuresDoNotPreventOtherTimedOutQueueEntriesFromBeingRejected() {
        val harness = Harness()
        val owner = Any()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val other = Reply()
        val throwing = object : RequestReply {
            override fun success(value: Any?) { throw IllegalStateException("unexpected success") }
            override fun error(code: String, message: String) { throw IllegalStateException("reply failure") }
        }
        harness.coordinator.submit(owner, true, {
            entered.countDown()
            assertTrue(release.await(5, TimeUnit.SECONDS))
        }, throwing)
        harness.coordinator.submit(owner, true, { error("must not run") }, throwing)
        harness.coordinator.submit(owner, true, { error("must not run") }, other)
        val thread = harness.startWork()
        assertTrue(entered.await(5, TimeUnit.SECONDS))
        harness.events.runNext()
        harness.events.fireTimeout()
        assertEquals(2, harness.deliveryFailures.size)
        assertTrue(other.outcomes.single().contains("operation_busy"))
        assertTrue(other.outcomes.single().contains("未执行"))

        release.countDown()
        thread.join(5_000)
        assertFalse(thread.isAlive)
        harness.events.runNext()
        val fresh = Reply()
        harness.coordinator.submit(Any(), false, { "fresh" }, fresh)
        harness.runWork()
        harness.events.runNext()
        harness.events.runNext()
        assertEquals(listOf("success:fresh"), fresh.outcomes)
    }

    @Test
    fun destroyedOwnerLosesQueuedWriteWhileRunningWorkKeepsTheGate() {
        val harness = Harness()
        val oldOwner = Any()
        val newOwner = Any()
        val running = Reply()
        val oldQueued = Reply()
        val next = Reply()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val writes = AtomicInteger()
        harness.coordinator.submit(oldOwner, true, {
            entered.countDown()
            assertTrue(release.await(5, TimeUnit.SECONDS))
            writes.incrementAndGet()
        }, running)
        harness.coordinator.submit(oldOwner, true, { writes.addAndGet(100) }, oldQueued)
        val thread = harness.startWork()
        assertTrue(entered.await(5, TimeUnit.SECONDS))
        harness.events.runNext()
        harness.coordinator.cancelOwner(oldOwner)
        assertTrue(running.outcomes.single().contains("结果不确定"))
        assertTrue(oldQueued.outcomes.single().contains("未执行"))

        harness.coordinator.submit(newOwner, false, { "new screen" }, next)
        assertTrue(harness.work.isEmpty())
        release.countDown()
        thread.join(5_000)
        assertFalse(thread.isAlive)
        harness.events.runNext()
        assertEquals(1, running.outcomes.size)
        assertEquals(1, writes.get())
        harness.runWork()
        harness.events.runNext()
        harness.events.runNext()
        assertEquals(listOf("success:new screen"), next.outcomes)
    }

    @Test
    fun destroyedOwnerBeforeWorkerStartsCannotExecuteItsWrite() {
        val harness = Harness()
        val owner = Any()
        val reply = Reply()
        val writes = AtomicInteger()
        harness.coordinator.submit(owner, true, { writes.incrementAndGet() }, reply)
        harness.coordinator.cancelOwner(owner)
        assertTrue(reply.outcomes.single().contains("未执行"))
        harness.runWork()
        harness.events.runNext()
        assertEquals(0, writes.get())
        assertEquals(1, reply.outcomes.size)
        assertTrue(harness.events.delayed.isEmpty())
    }

    @Test
    fun workerFailureIsReportedAndNextQueuedRequestStillRuns() {
        val harness = Harness()
        val first = Reply()
        val second = Reply()
        val owner = Any()
        harness.coordinator.submit(owner, false, { throw IllegalStateException("test failure") }, first)
        harness.coordinator.submit(owner, false, { "recovered" }, second)
        harness.runWork()
        harness.events.runNext()
        harness.events.runNext()
        assertEquals(listOf("error:operation_failed:test failure"), first.outcomes)
        harness.runWork()
        harness.events.runNext()
        harness.events.runNext()
        assertEquals(listOf("success:recovered"), second.outcomes)
    }

    @Test
    fun fatalWriteReportsUncertaintyAndHaltsQueuedAndFutureWork() {
        val harness = Harness()
        val owner = Any()
        val first = Reply()
        val queued = Reply()
        val later = Reply()
        val writes = AtomicInteger()
        harness.coordinator.submit(owner, true, {
            writes.incrementAndGet()
            throw AssertionError("fatal write")
        }, first)
        harness.coordinator.submit(owner, true, { writes.addAndGet(100) }, queued)
        harness.runWork()
        harness.events.runNext()
        harness.events.runNext()

        assertEquals(1, writes.get())
        assertTrue(first.outcomes.single().contains("operation_fatal"))
        assertTrue(first.outcomes.single().contains("结果不确定"))
        assertTrue(first.outcomes.single().contains("可能仍会"))
        assertTrue(first.outcomes.single().contains("强行停止本应用不能保证取消"))
        assertTrue(first.outcomes.single().contains("在系统设置中强行停止本应用后重新打开"))
        assertTrue(queued.outcomes.single().contains("operation_busy"))
        assertTrue(queued.outcomes.single().contains("未执行"))
        assertTrue(queued.outcomes.single().contains("强行停止本应用不能保证取消"))
        assertTrue(harness.deliveryFailures.single() is AssertionError)
        harness.coordinator.submit(owner, false, { "must not run" }, later)
        assertTrue(later.outcomes.single().contains("operation_busy"))
        assertTrue(later.outcomes.single().contains("严重错误"))
        assertTrue(later.outcomes.single().contains("强行停止本应用不能保证取消"))
        assertTrue(later.outcomes.single().contains("在系统设置中强行停止本应用后重新打开"))
        assertTrue(harness.work.isEmpty())
    }

    @Test
    fun fatalReadExplainsHowToRestartWithoutClaimingAWrite() {
        val harness = Harness()
        val first = Reply()
        val later = Reply()
        harness.coordinator.submit(Any(), false, { throw AssertionError("fatal read") }, first)
        harness.runWork()
        harness.events.runNext()
        harness.events.runNext()

        assertTrue(first.outcomes.single().contains("operation_fatal"))
        assertTrue(first.outcomes.single().contains("在系统设置中强行停止本应用后重新打开"))
        assertFalse(first.outcomes.single().contains("写入"))
        harness.coordinator.submit(Any(), false, { "must not run" }, later)
        assertTrue(later.outcomes.single().contains("operation_busy"))
        assertTrue(later.outcomes.single().contains("未执行"))
        assertTrue(later.outcomes.single().contains("在系统设置中强行停止本应用后重新打开"))
        assertFalse(later.outcomes.single().contains("写入"))
        assertTrue(harness.work.isEmpty())
    }

    @Test
    fun fatalErrorAfterTimeoutKeepsCoordinatorHaltedWithoutASecondReply() {
        val harness = Harness()
        val owner = Any()
        val first = Reply()
        val queued = Reply()
        val later = Reply()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val writes = AtomicInteger()
        harness.coordinator.submit(owner, true, {
            entered.countDown()
            assertTrue(release.await(5, TimeUnit.SECONDS))
            writes.incrementAndGet()
            throw AssertionError("late fatal write")
        }, first)
        harness.coordinator.submit(owner, true, { writes.addAndGet(100) }, queued)
        val thread = harness.startWork()
        assertTrue(entered.await(5, TimeUnit.SECONDS))
        harness.events.runNext()
        harness.events.fireTimeout()
        assertTrue(first.outcomes.single().contains("operation_timeout"))
        assertTrue(queued.outcomes.single().contains("未执行"))

        release.countDown()
        thread.join(5_000)
        assertFalse(thread.isAlive)
        harness.events.runNext()
        assertEquals(1, writes.get())
        assertEquals(1, first.outcomes.size)
        assertTrue(harness.deliveryFailures.single() is AssertionError)
        harness.coordinator.submit(owner, false, { "must not run" }, later)
        assertTrue(later.outcomes.single().contains("operation_busy"))
        assertTrue(later.outcomes.single().contains("严重错误"))
        assertTrue(later.outcomes.single().contains("强行停止本应用不能保证取消"))
        assertTrue(later.outcomes.single().contains("在系统设置中强行停止本应用后重新打开"))
        assertTrue(harness.work.isEmpty())
    }

    @Test
    fun executorRejectionDoesNotLeaveTheCoordinatorPermanentlyBusy() {
        val events = FakeEvents()
        val work = ArrayDeque<Runnable>()
        var reject = true
        val coordinator = RequestCoordinator(Executor { action ->
            if (reject) throw RejectedExecutionException("executor unavailable")
            work.addLast(action)
        }, events, { throw AssertionError("Unexpected reply failure", it) }, { 0L })
        val first = Reply()
        coordinator.submit(Any(), false, { "never" }, first)
        assertEquals(listOf("error:operation_failed:executor unavailable"), first.outcomes)

        reject = false
        val second = Reply()
        coordinator.submit(Any(), false, { "recovered" }, second)
        work.removeFirst().run()
        events.runNext()
        events.runNext()
        assertEquals(listOf("success:recovered"), second.outcomes)
    }

    @Test
    fun boundedQueueRejectsExcessWithoutDisruptingEarlierRequests() {
        val harness = Harness(maxQueued = 1)
        val owner = Any()
        val first = Reply()
        val second = Reply()
        val excess = Reply()
        harness.coordinator.submit(owner, false, { "first" }, first)
        harness.coordinator.submit(owner, false, { "second" }, second)
        harness.coordinator.submit(owner, true, { error("excess should not run") }, excess)
        assertTrue(excess.outcomes.single().contains("未执行"))
        harness.runWork()
        harness.events.runNext()
        harness.events.runNext()
        harness.runWork()
        harness.events.runNext()
        harness.events.runNext()
        assertEquals(listOf("success:first"), first.outcomes)
        assertEquals(listOf("success:second"), second.outcomes)
    }

    private class Reply : RequestReply {
        val outcomes = mutableListOf<String>()
        override fun success(value: Any?) { outcomes += "success:$value" }
        override fun error(code: String, message: String) { outcomes += "error:$code:$message" }
    }

    private class FakeEvents : RequestEventLoop {
        private val ready = LinkedBlockingQueue<Runnable>()
        val delayed = mutableListOf<Pair<Runnable, Long>>()

        override fun post(action: Runnable) { ready.put(action) }
        override fun postDelayed(action: Runnable, delayMillis: Long) {
            delayed += action to delayMillis
        }
        override fun remove(action: Runnable) { delayed.removeAll { it.first === action } }

        fun runNext() {
            val action = ready.poll(5, TimeUnit.SECONDS)
            assertNotNull("Expected an event-loop callback", action)
            action!!.run()
        }

        fun fireTimeout() {
            val action = delayed.single().first
            delayed.clear()
            action.run()
        }
    }

    private class Harness(maxQueued: Int = 32) {
        @Volatile var nowMillis = 0L
        val events = FakeEvents()
        val work = ArrayDeque<Runnable>()
        val deliveryFailures = mutableListOf<Throwable>()
        val coordinator = RequestCoordinator(
            Executor { work.addLast(it) }, events, { deliveryFailures.add(it) }, { nowMillis },
            maxQueued = maxQueued,
        )

        fun runWork() { work.removeFirst().run() }
        fun startWork(): Thread = Thread(work.removeFirst()).also { it.start() }
    }
}
