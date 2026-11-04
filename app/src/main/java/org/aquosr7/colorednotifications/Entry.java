package org.aquosr7.colorednotifications;

import android.content.pm.PackageManager;
import android.graphics.drawable.Drawable;
import android.content.res.ColorStateList;
import android.widget.ImageView;
import android.service.notification.StatusBarNotification;
import android.util.Log;
import android.view.View;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Collections;
import java.util.Map;
import java.util.WeakHashMap;

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
    private static final String IMS_TINT_COLLECTOR =
            "com.android.systemui.statusbar.pipeline.mobile.ui.binder.MobileIconBinder$bind$1$1$12$1";
    private static final Map<Object, Boolean> NATIVE_IMS_VIEWS =
            Collections.synchronizedMap(new WeakHashMap<>());

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

        // Battery hook is independent: a missing notification hook must not block it.
        installBatteryColorHook(param.getClassLoader());

        try {
            Class<?> iconView = param.getClassLoader().loadClass(ICON_VIEW);
            installApplicationIconHook(iconView);
            installColorPreservationHooks(iconView);
            installNativeImsTintHook(param.getClassLoader());
            Log.i(TAG, "SystemUI hooks initialized");
        } catch (Throwable error) {
            Log.e(TAG, "SystemUI hook initialization failed", error);
        }
    }

    /**
     * Recolors the stock AQUOS R7 battery fill by charge level. The original
     * drawable, charging animation, bolt, dimensions and status-bar layout stay intact.
     */
    private void installBatteryColorHook(ClassLoader loader) {
        try {
            Class<?> drawable = loader.loadClass(
                    "com.android.settingslib.graph.ThemedBatteryDrawable");
            Method method = findMethod(drawable, "batteryColorForLevel", int.class);
            if (method == null) {
                throw new NoSuchMethodException(
                        "ThemedBatteryDrawable.batteryColorForLevel(int)");
            }
            if (method.getReturnType() != int.class) {
                throw new NoSuchMethodException(
                        "batteryColorForLevel(int) does not return int");
            }

            hook(method).setId("aquosr7-vector-battery-level-color").intercept(chain -> {
                Object rawLevel = chain.getArg(0);
                if (!(rawLevel instanceof Integer)) return chain.proceed();

                int level = Math.max(0, Math.min(100, (Integer) rawLevel));
                int color = batteryColorForLevel(level);
                Log.d(TAG, "BATTERY_COLOR_HOOK level=" + level
                        + " color=#" + Integer.toHexString(color));
                return color;
            });
            Log.i(TAG, "BATTERY_COLOR_HOOK_INSTALLED "
                    + "ThemedBatteryDrawable.batteryColorForLevel(int)");
        } catch (Throwable error) {
            Log.e(TAG, "BATTERY_COLOR_HOOK_FAILED", error);
        }
    }

    private static int batteryColorForLevel(int level) {
        if (level >= 76) return 0xFF34C759; // green
        if (level >= 51) return 0xFFFFD60A; // yellow
        if (level >= 21) return 0xFFFF9500; // orange
        return 0xFFFF3B30;                  // red
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
                if (isNativeImsIcon(target)) {
                    // All four Sharp IMS tint entry points are intercepted
                    // before their original implementations can recolor the drawable.
                    return null;
                }
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


    /**
     * Sharp's collector receives four ImageViews: icon, network type, roaming,
     * then the dedicated $volteView. The latter displays the selected native
     * VoLTE/VoWiFi resource.
     */
    private void installNativeImsTintHook(ClassLoader loader) {
        try {
            Class<?> collector = loader.loadClass(IMS_TINT_COLLECTOR);
            Class<?> statusBarIconView = loader.loadClass(ICON_VIEW);
            Constructor<?> constructor = collector.getDeclaredConstructor(
                    ImageView.class, ImageView.class, ImageView.class, ImageView.class,
                    statusBarIconView);
            hook(constructor).setId("aquosr7-register-native-ims-view").intercept(chain -> {
                Object result = chain.proceed();
                Object imsView = chain.getArg(3);
                if (imsView instanceof ImageView) {
                    NATIVE_IMS_VIEWS.put(imsView, Boolean.TRUE);
                    Log.i(TAG, "NATIVE_IMS_VIEW_REGISTERED view="
                            + Integer.toHexString(System.identityHashCode(imsView)));
                }
                return result;
            });

            Method setTint = ImageView.class.getDeclaredMethod(
                    "setImageTintList", ColorStateList.class);
            hook(setTint).setId("aquosr7-preserve-native-ims-drawable-colors")
                    .intercept(chain -> {
                        if (NATIVE_IMS_VIEWS.containsKey(chain.getThisObject())) {
                            Log.i(TAG, "NATIVE_IMS_TINT_BLOCKED");
                            return null;
                        }
                        return chain.proceed();
                    });
            Log.i(TAG, "Installed native IMS ImageView tint hook");
        } catch (Throwable error) {
            Log.e(TAG, "Cannot install native IMS tint hook", error);
        }
    }

    /**
     * Identify Sharp's native IMS status icons from the actual StatusBarIcon
     * resource, not from guessed slot names. The firmware's selected drawable
     * is preserved (including tw1/tw2 SIM selection); only tint mutations are
     * skipped for these four known resource entry names.
     */
    private static boolean isNativeImsIcon(Object target) {
        if (!(target instanceof View)) return false;
        String resourceName = null;
        String resourcePackage = null;
        try {
            Field iconField = findField(target.getClass(), "mIcon");
            if (iconField == null) return false;
            iconField.setAccessible(true);
            Object statusBarIcon = iconField.get(target);
            if (statusBarIcon == null) return false;

            Field drawableField = findField(statusBarIcon.getClass(), "icon");
            if (drawableField == null) return false;
            drawableField.setAccessible(true);
            Object icon = drawableField.get(statusBarIcon);
            if (icon == null) return false;

            // StatusBarIconView also handles app notification icons. Most are
            // bitmaps/adaptive icons, for which getResId() throws. Check type
            // first and ignore every non-resource icon without logging noise.
            Method getType = findMethod(icon.getClass(), "getType");
            if (getType == null) return false;
            Object typeValue = getType.invoke(icon);
            if (!(typeValue instanceof Integer)
                    || ((Integer) typeValue) != android.graphics.drawable.Icon.TYPE_RESOURCE) {
                return false;
            }

            Method getResPackage = findMethod(icon.getClass(), "getResPackage");
            if (getResPackage == null) return false;
            Object packageValue = getResPackage.invoke(icon);
            if (!(packageValue instanceof String)) return false;
            resourcePackage = (String) packageValue;

            // The four target drawables belong to SystemUI. Do not attempt to
            // resolve foreign application resource IDs in SystemUI Resources.
            if (!SYSTEM_UI.equals(resourcePackage)) return false;

            Method getResId = findMethod(icon.getClass(), "getResId");
            if (getResId == null) return false;
            Object value = getResId.invoke(icon);
            if (!(value instanceof Integer) || ((Integer) value) == 0) return false;

            View view = (View) target;
            android.content.res.Resources ownerResources = view.getContext()
                    .getPackageManager().getResourcesForApplication(SYSTEM_UI);
            resourceName = ownerResources.getResourceName((Integer) value);

            boolean match = resourceName.equals("com.android.systemui:drawable/ic_volte_tw")
                    || resourceName.equals("com.android.systemui:drawable/ic_vowifi_tw")
                    || resourceName.equals("com.android.systemui:drawable/ic_vowifi_tw1")
                    || resourceName.equals("com.android.systemui:drawable/ic_vowifi_tw2");

            if (match) {
                Log.i(TAG, "IMS_NATIVE_ICON_MATCH resource=" + resourceName
                        + " iconPackage=" + resourcePackage);
            }
            return match;
        } catch (Throwable error) {
            Log.w(TAG, "IMS_CHECK failed resource=" + resourceName
                    + " package=" + resourcePackage
                    + " error=" + error.getClass().getSimpleName());
            return false;
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
