package android.os;

/** Host-only placeholder; this fixture does not dispatch through Android Handler. */
public final class Handler {
    public Handler(Looper looper) { }
    public boolean post(Runnable action) { return true; }
    public boolean postDelayed(Runnable action, long delay) { return true; }
}
