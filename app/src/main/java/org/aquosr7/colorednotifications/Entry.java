package org.aquosr7.colorednotifications;

import android.content.pm.PackageManager;
import android.graphics.drawable.Drawable;
import android.service.notification.StatusBarNotification;
import android.util.Log;
import android.view.View;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

import io.github.libxposed.api.XposedModule;
import io.github.libxposed.api.XposedModuleInterface.ModuleLoadedParam;
import io.github.libxposed.api.XposedModuleInterface.PackageLoadedParam;
import io.github.libxposed.api.XposedModuleInterface.PackageReadyParam;

/**
 * Replaces only third-party notification status-bar glyphs with their package
 * launcher icon. The existing StatusBarIconView remains responsible for
 * measuring and drawing the drawable at AQUOS R7's native notification size.
 */
public final class Entry extends XposedModule {
    private static final String TAG = "AQUOSR7ColoredNotifications";
    private static final String SYSTEM_UI = "com.android.systemui";
    private static final String ICON_VIEW =
            "com.android.systemui.statusbar.StatusBarIconView";

    @Override
    public void onModuleLoaded(ModuleLoadedParam param) {
        Log.i(TAG, "MODULE_LOADED process=" + param.getProcessName());
    }

    @Override
    public void onPackageLoaded(PackageLoadedParam param) {
        Log.i(TAG, "PACKAGE_LOADED package=" + param.getPackageName());
    }

    @Override
    public void onPackageReady(PackageReadyParam param) {
        Log.i(TAG, "PACKAGE_READY package=" + param.getPackageName());
        if (!SYSTEM_UI.equals(param.getPackageName())) return;

        try {
            Class<?> iconView = param.getClassLoader().loadClass(ICON_VIEW);
            installApplicationIconHook(iconView);
            installColorPreservationHooks(iconView);
            Log.i(TAG, "SystemUI hooks initialized");
        } catch (Throwable error) {
            Log.e(TAG, "SystemUI hook initialization failed", error);
        }
    }

    private void installApplicationIconHook(Class<?> iconView) {
        try {
            Class<?> statusBarIcon = iconView.getClassLoader()
                    .loadClass("com.android.internal.statusbar.StatusBarIcon");
            Method method = findMethod(iconView, "getIcon", statusBarIcon);
            if (method == null) throw new NoSuchMethodException("getIcon(StatusBarIcon)");

            hook(method).setId("aquosr7-original-application-icon").intercept(chain -> {
                Drawable notificationDrawable = (Drawable) chain.proceed();
                Object target = chain.getThisObject();
                StatusBarNotification notification = getNotification(target);

                if (notificationDrawable == null || notification == null) {
                    return notificationDrawable;
                }

                String packageName = notification.getPackageName();
                if (packageName == null || SYSTEM_UI.equals(packageName)) {
                    return notificationDrawable;
                }

                try {
                    View view = (View) target;
                    Drawable applicationIcon = view.getContext()
                            .getPackageManager().getApplicationIcon(packageName);
                    if (applicationIcon == null) return notificationDrawable;

                    Log.i(TAG, "APP_ICON_REPLACED package=" + packageName
                            + " intrinsic=" + applicationIcon.getIntrinsicWidth()
                            + "x" + applicationIcon.getIntrinsicHeight());
                    return applicationIcon;
                } catch (PackageManager.NameNotFoundException error) {
                    Log.w(TAG, "No launcher icon for " + packageName
                            + "; keeping notification icon", error);
                    return notificationDrawable;
                } catch (Throwable error) {
                    Log.e(TAG, "Application icon replacement failed for "
                            + packageName, error);
                    return notificationDrawable;
                }
            });
            Log.i(TAG, "Installed getIcon(StatusBarIcon)");
        } catch (Throwable error) {
            Log.e(TAG, "Cannot install application icon hook", error);
        }
    }

    /**
     * Sharp's StatusBarIconView has no crDroid NO_COLOR/mNewIconStyle fields.
     * Therefore do not pass a guessed sentinel value: skip only the color
     * mutation methods for third-party notification views. SystemUI icons
     * continue through the original methods unchanged.
     */
    private void installColorPreservationHooks(Class<?> iconView) {
        hookAppOnly(iconView, "setStaticDrawableColor", new Class<?>[]{int.class},
                "aquosr7-preserve-static-color");
        hookAppOnly(iconView, "setIconColor",
                new Class<?>[]{int.class, boolean.class},
                "aquosr7-preserve-animated-color");
        hookAppOnly(iconView, "updateContrastedStaticColor", new Class<?>[]{},
                "aquosr7-preserve-contrast-color");
        hookAppOnly(iconView, "updateIconColor", new Class<?>[]{},
                "aquosr7-preserve-drawable-color");
    }

    private void hookAppOnly(Class<?> type, String name, Class<?>[] parameters,
                             String hookId) {
        try {
            Method method = findMethod(type, name, parameters);
            if (method == null) {
                throw new NoSuchMethodException(name);
            }
            hook(method).setId(hookId).intercept(chain -> {
                Object target = chain.getThisObject();
                if (!isThirdPartyNotification(target)) {
                    return chain.proceed();
                }

                // updateIconColor normally installs a ColorFilter. Clear any
                // previous filter and suppress future monochrome conversion.
                if ("updateIconColor".equals(name)) {
                    clearDrawableColorFilter(target);
                }
                return null;
            });
            Log.i(TAG, "Installed " + name);
        } catch (Throwable error) {
            Log.e(TAG, "Cannot install color hook " + name, error);
        }
    }

    private static boolean isThirdPartyNotification(Object target) {
        StatusBarNotification notification = getNotification(target);
        return notification != null
                && notification.getPackageName() != null
                && !SYSTEM_UI.equals(notification.getPackageName());
    }

    private static StatusBarNotification getNotification(Object target) {
        if (target == null) return null;
        try {
            Field field = findField(target.getClass(), "mNotification");
            if (field == null) return null;
            field.setAccessible(true);
            Object value = field.get(target);
            return value instanceof StatusBarNotification
                    ? (StatusBarNotification) value : null;
        } catch (Throwable error) {
            Log.w(TAG, "Cannot read StatusBarIconView.mNotification", error);
            return null;
        }
    }

    private static void clearDrawableColorFilter(Object target) {
        try {
            Method method = findMethod(target.getClass(), "setColorFilter",
                    android.graphics.ColorFilter.class);
            if (method != null) {
                method.setAccessible(true);
                method.invoke(target, new Object[]{null});
            }
        } catch (Throwable error) {
            Log.w(TAG, "Cannot clear notification drawable color filter", error);
        }
    }

    private static Method findMethod(Class<?> type, String name,
                                     Class<?>... parameters) {
        for (Class<?> current = type; current != null;
             current = current.getSuperclass()) {
            try {
                return current.getDeclaredMethod(name, parameters);
            } catch (NoSuchMethodException ignored) {
                // Continue through the superclass chain.
            }
        }
        return null;
    }

    private static Field findField(Class<?> type, String name) {
        for (Class<?> current = type; current != null;
             current = current.getSuperclass()) {
            try {
                return current.getDeclaredField(name);
            } catch (NoSuchFieldException ignored) {
                // Continue through the superclass chain.
            }
        }
        return null;
    }
}
