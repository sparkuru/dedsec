package im.majo.ctos;

final class Pty {
    static { System.loadLibrary("ctos_pty"); }
    private Pty() {}
    static native int[] start(boolean root, int columns, int rows, String directory);
    static native byte[] read(int fd);
    static native void write(int fd, byte[] data);
    static native void resize(int fd, int columns, int rows);
    static native void close(int fd);
    static native int waitFor(int pid);
}
