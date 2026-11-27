package android.content;

/** Host-only substitute: the requested service is unavailable, so the screen is locked. */
public class Context {
    public <T> T getSystemService(Class<T> type) { return null; }
}
