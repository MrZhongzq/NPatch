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

    public static final Result ALLOW_DISABLED = new Result(false, "ALLOW_DISABLED");
    public static final Result ALLOW_UI_PRESENT = new Result(false, "ALLOW_UI_PRESENT");
    public static final Result ALLOW_SELF_SENT = new Result(false, "ALLOW_SELF_SENT");
    public static final Result ALLOW_DEFAULT = new Result(false, "ALLOW_DEFAULT");
    public static final Result ALLOW_NULL_RECEIVER = new Result(false, "ALLOW_NULL");
    public static final Result BLOCK_RECEIVER_DISABLED = new Result(true, "BLOCK_RECEIVER_DISABLED");

    private SelfStartDecision() {}

    /**
     * Per-receiver decision (v2 runtime model). Blacklist-by-receiver: block only when the app's
     * master switch is on AND this receiver class is in the user's disabled set, unless bypassed.
     */
    public static Result decideReceiver(boolean masterEnabled, String receiverClass,
                                        Set<String> disabledReceivers,
                                        boolean hasResumedActivity, boolean selfSent) {
        if (!masterEnabled) return ALLOW_DISABLED;
        if (receiverClass == null || receiverClass.isEmpty()) return ALLOW_NULL_RECEIVER;
        if (hasResumedActivity) return ALLOW_UI_PRESENT;
        if (selfSent) return ALLOW_SELF_SENT;
        if (disabledReceivers != null && disabledReceivers.contains(receiverClass)) return BLOCK_RECEIVER_DISABLED;
        return ALLOW_DEFAULT;
    }

    /** Job (WorkManager/JobScheduler) suppression: skip when enabled AND app is backgrounded. */
    public static boolean shouldSkipJob(boolean suppressJobs, boolean hasResumedActivity) {
        return suppressJobs && !hasResumedActivity;
    }

    /** Service suppression: skip when the service class is user-disabled AND app is backgrounded. */
    public static boolean shouldSkipService(String serviceClass, java.util.Set<String> disabledServices,
                                            boolean hasResumedActivity) {
        if (hasResumedActivity) return false;
        if (serviceClass == null || disabledServices == null) return false;
        return disabledServices.contains(serviceClass);
    }
}
