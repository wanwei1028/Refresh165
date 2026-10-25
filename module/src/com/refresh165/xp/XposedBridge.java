package com.refresh165.xp;

import io.github.libxposed.api.XposedInterface;

/** 兼容层：把旧 XposedBridge.hook* 接到 libxposed 的 hook().intercept() 上。 */
public final class XposedBridge {
    private static volatile XposedInterface sModule;
    private static volatile ClassLoader sClassLoader;

    public static void init(XposedInterface module, ClassLoader cl) {
        sModule = module;
        sClassLoader = cl;
    }

    public static ClassLoader getClassLoader() { return sClassLoader; }

    public static void log(String msg) {
        XposedInterface m = sModule;
        if (m != null) m.log(4, "Refresh165", msg);
    }

    public static void hookAllMethods(Class<?> clazz, String methodName, XC_MethodHook callback) {
        if (clazz == null) return;
        Class<?> c = clazz;
        while (c != null) {
            for (java.lang.reflect.Method m : c.getDeclaredMethods()) {
                if (m.getName().equals(methodName)) hookExecutable(m, callback);
            }
            c = c.getSuperclass();
        }
    }

    public static XC_MethodHook.Unhook hookMethod(java.lang.reflect.Method method, XC_MethodHook callback) {
        return hookExecutable(method, callback);
    }

    private static XC_MethodHook.Unhook hookExecutable(java.lang.reflect.Executable ex, final XC_MethodHook callback) {
        final XposedInterface m = sModule;
        if (m == null || ex == null) return null;
        try {
            ex.setAccessible(true);
            XposedInterface.HookHandle handle = m.hook(ex).intercept(new XposedInterface.Hooker() {
                @Override public Object intercept(XposedInterface.Chain chain) throws Throwable {
                    XC_MethodHook.MethodHookParam p = new XC_MethodHook.MethodHookParam();
                    p.thisObject = chain.getThisObject();
                    p.args = chain.getArgs().toArray();
                    if (chain.getExecutable() instanceof java.lang.reflect.Method) {
                        p.method = (java.lang.reflect.Method) chain.getExecutable();
                    }
                    callback.beforeHookedMethod(p);
                    if (p.resultSet) return p.result;
                    try {
                        p.result = chain.proceed(p.args);
                    } catch (Throwable t) {
                        p.throwable = t;
                        try { callback.afterHookedMethod(p); } catch (Throwable ignored) { }
                        throw t;
                    }
                    callback.afterHookedMethod(p);
                    return p.result;
                }
            });
            return new XC_MethodHook.Unhook(handle);
        } catch (Throwable ignored) { return null; }
    }

    private XposedBridge() { }
}
