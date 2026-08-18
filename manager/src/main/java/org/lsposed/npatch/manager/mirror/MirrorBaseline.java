package org.lsposed.npatch.manager.mirror;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Baseline of the mirror tree: a snapshot of every regular file's (size, mtime), taken right after
 * the sync engine last wrote the mirror. It lets the next sync tell apart changes the user made in
 * the mirror (which must be written back) from changes the sync engine itself produced (which must
 * not). The mtime recorded is the mirror-side (local) measured value — never the remote's — so the
 * comparison is immune to the cross-filesystem timestamp drift (ext4 ms vs sdcardfs second rounding)
 * that made the old bidirectional sync misjudge direction and corrupt live SQLite databases.
 *
 * Pure logic (File/Map/Gson only, no android.*) so it is unit-testable on a plain JVM.
 */
public final class MirrorBaseline {

    private MirrorBaseline() {}

    private static final Gson GSON = new Gson();
    private static final Type SIG_MAP_TYPE = new TypeToken<HashMap<String, FileSig>>() {}.getType();

    /** Signature of one file in the mirror. Equality is what {@link #diff} keys "modified" on. */
    public static final class FileSig {
        public final long size;
        public final long mtime;

        public FileSig(long size, long mtime) {
            this.size = size;
            this.mtime = mtime;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof FileSig)) return false;
            FileSig that = (FileSig) o;
            return size == that.size && mtime == that.mtime;
        }

        @Override
        public int hashCode() {
            return (int) (size * 31 + mtime);
        }

        @Override
        public String toString() {
            return "FileSig(" + size + "," + mtime + ")";
        }
    }

    /** The manual changes detected in the mirror relative to the baseline. */
    public static final class ChangeSet {
        public final Set<String> added;
        public final Set<String> modified;
        public final Set<String> deleted;

        public ChangeSet(Set<String> added, Set<String> modified, Set<String> deleted) {
            this.added = added;
            this.modified = modified;
            this.deleted = deleted;
        }

        public boolean isEmpty() {
            return added.isEmpty() && modified.isEmpty() && deleted.isEmpty();
        }
    }

    /** Walk {@code root} and record every regular file by its path relative to root (using '/'). */
    public static Map<String, FileSig> snapshot(File root) {
        Map<String, FileSig> out = new HashMap<>();
        if (root == null || !root.isDirectory()) {
            return out;
        }
        String rootPath = root.getAbsolutePath();
        int prefixLen = rootPath.length() + 1; // skip root path + separator
        Deque<File> stack = new ArrayDeque<>();
        stack.push(root);
        while (!stack.isEmpty()) {
            File dir = stack.pop();
            File[] children = dir.listFiles();
            if (children == null) continue;
            for (File child : children) {
                if (child.isDirectory()) {
                    stack.push(child);
                } else if (child.isFile()) {
                    String rel = child.getAbsolutePath().substring(prefixLen).replace(File.separatorChar, '/');
                    out.put(rel, new FileSig(child.length(), child.lastModified()));
                }
            }
        }
        return out;
    }

    /** Diff current snapshot against the stored baseline. */
    public static ChangeSet diff(Map<String, FileSig> current, Map<String, FileSig> baseline) {
        Set<String> added = new HashSet<>();
        Set<String> modified = new HashSet<>();
        Set<String> deleted = new HashSet<>();
        for (Map.Entry<String, FileSig> e : current.entrySet()) {
            FileSig base = baseline.get(e.getKey());
            if (base == null) {
                added.add(e.getKey());
            } else if (!base.equals(e.getValue())) {
                modified.add(e.getKey());
            }
        }
        for (String key : baseline.keySet()) {
            if (!current.containsKey(key)) {
                deleted.add(key);
            }
        }
        return new ChangeSet(added, modified, deleted);
    }

    /** Load the persisted baseline for a package; empty map when none exists or on any error. */
    public static Map<String, FileSig> load(File baselineDir, String pkg) {
        File f = new File(baselineDir, pkg + ".json");
        if (!f.isFile()) {
            return new HashMap<>();
        }
        try {
            String json = new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
            Map<String, FileSig> map = GSON.fromJson(json, SIG_MAP_TYPE);
            return map != null ? map : new HashMap<String, FileSig>();
        } catch (Exception e) {
            return new HashMap<>();
        }
    }

    /** Persist the baseline for a package. */
    public static void save(File baselineDir, String pkg, Map<String, FileSig> sigs) {
        try {
            if (!baselineDir.isDirectory()) {
                //noinspection ResultOfMethodCallIgnored
                baselineDir.mkdirs();
            }
            File f = new File(baselineDir, pkg + ".json");
            Files.write(f.toPath(), GSON.toJson(sigs).getBytes(StandardCharsets.UTF_8));
        } catch (IOException e) {
            // best-effort; a lost baseline just means one extra export round next time
        }
    }
}
