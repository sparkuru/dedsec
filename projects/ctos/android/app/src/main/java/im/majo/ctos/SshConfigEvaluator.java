package im.majo.ctos;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Owner-thread configuration evaluation. Summaries are metadata, never launch authority. */
final class SshConfigEvaluator implements AutoCloseable {
    static final int MAX_REQUEST_BYTES = 9766;
    static final int MAX_SUMMARY_BYTES = 829;
    static final int MAX_CATALOG_BYTES = 70424;
    static final int MAX_AUDIT_BYTES = 82732;
    static final int MAX_RESOURCE_AUDIT_BYTES = 107328;
    static final int MAX_DIRECT_PLAN_BYTES = 48 + 3 * 259 + (256 + 32) * 516;
    static final int MAX_DETACHED_BYTES = 8455704;
    static final int MAX_OPENS = 1024, MAX_READ_BYTES = 64 * 1024 * 1024, MAX_LINES = 16384;
    private static final int REQUEST_MAGIC = 0x43534551;
    private static final int SUMMARY_MAGIC = 0x43534553;
    private static final int CATALOG_MAGIC = 0x43534344;
    private static final ThreadLocal<Boolean> ENTRY = new ThreadLocal<>();

    interface ViewBinding {
        long token() throws VaultException;
        boolean active();
    }

    interface DetachedGuard { void check() throws IOException; }

    interface NativeBinding {
        long open(long view, byte[] request) throws IOException;
        int[] run(long handle) throws IOException;
        byte[] summary(long handle) throws IOException;
        default long openCatalog(long view) throws IOException { throw unavailable(); }
        default byte[] catalog(long handle) throws IOException { throw unavailable(); }
        default long reserveResourceAudit() throws IOException { throw unavailable(); }
        default byte[] resourceAudit(long handle) throws IOException { throw unavailable(); }
        default long reserveAudit() throws IOException { throw unavailable(); }
        default byte[] audit(long handle) throws IOException { throw unavailable(); }
        default long reserveDetached(boolean catalogMode) throws IOException { throw unavailable(); }
        default long reserveDirect() throws IOException { throw unavailable(); }
        default byte[] directPlan(long handle) throws IOException { throw unavailable(); }
        default void loadDetached(long handle, byte[] source, int root, long deadline,
                byte[] request) throws IOException { throw unavailable(); }
        default int[] usage(long handle) throws IOException { throw unavailable(); }
        void cancel(long handle) throws IOException;
        void close(long handle) throws IOException;
        void destroy(long handle) throws IOException;
    }

    private static final class Native {
        static { System.loadLibrary("ctos_ssh_files"); }
        static native long open(long view, byte[] request) throws IOException;
        static native int[] run(long handle) throws IOException;
        static native byte[] summary(long handle) throws IOException;
        static native long openCatalog(long view) throws IOException;
        static native byte[] catalog(long handle) throws IOException;
        static native long reserveResourceAudit() throws IOException;
        static native byte[] resourceAudit(long handle) throws IOException;
        static native long reserveAudit() throws IOException;
        static native byte[] audit(long handle) throws IOException;
        static native long reserveDetached(boolean catalogMode) throws IOException;
        static native long reserveDirect() throws IOException;
        static native byte[] directPlan(long handle) throws IOException;
        static native void loadDetached(long handle, byte[] source, int root, long deadline,
                byte[] request) throws IOException;
        static native int[] usage(long handle) throws IOException;
        static native void cancel(long handle) throws IOException;
        static native void close(long handle) throws IOException;
        static native void destroy(long handle) throws IOException;
    }

    private static final NativeBinding PRODUCTION = new NativeBinding() {
        @Override public long open(long view, byte[] request) throws IOException {
            return Native.open(view, request);
        }
        @Override public int[] run(long handle) throws IOException { return Native.run(handle); }
        @Override public byte[] summary(long handle) throws IOException { return Native.summary(handle); }
        @Override public long openCatalog(long view) throws IOException { return Native.openCatalog(view); }
        @Override public byte[] catalog(long handle) throws IOException { return Native.catalog(handle); }
        @Override public long reserveResourceAudit() throws IOException { return Native.reserveResourceAudit(); }
        @Override public byte[] resourceAudit(long handle) throws IOException { return Native.resourceAudit(handle); }
        @Override public long reserveAudit() throws IOException { return Native.reserveAudit(); }
        @Override public byte[] audit(long handle) throws IOException { return Native.audit(handle); }
        @Override public long reserveDetached(boolean catalogMode) throws IOException {
            return Native.reserveDetached(catalogMode);
        }
        @Override public long reserveDirect() throws IOException { return Native.reserveDirect(); }
        @Override public byte[] directPlan(long handle) throws IOException { return Native.directPlan(handle); }
        @Override public void loadDetached(long handle, byte[] source, int root, long deadline,
                byte[] request) throws IOException {
            Native.loadDetached(handle, source, root, deadline, request);
        }
        @Override public int[] usage(long handle) throws IOException { return Native.usage(handle); }
        @Override public void cancel(long handle) throws IOException { Native.cancel(handle); }
        @Override public void close(long handle) throws IOException { Native.close(handle); }
        @Override public void destroy(long handle) throws IOException { Native.destroy(handle); }
    };

    static final class Request {
        final String alias, remoteUser, remoteUserFallback, localUser, localHostname, tag, remoteCommand;
        final int port;
        final long localUid;

        Request(String alias, String remoteUser, String remoteUserFallback, String localUser,
                String localHostname, String tag, String remoteCommand, int port, long localUid)
                throws IOException {
            if (port < 0 || port > 65535 || localUid < 0 || localUid > 0xffffffffL)
                throw invalid();
            this.alias = checkedText(alias, 255, false);
            this.remoteUser = checkedText(remoteUser, 255, true);
            this.remoteUserFallback = checkedText(remoteUserFallback, 255, true);
            this.localUser = checkedText(localUser, 255, false);
            this.localHostname = checkedText(localHostname, 255, false);
            this.tag = checkedText(tag, 255, true);
            this.remoteCommand = checkedText(remoteCommand, 8192, true);
            this.port = port;
            this.localUid = localUid;
        }

        private byte[] encode() throws IOException {
            String[] values = {alias, remoteUser, remoteUserFallback, localUser,
                    localHostname, tag, remoteCommand};
            byte[][] texts = new byte[values.length][];
            try {
                int size = 16 + 4 * values.length;
                for (int i = 0; i < values.length; i++) {
                    texts[i] = utf8(values[i], i == 6 ? 8192 : 255, i != 0 && i != 3 && i != 4);
                    size += texts[i].length;
                }
                if (size > MAX_REQUEST_BYTES) throw invalid();
                ByteBuffer buffer = ByteBuffer.allocate(size);
                buffer.putInt(REQUEST_MAGIC).putInt(1).putInt(port).putInt((int)localUid);
                for (byte[] text : texts) buffer.putInt(text.length).put(text);
                return buffer.array();
            } finally {
                for (byte[] text : texts) wipe(text);
            }
        }
    }

    static final class Diagnostic {
        final int status, reason, object, line, errorNumber;

        private Diagnostic(int[] values) throws IOException {
            if (values == null || values.length != 5) throw invalid();
            int status = values[0], reason = values[1], object = values[2], line = values[3];
            int errorNumber = values[4];
            if (status == 0) {
                if (reason != 0 || object != 0 || line != 0 || errorNumber != 0) throw invalid();
            } else if (status == 1) {
                if (reason < 1 || reason > 13 || object < 0 || object > 128 || line < 0
                        || line > 16385 || (line != 0 && object == 0) || errorNumber <= 0)
                    throw invalid();
            } else if (status == 2) {
                if (reason != 0 || object != 0 || line != 0 || errorNumber <= 0) throw invalid();
            } else throw invalid();
            this.status = status;
            this.reason = reason;
            this.object = object;
            this.line = line;
            this.errorNumber = errorNumber;
        }
    }

    static final class EvaluationException extends IOException {
        private static final long serialVersionUID = 1L;
        final Diagnostic diagnostic;

        private EvaluationException(Diagnostic diagnostic) {
            super("SSH configuration evaluation failed");
            this.diagnostic = diagnostic;
        }
    }

    static final class Summary {
        final boolean userRequired, hasJump, hasRemoteCommand, hasRawFields;
        final int port, identities, certificates, localForwards, remoteForwards;
        final int requestTty, sessionType;
        final String alias, hostname, user;

        private Summary(byte[] wire) throws IOException {
            if (wire == null || wire.length < 64 || wire.length > MAX_SUMMARY_BYTES) throw invalid();
            ByteBuffer buffer = ByteBuffer.wrap(wire);
            if (buffer.getInt() != SUMMARY_MAGIC || buffer.getInt() != 1) throw invalid();
            userRequired = flag(buffer.getInt());
            port = buffer.getInt();
            identities = buffer.getInt();
            certificates = buffer.getInt();
            localForwards = buffer.getInt();
            remoteForwards = buffer.getInt();
            hasJump = flag(buffer.getInt());
            hasRemoteCommand = flag(buffer.getInt());
            requestTty = buffer.getInt();
            sessionType = buffer.getInt();
            hasRawFields = flag(buffer.getInt());
            if (port < 1 || port > 65535 || identities < 0 || identities > 256
                    || certificates < 0 || certificates > 256 || localForwards < 0
                    || localForwards > 32 || remoteForwards < 0 || remoteForwards > 32
                    || localForwards + remoteForwards > 32 || requestTty < 0 || requestTty > 3
                    || sessionType < 0 || sessionType > 2 || !hasRawFields) throw invalid();
            alias = readText(buffer, false);
            hostname = readText(buffer, false);
            user = readText(buffer, true);
            if (buffer.hasRemaining() || userRequired != user.isEmpty()) throw invalid();
        }
    }

    /** Actual supported direct-connection options; the caller still binds a current vault/role. */
    static final class DirectPlan {
        final String alias, hostname, user;
        final int port, connectTimeoutMillis, requestTty, strictHostKeyChecking;
        final boolean identitiesOnly, pubkeyAuthentication, passwordAuthentication, userRequired;
        final List<String> identityFiles, userKnownHostsFiles;
        private DirectPlan(byte[] wire) throws IOException {
            if (wire == null || wire.length < 60 || wire.length > MAX_DIRECT_PLAN_BYTES) throw invalid();
            ByteBuffer buffer = ByteBuffer.wrap(wire);
            if (buffer.getInt() != 0x43534350 || buffer.getInt() != 1) throw invalid();
            port = buffer.getInt();
            identitiesOnly = flag(buffer.getInt());
            pubkeyAuthentication = flag(buffer.getInt());
            passwordAuthentication = flag(buffer.getInt());
            connectTimeoutMillis = buffer.getInt();
            requestTty = buffer.getInt();
            strictHostKeyChecking = buffer.getInt();
            userRequired = flag(buffer.getInt());
            int identities = buffer.getInt(), hosts = buffer.getInt();
            if (port < 1 || port > 65535 || connectTimeoutMillis < 1000 || connectTimeoutMillis > 60000
                    || connectTimeoutMillis % 1000 != 0 || (requestTty != 0 && requestTty != 2 && requestTty != 3)
                    || (strictHostKeyChecking != 2 && strictHostKeyChecking != 3)
                    || identities < 0 || identities > 256 || hosts < 1 || hosts > 32) throw invalid();
            alias = readText(buffer, false); hostname = readText(buffer, false); user = readText(buffer, true);
            if (userRequired != user.isEmpty()) throw invalid();
            List<String> keys = new ArrayList<>(identities), known = new ArrayList<>(hosts);
            for (int i = 0; i < identities + hosts; i++) {
                String path = readText(buffer, false, 512);
                try { VaultManifest.path(path); }
                catch (VaultException error) { throw invalid(); }
                (i < identities ? keys : known).add(path);
            }
            if (buffer.hasRemaining()) throw invalid();
            identityFiles = Collections.unmodifiableList(keys);
            userKnownHostsFiles = Collections.unmodifiableList(known);
        }
    }

    static final class Site {
        final int object, line;

        private Site(ByteBuffer buffer) throws IOException {
            if (buffer.remaining() < 8) throw invalid();
            object = buffer.getInt();
            line = buffer.getInt();
            if (object < 1 || object > 128 || line < 1 || line > 16384) throw invalid();
        }
    }

    static final class Entry {
        final int object, line;
        final String alias;

        private Entry(ByteBuffer buffer) throws IOException {
            Site site = new Site(buffer);
            object = site.object;
            line = site.line;
            alias = readText(buffer, false);
        }
    }

    /** Historical declaration metadata; it neither validates nor authorizes a connection. */
    static final class Catalog {
        final boolean declarationsOnly, omittedSelectors, hasMatch, includesIncomplete;
        final int scannedFiles;
        final int encodedBytes;
        final List<Entry> entries;
        final List<Site> deferredSites;

        private Catalog(byte[] wire) throws IOException {
            this(wire, MAX_CATALOG_BYTES, 512);
        }

        private Catalog(byte[] wire, int maxBytes, int maxRows) throws IOException {
            if (wire == null || wire.length < 24 || wire.length > MAX_CATALOG_BYTES
                    || wire.length > maxBytes) throw invalid();
            encodedBytes = wire.length;
            ByteBuffer buffer = ByteBuffer.wrap(wire);
            if (buffer.getInt() != CATALOG_MAGIC || buffer.getInt() != 1) throw invalid();
            int flags = buffer.getInt();
            scannedFiles = buffer.getInt();
            int aliases = buffer.getInt(), deferred = buffer.getInt();
            if ((flags & ~15) != 0 || (flags & 1) == 0 || scannedFiles < 1 || scannedFiles > 128
                    || aliases < 0 || aliases > 256 || deferred < 0 || deferred > 256
                    || aliases + deferred > maxRows
                    || ((flags & 8) != 0) != (deferred > 0)) throw invalid();
            declarationsOnly = true;
            omittedSelectors = (flags & 2) != 0;
            hasMatch = (flags & 4) != 0;
            includesIncomplete = (flags & 8) != 0;
            List<Entry> entries = new ArrayList<>(aliases);
            Set<String> names = new HashSet<>();
            for (int i = 0; i < aliases; i++) {
                Entry entry = new Entry(buffer);
                if (!names.add(entry.alias)) throw invalid();
                entries.add(entry);
            }
            List<Site> sites = new ArrayList<>(deferred);
            Set<Long> sources = new HashSet<>();
            for (int i = 0; i < deferred; i++) {
                Site site = new Site(buffer);
                if (!sources.add(((long)site.object << 32) | site.line)) throw invalid();
                sites.add(site);
            }
            if (buffer.hasRemaining()) throw invalid();
            this.entries = Collections.unmodifiableList(entries);
            deferredSites = Collections.unmodifiableList(sites);
        }
    }

    /** Actual static Include traversal metadata; no path or command is retained. */
    static final class Edge {
        final int parent, line, child;

        private Edge(ByteBuffer buffer) throws IOException {
            parent = buffer.getInt();
            line = buffer.getInt();
            child = buffer.getInt();
            if (parent < 1 || parent > 128 || child < 1 || child > 128 || parent == child
                    || line < 1 || line > MAX_LINES) throw invalid();
        }
    }

    /** Finite local-effects policy and observed static edges, never complete admission. */
    static final class Audit {
        final boolean localEffectsPolicyChecked = true;
        final int encodedBytes;
        final Catalog catalog;
        final List<Edge> edges;

        private Audit(byte[] wire, int maxBytes, int maxRows) throws IOException {
            if (wire == null || wire.length < 44 || wire.length > MAX_AUDIT_BYTES
                    || wire.length > maxBytes) throw invalid();
            ByteBuffer buffer = ByteBuffer.wrap(wire);
            if (buffer.getInt() != 0x43534341 || buffer.getInt() != 1 || buffer.getInt() != 1)
                throw invalid();
            int catalogLength = buffer.getInt(), edgeCount = buffer.getInt();
            if (catalogLength < 24 || catalogLength > MAX_CATALOG_BYTES || edgeCount < 0
                    || edgeCount > 1024 || edgeCount > maxRows
                    || 20L + catalogLength + 12L * edgeCount != wire.length) throw invalid();
            // Reserve edge rows before the nested codec can allocate aliases/sites/strings.
            int nestedRows = maxRows - edgeCount;
            ByteBuffer header = buffer.slice();
            if (header.getInt() != CATALOG_MAGIC || header.getInt() != 1) throw invalid();
            int flags = header.getInt(), files = header.getInt();
            int aliases = header.getInt(), deferred = header.getInt();
            if ((flags & ~15) != 0 || (flags & 1) == 0 || files < 1 || files > 128
                    || aliases < 0 || aliases > 256 || deferred < 0 || deferred > 256
                    || aliases + deferred > nestedRows
                    || ((flags & 8) != 0) != (deferred > 0)) throw invalid();
            byte[] nested = null;
            try {
                nested = Arrays.copyOfRange(wire, 20, 20 + catalogLength);
                catalog = new Catalog(nested, catalogLength, nestedRows);
            } finally { wipe(nested); }
            buffer.position(20 + catalogLength);
            List<Edge> edges = new ArrayList<>(edgeCount);
            Set<Long> triples = new HashSet<>();
            for (int i = 0; i < edgeCount; i++) {
                Edge edge = new Edge(buffer);
                long key = ((long)edge.parent << 32) | ((long)edge.line << 8) | edge.child;
                if (!triples.add(key)) throw invalid();
                edges.add(edge);
            }
            encodedBytes = wire.length;
            this.edges = Collections.unmodifiableList(edges);
        }
    }

    /** One explicit resource declaration; unresolved values never carry an invented target. */
    static final class Resource {
        static final int STATIC = 1, DEFERRED_CONTEXT = 2, DEFERRED_CWD = 3,
                DISABLED = 4, DEFERRED_KEY_RESOLUTION = 5;
        final int kind, parent, line, argument, state, target;

        private Resource(ByteBuffer buffer) throws IOException {
            kind = buffer.getInt();
            parent = buffer.getInt();
            line = buffer.getInt();
            argument = buffer.getInt();
            state = buffer.getInt();
            target = buffer.getInt();
            if (kind < 1 || kind > 5 || parent < 1 || parent > 128 || line < 1
                    || line > MAX_LINES || argument < 1 || argument > 256
                    || ((kind == 1 || kind == 2) && argument != 1)
                    || ((kind == 3 || kind == 4) && argument > 32)
                    || state < STATIC || state > DEFERRED_KEY_RESOLUTION
                    || (kind == 4 && state == DEFERRED_CONTEXT)
                    || (state == DISABLED && (kind == 2 || argument != 1))
                    || (state == DEFERRED_KEY_RESOLUTION && kind != 1 && kind != 2)
                    || (state == STATIC ? target < 1 || target > 128 || target == parent : target != 0))
                throw invalid();
        }

        boolean acceptsType(int type) {
            switch (kind) {
                case 1: return type == 2 || type == 3 || type == 4;
                case 2: return type == 4;
                case 3:
                case 4: return type == 5;
                case 5: return type == 3;
                default: return false;
            }
        }
    }

    /** Static literal metadata bindings and explicit unresolved path/key obligations. */
    static final class ResourceAudit {
        final Audit audit;
        final List<Resource> resources;
        final int encodedBytes;
        final boolean hasDeferred;

        private ResourceAudit(byte[] wire, int maxBytes, int maxRows) throws IOException {
            if (wire == null || wire.length < 64 || wire.length > MAX_RESOURCE_AUDIT_BYTES
                    || wire.length > maxBytes) throw invalid();
            ByteBuffer buffer = ByteBuffer.wrap(wire);
            if (buffer.getInt() != 0x43534352 || buffer.getInt() != 1 || buffer.getInt() != 1)
                throw invalid();
            int auditLength = buffer.getInt(), count = buffer.getInt();
            if (auditLength < 44 || auditLength > MAX_AUDIT_BYTES || count < 0 || count > 1024
                    || count > maxRows || 20L + auditLength + 24L * count != wire.length)
                throw invalid();
            // Reserve every resource row before the nested audit/catalog can allocate metadata.
            byte[] nested = null;
            try {
                nested = Arrays.copyOfRange(wire, 20, 20 + auditLength);
                audit = new Audit(nested, auditLength, maxRows - count);
            } finally { wipe(nested); }
            buffer.position(20 + auditLength);
            List<Resource> resources = new ArrayList<>(count);
            Set<Long> locators = new HashSet<>();
            boolean deferred = false;
            for (int i = 0; i < count; i++) {
                Resource resource = new Resource(buffer);
                // Argument 256 requires nine bits; it must not overlap source-line bits.
                long locator = ((long)resource.kind << 40) | ((long)resource.parent << 32)
                        | ((long)resource.line << 9) | resource.argument;
                if (!locators.add(locator)) throw invalid();
                resources.add(resource);
                deferred |= resource.state == Resource.DEFERRED_CONTEXT
                        || resource.state == Resource.DEFERRED_CWD
                        || resource.state == Resource.DEFERRED_KEY_RESOLUTION;
            }
            this.resources = Collections.unmodifiableList(resources);
            encodedBytes = wire.length;
            hasDeferred = deferred;
        }
    }

    private final Thread owner = Thread.currentThread();
    private final ViewBinding view;
    private final DetachedGuard detachedGuard;
    private final NativeBinding binding;
    private final boolean catalogMode, auditMode, resourceAuditMode, directMode;
    private volatile long handle;
    private volatile boolean cancelled;
    private boolean inFlight, attempted, ready, nativeClosed;
    private boolean loadAttempted, loaded;
    private int maxOpens, maxReadBytes, maxLines;

    private SshConfigEvaluator(ViewBinding view, NativeBinding binding, boolean catalogMode) {
        this.view = view;
        detachedGuard = null;
        this.binding = binding;
        this.catalogMode = catalogMode;
        auditMode = false;
        resourceAuditMode = false;
        directMode = false;
    }

    private SshConfigEvaluator(DetachedGuard guard, NativeBinding binding, boolean catalogMode) {
        this(guard, binding, catalogMode, false);
    }

    private SshConfigEvaluator(DetachedGuard guard, NativeBinding binding, boolean catalogMode,
            boolean auditMode) {
        this(guard, binding, catalogMode, auditMode, false);
    }

    private SshConfigEvaluator(DetachedGuard guard, NativeBinding binding, boolean catalogMode,
            boolean auditMode, boolean resourceAuditMode) {
        this(guard, binding, catalogMode, auditMode, resourceAuditMode, false);
    }

    private SshConfigEvaluator(DetachedGuard guard, NativeBinding binding, boolean catalogMode,
            boolean auditMode, boolean resourceAuditMode, boolean directMode) {
        view = null;
        detachedGuard = guard;
        this.binding = binding;
        this.catalogMode = catalogMode;
        this.auditMode = auditMode;
        this.resourceAuditMode = resourceAuditMode;
        this.directMode = directMode;
    }

    static SshConfigEvaluator prepareDetached(DetachedGuard guard, boolean catalogMode)
            throws IOException {
        return prepareDetached(guard, PRODUCTION, catalogMode);
    }

    static SshConfigEvaluator prepareDetached(DetachedGuard guard, NativeBinding binding,
            boolean catalogMode) throws IOException {
        return prepareDetachedMode(guard, binding, catalogMode, false);
    }
    static SshConfigEvaluator prepareDirectDetached(DetachedGuard guard) throws IOException {
        return prepareDirectDetached(guard, PRODUCTION);
    }

    static SshConfigEvaluator prepareDirectDetached(DetachedGuard guard, NativeBinding binding) throws IOException {
        return prepareDetachedMode(guard, binding, false, false, false, true);
    }

    static SshConfigEvaluator prepareDetachedAudit(DetachedGuard guard) throws IOException {
        return prepareDetachedAudit(guard, PRODUCTION);
    }

    static SshConfigEvaluator prepareDetachedAudit(DetachedGuard guard, NativeBinding binding)
            throws IOException {
        return prepareDetachedMode(guard, binding, false, true);
    }

    static SshConfigEvaluator prepareDetachedResourceAudit(DetachedGuard guard) throws IOException {
        return prepareDetachedResourceAudit(guard, PRODUCTION);
    }

    static SshConfigEvaluator prepareDetachedResourceAudit(DetachedGuard guard, NativeBinding binding)
            throws IOException {
        return prepareDetachedMode(guard, binding, false, true, true);
    }

    private static SshConfigEvaluator prepareDetachedMode(DetachedGuard guard, NativeBinding binding,
            boolean catalogMode, boolean auditMode) throws IOException {
        return prepareDetachedMode(guard, binding, catalogMode, auditMode, false);
    }

    private static SshConfigEvaluator prepareDetachedMode(DetachedGuard guard, NativeBinding binding,
            boolean catalogMode, boolean auditMode, boolean resourceAuditMode) throws IOException {
        return prepareDetachedMode(guard, binding, catalogMode, auditMode, resourceAuditMode, false);
    }

    private static SshConfigEvaluator prepareDetachedMode(DetachedGuard guard, NativeBinding binding,
            boolean catalogMode, boolean auditMode, boolean resourceAuditMode, boolean directMode) throws IOException {
        if (guard == null || binding == null) throw invalid();
        SshConfigEvaluator evaluator = new SshConfigEvaluator(guard, binding, catalogMode, auditMode,
                resourceAuditMode, directMode);
        evaluator.enter();
        try {
            guard.check();
            long token = directMode ? binding.reserveDirect() : resourceAuditMode ? binding.reserveResourceAudit()
                    : auditMode ? binding.reserveAudit() : binding.reserveDetached(catalogMode);
            if (token <= 0) throw unavailable();
            evaluator.handle = token;
            // A positive token must reach the caller even when cancellation raced reserve.
            return evaluator;
        } catch (IOException | LinkageError failure) { throw unavailable(); }
        finally { evaluator.leave(); }
    }

    void loadDetached(byte[] source, int root, long deadline, Request request) throws IOException {
        enter();
        byte[] wire = null;
        try {
            if (detachedGuard == null || loadAttempted) throw unavailable();
            loadAttempted = true;
            live();
            if (source == null || source.length < 24 || source.length > MAX_DETACHED_BYTES
                    || ((catalogMode || auditMode) ? request != null : request == null)) throw invalid();
            ByteBuffer header = ByteBuffer.wrap(source);
            if (header.getInt() != 0x43534449 || header.getInt() != 1) throw invalid();
            int count = header.getInt();
            maxOpens = header.getInt();
            maxReadBytes = header.getInt();
            maxLines = header.getInt();
            if (count < 1 || count > 128 || root < 0 || root >= count
                    || maxOpens < 0 || maxOpens > MAX_OPENS || maxReadBytes < 0
                    || maxReadBytes > MAX_READ_BYTES || maxLines < 0 || maxLines > MAX_LINES)
                throw invalid();
            if (!catalogMode && !auditMode) wire = request.encode();
            live();
            binding.loadDetached(handle, source, root, deadline, wire);
            live();
            loaded = true;
        } catch (IOException | LinkageError failure) { throw unavailable(); }
        finally { wipe(wire); leave(); }
    }

    static final class Usage {
        final int opens, readBytes, lines;
        Usage(int opens, int readBytes, int lines) {
            this.opens = opens;
            this.readBytes = readBytes;
            this.lines = lines;
        }
    }

    Usage usage() throws IOException {
        enter();
        int[] values = null;
        try {
            live();
            if (detachedGuard == null || !ready) throw unavailable();
            values = binding.usage(handle);
            if (values == null || values.length != 3 || values[0] < 1 || values[0] > maxOpens
                    || values[1] < 0 || values[1] > maxReadBytes
                    || values[2] < 0 || values[2] > maxLines) throw invalid();
            Usage usage = new Usage(values[0], values[1], values[2]);
            live();
            return usage;
        } catch (IOException | LinkageError failure) { throw unavailable(); }
        finally { if (values != null) Arrays.fill(values, 0); leave(); }
    }

    static SshConfigEvaluator open(SshPublicFileLease lease, Request request) throws IOException {
        if (lease == null) throw invalid();
        return open(new ViewBinding() {
            @Override public long token() throws VaultException { return lease.nativeToken(); }
            @Override public boolean active() { return lease.active(); }
        }, request, PRODUCTION);
    }

    static SshConfigEvaluator open(ViewBinding view, Request request, NativeBinding binding)
            throws IOException {
        if (view == null || request == null || binding == null) throw invalid();
        return openMode(view, request, binding, false);
    }

    static SshConfigEvaluator openCatalog(SshPublicFileLease lease) throws IOException {
        if (lease == null) throw invalid();
        return openCatalog(new ViewBinding() {
            @Override public long token() throws VaultException { return lease.nativeToken(); }
            @Override public boolean active() { return lease.active(); }
        }, PRODUCTION);
    }

    static SshConfigEvaluator openCatalog(ViewBinding view, NativeBinding binding) throws IOException {
        if (view == null || binding == null) throw invalid();
        return openMode(view, null, binding, true);
    }

    private static SshConfigEvaluator openMode(ViewBinding view, Request request,
            NativeBinding binding, boolean catalogMode) throws IOException {
        SshConfigEvaluator evaluator = new SshConfigEvaluator(view, binding, catalogMode);
        byte[] wire = null;
        evaluator.enter();
        try {
            if (!catalogMode) wire = request.encode();
            evaluator.liveView();
            long viewToken;
            try { viewToken = view.token(); }
            catch (VaultException failure) { throw unavailable(); }
            if (viewToken <= 0) throw unavailable();
            long token = catalogMode ? binding.openCatalog(viewToken) : binding.open(viewToken, wire);
            if (token <= 0) throw unavailable();
            evaluator.handle = token;
            // Do not perform a failing postcheck: a positive token owns cleanup.
            return evaluator;
        } catch (IOException | LinkageError failure) {
            throw unavailable();
        } finally {
            evaluator.leave();
            wipe(wire);
        }
    }

    void run() throws IOException {
        enter();
        int[] values = null;
        try {
            if (handle <= 0 || cancelled || nativeClosed) throw unavailable();
            if (detachedGuard != null && !loaded) throw unavailable();
            if (detachedGuard != null) live();
            if (attempted) throw unavailable();
            attempted = true;
            values = binding.run(handle);
            Diagnostic diagnostic = new Diagnostic(values);
            // A retained failed-open/revoked handle must keep its native diagnostic.
            // Only a successful result is subject to the Java publication check.
            if (diagnostic.status != 0) throw new EvaluationException(diagnostic);
            live();
            ready = true;
        } catch (EvaluationException failure) { throw failure; }
        catch (IOException | LinkageError failure) { throw unavailable(); }
        finally {
            if (values != null) Arrays.fill(values, 0);
            leave();
        }
    }

    Summary summary() throws IOException {
        enter();
        byte[] wire = null;
        try {
            live();
            if (!ready || catalogMode || auditMode) throw unavailable();
            wire = binding.summary(handle);
            Summary summary = new Summary(wire);
            live();
            return summary;
        } catch (IOException | LinkageError failure) { throw unavailable(); }
        finally { wipe(wire); leave(); }
    }

    DirectPlan directPlan() throws IOException {
        enter();
        byte[] wire = null;
        try {
            live();
            if (!ready || !directMode) throw unavailable();
            wire = binding.directPlan(handle);
            DirectPlan plan = new DirectPlan(wire);
            live();
            return plan;
        } catch (IOException | LinkageError failure) { throw unavailable(); }
        finally { wipe(wire); leave(); }
    }

    Catalog catalog() throws IOException {
        return catalog(MAX_CATALOG_BYTES, 512);
    }

    Catalog catalog(int maxBytes, int maxRows) throws IOException {
        enter();
        byte[] wire = null;
        try {
            live();
            if (!ready || !catalogMode || auditMode) throw unavailable();
            wire = binding.catalog(handle);
            Catalog catalog = new Catalog(wire, maxBytes, maxRows);
            live();
            return catalog;
        } catch (IOException | LinkageError failure) { throw unavailable(); }
        finally { wipe(wire); leave(); }
    }

    Audit audit(int maxBytes, int maxRows) throws IOException {
        enter();
        byte[] wire = null;
        try {
            live();
            if (!ready || !auditMode || resourceAuditMode) throw unavailable();
            wire = binding.audit(handle);
            Audit audit = new Audit(wire, maxBytes, maxRows);
            live();
            return audit;
        } catch (IOException | LinkageError failure) { throw unavailable(); }
        finally { wipe(wire); leave(); }
    }

    ResourceAudit resourceAudit(int maxBytes, int maxRows) throws IOException {
        enter();
        byte[] wire = null;
        try {
            live();
            if (!ready || !resourceAuditMode) throw unavailable();
            wire = binding.resourceAudit(handle);
            ResourceAudit audit = new ResourceAudit(wire, maxBytes, maxRows);
            live();
            return audit;
        } catch (IOException | LinkageError failure) { throw unavailable(); }
        finally { wipe(wire); leave(); }
    }

    void cancel() throws IOException {
        cancelled = true;
        long token = handle;
        if (token <= 0) return;
        if (Boolean.TRUE.equals(ENTRY.get())) throw unavailable();
        ENTRY.set(true);
        try { binding.cancel(token); }
        catch (IOException | LinkageError failure) { throw unavailable(); }
        finally { ENTRY.remove(); }
    }

    @Override public void close() throws IOException {
        enter();
        try {
            if (handle <= 0) return;
            if (!nativeClosed) {
                binding.close(handle);
                nativeClosed = true;
                ready = false;
            }
            binding.destroy(handle);
            handle = 0;
        } catch (IOException | LinkageError failure) { throw unavailable(); }
        finally { leave(); }
    }

    private void enter() throws IOException {
        if (Thread.currentThread() != owner || inFlight || Boolean.TRUE.equals(ENTRY.get()))
            throw unavailable();
        ENTRY.set(true);
        inFlight = true;
    }

    private void leave() { ENTRY.remove(); inFlight = false; }

    private void liveView() throws IOException {
        if (detachedGuard != null) detachedGuard.check();
        else if (!view.active()) throw unavailable();
    }

    private void live() throws IOException {
        if (handle <= 0 || cancelled || nativeClosed) throw unavailable();
        liveView();
    }

    private static boolean flag(int value) throws IOException {
        if (value != 0 && value != 1) throw invalid();
        return value == 1;
    }

    private static String readText(ByteBuffer buffer, boolean empty) throws IOException {
        return readText(buffer, empty, 255);
    }

    private static String readText(ByteBuffer buffer, boolean empty, int maximum) throws IOException {
        if (buffer.remaining() < 4) throw invalid();
        int length = buffer.getInt();
        if (length < 0 || length > maximum || length > buffer.remaining() || (!empty && length == 0))
            throw invalid();
        ByteBuffer text = buffer.slice();
        text.limit(length);
        try {
            String value = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(text).toString();
            checkedCharacters(value);
            buffer.position(buffer.position() + length);
            return value;
        } catch (CharacterCodingException failure) { throw invalid(); }
    }

    private static String checkedText(String value, int limit, boolean empty) throws IOException {
        byte[] bytes = utf8(value, limit, empty);
        wipe(bytes);
        return value;
    }

    private static byte[] utf8(String value, int limit, boolean empty) throws IOException {
        if (value == null || (!empty && value.isEmpty()) || value.length() > limit) throw invalid();
        checkedCharacters(value);
        ByteBuffer encoded = null;
        byte[] result = null;
        try {
            encoded = StandardCharsets.UTF_8.newEncoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).encode(java.nio.CharBuffer.wrap(value));
            if (encoded.remaining() > limit) throw invalid();
            result = new byte[encoded.remaining()];
            encoded.get(result);
            return result;
        } catch (CharacterCodingException failure) { throw invalid(); }
        finally { if (encoded != null && encoded.hasArray()) wipe(encoded.array()); }
    }

    private static void checkedCharacters(String value) throws IOException {
        for (int i = 0; i < value.length(); i++) {
            char ch = value.charAt(i);
            if (Character.isISOControl(ch)) throw invalid();
            if (Character.isHighSurrogate(ch)) {
                if (++i >= value.length() || !Character.isLowSurrogate(value.charAt(i))) throw invalid();
            } else if (Character.isLowSurrogate(ch)) throw invalid();
        }
    }

    private static void wipe(byte[] bytes) { if (bytes != null) Arrays.fill(bytes, (byte)0); }
    private static IOException invalid() { return new IOException("Invalid SSH configuration metadata"); }
    private static IOException unavailable() { return new IOException("SSH configuration evaluator unavailable"); }
}
