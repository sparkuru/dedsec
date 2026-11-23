package im.majo.thanox_bridge

import io.flutter.embedding.android.FlutterActivity
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel
import java.util.concurrent.Executors

class MainActivity : FlutterActivity() {
    private val worker = Executors.newSingleThreadExecutor()
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
        worker.execute {
            try {
                val value = when (call.method) {
                    "listApps" -> repository.listApps()
                    "findApp" -> repository.findApp(call.requiredString("packageName"))
                    "getAppDetails" -> repository.appDetails(
                        call.requiredString("packageName"),
                        call.requiredInt("userId"),
                        call.requiredInt("uid"),
                    )
                    "setOpMode" -> repository.setOpMode(
                        call.requiredString("packageName"),
                        call.requiredInt("uid"),
                        call.requiredInt("userId"),
                        call.requiredString("identityToken"),
                        call.requiredInt("code"),
                        call.requiredString("mode"),
                    )
                    else -> throw IllegalArgumentException("未知操作")
                }
                runOnUiThread { result.success(value) }
            } catch (error: Exception) {
                runOnUiThread {
                    result.error("operation_failed", error.message ?: "操作失败", null)
                }
            }
        }
    }

    override fun onDestroy() {
        if (::channel.isInitialized) channel.setMethodCallHandler(null)
        worker.shutdownNow()
        super.onDestroy()
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

    companion object {
        private const val CHANNEL = "im.majo.thanox_bridge/permissions"
    }
}
