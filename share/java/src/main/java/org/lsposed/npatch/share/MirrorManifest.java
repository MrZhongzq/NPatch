package org.lsposed.npatch.share;

import java.io.File;
import java.io.IOException;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Deque;

/**
 * Flat manifest of a data tree, shared between NPatchDataProvider (writer, runs in the target app
 * process and walks the tree with local File APIs) and MirrorSyncManager (parser). Replaces the
 * per-directory provider query storm: the provider streams one manifest instead of the manager
 * issuing one binder query per directory.
 *
 * Line format: {@code type\tsize\tmtime\trelPath\n}, type 'f' (file) or 'd' (directory, size 0).
 * relPath is relative to the tree root, '/'-separated. Directories (including empty ones) are
 * listed so the mirror can reflect the full structure (e.g. so a user can drop a .nomedia into an
 * otherwise-empty dir). Pure logic (java.io.File only, no android.*), unit-testable on a plain JVM.
 */
public final class MirrorManifest {

    private MirrorManifest() {}

    public static final char TYPE_FILE = 'f';
    public static final char TYPE_DIR = 'd';

    public static final class Entry {
        public final char type;
        public final long size;
        public final long mtime;
        public final String path;

        public Entry(char type, long size, long mtime, String path) {
            this.type = type;
            this.size = size;
            this.mtime = mtime;
            this.path = path;
        }

        public boolean isDirectory() {
            return type == TYPE_DIR;
        }
    }

    /** Iteratively walk {@code root} and append one line per file and directory beneath it. */
    public static void write(File root, Appendable out) throws IOException {
        if (root == null || !root.isDirectory()) {
            return;
        }
        Deque<Object[]> stack = new ArrayDeque<>();
        stack.push(new Object[]{root, ""});
        while (!stack.isEmpty()) {
            Object[] frame = stack.pop();
            File dir = (File) frame[0];
            String prefix = (String) frame[1];
            File[] children = dir.listFiles();
            if (children == null) {
                continue;
            }
            Arrays.sort(children, (a, b) -> a.getName().compareTo(b.getName()));
            for (File child : children) {
                String name = child.getName();
                // Names with tab/newline would break the line format; skip (they're also almost
                // always illegal on the sdcardfs mirror anyway).
                if (name.indexOf('\t') >= 0 || name.indexOf('\n') >= 0) {
                    continue;
                }
                String rel = prefix.isEmpty() ? name : prefix + "/" + name;
                if (child.isDirectory()) {
                    out.append("d\t0\t").append(Long.toString(child.lastModified()))
                            .append('\t').append(rel).append('\n');
                    stack.push(new Object[]{child, rel});
                } else if (child.isFile()) {
                    out.append("f\t").append(Long.toString(child.length())).append('\t')
                            .append(Long.toString(child.lastModified()))
                            .append('\t').append(rel).append('\n');
                }
            }
        }
    }

    /** Parse one manifest line; returns null on a malformed line (too few fields / bad numbers). */
    public static Entry parseLine(String line) {
        if (line == null || line.isEmpty()) {
            return null;
        }
        String[] parts = line.split("\t", 4);
        if (parts.length < 4 || parts[0].length() != 1) {
            return null;
        }
        try {
            char type = parts[0].charAt(0);
            long size = Long.parseLong(parts[1]);
            long mtime = Long.parseLong(parts[2]);
            return new Entry(type, size, mtime, parts[3]);
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
