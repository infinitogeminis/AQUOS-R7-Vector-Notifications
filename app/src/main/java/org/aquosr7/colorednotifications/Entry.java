package org.aquosr7.colorednotifications;

import android.util.Log;
import android.widget.ImageView;

import io.github.libxposed.api.XposedModule;
import io.github.libxposed.api.XposedModuleInterface.PackageReadyParam;
import io.github.libxposed.api.XposedInterface.ExceptionMode;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

/** Vector module for Sharp AQUOS R7 / Android 14 SystemUI. */
public final class Entry extends XposedModule {
    private static final String TAG = "AQUOSR7ColoredNotifications";
    private static final String SYSTEM_UI = "com.android.systemui";
    private static final String ICON_VIEW =
            "com.android.systemui.statusbar.StatusBarIconView";

    @Override
    public void onPackageReady(PackageReadyParam param) {
        if (!SYSTEM_UI.equals(param.getPackageName())) return;

        try {
            Class<?> iconClass = param.getClassLoader().loadClass(ICON_VIEW);
            Field notification = iconClass.getDeclaredField("mNotification");
            notification.setAccessible(true);

            hookAfterColor(iconClass.getDeclaredMethod("setStaticDrawableColor", int.class),
                    notification, "setStaticDrawableColor(int)");
            hookAfterColor(iconClass.getDeclaredMethod("updateIconColor"),
                    notification, "updateIconColor()");
            hookAfterColor(iconClass.getDeclaredMethod("updateContrastedStaticColor"),
                    notification, "updateContrastedStaticColor()");

            log(Log.INFO, TAG, "Hooks registered for AQUOS R7 SystemUI");
        } catch (Throwable t) {
            log(Log.ERROR, TAG, "Hook setup failed; no tint override applied", t);
        }
    }

    private void hookAfterColor(Method method, Field notification, String label) {
        hook(method)
                .setExceptionMode(ExceptionMode.PROTECTIVE)
                .intercept(chain -> {
                    Object result = chain.proceed();
                    Object target = chain.getThisObject();
                    if (target instanceof ImageView && notification.get(target) != null) {
                        ((ImageView) target).clearColorFilter();
                    }
                    return result;
                });
        log(Log.INFO, TAG, "Hook registered: " + label);
    }
}
