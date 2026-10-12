package im.majo.ctos;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.nio.ByteBuffer;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.UUID;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.bouncycastle.crypto.generators.Argon2BytesGenerator;
import org.bouncycastle.crypto.params.Argon2Parameters;

/** Portable v1 envelope. No parsing of plaintext occurs before GCM doFinal succeeds. */
final class VaultEnvelope {
    static final int MAX_PLAINTEXT = 12 * 1024 * 1024;
    static final int HEADER_BYTES = 88;
    static final int MAX_BYTES = HEADER_BYTES + MAX_PLAINTEXT + 16;
    static final int MEMORY_KIB = 65536;
    static final int ITERATIONS = 3;
    static final int LANES = 4;
    private static final long MAGIC = 0x43544f53564c5431L; // CTOSVLT1
    private static final SecureRandom RANDOM = new SecureRandom();

    final UUID id;
    final long generation;
    final byte[] salt;
    final byte[] nonce;
    final byte[] header;
    final byte[] ciphertext;

    private VaultEnvelope(UUID id, long generation, byte[] salt, byte[] nonce,
            byte[] header, byte[] ciphertext) {
        this.id = id;
        this.generation = generation;
        this.salt = salt;
        this.nonce = nonce;
        this.header = header;
        this.ciphertext = ciphertext;
    }

    static VaultEnvelope parse(byte[] encoded) throws VaultException {
        if (encoded.length < HEADER_BYTES + 16 || encoded.length > MAX_BYTES) throw format();
        try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(encoded))) {
            if (in.readLong() != MAGIC || in.readInt() != 1 || in.readInt() != 1
                    || in.readInt() != MEMORY_KIB || in.readInt() != ITERATIONS
                    || in.readInt() != LANES || in.readInt() != 0x13) throw format();
            UUID id = new UUID(in.readLong(), in.readLong());
            long generation = in.readLong();
            if (generation < 1) throw format();
            byte[] salt = new byte[16];
            byte[] nonce = new byte[12];
            in.readFully(salt);
            in.readFully(nonce);
            int length = in.readInt();
            if (length < 16 || length != encoded.length - HEADER_BYTES) throw format();
            byte[] cipher = new byte[length];
            in.readFully(cipher);
            return new VaultEnvelope(id, generation, salt, nonce,
                    Arrays.copyOf(encoded, HEADER_BYTES), cipher);
        } catch (java.io.IOException error) { throw format(); }
    }

    static byte[] derive(char[] password, byte[] salt) throws VaultException {
        byte[] input = VaultCodec.password(password);
        byte[] key = new byte[32];
        Argon2Parameters parameters = new Argon2Parameters.Builder(Argon2Parameters.ARGON2_id)
                .withVersion(Argon2Parameters.ARGON2_VERSION_13).withSalt(salt)
                .withMemoryAsKB(MEMORY_KIB).withIterations(ITERATIONS).withParallelism(LANES).build();
        try {
            Argon2BytesGenerator generator = new Argon2BytesGenerator();
            generator.init(parameters);
            generator.generateBytes(input, key);
            return key;
        } finally {
            VaultCodec.wipe(input);
            parameters.clear();
        }
    }

    static VaultEnvelope encrypt(UUID id, long generation, byte[] salt, byte[] key, byte[] plaintext)
            throws VaultException {
        if (plaintext.length > MAX_PLAINTEXT || generation < 1) throw VaultException.invalid();
        byte[] nonce = random(12);
        try {
            ByteArrayOutputStream buffer = new ByteArrayOutputStream(HEADER_BYTES);
            DataOutputStream out = new DataOutputStream(buffer);
            out.writeLong(MAGIC);
            out.writeInt(1);
            out.writeInt(1);
            out.writeInt(MEMORY_KIB);
            out.writeInt(ITERATIONS);
            out.writeInt(LANES);
            out.writeInt(0x13);
            out.writeLong(id.getMostSignificantBits());
            out.writeLong(id.getLeastSignificantBits());
            out.writeLong(generation);
            out.write(salt);
            out.write(nonce);
            out.writeInt(plaintext.length + 16);
            byte[] header = buffer.toByteArray();
            if (header.length != HEADER_BYTES) throw new AssertionError("Invalid envelope header");
            Cipher cipher = cipher(Cipher.ENCRYPT_MODE, key, nonce, header);
            return new VaultEnvelope(id, generation, salt.clone(), nonce, header, cipher.doFinal(plaintext));
        } catch (java.io.IOException | GeneralSecurityException error) {
            throw new VaultException("VAULT_FAILED", "Unable to encrypt the vault");
        }
    }

    byte[] decrypt(byte[] key) throws VaultException {
        try { return cipher(Cipher.DECRYPT_MODE, key, nonce, header).doFinal(ciphertext); }
        catch (GeneralSecurityException error) { throw VaultException.authentication(); }
    }

    byte[] encode() {
        return ByteBuffer.allocate(header.length + ciphertext.length).put(header).put(ciphertext).array();
    }

    byte[] binding() { return VaultCodec.digest(encode()); }
    byte[] materialBinding() {
        // Generation is the key-material epoch; content transactions keep it and use a fresh nonce.
        return VaultCodec.digest(ByteBuffer.allocate(72).put(header, 0, 56).put(salt).array());
    }

    static byte[] random(int length) {
        byte[] bytes = new byte[length];
        RANDOM.nextBytes(bytes);
        return bytes;
    }

    private static Cipher cipher(int mode, byte[] key, byte[] nonce, byte[] header)
            throws GeneralSecurityException {
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(mode, new SecretKeySpec(key, "AES"), new GCMParameterSpec(128, nonce));
        cipher.updateAAD(header);
        return cipher;
    }

    private static VaultException format() {
        return new VaultException("VAULT_FORMAT", "Unsupported, truncated, or oversized backup");
    }
}
