package org.aquosr7.batterycolors;

import android.util.Log;

import java.lang.reflect.Method;

import io.github.libxposed.api.XposedModule;
import io.github.libxposed.api.XposedModuleInterface.ModuleLoadedParam;
import io.github.libxposed.api.XposedModuleInterface.PackageReadyParam;

/**
 * AQUOS R7 battery fill-color hook. Build validation is run by battery-build.yml.
 *
 * Only replaces the integer returned by ThemedBatteryDrawable's level-color
 * calculation. It does not replace drawables, animation state, charging bolt,
 * SystemUI resources, or APK files.
 */
public final class BatteryEntry extends XposedModule {
    private static final String TAG = "AQUOSR7BatteryColors";
    private static final String SYSTEM_UI = "com.android.systemui";
    private static final String DRAWABLE_CLASS =
            "com.android.settingslib.graph.ThemedBatteryDrawable";

    private static final int GREEN = 0xFF34C759;
    private static final int YELLOW = 0xFFFFD60A;
    private static final int ORANGE = 0xFFFF9500;
    private static final int RED = 0xFFFF3B30;

    @Override
    public void onModuleLoaded(ModuleLoadedParam param) {
        Log.i(TAG, "MODULE_LOADED process=" + param.getProcessName());
    }

    @Override
    public void onPackageReady(PackageReadyParam param) {
        if (!SYSTEM_UI.equals(param.getPackageName())) return;

        try {
            Class<?> drawable = param.getClassLoader().loadClass(DRAWABLE_CLASS);
            Method method = drawable.getDeclaredMethod("batteryColorForLevel", int.class);
            hook(method).setId("aquosr7-battery-level-color").intercept(chain -> {
                Object rawLevel = chain.getArg(0);
                if (!(rawLevel instanceof Integer)) return chain.proceed();

                int level = Math.max(0, Math.min(100, (Integer) rawLevel));
                int color = colorForLevel(level);
                Log.d(TAG, "BATTERY_COLOR level=" + level
                        + " color=#" + Integer.toHexString(color));
                return color;
            });
            Log.i(TAG, "Installed ThemedBatteryDrawable.batteryColorForLevel(int)");
        } catch (Throwable error) {
            Log.e(TAG, "Battery color hook installation failed", error);
        }
    }

    static int colorForLevel(int level) {
        if (level >= 76) return GREEN;
        if (level >= 51) return YELLOW;
        if (level >= 21) return ORANGE;
        return RED;
    }
}
