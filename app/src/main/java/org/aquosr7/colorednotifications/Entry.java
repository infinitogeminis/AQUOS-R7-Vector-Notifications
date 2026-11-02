package org.aquosr7.colorednotifications;

import android.util.Log;

import io.github.libxposed.api.XposedModule;
import io.github.libxposed.api.XposedModuleInterface.ModuleLoadedParam;
import io.github.libxposed.api.XposedModuleInterface.PackageReadyParam;

/**
 * Lifecycle-only diagnostic for Sharp AQUOS R7 / Android 14.
 * Uses Android's logcat directly; no SystemUI methods are hooked or modified.
 */
public final class Entry extends XposedModule {
    private static final String TAG = "AQUOSR7ColoredNotifications";
    private static final String SYSTEM_UI = "com.android.systemui";

    static {
        Log.e(TAG, "DIAG class_initialized");
    }

    public Entry() {
        super();
        Log.e(TAG, "DIAG constructor_completed");
    }

    @Override
    public void onModuleLoaded(ModuleLoadedParam param) {
        Log.e(TAG, "DIAG CALLBACK onModuleLoaded process=" + param.getProcessName()
                + " systemServer=" + param.isSystemServer());
    }

    @Override
    public void onPackageReady(PackageReadyParam param) {
        String packageName = param.getPackageName();
        Log.e(TAG, "DIAG CALLBACK onPackageReady package=" + packageName
                + " classLoader=" + param.getClassLoader());

        if (SYSTEM_UI.equals(packageName)) {
            Log.e(TAG, "DIAG TARGET SystemUI callback confirmed");
        }
    }
}
