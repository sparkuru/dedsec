package im.majo.ctos;

import static org.junit.Assert.*;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.Test;

public final class SshKeyboardChallengeTest {
    static byte[] frame(int round, String name, String instruction, String... prompts) throws IOException {
        byte[] title = name.getBytes(StandardCharsets.UTF_8), help = instruction.getBytes(StandardCharsets.UTF_8);
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(bytes);
        for (int value : new int[]{SshKeyboardChallenge.CHALLENGE_MAGIC, 0x00010000, round, title.length, help.length, prompts.length})
            out.writeInt(value);
        out.write(title); out.write(help);
        for (int index = 0; index < prompts.length; index++) {
            byte[] prompt = prompts[index].getBytes(StandardCharsets.UTF_8);
            out.writeInt(index % 2); out.writeInt(prompt.length); out.write(prompt);
        }
        return bytes.toByteArray();
    }

    private static void invalid(byte[] wire) throws Exception {
        try { SshKeyboardChallenge.decode(wire); fail("Malformed challenge must fail"); }
        catch (IOException expected) { assertEquals("Invalid SSH keyboard-interactive framing", expected.getMessage()); }
    }

    @Test public void actualFramingPreservesOrderedEchoAndUnicodeWithoutTerminalControls() throws Exception {
        byte[] source = frame(1, "Name\u001b\u0085", "说明\n\tmore\r", "Password: ", "用户名😀: ");
        SshKeyboardChallenge value = SshKeyboardChallenge.decode(source);
        assertEquals(1, value.round); assertEquals("Name??", value.name); assertEquals("说明\n\tmore?", value.instruction);
        assertEquals("Password: ", value.prompts.get(0).text); assertFalse(value.prompts.get(0).echo);
        assertEquals("用户名😀: ", value.prompts.get(1).text); assertTrue(value.prompts.get(1).echo);
        Arrays.fill(source, (byte)0);
        assertEquals("用户名😀: ", value.prompts.get(1).text);
        try { value.prompts.clear(); fail("Public questions must be immutable"); }
        catch (UnsupportedOperationException expected) { }
    }

    @Test public void zeroQuestionsAndEmptyResponsesAreExplicitValidMessages() throws Exception {
        SshKeyboardChallenge zero = SshKeyboardChallenge.decode(frame(16, "", "Notice\n"));
        assertEquals(0, zero.prompts.size());
        byte[] response = zero.response(List.of());
        assertArrayEquals(ByteBuffer.allocate(16).putInt(SshKeyboardChallenge.RESPONSE_MAGIC)
                .putInt(0x00010000).putInt(16).putInt(0).array(), response);
        zero.requireResponse(response);
        SshKeyboardChallenge one = SshKeyboardChallenge.decode(frame(1, "", "", "Question: "));
        byte[] empty = one.response(List.of(""));
        assertEquals(20, empty.length); one.requireResponse(empty);
        byte[] nul = one.response(List.of("a\0😀"));
        one.requireResponse(nul);
        assertArrayEquals("a\0😀".getBytes(StandardCharsets.UTF_8), Arrays.copyOfRange(nul, 20, nul.length));
    }

    @Test public void rejectsBadVersionLengthsCountsUtf8AndEmptyPromptWithoutPartialPublication() throws Exception {
        byte[] good = frame(1, "", "", "?");
        invalid(null); invalid(new byte[23]); invalid(new byte[32769]);
        for (int offset : new int[]{0,4,8,12,16,20,24,28}) {
            byte[] bad = good.clone(); ByteBuffer.wrap(bad).putInt(offset, -1); invalid(bad);
        }
        byte[] bad = good.clone(); ByteBuffer.wrap(bad).putInt(4,0x00010001); invalid(bad);
        bad = good.clone(); ByteBuffer.wrap(bad).putInt(8,17); invalid(bad);
        bad = good.clone(); ByteBuffer.wrap(bad).putInt(20,17); invalid(bad);
        bad = good.clone(); ByteBuffer.wrap(bad).putInt(24,2); invalid(bad);
        bad = good.clone(); ByteBuffer.wrap(bad).putInt(28,0); invalid(bad);
        bad = good.clone(); bad[32]=(byte)0xff; invalid(bad);
        invalid(Arrays.copyOf(good,good.length+1)); invalid(Arrays.copyOf(good,good.length-1));
        invalid(frame(1,"x".repeat(4097),""));
    }

    @Test public void responseCountsUtf8ByteBudgetsAndRoundCannotBeForged() throws Exception {
        SshKeyboardChallenge one = SshKeyboardChallenge.decode(frame(1,"","","?"));
        List<List<?>> rejected = new ArrayList<>();
        rejected.add(List.of()); rejected.add(List.of("","")); rejected.add(List.of(2));
        rejected.add(List.of("x".repeat(4097))); rejected.add(List.of("é".repeat(2049)));
        rejected.add(List.of("\ud800")); rejected.add(List.of("sensitive-prefix\ud800"));
        rejected.add(Arrays.asList((Object)null));
        for (List<?> answer : rejected) {
            try { one.response(answer); fail("Invalid answer vector must fail"); }
            catch (VaultException expected) { assertEquals("SSH_KEYBOARD_CHALLENGE",expected.code); }
        }
        byte[] boundary = one.response(List.of("é".repeat(2048))); one.requireResponse(boundary);
        for (int offset : new int[]{0,4,8,12,16}) {
            byte[] bad = boundary.clone(); ByteBuffer.wrap(bad).putInt(offset,-1);
            try { one.requireResponse(bad); fail("Malformed response must fail"); } catch (IOException expected) { }
        }
        byte[] invalidUtf8 = one.response(List.of("x")); invalidUtf8[20]=(byte)0xff;
        try { one.requireResponse(invalidUtf8); fail("Strict UTF8 required"); } catch (IOException expected) { }
        byte[] invalidTail = one.response(List.of("sensitive-prefix-x")); invalidTail[invalidTail.length-1]=(byte)0xff;
        try { one.requireResponse(invalidTail); fail("Invalid tail after a valid secret prefix must fail"); } catch (IOException expected) { }
        one.requireResponse(one.response(List.of("valid after refusal")));
        String[] questions = new String[16]; Arrays.fill(questions,"?");
        SshKeyboardChallenge many = SshKeyboardChallenge.decode(frame(1,"","",questions));
        try { many.response(java.util.Collections.nCopies(16,"x".repeat(4096))); fail("Aggregate limit required"); }
        catch (VaultException expected) { assertEquals("SSH_KEYBOARD_CHALLENGE",expected.code); }
    }
}
