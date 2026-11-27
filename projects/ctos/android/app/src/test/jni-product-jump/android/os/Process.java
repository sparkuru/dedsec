package android.os;

import im.majo.ctos.host.HostLinux;

/** Host fixture substitute for Android process identity. */
public final class Process {
    private Process() { }
    public static int myPid() { return HostLinux.pid(); }
    public static int myUid() { return HostLinux.uid(); }
}
