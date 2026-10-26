package im.majo.tacchi.module

import android.app.Activity
import im.majo.tacchi.ipc.PeerIdentity
import im.majo.tacchi.probe.HardwareProbe
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface

class TacchiModule : XposedModule() {
    private var allowedProcess = false
    private var owner: Activity? = null
    private var probe: HardwareProbe? = null

    override fun onModuleLoaded(param: XposedModuleInterface.ModuleLoadedParam) {
        allowedProcess = !param.isSystemServer && param.processName == PeerIdentity.HOST && apiVersion >= 101
    }

    override fun onPackageReady(param: XposedModuleInterface.PackageReadyParam) {
        if (!allowedProcess || param.packageName != PeerIdentity.HOST || !param.isFirstPackage) return
        val fixture = param.classLoader.loadClass("dev.tacchi.probe.ProbeActivity")
        val display = fixture.getMethod("showModuleStatus", String::class.java)
        for (name in listOf("onHostReady", "onProbeRequested", "onProbeCanceled", "onHostPaused")) {
            hook(fixture.getDeclaredMethod(name)).intercept { chain ->
                val result = chain.proceed()
                val activity = chain.thisObject as? Activity
                if (activity != null) {
                    try {
                        when (name) {
                            "onHostReady" -> {
                                probe?.cancel()
                                owner = activity
                                probe = HardwareProbe(activity) { message -> display.invoke(activity, message) }
                                probe?.loaded()
                            }
                            "onProbeRequested" -> if (owner === activity) probe?.start()
                            "onProbeCanceled" -> if (owner === activity) probe?.cancel()
                            "onHostPaused" -> if (owner === activity) {
                                probe?.cancel()
                                probe = null
                                owner = null
                            }
                        }
                    } catch (_: Exception) {
                        runCatching { probe?.cancel() }
                        probe = null
                        owner = null
                    }
                }
                result
            }
        }
    }
}
