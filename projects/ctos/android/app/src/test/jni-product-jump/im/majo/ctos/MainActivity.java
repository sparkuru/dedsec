package im.majo.ctos;

/** Host-only shell for the Android Activity's marker interface; no factory implementation. */
final class MainActivity {
    interface AuthenticatedSshFactory extends SshSessionOwner.Factory { }
}
