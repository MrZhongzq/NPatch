package org.lsposed.npatch.loader;

import android.content.ComponentName;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.net.Uri;
import android.util.Log;

import org.lsposed.npatch.share.Constants;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/**
 * Redirects Google Play Services IPC from the real GMS to NPatch's built-in MicroG.
 * This runs inside the patched app's process via Xposed hooks.
 *
 * Hooks:
 * 1. Intent construction - redirect package/component to NPatch GMS
 * 2. ContentResolver - redirect authority URIs
 * 3. PackageManager.getPackageInfo - spoof GMS signature
 */
public class GmsRedirector {
    private static final String TAG = "NPatch-GmsRedirect";
    private static final String REAL_GMS = Constants.REAL_GMS_PACKAGE_NAME;
    private static final String NPATCH_GMS = Constants.NPATCH_GMS_PACKAGE_NAME;

    private static String originalSignature;

    public static void activate(Context context, String origSig) {
        originalSignature = origSig;
        Log.i(TAG, "Activating GMS redirect: " + REAL_GMS + " -> " + NPATCH_GMS);

        hookIntentSetPackage();
        hookIntentSetComponent();
        hookContentResolverAcquire();
        hookPackageManagerGetPackageInfo(context);

        Log.i(TAG, "GMS redirect hooks installed");
    }

    /**
     * Hook Intent.setPackage() to redirect GMS package references
     */
    private static void hookIntentSetPackage() {
        try {
            XposedBridge.hookAllMethods(Intent.class, "setPackage", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    String pkg = (String) param.args[0];
                    if (REAL_GMS.equals(pkg)) {
                        param.args[0] = NPATCH_GMS;
                        Log.d(TAG, "Redirected Intent.setPackage: " + REAL_GMS + " -> " + NPATCH_GMS);
                    }
                }
            });
        } catch (Throwable t) {
            Log.e(TAG, "Failed to hook Intent.setPackage", t);
        }
    }

    /**
     * Hook Intent.setComponent() to redirect GMS components
     */
    private static void hookIntentSetComponent() {
        try {
            XposedBridge.hookAllMethods(Intent.class, "setComponent", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    ComponentName cn = (ComponentName) param.args[0];
                    if (cn != null && REAL_GMS.equals(cn.getPackageName())) {
                        param.args[0] = new ComponentName(NPATCH_GMS, cn.getClassName());
                        Log.d(TAG, "Redirected Intent.setComponent to " + NPATCH_GMS);
                    }
                }
            });

            // Also hook the Intent constructor that takes ComponentName
            XposedBridge.hookAllConstructors(Intent.class, new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    Intent intent = (Intent) param.thisObject;
                    ComponentName cn = intent.getComponent();
                    if (cn != null && REAL_GMS.equals(cn.getPackageName())) {
                        intent.setComponent(new ComponentName(NPATCH_GMS, cn.getClassName()));
                    }
                }
            });
        } catch (Throwable t) {
            Log.e(TAG, "Failed to hook Intent.setComponent", t);
        }
    }

    /**
     * Hook ContentResolver to redirect GMS content provider URIs
     */
    private static void hookContentResolverAcquire() {
        try {
            XposedBridge.hookAllMethods(ContentResolver.class, "acquireProvider", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    if (param.args[0] instanceof Uri) {
                        Uri uri = (Uri) param.args[0];
                        String authority = uri.getAuthority();
                        if (authority != null && authority.contains(REAL_GMS)) {
                            String newAuth = authority.replace(REAL_GMS, NPATCH_GMS);
                            Uri newUri = uri.buildUpon().authority(newAuth).build();
                            param.args[0] = newUri;
                            Log.d(TAG, "Redirected ContentResolver URI: " + authority + " -> " + newAuth);
                        }
                    } else if (param.args[0] instanceof String) {
                        String authority = (String) param.args[0];
                        if (authority.contains(REAL_GMS)) {
                            param.args[0] = authority.replace(REAL_GMS, NPATCH_GMS);
                        }
                    }
                }
            });

            // Hook acquireContentProviderClient too
            XposedBridge.hookAllMethods(ContentResolver.class, "acquireContentProviderClient", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    if (param.args[0] instanceof Uri) {
                        Uri uri = (Uri) param.args[0];
                        String authority = uri.getAuthority();
                        if (authority != null && authority.contains(REAL_GMS)) {
                            String newAuth = authority.replace(REAL_GMS, NPATCH_GMS);
                            param.args[0] = uri.buildUpon().authority(newAuth).build();
                        }
                    } else if (param.args[0] instanceof String) {
                        String name = (String) param.args[0];
                        if (name.contains(REAL_GMS)) {
                            param.args[0] = name.replace(REAL_GMS, NPATCH_GMS);
                        }
                    }
                }
            });
        } catch (Throwable t) {
            Log.e(TAG, "Failed to hook ContentResolver", t);
        }
    }

    /**
     * Hook PackageManager to spoof the NPatch GMS signature as the original Google signature.
     * This makes the patched app believe it's talking to real GMS.
     */
    private static void hookPackageManagerGetPackageInfo(Context context) {
        try {
            XposedHelpers.findAndHookMethod(
                    context.getPackageManager().getClass(),
                    "getPackageInfo",
                    String.class, int.class,
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            String pkgName = (String) param.args[0];
                            int flags = (int) param.args[1];
                            PackageInfo pi = (PackageInfo) param.getResult();

                            if (pi != null && NPATCH_GMS.equals(pkgName) &&
                                    (flags & PackageManager.GET_SIGNATURES) != 0) {
                                // Make NPatch GMS look like real GMS to the patched app
                                if (originalSignature != null && !originalSignature.isEmpty()) {
                                    try {
                                        byte[] sigBytes = android.util.Base64.decode(originalSignature,
                                                android.util.Base64.DEFAULT);
                                        pi.signatures = new Signature[]{new Signature(sigBytes)};
                                    } catch (Throwable ignored) {}
                                }
                            }
                        }
                    }
            );
        } catch (Throwable t) {
            Log.e(TAG, "Failed to hook PackageManager.getPackageInfo", t);
        }
    }
}
