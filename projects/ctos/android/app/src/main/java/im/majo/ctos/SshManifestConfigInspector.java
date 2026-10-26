package im.majo.ctos;

import android.os.SystemClock;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

/** One-shot declaration scan of the actual exclusive caller loan; never semantic admission. */
final class SshManifestConfigInspector implements AutoCloseable {
    private static final long MAX_DURATION = 60_000L;
    private static final int MAX_OUTPUT = 1024 * 1024, MAX_ROWS = 4096;
    private static final ThreadLocal<Boolean> ENTERED = new ThreadLocal<>();

    interface Clock { long now(); }

    /** One cancellation belongs to exactly one job, including its retained cleanup owner. */
    static final class Cancellation {
        private final AtomicBoolean cancelled = new AtomicBoolean();
        private Object job;
        private SshConfigEvaluator active;
        private boolean forwardingFailed;

        void cancel() {
            cancelled.set(true);
            SshConfigEvaluator target;
            synchronized (this) { target = active; }
            if (target != null) forward(target);
        }

        boolean cancelled() { return cancelled.get(); }

        private synchronized void claim(Object owner) throws IOException {
            if (job != null) throw invalid();
            job = owner;
        }

        private void attach(Object owner, SshConfigEvaluator target) throws IOException {
            synchronized (this) {
                if (job != owner || active != null) throw invalid();
                active = target;
            }
            // Cancel may have seen no token while reserve was in flight.
            if (cancelled.get()) forward(target);
        }

        private synchronized void detach(Object owner, SshConfigEvaluator target) {
            if (job == owner && active == target) active = null;
        }

        private void forward(SshConfigEvaluator target) {
            try { target.cancel(); }
            catch (IOException failure) {
                synchronized (this) {
                    if (active == target) forwardingFailed = true;
                }
            }
        }

        private synchronized boolean failed() { return forwardingFailed; }
    }

    static final class Root {
        /** Original one-based file ordinal, matching catalog source objects. */
        final int object;
        final String id, path, identity;
        final SshConfigEvaluator.Catalog catalog;
        final SshConfigEvaluator.Audit audit;
        final SshConfigEvaluator.ResourceAudit resourceAudit;
        final SshConfigEvaluator.Usage usage;

        private Root(int index, VaultManifest.FileObject file, SshConfigEvaluator.Catalog catalog,
                SshConfigEvaluator.Audit audit, SshConfigEvaluator.ResourceAudit resourceAudit,
                SshConfigEvaluator.Usage usage) {
            object = index + 1;
            id = file.id;
            path = file.path;
            identity = file.identity;
            this.catalog = catalog;
            this.audit = audit;
            this.resourceAudit = resourceAudit;
            this.usage = usage;
        }
    }

    static final class Report {
        final boolean declarationsOnly = true, admissionComplete = false;
        final boolean staticIncludeReferencesVerified, localEffectsPolicyChecked;
        final boolean staticResourceReferencesVerified, resourcesIncomplete;
        final int configFiles, processedRootCount, catalogBytes;
        final List<Root> roots;
        final SshConfigEvaluator.Usage usage;

        private Report(List<Root> roots, int configFiles, int catalogBytes,
                int opens, int readBytes, int lines, boolean audited, boolean resourcesAudited) {
            this.roots = Collections.unmodifiableList(new ArrayList<>(roots));
            this.configFiles = configFiles;
            processedRootCount = roots.size();
            this.catalogBytes = catalogBytes;
            staticIncludeReferencesVerified = audited;
            localEffectsPolicyChecked = audited;
            staticResourceReferencesVerified = resourcesAudited;
            boolean incomplete = false;
            for (Root root : roots)
                incomplete |= root.resourceAudit != null && root.resourceAudit.hasDeferred;
            resourcesIncomplete = incomplete;
            usage = new SshConfigEvaluator.Usage(opens, readBytes, lines);
        }
    }

    static final class ReferenceException extends IOException {
        private static final long serialVersionUID = 1L;
        final int parent, line, child;

        private ReferenceException(SshConfigEvaluator.Edge edge) {
            super("SSH configuration static Include reference missing");
            parent = edge.parent;
            line = edge.line;
            child = edge.child;
        }
    }

    static final class ResourceReferenceException extends IOException {
        private static final long serialVersionUID = 1L;
        final int kind, parent, line, argument, target;

        private ResourceReferenceException(SshConfigEvaluator.Resource resource) {
            super("SSH configuration static resource reference missing");
            kind = resource.kind;
            parent = resource.parent;
            line = resource.line;
            argument = resource.argument;
            target = resource.target;
        }
    }

    /** Establish all cleanup slots before copying any configuration bytes. */
    private static final class Input implements AutoCloseable {
        final byte[][] paths, bodies;
        final int[] types;
        byte[] wire;
        int configs, totalBodies;

        Input(int count) {
            paths = new byte[count][];
            bodies = new byte[count][];
            types = new int[count];
        }

        @Override public void close() {
            for (byte[] path : paths) wipe(path);
            for (byte[] body : bodies) wipe(body);
            wipe(wire);
        }
    }

    private final Thread owner = Thread.currentThread();
    private final Clock clock;
    private final SshConfigEvaluator.NativeBinding binding;
    private boolean attempted, inFlight, cleanupFailed;
    private long deadline;
    private Cancellation cancellation;
    private SshConfigEvaluator active;

    SshManifestConfigInspector() { this(SystemClock::elapsedRealtime, null); }

    SshManifestConfigInspector(Clock clock, SshConfigEvaluator.NativeBinding binding) {
        if (clock == null) throw new IllegalArgumentException("Inspection clock required");
        this.clock = clock;
        this.binding = binding;
    }

    Report inspect(VaultManifest manifest, long deadline, Cancellation cancellation) throws IOException {
        return inspectMode(manifest, deadline, cancellation, false, false);
    }

    Report inspectAudit(VaultManifest manifest, long deadline, Cancellation cancellation) throws IOException {
        return inspectMode(manifest, deadline, cancellation, true, false);
    }

    Report inspectResourceAudit(VaultManifest manifest, long deadline, Cancellation cancellation)
            throws IOException {
        return inspectMode(manifest, deadline, cancellation, true, true);
    }

    private Report inspectMode(VaultManifest manifest, long deadline, Cancellation cancellation,
            boolean audited, boolean resourcesAudited) throws IOException {
        enter();
        Input input = null;
        Throwable primary = null;
        Report report;
        try {
            if (attempted) throw invalid();
            attempted = true;
            if (manifest == null || cancellation == null || manifest.files.size() > 128)
                throw invalid();
            this.deadline = deadline;
            this.cancellation = cancellation;
            cancellation.claim(this);
            long start = check();
            if (deadline - start > MAX_DURATION) throw invalid();
            input = new Input(manifest.files.size());
            copy(manifest, input);
            List<Root> roots = new ArrayList<>();
            int opens = 0, readBytes = 0, lines = 0, output = 0, rows = 0;
            for (int i = 0; i < input.types.length; i++) {
                if (input.types[i] != 1) continue;
                check();
                ByteBuffer.wrap(input.wire).putInt(12, SshConfigEvaluator.MAX_OPENS - opens)
                        .putInt(16, SshConfigEvaluator.MAX_READ_BYTES - readBytes)
                        .putInt(20, SshConfigEvaluator.MAX_LINES - lines);
                if (resourcesAudited) {
                    active = binding == null ? SshConfigEvaluator.prepareDetachedResourceAudit(this::checkGuard)
                            : SshConfigEvaluator.prepareDetachedResourceAudit(this::checkGuard, binding);
                } else if (audited) {
                    active = binding == null ? SshConfigEvaluator.prepareDetachedAudit(this::checkGuard)
                            : SshConfigEvaluator.prepareDetachedAudit(this::checkGuard, binding);
                } else {
                    active = binding == null ? SshConfigEvaluator.prepareDetached(this::checkGuard, true)
                            : SshConfigEvaluator.prepareDetached(this::checkGuard, binding, true);
                }
                cancellation.attach(this, active);
                check();
                active.loadDetached(input.wire, i, deadline, null);
                check();
                active.run();
                check();
                SshConfigEvaluator.Usage usage = active.usage();
                check();
                SshConfigEvaluator.ResourceAudit resourceAudit = resourcesAudited
                        ? active.resourceAudit(MAX_OUTPUT - output, MAX_ROWS - rows) : null;
                SshConfigEvaluator.Audit audit = resourceAudit != null ? resourceAudit.audit : audited
                        ? active.audit(MAX_OUTPUT - output, MAX_ROWS - rows) : null;
                SshConfigEvaluator.Catalog catalog = audit == null
                        ? active.catalog(MAX_OUTPUT - output, MAX_ROWS - rows) : audit.catalog;
                check();
                validateCatalog(catalog, input);
                if (audit != null) validateReferences(audit, input, manifest);
                if (resourceAudit != null) validateResources(resourceAudit, input, manifest);
                int encodedBytes = resourceAudit != null ? resourceAudit.encodedBytes
                        : audit == null ? catalog.encodedBytes : audit.encodedBytes;
                int retainedRows = catalog.entries.size() + catalog.deferredSites.size()
                        + (audit == null ? 0 : audit.edges.size())
                        + (resourceAudit == null ? 0 : resourceAudit.resources.size());
                if (encodedBytes > MAX_OUTPUT - output || retainedRows > MAX_ROWS - rows) throw limit();
                output += encodedBytes;
                rows += retainedRows;
                opens += usage.opens;
                readBytes += usage.readBytes;
                lines += usage.lines;
                roots.add(new Root(i, manifest.files.get(i), catalog, audit, resourceAudit, usage));
                cleanup();
                check();
            }
            report = new Report(roots, input.configs, output, opens, readBytes, lines, audited, resourcesAudited);
        } catch (IOException | RuntimeException | Error failure) {
            primary = failure;
            throw failure;
        } finally {
            try {
                try { if (!cleanupFailed) cleanup(); }
                catch (IOException | RuntimeException | Error failure) {
                    if (primary == null) throw failure;
                    if (primary != failure) primary.addSuppressed(failure);
                }
            } finally {
                try {
                    if (input != null) input.close();
                    // Cleanup and the original lifetime are part of publication.
                    if (primary == null && active == null) check();
                } finally { leave(); }
            }
        }
        return report;
    }

    private void checkGuard() throws IOException { check(); }

    private long check() throws IOException {
        long now = clock.now();
        if (cancellation == null || cancellation.cancelled() || cancellation.failed()
                || now < 0 || deadline <= now) throw invalid();
        return now;
    }

    private void copy(VaultManifest manifest, Input input) throws IOException {
        int size = 24;
        Set<String> names = new HashSet<>();
        for (int i = 0; i < input.types.length; i++) {
            check();
            VaultManifest.FileObject file = manifest.files.get(i);
            if (file == null || file.type == null) throw invalid();
            input.types[i] = nativeType(file.type);
            input.paths[i] = path(file.path);
            if (!names.add(file.path)) throw invalid();
            for (String other : names) {
                if (!other.equals(file.path) && (other.startsWith(file.path + "/")
                        || file.path.startsWith(other + "/"))) throw invalid();
            }
            size += 12 + input.paths[i].length;
            if (input.types[i] == 1) {
                if (file.content == null || file.content.length > VaultManifest.MAX_FILE_BYTES
                        || file.content.length > VaultManifest.MAX_TOTAL_FILE_BYTES - input.totalBodies)
                    throw limit();
                input.totalBodies += file.content.length;
                input.configs++;
            }
        }
        if ((long) input.configs * input.totalBodies > SshConfigEvaluator.MAX_READ_BYTES) throw limit();
        if (input.configs == 0) return;
        for (int i = 0; i < input.types.length; i++) {
            check();
            if (input.types[i] != 1) continue;
            byte[] source = manifest.files.get(i).content;
            input.bodies[i] = new byte[source.length];
            System.arraycopy(source, 0, input.bodies[i], 0, source.length);
            check();
        }
        size += input.totalBodies;
        if (size > SshConfigEvaluator.MAX_DETACHED_BYTES) throw limit();
        input.wire = new byte[size];
        ByteBuffer wire = ByteBuffer.wrap(input.wire);
        wire.putInt(0x43534449).putInt(1).putInt(input.types.length).putInt(0).putInt(0).putInt(0);
        for (int i = 0; i < input.types.length; i++) {
            check();
            wire.putInt(input.types[i]).putInt(input.paths[i].length).put(input.paths[i]);
            byte[] body = input.bodies[i];
            wire.putInt(body == null ? 0 : body.length);
            if (body != null) wire.put(body);
        }
        check();
    }

    private static void validateCatalog(SshConfigEvaluator.Catalog catalog, Input input)
            throws IOException {
        if (catalog.scannedFiles > input.configs) throw invalid();
        for (SshConfigEvaluator.Entry entry : catalog.entries) source(entry.object, input);
        for (SshConfigEvaluator.Site site : catalog.deferredSites) source(site.object, input);
    }

    private static void validateReferences(SshConfigEvaluator.Audit audit, Input input,
            VaultManifest manifest) throws IOException {
        for (SshConfigEvaluator.Edge edge : audit.edges) {
            source(edge.parent, input);
            source(edge.child, input);
            VaultManifest.FileObject parent = manifest.files.get(edge.parent - 1);
            String childPath = manifest.files.get(edge.child - 1).path;
            if (!parent.references.contains(childPath)) throw new ReferenceException(edge);
        }
    }

    private static void validateResources(SshConfigEvaluator.ResourceAudit audit, Input input,
            VaultManifest manifest) throws IOException {
        for (SshConfigEvaluator.Resource resource : audit.resources) {
            source(resource.parent, input);
            if (resource.state != SshConfigEvaluator.Resource.STATIC) continue;
            if (resource.target < 1 || resource.target > input.types.length
                    || !resource.acceptsType(input.types[resource.target - 1])) throw invalid();
            VaultManifest.FileObject parent = manifest.files.get(resource.parent - 1);
            String targetPath = manifest.files.get(resource.target - 1).path;
            if (!parent.references.contains(targetPath)) throw new ResourceReferenceException(resource);
        }
    }

    private static void source(int object, Input input) throws IOException {
        if (object < 1 || object > input.types.length || input.types[object - 1] != 1) throw invalid();
    }

    private void cleanup() throws IOException {
        SshConfigEvaluator target = active;
        if (target == null) return;
        try { target.close(); }
        catch (IOException | RuntimeException | Error failure) {
            cleanupFailed = true;
            throw failure;
        }
        // Keep the exact evaluator reachable on either close or destroy failure.
        if (cancellation != null) cancellation.detach(this, target);
        active = null;
    }

    boolean hasPendingCleanup() { return active != null; }

    @Override public void close() throws IOException {
        enter();
        try { attempted = true; cleanupFailed = false; cleanup(); }
        finally { leave(); }
    }

    private void enter() throws IOException {
        if (Thread.currentThread() != owner || inFlight || Boolean.TRUE.equals(ENTERED.get()))
            throw invalid();
        ENTERED.set(true);
        inFlight = true;
    }

    private void leave() { inFlight = false; ENTERED.remove(); }

    private static byte[] path(String value) throws IOException {
        if (value == null) throw invalid();
        try { VaultManifest.path(value); }
        catch (VaultException failure) { throw invalid(); }
        for (int i = 0; i < value.length(); i++) {
            if (Character.isISOControl(value.charAt(i))) throw invalid();
        }
        ByteBuffer encoded = null;
        try {
            encoded = StandardCharsets.UTF_8.newEncoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).encode(CharBuffer.wrap(value));
            if (encoded.remaining() > 512) throw invalid();
            byte[] result = new byte[encoded.remaining()];
            encoded.get(result);
            return result;
        } catch (CharacterCodingException failure) { throw invalid(); }
        finally { if (encoded != null && encoded.hasArray()) wipe(encoded.array()); }
    }

    private static int nativeType(VaultManifest.FileType type) throws IOException {
        switch (type) {
            case CONFIG: return 1;
            case PRIVATE_KEY: return 2;
            case PUBLIC_KEY: return 3;
            case CERTIFICATE: return 4;
            case KNOWN_HOSTS: return 5;
            default: throw invalid();
        }
    }

    private static void wipe(byte[] bytes) { if (bytes != null) Arrays.fill(bytes, (byte) 0); }
    private static IOException invalid() { return new IOException("SSH configuration inspection unavailable"); }
    private static IOException limit() { return new IOException("SSH configuration inspection limit exceeded"); }
}
