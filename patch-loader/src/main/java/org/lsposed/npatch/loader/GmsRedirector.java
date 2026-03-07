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
 * Redirects Google Play Services IPC from the real GMS to a MicroG-based GMS.
 * Supports multiple MicroG package names (NPatch GMS, ReVanced GmsCore, etc.)
 * This runs inside the patched app's process via Xposed hooks.
 */
public class GmsRedirector {
    private static final String TAG = "NPatch-GmsRedirect";
    private static final String REAL_GMS = Constants.REAL_GMS_PACKAGE_NAME;

    // Supported MicroG package names in priority order
    private static final String[] MICROG_PACKAGES = {
            Constants.NPATCH_GMS_PACKAGE_NAME,          // org.lsposed.npatch.gms
            "app.revanced.android.gms",                  // ReVanced GmsCore
            "org.microg.gms",                             // Original MicroG (user-variant)
    };

    private static String targetGms = null;
    private static String originalSignature;

    public static void activate(Context context, String origSig) {
        originalSignature = origSig;

        // Find which MicroG is installed
        targetGms = findInstalledMicroG(context);
        if (targetGms == null) {
            Log.w(TAG, "No MicroG/GmsCore found! Tried: " + String.join(", ", MICROG_PACKAGES));
            Log.w(TAG, "GMS redirect disabled - install NPatch GMS or ReVanced GmsCore");
            return;
        }

        Log.i(TAG, "Activating GMS redirect: " + REAL_GMS + " -> " + targetGms);

        hookIntentSetPackage();
        hookIntentSetComponent();
        hookIntentResolve();
        hookContentResolverAcquire();
        hookPackageManagerGetPackageInfo(context);

        Log.i(TAG, "GMS redirect hooks installed");
    }

    private static String findInstalledMicroG(Context context) {
        PackageManager pm = context.getPackageManager();
        for (String pkg : MICROG_PACKAGES) {
            try {
                pm.getPackageInfo(pkg, 0);
                Log.i(TAG, "Found MicroG: " + pkg);
                return pkg;
            } catch (PackageManager.NameNotFoundException ignored) {}
        }
        return null;
    }

    private static String redirectPackage(String pkg) {
        if (REAL_GMS.equals(pkg)) return targetGms;
        // Also redirect com.google.android.gsf (Google Services Framework)
        if ("com.google.android.gsf".equals(pkg)) return targetGms;
        return null;
    }

    private static String redirectAuthority(String authority) {
        if (authority == null) return null;
        if (authority.startsWith(REAL_GMS + ".")) {
            return targetGms + authority.substring(REAL_GMS.length());
        }
        if (authority.equals(REAL_GMS)) {
            return targetGms;
        }
        // Handle GSF authorities
        if (authority.startsWith("com.google.android.gsf")) {
            return authority.replace("com.google.android.gsf", targetGms);
        }
        return null;
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
                    String redirected = redirectPackage(pkg);
                    if (redirected != null) {
                        param.args[0] = redirected;
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
                    if (cn != null) {
                        String redirected = redirectPackage(cn.getPackageName());
                        if (redirected != null) {
                            param.args[0] = new ComponentName(redirected, cn.getClassName());
                        }
                    }
                }
            });
        } catch (Throwable t) {
            Log.e(TAG, "Failed to hook Intent.setComponent", t);
        }
    }

    /**
     * Hook Intent resolution to redirect implicit intents targeting GMS
     */
    private static void hookIntentResolve() {
        try {
            // Hook Intent constructor with action+uri to catch implicit GMS intents
            XposedBridge.hookAllConstructors(Intent.class, new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    Intent intent = (Intent) param.thisObject;
                    ComponentName cn = intent.getComponent();
                    if (cn != null) {
                        String redirected = redirectPackage(cn.getPackageName());
                        if (redirected != null) {
                            intent.setComponent(new ComponentName(redirected, cn.getClassName()));
                        }
                    }
                    String pkg = intent.getPackage();
                    if (pkg != null) {
                        String redirected = redirectPackage(pkg);
                        if (redirected != null) {
                            intent.setPackage(redirected);
                        }
                    }
                }
            });
        } catch (Throwable t) {
            Log.e(TAG, "Failed to hook Intent constructors", t);
        }
    }

    /**
     * Hook ContentResolver to redirect GMS content provider URIs
     */
    private static void hookContentResolverAcquire() {
        try {
            // Hook multiple ContentResolver methods
            for (String method : new String[]{
                    "acquireProvider", "acquireContentProviderClient",
                    "acquireUnstableProvider", "acquireUnstableContentProviderClient"
            }) {
                try {
                    XposedBridge.hookAllMethods(ContentResolver.class, method, new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            if (param.args[0] instanceof Uri) {
                                Uri uri = (Uri) param.args[0];
                                String authority = uri.getAuthority();
                                String newAuth = redirectAuthority(authority);
                                if (newAuth != null) {
                                    param.args[0] = uri.buildUpon().authority(newAuth).build();
                                }
                            } else if (param.args[0] instanceof String) {
                                String authority = (String) param.args[0];
                                String newAuth = redirectAuthority(authority);
                                if (newAuth != null) {
                                    param.args[0] = newAuth;
                                }
                            }
                        }
                    });
                } catch (Throwable ignored) {}
            }

            // Also hook ContentResolver.call which YouTube Music uses heavily
            try {
                XposedBridge.hookAllMethods(ContentResolver.class, "call", new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        for (int i = 0; i < param.args.length; i++) {
                            if (param.args[i] instanceof Uri) {
                                Uri uri = (Uri) param.args[i];
                                String authority = uri.getAuthority();
                                String newAuth = redirectAuthority(authority);
                                if (newAuth != null) {
                                    param.args[i] = uri.buildUpon().authority(newAuth).build();
                                }
                            } else if (param.args[i] instanceof String && i == 0) {
                                // First string arg might be authority
                                String authority = (String) param.args[i];
                                String newAuth = redirectAuthority(authority);
                                if (newAuth != null) {
                                    param.args[i] = newAuth;
                                }
                            }
                        }
                    }
                });
            } catch (Throwable ignored) {}
        } catch (Throwable t) {
            Log.e(TAG, "Failed to hook ContentResolver", t);
        }
    }

    /**
     * Hook PackageManager to spoof the MicroG signature as the original Google signature.
     * This makes the patched app believe it's talking to real GMS.
     */
    private static void hookPackageManagerGetPackageInfo(Context context) {
        try {
            // Hook getPackageInfo(String, int)
            XposedHelpers.findAndHookMethod(
                    context.getPackageManager().getClass(),
                    "getPackageInfo",
                    String.class, int.class,
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            spoofGmsSignature((PackageInfo) param.getResult(), (int) param.args[1]);
                        }
                    }
            );
        } catch (Throwable t) {
            Log.e(TAG, "Failed to hook PackageManager.getPackageInfo", t);
        }
    }

    private static void spoofGmsSignature(PackageInfo pi, int flags) {
        if (pi == null || targetGms == null) return;
        // Make MicroG look like real GMS
        if (targetGms.equals(pi.packageName) && (flags & PackageManager.GET_SIGNATURES) != 0) {
            if (originalSignature != null && !originalSignature.isEmpty()) {
                try {
                    byte[] sigBytes = android.util.Base64.decode(originalSignature,
                            android.util.Base64.DEFAULT);
                    pi.signatures = new Signature[]{new Signature(sigBytes)};
                } catch (Throwable ignored) {}
            }
        }
        // Also spoof the calling app's own package to appear as original
        // (for when MicroG asks "who's calling me?" via getCallingPackage)
    }
}
