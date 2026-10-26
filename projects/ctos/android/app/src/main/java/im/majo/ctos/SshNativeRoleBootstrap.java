package im.majo.ctos;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.BufferUnderflowException;
import java.nio.charset.StandardCharsets;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;

/**
 * Decoder for the native role-bootstrap record.  The record contains only
 * bounded identity and liveness metadata; it is not an argv, shell, file,
 * descriptor, or secret transport. Native code must validate ownership before
 * producing it, while this class validates the wire again before issuing the
 * existing package-private Java role.
 */
final class SshNativeRoleBootstrap {
    private static final int MAGIC = 0x43535242; // CSRB
    private static final int VERSION = 1;
    private static final int MAX_WIRE = 2048;
    private static final int ROLE_NONCE_BYTES = 32;
    private static final int CONNECTION_BINDING_BYTES = 32;

    private static final class Native {
        static { System.loadLibrary("ctos_ssh_files"); }

        static native byte[] open(int program, int hop, int port, long deadline,
                String connectionId, String targetIdentity, String host, String publicViewId,
                byte[] roleNonce, byte[] connectionBinding) throws IOException;

        static native boolean owns(long token, int ownerUid, byte[] wire) throws IOException;

        static native void release(long token, int ownerUid) throws IOException;

        static native byte[] systemIdentity() throws IOException;
    }

    static final class Decoded {
        final long token;
        final int ownerUid;
        final SshRoleContext.Bootstrap bootstrap;
        private final byte[] canonicalWire;

        private Decoded(long token, int ownerUid, SshRoleContext.Bootstrap bootstrap,
                byte[] wire) {
            this.token = token;
            this.ownerUid = ownerUid;
            this.bootstrap = bootstrap;
            this.canonicalWire = wire.clone();
        }

        /** Returns a copy so the native ownership proof always sees this record unchanged. */
        byte[] canonicalWire() { return canonicalWire.clone(); }
    }

    /**
     * Native/JNI must prove that this exact token and UID own the supplied
     * metadata before a Java role is issued. There is intentionally no
     * permissive default implementation: callers must select the production
     * JNI proof or supply an explicit test-owned callback.
     */
    interface NativeOwnership {
        boolean owns(Decoded record);
    }

    /**
     * Native role cleanup accepts only the issued token and its bound UID.
     * Production code uses {@link #productionRelease()}; tests inject a
     * callback so host JVM tests never need to load JNI.
     */
    interface NativeRelease {
        void release(long token, int ownerUid) throws IOException;
    }

    interface NativeOpen {
        byte[] open(SshRoleContext.Bootstrap bootstrap) throws IOException;
    }

    /** Retained before native creation; transfers ownership only after successful Java issue. */
    static final class Unissued {
        private final Thread owner = Thread.currentThread();
        private final SshRoleContext.Bootstrap bootstrap;
        private final NativeOpen nativeOpen;
        private final NativeOwnership ownership;
        private final NativeRelease release;
        private byte[] wire;
        private boolean attempted;
        private boolean issued;
        private boolean closed;

        private Unissued(SshRoleContext.Bootstrap bootstrap, NativeOpen nativeOpen,
                NativeOwnership ownership, NativeRelease release) {
            this.bootstrap = bootstrap;
            this.nativeOpen = nativeOpen;
            this.ownership = ownership;
            this.release = release;
        }

        void open() throws VaultException {
            requireOwner();
            if (attempted || issued || closed) throw invalid();
            attempted = true;
            try {
                // Capture the exact returned owner record before any decoding or publication.
                wire = nativeOpen.open(bootstrap);
                cleanupToken();
            } catch (IOException | LinkageError | RuntimeException failure) {
                throw unavailable();
            }
        }

        Issued issue(SshRoleRegistry registry, SshRoleContext.PublicView publicView,
                SshRoleContext.Clock clock) throws VaultException {
            requireOwner();
            if (!attempted || issued || closed || wire == null) throw invalid();
            Issued result = SshNativeRoleBootstrap.issue(wire, registry, publicView, clock, ownership);
            issued = true;
            VaultCodec.wipe(wire);
            wire = null;
            return result;
        }

        /** Expiry/revocation cannot discard cleanup ownership. A failed release remains retryable. */
        void closeUnissued() throws VaultException {
            requireOwner();
            if (issued) throw invalid();
            if (closed) return;
            if (wire != null) {
                long token = cleanupToken();
                int uid = wireInt(20);
                try { release.release(token, uid); }
                catch (IOException | LinkageError | RuntimeException failure) { throw unavailable(); }
            }
            closed = true;
            VaultCodec.wipe(wire);
            wire = null;
        }

        private long cleanupToken() throws VaultException {
            // Only Native.open can populate production wire. This bounded header read grants
            // cleanup alone and deliberately does not require a fresh clock or live file view.
            if (wire == null || wire.length < 24 || wire.length > MAX_WIRE
                    || wireInt(0) != MAGIC || wire[4] != VERSION
                    || (wire[5] != 1 && wire[5] != 2) || wire[6] != 0 || wire[7] != 0
                    || wireInt(16) <= 0 || wireInt(20) < 0) throw invalid();
            long token = 0;
            for (int index = 8; index < 16; index++) token = (token << 8) | (wire[index] & 0xffL);
            if (token <= 0) throw invalid();
            return token;
        }

        private int wireInt(int offset) {
            return ((wire[offset] & 0xff) << 24) | ((wire[offset + 1] & 0xff) << 16)
                    | ((wire[offset + 2] & 0xff) << 8) | (wire[offset + 3] & 0xff);
        }

        private void requireOwner() throws VaultException {
            if (Thread.currentThread() != owner) throw invalid();
        }
    }

    static Unissued prepare(SshRoleContext.Bootstrap bootstrap) throws VaultException {
        return prepare(bootstrap, SshNativeRoleBootstrap::openNativeWire,
                productionOwnership(), productionRelease());
    }

    /** Explicit native-provider seam for host JVM lifecycle tests. */
    static Unissued prepare(SshRoleContext.Bootstrap bootstrap, NativeOpen nativeOpen,
            NativeOwnership ownership, NativeRelease release) throws VaultException {
        if (!validBootstrap(bootstrap) || nativeOpen == null || ownership == null || release == null)
            throw invalid();
        return new Unissued(bootstrap, nativeOpen, ownership, release);
    }

    /** Keeps the native identity attached to the Java role for its lifetime. */
    static final class Issued {
        final long token;
        final int ownerUid;
        final SshRoleRegistry registry;
        final SshRoleContext role;

        private Issued(long token, int ownerUid, SshRoleRegistry registry, SshRoleContext role) {
            this.token = token;
            this.ownerUid = ownerUid;
            this.registry = registry;
            this.role = role;
        }
    }

    private SshNativeRoleBootstrap() { }

    /** Actual OS identity used only to validate the pinned config evaluator tuple. */
    static String[] systemIdentity() throws VaultException {
        byte[] wire = null;
        try {
            wire = Native.systemIdentity();
            return decodeSystemIdentity(wire);
        } catch (IOException | LinkageError | RuntimeException failure) {
            throw unavailable();
        } finally { VaultCodec.wipe(wire); }
    }

    static String[] decodeSystemIdentity(byte[] wire) throws VaultException {
        if (wire == null || wire.length < 6 || wire.length > 514) throw invalid();
        ByteBuffer input = ByteBuffer.wrap(wire).order(ByteOrder.BIG_ENDIAN);
        String[] result = new String[2];
        try {
            for (int index = 0; index < result.length; index++) {
                int length = Short.toUnsignedInt(input.getShort());
                if (length == 0 || length > 255 || length > input.remaining()) throw invalid();
                ByteBuffer text = input.slice();
                text.limit(length);
                result[index] = StandardCharsets.UTF_8.newDecoder()
                        .onMalformedInput(CodingErrorAction.REPORT)
                        .onUnmappableCharacter(CodingErrorAction.REPORT).decode(text).toString();
                input.position(input.position() + length);
                for (int ch = 0; ch < result[index].length(); ch++) {
                    if (Character.isISOControl(result[index].charAt(ch))) throw invalid();
                }
            }
            if (input.hasRemaining()) throw invalid();
            return result;
        } catch (BufferUnderflowException | CharacterCodingException failure) {
            throw invalid();
        }
    }

    static Decoded decode(byte[] wire, SshRoleContext.Clock clock) throws VaultException {
        if (wire == null || wire.length == 0 || wire.length > MAX_WIRE || clock == null)
            throw invalid();
        byte[] inputWire = wire.clone();
        ByteBuffer input = ByteBuffer.wrap(inputWire).order(ByteOrder.BIG_ENDIAN);
        try {
            if (input.getInt() != MAGIC || input.get() != VERSION) throw invalid();
            int programCode = Byte.toUnsignedInt(input.get());
            if (input.getShort() != 0 || (programCode != 1 && programCode != 2)) throw invalid();
            long token = input.getLong();
            int ownerPid = input.getInt();
            int ownerUid = input.getInt();
            int hop = input.getInt();
            int port = input.getInt();
            long deadline = input.getLong();
            byte[] nonce = new byte[32];
            byte[] connection = new byte[32];
            input.get(nonce);
            input.get(connection);
            String connectionId = text(input, 128);
            String targetIdentity = text(input, 512);
            String host = text(input, 253);
            String publicViewId = text(input, 128);
            if (input.hasRemaining() || token <= 0 || ownerPid <= 0 || ownerUid < 0
                    || hop < 0 || hop >= SshRoleContext.MAX_HOPS || port < 1 || port > 65535
                    || !nonzero(nonce) || !nonzero(connection)
                    || !identifier(connectionId, 128) || !target(targetIdentity)
                    || !host(host) || !identifier(publicViewId, 128)) throw invalid();
            long now = clock.now();
            if (now <= 0 || deadline <= now
                    || deadline - now > SshRoleContext.MAX_LIFETIME_MILLIS) throw invalid();
            SshRoleContext.Program program = programCode == 1
                    ? SshRoleContext.Program.CLIENT : SshRoleContext.Program.JUMP;
            SshRoleContext.Bootstrap bootstrap = new SshRoleContext.Bootstrap(program, ownerPid,
                    hop, connectionId, targetIdentity, host, port, publicViewId, nonce,
                    connection, deadline);
            return new Decoded(token, ownerUid, bootstrap, inputWire);
        } catch (BufferUnderflowException | IllegalArgumentException failure) {
            throw invalid();
        } finally {
            VaultCodec.wipe(inputWire);
        }
    }

    /**
     * The only production ownership callback. Native rechecks the live token,
     * current UID and exact canonical wire; a missing library is not an
     * ownership proof.
     */
    static NativeOwnership productionOwnership() {
        return record -> {
            if (record == null || record.token <= 0 || record.ownerUid < 0) return false;
            byte[] wire = record.canonicalWire();
            try {
                return Native.owns(record.token, record.ownerUid, wire);
            } catch (IOException | LinkageError | RuntimeException failure) {
                return false;
            } finally {
                VaultCodec.wipe(wire);
            }
        };
    }

    /** The only production native role-release callback. */
    static NativeRelease productionRelease() {
        return (token, ownerUid) -> Native.release(token, ownerUid);
    }

    /**
     * Bounded production entry point for an already-created role metadata
     * object. Native supplies the PID and UID; Java never supplies an argv,
     * shell, path, descriptor, body, or secret.
     */
    static byte[] open(SshRoleContext.Bootstrap bootstrap) throws VaultException {
        if (!validBootstrap(bootstrap)) throw invalid();
        byte[] wire = null;
        try {
            wire = openNativeWire(bootstrap);
            if (wire == null || wire.length == 0 || wire.length > MAX_WIRE) throw invalid();
            return wire.clone();
        } catch (IOException | LinkageError | RuntimeException failure) {
            throw unavailable();
        } finally {
            VaultCodec.wipe(wire);
        }
    }

    private static byte[] openNativeWire(SshRoleContext.Bootstrap bootstrap) throws IOException {
        byte[] nonce = bootstrap.roleNonce.clone();
        byte[] binding = bootstrap.connectionBinding.clone();
        try {
            int program = bootstrap.program == SshRoleContext.Program.CLIENT ? 1 : 2;
            return Native.open(program, bootstrap.hop, bootstrap.port, bootstrap.deadline,
                    bootstrap.connectionId, bootstrap.targetIdentity, bootstrap.host,
                    bootstrap.publicViewId, nonce, binding);
        } finally {
            VaultCodec.wipe(nonce);
            VaultCodec.wipe(binding);
        }
    }

    static Issued issue(byte[] wire, SshRoleRegistry registry,
            SshRoleContext.PublicView publicView, SshRoleContext.Clock clock,
            NativeOwnership ownership) throws VaultException {
        if (registry == null || publicView == null || clock == null || ownership == null)
            throw invalid();
        Decoded decoded = decode(wire, clock);
        final boolean owned;
        try {
            owned = ownership.owns(decoded);
        } catch (RuntimeException | LinkageError failure) {
            throw invalid();
        }
        if (!owned) throw invalid();
        SshRoleContext role = registry.issue(decoded.bootstrap, publicView);
        return new Issued(decoded.token, decoded.ownerUid, registry, role);
    }

    /**
     * Release native ownership first. The Java role remains registered when
     * native cleanup fails, so callers cannot mistake a failed close for a
     * completed lifecycle transition.
     */
    static void release(Issued issued, SshRoleRegistry registry, NativeRelease nativeRelease)
            throws VaultException {
        if (issued == null || issued.token <= 0 || issued.ownerUid < 0 || issued.registry != registry
                || issued.role == null || !issued.role.active() || registry == null
                || nativeRelease == null) throw invalid();
        try {
            nativeRelease.release(issued.token, issued.ownerUid);
        } catch (IOException | LinkageError | RuntimeException failure) {
            throw unavailable();
        }
        registry.release(issued.role);
    }

    private static String text(ByteBuffer input, int maximum) throws VaultException {
        int length = Short.toUnsignedInt(input.getShort());
        if (length == 0 || length > maximum || length > input.remaining()) throw invalid();
        byte[] bytes = new byte[length];
        input.get(bytes);
        try {
            for (byte value : bytes) {
                int code = Byte.toUnsignedInt(value);
                if (code < 0x21 || code > 0x7e) throw invalid();
            }
            String value = new String(bytes, StandardCharsets.US_ASCII);
            if (value.indexOf('\0') >= 0) throw invalid();
            return value;
        } finally {
            VaultCodec.wipe(bytes);
        }
    }

    private static boolean nonzero(byte[] value) {
        if (value == null) return false;
        byte combined = 0;
        for (byte item : value) combined |= item;
        return combined != 0;
    }

    private static boolean identifier(String value, int maximum) {
        if (value == null || value.isEmpty() || value.length() > maximum) return false;
        for (int i = 0; i < value.length(); i++) {
            char ch = value.charAt(i);
            if (!asciiLetterOrDigit(ch) && ch != '_' && ch != '-') return false;
        }
        return true;
    }

    private static boolean target(String value) {
        if (value == null || value.isEmpty() || value.length() > 512) return false;
        for (int i = 0; i < value.length(); i++) {
            char ch = value.charAt(i);
            if (!asciiLetterOrDigit(ch) && ch != '_' && ch != '-' && ch != '.'
                    && ch != ':' && ch != '@' && ch != '+' && ch != '/') return false;
        }
        return true;
    }

    private static boolean host(String value) {
        if (value == null || value.isEmpty() || value.length() > 253) return false;
        for (int i = 0; i < value.length(); i++) {
            char ch = value.charAt(i);
            if (!asciiLetterOrDigit(ch) && ch != '.' && ch != ':' && ch != '%'
                    && ch != '[' && ch != ']' && ch != '-') return false;
        }
        return true;
    }

    private static boolean validBootstrap(SshRoleContext.Bootstrap value) {
        return value != null && value.program != null && value.ownerPid > 0
                && value.hop >= 0 && value.hop < SshRoleContext.MAX_HOPS
                && value.port >= 1 && value.port <= 65535
                && value.deadline > 0
                && identifier(value.connectionId, 128)
                && target(value.targetIdentity)
                && host(value.host)
                && identifier(value.publicViewId, 128)
                && nonzero(value.roleNonce) && value.roleNonce.length == ROLE_NONCE_BYTES
                && nonzero(value.connectionBinding)
                && value.connectionBinding.length == CONNECTION_BINDING_BYTES;
    }

    private static boolean asciiLetterOrDigit(char value) {
        return value >= 'A' && value <= 'Z' || value >= 'a' && value <= 'z'
                || value >= '0' && value <= '9';
    }

    private static VaultException invalid() {
        return new VaultException("SSH_ROLE_REQUIRED", "The native SSH role bootstrap is invalid");
    }

    private static VaultException unavailable() {
        return new VaultException("SSH_ROLE_REQUIRED", "The native SSH role bootstrap is unavailable");
    }
}
