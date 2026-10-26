package com.refresh165;

import android.os.SystemClock;
import android.view.Display;
import java.lang.reflect.Method;
import java.util.concurrent.atomic.AtomicInteger;

import com.refresh165.xp.XC_MethodHook;
import com.refresh165.xp.XposedBridge;
import com.refresh165.xp.XposedHelpers;

/**
 * 模式重下发原语（docs/MINFPS-DESIGN.md §2.3 方案 B / B2）。
 *
 * <p>A1（audit）：flip 的**回位不允许被 `!Gear.takeover()` 挡** —— 120 档正是最需要回位的场景；
 * 回位失败/无 mode/异常 → `reapplyRestoreFailed` 并在 500ms 内再试（最多 2 次），仍失败记
 * `reapplyStuck=<hz>`；`reapplyHz` 一律写真实值。
 */
final class Reapply {
    private static volatile Method sDispatch;
    private static final AtomicInteger REQUESTS = new AtomicInteger();
    private static final AtomicInteger GENERATION = new AtomicInteger();
    private static final long MIN_DISPATCH_INTERVAL_MS = 1000L;
    private static final ThreadLocal<Boolean> IN_DISPATCH = new ThreadLocal<Boolean>();
    private static volatile long sLastDispatchAt;
    private static volatile int sLastDispatchHz = -1;
    private static volatile int sLastDispatchGeneration = -1;
    private static volatile int sFlipCount;
    private static volatile int sRestoreFail;
    private static volatile int sRestoreStuck;
    private static volatile String sState = "none";
    private static volatile String sReason = "none";

    private Reapply() { }

    static void resetThrottle() { sLastDispatchAt = 0; }

    private static volatile boolean sForceNext;

    /** 强制下发（跳过 1s 节流），用于开关切换这类必须立即生效的场景。 */
    static boolean modeForce(String reason) {
        sForceNext = true;
        try { return mode(reason); } finally { sForceNext = false; }
    }

    static boolean mode(String reason) {
        return modeOf(-1, reason);
    }

    static boolean modeOf(int hz, final String reason) {
        if (!Gear.takeover()) {
            HookStatus.stockPass("reapply");      // A：120 档必须可见（不只写 KV）
            return false;
        }
        android.content.ContentResolver cr = Sys.cr();
        if (cr == null) return fail("no-context", reason);
        try {
            if (android.provider.Settings.Global.getInt(cr, "refresh165_reapply", 1) == 0) {
                HookStatus.put("reapplySwitch", "off");
                return false;
            }
        } catch (Throwable t) {
            return fail("settings-unavailable", reason);
        }
        final Object policy = PolicyRef.policy();
        final Method dispatch = sDispatch;
        if (policy == null || dispatch == null) return fail("no-method", reason);
        int requested = hz > 0 ? hz : MinFpsState.reapplyHz();
        final boolean nativeFallback = requested <= 0;
        final String foreground = PerApp.foregroundPkg();
        if (nativeFallback && PerApp.blocks165Promotion(foreground)) {
            return fail("native-context-without-rate", reason);
        }
        int resolved = requested;
        if (resolved <= 0) {
            float current = currentRate(policy);
            if (current <= 0 || Float.isNaN(current) || Float.isInfinite(current)) {
                return fail("no-native-target", reason);
            }
            resolved = Math.round(current);
        }
        final int target = resolved;
        if (target == 30) return fail("adfr-floor-only", reason);
        try {
            Object content = Reflect.field(policy, "mDisplayContent");
            Method sleeping = content == null ? null : Sys.method(content.getClass(), "isSleeping");
            if (sleeping != null && Boolean.TRUE.equals(sleeping.invoke(content))) {
                return fail("display-sleeping", reason);
            }
            final int targetGeneration = MinFpsState.generation();
            long now = SystemClock.elapsedRealtime();
            boolean forceToggle = sForceNext || (reason != null && reason.startsWith("force"));
            if (!forceToggle
                    && now - sLastDispatchAt < MIN_DISPATCH_INTERVAL_MS
                    && sLastDispatchHz == target && sLastDispatchGeneration == targetGeneration) {
                HookStatus.put("reapplyMode", "throttled");
                return true;
            }

            // 同 mode 重发会被 SF 合并 → 目标已是 165 时先下发「交替档」再回位。
            // H：双键兼容 —— 任一为 0 即禁用；自证 flipSwitch=on|off:key
            boolean flipMain;
            boolean flipFixed;
            try {
                flipMain = android.provider.Settings.Global.getInt(cr, "refresh165_flip", 0) != 0;   // 默认关闭：翻转会造成可见跳变
                flipFixed = android.provider.Settings.Global
                        .getInt(cr, "refresh165_flip_on_fixed", 0) != 0;
            } catch (Throwable t) {
                flipMain = false;
                flipFixed = false;
            }
            boolean flipEnabled = flipMain && flipFixed;
            HookStatus.put("flipSwitch", flipEnabled ? "on:both"
                    : (flipMain ? "off:refresh165_flip_on_fixed" : "off:refresh165_flip"));
            boolean forceFlip = reason != null && reason.startsWith("force");
            if (target >= 165 && (flipEnabled || forceFlip)
                    && (nativeFallback || currentRate(policy) >= 164.5f)) {
                final Display.Mode alternate = findAlternateMode(policy, target);
                final Display.Mode restore = findMode(policy, 165, null);
                if (alternate == null || restore == null) return fail("no-flip-mode", reason);
                final int preFlipHz = Math.round(currentRate(policy));
                final int altHz = Math.round(alternate.getRefreshRate());
                invokeDispatch(policy, dispatch, alternate);
                final int flipGeneration = GENERATION.incrementAndGet();
                sLastDispatchAt = now;
                sLastDispatchHz = target;
                sLastDispatchGeneration = targetGeneration;
                sFlipCount++;
                sState = "submitted-flip";
                HookStatus.applied("reapply");      // R-3：稳态 165 走 flip 时该 tag 也要动
                sReason = reason;
                HookStatus.put("reapplyCount", REQUESTS.incrementAndGet());
                HookStatus.put("reapplyMode", sState);
                HookStatus.put("reapplyErr", "none");
                HookStatus.put("reapplyHz", target);          // A1：真实值，不写 "native"
                HookStatus.put("reapplyFlip", sFlipCount);
                HookStatus.put("reapplyLastAt", SystemClock.elapsedRealtime());
                HookStatus.put("reapplyReason", reason);
                HookStatus.put("reapplyVerify", "pending-flip-restore");

                final Runnable[] restoreTask = new Runnable[1];
                final int[] attempts = {0};
                restoreTask[0] = new Runnable() {
                    @Override public void run() {
                        if (flipGeneration != GENERATION.get()) {
                            HookStatus.put("reapplyVerify", "flip-cancelled-new-target");
                            return;
                        }
                        // A1：**不检查** Gear.takeover() —— 120 档回位同样必须执行
                        int desired = desiredRestoreHz(policy, preFlipHz, altHz);
                        try {
                            Display.Mode liveMode = findMode(policy, desired, null);
                            if (liveMode == null && desired != 120) {
                                liveMode = findMode(policy, 120, null);   // 面板必有的安全档
                            }
                            if (liveMode == null) {
                                restoreFailed(desired, restoreTask[0], attempts);
                                return;
                            }
                            invokeDispatch(policy, dispatch, liveMode);
                            sState = "submitted-flip-restored";
                            HookStatus.put("reapplyMode", sState);
                            int restoredHz = Math.round(liveMode.getRefreshRate());
                            HookStatus.put("reapplyHz", restoredHz);
                            float current = currentRate(policy);
                            HookStatus.put("reapplyVerifyRate", current);
                            boolean ok = Math.abs(current - restoredHz) < 0.5f;
                            HookStatus.put("reapplyVerify", ok
                                    ? "policy-match:panel-unverified"
                                    : "policy-mismatch:" + current + "!=" + restoredHz);
                            if (ok) HookStatus.put("reapplyRestoreFailed", "none");
                            else restoreFailed(restoredHz, restoreTask[0], attempts);
                        } catch (Throwable t) {
                            HookStatus.fail("reapply-flip-restore", t);
                            restoreFailed(desired, restoreTask[0], attempts);
                        }
                    }
                };
                Sys.main().postDelayed(restoreTask[0],
                        (reason != null && reason.startsWith("force")) ? 40L : 180L);
                return true;
            }
            return dispatchSingle(policy, dispatch, target, reason, now, targetGeneration);
        } catch (Throwable t) {
            HookStatus.fail("reapply-call", t);
            return fail("exception", reason);
        }
    }

    /** 单次 mode 下发（flip 之外的常规路径）+ 300ms 读回校验。 */
    private static boolean dispatchSingle(Object policy, final Method dispatch, final int target,
                                          String reason, long now, int targetGeneration) {
        try {
            Display.Mode mode = findMode(policy, target, null);
            if (mode == null) return fail("no-compatible-mode", reason);
            final int generation = GENERATION.incrementAndGet();
            invokeDispatch(policy, dispatch, mode);
            sLastDispatchAt = now;
            sLastDispatchHz = target;
            sLastDispatchGeneration = targetGeneration;
            sState = "submitted";
            sReason = reason;
            HookStatus.put("reapplyCount", REQUESTS.incrementAndGet());
            HookStatus.applied("reapply");                // A：成功必须计 tag
            HookStatus.put("reapplyMode", sState);
            HookStatus.put("reapplyErr", "none");
            HookStatus.put("reapplyHz", target);
            HookStatus.put("reapplyLastAt", SystemClock.elapsedRealtime());
            HookStatus.put("reapplyReason", reason);
            HookStatus.put("reapplyVerify", "pending-policy-readback");
            Sys.main().postDelayed(new Runnable() {
                @Override public void run() {
                    if (generation != GENERATION.get()
                            || targetGeneration != MinFpsState.generation()) return;
                    float current = currentRate(policy);        // mCurrentPickRate 是 Hz（实机 165.0）
                    HookStatus.put("reapplyVerifyRate", current);
                    HookStatus.put("reapplyVerify", current < 0f ? "unverified:no-field"
                            : (Math.abs(current - target) < 0.5f ? "policy-match:panel-unverified"
                            : "policy-mismatch:" + current + "!=" + target));
                }
            }, 300L);
            return true;
        } catch (Throwable t) {
            HookStatus.fail("reapply-call", t);
            return fail("exception", reason);
        }
    }

    /** A1：回位目标 Hz —— 目标值优先；否则当前原生档；最后 165（takeover 内）/120（120 档）。 */
    private static int desiredRestoreHz(Object policy, int preFlipHz, int altHz) {
        int desired = MinFpsState.reapplyHz();
        if (desired > 0 && Math.abs(desired - altHz) >= 0.5f) return desired;
        // 关键：回位目标**不能**等于交替档，否则「165→120→144」会把 120 档留在 144
        if (preFlipHz > 0 && Math.abs(preFlipHz - altHz) >= 0.5f) return preFlipHz;
        float cur = currentRate(policy);
        if (cur > 0f && cur < 164.5f && Math.abs(cur - altHz) >= 0.5f) return Math.round(cur);
        return Gear.takeover() ? 165 : 120;
    }

    /** A1：回位失败 → 计数 + 500ms 内重试（最多 2 次），再失败记 reapplyStuck。 */
    private static void restoreFailed(final int hz, final Runnable self, final int[] attempts) {
        sRestoreFail++;
        HookStatus.put("reapplyRestoreFailed", sRestoreFail);
        HookStatus.put("reapplyRestoreHz", hz);
        if (attempts[0] < 2) {
            attempts[0]++;
            HookStatus.put("reapplyRestoreRetry", attempts[0]);
            Sys.main().postDelayed(self, 500L);
        } else {
            sRestoreStuck++;
            HookStatus.put("reapplyStuck", hz);
            HookStatus.applied("reapply-stuck");
        }
    }

    private static void invokeDispatch(Object policy, Method dispatch, Display.Mode mode)
            throws Throwable {
        IN_DISPATCH.set(Boolean.TRUE);
        try {
            dispatch.invoke(policy, mode, null);
        } finally {
            IN_DISPATCH.remove();
        }
    }

    private static float currentRate(Object policy) {
        Object value = Reflect.field(policy, "mCurrentPickRate");
        if (!(value instanceof Number)) value = Reflect.field(policy, "mActiveRenderFrameRate");
        return value instanceof Number ? ((Number) value).floatValue() : -1f;
    }

    private static Display.Mode findAlternateMode(Object policy, int target) {
        // 不用 144：翻转窗口只有 ~180ms，144 会形成可见的帧率跳变；优先 120/90/60
        int[] candidates = target >= 165 ? new int[]{120, 90, 60} : new int[]{165, 120, 90, 60};
        for (int hz : candidates) {
            Display.Mode mode = findMode(policy, hz, null);
            if (mode != null && Math.abs(mode.getRefreshRate() - target) >= 0.5f) return mode;
        }
        return null;
    }

    private static Display.Mode findMode(Object policy, int hz, Display.Mode source) {
        Object info = Reflect.field(policy, "mDisplayInfo");
        Object value = Reflect.field(info, "supportedModes");
        if (!(value instanceof Display.Mode[])) return null;
        int width = source == null ? Reflect.intField(info, "logicalWidth", -1) : source.getPhysicalWidth();
        int height = source == null ? Reflect.intField(info, "logicalHeight", -1) : source.getPhysicalHeight();
        if (width <= 0 || height <= 0) return null;
        for (Display.Mode mode : (Display.Mode[]) value) {
            if (mode == null || Math.abs(mode.getRefreshRate() - hz) >= 0.5f) continue;
            int w = mode.getPhysicalWidth(), h = mode.getPhysicalHeight();
            if ((w == width && h == height) || (source == null && w == height && h == width)) return mode;
        }
        return null;
    }

    static void installDispatchHook(ClassLoader cl) throws Throwable {
        Class<?> policy = XposedHelpers.findClass(
                "com.android.server.wm.OplusRefreshRatePolicyImpl", cl);
        Method method = Sys.method(policy, "setDisplayConfigSpecs",
                Display.Mode.class, StringBuilder.class);
        int hooks = 0;
        if (method != null) {
            sDispatch = method;
            hooks += XposedBridge.hookMethodCount(method, new DispatchHook());
        }
        String[] alternates = {"setDisplayMode", "setDisplayModeId", "updateDisplayMode"};
        for (String name : alternates) {
            Method cand = Sys.method(policy, name, Display.Mode.class, StringBuilder.class);
            if (cand == null) continue;
            if (sDispatch == null) sDispatch = cand;
            hooks += XposedBridge.hookMethodCount(cand, new DispatchHook());
        }
        HookStatus.put("modeDispatchHooks", hooks);
        if (hooks > 0) HookStatus.installed("mode-dispatch");
        else HookStatus.fail("mode-dispatch", "hook-rejected");
    }

    private static final class DispatchHook extends XC_MethodHook {
        @Override protected void beforeHookedMethod(MethodHookParam p) {
            try {
                if (Boolean.TRUE.equals(IN_DISPATCH.get())) {
                    HookStatus.stockPass("mode-dispatch-internal");
                    return;
                }
                PolicyRef.setPolicy(p.thisObject);
                if (p.thisObject != PolicyRef.policy() || !Gear.takeover()) {
                    HookStatus.stockPass("mode-dispatch");
                    return;
                }
                if (p.args == null || p.args.length != 2 || !(p.args[0] instanceof Display.Mode)) return;
                int target = MinFpsState.targetHz();
                // PanelRate / final-mode 负责控制中心场景，这里不与它们抢同一个 mode
                if (target == 30) {
                    HookStatus.stockPass("mode-dispatch");
                    return;
                }
                // 165 档内普通应用/桌面：保留厂商 boost，但把它的目标改成 165
                if (target <= 0) {
                    // 165 档内：boost 可能早于 fg-switch 事件到达，此时 want165 仍为 false。
                    // 因此这里不再依赖转场窗口，只保留「游戏/native-only/固定帧率」排除。
                    if (VideoFixed.blocksGlobalPromotion()
                            || PerApp.blocks165Promotion(PerApp.foregroundPkg())) {
                        HookStatus.stockPass("mode-dispatch");
                        return;
                    }
                    target = 165;
                }
                // D2：进入计数放在闸门**之后**（120 档不再增长）
                HookStatus.applied("mode-dispatch-entered");
                Display.Mode original = (Display.Mode) p.args[0];
                if (Math.abs(original.getRefreshRate() - target) < 0.5f) {
                    HookStatus.put("modeDispatchSkip", "already-target");
                    return;
                }
                Display.Mode replacement = findMode(p.thisObject, target, original);
                if (replacement == null) {
                    HookStatus.put("modeDispatchSkip", "no-compatible-mode");
                    return;
                }
                p.args[0] = replacement;
                HookStatus.put("modeDispatchFrom", original.getRefreshRate());
                HookStatus.put("modeDispatchTo", replacement.getRefreshRate());
                HookStatus.applied("mode-dispatch-arguments");
            } catch (Throwable t) {
                HookStatus.fail("mode-dispatch-runtime", t);
            }
        }

        @Override protected void afterHookedMethod(MethodHookParam p) {
            if (p.getThrowable() != null) HookStatus.fail("mode-dispatch-runtime", p.getThrowable());
        }
    }

    private static boolean fail(String why, String reason) {
        sState = "unverified";
        sReason = reason;
        HookStatus.put("reapplyMode", sState);
        HookStatus.put("reapplyErr", why);
        HookStatus.put("reapplyReason", reason);
        return false;
    }

    static String statusLine() {
        return "reapplyMode=" + sState + " requests=" + REQUESTS.get()
                + " reason=" + sReason + " flips=" + sFlipCount
                + " restoreFail=" + sRestoreFail + " stuck=" + sRestoreStuck
                + " evidence=policy-only";
    }
}
