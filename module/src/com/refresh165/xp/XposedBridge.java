package com.refresh165.xp;

import io.github.libxposed.api.XposedInterface;
import java.lang.reflect.Executable;
import java.lang.reflect.Method;

/**
 * 兼容层：把旧 XposedBridge.hook* 接到 libxposed 的 hook().intercept() 上。
 *
 * <p>v1.0.6 只**增加**能力，不改 Hooker 语义（DESIGN §9.3 编码第 0 步）：
 * ① `hookAllMethods` / `hookMethodCount` 返回**安装成功数**，失败经 {@link InstallSink}
 *    回报（安装失败对外可见，否则无法区分「没装」与「没动手」）；
 * ② 新增 `argInt/argOf/argCount`：按类型取参，不假定位置。
 */
public final class XposedBridge {
    /** 安装失败回报（由 module 侧接到 HookStatus，避免 xp 依赖具体实现）。 */
    public interface InstallSink {
        void failed(String target, Throwable t);
    }

    private static volatile XposedInterface sModule;
    private static volatile ClassLoader sClassLoader;
    private static volatile InstallSink sSink;

    public static void init(XposedInterface module, ClassLoader cl) {
        sModule = module;
        sClassLoader = cl;
    }

    public static void setInstallSink(InstallSink sink) {
        sSink = sink;
    }

    public static ClassLoader getClassLoader() { return sClassLoader; }

    public static void log(String msg) {
        XposedInterface m = sModule;
        if (m != null) m.log(4, "Refresh165", msg);
    }

    /** @return 实际安装成功的方法数（0 = 没找到/全部失败）。 */
    public static int hookAllMethods(Class<?> clazz, String methodName, XC_MethodHook callback) {
        if (clazz == null || methodName == null) return 0;
        int ok = 0;
        Class<?> c = clazz;
        while (c != null) {
            Method[] ms;
            try {
                ms = c.getDeclaredMethods();
            } catch (Throwable t) {
                report(c.getName() + "#" + methodName, t);
                break;
            }
            for (Method m : ms) {
                if (!m.getName().equals(methodName)) continue;
                if (hookExecutable(m, callback) != null) ok++;
            }
            c = c.getSuperclass();
        }
        return ok;
    }

    /** 兼容旧调用点（需要 Unhook 的场合）；失败返回 null 并已经回报。 */
    public static XC_MethodHook.Unhook hookMethod(Method method, XC_MethodHook callback) {
        return hookExecutable(method, callback);
    }

    /** @return 1 = 安装成功，0 = 失败。 */
    public static int hookMethodCount(Method method, XC_MethodHook callback) {
        return hookExecutable(method, callback) != null ? 1 : 0;
    }

    private static XC_MethodHook.Unhook hookExecutable(Executable ex, final XC_MethodHook callback) {
        final XposedInterface m = sModule;
        if (m == null || ex == null) return null;
        try {
            ex.setAccessible(true);
            XposedInterface.HookHandle handle = m.hook(ex).intercept(new XposedInterface.Hooker() {
                @Override public Object intercept(XposedInterface.Chain chain) throws Throwable {
                    XC_MethodHook.MethodHookParam p = new XC_MethodHook.MethodHookParam();
                    p.thisObject = chain.getThisObject();
                    p.args = chain.getArgs().toArray();
                    if (chain.getExecutable() instanceof Method) {
                        p.method = (Method) chain.getExecutable();
                    }
                    try {
                        callback.beforeHookedMethod(p);
                    } catch (Throwable t) {
                        // 旧 XposedBridge 语义：before 抛异常 → 记录并继续原方法，
                        // 绝不把异常抛进被 hook 的 vendor 方法（可能持 WM 锁）。
                        report("before-" + ex.getName(), t);
                        p.args = chain.getArgs().toArray();
                        p.result = null;
                        p.throwable = null;
                        p.resultSet = false;
                    }
                    if (p.resultSet) {
                        runAfter(callback, p, ex);
                        if (p.throwable != null) throw p.throwable;
                        return p.result;
                    }
                    try {
                        p.result = chain.proceed(p.args);
                    } catch (Throwable t) {
                        p.setThrowable(t);
                    }
                    runAfter(callback, p, ex);
                    if (p.throwable != null) throw p.throwable;
                    return p.result;
                }
            });
            return new XC_MethodHook.Unhook(handle);
        } catch (Throwable t) {
            report(describe(ex), t);
            return null;
        }
    }

    private static String describe(Executable ex) {
        if (ex == null) return "null";
        StringBuilder b = new StringBuilder();
        b.append(ex.getDeclaringClass() == null ? "?" : ex.getDeclaringClass().getSimpleName())
                .append('#').append(ex.getName()).append('(');
        Class<?>[] types = ex.getParameterTypes();
        for (int i = 0; i < types.length; i++) {
            if (i > 0) b.append(',');
            b.append(types[i].getSimpleName());
        }
        return b.append(')').toString();
    }

    private static void runAfter(XC_MethodHook callback, XC_MethodHook.MethodHookParam p,
                                 Executable ex) {
        try {
            callback.afterHookedMethod(p);
        } catch (Throwable t) {
            report("after-" + ex.getName(), t);
        }
    }

    private static void report(String target, Throwable t) {
        InstallSink s = sSink;
        if (s != null) {
            try {
                s.failed(target, t);
            } catch (Throwable ignored) {
                // 回报通道自身失败时不再递归
            }
        }
    }

    // ------------------------------------------------------------------ 取参工具

    public static int argCount(XC_MethodHook.MethodHookParam p) {
        return p == null || p.args == null ? 0 : p.args.length;
    }

    /**
     * 按类型扫描 args 取 Integer，**不假定位置**（DESIGN §9.3 0-b）。
     * 用于 `normalizeHighRefreshGear(Context,int)` 这类多参签名：换 RUS 换序也不会取错。
     */
    public static int argInt(XC_MethodHook.MethodHookParam p, int fallback) {
        Integer v = argOf(p, Integer.class);
        return v == null ? fallback : v.intValue();
    }

    /** 按类型扫描 args 取第一个指定类型实例。 */
    public static <T> T argOf(XC_MethodHook.MethodHookParam p, Class<T> type) {
        if (p == null || p.args == null || type == null) return null;
        for (Object a : p.args) {
            if (a != null && type.isInstance(a)) {
                try {
                    return type.cast(a);
                } catch (Throwable t) {
                    return null;
                }
            }
        }
        return null;
    }

    private XposedBridge() { }
}
