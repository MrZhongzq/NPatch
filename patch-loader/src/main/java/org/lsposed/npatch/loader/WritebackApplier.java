package org.lsposed.npatch.loader;

import com.google.gson.Gson;

import org.lsposed.npatch.share.WritebackManifest;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;

/**
 * Applies a staged mirror write-back into the app's data directory during startup, BEFORE the host
 * app opens any SQLite database. Runs with the app's own uid (the only principal allowed to write
 * {@code /data/data/<pkg>} in a rootless design) and while no database handle is live, so it is the
 * one safe window to touch the real {@code databases/} files — unlike the old 30s bidirectional
 * sync that truncated live databases and corrupted chat records.
 *
 * Pure file logic (java.io.File + Gson, no android.*), unit-testable on a plain JVM by pointing
 * {@code dataDir} at a temp directory.
 */
public final class WritebackApplier {

    private WritebackApplier() {}

    private static final Gson GSON = new Gson();

    /**
     * If {@code <dataDir>/npatch_writeback/.ready} exists, apply the staged changes into dataDir and
     * clear the staging dir. No-op (returns false) when there is no ready staging. Never throws for
     * missing/partial staging — a half-written staging (no {@code .ready}) is ignored and left intact
     * so the next start can retry.
     *
     * @return true iff a staging was applied
     */
    public static boolean applyIfPending(File dataDir) {
        File stagingDir = new File(dataDir, WritebackManifest.DIR);
        File ready = new File(stagingDir, WritebackManifest.READY);
        if (!ready.isFile()) {
            return false;
        }
        File manifestFile = new File(stagingDir, WritebackManifest.MANIFEST);
        if (!manifestFile.isFile()) {
            return false;
        }
        WritebackManifest manifest;
        try {
            String json = new String(Files.readAllBytes(manifestFile.toPath()), StandardCharsets.UTF_8);
            manifest = GSON.fromJson(json, WritebackManifest.class);
        } catch (Exception e) {
            return false;
        }
        if (manifest == null || manifest.changes == null) {
            return false;
        }

        File payloadDir = new File(stagingDir, WritebackManifest.PAYLOAD);
        try {
            for (WritebackManifest.Change change : manifest.changes) {
                if (change == null || change.relPath == null || change.op == null) {
                    continue;
                }
                File target = new File(dataDir, change.relPath);
                if (WritebackManifest.OP_DELETE.equals(change.op)) {
                    deleteQuietly(target);
                } else if (WritebackManifest.OP_PUT.equals(change.op)) {
                    File src = new File(payloadDir, change.relPath);
                    if (!src.isFile()) {
                        continue; // manifest/payload mismatch: skip rather than corrupt
                    }
                    atomicReplace(src, target);
                }
            }
        } catch (IOException e) {
            // Leave .ready in place so the next start retries; do not mark applied.
            return false;
        }

        // Success: drop the whole staging dir and record the applied marker for the manager.
        deleteRecursively(stagingDir);
        writeAppliedMarker(dataDir);
        return true;
    }

    private static void atomicReplace(File src, File target) throws IOException {
        File parent = target.getParentFile();
        if (parent != null && !parent.isDirectory()) {
            //noinspection ResultOfMethodCallIgnored
            parent.mkdirs();
        }
        File tmp = new File(target.getParentFile(), target.getName() + ".npatch_tmp");
        Files.copy(src.toPath(), tmp.toPath(), StandardCopyOption.REPLACE_EXISTING);
        try {
            Files.move(tmp.toPath(), target.toPath(),
                    StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException atomicUnsupported) {
            Files.move(tmp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static void writeAppliedMarker(File dataDir) {
        try {
            File filesDir = new File(dataDir, "files");
            if (!filesDir.isDirectory()) {
                //noinspection ResultOfMethodCallIgnored
                filesDir.mkdirs();
            }
            File marker = new File(filesDir, WritebackManifest.APPLIED_MARKER);
            Files.write(marker.toPath(), String.valueOf(System.currentTimeMillis()).getBytes(StandardCharsets.UTF_8));
        } catch (IOException e) {
            // marker is best-effort; the manager will simply not dequeue this round
        }
    }

    private static void deleteQuietly(File f) {
        //noinspection ResultOfMethodCallIgnored
        f.delete();
    }

    private static void deleteRecursively(File f) {
        if (f.isDirectory()) {
            File[] children = f.listFiles();
            if (children != null) {
                for (File c : children) {
                    deleteRecursively(c);
                }
            }
        }
        //noinspection ResultOfMethodCallIgnored
        f.delete();
    }
}
