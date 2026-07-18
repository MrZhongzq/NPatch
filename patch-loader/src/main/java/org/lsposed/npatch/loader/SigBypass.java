package org.lsposed.npatch.loader;

import static org.lsposed.npatch.share.Constants.ORIGINAL_APK_ASSET_PATH;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.PackageParser;
import android.content.pm.Signature;
import android.os.Parcel;
import android.os.Parcelable;
import android.os.Process;
import android.util.Base64;
import android.util.Log;

import com.google.gson.JsonSyntaxException;

import org.json.JSONException;
import org.json.JSONObject;
import org.lsposed.lspd.nativebridge.SvcBypass;
import org.lsposed.npatch.loader.util.XLog;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.FileInputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

public class SigBypass {

    private static final String TAG = "NPatch-SigBypass";
    // PackageManager.hasSigningCertificate() certificate input types.
    private static final int CERT_INPUT_RAW_X509 = 0;
    private static final int CERT_INPUT_SHA256 = 1;
    private static final Map<String, String> signatures = new HashMap<>();
    private static String cachedOriginalApkPath;
    private static String cachedOriginalFactory = null;
    private static boolean packageArchiveInfoHooked;
    private static boolean hasSigningCertificateHooked;

    private static void replaceSignature(Context context, PackageInfo packageInfo) {
        boolean hasSignature = (packageInfo.signatures != null && packageInfo.signatures.length != 0) || packageInfo.signingInfo != null;
        if (hasSignature) {
            String packageName = packageInfo.packageName;
            String replacement = signatures.get(packageName);
            if (replacement == null && !signatures.containsKey(packageName)) {
                try {
                    var metaData = context.getPackageManager().getApplicationInfo(packageName, PackageManager.GET_META_DATA).metaData;
                    String encoded = null;
                    if (metaData != null) encoded = metaData.getString("npatch");
                    if (encoded != null) {
                        var json = new String(Base64.decode(encoded, Base64.DEFAULT), StandardCharsets.UTF_8);
                        try {
                            var patchConfig = new JSONObject(json);
                            replacement = patchConfig.getString("originalSignature");
                            if (patchConfig.has("appComponentFactory")) {
                                cachedOriginalFactory = patchConfig.optString("appComponentFactory", null);
                            }
                        } catch (JSONException e) {
                            Log.w(TAG, "fail to get originalSignature or factory", e);
                        }
                    }
                } catch (PackageManager.NameNotFoundException | JsonSyntaxException ignored) {
                }
                signatures.put(packageName, replacement);
            }
            if (replacement != null) {
                if (packageInfo.signatures != null && packageInfo.signatures.length > 0) {
                    XLog.d(TAG, "Replace signature info for `" + packageName + "` (method 1)");
                    packageInfo.signatures[0] = new Signature(replacement);
                }
                if (packageInfo.signingInfo != null) {
                    XLog.d(TAG, "Replace signature info for `" + packageName + "` (method 2)");
                    Signature[] signaturesArray = packageInfo.signingInfo.getApkContentsSigners();
                    if (signaturesArray != null && signaturesArray.length > 0) {
                        signaturesArray[0] = new Signature(replacement);
                    }
                }
            }
        }
    }

    private static void spoofApplicationInfo(ApplicationInfo appInfo) {
        if (appInfo != null) {
            if (cachedOriginalFactory != null && !cachedOriginalFactory.isEmpty()) {
                appInfo.appComponentFactory = cachedOriginalFactory;
            }
        }
    }

    // Returns the original (pre-patch) signature recorded for a package, loading it from the
    // patched app's "npatch" manifest metadata on first use. Backs hasSigningCertificate spoofing.
    private static Signature getOriginalSignature(Context context, String packageName) {
        if (!signatures.containsKey(packageName)) {
            String replacement = null;
            try {
                var metaData = context.getPackageManager()
                        .getApplicationInfo(packageName, PackageManager.GET_META_DATA).metaData;
                String encoded = metaData != null ? metaData.getString("npatch") : null;
                if (encoded != null) {
                    var json = new String(Base64.decode(encoded, Base64.DEFAULT), StandardCharsets.UTF_8);
                    replacement = new JSONObject(json).getString("originalSignature");
                }
            } catch (Throwable ignored) {
            }
            signatures.put(packageName, replacement);
        }
        String hex = signatures.get(packageName);
        return hex != null ? new Signature(hex) : null;
    }

    private static boolean matchesOriginalCertificate(Signature signature, byte[] certificate, int type) {
        if (signature == null || certificate == null) return false;
        try {
            byte[] sigBytes = signature.toByteArray();
            if (type == CERT_INPUT_SHA256) {
                byte[] sha = MessageDigest.getInstance("SHA-256").digest(sigBytes);
                return Arrays.equals(sha, certificate);
            }
            // CERT_INPUT_RAW_X509
            return Arrays.equals(sigBytes, certificate);
        } catch (Throwable e) {
            return false;
        }
    }

    // getPackageArchiveInfo(patchedApk) — feed the parser origin.apk and spoof the signature
    // so an app that inspects its own installed apk sees the original certificate.
    private static void hookPackageArchiveInfo(Context context) {
        if (packageArchiveInfoHooked) return;
        try {
            final String patchedApkPath = context.getPackageResourcePath();
            XC_MethodHook hook = new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    if (cachedOriginalApkPath == null) return;
                    Object apkPath = param.args.length == 0 ? null : param.args[0];
                    if (apkPath instanceof String && apkPath.equals(patchedApkPath)) {
                        param.args[0] = cachedOriginalApkPath;
                    }
                }

                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    PackageInfo packageInfo = (PackageInfo) param.getResult();
                    if (packageInfo != null) replaceSignature(context, packageInfo);
                }
            };
            hookPackageArchiveInfoMethods(PackageManager.class, hook);
            try {
                hookPackageArchiveInfoMethods(Class.forName("android.app.ApplicationPackageManager"), hook);
            } catch (Throwable ignored) {
            }
            packageArchiveInfoHooked = true;
        } catch (Throwable e) {
            Log.w(TAG, "fail to replace getPackageArchiveInfo", e);
        }
    }

    private static void hookPackageArchiveInfoMethods(Class<?> clazz, XC_MethodHook hook) {
        try {
            XposedBridge.hookAllMethods(clazz, "getPackageArchiveInfo", hook);
        } catch (NoSuchMethodError ignored) {
        }
    }

    // hasSigningCertificate(pkg/uid, cert, type) — report a match when the queried certificate
    // is the original one, so certificate-pinning tamper checks pass.
    private static void hookHasSigningCertificate(Context context) {
        if (hasSigningCertificateHooked) return;
        try {
            XC_MethodHook hook = new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    if (param.args.length < 3) return;
                    Object packageNameArg = param.args[0];
                    Object certificateArg = param.args[1];
                    Object typeArg = param.args[2];
                    if (!(certificateArg instanceof byte[]) || !(typeArg instanceof Integer)) {
                        return;
                    }
                    String packageName = null;
                    if (packageNameArg instanceof String) {
                        packageName = (String) packageNameArg;
                    } else if (packageNameArg instanceof Integer && ((Integer) packageNameArg) == Process.myUid()) {
                        packageName = context.getPackageName();
                    }
                    if (packageName == null) return;
                    Signature originalSignature = getOriginalSignature(context, packageName);
                    if (originalSignature == null) return;
                    if (matchesOriginalCertificate(originalSignature, (byte[]) certificateArg, (Integer) typeArg)) {
                        param.setResult(true);
                    }
                }
            };
            XposedBridge.hookAllMethods(PackageManager.class, "hasSigningCertificate", hook);
            try {
                XposedBridge.hookAllMethods(Class.forName("android.app.ApplicationPackageManager"), "hasSigningCertificate", hook);
            } catch (Throwable ignored) {
            }
            hasSigningCertificateHooked = true;
        } catch (Throwable e) {
            Log.w(TAG, "fail to hook hasSigningCertificate", e);
        }
    }

    // Hook the PackageInfo(Parcel) constructor so signatures are spoofed for EVERY parcel
    // unmarshal, including callers that use a saved/original CREATOR reference or construct
    // PackageInfo directly and thus bypass our CREATOR field replacement (ported from upstream —
    // this is the path an anti-spoofing "Creator" consistency check exploits).
    private static void hookPackageInfoConstructor(Context context) {
        try {
            XposedHelpers.findAndHookConstructor(PackageInfo.class, Parcel.class, new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    var packageInfo = (PackageInfo) param.thisObject;
                    if (packageInfo != null) replaceSignature(context, packageInfo);
                }
            });
        } catch (Throwable t) {
            Log.w(TAG, "fail to hook PackageInfo(Parcel) constructor", t);
        }
    }

    private static void hookPackageParser(Context context, int sigBypassLevel) {
        XposedBridge.hookAllMethods(PackageParser.class, "generatePackageInfo", new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                var packageInfo = (PackageInfo) param.getResult();
                if (packageInfo == null) return;
                // lv3+ relies on signature spoofing (below): return the ORIGINAL signature from
                // the PackageManager while leaving sourceDir pointing at the patched apk. We no
                // longer rewrite the app's own sourceDir here — that path redirection broke
                // WebView/classloader context and is superseded by the spoofing hooks.
                replaceSignature(context, packageInfo);
            }
        });
    }

    private static void proxyPackageInfoCreator(Context context, int sigBypassLevel) {
        Parcelable.Creator<PackageInfo> originalCreator = PackageInfo.CREATOR;
        Parcelable.Creator<PackageInfo> proxiedCreator = new Parcelable.Creator<>() {
            @Override
            public PackageInfo createFromParcel(Parcel source) {
                PackageInfo packageInfo = originalCreator.createFromParcel(source);
                replaceSignature(context, packageInfo);

                // 還原 appComponentFactory
                if (packageInfo.applicationInfo != null) {
                    spoofApplicationInfo(packageInfo.applicationInfo);
                }
                return packageInfo;
            }

            @Override
            public PackageInfo[] newArray(int size) {
                return originalCreator.newArray(size);
            }
        };
        XposedHelpers.setStaticObjectField(PackageInfo.class, "CREATOR", proxiedCreator);
        try {
            Map<?, ?> mCreators = (Map<?, ?>) XposedHelpers.getStaticObjectField(Parcel.class, "mCreators");
            mCreators.clear();
        } catch (NoSuchFieldError ignore) {
        } catch (Throwable e) {
            Log.w(TAG, "fail to clear Parcel.mCreators", e);
        }
        try {
            Map<?, ?> sPairedCreators = (Map<?, ?>) XposedHelpers.getStaticObjectField(Parcel.class, "sPairedCreators");
            sPairedCreators.clear();
        } catch (NoSuchFieldError ignore) {
        } catch (Throwable e) {
            Log.w(TAG, "fail to clear Parcel.sPairedCreators", e);
        }
    }

    public static void replaceApplication(String packageName, String sourceDir, String resourcesDir) throws IOException {
        try {
            Log.i(TAG, "Start Replace application info for `" + packageName + "`");
            XposedBridge.hookAllMethods(Class.forName("android.app.ApplicationPackageManager"), "getApplicationInfo", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                    if (packageName.equals(param.args[0])) {
                        ApplicationInfo info = (ApplicationInfo) param.getResult();
                        info.sourceDir = sourceDir;
                        info.publicSourceDir = sourceDir;
                    }
                }
            });
            XposedBridge.hookAllMethods(Class.forName("android.app.ApplicationPackageManager"), "getApplicationInfoAsUser", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                    if (packageName.equals(param.args[0])) {
                        ApplicationInfo info = (ApplicationInfo) param.getResult();
                        info.sourceDir = sourceDir;
                        info.publicSourceDir = sourceDir;
                    }
                }
            });
        } catch (Throwable e) {
            Log.w(TAG, "fail to replace getApplicationInfo", e);
        }
    }

    private static String extractOriginalApk(Context context) {
        File cacheDir = new File(context.getCacheDir(), "npatch/origin");
        if (!cacheDir.exists()) cacheDir.mkdirs();

        try (ZipFile sourceFile = new ZipFile(context.getPackageResourcePath())) {
            ZipEntry entry = sourceFile.getEntry(ORIGINAL_APK_ASSET_PATH);
            if (entry == null) {
                Log.e(TAG, "Original APK not found in assets!");
                return null;
            }

            File targetFile = new File(cacheDir, entry.getCrc() + ".apk");
            if (targetFile.exists() && targetFile.length() == entry.getSize()) {
                return targetFile.getAbsolutePath();
            }

            try (InputStream is = sourceFile.getInputStream(entry);
                 FileOutputStream fos = new FileOutputStream(targetFile)) {
                byte[] buffer = new byte[8192];
                int length;
                while ((length = is.read(buffer)) > 0) {
                    fos.write(buffer, 0, length);
                }
            }
            return targetFile.getAbsolutePath();
        } catch (IOException e) {
            Log.e(TAG, "Failed to extract original APK", e);
            return null;
        }
    }

    private static void hookJavaIO(String currentApkPath, String originalApkPath) {
        XC_MethodHook redirectHook = new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                if (param.args.length > 0) {
                    if (param.args[0] instanceof String) {
                        String path = (String) param.args[0];
                        if (path.equals(currentApkPath)) {
                            param.args[0] = originalApkPath;
                        }
                    } else if (param.args[0] instanceof File) {
                        File file = (File) param.args[0];
                        if (file.getPath().equals(currentApkPath)) {
                            param.args[0] = new File(originalApkPath);
                        }
                    }
                }
            }
        };
        XposedBridge.hookAllConstructors(ZipFile.class, redirectHook);
        try {
            XposedBridge.hookAllConstructors(FileInputStream.class, redirectHook);
        } catch (Throwable ignored) {}
    }

    static void doSigBypass(Context context, int sigBypassLevel, String originalApkPath) throws IOException {
        String currentApkPath = context.getPackageResourcePath();
        if (sigBypassLevel >= 2) {
            // NPatch prepares the embedded origin.apk through OriginApkHelper before this
            // point and hands us the extracted path; fall back to extracting it from the
            // assets/npatch/origin.apk entry ourselves if the caller didn't provide one.
            cachedOriginalApkPath = (originalApkPath != null && !originalApkPath.isEmpty())
                    ? originalApkPath
                    : extractOriginalApk(context);
        }

        // Java PMS Hook
        if (sigBypassLevel >= 1) {
            hookPackageParser(context, sigBypassLevel);
            proxyPackageInfoCreator(context, sigBypassLevel);
            hookPackageInfoConstructor(context);
        }

        if (sigBypassLevel >= 2 && cachedOriginalApkPath != null) {
            // 1. Java Core IO stability
            hookJavaIO(currentApkPath, cachedOriginalApkPath);
            // 2. Native OpenAt Hook (lv2/lv3 only). xHook rewrites each library's GOT (in the
            //    .data.rel.ro segment), which native integrity detectors flag as a hooked data
            //    segment ("lib has been hooked"). At lv4 the seccomp filter redirects openat at
            //    the syscall level without touching any GOT/code, so we skip xHook there.
            if (sigBypassLevel < 4) {
                org.lsposed.lspd.nativebridge.SigBypass.enableOpenatHook(
                        currentApkPath,
                        cachedOriginalApkPath,
                        context.getPackageName()
                );
            }

            // Signature spoofing (replaces the old lv3 path redirection). Instead of pointing
            // the app's own sourceDir/context at origin.apk (which broke WebView/classloader
            // context), we return the ORIGINAL signature from every PackageManager signature
            // surface: getPackageInfo/CREATOR (lv1, above), getPackageArchiveInfo, and
            // hasSigningCertificate. File-level reads are still covered by the lv2 openat/IO
            // redirect above.
            if (sigBypassLevel >= 3) {
                try {
                    hookPackageArchiveInfo(context);
                    hookHasSigningCertificate(context);
                    XLog.i(TAG, "Signature spoofing (LV3) enabled");
                } catch (Throwable t) {
                    Log.w(TAG, "Failed to apply signature spoofing", t);
                }
            }

            // SVC (Seccomp) Hook
            if (sigBypassLevel >= 4) {
                if (SvcBypass.initSvcHook()) {
                    SvcBypass.enableSvcRedirect(
                            currentApkPath,
                            cachedOriginalApkPath,
                            context.getPackageName()
                    );
                    XLog.i(TAG, "SVC Hook enabled");
                } else {
                    XLog.w(TAG, "SVC Hook failed to init");
                }
            }
        }
    }
}
