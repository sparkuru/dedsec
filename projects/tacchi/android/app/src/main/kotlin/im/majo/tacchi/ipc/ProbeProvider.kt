package im.majo.tacchi.ipc

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.os.Binder
import android.os.Bundle
import android.os.SystemClock

object NativeDiagnostics {
    internal val ledger = ProbeLedger(SystemClock::elapsedRealtime)

    fun snapshot(@Suppress("UNUSED_PARAMETER") context: Context): Map<String, Any?> = ledger.snapshot()
    fun cancel(@Suppress("UNUSED_PARAMETER") context: Context) = ledger.cancel()
}

class ProbeProvider : ContentProvider() {
    override fun onCreate() = true

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle {
        val app = context ?: return reply(false)
        return try {
            if (!PeerIdentity.accepts(app, Binder.getCallingUid(), PeerIdentity.HOST)) return reply(false)
            if (!NativeDiagnostics.ledger.allowCall()) return reply(false)
            if (arg != null) return reply(false)
            when (method) {
                "loaded" -> if (extras == null || extras.isEmpty) {
                    NativeDiagnostics.ledger.loaded()
                    reply(true)
                } else reply(false)
                "begin" -> if (extras == null || extras.isEmpty) {
                    val ticket = NativeDiagnostics.ledger.begin()
                    reply(ticket != null).apply {
                        if (ticket != null) {
                            putString("id", ticket.id)
                            putLong("revision", ticket.revision)
                            putLong("deadline", ticket.deadline)
                        }
                    }
                } else reply(false)
                "check", "finish" -> {
                    val fields = if (method == "check") setOf("id", "revision", "deadline")
                    else setOf("id", "revision", "deadline", "result")
                    if (extras == null || extras.keySet() != fields) return reply(false)
                    val id = extras.getString("id") ?: return reply(false)
                    if (id.length != 36) return reply(false)
                    val ticket = ProbeTicket(id, extras.getLong("revision", -1L), extras.getLong("deadline", -1L))
                    if (method == "check") reply(NativeDiagnostics.ledger.valid(ticket))
                    else {
                        val result = extras.getString("result") ?: return reply(false)
                        if (result.length > 32) return reply(false)
                        reply(NativeDiagnostics.ledger.finish(ticket, result))
                    }
                }
                else -> reply(false)
            }
        } catch (_: RuntimeException) {
            // IPC rejects malformed bundles without exposing caller data or exception messages.
            reply(false)
        }
    }

    private fun reply(ok: Boolean) = Bundle().apply { putBoolean("ok", ok) }
    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = null
    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0
}
