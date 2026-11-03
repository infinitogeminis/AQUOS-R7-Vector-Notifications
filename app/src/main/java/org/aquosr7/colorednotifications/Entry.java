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
            if (iconField == null) {
                Log.w(TAG, "IMS_CHECK: StatusBarIconView.mIcon field not found");
                return false;
            }
            iconField.setAccessible(true);
            Object statusBarIcon = iconField.get(target);
            if (statusBarIcon == null) return false;

            Field drawableField = findField(statusBarIcon.getClass(), "icon");
            if (drawableField == null) {
                Log.w(TAG, "IMS_CHECK: StatusBarIcon.icon field not found");
                return false;
            }
            drawableField.setAccessible(true);
            Object icon = drawableField.get(statusBarIcon);
            if (icon == null) return false;

            Method getResId = findMethod(icon.getClass(), "getResId");
            if (getResId == null) {
                Log.w(TAG, "IMS_CHECK: Icon.getResId() not found; iconClass="
                        + icon.getClass().getName());
                return false;
            }
            Object value = getResId.invoke(icon);
            if (!(value instanceof Integer) || ((Integer) value) == 0) return false;

            Method getResPackage = findMethod(icon.getClass(), "getResPackage");
            if (getResPackage != null) {
                Object packageValue = getResPackage.invoke(icon);
                if (packageValue instanceof String) {
                    resourcePackage = (String) packageValue;
                }
            }

            View view = (View) target;
            try {
                resourceName = view.getResources().getResourceName((Integer) value);
            } catch (Throwable lookupError) {
                Log.w(TAG, "IMS_CHECK: resource ID cannot be resolved by SystemUI Resources; id=0x"
                        + Integer.toHexString((Integer) value)
                        + " package=" + resourcePackage, lookupError);
                return false;
            }

            boolean systemUiResource = resourceName.startsWith("com.android.systemui:");
            boolean expectedPackage = resourcePackage == null
                    || SYSTEM_UI.equals(resourcePackage);
            boolean match = systemUiResource && expectedPackage
                    && (resourceName.endsWith(":drawable/ic_volte_tw")
                    || resourceName.endsWith(":drawable/ic_vowifi_tw")
                    || resourceName.endsWith(":drawable/ic_vowifi_tw1")
                    || resourceName.endsWith(":drawable/ic_vowifi_tw2"));

            if (match) {
                Log.i(TAG, "IMS_NATIVE_ICON_MATCH resource=" + resourceName
                        + " iconPackage=" + resourcePackage);
            }
            return match;
        } catch (Throwable error) {
            Log.w(TAG, "IMS_CHECK failed resource=" + resourceName
                    + " package=" + resourcePackage, error);
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
