package org.aquosr7.colorednotifications;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.drawable.Drawable;
import android.util.Log;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

import io.github.libxposed.api.XposedModule;
import io.github.libxposed.api.XposedModuleInterface.PackageReadyParam;

/**
 * crDroid-style original application icons for AQUOS R7 SystemUI.
 *
 * Replaces the notification small-icon drawable with the notifying
 * application's launcher icon and disables SystemUI tint for that icon.
 * SystemUI-owned icons keep their stock drawable and tint.
 */
public final class Entry extends XposedModule {
    private static final String TAG = "AQUOSR7ColoredNotifications";
    private static final String SYSTEM_UI = "com.android.systemui";
    private static final String ICON_VIEW =
            "com.android.systemui.statusbar.StatusBarIconView";
    private static final String STATUS_BAR_ICON =
            "com.android.internal.statusbar.StatusBarIcon";

    @Override
    public void onPackageReady(PackageReadyParam param) {
        if (!SYSTEM_UI.equals(param.getPackageName())) return;

        final ClassLoader loader = param.getClassLoader();
        try {
            Class<?> iconClass = loader.loadClass(ICON_VIEW);
            installOriginalApplicationIconHook(iconClass, loader);
            installNoTintHooks(iconClass);
            Log.i(TAG, "crDroid-style original app icon hooks initialized");
        } catch (Throwable t) {
            Log.e(TAG, "Hook initialization failed", t);
        }
    }

    /**
     * Mirrors crDroid 8 StatusBarIconView.getIcon(): use the application's
     * launcher icon instead of StatusBarIcon.icon for non-SystemUI packages.
     * The caller's existing ScalingDrawableWrapper remains in effect because
     * this hook replaces only the return value of getIcon().
     */
    private void installOriginalApplicationIconHook(Class<?> iconClass,
            ClassLoader loader) {
        try {
            Class<?> statusBarIconClass = loader.loadClass(STATUS_BAR_ICON);
            Method method = findMethod(iconClass, "getIcon",
                    Context.class, Context.class, statusBarIconClass);
            if (method == null) throw new NoSuchMethodException(
                    "getIcon(Context, Context, StatusBarIcon)");
            method.setAccessible(true);

            hook(method).setId("aquosr7-crdroid-original-app-icon")
                    .intercept(chain -> {
                        java.util.List<?> args = chain.getArgs();
                        if (args == null || args.size() < 3
                                || !(args.get(1) instanceof Context)) {
                            return chain.proceed();
                        }

                        String packageName = readPackageName(args.get(2));
                        if (packageName == null || isSystemUiPackage(packageName)) {
                            return chain.proceed();
                        }

                        try {
                            Context packageContext = (Context) args.get(1);
                            Drawable appIcon = packageContext.getPackageManager()
                                    .getApplicationIcon(packageName);
                            if (appIcon != null) {
                                Log.d(TAG, "Using launcher icon for " + packageName);
                                return appIcon;
                            }
                        } catch (Throwable t) {
                            Log.w(TAG, "Launcher icon unavailable for "
                                    + packageName + "; using notification icon", t);
                        }
                        return chain.proceed();
                    });
            Log.i(TAG, "Installed original application icon hook");
        } catch (Throwable t) {
            Log.e(TAG, "Could not install original application icon hook", t);
        }
    }

    /** Match crDroid's NO_COLOR handling in NotificationIconAreaController
     * and NotificationIconContainer.
     */
    private void installNoTintHooks(Class<?> iconClass) {
        try {
            Field noColorField = findField(iconClass, "NO_COLOR");
            if (noColorField == null) throw new NoSuchFieldException("NO_COLOR");
            noColorField.setAccessible(true);
            final int noColor = noColorField.getInt(null);

            Method staticColor = findMethod(iconClass,
                    "setStaticDrawableColor", int.class);
            if (staticColor == null) throw new NoSuchMethodException(
                    "setStaticDrawableColor(int)");
            staticColor.setAccessible(true);
            hook(staticColor).setId("aquosr7-crdroid-no-static-tint")
                    .intercept(chain -> {
                        if (isThirdPartyView(chain.getThisObject())) {
                            return chain.proceed(new Object[]{noColor});
                        }
                        return chain.proceed();
                    });
            Log.i(TAG, "Installed setStaticDrawableColor NO_COLOR hook");

            Method imageTint = findMethod(iconClass, "setImageTintList", ColorStateList.class);
            if (imageTint != null) {
                imageTint.setAccessible(true);
                hook(imageTint).setId("aquosr7-crdroid-clear-image-tint").intercept(chain -> {
                    if (isThirdPartyView(chain.getThisObject())) {
                        return chain.proceed(new Object[]{null});
                    }
                    return chain.proceed();
                });
                Log.i(TAG, "Installed setImageTintList(null) hook");
            } else {
                Log.w(TAG, "setImageTintList(ColorStateList) not found");
            }

            Method iconColor = findMethod(iconClass, "setIconColor",
                    int.class, boolean.class);
            if (iconColor != null) {
                iconColor.setAccessible(true);
                hook(iconColor).setId("aquosr7-crdroid-no-icon-tint")
                        .intercept(chain -> {
                            if (isThirdPartyView(chain.getThisObject())) {
                                java.util.List<?> args = chain.getArgs();
                                return chain.proceed(new Object[]{noColor, args.get(1)});
                            }
                            return chain.proceed();
                        });
                Log.i(TAG, "Installed setIconColor NO_COLOR hook");
            } else {
                Log.w(TAG, "setIconColor(int, boolean) not present; "
                        + "static tint hook remains active");
            }
        } catch (Throwable t) {
            Log.e(TAG, "Could not install no-tint hooks", t);
        }
    }

    private static boolean isThirdPartyView(Object view) {
        if (view == null) return false;
        try {
            Field iconField = findField(view.getClass(), "mIcon");
            if (iconField == null) return false;
            iconField.setAccessible(true);
            Object statusBarIcon = iconField.get(view);
            String packageName = readPackageName(statusBarIcon);
            return packageName != null && !isSystemUiPackage(packageName);
        } catch (Throwable t) {
            Log.w(TAG, "Unable to classify StatusBarIconView", t);
            return false;
        }
    }

    private static String readPackageName(Object statusBarIcon) {
        if (statusBarIcon == null) return null;
        try {
            Field pkg = findField(statusBarIcon.getClass(), "pkg");
            if (pkg == null) return null;
            pkg.setAccessible(true);
            Object value = pkg.get(statusBarIcon);
            return value instanceof String ? (String) value : null;
        } catch (Throwable t) {
            Log.w(TAG, "Unable to read StatusBarIcon.pkg", t);
            return null;
        }
    }

    private static boolean isSystemUiPackage(String packageName) {
        return SYSTEM_UI.equals(packageName)
                || packageName.startsWith(SYSTEM_UI + ".");
    }

    private static Method findMethod(Class<?> type, String name,
            Class<?>... parameters) {
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
