package org.lsposed.npatch.service;

import android.annotation.SuppressLint;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.Build;
import android.os.Environment;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.ParcelFileDescriptor;
import android.os.RemoteException;
import android.os.UserHandle;
import android.util.Log;

import android.os.Bundle;

import org.lsposed.npatch.share.Constants;
import org.lsposed.lspd.models.Module;
import org.lsposed.lspd.service.ILSPApplicationService;
import org.lsposed.lspd.service.ILSPInjectedModuleService;
import org.lsposed.lspd.service.IRemotePreferenceCallback;

import java.io.File;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

public class RemoteApplicationService implements ILSPApplicationService {

    private static final String TAG = "NPatch";
    private static final String MODULE_SERVICE = "org.lsposed.npatch.manager.ModuleService";
    private static final int CONNECTION_TIMEOUT_SEC = 1;

    private volatile ILSPApplicationService service;

    // Disconnect protection for the useManager path: when the manager process dies in the
    // background, a module calling requestRemotePreferences/openRemoteFile on the manager-held
    // binder throws DeadObjectException into the HOST app process, which can crash it. We cache the
    // last known module scope to keep serving it, and wrap each module.service in a
    // DisconnectSafeInjectedModuleService that swallows RemoteException instead of propagating.
    private final AtomicBoolean managerAvailable = new AtomicBoolean(true);
    private volatile List<Module> cachedModuleScope = Collections.emptyList();
    private volatile List<Module> cachedLegacyModuleScope = Collections.emptyList();

    @SuppressLint("DiscouragedPrivateApi")
    public RemoteApplicationService(Context context) throws RemoteException {
        try {
            Intent intent = new Intent()
                    .setComponent(new ComponentName(Constants.MANAGER_PACKAGE_NAME, MODULE_SERVICE))
                    .putExtra("packageName", context.getPackageName());

            CountDownLatch latch = new CountDownLatch(1);

            ServiceConnection conn = new ServiceConnection() {
                @Override
                public void onServiceConnected(ComponentName name, IBinder binder) {
                    Log.i(TAG, "Manager binder received");
                    service = Stub.asInterface(binder);
                    managerAvailable.set(true);
                    latch.countDown();
                }

                @Override
                public void onServiceDisconnected(ComponentName name) {
                    Log.e(TAG, "Manager service died");
                    service = null;
                    managerAvailable.set(false);
                }
            };

            Log.i(TAG, "Requesting manager binder...");
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                context.bindService(intent, Context.BIND_AUTO_CREATE, Executors.newSingleThreadExecutor(), conn);
            } else {
                HandlerThread handlerThread = new HandlerThread("RemoteApplicationService");
                handlerThread.start();
                Handler handler = new Handler(handlerThread.getLooper());

                Class<?> contextImplClass = context.getClass();
                Method getUserMethod = contextImplClass.getMethod("getUser");
                UserHandle userHandle = (UserHandle) getUserMethod.invoke(context);

                Method bindServiceAsUserMethod = contextImplClass.getDeclaredMethod(
                        "bindServiceAsUser",
                        Intent.class,
                        ServiceConnection.class,
                        int.class,
                        Handler.class,
                        UserHandle.class
                );

                bindServiceAsUserMethod.invoke(context, intent, conn, Context.BIND_AUTO_CREATE, handler, userHandle);
            }

            boolean success = latch.await(CONNECTION_TIMEOUT_SEC, TimeUnit.SECONDS);
            if (!success) {
                throw new TimeoutException("Bind service timeout");
            }

        } catch (NoSuchMethodException | IllegalAccessException | InvocationTargetException |
                 InterruptedException | TimeoutException e) {
            
            RemoteException remoteException = new RemoteException("Failed to get manager binder");
            remoteException.initCause(e);
            throw remoteException;
        }
    }

    @Override
    public List<Module> getLegacyModulesList() throws RemoteException {
        ILSPApplicationService current = service;
        if (current == null || !managerAvailable.get()) {
            return getCachedModuleScope(true);
        }
        try {
            List<Module> wrapped = wrapModuleServices(current.getLegacyModulesList());
            cachedLegacyModuleScope = wrapped;
            return new ArrayList<>(wrapped);
        } catch (RemoteException error) {
            managerAvailable.set(false);
            return getCachedModuleScope(true);
        }
    }

    @Override
    public List<Module> getModulesList() throws RemoteException {
        ILSPApplicationService current = service;
        if (current == null || !managerAvailable.get()) {
            return getCachedModuleScope(false);
        }
        try {
            List<Module> wrapped = wrapModuleServices(current.getModulesList());
            cachedModuleScope = wrapped;
            return new ArrayList<>(wrapped);
        } catch (RemoteException error) {
            managerAvailable.set(false);
            return getCachedModuleScope(false);
        }
    }

    private List<Module> getCachedModuleScope(boolean legacy) {
        return new ArrayList<>(legacy ? cachedLegacyModuleScope : cachedModuleScope);
    }

    /** Wrap each module's injected service so a later manager death degrades gracefully. */
    private List<Module> wrapModuleServices(List<Module> modules) {
        if (modules == null) {
            return new ArrayList<>();
        }
        for (Module module : modules) {
            if (module != null && module.service != null
                    && !(module.service instanceof DisconnectSafeInjectedModuleService)) {
                module.service = new DisconnectSafeInjectedModuleService(module.service);
            }
        }
        return modules;
    }

    @Override
    public String getPrefsPath(String packageName) {
        return new File(Environment.getDataDirectory(), "data/" + packageName + "/shared_prefs/")
                .getAbsolutePath();
    }

    @Override
    public IBinder asBinder() {
        return service == null ? null : service.asBinder();
    }

    @Override
    public ParcelFileDescriptor requestInjectedManagerBinder(List<IBinder> binder) {
        return null;

    }

    @Override
    public boolean isLogMuted() throws RemoteException {
        return false;
    }

    /**
     * Proxies a module's injected service so that after the manager process dies, calls degrade
     * gracefully (swallow RemoteException) instead of throwing DeadObjectException into the host app.
     */
    private static final class DisconnectSafeInjectedModuleService extends ILSPInjectedModuleService.Stub {
        private final ILSPInjectedModuleService delegate;

        DisconnectSafeInjectedModuleService(ILSPInjectedModuleService delegate) {
            this.delegate = delegate;
        }

        @Override
        public long getFrameworkProperties() {
            try {
                return delegate.getFrameworkProperties();
            } catch (RemoteException error) {
                return 0L;
            }
        }

        @Override
        public Bundle requestRemotePreferences(String group, IRemotePreferenceCallback callback) {
            try {
                Bundle result = delegate.requestRemotePreferences(group, callback);
                return result == null ? Bundle.EMPTY : result;
            } catch (RemoteException error) {
                return Bundle.EMPTY;
            }
        }

        @Override
        public ParcelFileDescriptor openRemoteFile(String path) {
            try {
                return delegate.openRemoteFile(path);
            } catch (RemoteException error) {
                return null;
            }
        }

        @Override
        public String[] getRemoteFileList() {
            try {
                return delegate.getRemoteFileList();
            } catch (RemoteException error) {
                return new String[0];
            }
        }
    }
}