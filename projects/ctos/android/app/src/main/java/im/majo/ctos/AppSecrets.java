package im.majo.ctos;

import java.io.IOException;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.nio.ByteBuffer;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.util.UUID;

/** One vault owner. Business adapters receive purpose-limited operations, never a channel key API. */
final class AppSecrets {
    interface Store {
        byte[] read() throws IOException;
        void commit(byte[] envelope, byte[] quickMaterial, EffectGuard guard) throws IOException, VaultException;
        byte[] quickMaterial() throws IOException;
    }
    interface EffectGuard {
        void check() throws VaultException;
        default void beforeCommit() throws VaultException { check(); }
    }
    static EffectGuard boundGuard(EffectGuard parent, EffectGuard condition) {
        return new EffectGuard() {
            @Override public void check() throws VaultException { parent.check(); condition.check(); }
            @Override public void beforeCommit() throws VaultException {
                check();
                parent.beforeCommit();
                condition.check();
            }
        };
    }
    interface SshContentValidator { void validate(VaultManifest manifest) throws VaultException; }
    interface SshRestoreReviewer {
        SshRestoreReview review(VaultManifest source, VaultManifest authenticatedTarget) throws VaultException;
    }
    interface ExactRestoreApproval {
        // Must be a bounded metadata/receipt check, not a parser, disk operation or system UI call.
        void requireApproved(SshRestoreReview review, RestoreConsent consent) throws VaultException;
    }
    private static final class RestoreProvider {
        final String id;
        final String version;
        final long revision;
        final SshRestoreReviewer reviewer;
        final ExactRestoreApproval approval;
        RestoreProvider(String id, String version, long revision, SshRestoreReviewer reviewer, ExactRestoreApproval approval) {
            this.id = id; this.version = version; this.revision = revision; this.reviewer = reviewer; this.approval = approval;
        }
    }
    static final class RestoreConsent {
        final String previewId;
        final String sourceCipherDigest;
        final String targetCipherDigest;
        final String reviewBinding;
        final String providerId;
        final String providerVersion;
        final long providerRevision;
        final long epoch;
        final long deadline;
        private RestoreConsent(ImportPreview preview) {
            previewId = preview.id;
            sourceCipherDigest = SshRestoreReview.hex(preview.sourceBinding);
            targetCipherDigest = preview.targetBinding == null ? "" : SshRestoreReview.hex(preview.targetBinding);
            reviewBinding = preview.reviewBinding;
            providerId = preview.provider.id; providerVersion = preview.provider.version; providerRevision = preview.provider.revision;
            epoch = preview.epoch; deadline = preview.deadline;
        }
    }

    static final class Verified implements AutoCloseable {
        final VaultEnvelope envelope;
        final byte[] key;
        final byte[] plaintext;
        final VaultManifest manifest;

        Verified(VaultEnvelope envelope, byte[] key, byte[] plaintext, VaultManifest manifest) {
            this.envelope = envelope;
            this.key = key;
            this.plaintext = plaintext;
            this.manifest = manifest;
        }

        @Override public void close() {
            VaultCodec.wipe(key);
            VaultCodec.wipe(plaintext);
            manifest.close();
        }
    }

    static final class ImportPreview implements AutoCloseable {
        final String id = UUID.randomUUID().toString();
        final Verified source;
        final byte[] targetBinding;
        private final byte[] sourceBinding;
        private final byte[] sourcePlaintextBinding;
        private final RestoreProvider provider;
        private final SshRestoreReview review;
        final String reviewBinding;
        private RestoreConsent consent;
        final long epoch;
        final long deadline;
        final String requestId;

        ImportPreview(Verified source, byte[] targetBinding, long epoch, long deadline, String requestId,
                RestoreProvider provider, SshRestoreReview review) {
            this.source = source;
            sourceBinding = source.envelope.binding();
            sourcePlaintextBinding = VaultCodec.digest(source.plaintext);
            this.targetBinding = targetBinding == null ? null : targetBinding.clone();
            this.provider = provider;
            this.review = review;
            reviewBinding = review == null ? "" : review.binding;
            this.epoch = epoch;
            this.deadline = deadline;
            this.requestId = requestId;
        }

        Map<String, Object> reviewMap() throws VaultException { return review == null ? null : review.toMap(); }
        @Override public void close() { source.close(); if (review != null) review.close(); consent = null; }
    }

    private final Store store;
    final Object publicationLock = new Object();
    volatile long contentRevision;
    final VaultSession session;
    private final VaultSession.Clock clock;
    private ImportPreview preview;
    private SshContentValidator sshValidator;
    private RestoreProvider restoreProvider;
    private long restoreRevision;
    // Cancellation is intentionally allowed while a commit holds this object's
    // monitor and is blocked in Store.commit.  A concurrent map lets the
    // cancellation path revoke the operation without waiting for that commit.
    private final Map<String, VaultOperation> operations = new ConcurrentHashMap<>();

    AppSecrets(Store store, VaultSession.Clock clock) {
        this.store = store;
        this.clock = clock;
        session = new VaultSession(clock);
    }

    synchronized void attachSshValidator(SshContentValidator validator) { sshValidator = validator; }

    synchronized void attachSshRestoreReviewer(String id, String version, SshRestoreReviewer reviewer,
            ExactRestoreApproval approval) throws VaultException {
        if (id == null || version == null || !id.matches("[A-Za-z0-9_.-]{1,128}")
                || !version.matches("[A-Za-z0-9_.+-]{1,128}")) throw VaultException.invalid();
        restoreRevision++;
        restoreProvider = reviewer == null ? null : new RestoreProvider(id, version, restoreRevision, reviewer,
                approval == null ? (review, consent) -> {
                    throw new VaultException("VAULT_TRUST_REVIEW", "Restore approval is not available");
                } : approval);
        cancelPreview();
    }

    private static boolean hasSsh(VaultManifest manifest) {
        return !manifest.files.isEmpty() || manifest.entries.stream().anyMatch(e -> e.type != VaultManifest.EntryType.TOTP);
    }

    private void requireSshValidator() throws VaultException {
        if (sshValidator == null) throw new VaultException("VAULT_SSH_VALIDATION_REQUIRED", "SSH file validation is not available yet");
    }

    private synchronized void validateBusiness(VaultManifest manifest) throws VaultException {
        boolean ssh = hasSsh(manifest);
        if (ssh && sshValidator == null) {
            throw new VaultException("VAULT_SSH_VALIDATION_REQUIRED", "SSH backup validation is not available yet");
        }
        if (ssh) sshValidator.validate(manifest);
    }

    synchronized VaultEnvelope current() throws VaultException {
        try {
            byte[] bytes = store.read();
            return bytes == null ? null : VaultEnvelope.parse(bytes);
        } catch (IOException error) { throw new VaultException("VAULT_IO", "Unable to read the vault"); }
    }

    synchronized byte[] quickMaterial() throws VaultException {
        try { return store.quickMaterial(); }
        catch (IOException error) { throw new VaultException("VAULT_IO", "Unable to read shortcut material"); }
    }

    void create(char[] password, char[] confirmation, EffectGuard guard) throws VaultException {
        long epoch = session.epoch();
        if (!Arrays.equals(password, confirmation)) throw new VaultException("PARAMETERS", "Passwords do not match");
        byte[] salt = VaultEnvelope.random(16);
        byte[] key = VaultEnvelope.derive(password, salt);
        byte[] plaintext = VaultManifest.empty().encode();
        try {
            VaultEnvelope envelope = VaultEnvelope.encrypt(UUID.randomUUID(), 1, salt, key, plaintext);
            verifyReadback(envelope, key, plaintext);
            synchronized (this) {
                guard.check();
                foreground();
                session.assertEpoch(epoch);
                if (current() != null) throw new VaultException("VAULT_EXISTS", "A vault already exists");
                commit(envelope, null, boundGuard(guard, () -> session.assertEpoch(epoch)));
                session.unlockIfEpoch(epoch, key);
            }
        } finally { VaultCodec.wipe(key); VaultCodec.wipe(plaintext); }
    }

    void unlock(char[] password, EffectGuard guard) throws VaultException {
        VaultEnvelope target;
        long epoch = session.epoch();
        synchronized (this) { target = requireCurrent(); }
        try (Verified verified = verify(target.encode(), password)) {
            synchronized (this) {
                guard.check();
                foreground();
                session.assertEpoch(epoch);
                unchanged(target.binding());
                if (!session.unlockIfEpoch(epoch, verified.key)) throw new VaultException("VAULT_CHANGED", "The vault context changed; unlock again");
            }
        }
    }

    byte[] export(char[] password, EffectGuard guard) throws VaultException {
        VaultEnvelope target;
        long epoch = session.epoch();
        synchronized (this) { target = requireCurrent(); }
        try (Verified ignored = verify(target.encode(), password)) {
            synchronized (this) {
                guard.check();
                foreground();
                session.assertEpoch(epoch);
                unchanged(target.binding());
                return target.encode();
            }
        }
    }

    void changePassword(char[] password, char[] replacement, char[] confirmation,
            EffectGuard guard) throws VaultException {
        if (!Arrays.equals(replacement, confirmation)) throw new VaultException("PARAMETERS", "Passwords do not match");
        VaultEnvelope target;
        long epoch = session.epoch();
        synchronized (this) { target = requireCurrent(); }
        try (Verified verified = verify(target.encode(), password)) {
            byte[] salt = VaultEnvelope.random(16);
            byte[] key = VaultEnvelope.derive(replacement, salt);
            try {
                if (target.generation == Long.MAX_VALUE) throw VaultException.invalid();
                VaultEnvelope next = VaultEnvelope.encrypt(target.id, target.generation + 1, salt,
                        key, verified.plaintext);
                verifyReadback(next, key, verified.plaintext);
                synchronized (this) {
                    guard.check();
                    foreground();
                    session.assertEpoch(epoch);
                    unchanged(target.binding());
                    commit(next, null, boundGuard(guard, () -> session.assertEpoch(epoch)));
                    lock();
                }
            } finally { VaultCodec.wipe(key); }
        }
    }

    ImportPreview preview(byte[] source, char[] sourcePassword, char[] targetPassword,
            String requestId, EffectGuard guard) throws VaultException {
        VaultEnvelope target;
        long epoch = session.epoch();
        synchronized (this) { target = current(); }
        Verified verified = verify(source, sourcePassword);
        Verified verifiedTarget = null;
        SshRestoreReview review = null;
        boolean held = false;
        try {
            validateBusiness(verified.manifest);
            if (target != null) {
                if (targetPassword == null) throw new VaultException("PARAMETERS", "Enter the current vault password");
                verifiedTarget = verify(target.encode(), targetPassword);
                validateBusiness(verifiedTarget.manifest);
            }
            synchronized (this) {
                guard.check();
                foreground();
                session.assertEpoch(epoch);
                byte[] binding = target == null ? null : target.binding();
                unchanged(binding);
                RestoreProvider provider = null;
                if (hasSsh(verified.manifest) || verifiedTarget != null && hasSsh(verifiedTarget.manifest)) {
                    requireSshValidator();
                    provider = restoreProvider;
                    if (provider == null) throw new VaultException("VAULT_SSH_REVIEW_REQUIRED", "SSH restore review is not available yet");
                    // Reviewer receives only temporary loans; retained raw arrays are wiped immediately afterwards.
                    try (VaultManifest sourceView = VaultManifest.decode(verified.plaintext);
                            VaultManifest targetView = verifiedTarget == null ? null : VaultManifest.decode(verifiedTarget.plaintext);
                            SshRestoreReview supplied = provider.reviewer.review(sourceView, targetView)) {
                        if (supplied == null) throw VaultException.invalid();
                        try (VaultManifest canonicalSource = VaultManifest.decode(verified.plaintext);
                                VaultManifest canonicalTarget = verifiedTarget == null ? null : VaultManifest.decode(verifiedTarget.plaintext)) {
                            review = supplied.bind(canonicalSource, canonicalTarget,
                                    provider.id, provider.version, provider.revision, verified.envelope.binding(), binding);
                        }
                    }
                    guard.check(); foreground(); session.assertEpoch(epoch); unchanged(binding);
                    if (restoreProvider != provider) throw new VaultException("VAULT_CHANGED", "Restore reviewer changed; review again");
                }
                cancelPreview();
                preview = new ImportPreview(verified, binding, epoch, clock.now() + 120000, requestId, provider, review);
                held = true;
                return preview;
            }
        } finally {
            if (verifiedTarget != null) verifiedTarget.close();
            if (!held) { verified.close(); if (review != null) review.close(); }
        }
    }

    synchronized void importCommit(String previewId, boolean confirmTrust, EffectGuard guard)
            throws VaultException {
        importCommit(previewId, null, confirmTrust, guard);
    }

    synchronized void importCommit(String previewId, String acceptedReviewBinding, boolean confirmTrust, EffectGuard guard)
            throws VaultException {
        ImportPreview pending = preview;
        if (pending == null || !pending.id.equals(previewId)) throw new VaultException("VAULT_EXPIRED", "Preview expired");
        try {
            guard.check();
            foreground();
            session.assertEpoch(pending.epoch);
            unchanged(pending.targetBinding);
            if (clock.now() >= pending.deadline) throw new VaultException("VAULT_EXPIRED", "Preview expired");
            checkImportSource(pending);
            if (pending.review != null) {
                if (!confirmTrust || !pending.reviewBinding.equals(acceptedReviewBinding)) {
                    throw new VaultException("VAULT_TRUST_REVIEW", "Review and approve this exact restore projection");
                }
                pending.consent = new RestoreConsent(pending);
                checkRestoreApproval(pending);
            } else if (acceptedReviewBinding != null && !acceptedReviewBinding.isEmpty()) {
                throw new VaultException("VAULT_TRUST_REVIEW", "Restore review binding does not match");
            }
            // A fresh local ID makes source/target generation collisions impossible; salt/key remain portable.
            VaultEnvelope next = VaultEnvelope.encrypt(UUID.randomUUID(), 1, pending.source.envelope.salt,
                    pending.source.key, pending.source.plaintext);
            verifyReadback(next, pending.source.key, pending.source.plaintext);
            guard.check();
            commit(next, null, boundGuard(guard, () -> {
                session.assertEpoch(pending.epoch);
                foreground();
                if (preview != pending || clock.now() >= pending.deadline) throw new VaultException("VAULT_EXPIRED", "Preview expired");
                checkImportSource(pending);
                if (pending.review != null) checkRestoreApproval(pending);
            }));
            lock();
        } finally { cancelPreview(); }
    }

    private void checkRestoreApproval(ImportPreview pending) throws VaultException {
        requireSshValidator();
        pending.review.requireOpen();
        if (pending.provider != restoreProvider || pending.consent == null
                || !pending.review.binding.equals(pending.consent.reviewBinding)) {
            throw new VaultException("VAULT_CHANGED", "Restore reviewer or consent changed; review again");
        }
        pending.provider.approval.requireApproved(pending.review, pending.consent);
        pending.review.requireOpen();
        session.assertEpoch(pending.epoch);
        foreground();
        if (preview != pending || clock.now() >= pending.deadline) throw new VaultException("VAULT_EXPIRED", "Preview expired");
        if (pending.provider != restoreProvider || pending.consent == null) {
            throw new VaultException("VAULT_CHANGED", "Restore reviewer or consent changed; review again");
        }
    }

    private void checkImportSource(ImportPreview pending) throws VaultException {
        if (!MessageDigest.isEqual(pending.sourceBinding, pending.source.envelope.binding())
                || !MessageDigest.isEqual(pending.sourcePlaintextBinding, VaultCodec.digest(pending.source.plaintext))) {
            throw new VaultException("VAULT_CHANGED", "The authenticated source changed; review again");
        }
    }

    void disableQuick(char[] password, EffectGuard guard) throws VaultException {
        VaultEnvelope target;
        byte[] quick;
        long epoch = session.epoch();
        synchronized (this) { target = requireCurrent(); quick = quickMaterial(); }
        try (Verified ignored = verify(target.encode(), password)) {
            synchronized (this) {
                guard.check();
                foreground();
                session.assertEpoch(epoch);
                unchanged(target.binding());
                if (!Arrays.equals(quick, quickMaterial())) throw new VaultException("VAULT_CHANGED", "Shortcut material changed");
                // Disabled state is committed with the complete vault before Keystore cleanup.
                commit(target, null, boundGuard(guard, () -> session.assertEpoch(epoch)));
                lock();
            }
        }
    }

    synchronized void setQuick(VaultEnvelope target, byte[] wrapper, long epoch, EffectGuard guard)
            throws VaultException {
        guard.check();
        foreground();
        session.assertEpoch(epoch);
        unchanged(target.binding());
        commit(target, wrapper, boundGuard(guard, () -> session.assertEpoch(epoch)));
    }

    synchronized void quickUnlock(VaultEnvelope target, byte[] key, long epoch, EffectGuard guard)
            throws VaultException {
        byte[] plaintext = target.decrypt(key);
        try (VaultManifest ignored = VaultManifest.decode(plaintext)) {
            guard.check();
            foreground();
            session.assertEpoch(epoch);
            unchanged(target.binding());
            if (!session.unlockIfEpoch(epoch, key)) throw new VaultException("VAULT_CHANGED", "The vault context changed; unlock again");
        } finally { VaultCodec.wipe(plaintext); }
    }

    synchronized void lock() { session.lock(); cancelPreview(); operations.clear(); }
    synchronized void cleanupInvalidatedContexts() {
        long currentEpoch = session.epoch();
        operations.values().removeIf(operation -> operation.epoch != currentEpoch);
        if (preview != null && (preview.epoch != currentEpoch || clock.now() >= preview.deadline)) cancelPreview();
    }

    synchronized VaultOperation beginTotp(String entryId) throws VaultException {
        try (Verified verified = sessionManifest()) {
            findEntry(verified.manifest, entryId, VaultManifest.EntryType.TOTP);
            return operation(VaultOperation.Consumer.TOTP, VaultOperation.Purpose.TOTP_CODE, entryId, "", "", verified.envelope);
        }
    }

    synchronized VaultOperation beginTotpList() throws VaultException {
        return beginMutation(VaultOperation.Consumer.TOTP, VaultOperation.Purpose.TOTP_LIST, "totp-list", "", "");
    }
    synchronized Map<String, Object> totpList(VaultOperation operation, long unixSeconds) throws VaultException {
        verifyOperation(operation, VaultOperation.Consumer.TOTP, VaultOperation.Purpose.TOTP_LIST, "totp-list", "", "");
        List<Map<String, Object>> entries = new ArrayList<>();
        try (Verified verified = sessionManifest()) {
            for (VaultManifest.Entry entry : verified.manifest.entries) {
                if (entry.type == VaultManifest.EntryType.TOTP) entries.add(VaultCollections.map(
                        "entryId", entry.id, "identity", entry.identity, "label", entry.label,
                        "code", generateTotp(entry.secret, unixSeconds)));
            }
            protectedEffect(operation);
            return VaultCollections.map("entries", entries, "unixSeconds", unixSeconds);
        }
    }
    synchronized VaultOperation beginTotpRename(String id) throws VaultException {
        try (Verified verified = sessionManifest()) { findEntry(verified.manifest, id, VaultManifest.EntryType.TOTP); }
        return beginMutation(VaultOperation.Consumer.TOTP, VaultOperation.Purpose.TOTP_RENAME, id, "", "");
    }
    synchronized void renameTotp(VaultOperation operation, String id, String label, EffectGuard guard) throws VaultException {
        TotpInput.metadata(label, false);
        verifyOperation(operation, VaultOperation.Consumer.TOTP, VaultOperation.Purpose.TOTP_RENAME, id, "", "");
        try (Verified verified = sessionManifest()) {
            VaultManifest.Entry old = findEntry(verified.manifest, id, VaultManifest.EntryType.TOTP);
            VaultManifest.Entry replacement = new VaultManifest.Entry(old.type, old.id, old.identity, label,
                    old.algorithm, old.digits, old.period, old.secret);
            List<VaultManifest.Entry> entries = new ArrayList<>();
            for (VaultManifest.Entry entry : verified.manifest.entries) entries.add(entry == old ? replacement : entry);
            try (VaultManifest next = new VaultManifest(entries, verified.manifest.files)) { commitManifest(operation, verified, next, guard); }
            finally { VaultCodec.wipe(replacement.secret); }
        }
    }
    synchronized void checkTotpPending(VaultOperation operation) throws VaultException {
        if (operation == null || operations.get(operation.requestId) != operation || operation.consumed
                || operation.purpose != VaultOperation.Purpose.TOTP_SAVE || operation.consumer != VaultOperation.Consumer.TOTP) throw denied();
        protectedEffect(operation);
    }
    synchronized long totpPublication(VaultOperation operation) throws VaultException {
        protectedEffect(operation); return contentRevision;
    }
    void publishTotp(long epoch, long revision, long deadline, EffectGuard owner, EffectGuard publish) throws VaultException {
        synchronized (publicationLock) { synchronized (session) {
            owner.check(); session.assertEpoch(epoch);
            if (!session.foreground() || !session.unlocked() || contentRevision != revision || clock.now() >= deadline)
                throw new VaultException("VAULT_CHANGED", "The TOTP context changed or expired");
            publish.check();
        } }
    }
    synchronized void addTotp(VaultOperation operation, String id, String identity, String label, byte[] seed,
            EffectGuard guard) throws VaultException {
        try (Verified verified = sessionManifest()) {
            if (verified.manifest.entries.stream().anyMatch(entry -> entry.id.equals(id))) throw denied();
        }
        saveTotp(operation, id, identity, label, seed, guard);
    }

    synchronized VaultOperation beginTotpSave(String id) throws VaultException {
        return beginMutation(VaultOperation.Consumer.TOTP, VaultOperation.Purpose.TOTP_SAVE, id, "", "");
    }
    synchronized VaultOperation beginTotpDelete(String id) throws VaultException {
        try (Verified verified = sessionManifest()) { findEntry(verified.manifest, id, VaultManifest.EntryType.TOTP); }
        return beginMutation(VaultOperation.Consumer.TOTP, VaultOperation.Purpose.TOTP_DELETE, id, "", "");
    }
    synchronized void saveTotp(VaultOperation operation, String id, String identity, String label, byte[] seed,
            EffectGuard guard) throws VaultException {
        if (seed.length < 10 || seed.length > 128) throw VaultException.invalid();
        VaultManifest.Entry entry = new VaultManifest.Entry(VaultManifest.EntryType.TOTP, id, identity, label, "SHA1", 6, 30, seed);
        try { mutateEntry(operation, VaultOperation.Consumer.TOTP, VaultOperation.Purpose.TOTP_SAVE,
                id, "", "", entry, guard); }
        finally { VaultCodec.wipe(entry.secret); }
    }
    synchronized void deleteTotp(VaultOperation operation, String id, EffectGuard guard) throws VaultException {
        mutateEntry(operation, VaultOperation.Consumer.TOTP, VaultOperation.Purpose.TOTP_DELETE, id, "", "", null, guard);
    }
    synchronized VaultOperation beginSshSave(String id, String target, String connectionId) throws VaultException {
        if (connectionId.isEmpty()) throw denied();
        return beginMutation(VaultOperation.Consumer.SSH, VaultOperation.Purpose.SSH_SAVE, id, target, connectionId);
    }
    synchronized void saveSsh(VaultOperation operation, String id, String target, String connectionId,
            String label, boolean keyPassphrase, byte[] secret, EffectGuard guard) throws VaultException {
        if (secret.length == 0 || secret.length > 8192) throw VaultException.invalid();
        VaultManifest.Entry entry = new VaultManifest.Entry(keyPassphrase ? VaultManifest.EntryType.SSH_KEY_PASSPHRASE
                : VaultManifest.EntryType.SSH_PASSWORD, id, target, label, "", 0, 0, secret);
        try { mutateEntry(operation, VaultOperation.Consumer.SSH, VaultOperation.Purpose.SSH_SAVE,
                id, target, connectionId, entry, guard); }
        finally { VaultCodec.wipe(entry.secret); }
    }
    synchronized VaultOperation beginSshDelete(String id, String target) throws VaultException {
        try (Verified verified = sessionManifest()) {
            if (!findSshEntry(verified.manifest, id).identity.equals(target)) throw denied();
        }
        return beginMutation(VaultOperation.Consumer.SSH, VaultOperation.Purpose.SSH_DELETE, id, target, "");
    }
    synchronized void deleteSsh(VaultOperation operation, String id, String target, EffectGuard guard) throws VaultException {
        mutateEntry(operation, VaultOperation.Consumer.SSH, VaultOperation.Purpose.SSH_DELETE, id, target, "", null, guard);
    }

    private VaultOperation beginMutation(VaultOperation.Consumer consumer, VaultOperation.Purpose purpose,
            String resource, String target, String connectionId) throws VaultException {
        if (resource.isEmpty()) throw denied();
        try (Verified verified = sessionManifest()) { return operation(consumer, purpose, resource, target, connectionId, verified.envelope); }
    }

    private void mutateEntry(VaultOperation operation, VaultOperation.Consumer consumer, VaultOperation.Purpose purpose,
            String id, String target, String connectionId, VaultManifest.Entry replacement, EffectGuard guard) throws VaultException {
        verifyOperation(operation, consumer, purpose, id, target, connectionId);
        try (Verified verified = sessionManifest()) {
            List<VaultManifest.Entry> entries = new ArrayList<>();
            for (VaultManifest.Entry old : verified.manifest.entries) {
                if (old.id.equals(id)) {
                    if (consumer == VaultOperation.Consumer.TOTP ? old.type != VaultManifest.EntryType.TOTP
                            : old.type == VaultManifest.EntryType.TOTP || !old.identity.equals(target)) throw denied();
                } else entries.add(old);
            }
            if (replacement != null) entries.add(replacement);
            try (VaultManifest next = new VaultManifest(entries, verified.manifest.files)) {
                commitManifest(operation, verified, next, guard);
            }
        }
    }

    synchronized VaultOperation beginTrustUpdate(SshTrustProposal proposal) throws VaultException {
        try (Verified verified = sessionManifest()) {
            VaultManifest.FileObject file = findFile(verified.manifest, proposal.path);
            if (file.type != VaultManifest.FileType.KNOWN_HOSTS || !file.id.equals(proposal.objectId)
                    || proposal.objectType != null && file.type != proposal.objectType
                    || proposal.objectIdentity != null && !file.identity.equals(proposal.objectIdentity)) {
                throw denied();
            }
            return operation(VaultOperation.Consumer.SSH, VaultOperation.Purpose.SSH_TRUST_UPDATE,
                    proposal.binding, proposal.targetIdentity, proposal.connectionId, verified.envelope);
        }
    }

    synchronized void updateTrust(VaultOperation operation, SshTrustProposal proposal, EffectGuard guard) throws VaultException {
        verifyOperation(operation, VaultOperation.Consumer.SSH, VaultOperation.Purpose.SSH_TRUST_UPDATE,
                proposal.binding, proposal.targetIdentity, proposal.connectionId);
        byte[] content = proposal.newContent();
        byte[] baseDigest = proposal.baseDigest();
        if (content.length > VaultManifest.MAX_FILE_BYTES) throw VaultException.invalid();
        try (Verified verified = sessionManifest()) {
            VaultManifest.FileObject old = findFile(verified.manifest, proposal.path);
            byte[] currentDigest = VaultCodec.digest(old.content);
            try {
                if (old.type != VaultManifest.FileType.KNOWN_HOSTS || !old.id.equals(proposal.objectId)
                        || proposal.objectType != null && old.type != proposal.objectType
                        || proposal.objectIdentity != null && !old.identity.equals(proposal.objectIdentity)
                        || !MessageDigest.isEqual(baseDigest, currentDigest)) {
                    throw new VaultException("VAULT_CHANGED", "Trust content changed; review again");
                }
            } finally { VaultCodec.wipe(currentDigest); }
            VaultManifest.FileObject replacement = new VaultManifest.FileObject(old.type, old.id, old.path, old.identity, content, old.references);
            try {
                List<VaultManifest.FileObject> files = new ArrayList<>();
                for (VaultManifest.FileObject file : verified.manifest.files) files.add(file.path.equals(proposal.path) ? replacement : file);
                try (VaultManifest next = new VaultManifest(verified.manifest.entries, files)) {
                    commitManifest(operation, verified, next, guard);
                }
            } finally { VaultCodec.wipe(replacement.content); }
        } finally { VaultCodec.wipe(content); }
    }

    synchronized VaultOperation beginSshBundle(SshBundleProposal proposal) throws VaultException {
        proposal.requireOpen();
        requireSshValidator();
        try (Verified verified = sessionManifest(); VaultManifest next = proposal.applyTo(verified.manifest)) {
            // Deliberately validate even an empty final SSH namespace after deleting its last file.
            sshValidator.validate(next);
            return operation(VaultOperation.Consumer.SSH, VaultOperation.Purpose.SSH_BUNDLE_WRITE,
                    proposal.binding, "", "", verified.envelope);
        }
    }

    synchronized void commitSshBundle(VaultOperation operation, SshBundleProposal proposal, EffectGuard guard)
            throws VaultException {
        proposal.requireOpen();
        requireSshValidator();
        verifyOperation(operation, VaultOperation.Consumer.SSH, VaultOperation.Purpose.SSH_BUNDLE_WRITE,
                proposal.binding, "", "");
        try (Verified verified = sessionManifest(); VaultManifest next = proposal.applyTo(verified.manifest)) {
            sshValidator.validate(next);
            commitManifest(operation, verified, next, boundGuard(guard, () -> {
                proposal.requireOpen();
                requireSshValidator();
                if (clock.now() >= operation.deadline) throw new VaultException("VAULT_EXPIRED", "Secret operation expired");
            }));
        }
    }

    private void commitManifest(VaultOperation operation, Verified verified, VaultManifest next,
            EffectGuard guard) throws VaultException {
        validateBusiness(next);
        byte[] plain = next.encode();
        try {
            VaultEnvelope envelope = VaultEnvelope.encrypt(verified.envelope.id, verified.envelope.generation,
                    verified.envelope.salt, verified.key, plain);
            verifyReadback(envelope, verified.key, plain);
            guard.check();
            protectedEffect(operation);
            commit(envelope, quickMaterial(), boundGuard(guard, () -> {
                if (operation.cancelled) throw new VaultException("VAULT_CANCELLED", "Secret request was cancelled");
                session.assertEpoch(operation.epoch);
                foreground();
                if (clock.now() >= operation.deadline) throw new VaultException("VAULT_EXPIRED", "Secret operation expired");
            }));
            operations.clear();
            cancelPreview();
            session.unlockIfEpoch(operation.epoch, verified.key);
        } finally { VaultCodec.wipe(plain); }
    }

    synchronized String totpCode(VaultOperation operation, String entryId, long unixSeconds) throws VaultException {
        verifyOperation(operation, VaultOperation.Consumer.TOTP, VaultOperation.Purpose.TOTP_CODE, entryId, "", "");
        if (unixSeconds < 0) throw new VaultException("PARAMETERS", "Invalid code time");
        try (Verified verified = sessionManifest()) {
            VaultManifest.Entry entry = findEntry(verified.manifest, entryId, VaultManifest.EntryType.TOTP);
            String code = generateTotp(entry.secret, unixSeconds);
            protectedEffect(operation);
            return code;
        }
    }

    static String generateTotp(byte[] seed, long unixSeconds) throws VaultException {
        if (unixSeconds < 0) throw new VaultException("PARAMETERS", "Invalid code time");
        try {
                Mac mac = Mac.getInstance("HmacSHA1");
                mac.init(new SecretKeySpec(seed, "HmacSHA1"));
                byte[] digest = mac.doFinal(ByteBuffer.allocate(8).putLong(unixSeconds / 30).array());
                try {
                    int offset = digest[digest.length - 1] & 15;
                    int binary = (digest[offset] & 127) << 24 | (digest[offset + 1] & 255) << 16
                            | (digest[offset + 2] & 255) << 8 | digest[offset + 3] & 255;
                    String code = String.format(java.util.Locale.ROOT, "%06d", binary % 1000000);
                    return code;
                } finally { VaultCodec.wipe(digest); }
            } catch (java.security.GeneralSecurityException error) {
                throw new VaultException("VAULT_FAILED", "Unable to generate a code");
            }
    }

    synchronized VaultOperation beginSshCredential(String entryId, String target, String connectionId) throws VaultException {
        return beginSshCredential(entryId, target, connectionId, null);
    }

    synchronized VaultOperation beginSshCredential(String entryId, String target, String connectionId,
            VaultManifest.EntryType expectedType) throws VaultException {
        try (Verified verified = sessionManifest()) {
            VaultManifest.Entry entry = findSshEntry(verified.manifest, entryId);
            if (!entry.identity.equals(target) || connectionId.isEmpty()
                    || expectedType != null && entry.type != expectedType) throw denied();
            return operation(VaultOperation.Consumer.SSH, VaultOperation.Purpose.SSH_CREDENTIAL,
                    entryId, target, connectionId, verified.envelope);
        }
    }

    synchronized void sshCredential(VaultOperation operation, String entryId, String target,
            String connectionId, EffectGuard hostKeyGuard, SshVaultAdapter.CredentialReceiver receiver) throws VaultException {
        sshCredential(operation, entryId, target, connectionId, null, hostKeyGuard, receiver);
    }

    synchronized void sshCredential(VaultOperation operation, String entryId, String target,
            String connectionId, VaultManifest.EntryType expectedType, EffectGuard hostKeyGuard,
            SshVaultAdapter.CredentialReceiver receiver) throws VaultException {
        verifyOperation(operation, VaultOperation.Consumer.SSH, VaultOperation.Purpose.SSH_CREDENTIAL,
                entryId, target, connectionId);
        try (Verified verified = sessionManifest()) {
            VaultManifest.Entry entry = findSshEntry(verified.manifest, entryId);
            if (!entry.identity.equals(target) || expectedType != null && entry.type != expectedType) throw denied();
            byte[] credential = entry.secret.clone();
            try { hostKeyGuard.check(); protectedEffect(operation); receiver.receive(credential); }
            finally { VaultCodec.wipe(credential); }
        }
    }

    synchronized VaultOperation beginSshFiles(String rootPath, String connectionId) throws VaultException {
        VaultManifest.path(rootPath);
        if (connectionId.isEmpty()) throw denied();
        try (Verified verified = sessionManifest()) {
            if (findFile(verified.manifest, rootPath).type == VaultManifest.FileType.PRIVATE_KEY) throw denied();
            return operation(VaultOperation.Consumer.SSH, VaultOperation.Purpose.SSH_FILES,
                    rootPath, "", connectionId, verified.envelope);
        }
    }

    synchronized void sshFiles(VaultOperation operation, String rootPath, String connectionId,
            SshVaultAdapter.FileReceiver receiver) throws VaultException {
        sshFileSnapshot(operation, rootPath, connectionId, snapshot -> receiver.receive(snapshot.publicFiles));
    }

    synchronized void sshFileSnapshot(VaultOperation operation, String rootPath, String connectionId,
            SshVaultAdapter.FileSnapshotReceiver receiver) throws VaultException {
        verifyOperation(operation, VaultOperation.Consumer.SSH, VaultOperation.Purpose.SSH_FILES,
                rootPath, "", connectionId);
        try (Verified verified = sessionManifest()) {
            List<VaultManifest.FileObject> selected = new ArrayList<>();
            selectFiles(verified.manifest, rootPath, new HashSet<>(), selected);
            SshFileSnapshot snapshot = new SshFileSnapshot(operation, selected);
            // Copy borrowed public bodies to a request-owned native view before returning;
            // private bodies require the separate, host-verified key operation.
            protectedEffect(operation);
            receiver.receive(snapshot);
        }
    }

    synchronized SshPublicFileLease sshPublicFiles(VaultOperation operation, String rootPath, String connectionId)
            throws VaultException {
        SshPublicFileLease[] result = new SshPublicFileLease[1];
        try {
            sshFileSnapshot(operation, rootPath, connectionId, snapshot -> {
                result[0] = SshPublicFileLease.create(snapshot);
                operation.attachRevocation(result[0]);
                session.attachRevocation(operation.epoch, result[0]);
                protectedEffect(operation);
            });
            return result[0];
        } catch (VaultException | RuntimeException | Error failure) {
            if (result[0] != null) {
                try { result[0].close(); }
                catch (VaultException cleanup) { failure.addSuppressed(cleanup); }
            }
            throw failure;
        }
    }

    private static void selectFiles(VaultManifest manifest, String path, Set<String> visited,
            List<VaultManifest.FileObject> selected) throws VaultException {
        if (!visited.add(path)) return;
        VaultManifest.FileObject file = findFile(manifest, path);
        selected.add(file);
        for (String ref : file.references) selectFiles(manifest, ref, visited, selected);
    }

    synchronized VaultOperation beginSshPrivateKey(String path, String keyIdentity, String target,
            String connectionId) throws VaultException {
        if (target.isEmpty() || connectionId.isEmpty()) throw denied();
        try (Verified verified = sessionManifest()) {
            VaultManifest.FileObject file = findFile(verified.manifest, path);
            if (file.type != VaultManifest.FileType.PRIVATE_KEY || !file.identity.equals(keyIdentity)) throw denied();
            return operation(VaultOperation.Consumer.SSH, VaultOperation.Purpose.SSH_PRIVATE_KEY,
                    path + "\0" + keyIdentity, target, connectionId, verified.envelope);
        }
    }

    synchronized void sshPrivateKey(VaultOperation operation, String path, String keyIdentity, String target,
            String connectionId, EffectGuard hostKeyGuard, SshVaultAdapter.CredentialReceiver receiver) throws VaultException {
        verifyOperation(operation, VaultOperation.Consumer.SSH, VaultOperation.Purpose.SSH_PRIVATE_KEY,
                path + "\0" + keyIdentity, target, connectionId);
        try (Verified verified = sessionManifest()) {
            VaultManifest.FileObject file = findFile(verified.manifest, path);
            if (file.type != VaultManifest.FileType.PRIVATE_KEY || !file.identity.equals(keyIdentity)) throw denied();
            byte[] key = file.content.clone();
            try { hostKeyGuard.check(); protectedEffect(operation); receiver.receive(key); }
            finally { VaultCodec.wipe(key); }
        }
    }

    private VaultOperation operation(VaultOperation.Consumer consumer, VaultOperation.Purpose purpose,
            String resource, String target, String connectionId, VaultEnvelope envelope) throws VaultException {
        operations.values().removeIf(o -> o.consumed || o.cancelled || o.epoch != session.epoch() || clock.now() >= o.deadline);
        if (operations.size() >= 64) throw new VaultException("BUSY", "Too many secret operations");
        VaultOperation operation = new VaultOperation(consumer, purpose, resource, target, connectionId,
                envelope, session.epoch(), clock.now() + 120000);
        operations.put(operation.requestId, operation);
        return operation;
    }

    private void verifyOperation(VaultOperation operation, VaultOperation.Consumer consumer,
            VaultOperation.Purpose purpose, String resource, String target, String connectionId) throws VaultException {
        if (operation != null && operation.cancelled) throw new VaultException("VAULT_CANCELLED", "Secret request was cancelled");
        if (operation == null || operations.get(operation.requestId) != operation || operation.consumed
                || operation.consumer != consumer || operation.purpose != purpose || !operation.resource.equals(resource)
                || !operation.target.equals(target) || !operation.connectionId.equals(connectionId)) throw denied();
        session.assertEpoch(operation.epoch);
        foreground();
        if (!session.unlocked() || clock.now() >= operation.deadline) throw new VaultException("VAULT_EXPIRED", "Secret operation expired");
        VaultEnvelope envelope = requireCurrent();
        if (!envelope.id.equals(operation.vaultId) || envelope.generation != operation.generation) throw denied();
        unchanged(operation.snapshotBinding);
        operation.consumed = true;
        operations.remove(operation.requestId);
    }

    private void protectedEffect(VaultOperation operation) throws VaultException {
        if (operation.cancelled) throw new VaultException("VAULT_CANCELLED", "Secret request was cancelled");
        unchanged(operation.snapshotBinding);
        session.assertEpoch(operation.epoch);
        foreground();
        if (!session.unlocked() || clock.now() >= operation.deadline) {
            throw new VaultException("VAULT_EXPIRED", "Secret operation expired");
        }
        if (operation.cancelled) throw new VaultException("VAULT_CANCELLED", "Secret request was cancelled");
    }

    void cancelOperation(VaultOperation operation) {
        if (operation == null) return;
        operation.cancel();
        if (operations.get(operation.requestId) == operation) operations.remove(operation.requestId);
    }

    private Verified sessionManifest() throws VaultException {
        byte[] key = session.key();
        byte[] plaintext = null;
        try {
            VaultEnvelope envelope = requireCurrent();
            plaintext = envelope.decrypt(key);
            VaultManifest manifest = VaultManifest.decode(plaintext);
            try { validateBusiness(manifest); }
            catch (VaultException failure) { manifest.close(); throw failure; }
            return new Verified(envelope, key, plaintext, manifest);
        } catch (VaultException error) { VaultCodec.wipe(key); VaultCodec.wipe(plaintext); throw error; }
    }

    private static VaultManifest.Entry findEntry(VaultManifest manifest, String id, VaultManifest.EntryType type)
            throws VaultException {
        for (VaultManifest.Entry entry : manifest.entries) if (entry.id.equals(id) && entry.type == type) return entry;
        throw denied();
    }

    private static VaultManifest.Entry findSshEntry(VaultManifest manifest, String id) throws VaultException {
        for (VaultManifest.Entry entry : manifest.entries) if (entry.id.equals(id) && entry.type != VaultManifest.EntryType.TOTP) return entry;
        throw denied();
    }

    private static VaultManifest.FileObject findFile(VaultManifest manifest, String path) throws VaultException {
        for (VaultManifest.FileObject file : manifest.files) if (file.path.equals(path)) return file;
        throw denied();
    }

    private static VaultException denied() { return new VaultException("VAULT_OPERATION", "Secret operation does not match its resource"); }

    synchronized void cancelPreview(String id) {
        if (preview != null && preview.id.equals(id)) cancelPreview();
    }
    synchronized void expirePreview(String id) {
        if (preview != null && preview.id.equals(id) && clock.now() >= preview.deadline) cancelPreview();
    }

    synchronized void cancelRequest(String id) {
        if (preview != null && preview.requestId.equals(id)) cancelPreview();
    }

    private void cancelPreview() {
        if (preview != null) preview.close();
        preview = null;
    }

    private void foreground() throws VaultException {
        if (!session.foreground()) throw new VaultException("VAULT_FOREGROUND_REQUIRED", "Return to the app to continue");
    }

    private VaultEnvelope requireCurrent() throws VaultException {
        VaultEnvelope envelope = current();
        if (envelope == null) throw new VaultException("VAULT_MISSING", "Create or import a vault first");
        return envelope;
    }

    private void unchanged(byte[] binding) throws VaultException {
        VaultEnvelope current = current();
        if (binding == null ? current != null : current == null || !MessageDigest.isEqual(binding, current.binding())) {
            throw new VaultException("VAULT_CHANGED", "The vault changed; try again");
        }
    }

    private void commit(VaultEnvelope envelope, byte[] quick, EffectGuard guard) throws VaultException {
        try { store.commit(envelope.encode(), quick, new EffectGuard() {
            @Override public void check() throws VaultException { guard.check(); }
            @Override public void beforeCommit() throws VaultException {
                guard.beforeCommit();
                synchronized (publicationLock) { contentRevision++; }
            }
        }); }
        catch (IOException error) { throw new VaultException("VAULT_IO", "Unable to save the vault; previous data retained"); }
        catch (VaultException error) {
            if (error.code.equals("VAULT_COMMIT_UNCERTAIN")) { session.lock(); operations.clear(); cancelPreview(); }
            throw error;
        }
    }

    static Verified verify(byte[] bytes, char[] password) throws VaultException {
        VaultEnvelope envelope = VaultEnvelope.parse(bytes);
        byte[] key = VaultEnvelope.derive(password, envelope.salt);
        byte[] plaintext = null;
        try {
            plaintext = envelope.decrypt(key);
            return new Verified(envelope, key, plaintext, VaultManifest.decode(plaintext));
        } catch (VaultException error) {
            VaultCodec.wipe(key);
            VaultCodec.wipe(plaintext);
            throw error;
        }
    }

    private static void verifyReadback(VaultEnvelope envelope, byte[] key, byte[] expected) throws VaultException {
        byte[] readback = VaultEnvelope.parse(envelope.encode()).decrypt(key);
        try (VaultManifest ignored = VaultManifest.decode(readback)) {
            if (!MessageDigest.isEqual(readback, expected)) throw VaultException.invalid();
        } finally { VaultCodec.wipe(readback); }
    }
}
