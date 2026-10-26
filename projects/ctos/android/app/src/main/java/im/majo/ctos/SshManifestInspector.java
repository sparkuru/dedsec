package im.majo.ctos;

import android.os.SystemClock;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/** Detached content inspection of an exclusive caller loan; never semantic admission. */
final class SshManifestInspector {
    private static final long MAX_DURATION = 60_000L;
    private static final int WIRE_MAGIC = 0x43534d49;
    private static final ThreadLocal<Boolean> ENTERED = new ThreadLocal<>();

    interface Clock { long now(); }

    /** Borrows the owned arrays only for this call; implementations must not retain them. */
    interface NativeBinding {
        byte[] inspect(int type, byte[] body, byte[] passphrase, long deadline) throws IOException;
    }

    private static final class Native {
        static { System.loadLibrary("ctos_ssh_files"); }
        static native byte[] inspect(int type, byte[] body, byte[] passphrase, long deadline)
                throws IOException;
    }

    static final class Cancellation {
        private final AtomicBoolean cancelled = new AtomicBoolean();
        void cancel() { cancelled.set(true); }
        boolean cancelled() { return cancelled.get(); }
    }

    enum Status {
        PARSED, MALFORMED, UNSUPPORTED, NEEDS_PASSPHRASE, BAD_PASSPHRASE,
        CONFIG_DEFERRED, AMBIGUOUS_PASSPHRASE
    }

    enum KeyKind { NONE, ED25519, EC256, EC384, EC521, RSA }

    static final class Entry {
        final VaultManifest.FileType type;
        final String id, path, identity;
        final Status status;
        final KeyKind keyKind;
        final boolean encrypted, certificate;
        final int records, line;
        // Immutable public metadata only; private-file and credential digests are never exposed.
        final String publicBlobDigest;

        private Entry(VaultManifest.FileObject file, Status status) {
            type = file.type;
            id = file.id;
            path = file.path;
            identity = file.identity;
            this.status = status;
            keyKind = KeyKind.NONE;
            encrypted = certificate = false;
            records = line = 0;
            publicBlobDigest = "";
        }

        private Entry(VaultManifest.FileObject file, byte[] wire) throws IOException {
            if (wire == null || wire.length != 60) throw invalid();
            ByteBuffer buffer = ByteBuffer.wrap(wire);
            if (buffer.getInt() != WIRE_MAGIC || buffer.getInt() != 1) throw invalid();
            int statusValue = buffer.getInt(), kindValue = buffer.getInt();
            int flags = buffer.getInt(), count = buffer.getInt(), sourceLine = buffer.getInt();
            if (statusValue < 0 || statusValue > 4 || kindValue < 0 || kindValue > 5
                    || (flags & ~7) != 0 || (flags & 4) == 0
                    || sourceLine < 0 || sourceLine > 16384) throw invalid();
            boolean isEncrypted = (flags & 1) != 0, isCertificate = (flags & 2) != 0;
            boolean parsed = statusValue == 0;
            boolean privateKey = file.type == VaultManifest.FileType.PRIVATE_KEY;
            boolean knownHosts = file.type == VaultManifest.FileType.KNOWN_HOSTS;
            if (file.type == VaultManifest.FileType.CONFIG || (isEncrypted && !privateKey)
                    || isCertificate != (parsed && file.type == VaultManifest.FileType.CERTIFICATE)
                    || ((statusValue == 3 || statusValue == 4) && (!privateKey || !isEncrypted)))
                throw invalid();
            int digestBits = 0;
            StringBuilder digest = new StringBuilder(64);
            for (int i = 0; i < 32; i++) {
                int value = buffer.get() & 255;
                digestBits |= value;
                digest.append(Character.forDigit(value >>> 4, 16));
                digest.append(Character.forDigit(value & 15, 16));
            }
            if (parsed) {
                if (sourceLine != 0) throw invalid();
                if (knownHosts) {
                    if (kindValue != 0 || digestBits != 0 || count < 0 || count > 16384)
                        throw invalid();
                } else if (kindValue == 0 || count != 1 || digestBits == 0) throw invalid();
            } else if (kindValue != 0 || count != 0 || digestBits != 0) throw invalid();
            type = file.type;
            id = file.id;
            path = file.path;
            identity = file.identity;
            status = Status.values()[statusValue];
            keyKind = KeyKind.values()[kindValue];
            encrypted = isEncrypted;
            certificate = isCertificate;
            records = count;
            line = sourceLine;
            publicBlobDigest = parsed && !knownHosts ? digest.toString() : "";
        }
    }

    static final class Report {
        final boolean contentOnly = true;
        final boolean admissionComplete = false;
        final List<Entry> entries;

        private Report(List<Entry> entries) {
            this.entries = Collections.unmodifiableList(new ArrayList<>(entries));
        }
    }

    /** All allocation slots exist before any sensitive byte is copied into them. */
    private static final class Loan implements AutoCloseable {
        final byte[][] bodies, passphrases;
        final int[] matches, selected;

        Loan(VaultManifest manifest) {
            bodies = new byte[manifest.files.size()][];
            passphrases = new byte[manifest.entries.size()][];
            matches = new int[manifest.files.size()];
            selected = new int[manifest.files.size()];
        }

        @Override public void close() {
            for (byte[] body : bodies) wipe(body);
            for (byte[] passphrase : passphrases) wipe(passphrase);
        }
    }

    private final Clock clock;
    private final NativeBinding binding;

    SshManifestInspector() { this(SystemClock::elapsedRealtime, Native::inspect); }

    SshManifestInspector(Clock clock, NativeBinding binding) {
        if (clock == null || binding == null) throw new IllegalArgumentException("Inspection dependency required");
        this.clock = clock;
        this.binding = binding;
    }

    Report inspect(VaultManifest manifest, long deadline, Cancellation cancellation) throws IOException {
        if (Boolean.TRUE.equals(ENTERED.get())) throw invalid();
        ENTERED.set(true);
        try {
            if (manifest == null || cancellation == null) throw invalid();
            long start = check(deadline, cancellation);
            if (deadline - start > MAX_DURATION) throw invalid();
            Report report;
            try (Loan loan = new Loan(manifest)) {
                copy(manifest, loan, deadline, cancellation);
                List<Entry> entries = new ArrayList<>();
                for (int i = 0; i < manifest.files.size(); i++) {
                    check(deadline, cancellation);
                    VaultManifest.FileObject file = manifest.files.get(i);
                    if (file.type == VaultManifest.FileType.CONFIG) {
                        entries.add(new Entry(file, Status.CONFIG_DEFERRED));
                    } else if (loan.matches[i] > 1) {
                        entries.add(new Entry(file, Status.AMBIGUOUS_PASSPHRASE));
                    } else {
                        byte[] passphrase = loan.matches[i] == 0 ? null
                                : loan.passphrases[loan.selected[i]];
                        byte[] wire = null;
                        try {
                            check(deadline, cancellation);
                            wire = binding.inspect(nativeType(file.type), loan.bodies[i],
                                    passphrase, deadline);
                            check(deadline, cancellation);
                            entries.add(new Entry(file, wire));
                        } finally { wipe(wire); }
                    }
                    check(deadline, cancellation);
                }
                report = new Report(entries);
            }
            // Cleanup is part of the job: expiry/cancellation during wiping cannot publish a report.
            check(deadline, cancellation);
            return report;
        } finally { ENTERED.remove(); }
    }

    private void copy(VaultManifest manifest, Loan loan, long deadline, Cancellation cancellation)
            throws IOException {
        for (int i = 0; i < manifest.files.size(); i++) {
            check(deadline, cancellation);
            VaultManifest.FileObject file = manifest.files.get(i);
            loan.bodies[i] = new byte[file.content.length];
            System.arraycopy(file.content, 0, loan.bodies[i], 0, file.content.length);
            check(deadline, cancellation);
            if (file.type != VaultManifest.FileType.PRIVATE_KEY) continue;
            String identity = "key:" + file.identity;
            for (int j = 0; j < manifest.entries.size(); j++) {
                VaultManifest.Entry entry = manifest.entries.get(j);
                if (entry.type != VaultManifest.EntryType.SSH_KEY_PASSPHRASE
                        || !identity.equals(entry.identity)) continue;
                loan.matches[i]++;
                loan.selected[i] = j;
                check(deadline, cancellation);
                if (loan.passphrases[j] == null) {
                    loan.passphrases[j] = new byte[entry.secret.length];
                    System.arraycopy(entry.secret, 0, loan.passphrases[j], 0, entry.secret.length);
                }
                check(deadline, cancellation);
            }
        }
        check(deadline, cancellation);
    }

    private long check(long deadline, Cancellation cancellation) throws IOException {
        if (cancellation.cancelled()) throw new IOException("SSH content inspection cancelled");
        long now = clock.now();
        if (now < 0 || deadline <= now || cancellation.cancelled())
            throw new IOException("SSH content inspection unavailable");
        return now;
    }

    private static int nativeType(VaultManifest.FileType type) throws IOException {
        switch (type) {
            case PRIVATE_KEY: return 2;
            case PUBLIC_KEY: return 3;
            case CERTIFICATE: return 4;
            case KNOWN_HOSTS: return 5;
            default: throw invalid();
        }
    }

    private static IOException invalid() { return new IOException("Invalid SSH content inspection"); }
    private static void wipe(byte[] bytes) { if (bytes != null) Arrays.fill(bytes, (byte)0); }
}
