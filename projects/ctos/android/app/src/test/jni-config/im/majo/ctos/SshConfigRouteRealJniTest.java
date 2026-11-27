package im.majo.ctos;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

/** Linux host JVM exercising the production evaluator, JNI and pinned parser. */
public final class SshConfigRouteRealJniTest {
    private interface Work { void run() throws Exception; }

    private static void check(boolean condition) {
        if (!condition) throw new AssertionError();
    }

    private static void rejected(Work action) throws Exception {
        try { action.run(); throw new AssertionError("expected refusal"); }
        catch (IOException expected) { }
    }

    private static void bytes(DataOutputStream out, String value) throws IOException {
        byte[] encoded = value.getBytes(StandardCharsets.UTF_8);
        out.writeInt(encoded.length);
        out.write(encoded);
    }

    private static byte[] source(String config) throws IOException {
        ByteArrayOutputStream result = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(result);
        out.writeInt(0x43534449); out.writeInt(1); out.writeInt(2);
        out.writeInt(1024); out.writeInt(64 * 1024 * 1024); out.writeInt(16384);
        out.writeInt(1); bytes(out, ".ssh/config"); bytes(out, config);
        out.writeInt(5); bytes(out, ".ssh/known_hosts"); bytes(out, "");
        return result.toByteArray();
    }

    private static long deadline() throws IOException {
        String uptime = Files.readString(Path.of("/proc/uptime")).split(" ", 2)[0];
        return (long)(Double.parseDouble(uptime) * 1000) + 30000;
    }

    private static SshConfigEvaluator.Request request() throws IOException {
        return new SshConfigEvaluator.Request("target", "", "", "localuser", "local.example",
                "", "", 0, 1000);
    }

    private static SshConfigEvaluator open(String config) throws Exception {
        SshConfigEvaluator evaluator = SshConfigEvaluator.prepareProfileRouteDetached(() -> { });
        try {
            evaluator.loadDetached(source(config), 0, deadline(), request());
            return evaluator;
        } catch (Exception error) {
            evaluator.close();
            throw error;
        }
    }

    public static void main(String[] args) throws Exception {
        String config = "Host target\n HostName target.example\n User carol\n ProxyJump alice@one:2201,two\n"
                + "Host one\n HostName first.example\n IdentityFile /source/first\n PasswordAuthentication no\n"
                + "Host two\n HostName second.example\n User bob\n Port 2202\n IdentityFile /source/second\n"
                + "Host *\n UserKnownHostsFile ~/.ssh/known_hosts\n";
        try (SshConfigEvaluator evaluator = open(config)) {
            evaluator.run();
            SshConfigEvaluator.ProfileRoute route = evaluator.profileRoute();
            check(route.entries.size() == 3 && route.jumps().size() == 2);
            check(route.entries.get(0).hostname.equals("first.example"));
            check(route.entries.get(0).user.equals("alice") && route.entries.get(0).port == 2201);
            check(route.entries.get(0).identityHints.equals(List.of("/source/first")));
            check(!route.entries.get(0).passwordAuthentication && route.entries.get(0).requestTty == 1);
            check(route.entries.get(1).hostname.equals("second.example"));
            check(route.entries.get(1).user.equals("bob") && route.entries.get(1).port == 2202);
            check(route.target().hostname.equals("target.example") && route.target().user.equals("carol"));
            check(route.target().userKnownHostsFiles.equals(List.of(".ssh/known_hosts")));
            rejected(evaluator::profileDirectPlan);
            AtomicReference<Throwable> wrongThread = new AtomicReference<>();
            Thread thread = new Thread(() -> {
                try { evaluator.profileRoute(); wrongThread.set(new AssertionError("owner fence")); }
                catch (IOException expected) { }
                catch (Throwable error) { wrongThread.set(error); }
            });
            thread.start(); thread.join();
            if (wrongThread.get() != null) throw new AssertionError(wrongThread.get());
            AtomicReference<Throwable> cancellation = new AtomicReference<>();
            thread = new Thread(() -> {
                try { evaluator.cancel(); }
                catch (Throwable error) { cancellation.set(error); }
            });
            thread.start(); thread.join();
            if (cancellation.get() != null) throw new AssertionError(cancellation.get());
            rejected(evaluator::profileRoute);
        }
        try (SshConfigEvaluator evaluator = open("Host target\n User carol\n")) {
            evaluator.run();
            check(evaluator.profileRoute().jumps().isEmpty());
        }
        try (SshConfigEvaluator evaluator = open("Host target\n ProxyJump one\nHost one\n"
                + " LocalForward 3300 example:22\n")) {
            rejected(evaluator::run);
            rejected(evaluator::profileRoute);
        }
        try (SshConfigEvaluator evaluator = SshConfigEvaluator.prepareProfileDirectDetached(() -> { })) {
            evaluator.loadDetached(source(config), 0, deadline(), request());
            rejected(evaluator::run);
        }
        System.out.println("CONFIG_ROUTE_REAL_JNI double-hop=pass per-hop=pass no-hop=pass"
                + " policy=pass owner=pass cancel=pass cleanup=pass");
    }
}
