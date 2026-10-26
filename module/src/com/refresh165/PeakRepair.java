package com.refresh165;

import android.content.ContentResolver;
import android.provider.Settings;

/**
 * 一次性峰值修复（DESIGN §2.4）：解决 LSPosed 注入晚于首次 updatePeakRefreshRate 的窗口。
 *
 * <p>不轮询：安装时 + 5s + 15s 三次机会，命中即停；只在 `<164.5` 时写，避免形成写回环（K17）。
 */
final class PeakRepair {
    private static volatile boolean sFixed;
    private static volatile int sCount;
    private static volatile String sLast = "not-run";
    private static volatile boolean sScheduled;

    private PeakRepair() { }

    static void schedule() {
        if (sScheduled) return;
        sScheduled = true;
        post(0L);
        post(5000L);
        post(15000L);
        HookStatus.installed("peak-repair");
    }

    private static void post(final long delay) {
        Sys.main().postDelayed(new Runnable() {
            @Override public void run() {
                try {
                    once();
                } catch (Throwable t) {
                    HookStatus.fail("peak-repair", t);
                }
            }
        }, delay);
    }

    static void once() {
        if (!Gear.takeover() || sFixed) return;
        // 与 MinFpsState 的 peak 抖动互斥：抖动窗口内不得介入（否则会覆盖临时值）
        if (MinFpsState.peakNudgeActive()) {
            HookStatus.put("peakRepair", "skipped-nudging");
            HookStatus.applied("peak-repair-skipped");
            return;
        }
        ContentResolver cr = Sys.cr();
        if (cr == null) { sLast = "no-context"; return; }
        float cur;
        try {
            cur = Settings.System.getFloat(cr, "peak_refresh_rate", 0f);
        } catch (Throwable t) {
            HookStatus.fail("peak-read", t);
            sLast = "read-failed";
            return;
        }
        if (cur >= 164.5f) {
            sFixed = true;
            sLast = "already-" + cur;
            publish();
            return;
        }
        try {
            // 与 vendor 同型：putFloat（P2-7）
            if (!Settings.System.putFloat(cr, "peak_refresh_rate", 165f)) {
                HookStatus.fail("peak-write", "write-rejected");
                sLast = "write-rejected";
                publish();
                return;
            }
            sFixed = true;
            sCount++;
            sLast = "repair " + cur + " -> 165";
            HookStatus.applied("peak-repair");
            android.util.Log.i("Refresh165", "peak repair " + cur + " -> 165");
        } catch (Throwable t) {
            HookStatus.fail("peak-write", t);
            sLast = "write-failed";
        }
        publish();
    }

    private static void publish() {
        HookStatus.put("peakFixCount", sCount);
        HookStatus.put("peakFix", sLast);
    }
}
