package im.majo.ctos;

import static org.junit.Assert.*;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import org.junit.Test;

public final class SshTerminalStateTest {
    @Test public void recreationReplaysOneSnapshotThenLiveBytesWithoutOwningTheConnection() {
        SshTerminalState state = new SshTerminalState();
        state.begin(1, "ssh");
        List<Map<String, Object>> first = new ArrayList<>();
        SshTerminalState.Listener original = first::add;
        state.subscribe(original);
        state.state(1, "running", null, null);
        state.data(1, new byte[] {1, 2});
        state.unsubscribe(original);
        state.data(1, new byte[] {3, 4});
        List<Map<String, Object>> restored = new ArrayList<>();
        SshTerminalState.Listener replacement = restored::add;
        state.subscribe(replacement);
        assertEquals(1, restored.size());
        Map<String, Object> snapshot = restored.get(0);
        assertEquals("snapshot", snapshot.get("type"));
        assertEquals("running", snapshot.get("state"));
        assertEquals(Boolean.TRUE, snapshot.get("remote"));
        assertArrayEquals(new byte[] {1, 2, 3, 4}, (byte[]) snapshot.get("bytes"));
        state.unsubscribe(original);
        state.data(1, new byte[] {5});
        assertEquals(2, restored.size());
        assertEquals(2, first.size());
        assertEquals("data", restored.get(1).get("type"));
    }

    @Test public void replayIsBoundedAndTruncationIsVisible() {
        SshTerminalState state = new SshTerminalState();
        state.begin(1, "ssh");
        state.state(1, "running", null, null);
        byte[] block = new byte[32768];
        for (int i = 0; i < 12; i++) {
            Arrays.fill(block, (byte) i);
            state.data(1, block);
        }
        assertEquals(SshTerminalState.OUTPUT_LIMIT, state.bufferedBytes());
        Map<String, Object> snapshot = state.snapshot();
        assertEquals(Boolean.TRUE, snapshot.get("truncated"));
        byte[] replay = (byte[]) snapshot.get("bytes");
        assertEquals(4, replay[0]);
        assertEquals(11, replay[replay.length - 1]);
        replay[0] = 99;
        assertEquals(4, ((byte[]) state.snapshot().get("bytes"))[0]);
    }

    @Test public void oldGenerationCannotPublishOrFocusAndCloseWipesReplay() {
        SshTerminalState state = new SshTerminalState();
        List<Map<String, Object>> received = new ArrayList<>();
        state.subscribe(received::add);
        state.begin(1, "ssh");
        state.state(1, "running", null, null);
        state.data(1, new byte[] {7});
        state.state(1, "closed", 0L, null);
        assertEquals(0, state.bufferedBytes());
        state.state(1, "running", null, null);
        assertEquals("closed", state.snapshot().get("state"));
        state.begin(2, "ssh");
        state.state(2, "running", null, null);
        int count = received.size();
        state.data(1, new byte[] {8});
        state.state(1, "closed", null, null);
        state.focus(1);
        assertEquals(count, received.size());
        state.focus(2);
        assertEquals("focus", received.get(received.size() - 1).get("type"));
        assertEquals(2, received.get(received.size() - 1).get("generation"));
        assertEquals(0, state.bufferedBytes());
    }

    @Test public void reconnectHintAppearsOnlyOnConfirmedLockCloseAndSurvivesRecreation() throws Exception {
        SshTerminalState state = new SshTerminalState();
        state.begin(1, "ssh");
        SshReconnectTarget target = new SshReconnectTarget(new SshPasswordProfile("host.invalid", 22, "user"), "key_1", "alias");
        state.reconnectTarget(1, target);
        state.state(1, "running", null, null);
        state.data(1, new byte[] {7});
        assertFalse(state.snapshot().containsKey("reconnect"));
        state.state(1, "failed", null, "SSH_SCREEN_LOCKED");
        assertFalse(state.snapshot().containsKey("reconnect"));
        assertEquals(0, state.bufferedBytes());
        state.state(1, "running", null, null);
        assertEquals("failed", state.snapshot().get("state"));
        state.state(1, "failed", null, "SSH_TERMINAL_CLEANUP_PENDING");
        assertFalse(state.snapshot().containsKey("reconnect"));
        state.state(1, "closed", null, "SSH_SCREEN_LOCKED");
        List<Map<String, Object>> recreated = new ArrayList<>();
        state.subscribe(recreated::add);
        assertEquals(target.wire(), recreated.get(0).get("reconnect"));
        assertArrayEquals(new byte[0], (byte[])recreated.get(0).get("bytes"));
        ((Map<?, ?>)recreated.get(0).get("reconnect")).clear();
        assertEquals(target.wire(), state.snapshot().get("reconnect"));
        state.begin(2, "ssh");
        state.reconnectTarget(1, target);
        state.state(2, "closed", null, "SSH_SCREEN_LOCKED");
        assertFalse("A stale target must never bind to a new generation", state.snapshot().containsKey("reconnect"));
    }

    @Test public void localAndRemoteGenerationsShareOneStrictlyIncreasingSequence() {
        int local = SshTerminalState.nextGeneration();
        int remote = SshTerminalState.nextGeneration();
        int recreatedLocal = SshTerminalState.nextGeneration();
        assertTrue(local > 0);
        assertTrue(remote > local);
        assertTrue(recreatedLocal > remote);
    }
}
