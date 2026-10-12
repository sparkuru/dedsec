package im.majo.ctos;

import java.util.concurrent.atomic.AtomicInteger;

/** Never reuse a launch code in this process: cancelled system UIs may return much later. */
final class VaultUiRequests {
    private static final AtomicInteger next = new AtomicInteger(1000);
    private VaultUiRequests() { }
    static int allocate() throws VaultException {
        int code = next.getAndIncrement();
        if (code > 65535) throw new VaultException("VAULT_UNAVAILABLE", "Restart the app to launch another system request");
        return code;
    }
    static boolean owns(int code) { return code >= 1000 && code < next.get() && code <= 65535; }
}
