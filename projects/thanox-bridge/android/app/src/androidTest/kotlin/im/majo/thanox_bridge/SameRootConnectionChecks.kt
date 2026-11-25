package im.majo.thanox_bridge

import android.os.Binder
import android.os.Parcel

/** Exercises wire routing and failure sequencing using only in-process fake binders. */
internal object SameRootConnectionChecks {
    private const val OPS = "github.tornaco.android.thanos.core.ops.IOps"
    private const val PKG = "github.tornaco.android.thanos.core.pm.IPkgManager"
    private const val TARGET = "test.fixture"
    private const val UID = 10101

    fun run() {
        val root = FakeRoot()
        val ops = root.connect()
        check(ops.opName(26) == "CAMERA")
        check(ops.exactUid(TARGET, 0) == UID)
        check(root.events == listOf("root.ops", "ops.name", "root.uid", "uid.query")) {
            "Operations and UID query did not use the expected root"
        }

        val missingOps = FakeRoot(missingOps = true)
        check(runCatching { missingOps.connect() }.isFailure)
        val wrongOps = FakeRoot(opsDescriptor = "unexpected.ops")
        check(runCatching { wrongOps.connect() }.isFailure)
        for (fake in listOf(FakeRoot(missingUid = true), FakeRoot(uidDescriptor = "unexpected.uid"))) {
            val session = fake.connect()
            check(runCatching { session.exactUid(TARGET, 0) }.isFailure)
            check("ops.write" !in fake.events)
        }

        for (failedQuery in 1..3) {
            val failingRoot = FakeRoot(failUidQuery = failedQuery)
            val failingOps = failingRoot.connect()
            val outcome = runCatching {
                PermissionPolicy.guardedWrite(
                    verify = { PermissionPolicy.validateThanoxUid(0, UID, failingOps.exactUid(TARGET, 0)) },
                    write = { failingOps.setMode(26, TARGET, 0, "ALLOW_ALWAYS") },
                    read = { failingOps.opName(26) },
                )
            }.exceptionOrNull() ?: error("Failed UID query was reported as success")
            check(failingRoot.events.count { it == "root.uid" } == failedQuery)
            check(("ops.write" in failingRoot.events) == (failedQuery > 1))
            check(("ops.name" in failingRoot.events) == (failedQuery == 3))
            if (failedQuery > 1) {
                check(outcome is IllegalStateException && outcome.message?.contains("无法确认") == true) {
                    "Post-write UID failure was not uncertain"
                }
            } else {
                check(outcome.message?.contains("无法确认") != true) {
                    "Pre-write UID failure was misreported as an attempted write"
                }
            }
        }
    }

    private class FakeRoot(
        private val missingOps: Boolean = false,
        private val missingUid: Boolean = false,
        opsDescriptor: String = OPS,
        uidDescriptor: String = PKG,
        private val failUidQuery: Int = 0,
    ) : Binder() {
        val events = mutableListOf<String>()
        private var uidQueries = 0

        private val ops = object : Binder() {
            init { attachInterface(null, opsDescriptor) }

            override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
                if (code != 1 && code != 3) return super.onTransact(code, data, reply, flags)
                data.enforceInterface(OPS)
                check(data.readInt() == 26)
                if (code == 1) {
                    check(data.readInt() == 1 && data.readString() == TARGET && data.readInt() == 0)
                    check(data.readString() == "ALLOW_ALWAYS")
                    events += "ops.write"
                } else {
                    events += "ops.name"
                }
                val result = checkNotNull(reply)
                result.writeNoException()
                if (code == 3) result.writeString("CAMERA")
                return true
            }
        }

        private val uid = object : Binder() {
            init { attachInterface(null, uidDescriptor) }

            override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
                if (code != 2) return super.onTransact(code, data, reply, flags)
                data.enforceInterface(PKG)
                check(data.readInt() == 1 && data.readString() == TARGET && data.readInt() == 0)
                events += "uid.query"
                uidQueries++
                check(uidQueries != failUidQuery) { "Simulated UID query failure" }
                val result = checkNotNull(reply)
                result.writeNoException()
                result.writeInt(UID)
                return true
            }
        }

        init { attachInterface(null, ThanoxService.ROOT) }

        override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
            if (code != 4 && code != 31) return super.onTransact(code, data, reply, flags)
            data.enforceInterface(ThanoxService.ROOT)
            val result = checkNotNull(reply)
            result.writeNoException()
            if (code == 31) {
                events += "root.ops"
                result.writeStrongBinder(if (missingOps) null else ops)
            } else {
                events += "root.uid"
                result.writeStrongBinder(if (missingUid) null else uid)
            }
            return true
        }

        fun connect(): ThanoxOps = ThanoxOps.connect(
            ThanoxService.Connection(this, ThanoxProfile.BOARD_ANDROID_11_THANOX_9_6),
        )
    }
}
