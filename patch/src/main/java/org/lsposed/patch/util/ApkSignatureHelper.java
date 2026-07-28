package org.lsposed.patch.util;

import com.android.apksig.ApkVerifier;

import java.io.File;
import java.io.InputStream;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.io.UnsupportedEncodingException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;
import java.util.List;
import java.security.cert.Certificate;
import java.security.cert.X509Certificate;
import java.util.Enumeration;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

/**
 * Created by Wind
 */
public class ApkSignatureHelper {
    private static final byte[] APK_V2_MAGIC = {'A', 'P', 'K', ' ', 'S', 'i', 'g', ' ',
            'B', 'l', 'o', 'c', 'k', ' ', '4', '2'};

    private static char[] toChars(byte[] mSignature) {
        byte[] sig = mSignature;
        final int N = sig.length;
        final int N2 = N * 2;
        char[] text = new char[N2];
        for (int j = 0; j < N; j++) {
            byte v = sig[j];
            int d = (v >> 4) & 0xf;
            text[j * 2] = (char) (d >= 10 ? ('a' + d - 10) : ('0' + d));
            d = v & 0xf;
            text[j * 2 + 1] = (char) (d >= 10 ? ('a' + d - 10) : ('0' + d));
        }
        return text;
    }

    private static Certificate[] loadCertificates(JarFile jarFile, JarEntry je, byte[] readBuffer) {
        try {
            InputStream is = jarFile.getInputStream(je);
            while (is.read(readBuffer, 0, readBuffer.length) != -1) {
            }
            is.close();
            return (Certificate[]) (je != null ? je.getCertificates() : null);
        } catch (Exception e) {
        }
        return null;
    }

    public static String getApkSignInfo(String apkFilePath) {
        // Prefer apksig's ApkVerifier (bundled transitively via apkzlib): it correctly locates the
        // End-Of-Central-Directory even when the APK carries a ZIP comment (channel-packers like
        // Walle/VasDolly, SignApk, etc.) and understands every signature scheme (v1/v2/v3/v3.1).
        // The hand-rolled parsers below assume a comment-less ZIP and only recognise the v2 block
        // id, so a comment or a v3-only signature (common on modern high-minSdk apps) silently made
        // both return null -> "get original signature failed" on the very first patch.
        String sig = getApkSignApksig(apkFilePath);
        if (sig != null && !sig.isEmpty()) {
            return sig;
        }
        // Extract-only fallback: some inputs are apks that were already statically patched by
        // another tool (dex modified, v2/v3 stripped) but still carry the original v1 cert block in
        // META-INF/*.(RSA|DSA|EC). Every path above insists the apk *verify* before it will hand
        // over a certificate — apksig fails v1 integrity because the manifest digests no longer
        // match the tampered dex, and JarFile.getCertificates() (getApkSignV1) has the same
        // requirement. Here we only need the signer's certificate, not an integrity verdict, so we
        // parse the PKCS#7 block directly and skip digest checking entirely.
        sig = getApkSignPkcs7CertOnly(apkFilePath);
        if (sig != null && !sig.isEmpty()) {
            return sig;
        }
        // Fallback: legacy hand-rolled parsing, kept for resilience against exotic/broken packages.
        try {
            return getApkSignV2(apkFilePath);
        } catch (Exception e) {
            return getApkSignV1(apkFilePath);
        }
    }

    // Reads the signer certificate straight out of the v1 PKCS#7 block (META-INF/*.RSA|DSA|EC)
    // WITHOUT verifying any file digests. Works even when the apk was re-packaged/tampered and had
    // its v2/v3 signatures stripped, as long as the original v1 cert block is still present. The
    // X.509 CertificateFactory understands PKCS#7 SignedData and returns its embedded certs; the
    // first one is the signer's (post-rotation) leaf, matching what PackageManager reports.
    private static String getApkSignPkcs7CertOnly(String apkFilePath) {
        try (java.util.zip.ZipFile zip = new java.util.zip.ZipFile(apkFilePath)) {
            Enumeration<? extends java.util.zip.ZipEntry> entries = zip.entries();
            while (entries.hasMoreElements()) {
                java.util.zip.ZipEntry entry = entries.nextElement();
                String name = entry.getName();
                String upper = name.toUpperCase();
                if (!upper.startsWith("META-INF/") || name.indexOf('/', "META-INF/".length()) >= 0) {
                    continue; // signature blocks live directly under META-INF/, not in subdirs
                }
                if (!(upper.endsWith(".RSA") || upper.endsWith(".DSA") || upper.endsWith(".EC"))) {
                    continue;
                }
                try (InputStream is = zip.getInputStream(entry)) {
                    java.security.cert.CertificateFactory cf =
                            java.security.cert.CertificateFactory.getInstance("X.509");
                    for (Certificate cert : cf.generateCertificates(is)) {
                        if (cert instanceof X509Certificate) {
                            return new String(toChars(cert.getEncoded()));
                        }
                    }
                }
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    private static String getApkSignApksig(String apkFilePath) {
        try {
            ApkVerifier verifier = new ApkVerifier.Builder(new File(apkFilePath)).build();
            ApkVerifier.Result result = verifier.verify();
            // We only need the signer certificate, not a full integrity verdict: getSignerCertificates()
            // is populated from whichever scheme block parsed successfully, so we don't gate on
            // isVerified(). The first entry is the current (post-rotation) signing certificate, which is
            // what PackageManager / detectors observe.
            List<X509Certificate> certs = result.getSignerCertificates();
            if (certs != null && !certs.isEmpty()) {
                return new String(toChars(certs.get(0).getEncoded()));
            }
        } catch (Throwable ignored) {
            // Fall through to the legacy parsers.
        }
        return null;
    }

    public static String getApkSignV1(String apkFilePath) {
        byte[] readBuffer = new byte[8192];
        Certificate[] certs = null;
        try {
            JarFile jarFile = new JarFile(apkFilePath);
            Enumeration<?> entries = jarFile.entries();
            while (entries.hasMoreElements()) {
                JarEntry je = (JarEntry) entries.nextElement();
                if (je.isDirectory()) {
                    continue;
                }
                if (je.getName().startsWith("META-INF/")) {
                    continue;
                }
                Certificate[] localCerts = loadCertificates(jarFile, je, readBuffer);
                if (certs == null) {
                    certs = localCerts;
                } else {
                    for (int i = 0; i < certs.length; i++) {
                        boolean found = false;
                        for (int j = 0; j < localCerts.length; j++) {
                            if (certs[i] != null && certs[i].equals(localCerts[j])) {
                                found = true;
                                break;
                            }
                        }
                        if (!found || certs.length != localCerts.length) {
                            jarFile.close();
                            return null;
                        }
                    }
                }
            }
            jarFile.close();
            return certs != null ? new String(toChars(certs[0].getEncoded())) : null;
        } catch (Throwable ignored) {
        }
        return null;
    }

    private static String getApkSignV2(String apkFilePath) throws IOException {
        try (RandomAccessFile apk = new RandomAccessFile(apkFilePath, "r")) {
            ByteBuffer buffer = ByteBuffer.allocate(0x10);
            buffer.order(ByteOrder.LITTLE_ENDIAN);

            apk.seek(apk.length() - 0x6);
            apk.readFully(buffer.array(), 0x0, 0x6);
            int offset = buffer.getInt();
            if (buffer.getShort() != 0) {
                throw new UnsupportedEncodingException("no zip");
            }

            apk.seek(offset - 0x10);
            apk.readFully(buffer.array(), 0x0, 0x10);

            if (!Arrays.equals(buffer.array(), APK_V2_MAGIC)) {
                throw new UnsupportedEncodingException("no apk v2");
            }

            // Read and compare size fields
            apk.seek(offset - 0x18);
            apk.readFully(buffer.array(), 0x0, 0x8);
            buffer.rewind();
            int size = (int) buffer.getLong();

            ByteBuffer block = ByteBuffer.allocate(size + 0x8);
            block.order(ByteOrder.LITTLE_ENDIAN);
            apk.seek(offset - block.capacity());
            apk.readFully(block.array(), 0x0, block.capacity());

            if (size != block.getLong()) {
                throw new UnsupportedEncodingException("no apk v2");
            }

            while (block.remaining() > 24) {
                size = (int) block.getLong();
                if (block.getInt() == 0x7109871a) {
                    // SignerSequence length(4) + Signer length(4) + SignedData length(4)
                    block.position(block.position() + 12);
                    // 读取 DigestsSequence
                    int digestsLen = block.getInt();
                    block.position(block.position() + digestsLen);
                    // 不使用，仅为了移动指针
                    block.getInt();
                    // 这里赋值给 size，供循环外读取数据使用
                    size = block.getInt();
                    // 此时 block 指针正好位于第一个证书数据的开头
                    break;
                } else {
                    block.position(block.position() + size - 0x4);
                }
            }

            byte[] certificate = new byte[size];
            block.get(certificate);

            return new String(toChars(certificate));
        }
    }
}
