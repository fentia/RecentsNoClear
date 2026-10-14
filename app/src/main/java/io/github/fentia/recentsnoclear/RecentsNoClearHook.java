package io.github.fentia.recentsnoclear;

import android.util.Log;
import android.view.View;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

import io.github.libxposed.api.XposedInterface;
import io.github.libxposed.api.XposedModule;

public class RecentsNoClearHook extends XposedModule {

    private static final String TAG = "RecentsNoClear";

    private static final Set<String> TARGET_PACKAGES = new HashSet<>(
            Arrays.asList("com.android.launcher", "com.oppo.launcher"));

    private static final String CLASS_CLEAR_ALL_PANEL = "com.oplus.quickstep.views.OplusClearAllPanelView";
    private static final String CLASS_PRESS_FEEDBACK_BUTTON = "com.android.launcher.views.PressFeedbackButton";

    // 目标应用 ClassLoader，供热重载时交给新一代代码
    private static ClassLoader sAppClassLoader;
    private static Class<?> sPanelClass;
    private static Field sClearButtonField;

    @Override
    public void onPackageReady(PackageReadyParam param) {
        if (!TARGET_PACKAGES.contains(param.getPackageName())) return;
        sAppClassLoader = param.getClassLoader();
        installHooks(param.getClassLoader());
    }

    // 放行热重载，并把目标应用 ClassLoader 传递给新一代代码
    @Override
    public boolean onHotReloading(HotReloadingParam param) {
        try {
            if (sAppClassLoader != null) param.setSavedInstanceState(sAppClassLoader);
        } catch (Throwable t) {
            log(Log.ERROR, TAG, "hot reload state transfer failed", t);
        }
        return true;
    }

    // 热重载不重放包生命周期回调，需在新代码中卸载旧 hook 并重新安装
    @Override
    public void onHotReloaded(HotReloadedParam param) {
        for (XposedInterface.HookHandle handle : param.getOldHookHandles()) handle.unhook();
        ClassLoader classLoader = hotReloadClassLoader(param);
        if (classLoader == null) {
            log(Log.ERROR, TAG, "hot reload classloader not found", null);
            return;
        }
        installHooks(classLoader);
    }

    // 优先取热重载传递的 ClassLoader，回退到旧 hook 的宿主类
    private static ClassLoader hotReloadClassLoader(HotReloadedParam param) {
        Object saved = param.getSavedInstanceState();
        if (saved instanceof ClassLoader) return (ClassLoader) saved;
        for (XposedInterface.HookHandle handle : param.getOldHookHandles()) {
            ClassLoader classLoader = handle.getExecutable().getDeclaringClass().getClassLoader();
            if (classLoader != null) return classLoader;
        }
        return null;
    }

    // 隐藏清除按钮：面板 inflate 完成后一次，setAlpha 后再次（setAlpha 会重新显示按钮）
    private void installHooks(ClassLoader classLoader) {
        try {
            Class<?> panel = Class.forName(CLASS_CLEAR_ALL_PANEL, false, classLoader);
            Field clearButton = findFieldByType(panel, CLASS_PRESS_FEEDBACK_BUTTON);
            if (clearButton == null) {
                log(Log.ERROR, TAG, "clear button field not found", null);
                return;
            }
            clearButton.setAccessible(true);
            sPanelClass = panel;
            sClearButtonField = clearButton;

            Method inflate = findMethodByName(panel, "inflateIfNeeded");
            if (inflate == null) inflate = findMethodByName(panel, "onFinishInflate");
            if (inflate == null) {
                log(Log.ERROR, TAG, "inflate hook failed", null);
            } else {
                hook(inflate).intercept(chain -> {
                    Object result = chain.proceed();
                    hideClearButton(chain.getThisObject());
                    return result;
                });
            }

            Method setAlpha = findMethod(panel, "setAlpha", float.class);
            if (setAlpha == null) {
                log(Log.ERROR, TAG, "setAlpha hook failed", null);
            } else {
                hook(setAlpha).intercept(chain -> {
                    Object result = chain.proceed();
                    hideClearButton(chain.getThisObject());
                    return result;
                });
            }
        } catch (Throwable t) {
            log(Log.ERROR, TAG, "clear all panel hook failed", t);
        }
    }

    // 沿继承链按字段类型查找，字段名在部分系统版本被混淆
    private static Field findFieldByType(Class<?> clazz, String typeName) {
        for (Class<?> current = clazz; current != null; current = current.getSuperclass()) {
            for (Field field : current.getDeclaredFields()) {
                if (field.getType().getName().equals(typeName)) return field;
            }
        }
        return null;
    }

    // 沿继承链按名称与参数类型查找方法
    private static Method findMethod(Class<?> clazz, String name, Class<?>... parameterTypes) {
        for (Class<?> current = clazz; current != null; current = current.getSuperclass()) {
            try {
                return current.getDeclaredMethod(name, parameterTypes);
            } catch (NoSuchMethodException ignored) {
            }
        }
        return null;
    }

    // 沿继承链按名称查找方法，无参重载优先
    private static Method findMethodByName(Class<?> clazz, String name) {
        Method fallback = null;
        for (Class<?> current = clazz; current != null; current = current.getSuperclass()) {
            for (Method method : current.getDeclaredMethods()) {
                if (!method.getName().equals(name)) continue;
                if (method.getParameterCount() == 0) return method;
                if (fallback == null) fallback = method;
            }
        }
        return fallback;
    }

    // 将按钮字段置为不可见，非目标实例或读取失败时跳过
    private static void hideClearButton(Object panel) {
        if (sPanelClass == null || !sPanelClass.isInstance(panel)) return;
        try {
            View button = (View) sClearButtonField.get(panel);
            if (button != null) button.setVisibility(View.GONE);
        } catch (Throwable ignored) {
        }
    }
}
