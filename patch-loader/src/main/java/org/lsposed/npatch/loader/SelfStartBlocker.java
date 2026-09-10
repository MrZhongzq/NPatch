package org.lsposed.npatch.loader;

import android.app.Activity;
import android.app.Application;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.util.Log;

import org.lsposed.npatch.share.PatchConfig;
import org.lsposed.npatch.share.SelfStartDecision;

import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;

/**
 * In-process self-start manager. Hooks static broadcast dispatch and neutralizes blacklisted
 * self-start broadcasts so the app, once woken by them, does nothing and gets reclaimed.
 * Rootless: cannot stop the OS from spawning the process, only stop the receiver from doing work.
 * Only touches BROADCAST dispatch — activity/service/provider (associated-start) are untouched.
 */
public final class SelfStartBlocker {

    private static final String TAG = "NPatch-SelfStart";

    private static Set<String> blacklist;
    private static String ownPackage;
    private static final AtomicInteger resumedCount = new AtomicInteger(0);

    private SelfStartBlocker() {}

    public static void activate(Context context, PatchConfig config) {
        try {
            blacklist = new LinkedHashSet<>(Arrays.asList(
                    config.selfStartBlacklist == null ? new String[0] : config.selfStartBlacklist));
            ownPackage = context.getPackageName();

            registerForegroundTracker(context);
            hookHandleReceiver();

            Log.i(TAG, "Self-start management active, blacklist size=" + blacklist.size());
        } catch (Throwable t) {
            Log.e(TAG, "Failed to activate self-start management", t);
        }
    }

    private static void registerForegroundTracker(Context context) {
        try {
            Context app = context.getApplicationContext();
            if (app instanceof Application) {
                ((Application) app).registerActivityLifecycleCallbacks(new Application.ActivityLifecycleCallbacks() {
                    @Override public void onActivityResumed(Activity a) { resumedCount.incrementAndGet(); }
                    @Override public void onActivityPaused(Activity a) {
                        if (resumedCount.get() > 0) resumedCount.decrementAndGet();
                    }
                    @Override public void onActivityCreated(Activity a, Bundle b) {}
                    @Override public void onActivityStarted(Activity a) {}
                    @Override public void onActivityStopped(Activity a) {}
                    @Override public void onActivitySaveInstanceState(Activity a, Bundle b) {}
                    @Override public void onActivityDestroyed(Activity a) {}
                });
            }
        } catch (Throwable t) {
            Log.w(TAG, "Foreground tracker not installed (fail-open)", t);
        }
    }

    private static void hookHandleReceiver() throws ClassNotFoundException {
        Class<?> activityThread = Class.forName("android.app.ActivityThread");
        XposedBridge.hookAllMethods(activityThread, "handleReceiver", new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                try {
                    Object receiverData = param.args[0];
                    Intent intent = (Intent) XposedBridgeHelper.getIntent(receiverData);
                    if (intent == null) return;

                    String action = intent.getAction();
                    boolean selfSent = isSelfSent(intent);
                    SelfStartDecision.Result r = SelfStartDecision.decide(
                            true, action, blacklist, resumedCount.get() > 0, selfSent);

                    if (r.block) {
                        Log.i(TAG, "[SelfStart] BLOCK " + action);
                        // Preserve the AMS finish handshake to avoid ANR: ReceiverData extends
                        // BroadcastReceiver.PendingResult; finish() ourselves, then skip onReceive.
                        if (receiverData instanceof BroadcastReceiver.PendingResult) {
                            ((BroadcastReceiver.PendingResult) receiverData).finish();
                        }
                        param.setResult(null);
                    }
                } catch (Throwable t) {
                    // Fail-open: never break broadcast dispatch on our own error.
                    Log.w(TAG, "handleReceiver hook error (fail-open)", t);
                }
            }
        });
    }

    private static boolean isSelfSent(Intent intent) {
        ComponentName cn = intent.getComponent();
        if (cn != null && ownPackage != null && ownPackage.equals(cn.getPackageName())) return true;
        String pkg = intent.getPackage();
        return pkg != null && pkg.equals(ownPackage);
    }

    /** Reflection helper for the hidden ActivityThread.ReceiverData.intent field. */
    private static final class XposedBridgeHelper {
        static Object getIntent(Object receiverData) {
            try {
                return de.robv.android.xposed.XposedHelpers.getObjectField(receiverData, "intent");
            } catch (Throwable t) {
                return null;
            }
        }
    }
}
