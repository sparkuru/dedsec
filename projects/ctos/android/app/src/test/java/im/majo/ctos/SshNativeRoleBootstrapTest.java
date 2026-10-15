package im.majo.ctos;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import org.junit.Test;

/** Wire/registry contract tests; no executable, shell, secret, or endpoint is used. */
public final class SshNativeRoleBootstrapTest {
    private static final class Clock implements SshRoleContext.Clock {
        long now = 1_000;
        @Override public long now() { return now; }
    }

    private static final class View implements SshRoleContext.PublicView {
        boolean active = true;
        @Override public boolean active() { return active; }
    }

    @Test public void decodesAndIssuesOnlyWithExplicitNativeOwnership() throws Exception {
        Clock clock = new Clock();
        byte[] wire = record(7, 42, 1001, 1, 3, 22, 30_000,
                "connection_7", "target:user@example.invalid:22", "example.invalid", "view_7",
                4, 5);
        SshNativeRoleBootstrap.Decoded decoded = SshNativeRoleBootstrap.decode(wire, clock);
        assertEquals(7, decoded.token);
        assertEquals(42, decoded.ownerUid);
        assertEquals(1001, decoded.bootstrap.ownerPid);
        assertEquals(3, decoded.bootstrap.hop);
        assertEquals("connection_7", decoded.bootstrap.connectionId);
        assertEquals(SshRoleContext.Program.CLIENT, decoded.bootstrap.program);

        SshRoleRegistry registry = new SshRoleRegistry(clock);
        SshNativeRoleBootstrap.Issued issued = SshNativeRoleBootstrap.issue(wire, registry,
                new View(), clock, record -> record.token == 7 && record.ownerUid == 42
                        && record.bootstrap.ownerPid == 1001
                        && record.bootstrap.hop == 3
                        && record.bootstrap.connectionId.equals("connection_7")
                        && record.bootstrap.targetIdentity.equals("target:user@example.invalid:22")
                        && record.bootstrap.host.equals("example.invalid")
                        && record.bootstrap.port == 22
                        && record.bootstrap.publicViewId.equals("view_7")
                        && assertBytes(record.bootstrap.roleNonce, 4)
                        && assertBytes(record.bootstrap.connectionBinding, 5));
        try {
            assertEquals(7, issued.token);
            assertEquals(42, issued.ownerUid);
            assertNotNull(issued.role);
            assertEquals("view_7", issued.role.publicViewId);
            assertTrue(issued.role.active());
        } finally {
            registry.release(issued.role);
        }
    }

    @Test public void decodedRetainsAnImmutableCanonicalWireCopy() throws Exception {
        Clock clock = new Clock();
        byte[] wire = record(14, 42, 1001, 1, 0, 22, 30_000,
                "connection_14", "target:user@example.invalid:22", "example.invalid", "view_14",
                8, 9);
        byte[] expected = wire.clone();
        SshNativeRoleBootstrap.Decoded decoded = SshNativeRoleBootstrap.decode(wire, clock);

        wire[0] = 0;
        assertArrayEquals(expected, decoded.canonicalWire());
        byte[] returned = decoded.canonicalWire();
        returned[0] = 0;
        assertArrayEquals(expected, decoded.canonicalWire());
    }

    @Test public void productionOwnershipFactoryIsExplicitAndDoesNotLoadNativeForHostTests() {
        assertNotNull(SshNativeRoleBootstrap.productionOwnership());
        assertNotNull(SshNativeRoleBootstrap.productionRelease());
    }

    @Test public void successfulReleaseClosesNativeBeforeJavaRole() throws Exception {
        Clock clock = new Clock();
        byte[] wire = record(15, 42, 1001, 1, 0, 22, 30_000,
                "connection_15", "target:user@example.invalid:22", "example.invalid", "view_15",
                4, 5);
        SshRoleRegistry registry = new SshRoleRegistry(clock);
        SshNativeRoleBootstrap.Issued issued = SshNativeRoleBootstrap.issue(wire, registry,
                new View(), clock, record -> true);
        try {
            final boolean[] called = {false};
            SshNativeRoleBootstrap.release(issued, registry, (token, ownerUid) -> {
                assertEquals(15, token);
                assertEquals(42, ownerUid);
                assertEquals(1, registry.size());
                assertTrue(issued.role.active());
                called[0] = true;
            });
            assertTrue(called[0]);
            assertEquals(0, registry.size());
            assertFalse(issued.role.active());
        } finally {
            registry.close();
        }
    }

    @Test public void failedNativeReleaseKeepsJavaRoleAndCanBeRetried() throws Exception {
        Clock clock = new Clock();
        byte[] wire = record(16, 42, 1001, 1, 0, 22, 30_000,
                "connection_16", "target:user@example.invalid:22", "example.invalid", "view_16",
                4, 5);
        SshRoleRegistry registry = new SshRoleRegistry(clock);
        SshNativeRoleBootstrap.Issued issued = SshNativeRoleBootstrap.issue(wire, registry,
                new View(), clock, record -> true);
        try {
            try {
                SshNativeRoleBootstrap.release(issued, registry, (token, ownerUid) -> {
                    assertEquals(16, token);
                    assertEquals(42, ownerUid);
                    throw new IOException("native close failed");
                });
                fail("native cleanup failure must be visible");
            } catch (VaultException failure) {
                assertEquals("SSH_ROLE_REQUIRED", failure.code);
            }
            assertEquals(1, registry.size());
            assertTrue(issued.role.active());

            SshNativeRoleBootstrap.release(issued, registry, (token, ownerUid) -> {
                assertEquals(16, token);
                assertEquals(42, ownerUid);
            });
            assertEquals(0, registry.size());
            assertFalse(issued.role.active());
        } finally {
            registry.close();
        }
    }

    @Test public void linkageFailureAlsoLeavesJavaRoleRegistered() throws Exception {
        Clock clock = new Clock();
        byte[] wire = record(17, 42, 1001, 1, 0, 22, 30_000,
                "connection_17", "target:user@example.invalid:22", "example.invalid", "view_17",
                4, 5);
        SshRoleRegistry registry = new SshRoleRegistry(clock);
        SshNativeRoleBootstrap.Issued issued = SshNativeRoleBootstrap.issue(wire, registry,
                new View(), clock, record -> true);
        try {
            try {
                SshNativeRoleBootstrap.release(issued, registry, (token, ownerUid) -> {
                    throw new UnsatisfiedLinkError("fixture does not load JNI");
                });
                fail("linkage failure must be visible");
            } catch (VaultException failure) {
                assertEquals("SSH_ROLE_REQUIRED", failure.code);
            }
            assertEquals(1, registry.size());
            assertTrue(issued.role.active());
        } finally {
            registry.close();
        }
    }

    @Test public void mismatchedReleaseInputCannotTouchNativeOrJavaRole() throws Exception {
        Clock clock = new Clock();
        byte[] wire = record(18, 42, 1001, 1, 0, 22, 30_000,
                "connection_18", "target:user@example.invalid:22", "example.invalid", "view_18",
                4, 5);
        SshRoleRegistry registry = new SshRoleRegistry(clock);
        SshRoleRegistry otherRegistry = new SshRoleRegistry(clock);
        SshNativeRoleBootstrap.Issued issued = SshNativeRoleBootstrap.issue(wire, registry,
                new View(), clock, record -> true);
        try {
            try {
                SshNativeRoleBootstrap.release(issued, otherRegistry, (token, ownerUid) ->
                        fail("a mismatched registry must not invoke native cleanup"));
                fail("mismatched registry must be rejected");
            } catch (VaultException failure) {
                assertEquals("SSH_ROLE_REQUIRED", failure.code);
            }
            assertEquals(1, registry.size());
            assertTrue(issued.role.active());

            try {
                SshNativeRoleBootstrap.release(null, registry, (token, ownerUid) ->
                        fail("malformed lifecycle input must not invoke native cleanup"));
                fail("null issued record must be rejected");
            } catch (VaultException failure) {
                assertEquals("SSH_ROLE_REQUIRED", failure.code);
            }
            assertEquals(1, registry.size());
            assertTrue(issued.role.active());

            try {
                SshNativeRoleBootstrap.release(issued, registry, (token, ownerUid) ->
                        { throw new IllegalStateException("native callback failed"); });
                fail("unexpected native callback failure must be visible");
            } catch (VaultException failure) {
                assertEquals("SSH_ROLE_REQUIRED", failure.code);
            }
            assertEquals(1, registry.size());
            assertTrue(issued.role.active());
        } finally {
            otherRegistry.close();
            registry.close();
        }
    }

    @Test public void openRejectsInvalidBootstrapBeforeNativeBoundary() throws Exception {
        SshRoleContext.Bootstrap invalid = new SshRoleContext.Bootstrap(
                SshRoleContext.Program.CLIENT, 1001, 0, "connection_15",
                "target:user@example.invalid:22", "example.invalid", 22, "view_15",
                new byte[0], new byte[32], 30_000);
        try {
            SshNativeRoleBootstrap.open(invalid);
            fail("invalid bootstrap must not reach native open");
        } catch (VaultException failure) {
            assertEquals("SSH_ROLE_REQUIRED", failure.code);
        }
    }

    @Test public void refusesRoleIssueWhenNativeTokenOrUidIsNotOwned() throws Exception {
        Clock clock = new Clock();
        byte[] wire = record(13, 42, 1001, 1, 0, 22, 30_000,
                "connection_13", "target:user@example.invalid:22", "example.invalid", "view_13",
                6, 7);
        SshRoleRegistry registry = new SshRoleRegistry(clock);
        try {
            SshNativeRoleBootstrap.issue(wire, registry, new View(), clock, record -> false);
            fail("unowned native token must not issue a Java role");
        } catch (VaultException failure) {
            assertEquals("SSH_ROLE_REQUIRED", failure.code);
        }
        assertEquals(0, registry.size());

        try {
            SshNativeRoleBootstrap.issue(wire, registry, new View(), clock,
                    record -> record.token == 13 && record.ownerUid == 41);
            fail("a mismatched native UID must not issue a Java role");
        } catch (VaultException failure) {
            assertEquals("SSH_ROLE_REQUIRED", failure.code);
        }
        assertEquals(0, registry.size());

        try {
            SshNativeRoleBootstrap.issue(wire, registry, new View(), clock,
                    record -> record.token == 13 && record.ownerUid == 42
                            && record.bootstrap.connectionId.equals("different_connection"));
            fail("a mismatched native connection binding must not issue a Java role");
        } catch (VaultException failure) {
            assertEquals("SSH_ROLE_REQUIRED", failure.code);
        }
        assertEquals(0, registry.size());
    }

    @Test public void rejectsMalformedOrExpiredRecordsBeforeRoleIssue() throws Exception {
        Clock clock = new Clock();
        byte[] valid = record(8, 42, 1001, 2, 0, 22, 30_000,
                "connection_8", "target:user@example.invalid:22", "example.invalid", "view_8",
                4, 5);
        byte[] badMagic = valid.clone();
        badMagic[0] = 0;
        rejects(badMagic, clock);
        rejects(Arrays.copyOf(valid, valid.length - 1), clock);
        byte[] trailing = Arrays.copyOf(valid, valid.length + 1);
        rejects(trailing, clock);
        byte[] shell = record(9, 42, 1001, 1, 0, 22, 30_000,
                "connection;9", "target:user@example.invalid:22", "example.invalid", "view_9",
                4, 5);
        rejects(shell, clock);
        byte[] expired = record(10, 42, 1001, 1, 0, 22, 1,
                "connection_10", "target:user@example.invalid:22", "example.invalid", "view_10",
                4, 5);
        clock.now = 2_000;
        rejects(expired, clock);
    }

    @Test public void rejectsZeroBindingAndUnexpectedWireVersion() throws Exception {
        Clock clock = new Clock();
        byte[] zero = record(11, 42, 1001, 1, 0, 22, 30_000,
                "connection_11", "target:user@example.invalid:22", "example.invalid", "view_11",
                0, 5);
        rejects(zero, clock);
        byte[] version = record(12, 42, 1001, 1, 0, 22, 30_000,
                "connection_12", "target:user@example.invalid:22", "example.invalid", "view_12",
                4, 5);
        version[4] = 2;
        rejects(version, clock);
    }

    private static void rejects(byte[] wire, Clock clock) throws Exception {
        try {
            SshNativeRoleBootstrap.decode(wire, clock);
            fail("invalid native bootstrap accepted");
        } catch (VaultException failure) {
            assertEquals("SSH_ROLE_REQUIRED", failure.code);
        }
    }

    private static byte[] record(long token, int uid, int pid, int program, int hop, int port,
            long lifetime, String connectionId, String targetIdentity, String host, String view,
            int nonceValue, int bindingValue) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        DataOutputStream output = new DataOutputStream(bytes);
        output.writeInt(0x43535242);
        output.writeByte(1);
        output.writeByte(program);
        output.writeShort(0);
        output.writeLong(token);
        output.writeInt(pid);
        output.writeInt(uid);
        output.writeInt(hop);
        output.writeInt(port);
        output.writeLong(1_000 + lifetime);
        output.write(filled(nonceValue));
        output.write(filled(bindingValue));
        text(output, connectionId);
        text(output, targetIdentity);
        text(output, host);
        text(output, view);
        output.flush();
        return bytes.toByteArray();
    }

    private static void text(DataOutputStream output, String value) throws IOException {
        byte[] bytes = value.getBytes(StandardCharsets.US_ASCII);
        output.writeShort(bytes.length);
        output.write(bytes);
    }

    private static byte[] filled(int value) {
        byte[] bytes = new byte[32];
        Arrays.fill(bytes, (byte)value);
        return bytes;
    }

    private static boolean assertBytes(byte[] actual, int value) {
        assertArrayEquals(filled(value), actual);
        return true;
    }
}
