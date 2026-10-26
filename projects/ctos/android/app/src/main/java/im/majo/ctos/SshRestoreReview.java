package im.majo.ctos;

import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/** Bounded metadata projection from a real SSH reviewer, not an SSH parser or secret transport. */
final class SshRestoreReview implements AutoCloseable {
    static final int MAX_COVERAGE = 2 * (VaultManifest.MAX_ENTRIES + VaultManifest.MAX_FILES);
    static final int MAX_ROWS = 4096;
    static final int MAX_BYTES = 1024 * 1024;
    enum Side { SOURCE, TARGET }
    enum Kind { FILE, CREDENTIAL, TRUST }
    enum TrustStatus { IMPORTED, SAME, CHANGED, TARGET_REMOVED, UNKNOWN_MAPPING }
    enum ConflictStatus { IDENTITY_CHANGED, CONTENT_CHANGED, TARGET_REMOVED, UNKNOWN_MAPPING }

    static final class Coverage {
        final Side side;
        final Kind kind;
        final String type;
        final String objectId;
        final String path;
        final String identity;
        final String objectBinding;
        Coverage(Side side, Kind kind, String type, String objectId, String path, String identity, String objectBinding)
                throws VaultException {
            if (side == null || kind == null || kind == Kind.TRUST) throw VaultException.invalid();
            this.side = side;
            this.kind = kind;
            this.type = text(type, 64);
            this.objectId = text(objectId, 128);
            this.path = text(path, 512);
            this.identity = text(identity, 512);
            this.objectBinding = digestText(objectBinding);
        }
        static Coverage file(Side side, VaultManifest.FileObject file) throws VaultException {
            return new Coverage(side, Kind.FILE, file.type.name(), file.id, file.path, file.identity,
                    SshBundleProposal.objectBinding(file));
        }
        static Coverage credential(Side side, VaultManifest.Entry entry) throws VaultException {
            if (entry.type == VaultManifest.EntryType.TOTP) throw VaultException.invalid();
            return new Coverage(side, Kind.CREDENTIAL, entry.type.name(), entry.id, "", entry.identity, entryBinding(entry));
        }
        private String key() { return side.name() + ':' + objectId; }
        private Map<String, Object> map() {
            Map<String, Object> result = new HashMap<>();
            result.put("side", lower(side)); result.put("kind", lower(kind)); result.put("type", type);
            result.put("objectId", objectId); result.put("path", path); result.put("identity", identity);
            result.put("objectBinding", objectBinding);
            return Collections.unmodifiableMap(result);
        }
    }

    static final class TrustChange {
        final TrustStatus status;
        final String sourceObjectId;
        final String targetObjectId;
        final String host;
        final int port;
        final String keyType;
        final String sourceFingerprint;
        final String targetFingerprint;
        final String marker;
        TrustChange(TrustStatus status, String sourceObjectId, String targetObjectId, String host, int port,
                String keyType, String sourceFingerprint, String targetFingerprint, String marker) throws VaultException {
            if (status == null || port < 0 || port > 65535 || port == 0 && status != TrustStatus.UNKNOWN_MAPPING) throw VaultException.invalid();
            this.status = status;
            this.sourceObjectId = text(sourceObjectId, 128);
            this.targetObjectId = text(targetObjectId, 128);
            this.host = text(host, 512);
            this.port = port;
            this.keyType = text(keyType, 64);
            this.sourceFingerprint = fingerprint(sourceFingerprint);
            this.targetFingerprint = fingerprint(targetFingerprint);
            this.marker = text(marker, 32);
            if (this.host.isEmpty() || this.keyType.isEmpty()
                    || !VaultCollections.set("", "@revoked", "@cert-authority").contains(marker)
                    || status == TrustStatus.IMPORTED && (sourceObjectId.isEmpty() || !targetObjectId.isEmpty())
                    || status == TrustStatus.TARGET_REMOVED && (!sourceObjectId.isEmpty() || targetObjectId.isEmpty())
                    || (status == TrustStatus.SAME || status == TrustStatus.CHANGED) && (sourceObjectId.isEmpty() || targetObjectId.isEmpty())
                    || sourceObjectId.isEmpty() && targetObjectId.isEmpty()
                    || !sourceObjectId.isEmpty() && sourceFingerprint.isEmpty()
                    || !targetObjectId.isEmpty() && targetFingerprint.isEmpty()) throw VaultException.invalid();
        }
        private Map<String, Object> map() {
            Map<String, Object> result = new HashMap<>();
            result.put("status", lower(status)); result.put("sourceObjectId", sourceObjectId); result.put("targetObjectId", targetObjectId);
            result.put("host", host); result.put("port", port); result.put("keyType", keyType);
            result.put("sourceFingerprint", sourceFingerprint); result.put("targetFingerprint", targetFingerprint); result.put("marker", marker);
            return Collections.unmodifiableMap(result);
        }
    }

    static final class Conflict {
        final Kind kind;
        final ConflictStatus status;
        final String sourceObjectId;
        final String targetObjectId;
        Conflict(Kind kind, ConflictStatus status, String sourceObjectId, String targetObjectId) throws VaultException {
            if (kind == null || status == null) throw VaultException.invalid();
            this.kind = kind;
            this.status = status;
            this.sourceObjectId = text(sourceObjectId, 128);
            this.targetObjectId = text(targetObjectId, 128);
            if (sourceObjectId.isEmpty() && targetObjectId.isEmpty()
                    || status == ConflictStatus.TARGET_REMOVED && (!sourceObjectId.isEmpty() || targetObjectId.isEmpty())) throw VaultException.invalid();
        }
        private Map<String, Object> map() {
            Map<String, Object> result = new HashMap<>();
            result.put("kind", lower(kind)); result.put("status", lower(status));
            result.put("sourceObjectId", sourceObjectId); result.put("targetObjectId", targetObjectId);
            return Collections.unmodifiableMap(result);
        }
    }

    private final List<Coverage> coverage;
    private final List<TrustChange> trustChanges;
    private final List<Conflict> conflicts;
    final String providerId;
    final String providerVersion;
    final long providerRevision;
    final String sourceCipherDigest;
    final String targetCipherDigest;
    final String binding;
    private boolean closed;

    SshRestoreReview(List<Coverage> coverage, List<TrustChange> trustChanges, List<Conflict> conflicts) throws VaultException {
        this(coverage, trustChanges, conflicts, "", "", 0, "", "");
    }

    private SshRestoreReview(List<Coverage> coverage, List<TrustChange> trustChanges, List<Conflict> conflicts,
            String providerId, String providerVersion, long providerRevision, String sourceCipherDigest, String targetCipherDigest)
            throws VaultException {
        if (coverage == null || trustChanges == null || conflicts == null || coverage.size() > MAX_COVERAGE
                || trustChanges.size() > MAX_ROWS || conflicts.size() > MAX_ROWS || trustChanges.size() + conflicts.size() > MAX_ROWS) throw VaultException.invalid();
        for (Coverage item : coverage) if (item == null) throw VaultException.invalid();
        for (TrustChange item : trustChanges) if (item == null) throw VaultException.invalid();
        for (Conflict item : conflicts) if (item == null) throw VaultException.invalid();
        this.coverage = VaultCollections.copyList(coverage);
        this.trustChanges = VaultCollections.copyList(trustChanges);
        this.conflicts = VaultCollections.copyList(conflicts);
        this.providerId = text(providerId, 128);
        this.providerVersion = text(providerVersion, 128);
        this.providerRevision = providerRevision;
        this.sourceCipherDigest = sourceCipherDigest;
        this.targetCipherDigest = targetCipherDigest;
        Map<String, Coverage> objects = new HashMap<>();
        for (Coverage object : this.coverage) if (objects.put(object.key(), object) != null) throw VaultException.invalid();
        for (TrustChange change : this.trustChanges) {
            requireReference(objects, Side.SOURCE, change.sourceObjectId, Kind.TRUST);
            requireReference(objects, Side.TARGET, change.targetObjectId, Kind.TRUST);
        }
        for (Conflict conflict : this.conflicts) {
            requireReference(objects, Side.SOURCE, conflict.sourceObjectId, conflict.kind);
            requireReference(objects, Side.TARGET, conflict.targetObjectId, conflict.kind);
        }
        binding = canonicalBinding();
    }

    synchronized SshRestoreReview bind(VaultManifest source, VaultManifest target, String providerId,
            String version, long revision, byte[] sourceDigest, byte[] targetDigest) throws VaultException {
        requireOpen();
        requireReviewedMetadata(source);
        requireReviewedMetadata(target);
        Map<String, Coverage> expected = new HashMap<>();
        expectedCoverage(expected, Side.SOURCE, source);
        if (target != null) expectedCoverage(expected, Side.TARGET, target);
        if (expected.size() != coverage.size()) throw VaultException.invalid();
        for (Coverage actual : coverage) {
            Coverage correct = expected.get(actual.key());
            if (correct == null || correct.kind != actual.kind || !correct.type.equals(actual.type)
                    || !correct.path.equals(actual.path) || !correct.identity.equals(actual.identity)
                    || !correct.objectBinding.equals(actual.objectBinding)) throw VaultException.invalid();
        }
        // Public password digests are guessing oracles. Validate raw native bindings first, then
        // bind the actual displayed projection with fresh, unlinkable credential tokens.
        byte[] tokenKey = VaultEnvelope.random(32);
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(tokenKey, "HmacSHA256"));
            List<Coverage> displayed = new ArrayList<>();
            for (Coverage object : coverage) {
                String binding = object.kind == Kind.CREDENTIAL
                        ? hex(mac.doFinal(object.objectBinding.getBytes(StandardCharsets.US_ASCII))) : object.objectBinding;
                displayed.add(new Coverage(object.side, object.kind, object.type, object.objectId, object.path, object.identity, binding));
            }
            return new SshRestoreReview(displayed, trustChanges, conflicts, providerId, version, revision,
                    hex(sourceDigest), targetDigest == null ? "" : hex(targetDigest));
        } catch (java.security.GeneralSecurityException error) { throw new AssertionError(error); }
        finally { VaultCodec.wipe(tokenKey); }
    }

    synchronized Map<String, Object> toMap() throws VaultException {
        requireOpen();
        Map<String, Object> result = new HashMap<>();
        result.put("providerId", providerId); result.put("providerVersion", providerVersion); result.put("providerRevision", providerRevision);
        result.put("sourceCipherDigest", sourceCipherDigest); result.put("targetCipherDigest", targetCipherDigest);
        List<Map<String, Object>> objects = new ArrayList<>(), trust = new ArrayList<>(), changes = new ArrayList<>();
        for (Coverage object : coverage) objects.add(object.map());
        for (TrustChange change : trustChanges) trust.add(change.map());
        for (Conflict conflict : conflicts) changes.add(conflict.map());
        result.put("coverage", VaultCollections.copyList(objects)); result.put("trustChanges", VaultCollections.copyList(trust));
        result.put("conflicts", VaultCollections.copyList(changes));
        return Collections.unmodifiableMap(result);
    }

    synchronized void requireOpen() throws VaultException {
        if (closed) throw new VaultException("VAULT_EXPIRED", "Restore review expired");
    }
    @Override public synchronized void close() { closed = true; }

    static void requireReviewedMetadata(VaultManifest manifest) throws VaultException {
        // The current projection has no key-binding/account rows to review.
        if (manifest != null && (!manifest.keyBindings.isEmpty() || !manifest.accounts.isEmpty()))
            throw new VaultException("VAULT_SSH_REVIEW_REQUIRED", "SSH restore review is not available yet");
    }

    private static void expectedCoverage(Map<String, Coverage> result, Side side, VaultManifest manifest) throws VaultException {
        for (VaultManifest.FileObject file : manifest.files) { Coverage object = Coverage.file(side, file); result.put(object.key(), object); }
        for (VaultManifest.Entry entry : manifest.entries) if (entry.type != VaultManifest.EntryType.TOTP) {
            Coverage object = Coverage.credential(side, entry); result.put(object.key(), object);
        }
    }

    private static void requireReference(Map<String, Coverage> objects, Side side, String id, Kind kind) throws VaultException {
        if (id.isEmpty()) return;
        Coverage object = objects.get(side.name() + ':' + id);
        if (object == null || (kind == Kind.TRUST ? object.kind != Kind.FILE || !object.type.equals("KNOWN_HOSTS") : object.kind != kind)) {
            throw VaultException.invalid();
        }
    }

    private String canonicalBinding() throws VaultException {
        try (VaultCodec.SecretBuffer buffer = new VaultCodec.SecretBuffer(); DataOutputStream out = new DataOutputStream(buffer)) {
            VaultCodec.writeText(out, "ctos-ssh-restore-v1");
            VaultCodec.writeText(out, providerId); VaultCodec.writeText(out, providerVersion); out.writeLong(providerRevision);
            VaultCodec.writeText(out, sourceCipherDigest); VaultCodec.writeText(out, targetCipherDigest);
            out.writeInt(coverage.size());
            for (Coverage object : coverage) {
                out.writeInt(object.side.ordinal()); out.writeInt(object.kind.ordinal()); VaultCodec.writeText(out, object.type);
                VaultCodec.writeText(out, object.objectId); VaultCodec.writeText(out, object.path); VaultCodec.writeText(out, object.identity);
                VaultCodec.writeText(out, object.objectBinding);
            }
            out.writeInt(trustChanges.size());
            for (TrustChange change : trustChanges) {
                out.writeInt(change.status.ordinal()); VaultCodec.writeText(out, change.sourceObjectId); VaultCodec.writeText(out, change.targetObjectId);
                VaultCodec.writeText(out, change.host); out.writeInt(change.port); VaultCodec.writeText(out, change.keyType);
                VaultCodec.writeText(out, change.sourceFingerprint); VaultCodec.writeText(out, change.targetFingerprint); VaultCodec.writeText(out, change.marker);
            }
            out.writeInt(conflicts.size());
            for (Conflict conflict : conflicts) {
                out.writeInt(conflict.kind.ordinal()); out.writeInt(conflict.status.ordinal());
                VaultCodec.writeText(out, conflict.sourceObjectId); VaultCodec.writeText(out, conflict.targetObjectId);
            }
            if (buffer.size() > MAX_BYTES) throw VaultException.invalid();
            byte[] encoded = buffer.toByteArray();
            try { return hex(VaultCodec.digest(encoded)); } finally { VaultCodec.wipe(encoded); }
        } catch (IOException error) { throw new AssertionError(error); }
    }

    private static String entryBinding(VaultManifest.Entry entry) {
        try (VaultCodec.SecretBuffer buffer = new VaultCodec.SecretBuffer(); DataOutputStream out = new DataOutputStream(buffer)) {
            out.writeInt(entry.type.ordinal()); VaultCodec.writeText(out, entry.id); VaultCodec.writeText(out, entry.identity);
            VaultCodec.writeText(out, entry.label); VaultCodec.writeText(out, entry.algorithm); out.writeInt(entry.digits); out.writeInt(entry.period);
            VaultCodec.writeBytes(out, VaultCodec.digest(entry.secret));
            byte[] encoded = buffer.toByteArray();
            try { return hex(VaultCodec.digest(encoded)); } finally { VaultCodec.wipe(encoded); }
        } catch (IOException error) { throw new AssertionError(error); }
    }
    private static String text(String value, int maximum) throws VaultException {
        if (value == null || value.getBytes(StandardCharsets.UTF_8).length > maximum
                || value.chars().anyMatch(c -> c < 32 || c == 127)) throw VaultException.invalid();
        return value;
    }
    private static String digestText(String value) throws VaultException {
        if (value == null || !value.matches("[0-9a-f]{64}")) throw VaultException.invalid();
        return value;
    }
    private static String fingerprint(String value) throws VaultException {
        if (value == null || !value.isEmpty() && !value.matches("SHA256:[A-Za-z0-9+/]{43}")) throw VaultException.invalid();
        return value;
    }
    static String hex(byte[] bytes) {
        StringBuilder result = new StringBuilder();
        for (byte value : bytes) result.append(String.format(Locale.ROOT, "%02x", value));
        return result.toString();
    }
    private static String lower(Enum<?> value) { return value.name().toLowerCase(Locale.ROOT); }
}
