package org.lsposed.npatch.loader;

import static org.lsposed.npatch.share.Constants.CONFIG_ASSET_PATH;
import android.app.ActivityThread;
import android.app.LoadedApk;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.res.CompatibilityInfo;
import android.os.Build;
import android.os.RemoteException;
import android.os.Process;
import android.system.Os;
import android.util.Log;

import com.google.gson.Gson;

import org.json.JSONArray;
import org.json.JSONObject;
import org.matrix.vector.Startup;
import org.lsposed.lspd.models.Module;
import org.lsposed.lspd.service.ILSPApplicationService;
import org.lsposed.npatch.loader.util.XLog;
import org.lsposed.npatch.service.IntegrApplicationService;
import org.lsposed.npatch.service.NeoLocalApplicationService;
import org.lsposed.npatch.service.RemoteApplicationService;
import org.lsposed.npatch.share.PatchConfig;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.function.BiConsumer;

import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import hidden.HiddenApiBridge;

/**
 * Created by Windysha
 * Updated by NkBe
 */
@SuppressWarnings("unused")
public class LSPApplication {

    private static final String TAG = "NPatch";
    private static final int FIRST_APP_ZYGOTE_ISOLATED_UID = 90000;
    private static final int PER_USER_RANGE = 100000;

    private static final Gson GSON = new Gson();

    private static ActivityThread activityThread;
    private static LoadedApk stubLoadedApk;
    private static LoadedApk appLoadedApk;

    private static PatchConfig config;
    private static String cachedOriginalApkPath;

    public static boolean isIsolated() {
        return (Process.myUid() % PER_USER_RANGE) >= FIRST_APP_ZYGOTE_ISOLATED_UID;
    }

    private static boolean hasEmbeddedModules(Context context) {
        try {
            String[] list = context.getAssets().list("npatch/modules");
            return list != null && list.length > 0;
        } catch (IOException e) {
            return false;
        }
    }

    public static void log(String msg) {
        Log.i(TAG, msg);
        XposedBridge.log(TAG + ": " + msg);
    }

    public static void log(String msg, Throwable tr) {
        Log.e(TAG, msg, tr);
        XposedBridge.log(TAG + ": " + msg + "\n" + Log.getStackTraceString(tr));
    }

    /** Best-effort reflective field set; some fields are absent on certain Android versions. */
    private static void trySetField(Object obj, String field, Object value) {
        if (obj == null) return;
        try {
            XposedHelpers.setObjectField(obj, field, value);
        } catch (Throwable ignored) {
        }
    }

    public static void onLoad() throws RemoteException, IOException {
        if (isIsolated()) {
            XLog.d(TAG, "Skip isolated process");
            return;
        }
        activityThread = ActivityThread.currentActivityThread();
        var context = createLoadedApkWithContext();
        if (context == null) {
            log("Error when creating context");
            return;
        }

        // Apply any pending mirror write-back BEFORE the host app initializes / opens its databases.
        // This is the ONLY safe window to touch the real app data in a rootless design: app uid, and
        // no SQLite handle is live yet (host Application.onCreate runs after onLoad returns). See
        // MirrorSyncManager (staging) / WritebackApplier (apply).
        if (config.mirrorMode) {
            try {
                boolean applied = WritebackApplier.applyIfPending(new File(context.getApplicationInfo().dataDir));
                if (applied) log("Applied pending mirror write-back staging");
            } catch (Throwable t) {
                log("Mirror write-back apply failed (ignored)", t);
            }
        }

        log("Initialize service client");
        ILSPApplicationService service = null;

        if (config.useManager) {
            try {
                service = new RemoteApplicationService(context);
                List<Module> m = service.getLegacyModulesList();
                JSONArray moduleArr = new JSONArray();
                if (m != null) {
                    for (Module module : m) {
                        JSONObject moduleObj = new JSONObject();
                        moduleObj.put("path", module.apkPath);
                        moduleObj.put("packageName", module.packageName);
                        moduleArr.put(moduleObj);
                    }
                }
                SharedPreferences shared = context.getSharedPreferences("npatch", Context.MODE_PRIVATE);
                shared.edit().putString("modules", moduleArr.toString()).apply();
                log("Success update module scope from Manager");
            } catch (Throwable e) {
                log("Failed to connect to manager: " + e.getMessage());
                service = null;
            }
        }

        if (service == null) {
            if (hasEmbeddedModules(context)) {
                log("Using Integrated Service (Embedded Modules Found)");
                service = new IntegrApplicationService(context);
            } else {
                log("Using NeoLocal Service (Cached Config)");
                service = new NeoLocalApplicationService(context);
            }
        }

        disableProfile(context);
        Startup.initXposed(false, ActivityThread.currentProcessName(), context.getApplicationInfo().dataDir, service);
        Startup.bootstrapXposed(false);

        // Start file-based log capture (replaces setLogPrinter removed in Vector v2.0)
        if (config.outputLog) {
            startLogcatCapture(context);
        }

        // WARN: Since it uses `XResource`, the following class should not be initialized
        // before forkPostCommon is invoke. Otherwise, you will get failure of XResources

        log("Load modules");
        LSPLoader.initModules(appLoadedApk);
        log("Modules initialized");

        switchAllClassLoader();
        SigBypass.doSigBypass(context, config.sigBypassLevel, cachedOriginalApkPath);

        // Activate GMS redirect if enabled (for Google apps with MicroG)
        if (config.useNPatchGms) {
            log("Activating NPatch GMS redirect");
            GmsRedirector.activate(context, config.originalSignature);
        }

        log("NPatch bootstrap completed");
    }

    private static Context createLoadedApkWithContext() {
        try {
            var timeStart = System.currentTimeMillis();
            var mBoundApplication = XposedHelpers.getObjectField(activityThread, "mBoundApplication");

            stubLoadedApk = (LoadedApk) XposedHelpers.getObjectField(mBoundApplication, "info");
            var appInfo = (ApplicationInfo) XposedHelpers.getObjectField(mBoundApplication, "appInfo");
            var compatInfo = (CompatibilityInfo) XposedHelpers.getObjectField(mBoundApplication, "compatInfo");
            var baseClassLoader = stubLoadedApk.getClassLoader();

            try (var is = baseClassLoader.getResourceAsStream(CONFIG_ASSET_PATH)) {
                if (is == null) throw new IOException("Config file not found in assets");
                BufferedReader streamReader = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8));
                config = GSON.fromJson(streamReader, PatchConfig.class);
            } catch (IOException e) {
                Log.e(TAG, "Failed to load config file", e);
                return null;
            }
            log("Use manager: " + config.useManager);
            log("Signature bypass level: " + config.sigBypassLevel);

            // data-fixed embeds the provider dex into the cached origin apk inside
            // prepareOriginApk(injectProvider) — no separate runtime dex injection needed.
            Path cacheApkPath = OriginApkHelper.prepareOriginApk(appInfo, baseClassLoader, config.injectProvider);
            cachedOriginalApkPath = cacheApkPath.toString();
            String cacheApk = cacheApkPath.toString();
            // Redirect EVERY apk-source field consistently to the cached origin apk. Only setting
            // sourceDir/publicSourceDir leaves scanSourceDir and the LoadedApk's mResDir/
            // mApplicationInfo pointing elsewhere (mResDir stays null), which makes resource
            // resolution inconsistent — breaking e.g. "No package ID 6a" and the system-WebView
            // provider class loading (createApplicationContext) that ~90% of apps rely on.
            appInfo.sourceDir = cacheApk;
            appInfo.publicSourceDir = cacheApk;
            appInfo.appComponentFactory = config.appComponentFactory;
            trySetField(appInfo, "scanSourceDir", cacheApk);
            trySetField(appInfo, "scanPublicSourceDir", cacheApk);

            var mPackages = (Map<?, ?>) XposedHelpers.getObjectField(activityThread, "mPackages");
            mPackages.remove(appInfo.packageName);
            appLoadedApk = activityThread.getPackageInfoNoCheck(appInfo, compatInfo);

            // Keep both LoadedApks' ApplicationInfo and resource dir consistent with the cached apk.
            trySetField(appLoadedApk, "mApplicationInfo", appInfo);
            trySetField(stubLoadedApk, "mApplicationInfo", appInfo);
            trySetField(appLoadedApk, "mResDir", cacheApk);
            trySetField(stubLoadedApk, "mResDir", cacheApk);

            XposedHelpers.setObjectField(mBoundApplication, "info", appLoadedApk);

            var activityClientRecordClass = XposedHelpers.findClass("android.app.ActivityThread$ActivityClientRecord", ActivityThread.class.getClassLoader());
            var fixActivityClientRecord = (BiConsumer<Object, Object>) (k, v) -> {
                if (activityClientRecordClass.isInstance(v)) {
                    var pkgInfo = XposedHelpers.getObjectField(v, "packageInfo");
                    if (pkgInfo == stubLoadedApk) {
                        Log.d(TAG, "fix loadedapk from ActivityClientRecord");
                        XposedHelpers.setObjectField(v, "packageInfo", appLoadedApk);
                    }
                }
            };
            var mActivities = (Map<?, ?>) XposedHelpers.getObjectField(activityThread, "mActivities");
            mActivities.forEach(fixActivityClientRecord);
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    var mLaunchingActivities = (Map<?, ?>) XposedHelpers.getObjectField(activityThread, "mLaunchingActivities");
                    mLaunchingActivities.forEach(fixActivityClientRecord);
                }
            } catch (Throwable ignored) {
            }
            log("hooked app initialized: " + appLoadedApk);

            var context = (Context) XposedHelpers.callStaticMethod(Class.forName("android.app.ContextImpl"), "createAppContext", activityThread, appLoadedApk);
            if (config.appComponentFactory != null) {
                try {
                    context.getClassLoader().loadClass(config.appComponentFactory);
                } catch (Throwable e) {
                    Log.w(TAG, "Original AppComponentFactory not found: " + config.appComponentFactory, e);
                    appInfo.appComponentFactory = null;
                }
            }
            log("createLoadedApkWithContext cost: " + (System.currentTimeMillis() - timeStart) + "ms");

            SigBypass.replaceApplication(appInfo.packageName, appInfo.sourceDir, appInfo.publicSourceDir);
            return context;
        } catch (Throwable e) {
            log("createLoadedApk", e);
            return null;
        }
    }

    private static void startLogcatCapture(Context context) {
        try {
            String pkgName = context.getPackageName();
            File logDir = new File(android.os.Environment.getExternalStorageDirectory(),
                    "Android/media/" + pkgName + "/npatch/log");
            if (!logDir.exists() && !logDir.mkdirs()) {
                Log.w(TAG, "Failed to create log directory: " + logDir);
                return;
            }
            String dateStr = new java.text.SimpleDateFormat("yyyyMMdd", java.util.Locale.US)
                    .format(new java.util.Date());
            File logFile = new File(logDir, dateStr + ".log");

            // Capture the whole log of THIS process (framework "Vector"/"LSPosed-*"/"Xposed"
            // tags, every loaded module's logs and the host app itself) regardless of tag.
            //
            // The previous tag whitelist ("NPatch:*", ... , "*:S") missed the core framework
            // tag "Vector" and any module-defined tag, and reading the global logcat requires
            // the READ_LOGS permission that a non-root patched app does not hold — so it would
            // silently record almost nothing. Filtering by our own pid instead works without
            // READ_LOGS (an app may always read its own process log) and keeps other processes'
            // noise out.
            int myPid = android.os.Process.myPid();
            String[] cmd = {"logcat", "-v", "threadtime", "--pid", String.valueOf(myPid), "*:V"};
            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.redirectErrorStream(true);
            pb.redirectOutput(ProcessBuilder.Redirect.appendTo(logFile));
            pb.start();
            Log.i(TAG, "Logcat capture started (pid=" + myPid + ") -> " + logFile.getAbsolutePath());
        } catch (Throwable e) {
            Log.w(TAG, "Failed to start logcat capture", e);
        }
    }

    public static void disableProfile(Context context) {
        var appInfo = context.getApplicationInfo();
        if (appInfo == null) return;

        var codePaths = new ArrayList<String>();
        if ((appInfo.flags & ApplicationInfo.FLAG_HAS_CODE) != 0) codePaths.add(appInfo.sourceDir);
        if (appInfo.splitSourceDirs != null) Collections.addAll(codePaths, appInfo.splitSourceDirs);
        if (codePaths.isEmpty()) return;

        var profileDir = HiddenApiBridge.Environment_getDataProfilesDePackageDirectory(appInfo.uid / PER_USER_RANGE, context.getPackageName());

        for (int i = codePaths.size() - 1; i >= 0; i--) {
            String splitName = i == 0 ? null : appInfo.splitNames[i - 1];
            File profile = new File(profileDir, splitName == null ? "primary.prof" : splitName + ".split.prof");

            try {
                // 如果已是 0 字節且唯讀，直接跳過
                if (profile.exists() && profile.length() == 0 && !profile.canWrite()) continue;
                // 自動將已存在的檔案內容清空或建立新檔
                try (var ignored = new FileOutputStream(profile)) {
                }
                // 設定檔案只讀
                Os.chmod(profile.getAbsolutePath(), 00444);

            } catch (Throwable e) {
                Log.e(TAG, "Failed to disable profile: " + profile.getName(), e);
            }
        }
    }

    private static void switchAllClassLoader() {
        var fields = LoadedApk.class.getDeclaredFields();
        for (Field field : fields) {
            if (field.getType() == ClassLoader.class) {
                var obj = XposedHelpers.getObjectField(appLoadedApk, field.getName());
                XposedHelpers.setObjectField(stubLoadedApk, field.getName(), obj);
            }
        }
    }
}
