package im.majo.thanox_bridge

import android.app.Activity
import android.app.Instrumentation
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

class RequestCoordinatorInstrumentation : Instrumentation() {
    override fun onCreate(arguments: Bundle) {
        super.onCreate(arguments)
        start()
    }

    override fun onStart() {
        val result = Bundle()
        val code = try {
            check(Looper.myLooper() != Looper.getMainLooper()) { "Instrumentation is on the main thread" }
            check(targetContext.packageName == "im.majo.thanox_bridge") { "Unexpected target application" }
            checkTimeoutAndRecovery()
            checkOwnerCancellation()
            result.putString("stream", "\nCoordinator timeout: PASS\nOwner cancellation: PASS\n")
            Activity.RESULT_OK
        } catch (error: Throwable) {
            result.putString(
                "stream",
                "\nCoordinator instrumentation: FAIL ${error.javaClass.simpleName}: ${error.message}\n",
            )
            Activity.RESULT_CANCELED
        }
        finish(code, result)
    }

    private fun checkTimeoutAndRecovery() {
        val harness = Harness(TEST_TIMEOUT_MILLIS)
        val release = CountDownLatch(1)
        try {
            val owner = Any()
            val started = CountDownLatch(1)
            val actionReturned = CountDownLatch(1)
            val actions = AtomicInteger()
            val active = RecordingReply()
            val queued = RecordingReply()
            val rejected = RecordingReply()

            harness.onMain {
                harness.coordinator.submit(owner, true, {
                    started.countDown()
                    try {
                        await(release, "release timed-out action", BLOCK_SECONDS)
                        actions.incrementAndGet()
                        "late result"
                    } finally {
                        actionReturned.countDown()
                    }
                }, active)
                harness.coordinator.submit(owner, true, { actions.addAndGet(100) }, queued)
            }
            await(started, "start timed-out action")
            val timeout = active.await("timeout reply")
            timeout.checkError("operation_timeout", "结果不确定")
            check(timeout.message?.contains("可能仍会") == true) { "Timeout did not describe the delayed write" }
            val startedAt = harness.firstWorkerStartMillis.get()
            check(startedAt >= 0 && timeout.atMillis - startedAt >= TEST_TIMEOUT_MILLIS) {
                "Timeout arrived before the test deadline"
            }
            queued.await("queued rejection").checkError("operation_busy", "未执行")

            harness.onMain {
                harness.coordinator.submit(Any(), true, { actions.addAndGet(1000) }, rejected)
            }
            rejected.await("new request rejection").checkError("operation_busy", "未执行")
            check(actions.get() == 0) { "An action ran before release" }

            release.countDown()
            await(actionReturned, "return timed-out action")
            harness.awaitPostedCompletion()
            val fresh = RecordingReply()
            harness.onMain { harness.coordinator.submit(Any(), false, { "recovered" }, fresh) }
            fresh.await("recovered request").checkSuccess("recovered")
            check(actions.get() == 1) { "Rejected work ran or the original action did not finish" }
            check(active.count.get() == 1 && queued.count.get() == 1 && rejected.count.get() == 1) {
                "A request received more than one reply"
            }
            harness.checkNoFailures()
        } finally {
            release.countDown()
            harness.close()
        }
    }

    private fun checkOwnerCancellation() {
        val harness = Harness(OWNER_TIMEOUT_MILLIS)
        val release = CountDownLatch(1)
        try {
            val oldOwner = Any()
            val started = CountDownLatch(1)
            val nextStarted = CountDownLatch(1)
            val actions = AtomicInteger()
            val active = RecordingReply()
            val queued = RecordingReply()
            val next = RecordingReply()

            harness.onMain {
                harness.coordinator.submit(oldOwner, true, {
                    started.countDown()
                    await(release, "release detached action", BLOCK_SECONDS)
                    actions.incrementAndGet()
                    "late detached result"
                }, active)
                harness.coordinator.submit(oldOwner, true, { actions.addAndGet(100) }, queued)
            }
            await(started, "start detached action")
            harness.onMain {
                harness.coordinator.cancelOwner(oldOwner)
                harness.coordinator.submit(Any(), false, {
                    nextStarted.countDown()
                    "new owner"
                }, next)
            }
            active.await("detached running reply").checkError("operation_detached", "结果不确定")
            queued.await("detached queued reply").checkError("operation_detached", "未执行")
            check(nextStarted.count == 1L && next.count.get() == 0) {
                "New owner overtook the running action"
            }
            check(actions.get() == 0) { "An action ran before release" }

            release.countDown()
            next.await("new owner recovery").checkSuccess("new owner")
            harness.awaitPostedCompletion()
            check(nextStarted.count == 0L && actions.get() == 1) { "Detached queued work ran" }
            check(active.count.get() == 1 && queued.count.get() == 1 && next.count.get() == 1) {
                "A request received more than one reply"
            }
            harness.checkNoFailures()
        } finally {
            release.countDown()
            harness.close()
        }
    }

    private data class Outcome(
        val code: String?,
        val message: String?,
        val value: Any?,
        val onMain: Boolean,
        val atMillis: Long,
    ) {
        fun checkError(expectedCode: String, expectedMessage: String) {
            check(onMain) { "Error reply was not delivered on the main looper" }
            check(code == expectedCode && message?.contains(expectedMessage) == true) {
                "Expected $expectedCode containing $expectedMessage; got $code: $message"
            }
        }

        fun checkSuccess(expectedValue: Any?) {
            check(onMain) { "Success reply was not delivered on the main looper" }
            check(code == null && value == expectedValue) { "Unexpected success reply: $code / $value" }
        }
    }

    private class RecordingReply : RequestReply {
        val count = AtomicInteger()
        private val outcomes = LinkedBlockingQueue<Outcome>()

        override fun success(value: Any?) = record(null, null, value)
        override fun error(code: String, message: String) = record(code, message, null)

        private fun record(code: String?, message: String?, value: Any?) {
            count.incrementAndGet()
            outcomes.add(Outcome(code, message, value, Looper.myLooper() == Looper.getMainLooper(), SystemClock.uptimeMillis()))
        }

        fun await(label: String): Outcome = outcomes.poll(WAIT_SECONDS, TimeUnit.SECONDS)
            ?: error("Timed out waiting for $label")
    }

    private class Harness(timeoutMillis: Long) {
        private val handler = Handler(Looper.getMainLooper())
        private val worker = Executors.newSingleThreadExecutor()
        private val failures = ConcurrentLinkedQueue<Throwable>()
        val firstWorkerStartMillis = AtomicLong(-1)
        val coordinator = RequestCoordinator(
            worker,
            object : RequestEventLoop {
                override fun post(action: Runnable) { check(handler.post(action)) { "Main looper rejected callback" } }
                override fun postDelayed(action: Runnable, delayMillis: Long) {
                    check(handler.postDelayed(action, delayMillis)) { "Main looper rejected timer" }
                }
                override fun remove(action: Runnable) { handler.removeCallbacks(action) }
            },
            { failures.add(it) },
            {
                val now = SystemClock.uptimeMillis()
                if (Looper.myLooper() != Looper.getMainLooper()) {
                    firstWorkerStartMillis.compareAndSet(-1, now)
                }
                now
            },
            timeoutMillis = timeoutMillis,
        )

        fun onMain(action: () -> Unit) {
            val finished = CountDownLatch(1)
            val failure = AtomicReference<Throwable?>()
            check(handler.post {
                try {
                    action()
                } catch (error: Throwable) {
                    failure.set(error)
                } finally {
                    finished.countDown()
                }
            }) { "Main looper rejected test action" }
            await(finished, "main-looper test action")
            failure.get()?.let { throw IllegalStateException("Main-looper test action failed", it) }
        }

        fun awaitPostedCompletion() {
            val finished = CountDownLatch(1)
            worker.execute {
                if (!handler.post { finished.countDown() }) {
                    failures.add(IllegalStateException("Main looper rejected completion marker"))
                    finished.countDown()
                }
            }
            await(finished, "coordinator completion marker")
        }

        fun checkNoFailures() {
            check(failures.isEmpty()) { "Coordinator reported a failure: ${failures.peek()}" }
        }

        fun close() {
            worker.shutdownNow()
            check(worker.awaitTermination(WAIT_SECONDS, TimeUnit.SECONDS)) { "Test worker did not terminate" }
        }
    }

    companion object {
        private const val TEST_TIMEOUT_MILLIS = 1_000L
        private const val OWNER_TIMEOUT_MILLIS = 15_000L
        private const val WAIT_SECONDS = 10L
        private const val BLOCK_SECONDS = 30L

        private fun await(latch: CountDownLatch, label: String, seconds: Long = WAIT_SECONDS) {
            check(latch.await(seconds, TimeUnit.SECONDS)) { "Timed out waiting to $label" }
        }
    }
}
