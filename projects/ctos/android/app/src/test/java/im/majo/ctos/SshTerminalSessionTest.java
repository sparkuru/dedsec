package im.majo.ctos;

import static org.junit.Assert.*;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.Test;

/** Controller-only fixtures; real encrypted/JNI shell acceptance is verified separately. */
public final class SshTerminalSessionTest {
    private static final class Turns implements SshTerminalSession.Scheduler {
        final ArrayDeque<Runnable> turns = new ArrayDeque<>();
        @Override public void later(Runnable turn, long delay) { turns.add(turn); }
        void next() { assertFalse(turns.isEmpty()); turns.remove().run(); }
    }

    private static final class Port implements SshTerminalSession.Port {
        SshNativeSession.ShellPhase phase = SshNativeSession.ShellPhase.RUNNING;
        final ByteArrayOutputStream written = new ByteArrayOutputStream();
        final ArrayDeque<byte[]> output = new ArrayDeque<>();
        final List<String> sizes = new ArrayList<>();
        int acceptance = 32768;
        int steps;
        int begins;
        int closes;
        int failedCloses;
        boolean live = true;
        boolean cancelled;
        boolean resizeAccepted = true;
        Long exitStatus;
        boolean stepError;
        boolean closeError;
        @Override public void begin(int columns, int rows) { begins++; }
        @Override public SshTerminalSession.Status status() {
            return new SshTerminalSession.Status(phase, output.isEmpty() ? 0 : output.peek().length, exitStatus);
        }
        @Override public void step() {
            steps++;
            if (stepError) throw new AssertionError("injected step error");
        }
        @Override public int write(byte[] bytes) {
            int length = Math.min(acceptance, bytes.length);
            written.write(bytes, 0, length);
            return length;
        }
        @Override public byte[] read(int maximum) { return output.remove(); }
        @Override public boolean resize(int columns, int rows) {
            sizes.add(columns + "x" + rows);
            return resizeAccepted;
        }
        @Override public boolean publicationLive() { return live && !cancelled; }
        @Override public void cancel() { cancelled = true; }
        @Override public void close() throws IOException {
            closes++;
            if (closeError) { closeError = false; throw new AssertionError("injected close error"); }
            if (failedCloses-- > 0) throw new IOException("cleanup pending");
            live = false;
        }
    }

    private static final class Events implements SshTerminalSession.Sink {
        final List<String> states = new ArrayList<>();
        final List<byte[]> data = new ArrayList<>();
        final ArrayDeque<Runnable> acknowledgements = new ArrayDeque<>();
        Long exit;
        @Override public void data(byte[] bytes, Runnable consumed) {
            assertEquals("running", states.get(0));
            data.add(bytes);
            acknowledgements.add(consumed);
        }
        @Override public void state(String state, Long status, String reason) {
            states.add(state);
            if (status != null) exit = status;
        }
    }

    private static final class Fixture {
        final Port port = new Port();
        final Turns turns = new Turns();
        final Events events = new Events();
        int releases;
        final SshTerminalSession session = new SshTerminalSession(port, turns, events, () -> releases++);
        Fixture() { session.start(80, 24); turns.next(); }
    }

    @Test public void partialInputAcceptancePreservesSuffixAndRejectsFullQueueTruthfully() {
        Fixture f = new Fixture();
        f.port.acceptance = 0;
        byte[] input = new byte[SshTerminalSession.INPUT_LIMIT];
        for (int i = 0; i < input.length; i++) input[i] = (byte) i;
        f.session.write(input);
        try { f.session.write(new byte[] {42}); fail(); }
        catch (IllegalStateException expected) { assertTrue(expected.getMessage().contains("queue")); }
        f.turns.next();
        assertEquals(input.length, f.session.queuedInputBytes());
        assertEquals(0, f.port.written.size());
        f.port.acceptance = 1234;
        while (f.session.queuedInputBytes() > 0) f.turns.next();
        assertArrayEquals(input, f.port.written.toByteArray());
        f.session.stop();
    }

    @Test public void outputBackpressureBlocksSecondReadUntilAcknowledgement() {
        Fixture f = new Fixture();
        f.port.output.add(new byte[32768]);
        f.port.output.add(new byte[] {7});
        f.turns.next();
        assertEquals(32768, f.session.outputInFlightBytes());
        for (int i = 0; i < 5; i++) f.turns.next();
        assertEquals(1, f.events.data.size());
        assertEquals(1, f.port.output.size());
        f.events.acknowledgements.remove().run();
        while (f.events.data.size() < 2) f.turns.next();
        assertEquals(1, f.session.outputInFlightBytes());
        f.session.stop();
    }

    @Test public void normalExitDrainsLastOutputBeforeCloseAndPreservesUnsignedStatus() {
        Fixture f = new Fixture();
        f.port.output.add(new byte[] {3, 4});
        f.port.phase = SshNativeSession.ShellPhase.CLOSED;
        f.port.exitStatus = 4294967295L;
        f.turns.next();
        for (int i = 0; i < 3; i++) f.turns.next();
        assertEquals(0, f.port.closes);
        assertTrue(f.session.publicationLive());
        f.events.acknowledgements.remove().run();
        while (!f.session.closed()) f.turns.next();
        assertEquals(Arrays.asList("running", "closed"), f.events.states);
        assertEquals(Long.valueOf(4294967295L), f.events.exit);
        assertEquals(1, f.releases);
        assertArrayEquals(new byte[2], f.events.data.get(0));
    }

    @Test public void cancellationFencesPendingPublicationBeforeOwnerCloses() throws Exception {
        Fixture f = new Fixture();
        f.port.output.add(new byte[] {8});
        f.turns.next();
        Thread thread = new Thread(f.session::requestCancel);
        thread.start(); thread.join();
        assertFalse(f.session.publicationLive());
        assertEquals(0, f.port.closes);
        f.turns.next();
        assertEquals(1, f.port.closes);
        assertEquals(0, f.releases); // Main-thread publication still owns the in-flight bytes.
        f.events.acknowledgements.remove().run();
        while (f.releases == 0) f.turns.next();
        assertEquals(1, f.releases);
        assertFalse(f.session.publicationLive());
    }

    @Test public void cleanupFailureRetainsOwnerAndRetriesBeforeRelease() {
        Fixture f = new Fixture();
        f.port.failedCloses = 2;
        f.session.stop();
        assertFalse(f.session.closed());
        assertEquals(0, f.releases);
        assertEquals(Arrays.asList("running", "failed"), f.events.states);
        while (!f.session.closed()) f.turns.next();
        assertEquals(3, f.port.closes);
        assertEquals(1, f.releases);
    }

    @Test public void resizeCoalescesAndRetriesOnlyLatestDimensions() {
        Fixture f = new Fixture();
        f.port.resizeAccepted = false;
        f.session.resize(100, 40);
        f.session.resize(120, 50);
        f.turns.next();
        f.session.resize(140, 60);
        f.port.resizeAccepted = true;
        f.turns.next();
        f.turns.next();
        assertEquals(Arrays.asList("120x50", "140x60"), f.port.sizes);
        f.session.stop();
    }

    @Test public void openingDoesNotPublishRunningAndRejectedChannelCloses() {
        Port port = new Port();
        Turns turns = new Turns();
        Events events = new Events();
        port.phase = SshNativeSession.ShellPhase.PTY_PENDING;
        SshTerminalSession session = new SshTerminalSession(port, turns, events, () -> {});
        session.start(80, 24);
        turns.next();
        assertTrue(events.states.isEmpty());
        try { session.write(new byte[] {1}); fail(); }
        catch (IllegalStateException expected) { }
        port.phase = SshNativeSession.ShellPhase.REJECTED;
        turns.next();
        assertEquals(Arrays.asList("failed"), events.states);
        assertTrue(session.closed());
    }

    @Test public void revokedOutputIsWipedBeforeSinkPublication() {
        Fixture f = new Fixture();
        byte[] output = new byte[] {9};
        f.port.output.add(output);
        f.port.live = false;
        f.turns.next();
        assertTrue(f.events.data.isEmpty());
        assertArrayEquals(new byte[1], output);
        assertTrue(f.session.closed());
    }
    @Test public void propagatedStepErrorStillClosesOwnedSession() {
        Fixture f = new Fixture();
        f.port.stepError = true;
        try { f.turns.next(); fail(); }
        catch (AssertionError expected) { assertEquals("injected step error", expected.getMessage()); }
        assertTrue(f.session.closed());
        assertEquals(1, f.releases);
    }

    @Test public void propagatedCleanupErrorRetainsRetryResponsibility() {
        Fixture f = new Fixture();
        f.port.closeError = true;
        try { f.session.stop(); fail(); }
        catch (AssertionError expected) { assertEquals("injected close error", expected.getMessage()); }
        assertFalse(f.session.closed());
        assertEquals(0, f.releases);
        while (!f.session.closed()) f.turns.next();
        assertEquals(2, f.port.closes);
        assertEquals(1, f.releases);
    }

    @Test public void queuedStartCancelledBeforeOwnerRunsNeverBeginsChannel() {
        Port port = new Port();
        Turns turns = new Turns();
        Events events = new Events();
        SshTerminalSession session = new SshTerminalSession(port, turns, events, () -> {});
        turns.later(() -> session.start(80, 24), 0);
        session.requestCancel();
        turns.next();
        assertEquals(0, port.begins);
        assertEquals(1, port.closes);
        assertEquals(Arrays.asList("closed"), events.states);
        assertTrue(session.closed());
    }

}
