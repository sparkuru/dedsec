package im.majo.tacchi

import android.app.Application
import io.github.libxposed.service.XposedService
import io.github.libxposed.service.XposedServiceHelper

class TacchiApplication : Application() {
    internal val frameworkStatus = FrameworkStatus()
    private val services = mutableMapOf<XposedService, FrameworkConnection>()

    override fun onCreate() {
        super.onCreate()
        XposedServiceHelper.registerListener(object : XposedServiceHelper.OnServiceListener {
            override fun onServiceBind(service: XposedService) {
                synchronized(services) {
                    val connection = services.getOrPut(service) { ServiceConnection(service) }
                    frameworkStatus.bind(connection)
                }
            }

            override fun onServiceDied(service: XposedService) {
                synchronized(services) {
                    services.remove(service)?.let(frameworkStatus::died)
                }
            }
        })
    }

    private class ServiceConnection(private val service: XposedService) : FrameworkConnection {
        override fun read(): FrameworkDetails = try {
            FrameworkDetails(
                api = service.apiVersion,
                name = service.frameworkName,
                version = service.frameworkVersion,
                remoteStorage = service.frameworkProperties and XposedService.PROP_CAP_REMOTE != 0L,
            )
        } catch (_: XposedService.ServiceException) {
            throw FrameworkUnavailableException()
        }
    }
}
