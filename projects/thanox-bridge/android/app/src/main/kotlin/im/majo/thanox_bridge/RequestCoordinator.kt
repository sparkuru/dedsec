package im.majo.thanox_bridge

import java.util.ArrayDeque
import java.util.concurrent.Executor

internal interface RequestEventLoop {
    fun post(action: Runnable)
    fun postDelayed(action: Runnable, delayMillis: Long)
    fun remove(action: Runnable)
}

internal interface RequestReply {
    fun success(value: Any?)
    fun error(code: String, message: String)
}

/** All coordinator state and replies are confined to the event loop. */
internal class RequestCoordinator(
    private val worker: Executor,
    private val events: RequestEventLoop,
    private val reportFailure: (Throwable) -> Unit,
    private val uptimeMillis: () -> Long,
    private val timeoutMillis: Long = 60_000,
    private val maxQueued: Int = 32,
) {
    private class Request(
        val owner: Any,
        val isWrite: Boolean,
        val action: () -> Any?,
        var reply: RequestReply?,
    ) {
        val startLock = Any()
        var mayStart = true
        var hasStarted = false
        var timedOut = false
        var timeout: Runnable? = null
    }

    private sealed class Completion {
        class Success(val value: Any?) : Completion()
        class Failure(val error: Exception) : Completion()
        class Fatal(val error: Error) : Completion()
        object Skipped : Completion()
    }

    private val queue = ArrayDeque<Request>()
    private var active: Request? = null
    private var haltMessage: String? = null

    fun submit(owner: Any, isWrite: Boolean, action: () -> Any?, reply: RequestReply) {
        val blockedMessage = haltMessage
        if (blockedMessage != null) {
            deliver { reply.error(BUSY, blockedMessage) }
            return
        }
        if (active?.timedOut == true) {
            deliver { reply.error(BUSY, BLOCKED_MESSAGE) }
            return
        }
        if (active != null && queue.size >= maxQueued) {
            deliver { reply.error(BUSY, "请求过多，该请求未执行，请稍后重试。") }
            return
        }
        queue.addLast(Request(owner, isWrite, action, reply))
        startNext()
    }

    fun cancelOwner(owner: Any) {
        val canceled = mutableListOf<Request>()
        val iterator = queue.iterator()
        while (iterator.hasNext()) {
            val request = iterator.next()
            if (request.owner === owner) {
                iterator.remove()
                canceled += request
            }
        }
        val running = active?.takeIf { it.owner === owner }
        val wasStarted = if (running != null) synchronized(running.startLock) {
            running.mayStart = false
            running.hasStarted
        } else {
            false
        }
        canceled.forEach { fail(it, DETACHED, "页面已关闭，该请求未执行。") }
        if (running != null) {
            val message = if (!wasStarted) {
                "页面已关闭，该请求未执行。"
            } else if (running.isWrite) {
                "页面已关闭，操作结果不确定，可能仍会在后台执行；请重新检查当前状态。"
            } else {
                "页面已关闭，正在进行的读取可能仍会继续。"
            }
            fail(running, DETACHED, message)
        }
    }

    private fun startNext() {
        if (active != null || queue.isEmpty()) return
        val request = queue.removeFirst()
        active = request
        try {
            worker.execute {
                val startedAt = synchronized(request.startLock) {
                    if (request.mayStart) {
                        request.hasStarted = true
                        uptimeMillis()
                    } else {
                        null
                    }
                }
                val completion = if (startedAt == null) {
                    Completion.Skipped
                } else {
                    events.post(Runnable { started(request, startedAt) })
                    try {
                        Completion.Success(request.action())
                    } catch (error: Exception) {
                        Completion.Failure(error)
                    } catch (error: Error) {
                        Completion.Fatal(error)
                    }
                }
                events.post(Runnable { finish(request, completion) })
            }
        } catch (error: Exception) {
            finish(request, Completion.Failure(error))
        } catch (error: Error) {
            finish(request, Completion.Fatal(error))
        }
    }

    private fun started(request: Request, startedAt: Long) {
        if (active !== request || request.timedOut) return
        val elapsed = (uptimeMillis() - startedAt).coerceAtLeast(0)
        if (elapsed >= timeoutMillis) {
            timedOut(request)
            return
        }
        val timeout = Runnable { timedOut(request) }
        request.timeout = timeout
        events.postDelayed(timeout, timeoutMillis - elapsed)
    }

    private fun timedOut(request: Request) {
        if (active !== request || request.timedOut) return
        request.timedOut = true
        synchronized(request.startLock) { request.mayStart = false }
        val rejected = mutableListOf<Request>()
        while (queue.isNotEmpty()) rejected += queue.removeFirst()
        val message = if (request.isWrite) {
            "操作超时，结果不确定，可能仍会在后台执行；重启不能保证取消已提交的操作。请稍后刷新检查当前状态。"
        } else {
            "读取超时，上一次操作尚未结束；请稍后重试。"
        }
        try {
            fail(request, TIMEOUT, message)
        } finally {
            rejected.forEach { fail(it, BUSY, BLOCKED_MESSAGE) }
        }
    }

    private fun finish(request: Request, completion: Completion) {
        if (active !== request) return
        request.timeout?.let(events::remove)
        request.timeout = null
        active = null
        if (completion is Completion.Fatal) {
            val blockedMessage = if (request.isWrite) HALTED_WRITE_MESSAGE else HALTED_READ_MESSAGE
            haltMessage = blockedMessage
            val rejected = mutableListOf<Request>()
            while (queue.isNotEmpty()) rejected += queue.removeFirst()
            report(completion.error)
            val message = if (request.isWrite) {
                "操作发生严重错误，结果不确定，可能仍会在后台执行。强行停止本应用不能保证取消已提交的操作。请在系统设置中强行停止本应用后重新打开，再核对当前状态。"
            } else {
                "读取过程中发生严重错误，请在系统设置中强行停止本应用后重新打开。"
            }
            fail(request, FATAL, message)
            rejected.forEach { fail(it, BUSY, blockedMessage) }
            return
        }
        try {
            if (!request.timedOut) {
                when (completion) {
                    is Completion.Success -> succeed(request, completion.value)
                    is Completion.Failure -> fail(
                        request, FAILED, completion.error.message ?: "操作失败",
                    )
                    is Completion.Fatal -> error("Fatal completion was not handled")
                    Completion.Skipped -> fail(request, DETACHED, "页面已关闭，该请求未执行。")
                }
            }
        } finally {
            startNext()
        }
    }

    private fun succeed(request: Request, value: Any?) {
        val reply = request.reply
        request.reply = null
        if (reply != null) deliver { reply.success(value) }
    }

    private fun fail(request: Request, code: String, message: String) {
        val reply = request.reply
        request.reply = null
        if (reply != null) deliver { reply.error(code, message) }
    }

    private fun deliver(action: () -> Unit) {
        try {
            action()
        } catch (error: Exception) {
            report(error)
        }
    }

    private fun report(error: Throwable) {
        try {
            reportFailure(error)
        } catch (_: Exception) {
            System.err.println("Could not report request coordination failure")
        }
    }

    private companion object {
        const val FAILED = "operation_failed"
        const val TIMEOUT = "operation_timeout"
        const val FATAL = "operation_fatal"
        const val BUSY = "operation_busy"
        const val DETACHED = "operation_detached"
        const val BLOCKED_MESSAGE = "上一次操作尚未结束，该请求未执行；请稍后重试。"
        const val HALTED_READ_MESSAGE = "此前请求发生严重错误，该请求未执行。请在系统设置中强行停止本应用后重新打开。"
        const val HALTED_WRITE_MESSAGE = "此前写入发生严重错误且可能仍会继续。强行停止本应用不能保证取消已提交的操作。该请求未执行。请在系统设置中强行停止本应用后重新打开，再核对当前状态。"
    }
}
