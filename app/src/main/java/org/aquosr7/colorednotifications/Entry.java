package org.aquosr7.colorednotifications;

import android.content.Context;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.graphics.ColorFilter;
import android.graphics.drawable.Drawable;
import android.service.notification.StatusBarNotification;
import android.util.Log;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;

import io.github.libxposed.api.XposedModule;
import io.github.libxposed.api.XposedModuleInterface.PackageReadyParam;

/** crDroid notification icon color behavior adapted to AQUOS R7 SystemUI. */
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
            if (noColorField == null) throw new NoSuchFieldException("NO_COLOR");
            noColorField.setAccessible(true);
            final int noColor = noColorField.getInt(null);

            Method setStaticColor = iconClass.getDeclaredMethod(
                    "setStaticDrawableColor", int.class);
            setStaticColor.setAccessible(true);
            hook(setStaticColor).setId("aquosr7-third-party-no-color")
                    .intercept(chain -> {
                        if (isThirdPartyNotification(chain.getThisObject())) {
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
                        if (isThirdPartyNotification(view)) clearImageTint(view);
                        return result;
                    });

            // crDroid loadDrawable(): use the app's launcher icon instead of the
            // notification's monochrome status-bar drawable for third-party apps.
            Class<?> statusBarIconClass =
                    loader.loadClass("com.android.internal.statusbar.StatusBarIcon");
            Method loadDrawable = iconClass.getDeclaredMethod(
                    "loadDrawable", Context.class, statusBarIconClass);
            loadDrawable.setAccessible(true);
            hook(loadDrawable).setId("aquosr7-third-party-application-icon")
                    .intercept(chain -> {
                        Object view = chain.getThisObject();
                        Object result = chain.proceed();
                        if (!isThirdPartyNotification(view)) return result;
                        java.util.List<?> args = chain.getArgs();
                        if (args == null || args.size() < 2
                                || !(args.get(0) instanceof Context)
                                || args.get(1) == null) return result;
                        String packageName = readStringField(args.get(1), "pkg");
                        if (packageName == null) {
                            StatusBarNotification sbn = getNotification(view);
                            if (sbn != null) packageName = sbn.getPackageName();
                        }
                        if (packageName == null || SYSTEM_UI.equals(packageName)) {
                            return result;
                        }
                        try {
                            return ((Context) args.get(0)).getPackageManager()
                                    .getApplicationIcon(packageName);
                        } catch (PackageManager.NameNotFoundException e) {
                            Log.w(TAG, "No launcher icon for " + packageName, e);
                            return result;
                        }
                    });

            // Reapply the crDroid dark-area behavior after AQUOS updates tint.
            Method onDarkChanged = findMethodByNameAndCount(iconClass,
                    "onDarkChanged", 3);
            if (onDarkChanged != null) {
                onDarkChanged.setAccessible(true);
                hook(onDarkChanged).setId("aquosr7-third-party-dark-area")
                        .intercept(chain -> {
                            Object view = chain.getThisObject();
                            Object result = chain.proceed();
                            if (isThirdPartyNotification(view)) {
                                clearImageTint(view);
                                Object[] args = chain.getArgs();
                                if (args != null && args.size() == 3
                                        && args.get(2) instanceof Integer) {
                                    invokeSingleArg(view, "setDecorColor",
                                            int.class, args.get(2));
                                }
                            }
                            return result;
                        });
            }

            Log.i(TAG, "crDroid behavior installed: launcher drawable, NO_COLOR, "
                    + "clear tint, dark-area handling");
        } catch (Throwable t) {
            Log.e(TAG, "Failed to install crDroid notification icon behavior", t);
        }
    }

    private static boolean isThirdPartyNotification(Object view) {
        StatusBarNotification sbn = getNotification(view);
        return sbn != null && sbn.getPackageName() != null
                && !SYSTEM_UI.equals(sbn.getPackageName());
    }

    private static StatusBarNotification getNotification(Object view) {
        try {
            Field f = findField(view.getClass(), "mNotification");
            if (f == null) return null;
            f.setAccessible(true);
            Object value = f.get(view);
            return value instanceof StatusBarNotification
                    ? (StatusBarNotification) value : null;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static String readStringField(Object target, String name) {
        try {
            Field f = findField(target.getClass(), name);
            if (f == null) return null;
            f.setAccessible(true);
            Object value = f.get(target);
            return value instanceof String ? (String) value : null;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static void clearImageTint(Object view) {
        invokeSingleArg(view, "setColorFilter", ColorFilter.class, null);
        invokeSingleArg(view, "setImageTintList", ColorStateList.class, null);
    }

    private static void invokeSingleArg(Object target, String name,
            Class<?> parameterType, Object value) {
        try {
            Method method = findMethod(target.getClass(), name, parameterType);
            if (method != null) {
                method.setAccessible(true);
                method.invoke(target, value);
            }
        } catch (Throwable t) {
            Log.w(TAG, "Unable to invoke " + name, t);
        }
    }

    private static Method findMethod(Class<?> type, String name, Class<?> parameter) {
        for (Class<?> c = type; c != null; c = c.getSuperclass()) {
            try {
                Method m = c.getDeclaredMethod(name, parameter);
                if (!Modifier.isStatic(m.getModifiers())) return m;
            } catch (NoSuchMethodException ignored) { }
        }
        return null;
    }

    private static Method findMethodByNameAndCount(
            Class<?> type, String name, int count) {
        for (Class<?> c = type; c != null; c = c.getSuperclass()) {
            for (Method m : c.getDeclaredMethods()) {
                if (m.getName().equals(name)
                        && m.getParameterTypes().length == count) return m;
            }
        }
        return null;
    }

    private static Field findField(Class<?> type, String name) {
        for (Class<?> c = type; c != null; c = c.getSuperclass()) {
            try { return c.getDeclaredField(name); }
            catch (NoSuchFieldException ignored) { }
        }
        return null;
    }
}
