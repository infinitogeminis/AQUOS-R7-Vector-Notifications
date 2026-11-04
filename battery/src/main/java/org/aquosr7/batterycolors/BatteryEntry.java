package org.aquosr7.batterycolors;

import android.util.Log;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Collections;
import java.util.Map;
import java.util.WeakHashMap;

import io.github.libxposed.api.XposedModule;
import io.github.libxposed.api.XposedModuleInterface.ModuleLoadedParam;
import io.github.libxposed.api.XposedModuleInterface.PackageLoadedParam;
import io.github.libxposed.api.XposedModuleInterface.PackageReadyParam;

/** Standalone Vector plugin for AQUOS R7 battery fill colors. */
public final class BatteryEntry extends XposedModule {
    private static final String TAG = "AQUOSR7BatteryColors";
    private static final String SYSTEM_UI = "com.android.systemui";
    private static final String DRAWABLE_CLASS =
            "com.android.settingslib.graph.ThemedBatteryDrawable";

    private static final Map<ClassLoader, Boolean> HOOKED_CLASSLOADERS =
            Collections.synchronizedMap(new WeakHashMap<>());
    private static final Map<Object, Integer> LAST_LOGGED_LEVEL =
            Collections.synchronizedMap(new WeakHashMap<>());
    private static final Map<Object, Boolean> DUAL_TONE_WARNED =
            Collections.synchronizedMap(new WeakHashMap<>());

    @Override
    public void onModuleLoaded(ModuleLoadedParam param) {
        Log.i(TAG, "MODULE_LOADED process=" + param.getProcessName()
                + " systemServer=" + param.isSystemServer());
    }

    /**
     * Install at the earliest package lifecycle stage. This avoids relying
     * solely on AppComponentFactory's later PackageReady callback in SystemUI.
     */
    @Override
    public void onPackageLoaded(PackageLoadedParam param) {
        String packageName = param.getPackageName();
        Log.i(TAG, "PACKAGE_LOADED package=" + packageName
                + " first=" + param.isFirstPackage());
        if (!SYSTEM_UI.equals(packageName)) return;

        ClassLoader loader = param.getDefaultClassLoader();
        Log.i(TAG, "SYSTEMUI_CLASSLOADER source=package_loaded loader="
                + loaderIdentity(loader));
        installHooks(loader, "package_loaded");
    }

    /** Fallback for frameworks/packages that provide a distinct active loader. */
    @Override
    public void onPackageReady(PackageReadyParam param) {
        String packageName = param.getPackageName();
        Log.i(TAG, "PACKAGE_READY package=" + packageName
                + " first=" + param.isFirstPackage());
        if (!SYSTEM_UI.equals(packageName)) return;

        ClassLoader loader = param.getClassLoader();
        Log.i(TAG, "SYSTEMUI_CLASSLOADER source=package_ready loader="
                + loaderIdentity(loader));
        installHooks(loader, "package_ready");
    }

    private static String loaderIdentity(ClassLoader loader) {
        return loader == null ? "null"
                : loader.getClass().getName() + "@"
                + Integer.toHexString(System.identityHashCode(loader));
    }

    private synchronized void installHooks(ClassLoader classLoader, String phase) {
        if (classLoader == null) {
            Log.e(TAG, "SYSTEMUI_CLASSLOADER_NULL phase=" + phase);
            return;
        }
        if (HOOKED_CLASSLOADERS.containsKey(classLoader)) {
            Log.i(TAG, "HOOKS_ALREADY_INSTALLED phase=" + phase);
            return;
        }

        try {
            Class<?> drawable = classLoader.loadClass(DRAWABLE_CLASS);
            Log.i(TAG, "DRAWABLE_CLASS_FOUND name=" + drawable.getName()
                    + " phase=" + phase);

            Method setLevel = findMethod(drawable, "setBatteryLevel", int.class);
            Method setColors = findMethod(drawable, "setColors",
                    int.class, int.class, int.class);

            // Do not let one absent overload prevent installation of the other.
            int installed = 0;
            if (setLevel != null) {
                hook(setLevel).setId("aquosr7-battery-fill-level").intercept(chain -> {
                    Object result = chain.proceed();
                    applyBatteryFill(chain.getThisObject());
                    return result;
                });
                installed++;
                Log.i(TAG, "HOOK_INSTALLED method=setBatteryLevel(int)");
            } else {
                Log.w(TAG, "HOOK_METHOD_MISSING method=setBatteryLevel(int)");
            }

            if (setColors != null) {
                hook(setColors).setId("aquosr7-battery-fill-colors").intercept(chain -> {
                    Object result = chain.proceed();
                    applyBatteryFill(chain.getThisObject());
                    return result;
                });
                installed++;
                Log.i(TAG, "HOOK_INSTALLED method=setColors(int,int,int)");
            } else {
                Log.w(TAG, "HOOK_METHOD_MISSING method=setColors(int,int,int)");
            }

            if (installed == 0) {
                throw new NoSuchMethodException(
                        "No supported battery drawable update methods found");
            }

            HOOKED_CLASSLOADERS.put(classLoader, Boolean.TRUE);
            Log.i(TAG, "BATTERY_FILL_HOOKS_INSTALLED count=" + installed
                    + " class=" + drawable.getName() + " phase=" + phase);
        } catch (Throwable error) {
            Log.e(TAG, "BATTERY_FILL_HOOKS_FAILED phase=" + phase, error);
        }
    }

    private static void applyBatteryFill(Object drawable) {
        if (drawable == null) return;

        try {
            Class<?> type = drawable.getClass();
            Field dualToneField = findField(type, "dualTone");
            Field levelField = findField(type, "batteryLevel");
            Field fillColorField = findField(type, "fillColor");
            Field fillPaintField = findField(type, "fillPaint");

            if (dualToneField == null || levelField == null
                    || fillColorField == null || fillPaintField == null) {
                throw new NoSuchFieldException(
                        "Expected fill fields unavailable"
                                + " dualTone=" + (dualToneField != null)
                                + " batteryLevel=" + (levelField != null)
                                + " fillColor=" + (fillColorField != null)
                                + " fillPaint=" + (fillPaintField != null));
            }

            dualToneField.setAccessible(true);
            levelField.setAccessible(true);
            fillColorField.setAccessible(true);
            fillPaintField.setAccessible(true);

            if (dualToneField.getBoolean(drawable)) {
                if (DUAL_TONE_WARNED.put(drawable, Boolean.TRUE) == null) {
                    Log.w(TAG, "BATTERY_FILL_DUAL_TONE_UNSUPPORTED_SAFE_SKIP");
                }
                return;
            }

            int level = Math.max(0, Math.min(100, levelField.getInt(drawable)));
            int color = colorForLevel(level);
            fillColorField.setInt(drawable, color);

            Object paint = fillPaintField.get(drawable);
            if (paint == null) throw new IllegalStateException("fillPaint is null");

            Method setColor = findMethod(paint.getClass(), "setColor", int.class);
            if (setColor == null) throw new NoSuchMethodException("Paint.setColor(int)");
            setColor.setAccessible(true);
            setColor.invoke(paint, color);

            Integer previous = LAST_LOGGED_LEVEL.put(drawable, level);
            if (previous == null || previous.intValue() != level) {
                Log.i(TAG, "BATTERY_FILL_APPLIED level=" + level
                        + " color=#" + String.format(java.util.Locale.ROOT, "%08X", color));
            }
        } catch (Throwable error) {
            Log.e(TAG, "BATTERY_FILL_APPLY_FAILED", error);
        }
    }

    static int colorForLevel(int level) {
        int clamped = Math.max(0, Math.min(100, level));
        if (clamped >= 76) return 0xFF34C759;
        if (clamped >= 51) return 0xFFFFD60A;
        if (clamped >= 21) return 0xFFFF9500;
        return 0xFFFF3B30;
    }

    private static Method findMethod(Class<?> type, String name, Class<?>... parameters) {
        for (Class<?> current = type; current != null; current = current.getSuperclass()) {
            try {
                return current.getDeclaredMethod(name, parameters);
            } catch (NoSuchMethodException ignored) {
                // Continue through the superclass chain.
            }
        }
        return null;
    }

    private static Field findField(Class<?> type, String name) {
        for (Class<?> current = type; current != null; current = current.getSuperclass()) {
            try {
                return current.getDeclaredField(name);
            } catch (NoSuchFieldException ignored) {
                // Continue through the superclass chain.
            }
        }
        return null;
    }
}
