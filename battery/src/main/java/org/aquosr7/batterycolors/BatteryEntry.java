package org.aquosr7.batterycolors;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
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
    private static final Map<Object, Boolean> DUAL_TONE_READY_LOGGED =
            Collections.synchronizedMap(new WeakHashMap<>());
    private static final ThreadLocal<DrawContext> ACTIVE_DRAW =
            new ThreadLocal<>();

    private static final class DrawContext {
        final Object drawable;
        final Path unifiedPath;
        final Paint fillPaint;
        final boolean dualTone;
        final int level;

        DrawContext(Object drawable, Path unifiedPath, Paint fillPaint,
                    boolean dualTone, int level) {
            this.drawable = drawable;
            this.unifiedPath = unifiedPath;
            this.fillPaint = fillPaint;
            this.dualTone = dualTone;
            this.level = level;
        }
    }

    @Override
    public void onModuleLoaded(ModuleLoadedParam param) {
        Log.i(TAG, "MODULE_LOADED process=" + param.getProcessName()
                + " systemServer=" + param.isSystemServer());
    }

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

            int installed = 0;
            Method setLevel = findMethod(drawable, "setBatteryLevel", int.class);
            if (setLevel != null) {
                hook(setLevel).setId("aquosr7-battery-fill-level-v104").intercept(chain -> {
                    Object result = chain.proceed();
                    applyBatteryFill(chain.getThisObject());
                    return result;
                });
                installed++;
                Log.i(TAG, "HOOK_INSTALLED method=setBatteryLevel(int)");
            } else {
                Log.w(TAG, "HOOK_METHOD_MISSING method=setBatteryLevel(int)");
            }

            Method setColors = findMethod(drawable, "setColors",
                    int.class, int.class, int.class);
            if (setColors != null) {
                hook(setColors).setId("aquosr7-battery-fill-colors-v104").intercept(chain -> {
                    Object result = chain.proceed();
                    applyBatteryFill(chain.getThisObject());
                    return result;
                });
                installed++;
                Log.i(TAG, "HOOK_INSTALLED method=setColors(int,int,int)");
            } else {
                Log.w(TAG, "HOOK_METHOD_MISSING method=setColors(int,int,int)");
            }

            Method draw = findMethod(drawable, "draw", Canvas.class);
            if (draw != null) {
                hook(draw).setId("aquosr7-battery-dualtone-draw-context-v104")
                        .intercept(chain -> {
                            Object target = chain.getThisObject();
                            DrawContext previous = ACTIVE_DRAW.get();
                            ACTIVE_DRAW.set(createDrawContext(target));
                            try {
                                return chain.proceed();
                            } finally {
                                if (previous == null) ACTIVE_DRAW.remove();
                                else ACTIVE_DRAW.set(previous);
                            }
                        });
                installed++;
                Log.i(TAG, "HOOK_INSTALLED method=draw(Canvas)");
            } else {
                Log.w(TAG, "HOOK_METHOD_MISSING method=draw(Canvas)");
            }

            installCanvasFillHook();
            installed++;

            if (installed < 2) {
                throw new NoSuchMethodException("Insufficient battery hooks installed");
            }

            HOOKED_CLASSLOADERS.put(classLoader, Boolean.TRUE);
            Log.i(TAG, "BATTERY_FILL_HOOKS_INSTALLED count=" + installed
                    + " class=" + drawable.getName() + " phase=" + phase);
        } catch (Throwable error) {
            Log.e(TAG, "BATTERY_FILL_HOOKS_FAILED phase=" + phase, error);
        }
    }

    private void installCanvasFillHook() throws Exception {
        Method drawPath = Canvas.class.getDeclaredMethod("drawPath", Path.class, Paint.class);
        hook(drawPath).setId("aquosr7-battery-dualtone-canvas-fill-v104")
                .intercept(chain -> {
                    DrawContext context = ACTIVE_DRAW.get();
                    if (context == null || !context.dualTone
                            || chain.getArg(0) != context.unifiedPath
                            || chain.getArg(1) != context.fillPaint) {
                        return chain.proceed();
                    }

                    Paint paint = context.fillPaint;
                    int originalColor = paint.getColor();
                    int batteryColor = colorForLevel(context.level);
                    paint.setColor(batteryColor);
                    try {
                        Object result = chain.proceed();
                        if (DUAL_TONE_READY_LOGGED.put(
                                context.drawable, Boolean.TRUE) == null) {
                            Log.i(TAG, "DUAL_TONE_BODY_DRAW_RECOLORED level="
                                    + context.level + " color="
                                    + String.format(java.util.Locale.ROOT,
                                    "#%08X", batteryColor)
                                    + " bolt=unchanged");
                        }
                        return result;
                    } finally {
                        paint.setColor(originalColor);
                    }
                });
        Log.i(TAG, "HOOK_INSTALLED method=Canvas.drawPath(Path,Paint)");
    }

    private static DrawContext createDrawContext(Object drawable) {
        if (drawable == null) return null;
        try {
            Class<?> type = drawable.getClass();
            Field dualToneField = findField(type, "dualTone");
            Field levelField = findField(type, "batteryLevel");
            Field unifiedPathField = findField(type, "unifiedPath");
            Field fillPaintField = findField(type, "fillPaint");

            if (dualToneField == null || levelField == null
                    || unifiedPathField == null || fillPaintField == null) {
                Log.e(TAG, "DUAL_TONE_DRAW_CONTEXT_FIELDS_MISSING"
                        + " dualTone=" + (dualToneField != null)
                        + " batteryLevel=" + (levelField != null)
                        + " unifiedPath=" + (unifiedPathField != null)
                        + " fillPaint=" + (fillPaintField != null));
                return null;
            }

            dualToneField.setAccessible(true);
            levelField.setAccessible(true);
            unifiedPathField.setAccessible(true);
            fillPaintField.setAccessible(true);

            boolean dualTone = dualToneField.getBoolean(drawable);
            int level = Math.max(0, Math.min(100, levelField.getInt(drawable)));
            Object path = unifiedPathField.get(drawable);
            Object paint = fillPaintField.get(drawable);
            if (!(path instanceof Path) || !(paint instanceof Paint)) return null;

            return new DrawContext(drawable, (Path) path, (Paint) paint,
                    dualTone, level);
        } catch (Throwable error) {
            Log.e(TAG, "DUAL_TONE_DRAW_CONTEXT_FAILED", error);
            return null;
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

            if (levelField == null) {
                throw new NoSuchFieldException("batteryLevel field missing");
            }
            levelField.setAccessible(true);
            int level = Math.max(0, Math.min(100, levelField.getInt(drawable)));
            int color = colorForLevel(level);

            boolean dualTone = false;
            if (dualToneField != null) {
                dualToneField.setAccessible(true);
                dualTone = dualToneField.getBoolean(drawable);
            }

            if (dualTone) {
                if (DUAL_TONE_READY_LOGGED.put(drawable, Boolean.TRUE) == null) {
                    Log.i(TAG, "BATTERY_FILL_DUAL_TONE_DRAW_HOOK_READY level="
                            + level + " color="
                            + String.format(java.util.Locale.ROOT, "#%08X", color));
                }
                return;
            }

            if (fillColorField == null || fillPaintField == null) {
                throw new NoSuchFieldException("Non-dual-tone fill fields missing"
                        + " fillColor=" + (fillColorField != null)
                        + " fillPaint=" + (fillPaintField != null));
            }

            fillColorField.setAccessible(true);
            fillPaintField.setAccessible(true);
            fillColorField.setInt(drawable, color);

            Object paint = fillPaintField.get(drawable);
            if (!(paint instanceof Paint)) {
                throw new IllegalStateException("fillPaint is not android.graphics.Paint");
            }
            ((Paint) paint).setColor(color);

            Integer previous = LAST_LOGGED_LEVEL.put(drawable, level);
            if (previous == null || previous.intValue() != level) {
                Log.i(TAG, "BATTERY_FILL_APPLIED level=" + level
                        + " color=" + String.format(java.util.Locale.ROOT, "#%08X", color));
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
