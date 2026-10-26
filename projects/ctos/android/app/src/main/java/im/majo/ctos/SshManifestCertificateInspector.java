package im.majo.ctos;

import android.os.SystemClock;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/** All supplied certificate facts; embedded signature consistency is not CA trust. */
final class SshManifestCertificateInspector {
    private static final int WIRE_BYTES = 168;
    private static final ThreadLocal<Boolean> ENTERED = new ThreadLocal<>();
    interface Clock { long now(); }
    /** Borrows the independent public body synchronously; must not retain or mutate it. */
    interface NativeBinding {
        byte[] inspectCertificate(byte[] body, long deadline) throws IOException;
    }
    private static final class Native {
        static { System.loadLibrary("ctos_ssh_files"); }
        static native byte[] inspectCertificate(byte[] body, long deadline) throws IOException;
    }
    static final class Cancellation {
        private final AtomicBoolean cancelled = new AtomicBoolean();
        void cancel() { cancelled.set(true); }
        boolean cancelled() { return cancelled.get(); }
    }
    enum Status { FACTS, MALFORMED, UNSUPPORTED }
    enum CertificateType { USER, HOST }
    enum KeyKind { NONE, ED25519, EC256, EC384, EC521, RSA }
    enum SignatureAlgorithm { NONE, ED25519, EC256, EC384, EC521, RSA_SHA256, RSA_SHA512 }
    static final class Facts {
        final CertificateType certificateType;
        final KeyKind subjectKind, caKind;
        final SignatureAlgorithm signatureAlgorithm;
        final int principalCount, criticalByteLength, extensionByteLength;
        // Unsigned SSH u64 bit patterns, not signed values, milliseconds or checked intervals.
        final long serialBits, validAfterBits, validBeforeBits;
        final String certificateDigest, subjectDigest, caDigest;
        private Facts(int type, int subject, int ca, int algorithm, int principals,
                int critical, int extensions, long serial, long after, long before,
                String certificateDigest, String subjectDigest, String caDigest) {
            certificateType = CertificateType.values()[type - 1];
            subjectKind = KeyKind.values()[subject]; caKind = KeyKind.values()[ca];
            signatureAlgorithm = SignatureAlgorithm.values()[algorithm];
            principalCount = principals; criticalByteLength = critical; extensionByteLength = extensions;
            serialBits = serial; validAfterBits = after; validBeforeBits = before;
            this.certificateDigest = certificateDigest; this.subjectDigest = subjectDigest; this.caDigest = caDigest;
        }
    }
    static final class Entry {
        final String id, path, identity;
        final int ordinal, sourceLine;
        final Status status;
        final Facts facts;
        private Entry(VaultManifest.FileObject file, int ordinal, byte[] wire) throws IOException {
            if (wire == null || wire.length != WIRE_BYTES) throw invalid();
            ByteBuffer input = ByteBuffer.wrap(wire);
            if (input.getInt() != 0x43534346 || input.getInt() != 1) throw invalid();
            int s = input.getInt(), flags = input.getInt();
            int type = input.getInt(), subject = input.getInt(), ca = input.getInt();
            int algorithm = input.getInt(), principals = input.getInt();
            int critical = input.getInt(), extensions = input.getInt(), line = input.getInt();
            long serial = input.getLong(), after = input.getLong(), before = input.getLong();
            if (s < 0 || s > 2 || line < 0 || line > 16384) throw invalid();
            boolean available = s == 0;
            if (available) {
                if (flags != 7 || type < 1 || type > 2 || subject < 1 || subject > 5
                        || ca < 1 || ca > 5 || algorithm < 1 || algorithm > 6
                        || (ca < 5 ? algorithm != ca : algorithm < 5)
                        || principals < 0 || principals > 256 || critical < 0
                        || critical > VaultManifest.MAX_FILE_BYTES || extensions < 0
                        || extensions > VaultManifest.MAX_FILE_BYTES || line == 0) throw invalid();
            } else if (flags != 1 || type != 0 || subject != 0 || ca != 0 || algorithm != 0
                    || principals != 0 || critical != 0 || extensions != 0
                    || serial != 0 || after != 0 || before != 0) throw invalid();
            String certificateDigest = digest(input, available);
            String subjectDigest = digest(input, available), caDigest = digest(input, available);
            id = file.id; path = file.path; identity = file.identity; this.ordinal = ordinal;
            sourceLine = line; status = Status.values()[s];
            facts = available ? new Facts(type, subject, ca, algorithm, principals, critical, extensions,
                    serial, after, before, certificateDigest, subjectDigest, caDigest) : null;
        }
    }
    static final class Report {
        final boolean allCertificateFilesInspected = true, factsOnly = true;
        final boolean admissionComplete = false, trustChecked = false, timeValidityChecked = false;
        final boolean principalApplicabilityChecked = false, revocationChecked = false;
        final boolean allCertificateFactsAvailable;
        final List<Entry> entries;
        private Report(List<Entry> entries) {
            this.entries = Collections.unmodifiableList(new ArrayList<>(entries));
            boolean available = true;
            for (Entry entry : entries) available &= entry.status == Status.FACTS;
            allCertificateFactsAvailable = available;
        }
    }
    private static final class Loan implements AutoCloseable {
        final byte[][] bodies;
        Loan(int count) { bodies = new byte[count][]; }
        @Override public void close() { for (byte[] body : bodies) wipe(body); }
    }
    private final Thread owner = Thread.currentThread();
    private final Clock clock;
    private final NativeBinding binding;
    private boolean attempted;
    SshManifestCertificateInspector() { this(SystemClock::elapsedRealtime, Native::inspectCertificate); }
    SshManifestCertificateInspector(Clock clock, NativeBinding binding) {
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
            try (Loan loan = new Loan(manifest.files.size())) {
                preflight(manifest, deadline, cancellation);
                copy(manifest, loan, deadline, cancellation);
                List<Entry> entries = new ArrayList<>();
                for (int i = 0; i < manifest.files.size(); i++) {
                    check(deadline, cancellation);
                    VaultManifest.FileObject file = manifest.files.get(i);
                    if (file.type != VaultManifest.FileType.CERTIFICATE) continue;
                    byte[] wire = null;
                    try {
                        wire = binding.inspectCertificate(loan.bodies[i], deadline);
                        check(deadline, cancellation);
                        entries.add(new Entry(file, i, wire));
                    } finally { wipe(wire); }
                    check(deadline, cancellation);
                }
                report = new Report(entries);
            }
            check(deadline, cancellation);
            return report;
        } finally { ENTERED.remove(); }
    }
    private void preflight(VaultManifest manifest, long deadline, Cancellation cancellation) throws IOException {
        long total = 0;
        int certificates = 0;
        for (VaultManifest.FileObject file : manifest.files) {
            check(deadline, cancellation);
            if (file.content.length > VaultManifest.MAX_FILE_BYTES) throw invalid();
            total += file.content.length;
            if (total > VaultManifest.MAX_TOTAL_FILE_BYTES) throw invalid();
            if (file.type == VaultManifest.FileType.CERTIFICATE && ++certificates * WIRE_BYTES > 21504) throw invalid();
        }
        check(deadline, cancellation);
    }
    private void copy(VaultManifest manifest, Loan loan, long deadline, Cancellation cancellation) throws IOException {
        for (int i = 0; i < manifest.files.size(); i++) {
            check(deadline, cancellation);
            VaultManifest.FileObject file = manifest.files.get(i);
            if (file.type != VaultManifest.FileType.CERTIFICATE) continue;
            loan.bodies[i] = new byte[file.content.length];
            System.arraycopy(file.content, 0, loan.bodies[i], 0, file.content.length);
            check(deadline, cancellation);
        }
    }
    private static String digest(ByteBuffer input, boolean available) throws IOException {
        int bits = 0;
        StringBuilder result = new StringBuilder(64);
        for (int i = 0; i < 32; i++) {
            int value = input.get() & 255;
            bits |= value;
            result.append(Character.forDigit(value >>> 4, 16));
            result.append(Character.forDigit(value & 15, 16));
        }
        if (available == (bits == 0)) throw invalid();
        return available ? result.toString() : "";
    }
    private long check(long deadline, Cancellation cancellation) throws IOException {
        if (cancellation.cancelled()) throw invalid();
        long now = clock.now();
        if (now < 0 || deadline <= now || cancellation.cancelled()) throw invalid();
        return now;
    }
    private static IOException invalid() { return new IOException("SSH certificate inspection unavailable"); }
    private static void wipe(byte[] bytes) { if (bytes != null) Arrays.fill(bytes, (byte)0); }
}
