package org.aquosr7.colorednotifications;

import android.content.res.ColorStateList;
import android.graphics.ColorFilter;
import android.service.notification.StatusBarNotification;
import android.util.Log;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

import io.github.libxposed.api.XposedModule;
import io.github.libxposed.api.XposedModuleInterface.ModuleLoadedParam;
import io.github.libxposed.api.XposedModuleInterface.PackageLoadedParam;
import io.github.libxposed.api.XposedModuleInterface.PackageReadyParam;

/**
 * AQUOS R7 notification icon tint bypass.
 * Hooks are installed independently to tolerate ROM-specific method differences.
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

        final Class<?> iconClass;
        try {
            iconClass = param.getClassLoader().loadClass(ICON_VIEW);
        } catch (Throwable t) {
            Log.e(TAG, "StatusBarIconView class not found", t);
            return;
        }

        installStaticColorHook(iconClass);
        installUpdateColorHook(iconClass);
        installContrastedColorHook(iconClass);
        Log.i(TAG, "Hook initialization finished");
    }

    private void installStaticColorHook(Class<?> iconClass) {
        try {
            Field field = findField(iconClass, "NO_COLOR");
            if (field == null) throw new NoSuchFieldException("NO_COLOR");
            field.setAccessible(true);
            final int noColor = field.getInt(null);

            Method method = findMethod(iconClass, "setStaticDrawableColor", int.class);
            if (method == null) throw new NoSuchMethodException("setStaticDrawableColor(int)");
            method.setAccessible(true);

            hook(method).setId("aquosr7-third-party-no-color")
                    .intercept(chain -> {
                        if (isThirdParty(chain.getThisObject())) {
                            return chain.proceed(new Object[]{noColor});
                        }
                        return chain.proceed();
                    });
            Log.i(TAG, "Installed setStaticDrawableColor hook");
        } catch (Throwable t) {
            Log.e(TAG, "Could not install setStaticDrawableColor hook", t);
        }
    }

    private void installUpdateColorHook(Class<?> iconClass) {
        installPostColorClearHook(iconClass, "updateIconColor",
                "aquosr7-third-party-clear-icon-color");
    }

    private void installContrastedColorHook(Class<?> iconClass) {
        installPostColorClearHook(iconClass, "updateContrastedStaticColor",
                "aquosr7-third-party-clear-contrasted-color");
    }

    private void installPostColorClearHook(Class<?> iconClass, String methodName, String hookId) {
        try {
            Method method = findMethod(iconClass, methodName);
            if (method == null) throw new NoSuchMethodException(methodName + "()");
            method.setAccessible(true);

            hook(method).setId(hookId).intercept(chain -> {
                Object view = chain.getThisObject();
                Object result = chain.proceed();
                if (isThirdParty(view)) clearImageTint(view);
                return result;
            });
            Log.i(TAG, "Installed " + methodName + " hook");
        } catch (Throwable t) {
            Log.e(TAG, "Could not install " + methodName + " hook", t);
        }
    }

    private static boolean isThirdParty(Object view) {
        StatusBarNotification sbn = getNotification(view);
        return sbn != null && sbn.getPackageName() != null
                && !SYSTEM_UI.equals(sbn.getPackageName());
    }

    private static StatusBarNotification getNotification(Object view) {
        if (view == null) return null;
        try {
            Field f = findField(view.getClass(), "mNotification");
            if (f == null) return null;
            f.setAccessible(true);
            Object value = f.get(view);
            return value instanceof StatusBarNotification
                    ? (StatusBarNotification) value : null;
        } catch (Throwable t) {
            Log.w(TAG, "Unable to read mNotification", t);
            return null;
        }
    }

    private static void clearImageTint(Object view) {
        invokeSingleArg(view, "setColorFilter", ColorFilter.class, null);
        invokeSingleArg(view, "setImageTintList", ColorStateList.class, null);
    }

    private static void invokeSingleArg(
            Object target, String name, Class<?> parameterType, Object value) {
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

    private static Method findMethod(Class<?> type, String name, Class<?>... parameters) {
        for (Class<?> c = type; c != null; c = c.getSuperclass()) {
            try {
                return c.getDeclaredMethod(name, parameters);
            } catch (NoSuchMethodException ignored) { }
        }
        return null;
    }

    private static Field findField(Class<?> type, String name) {
        for (Class<?> c = type; c != null; c = c.getSuperclass()) {
            try {
                return c.getDeclaredField(name);
            } catch (NoSuchFieldException ignored) { }
        }
        return null;
    }
}
