package com.refresh165.xp;

import java.lang.reflect.Method;

/** 兼容层：反射工具，替代旧的 XposedHelpers。 */
public final class XposedHelpers {

    public static Class<?> findClass(String name, ClassLoader cl) throws ClassNotFoundException {
        ClassLoader loader = cl != null ? cl : XposedBridge.getClassLoader();
        return Class.forName(name, false, loader != null ? loader : XposedHelpers.class.getClassLoader());
    }

    public static Object callMethod(Object obj, String methodName, Object... args) {
        if (obj == null) return null;
        Method m = find(obj.getClass(), methodName, args);
        if (m == null) throw new NoSuchMethodError(methodName + " in " + obj.getClass().getName());
        try {
            m.setAccessible(true);
            return m.invoke(obj, args);
        } catch (java.lang.reflect.InvocationTargetException e) {
            throw new RuntimeException(e.getCause());
        } catch (Throwable t) {
            throw new RuntimeException(t);
        }
    }

    public static Object callStaticMethod(Class<?> clazz, String methodName, Object... args) {
        if (clazz == null) return null;
        Method m = find(clazz, methodName, args);
        if (m == null) throw new NoSuchMethodError(methodName + " in " + clazz.getName());
        try {
            m.setAccessible(true);
            return m.invoke(null, args);
        } catch (java.lang.reflect.InvocationTargetException e) {
            throw new RuntimeException(e.getCause());
        } catch (Throwable t) {
            throw new RuntimeException(t);
        }
    }

    private static Method find(Class<?> clazz, String name, Object[] args) {
        int want = args == null ? 0 : args.length;
        Class<?> c = clazz;
        while (c != null) {
            for (Method m : c.getDeclaredMethods()) {
                if (!m.getName().equals(name)) continue;
                if (m.getParameterCount() != want) continue;
                if (assignable(m.getParameterTypes(), args)) return m;
            }
            c = c.getSuperclass();
        }
        // 退化：只按名字 + 参数个数
        c = clazz;
        while (c != null) {
            for (Method m : c.getDeclaredMethods()) {
                if (m.getName().equals(name) && m.getParameterCount() == want) return m;
            }
            c = c.getSuperclass();
        }
        return null;
    }

    private static boolean assignable(Class<?>[] types, Object[] args) {
        if (args == null) return types.length == 0;
        for (int i = 0; i < types.length; i++) {
            Object a = args[i];
            if (a == null) continue;
            Class<?> t = wrap(types[i]);
            if (!t.isInstance(a)) return false;
        }
        return true;
    }

    private static Class<?> wrap(Class<?> t) {
        if (!t.isPrimitive()) return t;
        if (t == int.class) return Integer.class;
        if (t == long.class) return Long.class;
        if (t == boolean.class) return Boolean.class;
        if (t == float.class) return Float.class;
        if (t == double.class) return Double.class;
        if (t == short.class) return Short.class;
        if (t == byte.class) return Byte.class;
        if (t == char.class) return Character.class;
        return t;
    }

    private XposedHelpers() { }
}
