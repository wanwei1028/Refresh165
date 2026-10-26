package com.refresh165;

import android.content.ContentResolver;
import android.os.SystemClock;
import android.provider.Settings;
import java.lang.reflect.Method;
import java.util.concurrent.atomic.AtomicInteger;

final class MinFpsState {
    private static volatile int sTarget = -1;
    private static volatile Object sRus;
    private static volatile String sXml;
    private static volatile boolean sPending;
    private static volatile boolean sPanelDirty;
    private static volatile boolean sLastTakeover;
    private static volatile long sCollapseUntil;
    private static volatile long sLastResendAt;
    private static volatile long sFgSwitchAt;
    private static volatile String sLastReason = "init";
    private static volatile String sLastChange = "init";
    private static volatile String sTargetReason = "init";
    private static volatile String sTargetContext = "init";
    private static volatile int sTargetFixed = Integer.MIN_VALUE;
    private static volatile int sEnforceAttempt;
    private static volatile boolean sNudging;
    private static final AtomicInteger GENERATION = new AtomicInteger();
    private static final AtomicInteger RESENDS = new AtomicInteger();
    private static Runnable sRestoreMin;
    private static long sLastNudgeAt;

    private MinFpsState() { }

    static void markPanelDirty() {
        sPanelDirty = true;
        HookStatus.put("panelDirty", 1);
    }

    static void clearPanelDirty() {
        sPanelDirty = false;
        HookStatus.put("panelDirty", 0);
    }

    static void noteRus(Object instance, String xmlArg) {
        sRus = instance;
        sXml = xmlArg;
    }

    static int generation() { return GENERATION.get(); }

    static void noteFgSwitch() {
        sFgSwitchAt = SystemClock.elapsedRealtime();
        HookStatus.applied("fg-switch-event");
    }

    static void noteFgSwitchEffective() {
        long at = sFgSwitchAt;
        if (at == 0) return;
        sFgSwitchAt = 0;
        HookStatus.put("fgSwitchLagMs", SystemClock.elapsedRealtime() - at);
        HookStatus.put("fgSwitchLagEvidence", "sam-callback-only");
    }

    static void dispatchTargetChange(String why) {
        sLastChange = why;
        HookStatus.put("lastTargetChange", why);
        final int token = GENERATION.incrementAndGet();
        sEnforceAttempt = 0;
        // The next vendor feature-234 call must carry the pending edge even
        // when it happens before the main-handler retry runs.
        markPending();
        HookStatus.put("pendingSync", 1);
        schedule(0L, token, why);
        schedule(350L, token, why);
    }

    private static void schedule(long delay, final int token, final String why) {
        Sys.main().postDelayed(new Runnable() {
            @Override public void run() {
                if (token != generation()) return;
                restoreMin();
                if (!Gear.takeover() || token != generation() || sEnforceAttempt >= 3) return;
                sEnforceAttempt++;
                HookStatus.put("enforceRetry", sEnforceAttempt);
                try {
                    if (Hook.want165(targetHz())) ensurePeakFloor("target-change");
                    markPending();
                    requestResendNow("enforce-" + why);
                    Hook.nudgeFromEnforcer();
                    if (!Reapply.mode("enforce-" + why)
                            && Hook.want165(targetHz())) peakNudge(why);
                } catch (Throwable t) {
                    HookStatus.fail("enforce-dispatch", t);
                }
            }
        }, delay);
    }

    /** A3：临时改写的原值持久化键（进程被杀/重启后可自愈）。 */
    private static final String KEY_MIN_RESTORE = "refresh165_min_restore";

    static void peakNudge(final String why) {
        // 默认关闭：瞬态改 min_refresh_rate 会在恢复失败时留下脏值（曾出现 30），
        // 且与持久 min 下限冲突。需要时用 refresh165_min_nudge=1 打开。
        if (!minNudgeEnabled()) { HookStatus.stockPass("min-nudge"); return; }
        if (!Gear.takeover()) return;
        final ContentResolver cr = Sys.cr();
        if (cr == null) return;
        long now = SystemClock.elapsedRealtime();
        if (sNudging || now - sLastNudgeAt < 300L) return;
        try {
            final String original = Settings.System.getString(cr, "min_refresh_rate");
            final float old = original == null ? 0f : Float.parseFloat(original);
            int target = targetHz();
            float peak = Settings.System.getFloat(cr, "peak_refresh_rate", 0f);
            float upper = target > 0 ? target : peak;
            if (!Float.isFinite(old) || upper <= 0f || old >= upper) {
                HookStatus.put("minNudge", "unavailable");
                return;
            }
            final String temporary = Float.toString(upper);
            // A3：改之前先把原值持久化，恢复成功后删键；开机时若有残留键 → 自愈
            try {
                Settings.Global.putString(cr, KEY_MIN_RESTORE,
                        original == null ? "" : original);
                HookStatus.put("minRestoreKey", "saved");
            } catch (Throwable t) {
                HookStatus.fail("min-restore-persist", t);
            }
            if (!Settings.System.putString(cr, "min_refresh_rate", temporary)) {
                HookStatus.fail("min-nudge-write", "rejected");
                clearRestoreKey(cr);
                sNudging = false;
                return;
            }
            sNudging = true;
            sLastNudgeAt = now;
            HookStatus.put("minNudge", "temporary-floor");
            HookStatus.put("minNudgeReason", why);
            HookStatus.put("minNudgeHz", upper);
            sRestoreMin = new Runnable() {
                @Override public void run() {
                    if (sRestoreMin != this) return;
                    try {
                        restoreOnce(cr, original, temporary, 0);
                    } finally {
                        sRestoreMin = null;
                        sNudging = false;          // A3：try/finally 保证复位
                    }
                }
            };
            Sys.main().postDelayed(sRestoreMin, 250L);
        } catch (Throwable t) {
            sNudging = false;
            HookStatus.fail("min-nudge", t);
        }
    }

    /** A3：恢复一次；失败 300ms 后重试一次；恢复成功/被外部覆盖都清掉持久化键。 */
    private static void restoreOnce(final ContentResolver cr, final String original,
                                    final String temporary, final int attempt) {
        try {
            String current = Settings.System.getString(cr, "min_refresh_rate");
            if (!temporary.equals(current)) {
                HookStatus.put("minNudge", "superseded");
                clearRestoreKey(cr);
                return;
            }
            boolean restored = Settings.System.putString(cr, "min_refresh_rate", original);
            if (restored) {
                HookStatus.put("minNudge", "restored");
                clearRestoreKey(cr);
                return;
            }
            HookStatus.put("minNudge", "restore-rejected");
            if (attempt < 1) {
                HookStatus.put("minRestoreRetry", attempt + 1);
                Sys.main().postDelayed(new Runnable() {
                    @Override public void run() {
                        restoreOnce(cr, original, temporary, attempt + 1);
                    }
                }, 300L);
            } else {
                HookStatus.applied("min-restore-failed");
            }
        } catch (Throwable t) {
            HookStatus.fail("min-nudge-restore", t);
        }
    }

    private static void clearRestoreKey(ContentResolver cr) {
        try {
            Settings.Global.putString(cr, KEY_MIN_RESTORE, null);
            HookStatus.put("minRestoreKey", "cleared");
        } catch (Throwable t) {
            HookStatus.fail("min-restore-clear", t);
        }
    }

    /** A3：开机/prime 时若有残留键 → 把原值写回并把键删掉（自愈）。 */
    static void recoverMinRestore(String why) {
        ContentResolver cr = Sys.cr();
        if (cr == null) return;
        String saved;
        try {
            saved = Settings.Global.getString(cr, KEY_MIN_RESTORE);
        } catch (Throwable t) {
            HookStatus.fail("min-restore-read", t);
            return;
        }
        if (saved == null) {
            HookStatus.put("minRestoreKey", "none");
            return;
        }
        try {
            Settings.System.putString(cr, "min_refresh_rate",
                    saved.isEmpty() ? null : saved);
            clearRestoreKey(cr);
            HookStatus.applied("min-restore-recovered");
            HookStatus.put("minRestoreRecovered", why + ":" + saved);
        } catch (Throwable t) {
            HookStatus.fail("min-restore-recover", t);
        }
    }

    private static void restoreMin() {
        Runnable restore = sRestoreMin;
        if (restore != null) {
            Sys.main().removeCallbacks(restore);
            restore.run();
        }
    }

    static void ensurePeakFloor(String why) {
        if (!Gear.takeover() || !Hook.want165(targetHz())) return;
        ContentResolver cr = Sys.cr();
        if (cr == null) return;
        try {
            float peak = Settings.System.getFloat(cr, "peak_refresh_rate", 0f);
            if (peak >= 164.5f) return;
            if (Settings.System.putFloat(cr, "peak_refresh_rate", 165f)) {
                HookStatus.applied("peak-floor");
                HookStatus.put("peakFloorReason", why);
            } else {
                HookStatus.fail("peak-floor", "write-rejected");
            }
        } catch (Throwable t) {
            HookStatus.fail("peak-floor", t);
        }
    }

    static boolean inPeakNudge() { return sNudging; }
    static boolean peakNudgeActive() { return sNudging; }

    static void retryEnforce() {
        if (sEnforceAttempt < 3) schedule(0L, generation(), "sam-mismatch");
    }


    static void onCandidate(String pkg) { onCandidate(pkg, VideoFixed.effectiveRateId()); }

    static void onCandidate(String pkg, int fixedId) {
        int target = targetHz(fixedId);
        String context = PerApp.promotionContext(pkg);
        HookStatus.put("minFpsTarget", target);
        HookStatus.put("panelTarget", target > 0 ? target : "vendor-native");
        if (target == sTarget && fixedId == sTargetFixed && context.equals(sTargetContext)) return;
        sTarget = target;
        sTargetFixed = fixedId;
        sTargetContext = context;
        HookStatus.put("minFpsContext", context);
        dispatchTargetChange(sTargetReason);
    }

    static void onGear(boolean takeover) {
        boolean previous = sLastTakeover;
        sLastTakeover = takeover;
        if (previous && !takeover) collapse();
        else if (!previous && takeover) {
            sTarget = Integer.MIN_VALUE;
            dispatchTargetChange("gear");
        }
    }

    private static void collapse() {
        final int token = GENERATION.incrementAndGet();
        sTarget = -1;
        HookStatus.put("minFpsTarget", sTarget);
        HookStatus.put("panelTarget", "vendor-native");
        Sys.main().post(new Runnable() {
            @Override public void run() {
                restoreMin();
                if (token != generation() || !sPanelDirty) return;
                sCollapseUntil = SystemClock.elapsedRealtime() + 2000L;
                markPending();
                requestResendNow("collapse");
            }
        });
    }

    static synchronized boolean takeCollapse() {
        boolean active = sCollapseUntil > SystemClock.elapsedRealtime();
        sCollapseUntil = 0;
        return active;
    }

    static int targetHz() { return targetHz(VideoFixed.effectiveRateId()); }

    /** Mode to re-submit when the policy target is native (-1). */
    static int reapplyHz() {
        int target = targetHz();
        if (target > 0) return target;
        String pkg = VideoFixed.foregroundPkg();
        int nativeHz = PerApp.nativeRequestHz(pkg);
        return nativeHz > 0 && nativeHz < 165 ? nativeHz : -1;
    }

    static int targetHz(int fixedId) {
        if (!Gear.takeover()) { reason("stock"); return -1; }
        String pkg = VideoFixed.foregroundPkg();
        boolean blank = pkg == null || pkg.isEmpty();
        if (blank || PerApp.isLauncher(pkg)) {
            reason(blank ? "blank-fg" : "launcher");
            return Gear.force165() ? 165 : -1;
        }
        int floor = VideoFixed.adfrFloorHz();
        if (floor > 0) { reason("video-fixed"); return floor; }
        if (fixedId > 0) {
            float hz = VideoFixed.effectiveHz();
            if (hz > 0) { reason("video-fixed"); return (int) hz; }
        }
        if (VideoFixed.handles(pkg)) {
            reason("video-fixed-suspended");
            return -1;
        }
        // 口径 1（lead 裁定）：**显式白名单优先** —— 用户勾了就是强制 165，游戏/原生-only 也一样；
        // blocks165Promotion 只在其后作为「否决顶 165」的兜底。
        if (PerApp.checked(pkg)) { reason("whitelist"); return 165; }
        if (PerApp.blocks165Promotion(pkg)) {
            reason(PerApp.isGame(pkg) ? "game-native" : "native-only");
            return -1;
        }
        if (Gear.force165()) { reason("force"); return 165; }
        reason("idle1");
        return -1;
    }

    private static void reason(String why) {
        sTargetReason = why;
        HookStatus.put("targetReason", why);
    }

    static synchronized boolean takePending() {
        boolean pending = sPending;
        sPending = false;
        return pending;
    }

    static synchronized void markPending() { sPending = true; }

    static void requestResendNow(String why) {
        Object rus = sRus;
        String xml = sXml;
        if (rus == null || xml == null) {
            HookStatus.put("minFpsResend", "deferred:no-config");
            return;
        }
        try {
            Method method = Sys.method(rus.getClass(), "updateAdfrXmlConfig", String.class);
            if (method == null) {
                HookStatus.fail("minfps-resend", "no-method");
                return;
            }
            method.invoke(rus, xml);
            RESENDS.incrementAndGet();
            sLastReason = why;
            HookStatus.put("minFpsResend", "submitted:panel-unverified");
            if (targetHz() <= 0) clearPanelDirty();
        } catch (Throwable t) {
            HookStatus.fail("minfps-resend", t);
        }
    }

    static void requestResend(final String why) {
        long now = SystemClock.elapsedRealtime();
        if (now - sLastResendAt < 300L) return;
        sLastResendAt = now;
        final int token = generation();
        Sys.main().post(new Runnable() {
            @Override public void run() {
                if (token != generation()) return;
                requestResendNow(why);
                Hook.nudgeFromEnforcer();
            }
        });
    }

    static void primeAfterBoot() {
        recoverMinRestore("prime");
        onCandidate(PerApp.foregroundPkg());
    }


    static String statusLine() {
        return "minFpsTarget=" + sTarget + " resend=" + RESENDS.get()
                + " why=" + sLastReason + " oneShotMinRestore=" + sNudging;
    }

    /** 用户「锁定 165（关闭 LTPO）」= 持久 min_refresh_rate 下限，而不是瞬时抖动。 */
    static void applyForceFloor(boolean on) {
        ContentResolver cr = Sys.cr();
        if (cr == null) return;
        final String KEY = "refresh165_min_saved";
        try {
            if (on) {
                if (!Gear.takeover()) return;
                String saved = Settings.Global.getString(cr, KEY);
                if (saved == null) {
                    float cur = Settings.System.getFloat(cr, "min_refresh_rate", 1f);
                    // 历史瞬态抖动可能把 min 留成 30；恢复目标必须是「真正原生」的 1。
                    if (cur >= 29.5f && cur <= 30.5f) cur = 1f;
                    if (!(cur > 0f) || cur > 165f) cur = 1f;
                    Settings.Global.putString(cr, KEY, Float.toString(cur));
                    HookStatus.put("minFloorSaved", cur);
                }
                boolean ok = Settings.System.putFloat(cr, "min_refresh_rate", 165f);
                HookStatus.put("minFloor", ok ? "165" : "write-rejected");
            } else {
                String saved = Settings.Global.getString(cr, KEY);
                if (saved == null) { HookStatus.put("minFloor", "none"); return; }
                try {
                    float v = Float.parseFloat(saved);
                    Settings.System.putFloat(cr, "min_refresh_rate", v);
                    HookStatus.put("minFloor", "restored:" + v);
                } catch (Throwable t) {
                    HookStatus.fail("min-floor-restore", t);
                } finally {
                    Settings.Global.putString(cr, KEY, null);
                }
            }
        } catch (Throwable t) {
            HookStatus.fail("min-floor", t);
        }
    }

    private static boolean minNudgeEnabled() {
        try {
            ContentResolver cr = Sys.cr();
            if (cr == null) return false;
            return Settings.Global.getInt(cr, "refresh165_min_nudge", 0) == 1;
        } catch (Throwable t) {
            return false;
        }
    }

    /** 手动「锁定 165」开关专用：恢复 v1.0.5 的 peak 抖动，驱动才会重算 min_fps。 */
    static void forceToggleNudge() {
        final ContentResolver cr = Sys.cr();
        if (cr == null || !Gear.takeover()) return;
        try {
            final float orig = Settings.System.getFloat(cr, "peak_refresh_rate", 165f);
            final float base = orig >= 164.5f ? orig : 165f;
            float temp = base >= 165f ? 120f : 165f;
            Settings.System.putFloat(cr, "peak_refresh_rate", temp);
            HookStatus.put("forceNudge", "transient:" + temp);
            Sys.main().postDelayed(new Runnable() {
                @Override public void run() {
                    try {
                        Settings.System.putFloat(cr, "peak_refresh_rate", base);
                        HookStatus.put("forceNudge", "restored:" + base);
                    } catch (Throwable t) {
                        HookStatus.fail("force-nudge-restore", t);
                    }
                }
            }, 250L);
        } catch (Throwable t) {
            HookStatus.fail("force-nudge", t);
        }
    }
}
