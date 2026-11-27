package android.system;

/** Host fixture exception matching the Android API needed by SshTcpConnector. */
public final class ErrnoException extends Exception {
    public final int errno;
    public ErrnoException(String function, int errno) {
        super(function + " failed with errno " + errno);
        this.errno = errno;
    }
}
