package im.majo.ctos;

/** Publishes a preview owner only after inheritance, and retires it if submission fails. */
final class SshPreviewHandoff {
    interface Inheritance { void run() throws VaultException; }

    static void submit(Inheritance inherit, Runnable attach, Runnable schedule, Runnable abort)
            throws VaultException {
        boolean submitted = false;
        try {
            inherit.run();
            attach.run();
            schedule.run();
            submitted = true;
        } finally {
            if (!submitted) abort.run();
        }
    }

    private SshPreviewHandoff() { }
}
