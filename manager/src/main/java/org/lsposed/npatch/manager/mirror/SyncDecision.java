package org.lsposed.npatch.manager.mirror;

/**
 * The per-file direction decision that replaces the old bidirectional mtime/size guessing.
 * Write-back happens ONLY when the mirror was changed by a human (manual edit/add/delete detected
 * against the baseline) — never because of the natural size/mtime drift of a running app's SQLite
 * files. Manual change wins over a concurrent remote change (no three-way merge in MVP).
 */
public final class SyncDecision {

    private SyncDecision() {}

    public enum Action { EXPORT, WRITEBACK, SKIP }

    public static Action decide(boolean manuallyChanged, boolean remoteChanged) {
        if (manuallyChanged) {
            return Action.WRITEBACK; // manual edit is authoritative
        }
        if (remoteChanged) {
            return Action.EXPORT;    // app produced new data -> refresh mirror (read-only)
        }
        return Action.SKIP;
    }
}
