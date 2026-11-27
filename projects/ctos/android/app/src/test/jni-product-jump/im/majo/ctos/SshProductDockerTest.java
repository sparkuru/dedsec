package im.majo.ctos;

import android.os.SystemClock;
import im.majo.ctos.host.HostLinux;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;

/** Real connector/vault/JNI against loopback sshd; the host Android APIs are fixture shims. */
public final class SshProductDockerTest {
    private static final String ADDRESS = "127.0.0.1";
    private static final int JUMP_PORT = 22221, TARGET_PORT = 22222;
    private static final String JUMP_USER = "ctosjump", TARGET_USER = "ctostarget";
    private static final long[] CONFIRM_DELAYS_MS = {3500, 1200};
    private static final char[] VAULT_PASSWORD = "disposable local fixture vault".toCharArray();

    private static final class Store implements AppSecrets.Store {
        final byte[] encrypted;
        int commits;
        Store(byte[] encrypted) { this.encrypted = encrypted; }
        @Override public byte[] read() { return encrypted.clone(); }
        @Override public byte[] quickMaterial() { return null; }
        @Override public void commit(byte[] bytes, byte[] quick, AppSecrets.EffectGuard guard) {
            commits++;
            throw new AssertionError("unexpected vault commit");
        }
    }

    private static final class Events implements SshPasswordConnector.Events {
        final String mode;
        final List<Thread> workers = new ArrayList<>();
        final AtomicReference<Throwable> workerFailure = new AtomicReference<>();
        final boolean[] confirmed = new boolean[2];
        final long[] elapsed = new long[2];
        volatile SshPasswordConnector connector;
        volatile boolean targetCancelled;
        int passwordPrompts;

        Events(String mode) { this.mode = mode; }

        @Override public void present(SshHostKeyReviewMailbox.Ticket ticket) {
            throw new AssertionError("unexpected host-key approval request");
        }
        @Override public void dismiss(SshHostKeyReviewMailbox.Ticket ticket) { }
        @Override public void present(SshPasswordMailbox.Ticket ticket) {
            require(mode.startsWith("jump"), "unexpected password prompt");
            int hop = passwordPrompts++;
            require(hop < 2 && ticket.hop == hop && ticket.savedOnly && ticket.savedAvailable
                    && ticket.purpose == SshPasswordMailbox.Purpose.PASSWORD
                    && ADDRESS.equals(ticket.host)
                    && ticket.port == (hop == 0 ? JUMP_PORT : TARGET_PORT)
                    && (hop == 0 ? JUMP_USER : TARGET_USER).equals(ticket.username),
                    "wrong saved-password prompt or hop order");
            Thread worker = new Thread(() -> {
                try {
                    long start = System.nanoTime();
                    long until = start + TimeUnit.MILLISECONDS.toNanos(CONFIRM_DELAYS_MS[hop]);
                    for (;;) {
                        long remaining = until - System.nanoTime();
                        if (remaining <= 0) break;
                        TimeUnit.NANOSECONDS.sleep(remaining);
                    }
                    elapsed[hop] = System.nanoTime() - start;
                    if ("jump-cancel".equals(mode) && hop == 1) {
                        connector.cancel();
                        targetCancelled = true;
                    } else {
                        confirmed[hop] = connector.respondPassword(ticket.id, ticket.binding,
                                new byte[0], SshPasswordMailbox.Choice.SAVED);
                        require(confirmed[hop], "saved-password confirmation was rejected");
                    }
                } catch (Throwable failure) { workerFailure.compareAndSet(null, failure); }
            }, "fixture-saved-confirmation-" + hop);
            worker.setDaemon(true);
            workers.add(worker);
            worker.start();
        }

        void awaitWorkers() throws Exception {
            for (Thread worker : workers) {
                worker.join(10_000);
                require(!worker.isAlive(), "saved-password confirmation did not finish");
            }
            Throwable failure = workerFailure.get();
            if (failure != null) throw new AssertionError("saved-password confirmation failed", failure);
        }

        void requirePrompts(int count, boolean cancelledTarget) {
            require(passwordPrompts == count, "wrong number of saved-password prompts");
            for (int hop = 0; hop < count; hop++) {
                require(elapsed[hop] >= TimeUnit.MILLISECONDS.toNanos(CONFIRM_DELAYS_MS[hop]),
                        "saved-password confirmation was not delayed long enough");
                require(confirmed[hop] == !(cancelledTarget && hop == 1), "wrong confirmation result");
            }
            require(targetCancelled == cancelledTarget, "wrong target cancellation result");
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    private static String publicKey(Path path) throws Exception {
        String[] fields = Files.readString(path, StandardCharsets.US_ASCII).trim().split("\\s+");
        require(fields.length >= 2 && "ssh-ed25519".equals(fields[0]) && fields[1].matches("[A-Za-z0-9+/=]+"),
                "unexpected fixture public key");
        return fields[0] + " " + fields[1];
    }

    private static Store encryptedStore(String mode, byte[] jumpSecret, byte[] targetSecret,
            String jumpKey, String targetKey) throws Exception {
        SshSavedPassword jumpPassword = new SshSavedPassword(new SshPasswordProfile(ADDRESS, JUMP_PORT, JUMP_USER));
        SshSavedPassword targetPassword = new SshSavedPassword(new SshPasswordProfile(ADDRESS, TARGET_PORT, TARGET_USER));
        String config = "Host jump\n HostName " + ADDRESS + "\n Port " + JUMP_PORT + "\n User " + JUMP_USER
                + "\n UserKnownHostsFile ~/.ssh/known_hosts\n"
                + ("jump-unknown-root".equals(mode) ? " StrictHostKeyChecking ask\n" : " StrictHostKeyChecking yes\n")
                + ("multi-jump".equals(mode) ? "Host other\n HostName " + ADDRESS
                        + "\n Port 22223\n User ctosother\n UserKnownHostsFile ~/.ssh/known_hosts\n" : "")
                + "Host target\n HostName " + ADDRESS + "\n Port " + TARGET_PORT + "\n User " + TARGET_USER
                + "\n UserKnownHostsFile ~/.ssh/known_hosts\n"
                + ("jump-unknown".equals(mode) ? " StrictHostKeyChecking ask\n" : " StrictHostKeyChecking yes\n")
                + ("gate".equals(mode) ? " ProxyJump jump\n"
                        : "direct-none".equals(mode) ? " ProxyJump none\n"
                        : "multi-jump".equals(mode) ? " ProxyJump jump,other\n"
                        : mode.startsWith("jump") ? " ProxyJump jump\n ConnectTimeout 2\n" : "");
        byte[] configBytes = config.getBytes(StandardCharsets.US_ASCII);
        byte[] hosts = (("jump-unknown-root".equals(mode) ? ""
                : "[" + ADDRESS + "]:" + JUMP_PORT + " " + jumpKey + "\n")
                + ("jump-unknown".equals(mode) ? ""
                        : "[" + ADDRESS + "]:" + TARGET_PORT + " " + targetKey + "\n"))
                .getBytes(StandardCharsets.US_ASCII);
        byte[] salt = new byte[16], key = null, plain = null;
        Arrays.fill(salt, (byte)7);
        try (VaultManifest manifest = new VaultManifest(List.of(
                new VaultManifest.Entry(VaultManifest.EntryType.SSH_PASSWORD, jumpPassword.entryId,
                        jumpPassword.target, "Jump", "", 0, 0, jumpSecret),
                new VaultManifest.Entry(VaultManifest.EntryType.SSH_PASSWORD, targetPassword.entryId,
                        targetPassword.target, "Target", "", 0, 0, targetSecret)), List.of(
                new VaultManifest.FileObject(VaultManifest.FileType.CONFIG,
                        SshConnectionMaterial.id("config", SshConnectionMaterial.ROOT), SshConnectionMaterial.ROOT,
                        SshConnectionMaterial.id("config-owner", SshConnectionMaterial.ROOT), configBytes,
                        List.of(SshConnectionMaterial.HOSTS)),
                new VaultManifest.FileObject(VaultManifest.FileType.KNOWN_HOSTS,
                        SshConnectionMaterial.id("hosts", SshConnectionMaterial.HOSTS), SshConnectionMaterial.HOSTS,
                        SshConnectionMaterial.id("hosts-owner", SshConnectionMaterial.HOSTS), hosts, List.of())),
                List.of(), List.of(jumpPassword.account(), targetPassword.account()))) {
            key = VaultEnvelope.derive(VAULT_PASSWORD, salt);
            plain = manifest.encode();
            return new Store(VaultEnvelope.encrypt(UUID.randomUUID(), 7, salt, key, plain).encode());
        } finally {
            VaultCodec.wipe(configBytes); VaultCodec.wipe(hosts); VaultCodec.wipe(salt);
            VaultCodec.wipe(key); VaultCodec.wipe(plain);
        }
    }

    private static String selectionBinding(AppSecrets vault, long deadline, boolean checkList) throws Exception {
        AppSecrets.BundleContext context = vault.newBundleContext(deadline, () -> { });
        try (SshConnectionMaterial.Snapshot snapshot = vault.sshLibrarySnapshot(context)) {
            String binding = SshConnectionMaterial.configBinding(snapshot, "target");
            if (!checkList) return binding;
            VaultManifest.FileObject config = SshConnectionMaterial.file(snapshot.manifest, SshConnectionMaterial.ROOT);
            require(config != null && new String(config.content, StandardCharsets.US_ASCII)
                    .contains("\n ProxyJump none\n"), "explicit ProxyJump none is missing");
            SshConnectionMaterial.CatalogScan scan = SshConnectionMaterial.catalog(snapshot.manifest,
                    SshConnectionMaterial.ROOT, deadline, () -> { });
            require(scan.catalog.entries.stream().anyMatch(entry -> "target".equals(entry.alias)),
                    "target alias is missing from the production catalog");
            int count = Math.max(1, scan.catalog.entries.size());
            SshConnectionMaterial.Limits share = new SshConnectionMaterial.Limits(
                    (SshConfigEvaluator.MAX_OPENS - scan.usage.opens) / count,
                    (SshConfigEvaluator.MAX_READ_BYTES - scan.usage.readBytes) / count,
                    (SshConfigEvaluator.MAX_LINES - scan.usage.lines) / count);
            SshConfigEvaluator.ProfilePlan plan = SshConnectionMaterial.profilePlan(snapshot.manifest,
                    SshConnectionMaterial.ROOT, "target", "", deadline, () -> { }, share);
            Method rowFactory = Class.forName("im.majo.ctos.SshProfilesBridge").getDeclaredMethod(
                    "profileHostRow", String.class, String.class, SshConfigEvaluator.ProfilePlan.class, List.class);
            rowFactory.setAccessible(true);
            Object result = rowFactory.invoke(null, "target", binding, plan, List.of());
            require(result instanceof Map<?, ?>, "production host row is unavailable");
            Map<?, ?> row = (Map<?, ?>)result;
            require("target".equals(row.get("alias")) && ADDRESS.equals(row.get("host"))
                    && Integer.valueOf(TARGET_PORT).equals(row.get("port"))
                    && TARGET_USER.equals(row.get("username")) && binding.equals(row.get("binding"))
                    && !row.containsKey("error"), "ProxyJump none did not produce a selectable target row");
            System.out.println("PRODUCT_DOCKER list=production-catalog-plan-row bridge-lifecycle=not-run");
            return binding;
        } finally { vault.cancelBundleContext(context); }
    }

    private static void verifyJumpTargetConfig(AppSecrets vault, long deadline) throws Exception {
        AppSecrets.BundleContext context = vault.newBundleContext(deadline, () -> { });
        try (SshConnectionMaterial.Snapshot snapshot = vault.sshLibrarySnapshot(context)) {
            SshConfigEvaluator.ProfileRoute route = SshConnectionMaterial.profileRoute(snapshot.manifest,
                    SshConnectionMaterial.ROOT, "target", "", deadline, () -> { });
            require(route.entries.size() == 2, "expected one jump and a target");
            SshConfigEvaluator.ProfilePlan jump = route.entries.get(0), target = route.entries.get(1);
            require(ADDRESS.equals(jump.hostname) && jump.port == JUMP_PORT && JUMP_USER.equals(jump.user)
                    && ADDRESS.equals(target.hostname) && target.port == TARGET_PORT && TARGET_USER.equals(target.user)
                    && target.connectTimeoutMillis == 2000, "wrong per-hop endpoint or target ConnectTimeout");
        } finally { vault.cancelBundleContext(context); }
    }

    private static void verifyViewReopens(AppSecrets vault, long deadline) throws Exception {
        SshVaultAdapter files = new SshVaultAdapter(vault, (connection, target) -> { });
        AppSecrets.SshPublicReadContext context = files.fileContext(deadline, () -> { });
        VaultOperation read = files.fileRequest(SshConnectionMaterial.ROOT, "fixture-cleanup", context);
        SshPublicFileLease view;
        try { view = files.openPublicFiles(read, SshConnectionMaterial.ROOT, "fixture-cleanup"); }
        catch (Exception | Error failure) { files.cancel(read); throw failure; }
        try { require(view.active() && view.nativeToken() > 0, "public view did not reopen"); }
        finally { view.close(); }
    }

    private static Map<String, Integer> nativeDescriptors() throws IOException {
        Map<String, Integer> result = new HashMap<>();
        try (Stream<Path> entries = Files.list(Path.of("/proc/self/fd"))) {
            for (Path entry : (Iterable<Path>)entries::iterator) {
                try {
                    String target = Files.readSymbolicLink(entry).toString();
                    if (target.startsWith("socket:[") || target.contains("memfd:ctos-ssh-public"))
                        result.merge(target, 1, Integer::sum);
                } catch (NoSuchFileException disappeared) { /* A concurrent JVM descriptor closed. */ }
            }
        }
        return result;
    }

    private static void requireDescriptorsReleased(Map<String, Integer> original) throws IOException {
        for (Map.Entry<String, Integer> entry : nativeDescriptors().entrySet())
            require(entry.getValue() <= original.getOrDefault(entry.getKey(), 0),
                    "a socket or managed native view remains open after cleanup");
    }

    private static String fingerprint(String publicKey) throws Exception {
        byte[] blob = Base64.getDecoder().decode(publicKey.split(" ")[1]);
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(blob);
        return "SHA256:" + Base64.getEncoder().withoutPadding().encodeToString(digest);
    }

    private static void requireTrustEvidence(SshPasswordConnector connector, String actualTargetKey,
            SshHostKeyAuthority.Classification classification)
            throws Exception {
        Field hopsField = SshPasswordConnector.class.getDeclaredField("jumpHops");
        hopsField.setAccessible(true);
        Object value = hopsField.get(connector);
        require(value instanceof List<?> && ((List<?>)value).size() == 2, "target hop was not constructed");
        Object child = ((List<?>)value).get(1);
        Field callbackField = child.getClass().getDeclaredField("callback");
        callbackField.setAccessible(true);
        SshNativeCallbackAdapter callback = (SshNativeCallbackAdapter)callbackField.get(child);
        require(callback != null, "target callback is missing");
        Field evidenceField = SshNativeCallbackAdapter.class.getDeclaredField("childTrustEvidence");
        evidenceField.setAccessible(true);
        SshHostKeyAuthority.Evidence evidence = (SshHostKeyAuthority.Evidence)evidenceField.get(callback);
        require(evidence != null && evidence.classification == classification
                && evidence.role.hop == 1 && ADDRESS.equals(evidence.role.host) && evidence.role.port == TARGET_PORT
                && "ssh-ed25519".equals(evidence.keyType) && fingerprint(actualTargetKey).equals(evidence.fingerprint),
                "actual target host-key classification was not observed");
    }

    private static void runCommand(SshNativeSession session, SshNativeSession.TerminalChain chain,
            long deadline, boolean requirePty) throws Exception {
        session.beginShell(80, 24);
        SshNativeSession.ShellStatus status;
        while ((status = chain.status()).phase != SshNativeSession.ShellPhase.RUNNING) {
            require(!status.phase.terminal() && SystemClock.elapsedRealtime() < deadline, "shell did not start");
            chain.step(); Thread.sleep(5);
        }
        String text = "printf '\\nCTOS_PRODUCT_TARGET_%s\\n' \"$(id -un)\"\n"
                + (requirePty ? "if [ -t 0 ]; then printf '\\nCTOS_PRODUCT_PTY_YES\\n'; fi\n" : "");
        byte[] command = text.getBytes(StandardCharsets.US_ASCII);
        int offset = 0;
        while (offset < command.length) {
            require(SystemClock.elapsedRealtime() < deadline, "shell input timed out");
            offset += session.writeShell(Arrays.copyOfRange(command, offset, command.length));
            chain.step(); Thread.sleep(5);
        }
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        while (SystemClock.elapsedRealtime() < deadline && output.size() < 65536) {
            chain.step();
            status = chain.status();
            if (status.bufferedBytes > 0) output.write(session.readShell(Math.min(status.bufferedBytes, 32768)));
            String normalized = output.toString(StandardCharsets.UTF_8).replace("\r", "");
            List<String> lines = Arrays.asList(normalized.split("\n"));
            if (lines.contains("CTOS_PRODUCT_TARGET_ctostarget")
                    && (!requirePty || lines.contains("CTOS_PRODUCT_PTY_YES"))) return;
            require(!status.phase.terminal(), "target shell closed before command output");
            Thread.sleep(5);
        }
        throw new AssertionError("target shell command output was not observed");
    }

    private static void run(String mode, byte[] jumpSecret, byte[] targetSecret,
            String jumpKey, String targetKey, String trustedTargetKey) throws Exception {
        boolean gate = "gate".equals(mode);
        boolean directNone = "direct-none".equals(mode);
        boolean jump = "jump".equals(mode), changed = "jump-changed".equals(mode);
        boolean unknown = "jump-unknown".equals(mode), unknownRoot = "jump-unknown-root".equals(mode);
        boolean cancel = "jump-cancel".equals(mode);
        boolean multi = "multi-jump".equals(mode);
        require(gate || "direct".equals(mode) || directNone || jump || changed || unknown || unknownRoot || cancel || multi,
                "unsupported fixture mode");
        Store store = encryptedStore(mode, jumpSecret, targetSecret, jumpKey, trustedTargetKey);
        AppSecrets vault = new AppSecrets(store, SystemClock::elapsedRealtime);
        SshPasswordConnector connector = null;
        SshNativeSession.TerminalChain chain = null;
        Map<String, Integer> descriptors = null;
        Events events = null;
        boolean unlocked = false;
        try {
            vault.attachSshValidator(new SshPasswordManifestValidator());
            vault.session.foreground(true);
            vault.unlock(VAULT_PASSWORD, () -> { });
            unlocked = true;
            long deadline = SystemClock.elapsedRealtime() + 55_000;
            String binding = selectionBinding(vault, deadline, directNone);
            if (jump || changed || unknown || unknownRoot || cancel) verifyJumpTargetConfig(vault, deadline);
            events = new Events(mode);
            HostLinux.resetSocketEvidence();
            nativeDescriptors();
            descriptors = nativeDescriptors();
            if (gate || multi || jump || changed || unknown || unknownRoot || cancel) {
                connector = new SshPasswordConnector(vault, null, "target", binding, "", deadline, () -> { }, events);
                events.connector = connector;
                connector.useServerDrivenAuthentication();
                SshNativeSession target = null;
                VaultException rejected = null;
                try {
                    target = connector.create(() -> false);
                } catch (VaultException expected) { rejected = expected; }
                if (target != null) chain = SshNativeSession.terminalChain(target);
                events.awaitWorkers();
                if (gate || multi) {
                    String code = gate ? "SSH_JUMP_AUTH_UNSUPPORTED" : "SSH_JUMP_CHAIN_UNSUPPORTED";
                    require(rejected != null && code.equals(rejected.code), "unexpected route refusal");
                    require(HostLinux.socketCount() == 0 && events.passwordPrompts == 0,
                            "unsupported route attempted network or a password prompt");
                } else if (unknownRoot) {
                    require(rejected != null && "SSH_JUMP_HOST_TRUST_REQUIRED".equals(rejected.code),
                            "untrusted jump did not report its host-trust failure");
                    require(events.passwordPrompts == 0 && HostLinux.socketCount() == 1,
                            "untrusted jump requested a password or used an unexpected socket");
                } else if (changed || unknown) {
                    require(rejected != null && "SSH_JUMP_HOST_TRUST_REQUIRED".equals(rejected.code),
                            "untrusted target did not report its host-trust failure");
                    events.requirePrompts(1, false);
                    requireTrustEvidence(connector, targetKey, changed
                            ? SshHostKeyAuthority.Classification.CHANGED : SshHostKeyAuthority.Classification.NEW);
                    require(HostLinux.socketCount() == 1, "untrusted target did not use the jump connection");
                } else if (cancel) {
                    require(rejected != null && "SSH_CANCELLED".equals(rejected.code),
                            "target prompt cancellation did not report SSH_CANCELLED");
                    events.requirePrompts(2, true);
                    require(HostLinux.socketCount() == 1, "cancelled target did not use the jump connection");
                } else {
                    require(rejected == null && target != null, "single-jump connection was not established");
                    events.requirePrompts(2, false);
                    runCommand(target, chain, deadline, true);
                    require(HostLinux.socketCount() == 1, "jump connection opened unexpected TCP sockets");
                }
            } else {
                connector = new SshPasswordConnector(vault, "target", binding, "", targetSecret,
                        false, false, deadline, () -> { }, events);
                SshNativeSession target = connector.create(() -> false);
                chain = SshNativeSession.terminalChain(target);
                runCommand(target, chain, deadline, false);
                require(HostLinux.socketCount() == 1 && events.passwordPrompts == 0,
                        "unexpected direct socket or prompt count");
            }
            require(store.commits == 0, "unexpected vault write");
        } finally {
            try {
                try { if (events != null) events.awaitWorkers(); }
                finally {
                    if (chain != null) chain.close();
                    if (connector != null) connector.close();
                }
                int socket = HostLinux.transferredSocket();
                if (socket >= 0) require(HostLinux.descriptorClosed(socket), "transferred TCP socket remains open");
                if (descriptors != null) requireDescriptorsReleased(descriptors);
                if (unlocked) verifyViewReopens(vault, SystemClock.elapsedRealtime() + 20_000);
            } finally {
                vault.session.lock();
                VaultCodec.wipe(store.encrypted);
            }
        }
        System.out.println("PRODUCT_DOCKER mode=" + mode + " outcome="
                + (gate ? "jump-gate-rejected-before-socket" : multi ? "multi-rejected-before-socket"
                        : changed ? "changed-before-target-auth" : unknown ? "unknown-before-target-auth"
                        : unknownRoot ? "unknown-jump-before-auth"
                        : cancel ? "target-prompt-cancelled"
                        : jump ? "jump-target-pty-command" : "direct-target-command")
                + " cleanup=pass commits=0 peer=real-sshd platform=host-jni");
    }

    public static void main(String[] args) throws Exception {
        require(args.length == 3 || args.length == 4, "expected mode and public-key paths");
        require("jump-changed".equals(args[0]) == (args.length == 4), "wrong host-key fixture arguments");
        byte[] secrets = System.in.readNBytes(97);
        require(secrets.length == 96, "expected two fixture secrets on stdin");
        byte[] jumpSecret = Arrays.copyOfRange(secrets, 0, 48);
        byte[] targetSecret = Arrays.copyOfRange(secrets, 48, 96);
        try {
            require(!MessageDigest.isEqual(jumpSecret, targetSecret), "fixture passwords must differ");
            String jumpKey = publicKey(Path.of(args[1])), targetKey = publicKey(Path.of(args[2]));
            require(!jumpKey.equals(targetKey), "fixture host keys must differ");
            String trustedTargetKey = args.length == 4 ? publicKey(Path.of(args[3])) : targetKey;
            require(args.length != 4 || !trustedTargetKey.equals(targetKey), "wrong target key is not distinct");
            run(args[0], jumpSecret, targetSecret, jumpKey, targetKey, trustedTargetKey);
        } finally {
            VaultCodec.wipe(secrets); VaultCodec.wipe(jumpSecret); VaultCodec.wipe(targetSecret);
            Arrays.fill(VAULT_PASSWORD, '\0');
        }
    }
}
