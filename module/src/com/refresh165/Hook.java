package com.refresh165;

import android.app.KeyguardManager;
import android.content.Context;
import android.os.Build;
import com.refresh165.xp.IXposedHookLoadPackage;
import com.refresh165.xp.XC_MethodHook;
import com.refresh165.xp.XposedBridge;
import com.refresh165.xp.XposedHelpers;
import com.refresh165.xp.callbacks.XC_LoadPackage;
import java.io.File;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.Set;

/**
 * Refresh165 4.15 candidate - system-side Bilibili view-override exemption, no fixed playback rate.
 *
 * V1 rewrote every high-refresh SurfaceControl request to [0, 165] and cleared
 * every DisplayModeDirector app request. That made video applications lose their
 * legitimate 24/30/60 Hz votes and caused repeated 165 <-> low-rate switching.
 *
 * This version deliberately does neither of those things. It removes the two vendor
 * clamps that translate the user's native rate id 7 (165 Hz) to id 3 (120 Hz):
 *
 *   OplusRefreshRatePolicyImpl.forceMaxRefreshRateSettings(7) -> 7
 *   OplusRefreshRateConfigs.getDefaultRateId(7)               -> 7
 *
 * App/content/video votes remain intact. The module unlocks the native 165-Hz
 * setting but otherwise follows each application's requested frame rate.
 */
// No module logs or file writes. Status is printed only on an explicit dumpsys query.
public final class Hook implements IXposedHookLoadPackage {
    private static final String SYSTEM_PACKAGE = "android";
    private static final String TARGET_MODEL = "PLK110";
    private static final int RATE_ID_165 = 7;

    private static volatile int selectedSettingMode = -1;
    private static final Set<String> STOCK_LOW_RATE_PACKAGES = new HashSet<>(Arrays.asList(
            // C.61 adfr_to_hw list
            "tv.danmaku.bili",
            "com.hunantv.imgo.activity",
            "com.ss.android.article.video",
            "com.tencent.qqlive",
            "com.qiyi.video",
            "com.sina.weibo",
            // C.61 short-video/content-idle list
            "com.ss.android.ugc.aweme",
            "com.ss.android.ugc.aweme.lite",
            "com.ss.android.ugc.live",
            "com.smile.gifmaker",
            "com.kuaishou.nebula",
            "com.tencent.wemeet.app",
            "com.sankuai.meituan"));

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lp) {
        android.util.Log.i("Refresh165", "load pkg=" + lp.packageName);
        if ("com.android.settings".equals(lp.packageName)) {
            hookSettingsPage(lp.classLoader);
            return;
        }
        if (!SYSTEM_PACKAGE.equals(lp.packageName) && !"system".equals(lp.packageName)) {
            return;
        }
        // 不限制机型：非 OPPO 设备上各 Hook 找不到类会自行跳过，不会影响系统。

        rateMapCompatible = hasExpectedRateMap(lp.classLoader);
        if (rateMapCompatible) {
            hookVendorRateClamp(lp.classLoader);
            hookDefaultRateMapping(lp.classLoader);
            hookDefaultWindowRate(lp.classLoader);
            removeDcRateCap(lp.classLoader);
            hookOrdinaryAnimationBoost(lp.classLoader);
            SystemViewPolicy.install(lp.classLoader);
        }
        preserveFullBrightnessDc(lp.classLoader);
        allowLowBrightnessDcIdle(lp.classLoader);
        hookNativeReserveTable(lp.classLoader);
        hookLockScreen165(lp.classLoader);
        boostLauncherVote(lp.classLoader);
        persistRefreshSetting(lp.classLoader);
        forceMinFps165(lp.classLoader);
        hookPanelFeatureArray(lp.classLoader);
        installForceObserver();
        // boostAnimationVotes165(lp.classLoader);  // updateVoteLocked 是投票唯一入口，注入 165 会打乱 getBestRequest 排序，导致整体掉 90
    }

    private static volatile int lockScreen165Count;
    private static volatile int forceAllCount;
    private static volatile int pickFixed;
    private static volatile int labelFixed;
    private static volatile int launcherVoteCount;
    private static volatile boolean refreshSettingApplied;
    private static volatile int forceMinFpsCount;
    private static volatile int panelForceApplied;
    private static volatile int panelArraySeen;
    private static volatile int panelVerSeq;
    private static volatile Object nativeRusInstance;
    private static volatile String nativeLastXmlArg;
    private static volatile boolean forceObserverInstalled;
    private static volatile int animation165Count;
    private static volatile boolean rateMapCompatible;
    /** Verify the rate-id semantics we use, not the firmware's marketing version. */
    private static boolean hasExpectedRateMap(ClassLoader cl) {
        try {
            Class<?> utils = XposedHelpers.findClass("com.android.server.wm.OplusRefreshRateUtils", cl);
            int[] ids = {1, 2, 3, 7};
            float[] rates = {90f, 60f, 120f, 165f};
            for (int i = 0; i < ids.length; i++) {
                Object value = XposedHelpers.callStaticMethod(utils, "getRefreshRateById", ids[i]);
                if (!(value instanceof Number)
                        || !(Math.abs(((Number)value).floatValue() - rates[i]) < 0.1f)) return false;
            }
            return true;
        } catch (Throwable ignored) { return false; }
    }

    private static final int[] ORIGINAL_RESERVE_TABLE =
            {10, 33, 33, 165, 33, 165, 33, 165, 33, 165, 33, 0, 0};
    /** 关闭 LTPO：reserve 下限全部 165，等于固定 165Hz 不降帧。 */
    private static final int[] FORCE_165_RESERVE_TABLE =
            {165,165,165,165,165,165,165,165,165,165};
    private static final int[] LOW_IDLE_RESERVE_TABLE =
            {10, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 0, 0};
    private static volatile boolean nativeTableHookInstalled;
    private static volatile int nativeTableCalls, nativeTablePatched;
    private static volatile String nativeTableStatus = "not-called";
    private static final ThreadLocal<ArrayDeque<Object[]>> nativeTableScope = new ThreadLocal<>();

    /**
     * The OEM builds feature234's 225 integers here at boot and on RUS updates.
     * Allow idle1 across reserve brightness/gray bins; leave active/app requests intact and copy the reserve
     * table, then restore its parsed model. No XML writes or direct HAL calls.
     */
    private static volatile long force165CheckedAt;
    private static volatile boolean force165Cached;

    /** 读取磁贴开关状态（refresh165_force），1 秒缓存避免高频反射。 */
    private static boolean isForce165() {
        long now = android.os.SystemClock.elapsedRealtime();
        if (now - force165CheckedAt < 1000L) {
            return force165Cached;
        }
        force165CheckedAt = now;
        boolean on = false;
        try {
            // system_server 里 currentApplication() 为 null，必须走 currentActivityThread().getSystemContext()
            Object thread = XposedHelpers.callStaticMethod(
                    XposedHelpers.findClass("android.app.ActivityThread", null),
                    "currentActivityThread");
            Object ctx = thread == null ? null
                    : XposedHelpers.callMethod(thread, "getSystemContext");
            if (ctx instanceof android.content.Context) {
                on = android.provider.Settings.Global.getInt(
                        ((android.content.Context) ctx).getContentResolver(),
                        "refresh165_force", 0) == 1;
            }
        } catch (Throwable ignored) { }
        force165Cached = on;
        return on;
    }

    private static void hookNativeReserveTable(ClassLoader cl) {
        try {
            Class<?> rus = XposedHelpers.findClass(
                    "com.android.server.display.feature.panel.hwdpfeatures.OplusHwDisplayRusAdfr", cl);
            Method update = rus.getDeclaredMethod("updateAdfrXmlConfig", String.class);
            if (update.getReturnType() != Void.TYPE) return;
            XposedBridge.hookMethod(update, new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam p) {
                    ArrayDeque<Object[]> stack = nativeTableScope.get();
                    if (stack == null) { stack = new ArrayDeque<>(); nativeTableScope.set(stack); }
                    Object[] frame = new Object[3];
                    stack.push(frame);
                    nativeRusInstance = p.thisObject;
                    if (p.args != null && p.args.length == 1 && p.args[0] instanceof String) {
                        nativeLastXmlArg = (String) p.args[0];
                    }
                    nativeTableCalls++;
                    nativeTableStatus = "unknown-context";
                    if (p.args == null || p.args.length != 1 || !(p.args[0] instanceof String)) return;
                    Object config = field(p.thisObject, "mAdfrXmlConfig");
                    if (!Integer.valueOf(0).equals(field(config, "mPanelId"))
                            || !Integer.valueOf(1).equals(field(config, "mEnable"))
                            || !Integer.valueOf(165).equals(field(config, "mReserveMode"))) {
                        nativeTableStatus = "unverified-config-preserved"; return;
                    }
                    Object value = field(config, "mReserveMinfpslevel1");
                    if (!(value instanceof int[])) { nativeTableStatus = "table-unavailable"; return; }
                    int[] original = (int[]) value;
                    if (Arrays.equals(original, LOW_IDLE_RESERVE_TABLE)) {
                        nativeTableStatus = "already-adjusted"; return;
                    }
                    if (!Arrays.equals(original, ORIGINAL_RESERVE_TABLE)) {
                        nativeTableStatus = "unverified-table-preserved"; return;
                    }
                    int[] replacement = (isForce165() ? FORCE_165_RESERVE_TABLE
                                                      : LOW_IDLE_RESERVE_TABLE).clone();
                    if (!writeField(config, "mReserveMinfpslevel1", replacement)) {
                        nativeTableStatus = "field-write-unavailable"; return;
                    }
                    frame[0] = config; frame[1] = original; frame[2] = replacement;
                    nativeTablePatched++;
                    nativeTableStatus = "supplying-native-idle1-profile";
                }
                @Override protected void afterHookedMethod(MethodHookParam p) {
                    ArrayDeque<Object[]> stack = nativeTableScope.get();
                    if (stack == null || stack.isEmpty()) return;
                    Object[] frame = stack.pop();
                    try {
                        if (frame[0] == null) return;
                        // Do not overwrite a newer model update from another caller.
                        if (field(frame[0], "mReserveMinfpslevel1") != frame[2]) {
                            nativeTableStatus = "model-changed-during-send"; return;
                        }
                        if (!writeField(frame[0], "mReserveMinfpslevel1", frame[1])) {
                            nativeTableStatus = "model-restore-failed"; return;
                        }
                        nativeTableStatus = p.getThrowable() == null
                                ? "send-returned-model-restored" : "send-threw-model-restored";
                    } finally { if (stack.isEmpty()) nativeTableScope.remove(); }
                }
            });
            nativeTableHookInstalled = true;
        } catch (Throwable ignored) { nativeTableStatus = "hook-unavailable"; }
    }

    // Only explicit dumpsys queries print this status; no continuous log output.
    private static volatile String lowStatus = "not-called";
    private static volatile int lowConfigCalls, lowApplied, lowOutgoingHw;
    private static volatile boolean lowHookInstalled, boostHookInstalled;
    private static Object lowLastManager;
    private static boolean lowLastEligible;
    private static final ThreadLocal<ArrayDeque<Object[]>> lowConfigScope = new ThreadLocal<>();
    private static final ThreadLocal<ArrayDeque<Boolean>> boostScope = new ThreadLocal<>();

    private static void allowLowBrightnessDcIdle(ClassLoader cl) {
        try {
            Class<?> manager = XposedHelpers.findClass("com.oplus.vrr.OPlusExternalRefreshRateManager", cl);
            Method config = manager.getDeclaredMethod("handleTouchIdleConfig", Boolean.TYPE);
            if (config.getReturnType() != Void.TYPE) return;
            XposedBridge.hookMethod(config, new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam p) {
                    ArrayDeque<Object[]> frames = lowConfigScope.get();
                    if (frames == null) { frames = new ArrayDeque<>(); lowConfigScope.set(frames); }
                    Object[] frame = new Object[]{p.thisObject, Boolean.FALSE};
                    frames.push(frame);
                    lowConfigCalls++;
                    lowStatus = "context-unavailable";
                    Object lock = field(p.thisObject, "mStateLock");
                    if (lock == null || p.args == null || p.args.length != 1
                            || !(p.args[0] instanceof Boolean)) return;
                    synchronized (lock) {
                        lowStatus = lowIdleReason(p.thisObject);
                        if (!"ordinary-30nit".equals(lowStatus)) return;
                        if (!Boolean.FALSE.equals(field(p.thisObject, "mTouchIdleHwBacklightEnough"))) {
                            lowStatus = "already-eligible-or-unknown"; return;
                        }
                        if (writeField(p.thisObject, "mTouchIdleHwBacklightEnough", Boolean.TRUE)) {
                            frame[1] = Boolean.TRUE;
                            lowApplied++;
                            lowStatus = "configuration-evaluated-with-lowlight-eligible";
                        } else lowStatus = "field-write-unavailable";
                    }
                }
                @Override protected void afterHookedMethod(MethodHookParam p) {
                    ArrayDeque<Object[]> frames = lowConfigScope.get();
                    if (frames == null || frames.isEmpty()) return;
                    Object[] frame = frames.pop();
                    try {
                        if (Boolean.TRUE.equals(frame[1])) {
                            Object lock = field(frame[0], "mStateLock");
                            if (lock != null) synchronized (lock) {
                                if (!writeField(frame[0], "mTouchIdleHwBacklightEnough", Boolean.FALSE))
                                    lowStatus = "restore-failed";
                            }
                        }
                    } finally { if (frames.isEmpty()) lowConfigScope.remove(); }
                }
            });
            lowHookInstalled = true;
            // Re-evaluate on mode/darkness/backlight changes too. Otherwise an
            // outgoing low-light configuration could outlive the DC selection.
            XC_MethodHook recheck = new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam p) {
                    if (p.getThrowable() != null) return;
                    Object lock = field(p.thisObject, "mStateLock");
                    if (lock == null) return;
                    synchronized (lock) {
                        boolean eligible = "ordinary-30nit".equals(lowIdleReason(p.thisObject));
                        if (lowLastManager == p.thisObject && lowLastEligible == eligible) return;
                        lowLastManager = p.thisObject;
                        lowLastEligible = eligible;
                        try { XposedHelpers.callMethod(p.thisObject, "handleTouchIdleConfig", Boolean.FALSE); }
                        catch (Throwable ignored) { lowStatus = "recheck-unavailable"; }
                    }
                }
            };
            XposedBridge.hookAllMethods(manager, "handleBacklightChange", recheck);
            XposedBridge.hookAllMethods(manager, "setDynamicTeStatusFromEpChange", recheck);
            XposedBridge.hookAllMethods(manager, "setDarkMode", recheck);
        } catch (Throwable ignored) { lowStatus = "hook-unavailable"; }
        // Observe the outgoing hardware-idle bit; this is NOT proof of driver acceptance.
        try {
            Class<?> controller = XposedHelpers.findClass("com.oplus.vrr.OPlusSurfaceFlingerController", cl);
            Class<?> bean = XposedHelpers.findClass("com.oplus.vrr.bean.TouchIdleBean", cl);
            XposedBridge.hookMethod(controller.getDeclaredMethod("setTouchIdleConfig", bean), new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam p) {
                    ArrayDeque<Object[]> frames = lowConfigScope.get();
                    if (frames != null && !frames.isEmpty() && Boolean.TRUE.equals(frames.peek()[1])
                            && p.args != null && p.args.length == 1
                            && Boolean.TRUE.equals(field(p.args[0], "mHwEnable"))) lowOutgoingHw++;
                }
            });
        } catch (Throwable ignored) { }
        try {
            Class<?> manager = XposedHelpers.findClass("com.oplus.vrr.OPlusExternalRefreshRateManager", cl);
            XposedBridge.hookMethod(manager.getDeclaredMethod("dump", java.io.PrintWriter.class, String[].class), new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam p) {
                    if (p.args == null || p.args.length != 2 || !(p.args[0] instanceof java.io.PrintWriter)
                            || !(p.args[1] instanceof String[])) return;
                    String[] args = (String[]) p.args[1];
                    if (args.length != 1 || !"refresh165-status".equals(args[0])) return;
                    ((java.io.PrintWriter) p.args[0]).println("Refresh165 4.15 candidate: lowHook=" + lowHookInstalled
                            + " lowConfigCalls=" + lowConfigCalls + " lowApplied=" + lowApplied
                            + " outgoingHw=" + lowOutgoingHw + " lowStatus=" + lowStatus
                            + " boostHook=" + boostHookInstalled + " mvt=stock firmwareGate=none rateMapCompatible=" + rateMapCompatible
                            + " nativeTableHook=" + nativeTableHookInstalled
                            + " nativeTableCalls=" + nativeTableCalls + " nativeTablePatched=" + nativeTablePatched
                            + " nativeTableStatus=" + nativeTableStatus + SystemViewPolicy.status());
                }
            });
        } catch (Throwable ignored) { }
    }

    private static String lowIdleReason(Object manager) {
        Object nits = field(manager, "mNits");
        if (!(nits instanceof Float) || !((Float)nits >= 0f && (Float)nits < 30f)) return "not-low-or-unknown";
        if (!Integer.valueOf(2).equals(field(manager, "mCurrentEPMode"))
                || !Boolean.TRUE.equals(field(manager, "mIsGameDcFpsEnable"))
                || !Boolean.FALSE.equals(field(manager, "mPwmMode"))) return "not-full-dc";
        if (!Boolean.FALSE.equals(field(manager, "mIsDarkMode"))) return "dark-mode-policy-preserved";
        Object bean = field(manager, "mTouchIdleBean");
        if (!Integer.valueOf(30).equals(field(bean, "mNitLimit"))
                || !Integer.valueOf(-1).equals(field(bean, "mBrightnessLimit"))) return "different-brightness-policy";
        Object limits = field(bean, "mSpecialHwNitLimitMap");
        Object pkg = field(manager, "mCurPkgName");
        if (!(limits instanceof java.util.Map) || !(pkg instanceof String) || ((String)pkg).isEmpty()) return "unknown-app-policy";
        if (((java.util.Map<?,?>)limits).containsKey(pkg)) return "special-app-limit-preserved";
        try {
            Object histogramLimit = XposedHelpers.callMethod(manager, "getMaxNitFromOtiKpMaps");
            if (histogramLimit != null && (!(histogramLimit instanceof Float)
                    || !((Float)histogramLimit >= 0f && (Float)histogramLimit <= 30f)))
                return "higher-or-unknown-histogram-limit-preserved";
        } catch (Throwable ignored) { return "histogram-policy-unavailable"; }
        return "ordinary-30nit";
    }

    private static boolean writeField(Object obj, String name, Object value) {
        if (obj == null) return false;
        for (Class<?> c = obj.getClass(); c != null; c = c.getSuperclass()) {
            try { Field f = c.getDeclaredField(name); f.setAccessible(true); f.set(obj, value); return true; }
            catch (NoSuchFieldException ignored) { }
            catch (Throwable ignored) { return false; }
        }
        return false;
    }

    /**
     * This verified vendor handler only writes EP mode 0 after a 144/165-Hz
     * conflict. Skip that reset while the user's full-brightness mode is active.
     * Do not change the feature flag: it also controls the native DC dispatch.
     * Do not recheck Hz here: a queued reset can arrive after a mode transition.
     */
    /**
     * 锁屏/系统界面自身请求 120（PRIORITY_WINDOW_REQUEST），导致锁屏停在 120。
     * 只在该窗口 picked==120 时改成 165，不动 mCurrentPickRate，不影响配置层/自动模式。
     */
    /**
     * PRIORITY_LAUNCHER_REQUEST(85) 在后台滑动/回桌面动画期间投 120，
     * 优先级高于窗口请求(0)与动画(40/90)，导致"划到一半"掉 120。
     * 这里只把 85 这条 120 投票换成 (165,60)：峰值 165、最低档仍 60，不顶死 ADFR。
     */
    private static void boostLauncherVote(ClassLoader cl) {
        try {
            Class<?> core = XposedHelpers.findClass(
                    "com.android.server.wm.OplusRefreshRateCore", cl);
            final Class<?> voteCls = XposedHelpers.findClass(
                    "com.android.server.wm.OplusRefreshRateCore$Vote", cl);
            final java.lang.reflect.Method forRate = voteCls.getDeclaredMethod(
                    "forRefreshRate", float.class, float.class);
            XposedBridge.hookAllMethods(core, "updateVoteLocked", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam p) {
                    if (p.args == null || p.args.length != 2 || !(p.args[0] instanceof Integer)
                            || p.args[1] == null) {
                        return;
                    }
                    int votePrio = ((Integer) p.args[0]).intValue();
                    // 85 = launcher 请求；130 = 指纹(FOD_SHOW)。两者都会把 120 顶上来。
                    if (votePrio != 85 && votePrio != 130) {
                        return;
                    }
                    Object rate = field(p.args[1], "mRefreshRate");
                    if (!(rate instanceof Float) || ((Float) rate).floatValue() != 120.0f) {
                        return;
                    }
                    try {
                        p.args[1] = forRate.invoke(null, Float.valueOf(165f), Float.valueOf(60f));
                        launcherVoteCount++;
                    } catch (Throwable ignored) { }
                }
            });
        } catch (Throwable ignored) { }
    }

    /**
     * 重启后 oplus_customize_screen_refresh_rate 会被重置为 0（智能），
     * 而智能模式在静止场景会被压到 90。这里在 system_server 起来后
     * 把档位写回 8（=165 档）；若用户手动改成了其它非 0 值则尊重用户。
     */
    /**
     * 在「设置 → 显示 → 屏幕刷新率」页面加一行开关：
     * 「锁定 165Hz（关闭 LTPO）」。状态写 Settings.Global.refresh165_force。
     */
    /**
     * 开关打开时把 ADFR 的最低帧直接顶到 165：钩 OPlusBackLightStrategy.getSAMinFpsByNit，
     * 这是 vendor 计算 saMinFps 的最终取点（165Hz 模式下原厂甚至匹配不到表、返回 0）。
     */
    /**
     * 开关变化时让 ADFR 重新下发一次配置：updateAdfrXmlConfig 只在特定事件被调用，
     * 光改 Settings 不会重发，所以这里保存实例+参数，开关一变就主动再调一次。
     */
    private static volatile Object policyInstance;

    /**
     * 让 vendor 立刻重算一次刷新率/min_fps，避免"开关后必须锁屏一次"。
     * 优先调用 OplusRefreshRatePolicyImpl.updateCurrentSettingMode(mode, true)；
     * 若不可用，退化到 peak_refresh_rate 抖动（会短暂经过 120，但无锁屏）。
     */
    private static void nudgeRefreshRate() {
        final Object policy = policyInstance;
        final int mode = selectedSettingMode;
        try {
            if (policy != null) {
                java.lang.reflect.Method m = policy.getClass().getDeclaredMethod(
                        "updateCurrentSettingMode", int.class, boolean.class);
                m.setAccessible(true);
                m.invoke(policy, Integer.valueOf(mode), Boolean.TRUE);
                android.util.Log.i("Refresh165", "nudge via updateCurrentSettingMode mode=" + mode);
                // 实测该调用不足以让 ADFR 重算 min_fps，继续走 peak 抖动兜底
                
            }
        } catch (Throwable t) {
            android.util.Log.w("Refresh165", "nudge direct failed: " + t);
        }
        // 退化：peak 抖动
        try {
            Object thread = XposedHelpers.callStaticMethod(
                    XposedHelpers.findClass("android.app.ActivityThread", null),
                    "currentActivityThread");
            final Object ctx = thread == null ? null
                    : XposedHelpers.callMethod(thread, "getSystemContext");
            if (!(ctx instanceof android.content.Context)) return;
            final android.content.ContentResolver cr =
                    ((android.content.Context) ctx).getContentResolver();
            final int orig = android.provider.Settings.System.getInt(
                    cr, "peak_refresh_rate", 165);
            // 选一个与当前不同的临时峰值，保证确实触发一次重算
            final int tmpPeak = orig >= 165 ? 120 : 165;
            android.provider.Settings.System.putInt(cr, "peak_refresh_rate", tmpPeak);
            new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(
                    new Runnable() {
                        @Override public void run() {
                            try {
                                android.provider.Settings.System.putInt(
                                        cr, "peak_refresh_rate", orig);
                                android.util.Log.i("Refresh165", "nudge via peak toggle " + orig);
                            } catch (Throwable ignored) { }
                        }
                    }, 200L);
        } catch (Throwable ignored) { }
    }

    private static void resendAdfrConfig() {
        Object inst = nativeRusInstance;
        String arg = nativeLastXmlArg;
        if (inst == null || arg == null) {
            android.util.Log.w("Refresh165", "resend skipped: inst=" + inst + " arg=" + arg);
            return;
        }
        try {
            Class<?> cls = inst.getClass();
            java.lang.reflect.Method m = cls.getDeclaredMethod("updateAdfrXmlConfig", String.class);
            m.setAccessible(true);
            m.invoke(inst, arg);
            android.util.Log.i("Refresh165", "resendAdfrConfig ok, force=" + isForce165());
        } catch (Throwable t) {
            android.util.Log.e("Refresh165", "resend failed: " + t);
        }
    }

    private static void installForceObserver() {
        if (forceObserverInstalled) return;
        forceObserverInstalled = true;
        // system_server 早期 getSystemContext() 还是 null，必须延迟重试到可用为止。
        Thread t = new Thread(new Runnable() {
            @Override public void run() {
                for (int i = 0; i < 60; i++) {
                    try {
                        Object thread = XposedHelpers.callStaticMethod(
                                XposedHelpers.findClass("android.app.ActivityThread", null),
                                "currentActivityThread");
                        final Object ctx = thread == null ? null
                                : XposedHelpers.callMethod(thread, "getSystemContext");
                        if (ctx instanceof android.content.Context) {
                            android.database.ContentObserver obs =
                                    new android.database.ContentObserver(
                                            new android.os.Handler(
                                                    android.os.Looper.getMainLooper())) {
                                        @Override public void onChange(boolean selfChange) {
                                            force165CheckedAt = 0;
                                            android.util.Log.i("Refresh165",
                                                    "observer fired, force=" + isForce165());
                                            pendingApply = true;
                                            resendAdfrConfig();
                                            nudgeRefreshRate();
                                        }
                                    };
                            ((android.content.Context) ctx).getContentResolver()
                                    .registerContentObserver(
                                            android.provider.Settings.Global
                                                    .getUriFor("refresh165_force"),
                                            false, obs);
                            // 档位纠偏：8 = 185 档，本机表没有第 5 段会回落 120，直接改回 7
                            android.database.ContentObserver pickObs =
                                    new android.database.ContentObserver(
                                            new android.os.Handler(
                                                    android.os.Looper.getMainLooper())) {
                                        @Override public void onChange(boolean selfChange) {
                                            try {
                                                android.content.ContentResolver cr2 =
                                                        ((android.content.Context) ctx)
                                                                .getContentResolver();
                                                int v = android.provider.Settings.Secure.getInt(
                                                        cr2,
                                                        "oplus_customize_screen_refresh_rate", 0);
                                                if (v == 8) {
                                                    android.provider.Settings.Secure.putInt(
                                                            cr2,
                                                            "oplus_customize_screen_refresh_rate",
                                                            7);
                                                    pickFixed++;
                                                    android.util.Log.i("Refresh165",
                                                            "pick 8 -> 7 corrected");
                                                }
                                            } catch (Throwable ignored) { }
                                        }
                                    };
                            ((android.content.Context) ctx).getContentResolver()
                                    .registerContentObserver(
                                            android.provider.Settings.Secure.getUriFor(
                                                    "oplus_customize_screen_refresh_rate"),
                                            false, pickObs);
                            android.util.Log.i("Refresh165",
                                    "force observer installed at attempt " + i);
                            return;
                        }
                    } catch (Throwable th) {
                        android.util.Log.e("Refresh165", "observer retry " + i + ": " + th);
                    }
                    try { Thread.sleep(2000L); } catch (InterruptedException e) { return; }
                }
                android.util.Log.e("Refresh165", "force observer gave up");
            }
        }, "Refresh165Observer");
        t.setDaemon(true);
        t.start();
    }

    private static volatile boolean pendingApply;

    /**
     * 唯一真正把 ADFR 参数送到 HWC 的地方：setDisplayPanelFeatureValueArray(234, modes)。
     * 布局（见 OplusHwDisplayRusAdfr.updateAdfrXmlConfig）：
     *   modes[1]        = version
     *   modes[48..86]   = minfps120/90/60 三张表
     *   modes[186..215] = reserveMinfps level1/2/3
     * 开关打开时把这些字段全部顶成 165，并抬 version 让 HWC 真正重新应用。
     */
    private static void hookPanelFeatureArray(ClassLoader cl) {
        try {
            Class<?> helper = XposedHelpers.findClass(
                    "com.android.server.display.util.OplusDisplayPanelFeatureHelper", cl);
            XposedBridge.hookAllMethods(helper, "setDisplayPanelFeatureValueArray",
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam p) {
                            if (p.args == null || p.args.length != 2) return;
                            if (!(p.args[0] instanceof Integer) || !(p.args[1] instanceof int[])) return;
                            if (((Integer) p.args[0]).intValue() != 234) return;
                            int[] modes = (int[]) p.args[1];
                            if (modes.length < 216) return;
                            boolean force = isForce165();
                            if (panelArraySeen++ < 3) {
                                android.util.Log.i("Refresh165", "panel array id=234 len="
                                        + modes.length + " force=" + force
                                        + " ver=" + modes[1]);
                            }
                            if (force) {
                                for (int i = 48; i <= 86; i++) modes[i] = 165;
                                for (int i = 186; i <= 215; i++) modes[i] = 165;
                                panelForceApplied++;
                            }
                            // 版本号必须严格递增，否则 HWC 认为配置没变直接丢弃。
                            if (force || pendingApply) {
                                panelVerSeq++;
                                modes[1] = modes[1] + panelVerSeq;
                                android.util.Log.i("Refresh165", "panel apply ver="
                                        + modes[1] + " force=" + force + " seq=" + panelVerSeq);
                            }
                            pendingApply = false;
                        }
                    });
            android.util.Log.i("Refresh165", "panel feature array hooked");
        } catch (Throwable t) {
            android.util.Log.e("Refresh165", "hookPanelFeatureArray failed: " + t);
        }
    }

    private static void forceMinFps165(ClassLoader cl) {
        try {
            Class<?> strat = XposedHelpers.findClass(
                    "com.oplus.vrr.OPlusBackLightStrategy", cl);
            XposedBridge.hookAllMethods(strat, "getSAMinFpsByNit", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam p) {
                    if (!isForce165()) return;
                    Object r = p.getResult();
                    int cur = r instanceof Number ? ((Number) r).intValue() : 0;
                    if (cur < 165) {
                        p.setResult(Integer.valueOf(165));
                        forceMinFpsCount++;
                    }
                }
            });
            android.util.Log.i("Refresh165", "forceMinFps165 hooked");
        } catch (Throwable t) {
            android.util.Log.e("Refresh165", "forceMinFps165 failed: " + t);
        }
    }

    private static void hookSettingsPage(ClassLoader cl) {
        try {
            android.util.Log.i("Refresh165", "hookSettingsPage enter cl=" + (cl != null));
            Class<?> frag = XposedHelpers.findClass(
                    "com.oplus.settings.feature.display.ScreenRefreshRateFragment", cl);
            android.util.Log.i("Refresh165", "fragment found: " + frag.getName());
            // 文案修正：本机没有 persist.performance.rapid.refresh_rate.level，
            // getExtremeRefreshLevelRate() 返回 0，refreshRateSettingSummary 会走 <=120 分支
            // 把「刷新率设定」渲染成 120 Hz。这里只把入参抬到 (165,165)，
            // 让设置页用它自己的「极速高刷」标签渲染；实际刷新率与 LTPO 不受影响。
            try {
                final java.lang.reflect.Method summary = frag.getDeclaredMethod(
                        "refreshRateSettingSummary", int.class, int.class);
                XposedBridge.hookMethod(summary, new XC_MethodHook() {
                    @Override protected void beforeHookedMethod(MethodHookParam mp) {
                        if (mp.args == null || mp.args.length != 2) return;
                        if (!(mp.args[0] instanceof Integer) || !(mp.args[1] instanceof Integer)) return;
                        int extreme = ((Integer) mp.args[0]).intValue();
                        if (extreme <= 120) {
                            mp.args[0] = Integer.valueOf(165);
                            mp.args[1] = Integer.valueOf(165);
                            labelFixed++;
                        }
                    }
                });
                android.util.Log.i("Refresh165", "refresh rate label hook installed");
            } catch (Throwable t) {
                android.util.Log.e("Refresh165", "label hook failed: " + t);
            }
            XposedBridge.hookAllMethods(frag, "onCreate", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam p) {
                    try {
                        // 只处理目标 Fragment 本身（适配层会沿继承链挂到基类，必须过滤）
                        if (!frag.isInstance(p.thisObject)) return;
                        Object screen = XposedHelpers.callMethod(p.thisObject, "getPreferenceScreen");
                        if (screen == null) return;
                        // 已存在就不重复添加
                        try {
                            Object exist = XposedHelpers.callMethod(screen, "findPreference",
                                    "refresh165_lock");
                            if (exist != null) return;
                        } catch (Throwable ignored) { }
                        Object ctxObj = XposedHelpers.callMethod(p.thisObject, "getContext");
                        if (!(ctxObj instanceof android.content.Context)) return;
                        final android.content.Context ctx = (android.content.Context) ctxObj;

                        // COUI 控件才会套用 ColorOS 的卡片样式；androidx 的那个会错位
                        Class<?> swCls = null;
                        String[] candidates = {
                                "com.coui.appcompat.preference.COUISwitchPreference",
                                "com.coui.appcompat.preference.COUISwitchPreferenceCompat",
                                "androidx.preference.SwitchPreferenceCompat",
                                "androidx.preference.SwitchPreference"
                        };
                        for (String cn : candidates) {
                            try {
                                swCls = XposedHelpers.findClass(cn, cl);
                                android.util.Log.i("Refresh165", "switch class = " + cn);
                                break;
                            } catch (Throwable ignored) { }
                        }
                        if (swCls == null) throw new ClassNotFoundException("no switch preference");
                        final Object sw = swCls.getConstructor(android.content.Context.class)
                                .newInstance(ctx);
                        XposedHelpers.callMethod(sw, "setKey", "refresh165_lock");
                        XposedHelpers.callMethod(sw, "setTitle", "锁定 165Hz（关闭 LTPO）");
                        XposedHelpers.callMethod(sw, "setSummary",
                                "开启后始终保持165Hz，切换立即生效");
                        XposedHelpers.callMethod(sw, "setChecked",
                                android.provider.Settings.Global.getInt(
                                        ctx.getContentResolver(), "refresh165_force", 0) == 1);

                        Class<?> listenerCls = XposedHelpers.findClass(
                                "androidx.preference.Preference$OnPreferenceChangeListener", cl);
                        Object listener = java.lang.reflect.Proxy.newProxyInstance(
                                cl, new Class<?>[]{listenerCls},
                                new java.lang.reflect.InvocationHandler() {
                                    @Override public Object invoke(Object proxy,
                                            java.lang.reflect.Method method, Object[] args) {
                                        if ("onPreferenceChange".equals(method.getName())
                                                && args != null && args.length == 2) {
                                            boolean on = Boolean.TRUE.equals(args[1]);
                                            android.provider.Settings.Global.putInt(
                                                    ctx.getContentResolver(),
                                                    "refresh165_force", on ? 1 : 0);
                                            android.util.Log.i("Refresh165",
                                                    "settings switch -> " + on);
                                        }
                                        return Boolean.TRUE;
                                    }
                                });
                        XposedHelpers.callMethod(sw, "setOnPreferenceChangeListener", listener);
                        Object group = null;
                        try {
                            group = XposedHelpers.callMethod(screen, "findPreference",
                                    "refresh_rate_category");
                        } catch (Throwable ignored) { }
                        try {
                            XposedHelpers.callMethod(sw, "setOrder", Integer.valueOf(40));
                        } catch (Throwable ignored) { }
                        if (group != null) {
                            XposedHelpers.callMethod(group, "addPreference", sw);
                        } else {
                            XposedHelpers.callMethod(screen, "addPreference", sw);
                        }
                        android.util.Log.i("Refresh165", "settings switch row added");
                    } catch (Throwable t) {
                        android.util.Log.e("Refresh165", "settings switch failed: " + t);
                    }
                }
            });
        } catch (Throwable t) {
            android.util.Log.e("Refresh165", "hookSettingsPage failed: " + t);
        }
    }

    private static void persistRefreshSetting(ClassLoader cl) {
        // 不依赖 SystemServer 生命周期（LSPosed 注入常常晚于 startOtherServices），
        // 直接在 system_server 进程内起一条一次性任务：开机后把"智能"改回 165 档。
        try {
            Thread t = new Thread(new Runnable() {
                @Override public void run() {
                    for (int i = 0; i < 12; i++) {
                        try {
                            Thread.sleep(10000L);
                        } catch (InterruptedException e) {
                            return;
                        }
                        try {
                            Object thread = XposedHelpers.callStaticMethod(
                                    XposedHelpers.findClass("android.app.ActivityThread", null),
                                    "currentActivityThread");
                            Object ctx = thread == null ? null
                                    : XposedHelpers.callMethod(thread, "getSystemContext");
                            if (!(ctx instanceof android.content.Context)) continue;
                            android.content.ContentResolver cr =
                                    ((android.content.Context) ctx).getContentResolver();
                            int cur = android.provider.Settings.Secure.getInt(
                                    cr, "oplus_customize_screen_refresh_rate", 0);
                            if (cur == 8) {
                                android.provider.Settings.Secure.putInt(
                                        cr, "oplus_customize_screen_refresh_rate", 7);
                                refreshSettingApplied = true;
                                continue;
                            }
                            if (cur == 0) {
                                // 7 在本固件是"高刷新率"档；Hook 会把 getDefaultRateId(7)
                                // 改写成 rateId 7(165)。写 8 会因原厂表只有 4 段而越界。
                                android.provider.Settings.Secure.putInt(
                                        cr, "oplus_customize_screen_refresh_rate", 7);
                                refreshSettingApplied = true;
                                android.util.Log.i("Refresh165",
                                        "persist: 0 -> 7 at attempt " + i);
                                return;
                            }
                            if (cur != 0) {
                                // 用户/系统已明确选了档位，尊重它，停止轮询。
                                android.util.Log.i("Refresh165", "persist: keep " + cur);
                                return;
                            }
                        } catch (Throwable ignored) { }
                    }
                }
            }, "Refresh165Persist");
            t.setDaemon(true);
            t.start();
        } catch (Throwable ignored) { }
    }

    private static void hookLockScreen165(ClassLoader cl) {
        try {
            Class<?> policy = XposedHelpers.findClass(
                    "com.android.server.wm.OplusRefreshRatePolicyImpl", cl);
            XposedBridge.hookAllMethods(policy, "getWindowPreferredModeId", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam p) {
                    if (p.args == null || p.args.length < 1 || !(p.args[0] instanceof Number)) {
                        return;
                    }
                    // 锁定开关打开时：任何窗口请求（含视频自己发的 60）一律 165。
                    if (isForce165()) {
                        int picked0 = ((Number) p.args[0]).intValue();
                        if (picked0 != RATE_ID_165) {
                            p.args[0] = Integer.valueOf(RATE_ID_165);
                            forceAllCount++;
                        }
                        return;
                    }
                    if (((Number) p.args[0]).intValue() != 3) {
                        return;
                    }
                    // 锁屏时焦点窗口是 NotificationShade，mFocusedApp 仍是 launcher，
                    // 按包名匹配不可靠，直接用 KeyguardManager 判断锁屏状态。
                    Object ctxObj = field(p.thisObject, "mContext");
                    if (!(ctxObj instanceof Context)) {
                        return;
                    }
                    KeyguardManager km = (KeyguardManager) ((Context) ctxObj)
                            .getSystemService(Context.KEYGUARD_SERVICE);
                    if (km != null && km.isKeyguardLocked()) {
                        p.args[0] = Integer.valueOf(RATE_ID_165);
                        lockScreen165Count++;
                    }
                }
            });
        } catch (Throwable ignored) { }
    }

    /**
     * 动画/启动动画走 PRIORITY_ANIMATION_BOOST(40) / START_ANIMATION_BOOST(90)，
     * 与窗口请求是两条独立通道。仅在 165 设置下把这两档的 120 投票换成 165。
     * 温控80/AOD121/指纹130/游戏25/应用10 全部不动。
     */
    private static void boostAnimationVotes165(ClassLoader cl) {
        try {
            Class<?> core = XposedHelpers.findClass(
                    "com.android.server.wm.OplusRefreshRateCore", cl);
            Class<?> voteCls = XposedHelpers.findClass(
                    "com.android.server.wm.OplusRefreshRateCore$Vote", cl);
            // 用 (refreshRate, minRate) 版本：动画把峰值拉到 165，但最低档仍是 60，
            // 避免用 forRefreshRate(int) 时 min==max 把 ADFR 决策顶死。
            final java.lang.reflect.Method forRate = voteCls.getDeclaredMethod(
                    "forRefreshRate", float.class, float.class);
            XposedBridge.hookAllMethods(core, "updateVoteLocked", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam p) {
                    if (p.args == null || p.args.length != 2 || !(p.args[0] instanceof Integer)
                            || p.args[1] == null) {
                        return;
                    }
                    int prio = ((Integer) p.args[0]).intValue();
                    if (prio != 40 && prio != 90) {
                        return;
                    }
                    Object rate = field(p.args[1], "mRefreshRate");
                    if (!(rate instanceof Float) || ((Float) rate).floatValue() != 120.0f) {
                        return;
                    }
                    try {
                        p.args[1] = forRate.invoke(null, Float.valueOf(165f), Float.valueOf(60f));
                        animation165Count++;
                    } catch (Throwable ignored) { }
                }
            });
        } catch (Throwable ignored) { }
    }

    private static void preserveFullBrightnessDc(ClassLoader cl) {
        try {
            Class<?> manager = XposedHelpers.findClass(
                    "com.oplus.vrr.OPlusExternalRefreshRateManager", cl);
            Method reset = manager.getDeclaredMethod("handleDcModeStatus");
            if (reset.getReturnType() != Void.TYPE) {
                return;
            }
            XposedBridge.hookMethod(reset, new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam p) {
                    if (p.args == null || p.args.length != 0) {
                        return;
                    }
                    Object lock = field(p.thisObject, "mStateLock");
                    if (lock == null) {
                        return;
                    }
                    synchronized (lock) {
                        Object mode = field(p.thisObject, "mCurrentEPMode");
                        Object enabled = field(p.thisObject, "mIsGameDcFpsEnable");
                        if (Integer.valueOf(2).equals(mode) && Boolean.TRUE.equals(enabled)) {
                            p.setResult(null);
                        }
                    }
                }
            });
        } catch (Throwable ignored) {
            // Unknown builds/classes keep their original behavior; no module logs.
        }
    }

    /** Remove only the DC controller's rate cap, not thermal or power votes. */
    private static void removeDcRateCap(ClassLoader cl) {
        try {
            Class<?> dc = XposedHelpers.findClass(
                    "com.android.server.wm.OplusRefreshRateDCController", cl);
            XposedBridge.hookAllMethods(dc, "setLimitMaxRefreshRateId", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam p) {
                    if (p.args != null && p.args.length == 1 && p.args[0] instanceof Integer) {
                        // Zero is the vendor's existing no-cap sentinel.
                        p.args[0] = Integer.valueOf(0);
                    }
                }
            });
            XposedBridge.hookAllMethods(dc, "getDCLimitRefreshRateId", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam p) {
                    if (p.getThrowable() == null && p.args != null && p.args.length == 1
                            && p.args[0] instanceof Integer) {
                        p.setResult(p.args[0]);
                    }
                }
            });
        } catch (Throwable ignored) {
            // No logging; leave any unavailable vendor entry unmodified.
        }
    }

    private static boolean unlockSetting(int mode) {
        return mode == 0 || mode == RATE_ID_165;
    }

    /**
     * ColorOS stores only four per-app setting columns. Rate ids 3/4/7 all use
     * column 3, whose ordinary default is id 3 (120 Hz). Promote that candidate
     * immediately before the vendor converts it to a Display.Mode. Explicit
     * rate ids 1/2 (90/60 Hz), thermal votes and later video votes are untouched.
     */
    private static void hookDefaultWindowRate(ClassLoader cl) {
        try {
            Class<?> policy = XposedHelpers.findClass(
                    "com.android.server.wm.OplusRefreshRatePolicyImpl", cl);
            XposedBridge.hookAllMethods(policy, "getWindowPreferredModeId",
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam p) {
                            if (p.args == null || p.args.length < 1
                                    || !(p.args[0] instanceof Number)) {
                                return;
                            }
                            int picked = ((Number) p.args[0]).intValue();
                            // Skip reflection entirely for non-120 candidates or other settings.
                            Object observer = field(p.thisObject, "mSettingsObserver");
                            Object modeValue = field(observer, "mCurrentPickMode");
                            int mode = modeValue instanceof Number
                                    ? ((Number) modeValue).intValue() : selectedSettingMode;
                            if (picked != 3 || !unlockSetting(mode)) {
                                return;
                            }
                            Object picker = field(p.thisObject, "mPickRefreshRateData");
                            String pkg = candidatePackage(picker);
                            if (pkg.isEmpty() || STOCK_LOW_RATE_PACKAGES.contains(pkg)
                                    || (candidateWindowRequestRate(picker) != 0
                                        && !("com.android.launcher".equals(pkg) && candidateWindowRequestRate(picker) == 3))
                                    || explicitUserOverrideRate(p.thisObject, pkg, mode) != 0) {
                                return;
                            }
                            p.args[0] = Integer.valueOf(RATE_ID_165);
                        }
                    });
        } catch (Throwable ignored) {
            // Preserve system behavior if a vendor hook cannot be installed.
        }
    }

    private static String candidatePackage(Object picker) {
        if (picker == null) {
            return "";
        }
        try {
            Object value = XposedHelpers.callMethod(picker, "candidateWinPkgName");
            return value == null ? "" : String.valueOf(value);
        } catch (Throwable t) {
            return "";
        }
    }

    private static int candidateWindowRequestRate(Object picker) {
        if (picker == null) {
            return -1;
        }
        try {
            Object value = XposedHelpers.callMethod(picker, "candidateWinReqRateId");
            return value instanceof Number ? ((Number) value).intValue() : -1;
        } catch (Throwable t) {
            return -1;
        }
    }

    /** Preserve actual setting-column overrides; -1 means lookup unavailable. */
    private static int explicitUserOverrideRate(Object policy, String pkg, int mode) {
        if (policy == null || pkg == null || pkg.isEmpty()) {
            return -1;
        }
        try {
            Object configs = field(policy, "mConfigs");
            if (configs == null) {
                return -1;
            }
            Object data = XposedHelpers.callMethod(
                    configs, "getPreferredRefreshRateData", pkg, null);
            if (data == null) {
                return 0;
            }
            Object value = XposedHelpers.callMethod(
                    data, "getOverridePreferredRefreshRateId", Integer.valueOf(mode == 0 ? 0 : 3));
            return value instanceof Number ? ((Number) value).intValue() : -1;
        } catch (Throwable t) {
            // Fail safe: preserve the stock candidate when its source is unknown.
            return 3;
        }
    }

    private static int settingMode(Object policy) {
        Object mode = field(field(policy, "mSettingsObserver"), "mCurrentPickMode");
        return mode instanceof Integer ? (Integer)mode : selectedSettingMode;
    }

    /** 动画不需要门槛：任何 priority 40/90 的 120 请求都提升到 165。 */
    private static boolean ordinaryBoostContext(Object policy, String target) {
        return true;
    }

    private static void pushBoost(boolean eligible) {
        ArrayDeque<Boolean> frames = boostScope.get();
        if (frames == null) { frames = new ArrayDeque<>(); boostScope.set(frames); }
        frames.push(eligible);
    }
    private static void popBoost() {
        ArrayDeque<Boolean> frames = boostScope.get();
        if (frames != null && !frames.isEmpty()) frames.pop();
        if (frames == null || frames.isEmpty()) boostScope.remove();
    }
    private static boolean insideOrdinaryBoost() {
        ArrayDeque<Boolean> frames = boostScope.get();
        return frames != null && Boolean.TRUE.equals(frames.peek());
    }

    /** Promote before the OEM's final vote resolution, never rewrite its result. */
    private static void hookOrdinaryAnimationBoost(ClassLoader cl) {
        try {
            Class<?> policy = XposedHelpers.findClass("com.android.server.wm.OplusRefreshRatePolicyImpl",cl);
            final Class<?> utils = XposedHelpers.findClass("com.android.server.wm.OplusRefreshRateUtils",cl);
            XposedBridge.hookAllMethods(policy,"startAppSwitchBoost",new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam p) {
                    boolean eligible = false;
                    if (p.args != null && p.args.length == 1) {
                        Object pkg = field(p.args[0],"packageName");
                        if (pkg instanceof String && ordinaryBoostContext(p.thisObject,(String)pkg)) {
                            try { eligible = Boolean.FALSE.equals(XposedHelpers.callMethod(p.thisObject,"isActivityDisableBoostForHome",p.args[0])); }
                            catch (Throwable ignored) { }
                        }
                    }
                    pushBoost(eligible);
                }
                @Override protected void afterHookedMethod(MethodHookParam p) { popBoost(); }
            });
            // Only the launcher's ordinary animation priority40, never priority85
            // (85 can outrank thermal80), game requests, or application video votes.
            XposedBridge.hookAllMethods(policy,"requestRefreshRate",new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam p) {
                    boolean eligible = false;
                    // 40=普通动画, 90=启动动画(START_ANIMATION_BOOST)。
                    // 之前只认 40 且 type==1/rate==120，导致打开应用的启动动画仍停在 90/120。
                    boolean animPrio = p.args != null && p.args.length == 4
                            && (Integer.valueOf(40).equals(p.args[0])
                                || Integer.valueOf(90).equals(p.args[0]))
                            && p.args[2] instanceof Float;
                    if (animPrio && ordinaryBoostContext(p.thisObject,"com.android.launcher")) {
                        try {
                            Object source=XposedHelpers.callStaticMethod(utils,"getRequestSource",field(p.thisObject,"mContext"));
                            eligible=source instanceof String && ((String)source).matches("com\\.android\\.launcher[0-9]+");
                        } catch(Throwable ignored) { }
                    }
                    pushBoost(eligible);
                    if (eligible) p.args[2]=Float.valueOf(165f);
                }
                @Override protected void afterHookedMethod(MethodHookParam p) { popBoost(); }
            });
            XposedBridge.hookAllMethods(policy,"getLauncherRateId",new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam p) {
                    if (insideOrdinaryBoost() && p.getThrowable()==null && Integer.valueOf(3).equals(p.getResult())
                            && p.args!=null && p.args.length==1
                            && (Integer.valueOf(3).equals(p.args[0]) || Integer.valueOf(7).equals(p.args[0]))) p.setResult(7);
                }
            });
            XposedBridge.hookAllMethods(policy,"adjustRefreshForDefaultMax",new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam p) {
                    if (insideOrdinaryBoost() && p.getThrowable()==null && Integer.valueOf(3).equals(p.getResult())
                            && p.args!=null && p.args.length==2 && "limit appSwitchBoost".equals(p.args[1])
                            && (Integer.valueOf(3).equals(p.args[0]) || Integer.valueOf(7).equals(p.args[0]))) p.setResult(7);
                }
            });
            boostHookInstalled=true;
        } catch(Throwable ignored) { }
    }

    private static void hookVendorRateClamp(ClassLoader cl) {
        try {
            Class<?> policy = XposedHelpers.findClass(
                    "com.android.server.wm.OplusRefreshRatePolicyImpl", cl);
            XposedBridge.hookAllMethods(policy, "forceMaxRefreshRateSettings",
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam p) {
                            if (p.args == null || p.args.length != 1
                                    || !(p.args[0] instanceof Number)) {
                                return;
                            }
                            int requested = ((Number) p.args[0]).intValue();
                            selectedSettingMode = requested;
                            policyInstance = p.thisObject;
                            if (requested != RATE_ID_165) {
                                return;
                            }
                            p.setResult(Integer.valueOf(RATE_ID_165));
                        }
                    });
        } catch (Throwable ignored) {
            // Preserve system behavior if a vendor hook cannot be installed.
        }
    }

    private static void hookDefaultRateMapping(ClassLoader cl) {
        try {
            Class<?> configs = XposedHelpers.findClass(
                    "com.android.server.wm.OplusRefreshRateConfigs", cl);
            XposedBridge.hookAllMethods(configs, "getDefaultRateId", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam p) {
                    if (p.args == null || p.args.length != 1
                            || !(p.args[0] instanceof Number)) {
                        return;
                    }
                    int pickMode = ((Number) p.args[0]).intValue();
                    if (unlockSetting(pickMode)) {
                        p.setResult(Integer.valueOf(RATE_ID_165));
                    }
                }
            });
        } catch (Throwable ignored) {
            // Preserve system behavior if a vendor hook cannot be installed.
        }
    }

    private static Object field(Object obj, String name) {
        if (obj == null) {
            return null;
        }
        Class<?> c = obj.getClass();
        while (c != null) {
            try {
                Field f = c.getDeclaredField(name);
                f.setAccessible(true);
                return f.get(obj);
            } catch (Throwable ignored) {
                c = c.getSuperclass();
            }
        }
        return null;
    }

}
