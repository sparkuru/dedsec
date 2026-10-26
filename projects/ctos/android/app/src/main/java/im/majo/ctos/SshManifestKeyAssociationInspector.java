package im.majo.ctos;

import android.os.SystemClock;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

/** One explicit supplied-pair proof job; never whole-manifest or certificate admission. */
final class SshManifestKeyAssociationInspector {
    private static final ThreadLocal<Boolean> ENTERED = new ThreadLocal<>();
    private static final long MAX_WORK_BYTES = 64L * 1024L * 1024L;
    interface Clock { long now(); }
    /** Borrows independent arrays synchronously; must neither mutate nor retain them. */
    interface NativeBinding {
        byte[] verify(int peerType, byte[] privateBody, byte[] passphrase, byte[] peerBody,
                long deadline) throws IOException;
    }
    private static final class Native {
        static { System.loadLibrary("ctos_ssh_files"); }
        static native byte[] verify(int peerType, byte[] privateBody, byte[] passphrase,
                byte[] peerBody, long deadline) throws IOException;
    }
    static final class Cancellation {
        private final AtomicBoolean cancelled = new AtomicBoolean();
        void cancel() { cancelled.set(true); }
        boolean cancelled() { return cancelled.get(); }
    }
    static final class Association {
        final String privateFileId, peerFileId, passphraseEntryId;
        Association(String privateFileId, String peerFileId, String passphraseEntryId) {
            this.privateFileId = privateFileId;
            this.peerFileId = peerFileId;
            this.passphraseEntryId = passphraseEntryId;
        }
    }
    enum Status { MATCH, MISMATCH, MALFORMED, UNSUPPORTED, NEEDS_PASSPHRASE, BAD_PASSPHRASE }
    enum Stage { COMPLETE, PRIVATE, PEER, RELATION, SIGNER }
    enum KeyKind { NONE, ED25519, EC256, EC384, EC521, RSA }
    static final class Entry {
        final String privateFileId, peerFileId, passphraseEntryId;
        final int privateOrdinal, peerOrdinal, passphraseOrdinal;
        final VaultManifest.FileType peerType;
        final Status status;
        final Stage stage;
        final KeyKind keyKind;
        final boolean encrypted;
        final String publicSubjectDigest;
        private Entry(Association association, int privateOrdinal, int peerOrdinal,
                int passphraseOrdinal, VaultManifest.FileType type, byte[] wire) throws IOException {
            if (wire == null || wire.length != 60) throw invalid();
            ByteBuffer input = ByteBuffer.wrap(wire);
            if (input.getInt() != 0x43534b41 || input.getInt() != 1) throw invalid();
            int s = input.getInt(), k = input.getInt(), f = input.getInt();
            int phase = input.getInt(), nativeType = input.getInt();
            if (s < 0 || s > 5 || k < 0 || k > 5 || phase < 0 || phase > 4
                    || (f & ~29) != 0 || (f & 4) == 0 || nativeType != nativeType(type)) throw invalid();
            boolean match = s == 0;
            if (match ? phase != 0 || k == 0 || (f & 24) != 24
                    : k != 0 || (f & 24) != 0) throw invalid();
            if ((s == 1 && phase != 3 && phase != 4)
                    || ((s == 2 || s == 3) && phase != 1 && phase != 2)
                    || ((s == 4 || s == 5) && (phase != 1 || (f & 1) == 0))) throw invalid();
            int digestBits = 0;
            StringBuilder digest = new StringBuilder(64);
            for (int i = 0; i < 32; i++) {
                int value = input.get() & 255;
                digestBits |= value;
                digest.append(Character.forDigit(value >>> 4, 16));
                digest.append(Character.forDigit(value & 15, 16));
            }
            if (match == (digestBits == 0)) throw invalid();
            privateFileId = association.privateFileId;
            peerFileId = association.peerFileId;
            passphraseEntryId = association.passphraseEntryId;
            this.privateOrdinal = privateOrdinal;
            this.peerOrdinal = peerOrdinal;
            this.passphraseOrdinal = passphraseOrdinal;
            peerType = type;
            status = Status.values()[s]; stage = Stage.values()[phase]; keyKind = KeyKind.values()[k];
            encrypted = (f & 1) != 0;
            publicSubjectDigest = match ? digest.toString() : "";
        }
    }
    static final class Report {
        final boolean explicitPairsOnly = true, admissionComplete = false;
        final boolean trustChecked = false, credentialTargetsChecked = false;
        final boolean allRequestedPairsMatch;
        final List<Entry> entries;
        private Report(List<Entry> entries) {
            this.entries = Collections.unmodifiableList(new ArrayList<>(entries));
            boolean match = !entries.isEmpty();
            for (Entry entry : entries) match &= entry.status == Status.MATCH;
            allRequestedPairsMatch = match;
        }
    }
    private static final class Loan implements AutoCloseable {
        final byte[][] bodies, passphrases;
        final int[] privateOrdinals, peerOrdinals, passphraseOrdinals;
        Loan(VaultManifest manifest, int count) {
            bodies = new byte[manifest.files.size()][];
            passphrases = new byte[manifest.entries.size()][];
            privateOrdinals = new int[count]; peerOrdinals = new int[count];
            passphraseOrdinals = new int[count];
            Arrays.fill(passphraseOrdinals, -1);
        }
        @Override public void close() {
            for (byte[] body : bodies) wipe(body);
            for (byte[] phrase : passphrases) wipe(phrase);
        }
    }
    private final Thread owner = Thread.currentThread();
    private final Clock clock;
    private final NativeBinding binding;
    private boolean attempted;
    SshManifestKeyAssociationInspector() { this(SystemClock::elapsedRealtime, Native::verify); }
    SshManifestKeyAssociationInspector(Clock clock, NativeBinding binding) {
        if (clock == null || binding == null) throw new IllegalArgumentException("Inspection dependency required");
        this.clock = clock; this.binding = binding;
    }
    Report inspect(VaultManifest manifest, List<Association> associations, long deadline,
            Cancellation cancellation) throws IOException {
        if (Thread.currentThread() != owner || attempted || Boolean.TRUE.equals(ENTERED.get())) throw invalid();
        attempted = true;
        ENTERED.set(true);
        try {
            if (manifest == null || associations == null || cancellation == null) throw invalid();
            long start = check(deadline, cancellation);
            if (deadline - start > 60_000L || associations.isEmpty() || associations.size() > 128) throw invalid();
            List<Association> pairs = new ArrayList<>(associations);
            Report report;
            try (Loan loan = new Loan(manifest, pairs.size())) {
                preflight(manifest, pairs, loan, deadline, cancellation);
                copy(manifest, loan, deadline, cancellation);
                List<Entry> results = new ArrayList<>();
                for (int i = 0; i < pairs.size(); i++) {
                    check(deadline, cancellation);
                    int privateOrdinal = loan.privateOrdinals[i], peerOrdinal = loan.peerOrdinals[i];
                    int phraseOrdinal = loan.passphraseOrdinals[i];
                    VaultManifest.FileType type = manifest.files.get(peerOrdinal).type;
                    byte[] wire = null;
                    try {
                        wire = binding.verify(nativeType(type), loan.bodies[privateOrdinal],
                                phraseOrdinal < 0 ? null : loan.passphrases[phraseOrdinal],
                                loan.bodies[peerOrdinal], deadline);
                        check(deadline, cancellation);
                        results.add(new Entry(pairs.get(i), privateOrdinal, peerOrdinal, phraseOrdinal, type, wire));
                    } finally { wipe(wire); }
                    check(deadline, cancellation);
                }
                report = new Report(results);
            }
            check(deadline, cancellation);
            return report;
        } finally { ENTERED.remove(); }
    }
    private void preflight(VaultManifest manifest, List<Association> pairs, Loan loan,
            long deadline, Cancellation cancellation) throws IOException {
        long total = 0, work = 0;
        for (VaultManifest.FileObject file : manifest.files) {
            check(deadline, cancellation);
            if (file.content.length > VaultManifest.MAX_FILE_BYTES) throw invalid();
            total += file.content.length;
            if (total > VaultManifest.MAX_TOTAL_FILE_BYTES) throw invalid();
        }
        Set<List<String>> seen = new HashSet<>();
        for (int i = 0; i < pairs.size(); i++) {
            check(deadline, cancellation);
            Association pair = pairs.get(i);
            if (pair == null || pair.privateFileId == null || pair.peerFileId == null
                    || !seen.add(Arrays.asList(pair.privateFileId, pair.peerFileId, pair.passphraseEntryId))) throw invalid();
            int privateIndex = fileIndex(manifest, pair.privateFileId), peerIndex = fileIndex(manifest, pair.peerFileId);
            VaultManifest.FileObject privateFile = manifest.files.get(privateIndex), peerFile = manifest.files.get(peerIndex);
            if (privateFile.type != VaultManifest.FileType.PRIVATE_KEY) throw invalid();
            nativeType(peerFile.type);
            loan.privateOrdinals[i] = privateIndex; loan.peerOrdinals[i] = peerIndex;
            work += (long)privateFile.content.length + peerFile.content.length;
            if (work > MAX_WORK_BYTES) throw invalid();
            if (pair.passphraseEntryId != null) {
                int phraseIndex = entryIndex(manifest, pair.passphraseEntryId);
                VaultManifest.Entry phrase = manifest.entries.get(phraseIndex);
                if (phrase.type != VaultManifest.EntryType.SSH_KEY_PASSPHRASE
                        || !("key:" + privateFile.identity).equals(phrase.identity)
                        || phrase.secret.length == 0 || phrase.secret.length > 8192) throw invalid();
                for (byte value : phrase.secret) if (value == 0) throw invalid();
                loan.passphraseOrdinals[i] = phraseIndex;
            }
        }
    }
    private void copy(VaultManifest manifest, Loan loan, long deadline, Cancellation cancellation) throws IOException {
        for (int i = 0; i < loan.privateOrdinals.length; i++) {
            int[] selected = {loan.privateOrdinals[i], loan.peerOrdinals[i]};
            for (int ordinal : selected) {
                check(deadline, cancellation);
                if (loan.bodies[ordinal] == null) {
                    byte[] source = manifest.files.get(ordinal).content;
                    loan.bodies[ordinal] = new byte[source.length];
                    System.arraycopy(source, 0, loan.bodies[ordinal], 0, source.length);
                }
                check(deadline, cancellation);
            }
            int ordinal = loan.passphraseOrdinals[i];
            if (ordinal >= 0 && loan.passphrases[ordinal] == null) {
                check(deadline, cancellation);
                byte[] source = manifest.entries.get(ordinal).secret;
                loan.passphrases[ordinal] = new byte[source.length];
                System.arraycopy(source, 0, loan.passphrases[ordinal], 0, source.length);
                check(deadline, cancellation);
            }
        }
    }
    private static int fileIndex(VaultManifest manifest, String id) throws IOException {
        for (int i = 0; i < manifest.files.size(); i++) if (id.equals(manifest.files.get(i).id)) return i;
        throw invalid();
    }
    private static int entryIndex(VaultManifest manifest, String id) throws IOException {
        for (int i = 0; i < manifest.entries.size(); i++) if (id.equals(manifest.entries.get(i).id)) return i;
        throw invalid();
    }
    private static int nativeType(VaultManifest.FileType type) throws IOException {
        if (type == VaultManifest.FileType.PUBLIC_KEY) return 3;
        if (type == VaultManifest.FileType.CERTIFICATE) return 4;
        throw invalid();
    }
    private long check(long deadline, Cancellation cancellation) throws IOException {
        if (cancellation.cancelled()) throw invalid();
        long now = clock.now();
        if (now < 0 || deadline <= now || cancellation.cancelled()) throw invalid();
        return now;
    }
    private static IOException invalid() { return new IOException("SSH key association inspection unavailable"); }
    private static void wipe(byte[] bytes) { if (bytes != null) Arrays.fill(bytes, (byte)0); }
}
