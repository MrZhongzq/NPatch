package org.lsposed.npatch.loader;

import static org.lsposed.npatch.share.Constants.ORIGINAL_APK_ASSET_PATH;

import android.content.pm.ApplicationInfo;
import android.util.Log;

import org.lsposed.npatch.loader.util.FileUtils;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.charset.StandardCharsets;
import java.util.Enumeration;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

public class OriginApkHelper {

    private static final String TAG = "NPatch-ApkHelper";
    private static final int PER_USER_RANGE = 100000;
    private static final Pattern DEX_ENTRY_PATTERN = Pattern.compile("^classes(\\d*)\\.dex$");

    public static Path prepareOriginApk(ApplicationInfo appInfo, ClassLoader baseClassLoader, boolean injectProvider) throws IOException {
        Path internalOriginDir = Paths.get(appInfo.dataDir, "cache/npatch/origin/");
        long sourceCrc = getOriginalApkCrc(appInfo.sourceDir);
        String patchedSourceDir = appInfo.sourceDir;

        Path internalCacheApk = internalOriginDir.resolve(sourceCrc + ".apk");

        int userId = appInfo.uid / PER_USER_RANGE;
        Path externalOriginPath = Paths.get("/storage/emulated/" + userId + "/Android/data/" + appInfo.packageName + "/cache/npatch/origin/origin.apk");

        Log.d(TAG, "Checking external APK at: " + externalOriginPath);

        if (!Files.exists(internalOriginDir)) {
            Files.createDirectories(internalOriginDir);
        }

        boolean externalExists = Files.exists(externalOriginPath);
        boolean refreshedFromSource = false;

        if (externalExists) {
            Log.i(TAG, "External origin.apk found! Overwriting internal cache.");
            copyFile(externalOriginPath, internalCacheApk);
            refreshedFromSource = true;
        } else {
            if (!Files.exists(internalCacheApk)) {
                Log.i(TAG, "Extracting origin.apk from assets.");
                FileUtils.deleteFolderIfExists(internalOriginDir);
                Files.createDirectories(internalOriginDir);
                copyOriginFromAssets(baseClassLoader, internalCacheApk);
                refreshedFromSource = true;
            } else {
                Log.d(TAG, "Internal cache hit: " + internalCacheApk);
            }
        }

        if (injectProvider) {
            ProviderDex providerDex = readPatchedProviderDex(patchedSourceDir);
            Path providerMarker = internalOriginDir.resolve(sourceCrc + "-" + providerDex.crc + ".provider");
            if (!Files.exists(providerMarker)) {
                if (!refreshedFromSource) {
                    if (externalExists) {
                        copyFile(externalOriginPath, internalCacheApk);
                    } else {
                        copyOriginFromAssets(baseClassLoader, internalCacheApk);
                    }
                }
                injectProviderDex(internalCacheApk, providerDex.bytes);
                clearProviderMarkers(internalOriginDir, sourceCrc);
                Files.write(providerMarker, Long.toString(providerDex.crc).getBytes(StandardCharsets.UTF_8),
                        StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
                Log.i(TAG, "Injected provider dex into cached origin apk");
            }
        }

        try {
            internalCacheApk.toFile().setWritable(false);
        } catch (Exception ignored) {
        }

        return internalCacheApk;
    }

    public static long getOriginalApkCrc(String sourceDir) throws IOException {
        try (ZipFile sourceFile = new ZipFile(sourceDir)) {
            ZipEntry entry = sourceFile.getEntry(ORIGINAL_APK_ASSET_PATH);
            if (entry == null) {
                return 0;
            }
            return entry.getCrc();
        }
    }

    private static void copyOriginFromAssets(ClassLoader baseClassLoader, Path internalCacheApk) throws IOException {
        try (InputStream is = baseClassLoader.getResourceAsStream(ORIGINAL_APK_ASSET_PATH)) {
            if (is == null) throw new IOException("Original APK not found in assets");
            Files.copy(is, internalCacheApk, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static void copyFile(Path source, Path destination) throws IOException {
        try (InputStream in = Files.newInputStream(source)) {
            Files.copy(in, destination, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static ProviderDex readPatchedProviderDex(String patchedSourceDir) throws IOException {
        try (ZipFile patchedApk = new ZipFile(patchedSourceDir)) {
            ZipEntry classesDex = patchedApk.getEntry("classes.dex");
            if (classesDex == null) {
                throw new IOException("Patched metaloader dex not found in classes.dex");
            }
            try (InputStream is = patchedApk.getInputStream(classesDex)) {
                return new ProviderDex(is.readAllBytes(), classesDex.getCrc());
            }
        }
    }

    private static void injectProviderDex(Path targetApk, byte[] providerDexBytes) throws IOException {
        Path tempApk = Files.createTempFile(targetApk.getParent(), "origin-provider-", ".apk");
        String nextDexName = findNextDexName(targetApk);

        try (ZipInputStream zis = new ZipInputStream(Files.newInputStream(targetApk));
             ZipOutputStream zos = new ZipOutputStream(Files.newOutputStream(tempApk, StandardOpenOption.TRUNCATE_EXISTING))) {
            ZipEntry entry;
            byte[] buffer = new byte[8192];
            while ((entry = zis.getNextEntry()) != null) {
                ZipEntry outEntry = new ZipEntry(entry.getName());
                outEntry.setMethod(entry.getMethod());
                if (entry.getMethod() == ZipEntry.STORED) {
                    outEntry.setSize(entry.getSize());
                    outEntry.setCompressedSize(entry.getCompressedSize());
                    outEntry.setCrc(entry.getCrc());
                }
                outEntry.setTime(entry.getTime());
                zos.putNextEntry(outEntry);
                int read;
                while ((read = zis.read(buffer)) != -1) {
                    zos.write(buffer, 0, read);
                }
                zos.closeEntry();
                zis.closeEntry();
            }

            ZipEntry providerEntry = new ZipEntry(nextDexName);
            zos.putNextEntry(providerEntry);
            zos.write(providerDexBytes);
            zos.closeEntry();
        }

        Files.move(tempApk, targetApk, StandardCopyOption.REPLACE_EXISTING);
    }

    private static String findNextDexName(Path targetApk) throws IOException {
        int maxDexIndex = 1;
        try (ZipFile zipFile = new ZipFile(targetApk.toFile())) {
            Enumeration<? extends ZipEntry> entries = zipFile.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                Matcher matcher = DEX_ENTRY_PATTERN.matcher(entry.getName());
                if (!matcher.matches()) {
                    continue;
                }
                String digits = matcher.group(1);
                int index = digits == null || digits.isEmpty() ? 1 : Integer.parseInt(digits);
                if (index > maxDexIndex) {
                    maxDexIndex = index;
                }
            }
        }
        return "classes" + (maxDexIndex + 1) + ".dex";
    }

    private static void clearProviderMarkers(Path internalOriginDir, long sourceCrc) throws IOException {
        String markerPrefix = sourceCrc + "-";
        try (var stream = Files.list(internalOriginDir)) {
            stream.filter(path -> {
                        String name = path.getFileName().toString();
                        return name.startsWith(markerPrefix) && name.endsWith(".provider");
                    })
                    .forEach(path -> {
                        try {
                            Files.deleteIfExists(path);
                        } catch (IOException ignored) {
                        }
                    });
        }
    }

    private static final class ProviderDex {
        final byte[] bytes;
        final long crc;

        ProviderDex(byte[] bytes, long crc) {
            this.bytes = bytes;
            this.crc = crc;
        }
    }
}
