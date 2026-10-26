package im.majo.ctos;

import android.os.SystemClock;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/** Exhaustive supplied-private proof and v1 credential ownership; never semantic admission. */
final class SshManifestPrivateMaterialInspector {
    private static final ThreadLocal<Boolean> ENTERED = new ThreadLocal<>();
    interface Clock { long now(); }
    /** Borrows independent arrays synchronously; must neither retain nor mutate them. */
    interface NativeBinding {
        byte[] verifyPrivate(byte[] body, byte[] passphrase, long deadline) throws IOException;
    }
    private static final class Native {
        static { System.loadLibrary("ctos_ssh_files"); }
        static native byte[] verifyPrivate(byte[] body, byte[] passphrase, long deadline) throws IOException;
    }
    static final class Cancellation {
        private final AtomicBoolean cancelled = new AtomicBoolean();
        void cancel() { cancelled.set(true); }
        boolean cancelled() { return cancelled.get(); }
    }
    enum Ownership { NO_PASSPHRASE, OWNER_RESOLVED, OWNER_AMBIGUOUS }
    enum CredentialStatus {
        OWNER_RESOLVED, ORPHAN, AMBIGUOUS_PRIVATE, AMBIGUOUS_PASSPHRASE,
        INVALID_NAMESPACE, TARGET_DEFERRED
    }
    enum Status { MATCH, MISMATCH, MALFORMED, UNSUPPORTED, NEEDS_PASSPHRASE, BAD_PASSPHRASE }
    enum Stage { COMPLETE, PRIVATE, SIGNER }
    enum KeyKind { NONE, ED25519, EC256, EC384, EC521, RSA }

    static final class Verification {
        final Status status;
        final Stage stage;
        final KeyKind keyKind;
        final boolean encrypted;
        final String publicSubjectDigest;
        private Verification(byte[] wire) throws IOException {
            if (wire == null || wire.length != 60) throw invalid();
            ByteBuffer input = ByteBuffer.wrap(wire);
            if (input.getInt() != 0x43534b50 || input.getInt() != 1) throw invalid();
            int s = input.getInt(), k = input.getInt(), f = input.getInt(), phase = input.getInt();
            if (input.getInt() != 2 || s < 0 || s > 5 || k < 0 || k > 5
                    || (f & ~29) != 0 || (f & 4) == 0) throw invalid();
            boolean match = s == 0;
            if (match ? phase != 0 || k == 0 || (f & 24) != 24
                    : phase != (s == 1 ? 4 : 1) || k != 0 || (f & 24) != 0) throw invalid();
            if ((s == 4 || s == 5) && (f & 1) == 0) throw invalid();
            int digestBits = 0;
            StringBuilder digest = new StringBuilder(64);
            for (int i = 0; i < 32; i++) {
                int value = input.get() & 255;
                digestBits |= value;
                digest.append(Character.forDigit(value >>> 4, 16));
                digest.append(Character.forDigit(value & 15, 16));
            }
            if (match == (digestBits == 0)) throw invalid();
            status = Status.values()[s]; keyKind = KeyKind.values()[k];
            stage = phase == 0 ? Stage.COMPLETE : phase == 1 ? Stage.PRIVATE : Stage.SIGNER;
            encrypted = (f & 1) != 0;
            publicSubjectDigest = match ? digest.toString() : "";
        }
    }
    static final class PrivateEntry {
        final String privateFileId, passphraseEntryId;
        final int privateOrdinal, passphraseOrdinal;
        final Ownership ownership;
        final Verification verification;
        private PrivateEntry(VaultManifest manifest, Loan loan, int ordinal, Verification verification) {
            privateFileId = manifest.files.get(ordinal).id;
            privateOrdinal = ordinal;
            passphraseOrdinal = loan.selected[ordinal];
            passphraseEntryId = passphraseOrdinal < 0 ? null : manifest.entries.get(passphraseOrdinal).id;
            ownership = loan.ownership[ordinal];
            this.verification = verification;
        }
    }
    static final class CredentialEntry {
        final String entryId, privateFileId;
        final int entryOrdinal, privateOrdinal;
        final VaultManifest.EntryType type;
        final CredentialStatus status;
        private CredentialEntry(VaultManifest manifest, int ordinal, int owner, CredentialStatus status) {
            VaultManifest.Entry entry = manifest.entries.get(ordinal);
            entryId = entry.id; entryOrdinal = ordinal; type = entry.type;
            privateOrdinal = owner;
            privateFileId = owner < 0 ? null : manifest.files.get(owner).id;
            this.status = status;
        }
    }
    static final class Report {
        final boolean allPrivateDeclarationsVisited = true, allNonTotpCredentialsVisited = true;
        final boolean admissionComplete = false, keyAssociationsChecked = false;
        final boolean passwordTargetsChecked = false, trustChecked = false;
        final boolean allPrivateMaterialsVerified, allPassphraseOwnersResolved;
        final List<PrivateEntry> privateEntries;
        final List<CredentialEntry> credentialEntries;
        private Report(List<PrivateEntry> privateEntries, List<CredentialEntry> credentialEntries) {
            this.privateEntries = Collections.unmodifiableList(new ArrayList<>(privateEntries));
            this.credentialEntries = Collections.unmodifiableList(new ArrayList<>(credentialEntries));
            boolean verified = true, resolved = true;
            for (PrivateEntry entry : privateEntries)
                verified &= entry.verification != null && entry.verification.status == Status.MATCH;
            for (CredentialEntry entry : credentialEntries)
                if (entry.type == VaultManifest.EntryType.SSH_KEY_PASSPHRASE)
                    resolved &= entry.status == CredentialStatus.OWNER_RESOLVED;
            allPrivateMaterialsVerified = verified; allPassphraseOwnersResolved = resolved;
        }
    }
    /** Slots exist before any body or phrase copy; ambiguous material never enters these slots. */
    private static final class Loan implements AutoCloseable {
        final byte[][] bodies, phrases;
        final Ownership[] ownership;
        final int[] selected, phraseCounts, privateCounts;
        Loan(VaultManifest manifest) {
            bodies = new byte[manifest.files.size()][];
            phrases = new byte[manifest.entries.size()][];
            ownership = new Ownership[manifest.files.size()];
            selected = new int[manifest.files.size()];
            phraseCounts = new int[manifest.files.size()];
            privateCounts = new int[manifest.files.size()];
            Arrays.fill(selected, -1);
        }
        @Override public void close() {
            for (byte[] body : bodies) wipe(body);
            for (byte[] phrase : phrases) wipe(phrase);
        }
    }
    private final Thread owner = Thread.currentThread();
    private final Clock clock;
    private final NativeBinding binding;
    private boolean attempted;
    SshManifestPrivateMaterialInspector() { this(SystemClock::elapsedRealtime, Native::verifyPrivate); }
    SshManifestPrivateMaterialInspector(Clock clock, NativeBinding binding) {
        if (clock == null || binding == null) throw new IllegalArgumentException("Inspection dependency required");
        this.clock = clock; this.binding = binding;
    }
    Report inspect(VaultManifest manifest, long deadline, Cancellation cancellation) throws IOException {
        if (Thread.currentThread() != owner || attempted || Boolean.TRUE.equals(ENTERED.get())) throw invalid();
        attempted = true;
        ENTERED.set(true);
        try {
            if (manifest == null || cancellation == null) throw invalid();
            long start = check(deadline, cancellation);
            if (deadline - start > 60_000L || manifest.files.size() > VaultManifest.MAX_FILES
                    || manifest.entries.size() > VaultManifest.MAX_ENTRIES) throw invalid();
            Report report;
            try (Loan loan = new Loan(manifest)) {
                List<CredentialEntry> credentials = preflight(manifest, loan, deadline, cancellation);
                copy(manifest, loan, deadline, cancellation);
                List<PrivateEntry> privateEntries = new ArrayList<>();
                for (int i = 0; i < manifest.files.size(); i++) {
                    check(deadline, cancellation);
                    if (loan.ownership[i] == null) continue;
                    Verification verification = null;
                    if (loan.ownership[i] != Ownership.OWNER_AMBIGUOUS) {
                        byte[] wire = null;
                        try {
                            int selected = loan.selected[i];
                            wire = binding.verifyPrivate(loan.bodies[i],
                                    selected < 0 ? null : loan.phrases[selected], deadline);
                            check(deadline, cancellation);
                            verification = new Verification(wire);
                        } finally { wipe(wire); }
                    }
                    privateEntries.add(new PrivateEntry(manifest, loan, i, verification));
                    check(deadline, cancellation);
                }
                report = new Report(privateEntries, credentials);
            }
            check(deadline, cancellation);
            return report;
        } finally { ENTERED.remove(); }
    }
    private List<CredentialEntry> preflight(VaultManifest manifest, Loan loan, long deadline,
            Cancellation cancellation) throws IOException {
        long total = 0;
        for (VaultManifest.FileObject file : manifest.files) {
            check(deadline, cancellation);
            if (file.content.length > VaultManifest.MAX_FILE_BYTES) throw invalid();
            total += file.content.length;
            if (total > VaultManifest.MAX_TOTAL_FILE_BYTES) throw invalid();
        }
        for (int i = 0; i < manifest.files.size(); i++) {
            check(deadline, cancellation);
            VaultManifest.FileObject file = manifest.files.get(i);
            if (file.type != VaultManifest.FileType.PRIVATE_KEY) continue;
            for (VaultManifest.FileObject candidate : manifest.files) {
                check(deadline, cancellation);
                if (candidate.type == VaultManifest.FileType.PRIVATE_KEY && file.identity.equals(candidate.identity))
                    loan.privateCounts[i]++;
            }
            String keyIdentity = "key:" + file.identity;
            for (int j = 0; j < manifest.entries.size(); j++) {
                check(deadline, cancellation);
                VaultManifest.Entry entry = manifest.entries.get(j);
                if (entry.type == VaultManifest.EntryType.SSH_KEY_PASSPHRASE && keyIdentity.equals(entry.identity)) {
                    loan.phraseCounts[i]++; loan.selected[i] = j;
                }
            }
            if (loan.phraseCounts[i] == 0) loan.ownership[i] = Ownership.NO_PASSPHRASE;
            else if (loan.privateCounts[i] == 1 && loan.phraseCounts[i] == 1) {
                loan.ownership[i] = Ownership.OWNER_RESOLVED;
                byte[] secret = manifest.entries.get(loan.selected[i]).secret;
                if (secret.length == 0 || secret.length > 8192) throw invalid();
                for (byte value : secret) if (value == 0) throw invalid();
            } else { loan.ownership[i] = Ownership.OWNER_AMBIGUOUS; loan.selected[i] = -1; }
        }
        List<CredentialEntry> credentials = new ArrayList<>();
        for (int i = 0; i < manifest.entries.size(); i++) {
            check(deadline, cancellation);
            VaultManifest.Entry entry = manifest.entries.get(i);
            if (entry.type == VaultManifest.EntryType.TOTP) continue;
            int privateOrdinal = -1;
            CredentialStatus status;
            if (entry.type == VaultManifest.EntryType.SSH_PASSWORD) {
                status = entry.identity.startsWith("target:") ? CredentialStatus.TARGET_DEFERRED
                        : CredentialStatus.INVALID_NAMESPACE;
            } else if (!entry.identity.startsWith("key:")) status = CredentialStatus.INVALID_NAMESPACE;
            else {
                int count = 0;
                for (int j = 0; j < manifest.files.size(); j++) {
                    check(deadline, cancellation);
                    VaultManifest.FileObject candidate = manifest.files.get(j);
                    if (candidate.type == VaultManifest.FileType.PRIVATE_KEY
                            && entry.identity.equals("key:" + candidate.identity)) { count++; privateOrdinal = j; }
                }
                if (count == 0) status = CredentialStatus.ORPHAN;
                else if (count > 1) { status = CredentialStatus.AMBIGUOUS_PRIVATE; privateOrdinal = -1; }
                else status = loan.phraseCounts[privateOrdinal] == 1 ? CredentialStatus.OWNER_RESOLVED
                        : CredentialStatus.AMBIGUOUS_PASSPHRASE;
            }
            credentials.add(new CredentialEntry(manifest, i, privateOrdinal, status));
        }
        check(deadline, cancellation);
        return credentials;
    }
    private void copy(VaultManifest manifest, Loan loan, long deadline, Cancellation cancellation) throws IOException {
        for (int i = 0; i < manifest.files.size(); i++) {
            check(deadline, cancellation);
            if (loan.ownership[i] == null || loan.ownership[i] == Ownership.OWNER_AMBIGUOUS) continue;
            byte[] source = manifest.files.get(i).content;
            loan.bodies[i] = new byte[source.length];
            System.arraycopy(source, 0, loan.bodies[i], 0, source.length);
            check(deadline, cancellation);
            int selected = loan.selected[i];
            if (selected >= 0 && loan.phrases[selected] == null) {
                source = manifest.entries.get(selected).secret;
                loan.phrases[selected] = new byte[source.length];
                System.arraycopy(source, 0, loan.phrases[selected], 0, source.length);
                check(deadline, cancellation);
            }
        }
    }
    private long check(long deadline, Cancellation cancellation) throws IOException {
        if (cancellation.cancelled()) throw invalid();
        long now = clock.now();
        if (now < 0 || deadline <= now || cancellation.cancelled()) throw invalid();
        return now;
    }
    private static IOException invalid() { return new IOException("SSH private material inspection unavailable"); }
    private static void wipe(byte[] bytes) { if (bytes != null) Arrays.fill(bytes, (byte)0); }
}
