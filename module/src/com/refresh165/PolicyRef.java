package com.refresh165;

/**
 * vendor 实例引用（DESIGN §4.3）：`policy` 供 PerApp / PeakRepair / MinFpsState 取
 * `mConfigs` 与 `updateCurrentSettingMode`；为 null 时对应功能记 unavailable 并降级。
 */
final class PolicyRef {
    private static volatile Object sPolicy;
    private static volatile Object sConfigs;
    private static volatile Object sCore;

    private PolicyRef() { }

    static void setPolicy(Object policy) {
        if (policy == null) return;
        Class<?> type = policy.getClass();
        while (type != null && !type.getName().equals(
                "com.android.server.wm.OplusRefreshRatePolicyImpl")) {
            type = type.getSuperclass();
        }
        if (type == null || Reflect.intField(policy, "mDisplayId", -1) != 0) return;
        sPolicy = policy;
        Object configs = Reflect.field(policy, "mConfigs");
        if (configs != null) sConfigs = configs;
        Object core = Reflect.field(policy, "mCore");
        if (core != null) sCore = core;
    }

    static void setConfigs(Object configs) {
        if (configs != null) sConfigs = configs;
    }

    static Object policy() {
        return sPolicy;
    }

    static Object configs() {
        Object c = sConfigs;
        if (c != null) return c;
        Object p = sPolicy;
        if (p != null) {
            Object got = Reflect.field(p, "mConfigs");
            if (got != null) { sConfigs = got; return got; }
        }
        return null;
    }

    static Object core() {
        return sCore;
    }

    static String statusLine() {
        return "policy=" + (sPolicy == null ? "null" : "ok")
                + " configs=" + (configs() == null ? "null" : "ok");
    }
}
