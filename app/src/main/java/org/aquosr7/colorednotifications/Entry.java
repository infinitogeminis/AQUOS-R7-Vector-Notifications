package org.aquosr7.colorednotifications;

import android.util.Log;
import android.widget.ImageView;

import io.github.libxposed.api.XposedModule;
import io.github.libxposed.api.XposedModuleInterface.ModuleLoadedParam;
import io.github.libxposed.api.XposedModuleInterface.PackageReadyParam;
import io.github.libxposed.api.XposedInterface.ExceptionMode;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

/** Diagnostic build for Sharp AQUOS R7 / Android 14 SystemUI. */
public final class Entry extends XposedModule {
    private static final String TAG = "AQUOSR7ColoredNotifications";
    private static final String SYSTEM_UI = "com.android.systemui";
    private static final String ICON_VIEW =
            "com.android.systemui.statusbar.StatusBarIconView";

    @Override
    public void onModuleLoaded(ModuleLoadedParam param) {
        log(Log.INFO, TAG, "DIAG onModuleLoaded process=" + param.getProcessName()
                + " systemServer=" + param.isSystemServer());
    }

    @Override
    public void onPackageReady(PackageReadyParam param) {
        String packageName = param.getPackageName();
        log(Log.INFO, TAG, "DIAG onPackageReady package=" + packageName);

        if (!SYSTEM_UI.equals(packageName)) {
            log(Log.INFO, TAG, "DIAG skip package=" + packageName
                    + " expected=" + SYSTEM_UI);
            return;
        }

        try {
            ClassLoader classLoader = param.getClassLoader();
            log(Log.INFO, TAG, "DIAG SystemUI classLoader=" + classLoader);

            Class<?> iconClass = classLoader.loadClass(ICON_VIEW);
            log(Log.INFO, TAG, "DIAG found class=" + iconClass.getName()
                    + " superclass=" + iconClass.getSuperclass());

            Field notification = iconClass.getDeclaredField("mNotification");
            notification.setAccessible(true);
            log(Log.INFO, TAG, "DIAG found field mNotification");

            hookAfterColor(iconClass.getDeclaredMethod("setStaticDrawableColor", int.class),
                    notification, "setStaticDrawableColor(int)");
            hookAfterColor(iconClass.getDeclaredMethod("updateIconColor"),
                    notification, "updateIconColor()");
            hookAfterColor(iconClass.getDeclaredMethod("updateContrastedStaticColor"),
                    notification, "updateContrastedStaticColor()");

            log(Log.INFO, TAG, "DIAG ALL_HOOKS_REGISTERED package=" + packageName);
        } catch (Throwable t) {
            log(Log.ERROR, TAG, "DIAG HOOK_SETUP_FAILED package=" + packageName, t);
        }
    }

    private void hookAfterColor(Method method, Field notification, String label) {
        hook(method)
                .setExceptionMode(ExceptionMode.PROTECTIVE)
                .intercept(chain -> {
                    Object result = chain.proceed();
                    try {
                        Object target = chain.getThisObject();
                        Object sbn = notification.get(target);
                        if (target instanceof ImageView && sbn != null) {
                            ((ImageView) target).clearColorFilter();
                        }
                    } catch (Throwable t) {
                        log(Log.ERROR, TAG, "DIAG interceptor failed: " + label, t);
                    }
                    return result;
                });
        log(Log.INFO, TAG, "DIAG HOOK_REGISTERED " + label);
    }
}
