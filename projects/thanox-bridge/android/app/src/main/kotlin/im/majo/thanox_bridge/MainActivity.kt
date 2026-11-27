package im.majo.thanox_bridge

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import io.flutter.embedding.android.FlutterActivity
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel
import java.util.concurrent.Executors

class MainActivity : FlutterActivity() {
    private val requestOwner = Any()
    private lateinit var channel: MethodChannel
    private lateinit var repository: PermissionRepository

    override fun configureFlutterEngine(flutterEngine: FlutterEngine) {
        super.configureFlutterEngine(flutterEngine)
        repository = PermissionRepository(applicationContext)
        channel = MethodChannel(flutterEngine.dartExecutor.binaryMessenger, CHANNEL)
        channel.setMethodCallHandler(::handleCall)
    }

    private fun handleCall(call: MethodCall, result: MethodChannel.Result) {
        if (call.method !in setOf("listApps", "findApp", "getAppDetails", "setOpMode")) {
            result.notImplemented()
            return
        }
        val action = try {
            requestAction(repository, call)
        } catch (error: Exception) {
            result.error("operation_failed", error.message ?: "操作失败", null)
            return
        }
        PermissionRequests.coordinator.submit(
            requestOwner, call.method == "setOpMode", action, ChannelReply(result),
        )
    }

    override fun onDestroy() {
        if (::channel.isInitialized) channel.setMethodCallHandler(null)
        PermissionRequests.coordinator.cancelOwner(requestOwner)
        super.onDestroy()
    }

    private companion object {
        const val CHANNEL = "im.majo.thanox_bridge/permissions"
    }
}

private object PermissionRequests {
    private val handler = Handler(Looper.getMainLooper())
    val coordinator = RequestCoordinator(
        Executors.newSingleThreadExecutor(),
        object : RequestEventLoop {
            override fun post(action: Runnable) { handler.post(action) }
            override fun postDelayed(action: Runnable, delayMillis: Long) {
                handler.postDelayed(action, delayMillis)
            }
            override fun remove(action: Runnable) { handler.removeCallbacks(action) }
        },
        { error -> Log.w("ThanoxBridge", "Request coordination failed: ${error.javaClass.simpleName}") },
        SystemClock::uptimeMillis,
    )
}

private class ChannelReply(private val result: MethodChannel.Result) : RequestReply {
    override fun success(value: Any?) { result.success(value) }
    override fun error(code: String, message: String) { result.error(code, message, null) }
}

private fun requestAction(repository: PermissionRepository, call: MethodCall): () -> Any? =
    when (call.method) {
        "listApps" -> ({ repository.listApps() })
        "findApp" -> {
            val packageName = call.requiredString("packageName")
            val action: () -> Any? = { repository.findApp(packageName) }
            action
        }
        "getAppDetails" -> {
            val packageName = call.requiredString("packageName")
            val userId = call.requiredInt("userId")
            val uid = call.requiredInt("uid")
            val action: () -> Any? = { repository.appDetails(packageName, userId, uid) }
            action
        }
        "setOpMode" -> {
            val packageName = call.requiredString("packageName")
            val uid = call.requiredInt("uid")
            val userId = call.requiredInt("userId")
            val identityToken = call.requiredString("identityToken")
            val code = call.requiredInt("code")
            val mode = call.requiredString("mode")
            val action: () -> Any? = {
                repository.setOpMode(packageName, uid, userId, identityToken, code, mode)
            }
            action
        }
        else -> throw IllegalArgumentException("未知操作")
    }

private fun MethodCall.requiredString(name: String): String =
    argument<String>(name)?.takeIf { it.isNotBlank() }
        ?: throw IllegalArgumentException("缺少参数：$name")

private fun MethodCall.requiredInt(name: String): Int = when (val value = argument<Any>(name)) {
    is Int -> value
    is Long -> {
        require(value in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong()) { "参数超出范围：$name" }
        value.toInt()
    }
    else -> throw IllegalArgumentException("缺少或无效参数：$name")
}
