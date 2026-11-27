package android.system;

/** Linux UAPI constants used by the loopback-only host fixture. */
public final class OsConstants {
    public static final int AF_INET = 2, AF_INET6 = 10, SOCK_STREAM = 1, IPPROTO_TCP = 6;
    public static final int EINTR = 4, EALREADY = 114, EINPROGRESS = 115;
    public static final int POLLOUT = 4, POLLNVAL = 32;
    private OsConstants() { }
}
