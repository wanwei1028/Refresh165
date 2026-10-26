package com.refresh165;

import android.os.SystemClock;
import android.provider.Settings;

/**
 * 唯一闸门（DESIGN §1.3/§1.4）：`Gear.takeover()` = 「当前 settingMode 是否为 7 且面板/标定可用」。
 *
 * <p>所有 165 相关 Hook 的第一行都是 `if (!Gear.takeover()) { HookStatus.stockPass(tag); return; }`，
 * 于是 120 档「Hook 活着但零改写」可自证（R2）。
 *
 * <p>force 开关不再参与「是否接管」，降级为「165 档内的强度开关」（§7.1）。
 */
final class Gear {
    static final int SETTING_MODE_AUTO = 0;
    static final int SETTING_MODE_90 = 1;
    static final int SETTING_MODE_60 = 2;
    static final int SETTING_MODE_120 = 3;
    static final int SETTING_MODE_144 = 4;
    static final int SETTING_MODE_165 = 7;
    static final int SETTING_MODE_EXTREME = 8;

    /** 按应用 override 的列号（N-3）：系统【读】用列 3（pick=7），设置页【写】用列 4（choice>120）。 */
    static final int PER_APP_COLUMN_PICK120 = 3;
    static final int PER_APP_COLUMN_CHOICE = 4;

    enum Kind { STOCK, EXTREME_165, EXTREME_185 }

    static final class Decision {
        final int settingMode;
        final int column;
        final int targetRateId;
        final Kind gear;
        final boolean panel165;
        final boolean takeover;
        final String reason;

        Decision(int mode, int col, Kind g, boolean panel, int target, boolean take, String why) {
            settingMode = mode;
            column = col;
            gear = g;
            panel165 = panel;
            targetRateId = target;
            takeover = take;
            reason = why;
        }
    }

    private static final Decision SAFE_STOCK =
            new Decision(Integer.MIN_VALUE, 0, Kind.STOCK, false, -1, false, "reentrant");

    private static volatile Decision sDecision;
    private static volatile long sDecisionAt;
    private static final long TTL_MS = 400L;
    private static final ThreadLocal<Boolean> IN_DECIDE = new ThreadLocal<Boolean>();

    private static volatile long sForceAt;
    private static volatile boolean sForce;

    private Gear() { }

    /** 仅镜像 vendor 语义，用于日志 / 无反射时的兜底。 */
    static int modeColumn(int settingMode) {
        switch (settingMode) {
            case SETTING_MODE_90: return 1;
            case SETTING_MODE_60: return 2;
            case SETTING_MODE_120:
            case SETTING_MODE_144:
            case SETTING_MODE_165: return PER_APP_COLUMN_PICK120;
            case SETTING_MODE_EXTREME: return PER_APP_COLUMN_CHOICE;
            default: return 0;
        }
    }

    static Decision decide() {
        if (Boolean.TRUE.equals(IN_DECIDE.get())) return SAFE_STOCK;
        long now = SystemClock.elapsedRealtime();
        Decision d = sDecision;
        if (d != null && now - sDecisionAt < TTL_MS) return d;
        IN_DECIDE.set(Boolean.TRUE);
        try {
            d = compute(now);
        } catch (Throwable t) {
            HookStatus.fail("gear-decide", t);
            d = new Decision(Integer.MIN_VALUE, 0, Kind.STOCK, false, -1, false,
                    "decide-error:" + t.getClass().getSimpleName());
        } finally {
            IN_DECIDE.remove();
        }
        sDecision = d;
        sDecisionAt = now;
        // 退出恢复（修复 #1）：takeover 真→假时在闸门之外做一次收口重发
        MinFpsState.onGear(d.takeover);
        // R2 自证（P0-B）：进入 stock 档时打基线，只统计「stock 档内的增量」
        Hook.noteGearTransition(d.takeover);
        HookStatus.put("gear", d.gear.name());
        HookStatus.put("settingMode", d.settingMode);
        HookStatus.put("column", d.column);
        HookStatus.put("reason", d.reason);
        HookStatus.put("takeOver", d.takeover);
        HookStatus.put("targetRateId", d.targetRateId);
        return d;
    }

    private static Decision compute(long now) {
        int mode;
        boolean readable = true;
        try {
            android.content.ContentResolver cr = Sys.cr();
            if (cr == null) throw new IllegalStateException("no-context");
            mode = Settings.Secure.getInt(cr, "oplus_customize_screen_refresh_rate",
                    SETTING_MODE_AUTO);
        } catch (Throwable t) {
            // 读不到就当原生：绝不因为读失败而接管（修 K3）
            mode = Integer.MIN_VALUE;
            readable = false;
        }
        if (!readable) HookStatus.applied("settings-unreadable");

        boolean kill = killSwitch();
        boolean panel165 = RateMap.has165Panel();
        boolean native185 = RateMap.hasNative185Slot();
        boolean rateIdOk = RateMap.mapOk && RateMap.ID_165 > 0;
        // P1-9：`getDefaultRateId` 明确装不上 → 165 档上不去 165，整体退出接管（§8.1）
        boolean hooksOk = !HookStatus.failed("default-rate-id");

        Kind gear;
        if (mode == SETTING_MODE_165) gear = Kind.EXTREME_165;
        else if (mode == SETTING_MODE_EXTREME && !native185) gear = Kind.EXTREME_165;   // 8 收敛为 7
        else if (mode == SETTING_MODE_EXTREME) gear = Kind.EXTREME_185;
        else gear = Kind.STOCK;

        boolean takeover = !kill && panel165 && rateIdOk && hooksOk
                && gear == Kind.EXTREME_165;
        int target = gear == Kind.EXTREME_185 ? RateMap.ID_185
                : (takeover ? RateMap.ID_165 : RateMap.ID_120);

        String reason;
        if (kill) reason = "kill-switch";
        else if (!readable) reason = "settings-unreadable";
        else if (!rateIdOk) reason = "rateMap-unmapped";
        else if (!hooksOk) reason = "r3-unavailable";
        else if (!panel165) reason = "panel-no-165";
        else if (mode == SETTING_MODE_165) reason = "pick-7";
        else if (mode == SETTING_MODE_EXTREME && !native185) reason = "pick-8-collapsed";
        else if (mode == SETTING_MODE_EXTREME) reason = "pick-8-native";
        else if (modeColumn(mode) == 0) reason = "stock-unknown-or-auto";
        else reason = "stock";

        if (modeColumn(mode) == 0 && mode >= 0 && mode != SETTING_MODE_AUTO) {
            HookStatus.applied("unknown-mode");
        }
        return new Decision(mode, modeColumn(mode), gear, panel165, target, takeover, reason);
    }

    static boolean takeover() {
        return decide().takeover;
    }


    static int settingMode() {
        return decide().settingMode;
    }

    /** settings 进程专用：vendor 类不可见（mapOk=false），只按「档位 7 + 面板有 165」判断 UI 语义。 */
    static boolean uiTakeover() {
        int mode = readSettingModeRaw();
        return mode == SETTING_MODE_165 && RateMap.has165Panel();
    }

    static int readSettingModeRaw() {
        android.content.ContentResolver cr = Sys.cr();
        if (cr == null) return Integer.MIN_VALUE;
        try {
            return Settings.Secure.getInt(cr, "oplus_customize_screen_refresh_rate", SETTING_MODE_AUTO);
        } catch (Throwable t) {
            HookStatus.fail("read-setting-mode", t);
            return Integer.MIN_VALUE;
        }
    }

    /**
     * L3 运行时 kill switch（DESIGN §8.2）：`Settings.Global.refresh165_disable==1`。
     * 所有模块动作立即 pass-through，不改任何用户设置，无需重启。
     */
    static boolean killSwitch() {
        android.content.ContentResolver cr = Sys.cr();
        if (cr == null) return false;
        try {
            boolean kill = Settings.Global.getInt(cr, "refresh165_disable", 0) == 1;
            HookStatus.put("killSwitch", kill ? 1 : 0);
            return kill;
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * 用户开关 `refresh165_force`（1s 缓存）。**只代表用户意图**，
     * UI 勾选态/磁贴必须用它，不能用 {@link #force165()}（后者含白名单派生）。
     */
    static boolean userForce165() {
        long now = SystemClock.elapsedRealtime();
        if (now - sForceAt < 1000L) return sForce;
        sForceAt = now;
        boolean on = false;
        android.content.ContentResolver cr = Sys.cr();
        if (cr != null) {
            try {
                on = Settings.Global.getInt(cr, "refresh165_force", 0) == 1;
            } catch (Throwable t) {
                HookStatus.fail("read-force", t);
            }
        }
        sForce = on;
        HookStatus.put("force165", on ? 1 : 0);
        return on;
    }

    /**
     * 「等效 force」（lead 2026-10-25）：用户开关 **或**（takeover 且前台在白名单）。
     *
     * <p>目的：让白名单应用前台时走**与 force=1 完全相同**的投票/reserve/panel/SAM 链路
     * （那条路已实机验证可靠），退出应用后自然回 idle1。已配置的视频策略（含暂停态）在
     * `MinFpsState.targetHz()` 和 `Hook.want165()` 里先于全局 force，因此不会被顶成 165。
     * 自证：`forceSource=user|app|none`。
     */
    static boolean force165() {
        if (userForce165()) {
            publishSource("user");
            return true;
        }
        try {
            String pkg = PerApp.foregroundPkg();
            if (takeover() && interactiveCached() && !keyguardCached()
                    && pkg != null && !pkg.isEmpty()
                    && !PerApp.isLauncher(pkg) && PerApp.isWhitelisted(pkg)) {
                publishSource("app");
                return true;
            }
        } catch (Throwable t) {
            HookStatus.fail("force-app", t);
        }
        publishSource("none");
        return false;
    }

    private static volatile String sForceSource = "none";

    // B（P1）：息屏/锁屏状态用 1s 缓存，热路径不 binder；AOD/息屏时白名单不再钉 165
    private static volatile boolean sInteractive = true;
    private static volatile long sInteractiveAt;
    private static volatile boolean sKeyguard;
    private static volatile long sKeyguardAt;

    static boolean interactiveCached() {
        long now = SystemClock.elapsedRealtime();
        if (now - sInteractiveAt < 1000L) return sInteractive;
        sInteractiveAt = now;
        try {
            android.content.Context ctx = Sys.context();
            android.os.PowerManager pm = ctx == null ? null
                    : (android.os.PowerManager) ctx.getSystemService(android.content.Context.POWER_SERVICE);
            sInteractive = pm == null || pm.isInteractive();
        } catch (Throwable t) {
            HookStatus.fail("read-interactive", t);
        }
        HookStatus.put("interactive", sInteractive ? 1 : 0);
        return sInteractive;
    }

    static boolean keyguardCached() {
        long now = SystemClock.elapsedRealtime();
        if (now - sKeyguardAt < 1000L) return sKeyguard;
        sKeyguardAt = now;
        try {
            android.content.Context ctx = Sys.context();
            android.app.KeyguardManager km = ctx == null ? null
                    : (android.app.KeyguardManager) ctx.getSystemService(android.content.Context.KEYGUARD_SERVICE);
            sKeyguard = km != null && km.isKeyguardLocked();
        } catch (Throwable t) {
            HookStatus.fail("read-keyguard", t);
        }
        HookStatus.put("keyguard", sKeyguard ? 1 : 0);
        return sKeyguard;
    }

    private static void publishSource(String src) {
        if (src.equals(sForceSource)) return;
        sForceSource = src;
        HookStatus.put("forceSource", src);
    }

    static void invalidateForce() {
        sForceAt = 0;
    }

    /** dumpsys 用：绕过 1s 缓存重新读一次（避免字段是旧快照）。 */
    static boolean force165Fresh() {
        sForceAt = 0;
        return force165();
    }

    /** 档位/开关变化后立即失效，避免最长 400ms 的旧档判定（V5）。 */
    static void invalidate() {
        sDecisionAt = 0;
    }
}
