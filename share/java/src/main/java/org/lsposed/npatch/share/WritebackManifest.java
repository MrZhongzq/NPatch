package org.lsposed.npatch.share;

import java.util.ArrayList;
import java.util.List;

/**
 * Staging protocol for mirror write-back, shared between the manager (writer) and the patch-loader
 * (applier). The manager stages manual changes under {@code <dataDir>/npatch_writeback/} and the
 * loader applies them into {@code databases/}(etc.) during app startup, before any SQLite database
 * is opened — the only place the real app data may be safely written in a rootless design.
 *
 * Layout under {@code <dataDir>/npatch_writeback/}:
 *   manifest.json   — this object, serialized with Gson
 *   payload/<relPath> — new content for each PUT entry
 *   .ready          — written LAST; the loader only applies a staging dir that has it (anti half-write)
 *
 * After applying, the loader writes {@code <dataDir>/files/npatch_writeback_applied} so the manager
 * can dequeue and rebuild its baseline.
 */
public final class WritebackManifest {

    public static final String DIR = "npatch_writeback";
    public static final String READY = ".ready";
    public static final String MANIFEST = "manifest.json";
    public static final String PAYLOAD = "payload";
    public static final String APPLIED_MARKER = "npatch_writeback_applied"; // under <dataDir>/files/
    public static final String OP_PUT = "PUT";
    public static final String OP_DELETE = "DELETE";

    public int version = 1;
    public List<Change> changes = new ArrayList<>();

    public static final class Change {
        public String relPath; // path relative to the app data root, e.g. "databases/msg.db"
        public String op;      // OP_PUT | OP_DELETE

        public Change() {}

        public Change(String relPath, String op) {
            this.relPath = relPath;
            this.op = op;
        }
    }
}
