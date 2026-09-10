package org.lsposed.npatch.share;

import java.util.Set;

/**
 * Pure self-start decision ladder (Thanox-style bypass ladder), Android-independent so it is
 * unit-testable on the JVM. Blacklist mode: allow by default, block only on blacklist hit.
 */
public final class SelfStartDecision {

    public static final class Result {
        public final boolean block;
        public final String reason;

        Result(boolean block, String reason) {
            this.block = block;
            this.reason = reason;
        }
    }

    public static final Result ALLOW_NULL_ACTION = new Result(false, "ALLOW_NULL_ACTION");
    public static final Result ALLOW_DISABLED = new Result(false, "ALLOW_DISABLED");
    public static final Result ALLOW_PUSH = new Result(false, "ALLOW_PUSH");
    public static final Result ALLOW_UI_PRESENT = new Result(false, "ALLOW_UI_PRESENT");
    public static final Result ALLOW_SELF_SENT = new Result(false, "ALLOW_SELF_SENT");
    public static final Result ALLOW_DEFAULT = new Result(false, "ALLOW_DEFAULT");
    public static final Result BLOCK_BLACKLIST = new Result(true, "BLOCK_BLACKLIST");

    private SelfStartDecision() {}

    public static Result decide(boolean enabled, String action, Set<String> blacklist,
                                boolean hasResumedActivity, boolean selfSent) {
        if (action == null || action.isEmpty()) return ALLOW_NULL_ACTION;
        if (!enabled) return ALLOW_DISABLED;
        if (SelfStartDefaults.isPushAction(action)) return ALLOW_PUSH;
        if (hasResumedActivity) return ALLOW_UI_PRESENT;
        if (selfSent) return ALLOW_SELF_SENT;
        if (blacklist != null && blacklist.contains(action)) return BLOCK_BLACKLIST;
        return ALLOW_DEFAULT;
    }
}
