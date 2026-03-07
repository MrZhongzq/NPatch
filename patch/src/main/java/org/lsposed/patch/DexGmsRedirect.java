package org.lsposed.patch;

import org.lsposed.patch.util.Logger;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.zip.Adler32;

/**
 * Patches DEX files to redirect Google Play Services references to a MicroG variant.
 * Scans the DEX string table and replaces com.google.android.gms with the target package.
 * Also replaces com.google.android.gsf (Google Services Framework).
 *
 * This is the same approach used by ReVanced to redirect GMS calls at bytecode level.
 */
public class DexGmsRedirect {
    private static final String REAL_GMS = "com.google.android.gms";
    private static final String REAL_GSF = "com.google.android.gsf";

    /**
     * Patch a DEX file's string table to redirect GMS package references.
     * The replacement must be EXACTLY the same length as the original to avoid
     * breaking DEX file offsets.
     *
     * @param dexBytes the original DEX file bytes
     * @param targetGms target package name (must be same length as com.google.android.gms)
     * @param logger for logging
     * @return patched DEX bytes, or original if target length doesn't match
     */
    public static byte[] patchDex(byte[] dexBytes, String targetGms, Logger logger) {
        if (targetGms == null || targetGms.equals(REAL_GMS)) {
            return dexBytes;
        }

        // GMS package name lengths must match for safe in-place replacement
        // com.google.android.gms = 23 chars
        // app.revanced.android.gms = 24 chars (TOO LONG for direct replacement!)
        // We need to use a padded approach or use a same-length package

        byte[] result = dexBytes.clone();
        byte[] searchGms = REAL_GMS.getBytes(StandardCharsets.UTF_8);
        byte[] searchGsf = REAL_GSF.getBytes(StandardCharsets.UTF_8);

        // For different-length replacements, we need to modify the MUTF-8 string entries
        // in the DEX string table. Each string entry has: uleb128 length + utf8 data + null terminator
        // We can only do same-length replacements safely without rebuilding the entire DEX.

        // If lengths differ, pad the shorter one with dots or use a wrapper approach
        byte[] replaceGms;
        if (targetGms.length() == REAL_GMS.length()) {
            replaceGms = targetGms.getBytes(StandardCharsets.UTF_8);
        } else if (targetGms.length() < REAL_GMS.length()) {
            // Pad target with trailing characters (this will break the package name)
            // Not ideal - skip this case
            logger.e("Target GMS package '" + targetGms + "' is shorter than original - skipping DEX patch");
            return dexBytes;
        } else {
            // Target is longer - can't do in-place replacement
            // We'll replace with a truncated version and handle at runtime
            logger.i("Target GMS package differs in length - using partial replacement strategy");
            // Replace only the prefix "com.google" -> target prefix of same length
            // Actually, for app.revanced.android.gms (24 chars) vs com.google.android.gms (23 chars)
            // This won't work with simple byte replacement. Need a different approach.
            // Fall back to runtime-only hooks
            logger.i("DEX string replacement skipped - relying on runtime hooks");
            return dexBytes;
        }

        int count = 0;
        for (int i = 0; i <= result.length - searchGms.length; i++) {
            if (matchBytes(result, i, searchGms)) {
                System.arraycopy(replaceGms, 0, result, i, replaceGms.length);
                count++;
            }
        }

        // Also replace GSF if same length
        if (REAL_GSF.length() == targetGms.length()) {
            byte[] replaceGsf = targetGms.getBytes(StandardCharsets.UTF_8);
            for (int i = 0; i <= result.length - searchGsf.length; i++) {
                if (matchBytes(result, i, searchGsf)) {
                    System.arraycopy(replaceGsf, 0, result, i, replaceGsf.length);
                    count++;
                }
            }
        }

        if (count > 0) {
            logger.i("Replaced " + count + " GMS references in DEX");
            // Fix DEX checksum and signature
            fixDexChecksums(result);
        }

        return result;
    }

    private static boolean matchBytes(byte[] data, int offset, byte[] pattern) {
        for (int j = 0; j < pattern.length; j++) {
            if (data[offset + j] != pattern[j]) return false;
        }
        return true;
    }

    /**
     * Recalculate DEX file SHA-1 signature (offset 12, 20 bytes)
     * and Adler32 checksum (offset 8, 4 bytes).
     */
    private static void fixDexChecksums(byte[] dex) {
        try {
            // SHA-1 signature covers bytes from offset 32 to end
            MessageDigest md = MessageDigest.getInstance("SHA-1");
            md.update(dex, 32, dex.length - 32);
            byte[] sha1 = md.digest();
            System.arraycopy(sha1, 0, dex, 12, 20);

            // Adler32 checksum covers bytes from offset 12 to end
            Adler32 adler = new Adler32();
            adler.update(dex, 12, dex.length - 12);
            long checksum = adler.getValue();
            ByteBuffer buf = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN);
            buf.putInt((int) checksum);
            System.arraycopy(buf.array(), 0, dex, 8, 4);
        } catch (Exception ignored) {}
    }

    public static byte[] readStream(InputStream is) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = is.read(buf)) != -1) {
            baos.write(buf, 0, n);
        }
        return baos.toByteArray();
    }
}
