package im.majo.ctos;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CoderResult;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Public server questions and bounded one-round ephemeral response framing. */
final class SshKeyboardChallenge {
    static final int MAX_ROUNDS = 16, MAX_PROMPTS = 16, MAX_FIELD_BYTES = 4096, MAX_WIRE_BYTES = 32768;
    static final int CHALLENGE_MAGIC = 0x43534b49, RESPONSE_MAGIC = 0x43534b52;

    static final class Prompt {
        final String text;
        final boolean echo;
        private Prompt(String text, boolean echo) { this.text = text; this.echo = echo; }
    }

    final int round;
    final String name, instruction;
    final List<Prompt> prompts;

    private SshKeyboardChallenge(int round, String name, String instruction, List<Prompt> prompts) {
        this.round = round; this.name = name; this.instruction = instruction;
        this.prompts = Collections.unmodifiableList(prompts);
    }

    static SshKeyboardChallenge decode(byte[] wire) throws IOException {
        if (wire == null || wire.length < 24 || wire.length > MAX_WIRE_BYTES) throw invalid();
        try {
            ByteBuffer input = ByteBuffer.wrap(wire);
            if (input.getInt() != CHALLENGE_MAGIC || input.getInt() != 0x00010000) throw invalid();
            int round = input.getInt(), nameLength = input.getInt(), instructionLength = input.getInt(), count = input.getInt();
            if (round < 1 || round > MAX_ROUNDS || count < 0 || count > MAX_PROMPTS) throw invalid();
            String name = display(readText(input, nameLength)), instruction = display(readText(input, instructionLength));
            List<Prompt> prompts = new ArrayList<>(count);
            for (int index = 0; index < count; index++) {
                int echo = input.getInt(), length = input.getInt();
                if ((echo != 0 && echo != 1) || length == 0) throw invalid();
                prompts.add(new Prompt(display(readText(input, length)), echo == 1));
            }
            if (input.hasRemaining()) throw invalid();
            return new SshKeyboardChallenge(round, name, instruction, prompts);
        } catch (IOException | RuntimeException malformed) { throw invalid(); }
    }

    byte[] response(List<?> answers) throws VaultException {
        if (answers == null || answers.size() != prompts.size()) throw failure();
        List<byte[]> encoded = new ArrayList<>(answers.size());
        byte[] result = null;
        boolean returned = false;
        try {
            int length = 16;
            for (Object answer : answers) {
                if (!(answer instanceof String)) throw failure();
                byte[] bytes = encodeText((String)answer);
                encoded.add(bytes);
                length += 4 + bytes.length;
                if (length > MAX_WIRE_BYTES) throw failure();
            }
            result = new byte[length];
            ByteBuffer output = ByteBuffer.wrap(result).putInt(RESPONSE_MAGIC).putInt(0x00010000)
                    .putInt(round).putInt(encoded.size());
            for (byte[] answer : encoded) output.putInt(answer.length).put(answer);
            returned = true;
            return result;
        } finally {
            for (byte[] answer : encoded) VaultCodec.wipe(answer);
            if (!returned) VaultCodec.wipe(result);
        }
    }

    void requireResponse(byte[] wire) throws IOException {
        if (wire == null || wire.length < 16 || wire.length > MAX_WIRE_BYTES) throw invalid();
        try {
            ByteBuffer input = ByteBuffer.wrap(wire);
            if (input.getInt() != RESPONSE_MAGIC || input.getInt() != 0x00010000
                    || input.getInt() != round || input.getInt() != prompts.size()) throw invalid();
            for (int index = 0; index < prompts.size(); index++) validateText(input, input.getInt());
            if (input.hasRemaining()) throw invalid();
        } catch (RuntimeException malformed) { throw invalid(); }
    }

    private static byte[] encodeText(String text) throws VaultException {
        if (text.length() > MAX_FIELD_BYTES) throw failure();
        byte[] scratch = new byte[MAX_FIELD_BYTES];
        try {
            ByteBuffer encoded = ByteBuffer.wrap(scratch);
            java.nio.charset.CharsetEncoder encoder = StandardCharsets.UTF_8.newEncoder()
                    .onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT);
            CoderResult result = encoder.encode(CharBuffer.wrap(text), encoded, true);
            if (result.isOverflow()) throw failure();
            if (result.isError()) result.throwException();
            result = encoder.flush(encoded);
            if (result.isOverflow()) throw failure();
            if (result.isError()) result.throwException();
            return java.util.Arrays.copyOf(scratch, encoded.position());
        } catch (CharacterCodingException invalid) { throw failure(); }
        finally { VaultCodec.wipe(scratch); }
    }

    private static String readText(ByteBuffer input, int length) throws IOException {
        if (length < 0 || length > MAX_FIELD_BYTES || length > input.remaining()) throw invalid();
        ByteBuffer field = input.slice(); field.limit(length);
        String text = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(field).toString();
        input.position(input.position() + length);
        return text;
    }

    private static void validateText(ByteBuffer input, int length) throws IOException {
        if (length < 0 || length > MAX_FIELD_BYTES || length > input.remaining()) throw invalid();
        ByteBuffer field = input.slice(); field.limit(length);
        char[] scratch = new char[MAX_FIELD_BYTES];
        try {
            java.nio.charset.CharsetDecoder decoder = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT);
            CharBuffer decoded = CharBuffer.wrap(scratch);
            CoderResult result = decoder.decode(field, decoded, true);
            if (result.isOverflow()) throw invalid();
            if (result.isError()) result.throwException();
            result = decoder.flush(decoded);
            if (result.isOverflow()) throw invalid();
            if (result.isError()) result.throwException();
            input.position(input.position() + length);
        } finally { java.util.Arrays.fill(scratch, '\0'); }
    }

    private static String display(String text) {
        StringBuilder result = new StringBuilder(text.length());
        for (int index = 0; index < text.length(); index++) {
            char value = text.charAt(index);
            result.append((value < 32 && value != '\n' && value != '\t') || value >= 127 && value <= 159 ? '?' : value);
        }
        return result.toString();
    }

    private static IOException invalid() { return new IOException("Invalid SSH keyboard-interactive framing"); }
    static VaultException failure() { return new VaultException("SSH_KEYBOARD_CHALLENGE", "The current server challenge is unavailable"); }
}
