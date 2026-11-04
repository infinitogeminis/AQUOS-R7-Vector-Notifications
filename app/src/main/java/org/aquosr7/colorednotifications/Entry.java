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
    private static final String NETSPEED_PACKAGE = "com.nisargjhaveri.netspeed";
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
                if (packageName == null || SYSTEM_UI.equals(packageName)
                        || NETSPEED_PACKAGE.equals(packageName)) {
                    if (NETSPEED_PACKAGE.equals(packageName)) {
                        Log.i(TAG, "NETSPEED_ICON_PRESERVED package=" + packageName);
                    }
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

            Method setResource = ImageView.class.getDeclaredMethod(
                    "setImageResource", int.class);
            hook(setResource).setId("aquosr7-tune-native-ims-brick-red")
                    .intercept(chain -> {
                        Object target = chain.getThisObject();
                        Object result = chain.proceed();
                        if (NATIVE_IMS_VIEWS.containsKey(target)) {
                            int resourceId = (Integer) chain.getArg(0);
                            String resourceName = ((ImageView) target).getResources()
                                    .getResourceEntryName(resourceId);
                            if ("ic_volte_tw".equals(resourceName)
                                    || "ic_vowifi_tw".equals(resourceName)
                                    || "ic_vowifi_tw1".equals(resourceName)
                                    || "ic_vowifi_tw2".equals(resourceName)) {
                                recolorNativeImsVector((ImageView) target);
                            } else {
                                Log.i(TAG, "NATIVE_IMS_COLOR_UNCHANGED resource=" + resourceName);
                            }
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

    private static void recolorNativeImsVector(ImageView imageView) {
        try {
            Drawable drawable = imageView.getDrawable();
            if (drawable == null) return;
            drawable = drawable.mutate();
            int changed = recolorVectorNode(drawable, 0);
            if (changed > 0) {
                imageView.setImageDrawable(drawable);
                Log.i(TAG, "NATIVE_IMS_RED_TUNED paths=" + changed + " target=#FFB36666");
            } else {
                Log.w(TAG, "NATIVE_IMS_RED_NOT_FOUND drawable=" + drawable.getClass().getName());
            }
        } catch (Throwable error) {
            Log.e(TAG, "NATIVE_IMS_RED_TUNING_FAILED", error);
        }
    }

    private static int recolorVectorNode(Object node, int depth) throws Exception {
        if (node == null || depth > 12) return 0;
        int changed = 0;
        String className = node.getClass().getName();

        if (className.contains("VectorDrawable") && !className.contains("Compat")) {
            Field stateField = findField(node.getClass(), "mVectorState");
            if (stateField != null) {
                stateField.setAccessible(true);
                changed += recolorVectorNode(stateField.get(node), depth + 1);
            }
        }
        Field rendererField = findField(node.getClass(), "mVPathRenderer");
        if (rendererField != null) {
            rendererField.setAccessible(true);
            changed += recolorVectorNode(rendererField.get(node), depth + 1);
        }
        Field rootField = findField(node.getClass(), "mRootGroup");
        if (rootField != null) {
            rootField.setAccessible(true);
            changed += recolorVectorNode(rootField.get(node), depth + 1);
        }
        Field childrenField = findField(node.getClass(), "mChildren");
        if (childrenField != null) {
            childrenField.setAccessible(true);
            Object children = childrenField.get(node);
            if (children instanceof Iterable<?>) {
                for (Object child : (Iterable<?>) children) {
                    changed += recolorVectorNode(child, depth + 1);
                }
            }
        }
        changed += recolorComplexColor(node, "mFillColors");
        changed += recolorComplexColor(node, "mStrokeColors");
        return changed;
    }

    private static int recolorComplexColor(Object path, String fieldName) throws Exception {
        Field colorField = findField(path.getClass(), fieldName);
        if (colorField == null) return 0;
        colorField.setAccessible(true);
        Object complexColor = colorField.get(path);
        if (complexColor == null) return 0;
        Field valueField = findField(complexColor.getClass(), "mColor");
        if (valueField == null || valueField.getType() != int.class) return 0;
        valueField.setAccessible(true);
        if (valueField.getInt(complexColor) != 0xFF8C4141) return 0;
        valueField.setInt(complexColor, 0xFFB36666);
        return 1;
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
