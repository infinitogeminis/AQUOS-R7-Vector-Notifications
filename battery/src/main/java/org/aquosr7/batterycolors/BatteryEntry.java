package org.aquosr7.batterycolors;

import android.util.Log;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Collections;
import java.util.Map;
import java.util.WeakHashMap;

import io.github.libxposed.api.XposedModule;
import io.github.libxposed.api.XposedModuleInterface.ModuleLoadedParam;
import io.github.libxposed.api.XposedModuleInterface.PackageReadyParam;

/**
 * Separate Vector plugin for AQUOS R7 battery fill colors.
 *
 * Changes only ThemedBatteryDrawable.fillColor and fillPaint in the
 * non-dual-tone draw path. It deliberately does not touch levelColor
 * (charging bolt), outline paint, drawable geometry, or animation state.
 */
public final class BatteryEntry extends XposedModule {
    private static final String TAG = "AQUOSR7BatteryColors";
    private static final String SYSTEM_UI = "com.android.systemui";
    private static final String DRAWABLE_CLASS =
            "com.android.settingslib.graph.ThemedBatteryDrawable";

    private static final Map<Object, Integer> LAST_LOGGED_LEVEL =
            Collections.synchronizedMap(new WeakHashMap<>());
    private static final Map<Object, Boolean> DUAL_TONE_WARNED =
            Collections.synchronizedMap(new WeakHashMap<>());

    @Override
    public void onModuleLoaded(ModuleLoadedParam param) {
        Log.i(TAG, "MODULE_LOADED process=" + param.getProcessName());
    }

    @Override
    public void onPackageReady(PackageReadyParam param) {
        if (!SYSTEM_UI.equals(param.getPackageName())) return;

        try {
            Class<?> drawable = param.getClassLoader().loadClass(DRAWABLE_CLASS);
            Method setLevel = findMethod(drawable, "setBatteryLevel", int.class);
            Method setColors = findMethod(drawable, "setColors",
                    int.class, int.class, int.class);

            if (setLevel == null || setColors == null) {
                throw new NoSuchMethodException(
                        "ThemedBatteryDrawable.setBatteryLevel/setColors");
            }

            hook(setLevel).setId("aquosr7-battery-fill-level").intercept(chain -> {
                Object result = chain.proceed();
                applyBatteryFill(chain.getThisObject());
                return result;
            });

            hook(setColors).setId("aquosr7-battery-fill-colors").intercept(chain -> {
                Object result = chain.proceed();
                applyBatteryFill(chain.getThisObject());
                return result;
            });

            Log.i(TAG, "BATTERY_FILL_HOOKS_INSTALLED class=" + drawable.getName()
                    + " methods=setBatteryLevel(int),setColors(int,int,int)");
        } catch (Throwable error) {
            Log.e(TAG, "BATTERY_FILL_HOOKS_FAILED", error);
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
                        "Expected ThemedBatteryDrawable fill fields are unavailable");
            }

            dualToneField.setAccessible(true);
            levelField.setAccessible(true);
            fillColorField.setAccessible(true);
            fillPaintField.setAccessible(true);

            // In dual-tone mode the stock renderer uses levelColor for the
            // clipped body fill and the charging bolt. Do not recolor the bolt.
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
