package im.majo.ctos;

import java.io.IOException;
import java.util.Objects;

/** One descriptor handoff, including a retained native owner for failed-open cleanup. */
final class SshSocketOwnership implements AutoCloseable {
    interface Closer { void close(int descriptor) throws IOException; }

    private final Thread owner = Thread.currentThread();
    private final int descriptor;
    private final Closer closer;
    private AutoCloseable nativeOwner;
    private boolean adopted;
    private boolean closed;

    SshSocketOwnership(int descriptor, Closer closer) {
        if (descriptor < 0) throw new IllegalArgumentException("Invalid SSH socket descriptor");
        this.descriptor = descriptor;
        this.closer = Objects.requireNonNull(closer);
    }

    int descriptor() {
        requireOwner();
        if (closed || adopted) throw new IllegalStateException("SSH socket was already transferred");
        return descriptor;
    }

    void prepareNativeOwner(AutoCloseable prepared) {
        requireOwner();
        if (closed || adopted || nativeOwner != null)
            throw new IllegalStateException("SSH socket handoff was already prepared");
        nativeOwner = Objects.requireNonNull(prepared);
    }

    void nativeAdopted() {
        requireOwner();
        if (closed || adopted || nativeOwner == null)
            throw new IllegalStateException("SSH socket handoff is not prepared");
        adopted = true;
    }

    boolean adopted() { return adopted; }

    /** A failed native close retains its exact owner, never retries a numeric descriptor. */
    @Override public void close() throws Exception {
        requireOwner();
        if (closed) return;
        if (adopted) {
            nativeOwner.close();
            nativeOwner = null;
            closed = true;
        } else {
            // close(2) failure does not safely authorize a second close of a reused fd number.
            closed = true;
            nativeOwner = null;
            closer.close(descriptor);
        }
    }

    private void requireOwner() {
        if (Thread.currentThread() != owner)
            throw new IllegalStateException("SSH socket ownership belongs to its event-loop thread");
    }
}
