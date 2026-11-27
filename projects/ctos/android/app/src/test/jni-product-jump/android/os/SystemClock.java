package android.os;

import im.majo.ctos.host.HostLinux;

/** Host fixture substitute using Linux CLOCK_BOOTTIME, as Android does. */
public final class SystemClock {
    private SystemClock() { }
    public static long elapsedRealtime() { return HostLinux.elapsedRealtime(); }
}
