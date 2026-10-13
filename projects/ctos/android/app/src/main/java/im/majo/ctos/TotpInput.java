package im.majo.ctos;

import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/** Strict offline input. Only the approved SHA1 / six digit / thirty second profile exists. */
final class TotpInput implements AutoCloseable {
    static final int MAX_URI = 8192;
    final String identity;
    final String label;
    final byte[] seed;
    private TotpInput(String identity, String label, byte[] seed) {
        this.identity = identity; this.label = label; this.seed = seed;
    }
    static TotpInput manual(String issuer, String account, String secret) throws VaultException {
        issuer = metadata(issuer.trim(), true); account = metadata(account.trim(), false);
        if (issuer.contains(":") || account.contains(":")) throw invalid();
        String identity = issuer.isEmpty() ? account : issuer + ":" + account;
        metadata(identity, false);
        return new TotpInput(identity, account, base32(secret.trim()));
    }
    static TotpInput uri(String text) throws VaultException {
        if (text.length() > MAX_URI) throw invalid();
        try {
            URI uri = new URI(text);
            if (!"otpauth".equals(uri.getScheme()) || !"totp".equals(uri.getRawAuthority())
                    || uri.getRawFragment() != null || uri.getRawQuery() == null
                    || uri.getRawPath() == null || !uri.getRawPath().startsWith("/")) throw invalid();
            String label = decode(uri.getRawPath().substring(1));
            if (label.contains("/") || label.isEmpty()) throw invalid();
            Map<String, String> params = new HashMap<>();
            for (String part : uri.getRawQuery().split("&", -1)) {
                int eq = part.indexOf('=');
                if (eq < 1) throw invalid();
                String key = decode(part.substring(0, eq));
                if (!VaultCollections.set("secret", "issuer", "algorithm", "digits", "period").contains(key)
                        || params.put(key, decode(part.substring(eq + 1))) != null) throw invalid();
            }
            if (!"SHA1".equals(params.getOrDefault("algorithm", "SHA1"))
                    || !"6".equals(params.getOrDefault("digits", "6"))
                    || !"30".equals(params.getOrDefault("period", "30")) || !params.containsKey("secret")) throw invalid();
            int colon = label.indexOf(':');
            String prefix = colon < 0 ? "" : label.substring(0, colon).trim();
            String account = colon < 0 ? label : label.substring(colon + 1).trim();
            String issuer = params.getOrDefault("issuer", prefix).trim();
            if (colon >= 0 && (prefix.isEmpty() || !prefix.equals(issuer))) throw invalid();
            return manual(issuer, account, params.get("secret"));
        } catch (VaultException failure) { throw failure; }
        catch (Exception failure) { throw invalid(); }
    }
    static String metadata(String text, boolean empty) throws VaultException {
        if ((!empty && text.isEmpty()) || text.getBytes(StandardCharsets.UTF_8).length > 512
                || text.codePoints().anyMatch(c -> Character.isISOControl(c) || Character.getType(c) == Character.FORMAT
                        || c == 0xfffd || c >= 0xd800 && c <= 0xdfff)) throw invalid();
        return text;
    }
    private static String decode(String text) throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        for (int i = 0; i < text.length();) {
            if (text.charAt(i) == '%') {
                if (i + 2 >= text.length()) throw invalid();
                int high = Character.digit(text.charAt(i + 1), 16), low = Character.digit(text.charAt(i + 2), 16);
                if (high < 0 || low < 0) throw invalid();
                bytes.write((high << 4) | low); i += 3;
            } else {
                int cp = text.codePointAt(i);
                if (cp >= 0xd800 && cp <= 0xdfff) throw invalid();
                bytes.write(new String(Character.toChars(cp)).getBytes(StandardCharsets.UTF_8));
                i += Character.charCount(cp);
            }
        }
        return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes.toByteArray())).toString();
    }
    static byte[] base32(String text) throws VaultException {
        if (text.length() > 208 || text.isEmpty()) throw invalid();
        if (!text.matches("[A-Za-z2-7=]+")) throw invalid();
        String value = text.toUpperCase(Locale.ROOT);
        int pad = value.indexOf('=');
        int length = pad < 0 ? value.length() : pad;
        int residue = length % 8;
        if (residue == 1 || residue == 3 || residue == 6) throw invalid();
        if (pad >= 0 && (value.length() % 8 != 0 || value.length() - length != (8 - residue) % 8
                || !value.substring(pad).matches("=+"))) throw invalid();
        byte[] bytes = new byte[length * 5 / 8];
        int bits = 0, buffer = 0, index = 0;
        try {
            for (int i = 0; i < length; i++) {
                char c = value.charAt(i);
                int n = c >= 'A' && c <= 'Z' ? c - 'A' : c >= '2' && c <= '7' ? c - '2' + 26 : -1;
                if (n < 0) throw invalid();
                buffer = (buffer << 5) | n; bits += 5;
                if (bits >= 8) { bits -= 8; bytes[index++] = (byte) (buffer >> bits); }
            }
            if (bytes.length < 10 || bytes.length > 128 || (buffer & ((1 << bits) - 1)) != 0) throw invalid();
            return bytes;
        } catch (VaultException failure) { VaultCodec.wipe(bytes); throw failure; }
    }
    static VaultException invalid() { return new VaultException("TOTP_INPUT", "Only standard TOTP SHA1/6/30 with a valid Base32 secret is supported"); }
    @Override public void close() { VaultCodec.wipe(seed); }
}
