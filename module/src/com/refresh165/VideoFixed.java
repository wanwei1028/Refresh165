package com.refresh165;

import android.app.KeyguardManager;
import android.content.Context;
import android.os.PowerManager;

import com.refresh165.xp.XposedHelpers;

/**
 * R6 生效链路（DESIGN §12.4）：`effectiveRateId()` 是唯一夹取入口。
 *
 * <p>七条同时成立才动手：档位 7 + 面板有 165 + 单一前台 + 非锁屏 + 非多窗口 +
 * 非温度兜底 + 该应用被配置了固定值。任一不成立 → 完全不碰，`videoFixedSuspended`
 * 记录第一条不成立的原因（供验收断言）。
 *
 * <p>N-2：固定 30Hz 没有对应 Display.Mode → 只走 ④ min_fps，①②③ 全部跳过
 * （`videoFixedKind=adfr-floor`）。
 */
final class VideoFixed {
    static final String KIND_NONE = "none";
    static final String KIND_MODE = "mode";
    static final String KIND_ADFR_FLOOR = "adfr-floor";

    private static volatile String sKind = KIND_NONE;
    private static volatile String sPkg = "";
    private static volatile int sHz;
    private static volatile String sSuspended = "init";
    private static volatile int sUnsupportedHz;
    private static volatile int sApplied;
    private static volatile String sMultiWindow = "not-probed";

    private VideoFixed() { }

    /**
     * 必修-1：该前台包是否有「固定帧率」配置（含 adfr-floor 与各 suspended 态）。
     * 有配置就由本层独占裁决 —— 即使当前 suspended（fixedId=-1），也绝不允许
     * R4 白名单 / force 开关 / 默认提升把它顶成 165（DESIGN §12.4.3）。
     * 判定只看「有没有配置」，不看「现在能不能生效」。
     */
    static boolean handles(String pkg) {
        if (pkg == null || pkg.isEmpty()) return false;
        if (!Gear.takeover()) return false;
        return VideoRate.hzOf(pkg) > VideoRate.FOLLOW;
    }

    static boolean blocksGlobalPromotion() {
        return handles(foregroundPkg());
    }

    /** 带显式前台包的版本（前台切换即时信号用，避免等解析缓存）。 */
    static int effectiveRateId(String pkg) {
        return effectiveRateIdInternal(pkg);
    }

    /** -1 = 本帧不固定。 */
    static int effectiveRateId() {
        return effectiveRateIdInternal(null);
    }

    private static int effectiveRateIdInternal(String explicitPkg) {
        if (!Gear.takeover()) {
            suspend("stock-gear");
            return -1;                                  // ← 裁定 E：硬闸门，第一行
        }
        String pkg = explicitPkg != null && !explicitPkg.isEmpty()
                ? explicitPkg : foregroundPkg();
        if (pkg == null || pkg.isEmpty()) {
            suspend("blank-fg");
            return -1;
        }
        if (PerApp.isLauncher(pkg)) {
            suspend("launcher");
            return -1;
        }
        int hz = VideoRate.hzOf(pkg);
        if (hz <= VideoRate.FOLLOW) {
            // -1 未配置 / 0 显式跟随 → 不固定
            suspend(hz == VideoRate.FOLLOW ? "explicit-follow" : "");
            return -1;
        }
        if (ThermalGuard.tripped()) {
            suspend("thermal");
            return -1;
        }
        if (isKeyguardLocked() || !isInteractive()) {
            suspend("keyguard/screen-off");
            return -1;
        }
        if (!singleForeground(pkg)) {
            suspend("multi-window/pip");
            return -1;
        }
        if (hz == 30) {                                  // N-2：30 只走 ④
            sKind = KIND_ADFR_FLOOR;
            sPkg = pkg;
            sHz = 30;
            setSuspended("none");
            publish();
            return -1;
        }
        float panelMax = RateMap.panelMaxHz();
        if (hz > panelMax) hz = (int) panelMax;          // R5：不超面板物理能力
        if (!RateMap.hasDisplayModeFor(hz)) {
            sUnsupportedHz++;
            suspend("no-mode-for-hz");
            return -1;
        }
        int id = RateMap.rateIdOf(hz);
        if (id <= 0) {
            sUnsupportedHz++;
            suspend("unsupported-hz");
            return -1;
        }
        sKind = KIND_MODE;
        sPkg = pkg;
        sHz = hz;
        sApplied++;
        setSuspended("none");
        publish();
        return id;
    }

    /** R6 ② 的条件：只有「有真实 mode」的固定值才改写抬升票。 */
    static boolean voteFixedAllowed(int priority) {
        if (sKind != KIND_MODE) return false;
        if (effectiveRateId() <= 0) return false;
        return priority == 40 || priority == 85 || priority == 90 || priority == 130;
    }

    /** R6 ② / ③ 用的 Hz（已过 panelMax 夹取）。 */
    static float effectiveHz() {
        return sHz > 0 ? sHz : 0f;
    }

    /** N-2：固定 30 时 min_fps 的唯一落点；-1 表示当前不是 adfr-floor。
     *  （min_fps 表的目标值只由 {@link MinFpsState#targetHz()} 计算，此处不重复实现。） */
    static int adfrFloorHz() {
        return sKind == KIND_ADFR_FLOOR ? sHz : -1;
    }

    static String kind() {
        return sKind;
    }

    private static void suspend(String why) {
        sKind = KIND_NONE;
        setSuspended(why == null ? "" : why);
    }

    private static void setSuspended(String why) {
        sSuspended = why;
        publish();
    }

    private static void publish() {
        HookStatus.put("videoFixed", sKind == KIND_NONE ? "none" : (sPkg + "@" + sHz));
        HookStatus.put("videoFixedKind", sKind);
        HookStatus.put("videoFixedHz", sHz);
        HookStatus.put("videoFixedSuspended", sSuspended);
        HookStatus.put("videoFixedApplied", sApplied);
        HookStatus.put("videoUnsupportedHz", sUnsupportedHz);
        HookStatus.put("videoMultiWin", sMultiWindow);
    }

    static String foregroundPkg() {
        return PerApp.foregroundPkg();
    }

    static boolean isKeyguardLocked() {
        Context ctx = Sys.context();
        if (ctx == null) return false;
        try {
            KeyguardManager km = (KeyguardManager) ctx.getSystemService(Context.KEYGUARD_SERVICE);
            return km != null && km.isKeyguardLocked();
        } catch (Throwable t) {
            HookStatus.fail("keyguard", t);
            return false;
        }
    }

    static boolean isInteractive() {
        Context ctx = Sys.context();
        if (ctx == null) return true;
        try {
            PowerManager pm = (PowerManager) ctx.getSystemService(Context.POWER_SERVICE);
            return pm == null || pm.isInteractive();
        } catch (Throwable t) {
            HookStatus.fail("interactive", t);
            return true;
        }
    }

    /**
     * 单一前台判定（P1-5）：用决策现场的 `mPickRefreshRateData` 里的 WindowState，
     * 不用 SystemViewPolicy 的 WINDOWS 表（那里只有 bili 的窗口）。
     * 必修-2（DESIGN §12.5 第三列 / K21）：反射失败一律 **fail-closed**——
     * 判不出是否多窗口就不固定，`videoFixedSuspended=multi-window/pip`，
     * 而不是"猜成单窗口"把 PiP/分屏也钉死。
     */
    static boolean singleForeground(String pkg) {
        Object data = Reflect.field(PolicyRef.policy(), "mPickRefreshRateData");
        if (data == null) {
            sMultiWindow = "unknown-suspended";     // fail-closed
            return false;
        }
        Object win = findWindowLike(data);
        if (win == null) {
            sMultiWindow = "unknown-suspended";     // fail-closed
            return false;
        }
        boolean multiKnown = false;
        boolean pinnedKnown = false;
        try {
            Object multi = XposedHelpers.callMethod(win, "inMultiWindowMode");
            if (multi instanceof Boolean) {
                multiKnown = true;
                if (Boolean.TRUE.equals(multi)) return false;
            }
        } catch (Throwable t) {
            sMultiWindow = "no-inMultiWindowMode";
        }
        try {
            Object pinned = XposedHelpers.callMethod(win, "inPinnedWindowingMode");
            if (pinned instanceof Boolean) {
                pinnedKnown = true;
                if (Boolean.TRUE.equals(pinned)) return false;
            }
        } catch (Throwable t) {
            sMultiWindow = "no-inPinnedWindowingMode";
        }
        Object wmode = Reflect.field(win, "mWindowingMode");
        if (wmode instanceof Number) {
            int w = ((Number) wmode).intValue();
            // PINNED=2 / SPLIT_PRIMARY=3 / SPLIT_SECONDARY=4 / FREEFORM=5 / MULTI_WINDOW=6
            if (w == 2 || w == 3 || w == 4 || w == 5 || w == 6 || w == 100) return false;
            if (w == 1) {
                sMultiWindow = "known-fullscreen";
                return true;
            }
        }
        if (multiKnown && pinnedKnown) {
            sMultiWindow = "known-single-window";
            return true;
        }
        sMultiWindow = "unknown-suspended";
        return false;
    }

    private static Object findWindowLike(Object holder) {
        if (holder == null) return null;
        for (Class<?> c = holder.getClass(); c != null; c = c.getSuperclass()) {
            java.lang.reflect.Field[] fs;
            try {
                fs = c.getDeclaredFields();
            } catch (Throwable t) {
                return null;
            }
            for (java.lang.reflect.Field f : fs) {
                String n = f.getName();
                if (!n.startsWith("m")) continue;
                String type = f.getType().getName();
                boolean likely = type.endsWith("WindowState") || type.endsWith("ActivityRecord")
                        || "mCandidateWin".equals(n) || "mCandidateWindow".equals(n);
                if (!likely) continue;
                try {
                    f.setAccessible(true);
                    Object v = f.get(holder);
                    if (v != null) return v;
                } catch (Throwable t) {
                    HookStatus.fail("find-window", t);
                }
            }
        }
        return null;
    }

    static String statusLine() {
        return "videoFixed=" + (sKind == KIND_NONE ? "none" : (sPkg + "@" + sHz))
                + " kind=" + sKind + " suspended=" + sSuspended;
    }
}
