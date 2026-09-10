package org.lsposed.npatch.share;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Single source of truth for self-start management default rules, shared by the manager
 * (UI defaults) and the loader (runtime decision). No Android dependency.
 */
public final class SelfStartDefaults {

    private SelfStartDefaults() {}

    /** Broadcast actions blocked by default as self-start vectors (user-editable). */
    public static final String[] DEFAULT_BLACKLIST = {
            "android.intent.action.BOOT_COMPLETED",
            "android.intent.action.QUICKBOOT_POWERON",
            "com.htc.intent.action.QUICKBOOT_POWERON",
            "android.intent.action.MY_PACKAGE_REPLACED",
            "android.intent.action.PACKAGE_ADDED",
            "android.intent.action.PACKAGE_REPLACED",
            "android.intent.action.PACKAGE_REMOVED",
            "android.intent.action.PACKAGE_CHANGED",
            "android.intent.action.MEDIA_MOUNTED",
            "android.intent.action.MEDIA_UNMOUNTED",
            "android.intent.action.MEDIA_EJECT",
            "android.intent.action.LOCALE_CHANGED",
            "android.hardware.usb.action.USB_STATE",
            "android.hardware.usb.action.USB_DEVICE_ATTACHED",
    };

    /** Push actions that are ALWAYS allowed and can never be blocked. */
    private static final Set<String> PUSH_WHITELIST = Collections.unmodifiableSet(new HashSet<>(Arrays.asList(
            "com.xiaomi.mipush.RECEIVE_MESSAGE",
            "com.xiaomi.mipush.MESSAGE_ARRIVED",
            "com.xiaomi.mipush.ERROR",
            "com.google.android.c2dm.intent.RECEIVE",
            "com.google.firebase.MESSAGING_EVENT"
    )));

    public static boolean isPushAction(String action) {
        return action != null && PUSH_WHITELIST.contains(action);
    }

    /** Fresh mutable, insertion-ordered copy of the default blacklist. */
    public static Set<String> defaultBlacklistSet() {
        return new LinkedHashSet<>(Arrays.asList(DEFAULT_BLACKLIST));
    }

    /** ContentProvider delivery constants (v2 runtime config), shared by manager & loader. */
    public static final String PROVIDER_AUTHORITY = "org.lsposed.npatch.manager.provider.config";
    public static final String SELFSTART_QUERY_TYPE = "selfstart";
    public static final String COL_MASTER = "master";
    public static final String COL_DISABLED = "disabled";
    public static final String DISABLED_SEP = "\n";
}
