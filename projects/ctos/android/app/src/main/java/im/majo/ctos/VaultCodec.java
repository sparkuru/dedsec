package im.majo.ctos;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;

final class VaultCodec {
    static final class SecretBuffer extends java.io.ByteArrayOutputStream {
        SecretBuffer() { super(); }
        SecretBuffer(int capacity) { super(capacity); }
        @Override public void close() { wipe(buf); reset(); }
    }
    private VaultCodec() { }

    static byte[] readBytes(DataInputStream in, int maximum) throws IOException, VaultException {
        int length = in.readInt();
        if (length < 0 || length > maximum || length > in.available()) throw VaultException.invalid();
        byte[] bytes = new byte[length];
        in.readFully(bytes);
        return bytes;
    }

    static String readText(DataInputStream in, int maximum) throws IOException, VaultException {
        byte[] bytes = readBytes(in, maximum);
        try {
            String text = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
            if (text.indexOf('\0') >= 0) throw VaultException.invalid();
            return text;
        } catch (CharacterCodingException error) {
            throw VaultException.invalid();
        } finally { wipe(bytes); }
    }

    static void writeBytes(DataOutputStream out, byte[] value) throws IOException {
        out.writeInt(value.length);
        out.write(value);
    }

    static void writeText(DataOutputStream out, String value) throws IOException {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        try { writeBytes(out, bytes); } finally { wipe(bytes); }
    }

    static byte[] password(char[] password) throws VaultException {
        if (password.length < 1 || password.length > 1024) {
            throw new VaultException("PARAMETERS", "Use a password between 1 and 1024 characters");
        }
        try {
            ByteBuffer encoded = StandardCharsets.UTF_8.newEncoder().onMalformedInput(CodingErrorAction.REPORT)
                    .encode(CharBuffer.wrap(password));
            byte[] bytes = new byte[encoded.remaining()];
            encoded.get(bytes);
            if (encoded.hasArray()) wipe(encoded.array());
            return bytes;
        } catch (CharacterCodingException error) {
            throw new VaultException("PARAMETERS", "The password contains invalid characters");
        }
    }

    static byte[] digest(byte[] bytes) {
        try { return MessageDigest.getInstance("SHA-256").digest(bytes); }
        catch (java.security.NoSuchAlgorithmException error) { throw new AssertionError(error); }
    }

    static void wipe(byte[] bytes) { if (bytes != null) Arrays.fill(bytes, (byte) 0); }
}
