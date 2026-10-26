package com.refresh165;

import java.lang.reflect.Field;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 字段读写工具。所有 vendor 字段访问都走这里，失败只记一次 HookStatus，不抛异常
 * （DESIGN §1.6-5：不允许大段静默 catch）。
 */
final class Reflect {
    /** P2 §6.2：Class→(name→Field) 缓存；热路径不再每次 getDeclaredField/setAccessible。 */
    private static final ConcurrentHashMap<String, Field> FIELDS =
            new ConcurrentHashMap<String, Field>();
    private static final Set<String> MISSES =
            java.util.Collections.newSetFromMap(new ConcurrentHashMap<String, Boolean>());

    private Reflect() { }

    /** P2：上限保护 —— 超过 512 条直接清空（字段集合是有限闭集，重建成本可接受）。 */
    private static final int MAX_CACHE = 512;

    private static void guardSize() {
        if (FIELDS.size() > MAX_CACHE) {
            FIELDS.clear();
            MISSES.clear();
            HookStatus.put("reflectCache", "cleared>" + MAX_CACHE);
        }
    }

    private static Field lookup(Class<?> owner, String name) {
        String key = owner.getName() + '#' + name;
        Field hit = FIELDS.get(key);
        if (hit != null) return hit;
        if (MISSES.contains(key)) return null;
        for (Class<?> c = owner; c != null; c = c.getSuperclass()) {
            try {
                Field f = c.getDeclaredField(name);
                f.setAccessible(true);
                guardSize();
                FIELDS.put(key, f);
                return f;
            } catch (NoSuchFieldException e) {
                // 继续往父类找
            } catch (Throwable t) {
                HookStatus.fail("field-" + name, t);
                MISSES.add(key);
                return null;
            }
        }
        guardSize();
        MISSES.add(key);
        return null;
    }

    static Object field(Object obj, String name) {
        if (obj == null || name == null) return null;
        Field f = lookup(obj.getClass(), name);
        if (f == null) return null;
        try {
            return f.get(obj);
        } catch (Throwable t) {
            HookStatus.fail("field-" + name, t);
            return null;
        }
    }

    static boolean write(Object obj, String name, Object value) {
        if (obj == null) return false;
        Field f = lookup(obj.getClass(), name);
        if (f == null) return false;
        try {
            f.set(obj, value);
            return true;
        } catch (Throwable t) {
            HookStatus.fail("write-" + name, t);
            return false;
        }
    }

    static int intField(Object obj, String name, int fallback) {
        Object v = field(obj, name);
        return v instanceof Number ? ((Number) v).intValue() : fallback;
    }

    static float floatField(Object obj, String name, float fallback) {
        Object v = field(obj, name);
        return v instanceof Number ? ((Number) v).floatValue() : fallback;
    }

    static boolean boolField(Object obj, String name, boolean fallback) {
        Object v = field(obj, name);
        return v instanceof Boolean ? ((Boolean) v).booleanValue() : fallback;
    }

    /** 字段是否真的存在于该对象类型（用于区分「值为 null」与「没有这个字段」）。 */
    static boolean hasField(Object obj, String name) {
        if (obj == null) return false;
        Class<?> c = obj.getClass();
        while (c != null) {
            try {
                c.getDeclaredField(name);
                return true;
            } catch (NoSuchFieldException e) {
                c = c.getSuperclass();
            } catch (Throwable t) {
                return false;
            }
        }
        return false;
    }
}
