package org.lsposed.npatch.service;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.net.Uri;
import android.os.IBinder;
import android.os.ParcelFileDescriptor;
import android.os.RemoteException;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;
import org.lsposed.npatch.util.ModuleLoader;
import org.lsposed.lspd.models.Module;
import org.lsposed.lspd.service.ILSPApplicationService;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class NeoLocalApplicationService extends ILSPApplicationService.Stub {
    private static final String TAG = "NPatch";
    private static final String AUTHORITY = "org.lsposed.npatch.manager.provider.config";
    private static final Uri PROVIDER_URI = Uri.parse("content://" + AUTHORITY + "/config");

    private final List<Module> cachedModule;

    public NeoLocalApplicationService(Context context) {
        cachedModule = Collections.synchronizedList(new ArrayList<>());
        boolean providerAvailable = loadModulesFromProvider(context);

        // Only fall back to the local cache when the Manager Provider is genuinely
        // unreachable (process gone / uninstalled). If the provider IS reachable but
        // returns 0 modules, that means the user disabled all modules for this app —
        // respect that instead of resurrecting a stale cached scope.
        //
        // This is the decoupling that lets injection survive an unavailable manager:
        // every reachable query refreshes the cache (see updateModulesCache), so the
        // patched app keeps a fresh, self-sufficient module list to load from.
        if (!providerAvailable && cachedModule.isEmpty()) {
            Log.w(TAG, "NeoLocal: Provider unavailable, falling back to local cache.");
            loadModulesFromCache(context);
        }
    }

    private void loadModulesFromCache(Context context) {
        try {
            SharedPreferences shared = context.getSharedPreferences("npatch", Context.MODE_PRIVATE);
            String jsonStr = shared.getString("modules", "[]");
            JSONArray jsonArray = new JSONArray(jsonStr);
            PackageManager pm = context.getPackageManager();

            Log.i(TAG, "NeoLocal: Loading from cache: " + jsonStr);

            for (int i = 0; i < jsonArray.length(); i++) {
                JSONObject obj = jsonArray.getJSONObject(i);
                String packageName = obj.optString("packageName");
                String path = obj.optString("path");

                if (path != null && !path.isEmpty() && new File(path).exists()) {
                    loadModuleByPath(packageName, path);
                } else if (packageName != null) {
                    loadSingleModule(pm, packageName);
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "NeoLocal: Failed to load from cache", e);
        }
    }

    private void loadModuleByPath(String pkgName, String path) {
        try {
            Module m = new Module();
            m.packageName = pkgName;
            m.apkPath = path;
            m.file = ModuleLoader.loadModule(m.apkPath);
            cachedModule.add(m);
            Log.i(TAG, "Loaded cached module " + pkgName);
        } catch (Throwable e) {
            Log.e(TAG, "Failed to load cached module " + pkgName, e);
        }
    }

    private boolean loadModulesFromProvider(Context context) {
        PackageManager pm = context.getPackageManager();
        String myPackageName = context.getPackageName();
        JSONArray cacheArray = new JSONArray();

        Uri queryUri = PROVIDER_URI.buildUpon()
                .appendQueryParameter("package", myPackageName)
                .build();

        try (Cursor cursor = context.getContentResolver().query(queryUri, null, null, null, null)) {
            if (cursor == null) {
                Log.w(TAG, "NeoLocal: Cannot reach Manager Provider.");
                return false;
            }

            while (cursor.moveToNext()) {
                int colIndex = cursor.getColumnIndex("packageName");
                if (colIndex != -1) {
                    String packageName = cursor.getString(colIndex);
                    String apkPath = loadSingleModule(pm, packageName);
                    if (apkPath != null) {
                        JSONObject moduleObj = new JSONObject();
                        moduleObj.put("path", apkPath);
                        moduleObj.put("packageName", packageName);
                        cacheArray.put(moduleObj);
                    }
                }
            }
            // Refresh the self-sufficient local cache with whatever the (reachable)
            // manager just reported, so a future launch can still inject if the manager
            // becomes unavailable.
            updateModulesCache(context, cacheArray);
            return true;
        } catch (Exception e) {
            Log.e(TAG, "NeoLocal: Provider query failed", e);
            return false;
        }
    }

    private String loadSingleModule(PackageManager pm, String pkgName) {
        try {
            ApplicationInfo appInfo = pm.getApplicationInfo(pkgName, 0);
            Module m = new Module();
            m.packageName = pkgName;
            m.apkPath = appInfo.sourceDir;

            if (m.apkPath != null && new File(m.apkPath).exists()) {
                m.file = ModuleLoader.loadModule(m.apkPath);
                cachedModule.add(m);
                Log.i(TAG, "NeoLocal: Loaded module " + pkgName);
                return m.apkPath;
            }
        } catch (Throwable e) {
            Log.e(TAG, "NeoLocal: Failed to load " + pkgName, e);
        }
        return null;
    }

    private void updateModulesCache(Context context, JSONArray modules) {
        try {
            SharedPreferences shared = context.getSharedPreferences("npatch", Context.MODE_PRIVATE);
            shared.edit().putString("modules", modules.toString()).apply();
            Log.i(TAG, "NeoLocal: Updated local modules cache: " + modules);
        } catch (Throwable e) {
            Log.e(TAG, "NeoLocal: Failed to update local modules cache", e);
        }
    }

    @Override
    public List<Module> getLegacyModulesList() throws RemoteException {
        return cachedModule;
    }

    @Override
    public List<Module> getModulesList() throws RemoteException {
        return new ArrayList<>();
    }

    @Override
    public String getPrefsPath(String packageName) throws RemoteException { return "/data/data/" + packageName + "/shared_prefs/"; }
    @Override
    public ParcelFileDescriptor requestInjectedManagerBinder(List<IBinder> binder) throws RemoteException { return null; }
    @Override
    public IBinder asBinder() {
        return this;
    }

    @Override
    public boolean isLogMuted() throws RemoteException {
        return false;
    }
}
