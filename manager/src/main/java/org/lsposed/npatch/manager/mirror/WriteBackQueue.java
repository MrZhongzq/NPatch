package org.lsposed.npatch.manager.mirror;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;

import java.io.File;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Persistent set of packages that have a ready write-back staging awaiting the loader to apply it on
 * next start. When Shizuku is unavailable the entries simply stay queued (write-back is far rarer
 * than read/export), and the staging — already written to the target app's private dir — is applied
 * whenever the user next reopens the app.
 *
 * Pure logic (File/Gson), unit-testable on a plain JVM.
 */
public final class WriteBackQueue {

    private WriteBackQueue() {}

    private static final Gson GSON = new Gson();
    private static final Type SET_TYPE = new TypeToken<LinkedHashSet<String>>() {}.getType();

    public static synchronized void markReady(File queueFile, String pkg) {
        Set<String> all = all(queueFile);
        all.add(pkg);
        persist(queueFile, all);
    }

    public static synchronized boolean isPending(File queueFile, String pkg) {
        return all(queueFile).contains(pkg);
    }

    public static synchronized void clear(File queueFile, String pkg) {
        Set<String> all = all(queueFile);
        if (all.remove(pkg)) {
            persist(queueFile, all);
        }
    }

    public static synchronized Set<String> all(File queueFile) {
        if (queueFile == null || !queueFile.isFile()) {
            return new LinkedHashSet<>();
        }
        try {
            String json = new String(Files.readAllBytes(queueFile.toPath()), StandardCharsets.UTF_8);
            Set<String> set = GSON.fromJson(json, SET_TYPE);
            return set != null ? set : new LinkedHashSet<String>();
        } catch (Exception e) {
            return new LinkedHashSet<>();
        }
    }

    private static void persist(File queueFile, Set<String> set) {
        try {
            File parent = queueFile.getParentFile();
            if (parent != null && !parent.isDirectory()) {
                //noinspection ResultOfMethodCallIgnored
                parent.mkdirs();
            }
            Files.write(queueFile.toPath(), GSON.toJson(set).getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            // best-effort; a lost queue entry just means the pending staging is applied on next
            // manual open rather than being proactively surfaced
        }
    }
}
