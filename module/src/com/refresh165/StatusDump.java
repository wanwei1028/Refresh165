package com.refresh165;

import com.refresh165.xp.XC_MethodHook;
import com.refresh165.xp.XposedBridge;
import com.refresh165.xp.XposedHelpers;
import java.io.PrintWriter;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * `dumpsys … refresh165-status` 出口（DESIGN §1.6-6、K9）。
 *
 * <p>挂三个可能的 dump 落点，任一处命中即输出；用 ThreadLocal 去重，避免嵌套 dump
 * 打印两遍。
 */
final class StatusDump {
    /** K：同一次 dumpsys 内去重（ThreadLocal，跨调用自动复位）。 */
    private static final ThreadLocal<Boolean> DUMP_ONCE = new ThreadLocal<Boolean>();
    static final String QUERY = "refresh165-status";
    private static final ThreadLocal<Boolean> PRINTING = new ThreadLocal<Boolean>();
    private static final AtomicInteger HITS = new AtomicInteger();

    private StatusDump() { }

    static void install(ClassLoader cl) {
        String[] classes = {
                "com.oplus.vrr.OPlusExternalRefreshRateManager",
                "com.oplus.vrr.OPlusRefreshRateService",
                "com.android.server.wm.OplusRefreshRatePolicyImpl"
        };
        int installed = 0;
        for (String name : classes) {
            Class<?> c;
            try {
                c = XposedHelpers.findClass(name, cl);
            } catch (Throwable t) {
                if (!HookStatus.isInstalled("status-dump")) {
                    HookStatus.failInstall("status-dump-" + simple(name), t);
                }
                continue;
            }
            installed += XposedBridge.hookAllMethods(c, "dump", new Printer());
            installed += XposedBridge.hookAllMethods(c, "onDump", new Printer());
        }
        if (installed > 0) {
            HookStatus.installed("status-dump");
        } else if (!HookStatus.isInstalled("status-dump")) {
            HookStatus.failInstall("status-dump", "no-dump-entry");
        }
        HookStatus.put("statusDumpHooks", installed);
    }

    private static String simple(String fqcn) {
        int i = fqcn.lastIndexOf('.');
        return i < 0 ? fqcn : fqcn.substring(i + 1);
    }

    /** 读 settings 进程写入的 UI 自证串（跨进程诊断）。 */
    private static String readUiStatus() {
        try {
            android.content.ContentResolver cr = Sys.cr();
            if (cr == null) return "no-context";
            String v = android.provider.Settings.Secure.getString(cr, "refresh165_ui_status");
            return v == null ? "none" : v;
        } catch (Throwable t) {
            return "read-failed:" + t.getClass().getSimpleName();
        }
    }

    private static final class Printer extends XC_MethodHook {
        @Override protected void afterHookedMethod(MethodHookParam p) {
            if (Boolean.TRUE.equals(PRINTING.get())) return;
            PrintWriter out = null;
            boolean wants = false;
            Object[] args = p.args;
            if (args == null) return;
            for (Object a : args) {
                if (a instanceof PrintWriter) out = (PrintWriter) a;
                else if (a instanceof String[]) {
                    for (String s : (String[]) a) if (QUERY.equals(s)) wants = true;
                } else if (QUERY.equals(a)) {
                    wants = true;
                }
            }
            if (!wants || out == null) return;
            PRINTING.set(Boolean.TRUE);
            try {
                int hits = HITS.incrementAndGet();
                PerApp.noteGameService(p.thisObject);
                // D1：dumpsys 时强制刷新一次 force 开关（避免 1s 缓存快照误导判读）
                // K：同一次 dumpsys 调用只输出一份（失败重入/多 hook 同时触发时去重）
                Boolean done = DUMP_ONCE.get();
                if (Boolean.TRUE.equals(done)) {
                    HookStatus.put("dumpHits", "dedup");
                    return;
                }
                DUMP_ONCE.set(Boolean.TRUE);
                HookStatus.put("dumpHits", 1);
                try {
                Gear.force165Fresh();
                out.println("---- Refresh165 ----");
                out.print(HookStatus.dump());
                out.println("rateMapLine: " + RateMap.statusLine());
                out.println("perAppLine: " + PerApp.statusLine());
                int periodic = ThermalGuard.periodicTaskCount();
                out.println("periodicTasks=" + periodic
                        + " timerList=" + (periodic > 0 ? "thermal-recheck(30s,fallback)" : "none"));
                out.println("thermalLine: " + ThermalGuard.statusLine());
                out.println("minFpsLine: " + MinFpsState.statusLine());
                out.println("videoLine: " + VideoFixed.statusLine());
                out.println("videoRateLine: " + VideoRate.statusLine());
                out.println("uiStatus: " + readUiStatus());
                out.println("sfLine: " + SfSpecs.statusLine());
                out.println("policyLine: " + PolicyRef.statusLine()
                        + " statusDumpHits=" + hits);
                out.flush();
                } catch (Throwable t) {
                    HookStatus.runtimeFailed("status-dump-print", t);
                } finally {
                    DUMP_ONCE.remove();
                    PRINTING.remove();
                }
            } catch (Throwable t) {
                HookStatus.runtimeFailed("status-dump-outer", t);
                PRINTING.remove();
            }
        }
    }
}
