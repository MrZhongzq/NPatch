package org.lsposed.npatch.loader;

import android.app.Activity;
import android.app.Application;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.util.Log;

import org.lsposed.npatch.share.SelfStartDecision;
import org.lsposed.npatch.share.SelfStartDefaults;

import java.io.File;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/**
 * In-process self-start manager (v2, runtime per-receiver). On process start it pulls this app's
 * config from the manager's ContentProvider; if the master switch is on it hooks static broadcast
 * dispatch and neutralizes any receiver the user disabled. Rootless: cannot stop the OS from
 * spawning the process, only stop the disabled receiver from doing work. Only BROADCAST dispatch is
 * touched (associated-start via activity/service/provider is untouched). Fail-open throughout.
 */
public final class SelfStartBlocker {

    private static final String TAG = "NPatch-SelfStart";
    private static final String CACHE_FILE = "npatch_selfstart_cache.txt";

    private static volatile boolean masterEnabled = false;
    private static volatile Set<String> disabledReceivers = new LinkedHashSet<>();
    private static String ownPackage;
    private static final AtomicInteger resumedCount = new AtomicInteger(0);

    private SelfStartBlocker() {}

    public static void activate(Context context) {
        try {
            ownPackage = context.getPackageName();
            AppCfg cfg = fetchConfig(context, ownPackage);   // provider → cache fallback
            masterEnabled = cfg.master;
            disabledReceivers = cfg.disabled;
            if (!masterEnabled) {
                Log.i(TAG, "Self-start master off, hook not installed");
                return;
            }
            registerForegroundTracker(context);
            hookHandleReceiver();
            Log.i(TAG, "Self-start active, disabled receivers=" + disabledReceivers.size());
        } catch (Throwable t) {
            Log.e(TAG, "activate failed (fail-open)", t);
        }
    }

    private static final class AppCfg {
        final boolean master; final Set<String> disabled;
        AppCfg(boolean m, Set<String> d) { master = m; disabled = d; }
    }

    private static final long PROVIDER_TIMEOUT_MS = 1000L;

    /**
     * Query the manager ContentProvider off-thread with a bounded wait, so a cold/slow manager
     * process never adds unbounded latency to this app's startup. On timeout OR any failure, fall
     * back to the on-disk cache (fail-open). The worker is a daemon thread and is left running past
     * the timeout — if it later completes, the cache it writes still benefits the next launch.
     */
    private static AppCfg fetchConfig(Context context, String pkg) {
        final AppCfg[] result = new AppCfg[1];
        Thread worker = new Thread(() -> {
            try { result[0] = queryProvider(context, pkg); } catch (Throwable ignored) {}
        });
        worker.setDaemon(true);
        worker.start();
        try { worker.join(PROVIDER_TIMEOUT_MS); } catch (InterruptedException ignored) {}
        if (result[0] != null) return result[0];   // got a fresh answer in time
        return readCache(context);                  // timeout or failure -> last-known-good cache
    }

    /** The actual provider round-trip; writes the cache on success. Returns null on any failure. */
    private static AppCfg queryProvider(Context context, String pkg) {
        Uri uri = Uri.parse("content://" + SelfStartDefaults.PROVIDER_AUTHORITY
                + "?type=" + SelfStartDefaults.SELFSTART_QUERY_TYPE + "&package=" + pkg);
        try (Cursor c = context.getContentResolver().query(uri, null, null, null, null)) {
            if (c != null && c.moveToFirst()) {
                int master = c.getInt(c.getColumnIndexOrThrow(SelfStartDefaults.COL_MASTER));
                String disabled = c.getString(c.getColumnIndexOrThrow(SelfStartDefaults.COL_DISABLED));
                Set<String> set = splitDisabled(disabled);
                writeCache(context, master == 1, set);
                return new AppCfg(master == 1, set);
            }
        } catch (Throwable t) {
            Log.w(TAG, "provider query failed", t);
        }
        return null;
    }

    private static Set<String> splitDisabled(String s) {
        Set<String> set = new LinkedHashSet<>();
        if (s != null && !s.isEmpty()) {
            for (String x : s.split(java.util.regex.Pattern.quote(SelfStartDefaults.DISABLED_SEP))) {
                String t = x.trim(); if (!t.isEmpty()) set.add(t);
            }
        }
        return set;
    }

    private static File cacheFile(Context context) {
        return new File(context.getCacheDir(), CACHE_FILE);
    }

    private static void writeCache(Context context, boolean master, Set<String> disabled) {
        try {
            StringBuilder sb = new StringBuilder(master ? "1" : "0");
            for (String r : disabled) sb.append('\n').append(r);
            java.nio.file.Files.write(cacheFile(context).toPath(),
                    sb.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
        } catch (Throwable ignored) {}
    }

    private static AppCfg readCache(Context context) {
        try {
            File f = cacheFile(context);
            if (!f.exists()) return new AppCfg(false, new LinkedHashSet<>());
            byte[] b = java.nio.file.Files.readAllBytes(f.toPath());
            String[] lines = new String(b, java.nio.charset.StandardCharsets.UTF_8)
                    .split(java.util.regex.Pattern.quote(SelfStartDefaults.DISABLED_SEP));
            boolean master = lines.length > 0 && "1".equals(lines[0].trim());
            Set<String> set = new LinkedHashSet<>();
            for (int i = 1; i < lines.length; i++) { String t = lines[i].trim(); if (!t.isEmpty()) set.add(t); }
            return new AppCfg(master, set);
        } catch (Throwable t) {
            return new AppCfg(false, new LinkedHashSet<>());
        }
    }

    private static void registerForegroundTracker(Context context) {
        try {
            Context app = context.getApplicationContext();
            if (app instanceof Application) {
                ((Application) app).registerActivityLifecycleCallbacks(new Application.ActivityLifecycleCallbacks() {
                    @Override public void onActivityResumed(Activity a) { resumedCount.incrementAndGet(); }
                    @Override public void onActivityPaused(Activity a) { if (resumedCount.get() > 0) resumedCount.decrementAndGet(); }
                    @Override public void onActivityCreated(Activity a, Bundle b) {}
                    @Override public void onActivityStarted(Activity a) {}
                    @Override public void onActivityStopped(Activity a) {}
                    @Override public void onActivitySaveInstanceState(Activity a, Bundle b) {}
                    @Override public void onActivityDestroyed(Activity a) {}
                });
            }
        } catch (Throwable t) {
            Log.w(TAG, "foreground tracker not installed (fail-open)", t);
        }
    }

    private static void hookHandleReceiver() throws ClassNotFoundException {
        Class<?> activityThread = Class.forName("android.app.ActivityThread");
        XposedBridge.hookAllMethods(activityThread, "handleReceiver", new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                try {
                    Object receiverData = param.args[0];
                    String receiverClass = receiverClassOf(receiverData);
                    Intent intent = intentOf(receiverData);
                    boolean selfSent = isSelfSent(intent);
                    SelfStartDecision.Result r = SelfStartDecision.decideReceiver(
                            masterEnabled, receiverClass, disabledReceivers, resumedCount.get() > 0, selfSent);
                    if (r.block) {
                        Log.i(TAG, "[SelfStart] BLOCK receiver=" + receiverClass
                                + " action=" + (intent == null ? null : intent.getAction()));
                        if (receiverData instanceof BroadcastReceiver.PendingResult) {
                            ((BroadcastReceiver.PendingResult) receiverData).finish();
                        }
                        param.setResult(null);
                    }
                } catch (Throwable t) {
                    Log.w(TAG, "handleReceiver hook error (fail-open)", t);
                }
            }
        });
    }

    /** ReceiverData.info is the receiver's ActivityInfo; its name is the receiver class. */
    private static String receiverClassOf(Object receiverData) {
        try {
            Object info = XposedHelpers.getObjectField(receiverData, "info");
            if (info instanceof ActivityInfo) return ((ActivityInfo) info).name;
        } catch (Throwable ignored) {}
        return null;
    }

    private static Intent intentOf(Object receiverData) {
        try {
            Object i = XposedHelpers.getObjectField(receiverData, "intent");
            if (i instanceof Intent) return (Intent) i;
        } catch (Throwable ignored) {}
        return null;
    }

    private static boolean isSelfSent(Intent intent) {
        if (intent == null) return false;
        ComponentName cn = intent.getComponent();
        if (cn != null && ownPackage != null && ownPackage.equals(cn.getPackageName())) return true;
        String pkg = intent.getPackage();
        return pkg != null && pkg.equals(ownPackage);
    }
}
