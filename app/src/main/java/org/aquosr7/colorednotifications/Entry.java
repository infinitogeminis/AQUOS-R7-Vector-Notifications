package org.aquosr7.colorednotifications;

import android.service.notification.StatusBarNotification;
import android.util.Log;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;

import io.github.libxposed.api.XposedModule;
import io.github.libxposed.api.XposedInterface;
import io.github.libxposed.api.XposedModuleInterface.PackageReadyParam;

/**
 * Keeps third-party notification icons untinted in AQUOS R7 SystemUI.
 * Port of crDroid's NO_COLOR + clear ColorFilter/ImageTintList behavior.
 */
public final class Entry extends XposedModule {
    private static final String TAG = "AQUOSR7ColoredNotifications";
    private static final String SYSTEM_UI = "com.android.systemui";
    private static final String ICON_VIEW =
            "com.android.systemui.statusbar.StatusBarIconView";

    @Override
    public void onPackageReady(PackageReadyParam param) {
        if (!SYSTEM_UI.equals(param.getPackageName())) return;

        try {
            ClassLoader loader = param.getClassLoader();
            Class<?> iconClass = loader.loadClass(ICON_VIEW);
            Field noColorField = findField(iconClass, "NO_COLOR");
            if (noColorField == null) {
                Log.e(TAG, "NO_COLOR field not found; refusing unsafe tint override");
                return;
            }
            noColorField.setAccessible(true);
            final int noColor = noColorField.getInt(null);

            Method setStaticColor = iconClass.getDeclaredMethod(
                    "setStaticDrawableColor", int.class);
            setStaticColor.setAccessible(true);
            hook(setStaticColor).setId("aquosr7-third-party-no-color")
                    .intercept(chain -> {
                        Object view = chain.getThisObject();
                        if (isThirdPartyNotification(view)) {
                            return chain.proceed(new Object[]{noColor});
                        }
                        return chain.proceed();
                    });

            Method updateIconColor = iconClass.getDeclaredMethod("updateIconColor");
            updateIconColor.setAccessible(true);
            hook(updateIconColor).setId("aquosr7-third-party-clear-tint")
                    .intercept(chain -> {
                        Object view = chain.getThisObject();
                        Object result = chain.proceed();
                        if (isThirdPartyNotification(view)) {
                            clearImageTint(view);
                        }
                        return result;
                    });

            Log.i(TAG, "Installed crDroid-style notification tint hooks; NO_COLOR="
                    + noColor);
        } catch (Throwable t) {
            Log.e(TAG, "Failed to install notification color hooks", t);
        }
    }

    private static boolean isThirdPartyNotification(Object view) {
        if (view == null) return false;
        try {
            Field notificationField = findField(view.getClass(), "mNotification");
            if (notificationField == null) return false;
            notificationField.setAccessible(true);
            Object value = notificationField.get(view);
            if (!(value instanceof StatusBarNotification)) return false;
            String packageName = ((StatusBarNotification) value).getPackageName();
            return packageName != null && !SYSTEM_UI.equals(packageName);
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static void clearImageTint(Object view) {
        invokeSingleArg(view, "setColorFilter",
                android.graphics.ColorFilter.class, null);
        invokeSingleArg(view, "setImageTintList",
                android.content.res.ColorStateList.class, null);
    }

    private static void invokeSingleArg(Object target, String name,
            Class<?> parameterType, Object value) {
        try {
            Method method = findMethod(target.getClass(), name, parameterType);
            if (method == null) return;
            method.setAccessible(true);
            method.invoke(target, value);
        } catch (Throwable t) {
            Log.w(TAG, "Unable to invoke " + name, t);
        }
    }

    private static Method findMethod(Class<?> type, String name, Class<?> parameter) {
        for (Class<?> c = type; c != null; c = c.getSuperclass()) {
            try {
                Method m = c.getDeclaredMethod(name, parameter);
                if (!Modifier.isStatic(m.getModifiers())) return m;
            } catch (NoSuchMethodException ignored) {
            }
        }
        return null;
    }

    private static Field findField(Class<?> type, String name) {
        for (Class<?> c = type; c != null; c = c.getSuperclass()) {
            try {
                return c.getDeclaredField(name);
            } catch (NoSuchFieldException ignored) {
            }
        }
        return null;
    }
}
