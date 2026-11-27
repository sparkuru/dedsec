package android.os;

/** The host fixture does not run Android's Looper. */
public final class Looper {
    public static Looper getMainLooper() { return new Looper(); }
}
