package im.majo.tacchi.ipc

import android.content.Context
import android.os.Bundle
import android.os.RemoteException

class ProbeClient(private val context: Context) {
    fun loaded(): Boolean = request("loaded")?.getBoolean("ok") == true

    fun begin(): ProbeTicket? {
        val result = request("begin") ?: return null
        if (!result.getBoolean("ok")) return null
        val id = result.getString("id") ?: return null
        return ProbeTicket(id, result.getLong("revision"), result.getLong("deadline"))
    }

    fun valid(ticket: ProbeTicket): Boolean = request("check", fields(ticket))?.getBoolean("ok") == true

    fun finish(ticket: ProbeTicket, result: String): Boolean = request("finish", fields(ticket).apply {
        putString("result", result)
    })?.getBoolean("ok") == true

    private fun fields(ticket: ProbeTicket) = Bundle().apply {
        putString("id", ticket.id)
        putLong("revision", ticket.revision)
        putLong("deadline", ticket.deadline)
    }

    private fun request(operation: String, fields: Bundle? = null): Bundle? {
        return try {
            if (!PeerIdentity.managerAvailable(context)) return null
            context.contentResolver.acquireUnstableContentProviderClient(PeerIdentity.AUTHORITY)?.use {
                it.call(operation, null, fields)
            }
        } catch (_: RemoteException) { null }
        catch (_: RuntimeException) { null }
    }
}
