package com.refresh165.xp;

/** 兼容层：旧 XC_MethodHook 语义（before/after + 参数与返回值修改）。 */
public abstract class XC_MethodHook {
    protected void beforeHookedMethod(MethodHookParam param) throws Throwable { }
    protected void afterHookedMethod(MethodHookParam param) throws Throwable { }

    /** 兼容层：用于撤销 hook。 */
    public static final class Unhook {
        private final io.github.libxposed.api.XposedInterface.HookHandle handle;
        public Unhook(io.github.libxposed.api.XposedInterface.HookHandle handle) { this.handle = handle; }
        public void unhook() {
            try { if (handle != null) handle.unhook(); } catch (Throwable ignored) { }
        }
    }

    public static final class MethodHookParam {
        public Object thisObject;
        public Object[] args;
        public java.lang.reflect.Method method;
        public Object result;
        public boolean resultSet;
        public Throwable throwable;
        private Object extra;

        public Object getResult() { return result; }
        public void setResult(Object r) { result = r; throwable = null; resultSet = true; }
        public Throwable getThrowable() { return throwable; }
        public void setThrowable(Throwable t) { throwable = t; result = null; resultSet = true; }
        public Object getExtra() { return extra; }
        public void setExtra(Object o) { extra = o; }
    }
}
