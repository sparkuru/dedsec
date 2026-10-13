package im.majo.ctos;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.content.ContentResolver;
import android.net.Uri;
import com.google.zxing.BinaryBitmap;
import com.google.zxing.RGBLuminanceSource;
import com.google.zxing.Result;
import com.google.zxing.DecodeHintType;
import com.google.zxing.common.HybridBinarizer;
import com.google.zxing.multi.qrcode.QRCodeMultiReader;
import java.io.InputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;

/** Reads only the current user-selected URI. No persistent grant, image file or barcode cache. */
final class TotpQrImages {
    static final int MAX_BYTES = 10 * 1024 * 1024, MAX_SIDE = 8192, MAX_PIXELS = 16000000;
    static final class Decoded implements AutoCloseable {
        final List<TotpInput> inputs;
        final int invalid;
        Decoded(List<TotpInput> inputs, int invalid) { this.inputs = inputs; this.invalid = invalid; }
        @Override public void close() { for (TotpInput input : inputs) input.close(); }
    }
    static void dimensions(int width, int height) throws VaultException {
        if (width <= 0 || height <= 0 || width > MAX_SIDE || height > MAX_SIDE || (long) width * height > MAX_PIXELS)
            throw new VaultException("TOTP_LIMIT", "The selected image is too large or invalid");
    }
    static byte[] read(InputStream stream, AppSecrets.EffectGuard guard) throws IOException, VaultException {
        if (stream == null) throw new IOException("Image unavailable");
        try (VaultCodec.SecretBuffer buffer = new VaultCodec.SecretBuffer()) {
            byte[] chunk = new byte[8192];
            try {
                int count;
                while ((count = stream.read(chunk)) != -1) {
                    guard.check();
                    if (buffer.size() + (long) count > MAX_BYTES) throw new VaultException("TOTP_LIMIT", "The selected image exceeds 10 MiB");
                    buffer.write(chunk, 0, count);
                }
                return buffer.toByteArray();
            } finally { VaultCodec.wipe(chunk); }
        }
    }
    static Decoded decode(ContentResolver resolver, Uri uri, AppSecrets.EffectGuard guard) throws VaultException {
        return decode(resolver, uri, guard, new android.os.CancellationSignal());
    }
    static Decoded decode(ContentResolver resolver, Uri uri, AppSecrets.EffectGuard guard, android.os.CancellationSignal cancellation) throws VaultException {
        byte[] bytes = null;
        Bitmap bitmap = null;
        int[] pixels = null;
        Decoded decoded = null;
        try (android.content.res.AssetFileDescriptor descriptor = resolver.openAssetFileDescriptor(uri, "r", cancellation);
                InputStream stream = descriptor == null ? null : descriptor.createInputStream()) {
            bytes = read(stream, guard);
            BitmapFactory.Options bounds = new BitmapFactory.Options(); bounds.inJustDecodeBounds = true;
            BitmapFactory.decodeByteArray(bytes, 0, bytes.length, bounds); dimensions(bounds.outWidth, bounds.outHeight);
            guard.check();
            BitmapFactory.Options options = new BitmapFactory.Options(); options.inPreferredConfig = Bitmap.Config.ARGB_8888; options.inMutable = true;
            bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.length, options);
            if (bitmap == null) throw new VaultException("TOTP_IMAGE", "The selected image cannot be decoded");
            dimensions(bitmap.getWidth(), bitmap.getHeight());
            pixels = new int[bitmap.getWidth() * bitmap.getHeight()];
            bitmap.getPixels(pixels, 0, bitmap.getWidth(), 0, 0, bitmap.getWidth(), bitmap.getHeight());
            decoded = decodePixels(pixels, bitmap.getWidth(), bitmap.getHeight(), guard);
        } catch (VaultException failure) { if (decoded != null) decoded.close(); throw failure; }
        catch (OutOfMemoryError failure) { if (decoded != null) decoded.close(); throw new VaultException("TOTP_LIMIT", "Insufficient memory for this image"); }
        catch (Exception failure) { if (decoded != null) decoded.close(); throw new VaultException("TOTP_IMAGE", "The selected image is unavailable or damaged"); }
        finally {
            VaultCodec.wipe(bytes); if (pixels != null) Arrays.fill(pixels, 0);
            if (bitmap != null) { if (bitmap.isMutable()) bitmap.eraseColor(0); bitmap.recycle(); }
        }
        return decoded;
    }
    static Decoded decodePixels(int[] pixels, int width, int height, AppSecrets.EffectGuard guard) throws VaultException {
        dimensions(width, height); guard.check();
        if (pixels == null || pixels.length != (long) width * height) throw new VaultException("TOTP_IMAGE", "Invalid pixel buffer");
        Result[] results;
        try {
            BinaryBitmap image = new BinaryBitmap(new HybridBinarizer(new RGBLuminanceSource(width, height, pixels)));
            results = new QRCodeMultiReader().decodeMultiple(image, java.util.Collections.singletonMap(DecodeHintType.TRY_HARDER, Boolean.TRUE));
        } catch (com.google.zxing.NotFoundException noCodes) { throw new VaultException("TOTP_NO_CODES", "No QR codes were found"); }
        guard.check();
        String[] texts = new String[results.length];
        for (int i = 0; i < results.length; i++) texts[i] = results[i].getText();
        return parse(texts, guard);
    }
    static Decoded parse(String[] texts, AppSecrets.EffectGuard guard) throws VaultException {
        if (texts.length > TotpPreviews.MAX_CANDIDATES) throw new VaultException("TOTP_LIMIT", "More than sixteen QR codes");
        List<TotpInput> inputs = new ArrayList<>(); int invalid = 0;
        HashSet<String> seen = new HashSet<>();
        try {
            for (String text : texts) {
                guard.check();
                if (text == null) { invalid++; continue; }
                if (!seen.add(text)) continue;
                try { inputs.add(TotpInput.uri(text)); }
                catch (VaultException unsupported) { invalid++; }
            }
            guard.check(); return new Decoded(inputs, invalid);
        } catch (VaultException | RuntimeException | Error failure) { for (TotpInput input : inputs) input.close(); throw failure; }
    }
}
