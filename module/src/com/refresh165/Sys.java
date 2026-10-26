package com.refresh165;

import android.content.ContentResolver;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import java.lang.reflect.Method;

import com.refresh165.xp.XposedHelpers;

/**
 * 宿主进程上下文 / 主线程工具（system_server 与 com.android.settings 两个进程共用）。
 *
 * <p>设计依据：DESIGN §1.4（Gear.decide 需要 ContentResolver）、§12.3（Settings 读写）、
 * §2.5（ThermalGuard 需要 PowerManager）。上下文只取一次并缓存，取不到时所有判定函数
 * 必须安全退化为「不接管」（DESIGN §1.4 reason=settings-unreadable）。
 */
final class Sys {
    private static volatile Context sContext;
    private static volatile boolean sContextFailLogged;
    private static volatile Handler sMain;

    private Sys() { }

    static void setContext(Context c) {
        if (c != null) sContext = c;
    }

    /** system_server 里 currentApplication() 为 null，必须走 getSystemContext()；settings 进程反之。 */
    static Context context() {
        Context c = sContext;
        if (c != null) return c;
        Object thread;
        try {
            thread = XposedHelpers.callStaticMethod(
                    Class.forName("android.app.ActivityThread"), "currentActivityThread");
        } catch (Throwable t) {
            logContextFailure(t);
            return null;
        }
        if (thread != null) {
            try {
                Object sc = XposedHelpers.callMethod(thread, "getSystemContext");
                if (sc instanceof Context) { sContext = (Context) sc; return sContext; }
            } catch (Throwable t) {
                logContextFailure(t);
            }
            try {
                Object app = XposedHelpers.callMethod(thread, "currentApplication");
                if (app instanceof Context) { sContext = (Context) app; return sContext; }
            } catch (Throwable t) {
                logContextFailure(t);
            }
            try {
                Object app = XposedHelpers.callMethod(thread, "getApplication");
                if (app instanceof Context) { sContext = (Context) app; return sContext; }
            } catch (Throwable t) {
                logContextFailure(t);
            }
        }
        return null;
    }

    private static void logContextFailure(Throwable t) {
        if (sContextFailLogged) return;
        sContextFailLogged = true;
        HookStatus.fail("sys-context", t);
        android.util.Log.e("Refresh165", "system context unavailable: " + t);
    }

    static ContentResolver cr() {
        Context c = context();
        return c == null ? null : c.getContentResolver();
    }

    static Handler main() {
        Handler h = sMain;
        if (h != null) return h;
        synchronized (Sys.class) {
            if (sMain == null) sMain = new Handler(Looper.getMainLooper());
            return sMain;
        }
    }

    /** 反射取方法但吞掉「找不到」这一类预期失败；返回值 null 表示不可用。 */
    static Method method(Class<?> c, String name, Class<?>... types) {
        if (c == null) return null;
        Class<?> k = c;
        while (k != null) {
            try {
                Method m = k.getDeclaredMethod(name, types);
                m.setAccessible(true);
                return m;
            } catch (NoSuchMethodException e) {
                k = k.getSuperclass();
            } catch (Throwable t) {
                HookStatus.fail("reflect-method", t);
                return null;
            }
        }
        return null;
    }
}
