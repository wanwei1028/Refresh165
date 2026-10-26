package com.refresh165;

import android.app.KeyguardManager;
import android.content.ContentResolver;
import android.content.Context;
import android.os.SystemClock;
import android.provider.Settings;
import com.refresh165.xp.IXposedHookLoadPackage;
import com.refresh165.xp.XC_MethodHook;
import com.refresh165.xp.XposedBridge;
import com.refresh165.xp.XposedHelpers;
import com.refresh165.xp.callbacks.XC_LoadPackage;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.Set;

/**
 * Refresh165 —— 系统侧 Hook 主体。
 *
 * <p>实现基准：docs/DESIGN.md（1705 行 v3）。五个不变式：
 * <ol>
 *   <li>所有 165 相关写操作的第一行都是 {@code if (!Gear.takeover()) { stockPass(tag); return; }}</li>
 *   <li>before 阶段只改 {@code p.args[i]} 元素，不换数组引用（K16）</li>
 *   <li>要吞掉原方法（含其异常）必须在 before 用 {@code p.setResult(x)} 短路（K13）</li>
 *   <li>同一方法只挂一个合并后的回调（K14）：{@code updateVoteLocked} / {@code getWindowPreferredModeId}</li>
 *   <li>每个安装点走 {@link #install} + {@link #installed}，失败必须可见（§1.6-5）</li>
 * </ol>
 */
public final class Hook implements IXposedHookLoadPackage {
    private static final String TAG = "Refresh165";
    private static final String SYSTEM_PACKAGE = "android";
    private static final int PRIO_HIGH_TEMPERATURE = 80;
    private static final int PRIO_LAUNCHER = 85;
    private static final int PRIO_FOD = 130;
    /** 「锁定 165」期间临时保存的 48..86 三张表（解锁时原样恢复）。 */
    private static int[] sMinfpsTablesSaved;

    /** 负向条件用：这些包是内容明确要低帧的（原 hookDefaultWindowRate 的判据，保留）。 */
    private static final Set<String> STOCK_LOW_RATE_PACKAGES = new HashSet<String>(Arrays.asList(
            "tv.danmaku.bili",
            "com.hunantv.imgo.activity",
            "com.ss.android.article.video",
            "com.tencent.qqlive",
            "com.qiyi.video",
            "com.sina.weibo",
            "com.ss.android.ugc.aweme",
            "com.ss.android.ugc.aweme.lite",
            "com.ss.android.ugc.live",
            "com.smile.gifmaker",
            "com.kuaishou.nebula"));

    private interface Body {
        void run() throws Throwable;
    }

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lp) {
        XposedBridge.setInstallSink(new XposedBridge.InstallSink() {
            @Override public void failed(String target, Throwable t) {
                HookStatus.installFailed(target, t);
            }
        });
        android.util.Log.i(TAG, "load pkg=" + lp.packageName);
        if ("com.android.settings".equals(lp.packageName)) {
            install("settings", new Body() {
                @Override public void run() throws Throwable { hookSettingsPage(lp.classLoader); }
            });
            return;
        }
        if (!SYSTEM_PACKAGE.equals(lp.packageName) && !"system".equals(lp.packageName)) {
            return;
        }
        // 不限制机型：非 OPPO 设备上各 Hook 找不到类会记 fail-install，不影响系统。
        installSystemHooks(lp.classLoader);
    }

    // ================================================================= 安装框架

    private static void install(String tag, Body body) {
        try {
            body.run();
            // C10：成功即进 installed 位图（ok 只记 STATE，不进位图 → 位图会漏 tag）
            HookStatus.markInstalled(tag);
            HookStatus.ok(tag);
        } catch (Throwable t) {
            HookStatus.fail(tag, t);
            android.util.Log.e(TAG, "hook " + tag + " failed: " + t);
        }
    }

    private static void installed(String tag, int count) {
        if (count > 0) HookStatus.installed(tag);
        else HookStatus.fail(tag, "no-method");
    }

    // ================================================================= 系统侧总装

    private static void installSystemHooks(final ClassLoader cl) {
        install("rate-map", new Body() {
            @Override public void run() throws Throwable { RateMap.probe(cl); }
        });
        install("status-dump", new Body() {
            @Override public void run() throws Throwable { StatusDump.install(cl); }
        });
        install("game-state", new Body() {
            @Override public void run() throws Throwable { hookGameState(cl); }
        });
        install("core-vote", new Body() {
            @Override public void run() throws Throwable { hookVoteLocked(cl); }
        });
        // L2 只在 L1 装不上时启用为保险丝（§2.3）
        if (!HookStatus.isInstalled("core-vote")) {
            install("thermal-entry-fuse", new Body() {
                @Override public void run() throws Throwable { hookThermalEntryFuse(cl); }
            });
        } else {
            HookStatus.put("thermalEntry", "fuse-not-installed");
        }
        install("thermal-observe", new Body() {
            @Override public void run() throws Throwable { hookThermalObserve(cl); }
        });
        install("default-rate-id", new Body() {
            @Override public void run() throws Throwable { hookDefaultRateId(cl); }
        });
        install("force-max", new Body() {
            @Override public void run() throws Throwable { hookVendorClamp(cl); }
        });
        install("win-pref", new Body() {
            @Override public void run() throws Throwable { hookWindowPreferredMode(cl); }
        });
        // Control-center is a separate display candidate; never raise the
        // foreground game's own preferred mode or min_fps target.
        install("panel-234", new Body() {
            @Override public void run() throws Throwable { hookPanelFeatureArray(cl); }
        });
        install("reserve", new Body() {
            @Override public void run() throws Throwable { hookNativeReserveTable(cl); }
        });
        install("sam-min-fps", new Body() {
            @Override public void run() throws Throwable { hookSamMinFps(cl); }
        });
        install("dc-cap", new Body() {
            @Override public void run() throws Throwable { hookDcController(cl); }
        });
        install("perfect-rate", new Body() {
            @Override public void run() throws Throwable { hookPerfectRefreshRate(cl); }
        });
        install("app-override", new Body() {
            @Override public void run() throws Throwable { hookAppOverrideRefreshRate(cl); }
        });
        install("anim-boost", new Body() {
            @Override public void run() throws Throwable { hookOrdinaryAnimationBoost(cl); }
        });
        // 软约束例外（DESIGN §3：#19/#23/#24）：保留 v1.0.5 功能，只加计数
        install("touch-idle", new Body() {
            @Override public void run() throws Throwable { allowLowBrightnessDcIdle(cl); }
        });
        install("dc-reset", new Body() {
            @Override public void run() throws Throwable { preserveFullBrightnessDc(cl); }
        });
        install("view-policy", new Body() {
            @Override public void run() throws Throwable {
                if (!SystemViewPolicy.install(cl)) {
                    throw new IllegalStateException("view-policy-unavailable");
                }
            }
        });
        install("app-switch-boost", new Body() {
            @Override public void run() throws Throwable { hookAppSwitchBoost(cl); }
        });
        install("sf-specs", new Body() {
            @Override public void run() throws Throwable { SfSpecs.install(cl); }
        });
        install("mode-dispatch", new Body() {
            @Override public void run() throws Throwable { Reapply.installDispatchHook(cl); }
        });
        install("final-mode", new Body() {
            @Override public void run() throws Throwable { hookFinalDisplayMode(cl); }
        });
        install("fg-switch", new Body() {
            @Override public void run() throws Throwable { hookForegroundSwitch(cl); }
        });
        // 观察者自己管理 HookStatus（异步重试，成功才算 installed）
        installObservers();
        install("thermal-guard", new Body() {
            @Override public void run() throws Throwable { ThermalGuard.install(); }
        });
        PeakRepair.schedule();
        HookStatus.put("enforce", "none");
        // D1：不再硬编码 timers/polling；periodicTasks 由 ThermalGuard 兜底轮询状态推导
        HookStatus.put("timerList",
                "one-shot:dispatch(0,+250ms)/peak-restore(+250ms)/reconcile(+1500ms)/"
                + "peak-repair(0,5s,15s@boot)/observer-retry(<=60x1.5s@boot)/"
                + "thermal-recheck(30s,only-if-listener-unavailable)");
        recordModuleVersion();
    }

    /** Observe vendor game-state updates without changing their return values. */
    private static void hookGameState(ClassLoader cl) throws Throwable {
        Class<?> service = XposedHelpers.findClass(
                "com.oplus.vrr.OPlusRefreshRateService", cl);
        int n = 0;
        for (Method method : service.getDeclaredMethods()) {
            String name = method.getName().toLowerCase(java.util.Locale.ROOT);
            if (!name.contains("game") && !name.contains("gaming")
                    && !name.contains("battle")) continue;
            n += XposedBridge.hookMethodCount(method, new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam p) {
                    PerApp.noteGameService(p.thisObject);
                }
                @Override protected void afterHookedMethod(MethodHookParam p) {
                    PerApp.noteGameService(p.thisObject);
                }
            });
        }
        HookStatus.put("gameStateHooks", n);
        HookStatus.put("gameState", n > 0 ? "observing" : "unavailable");
    }

    /** Keep diagnostics aligned with the APK actually loaded by the module host. */
    private static void recordModuleVersion() {
        try {
            Context context = Sys.context();
            if (context == null) {
                HookStatus.put("module", "unavailable");
                HookStatus.put("moduleVersionError", "no-context");
                return;
            }
            android.content.pm.PackageInfo info = context.getPackageManager()
                    .getPackageInfo("com.refresh165", 0);
            String version = info.versionName;
            if (version != null && version.startsWith("v")) version = version.substring(1);
            HookStatus.put("module", version == null || version.isEmpty() ? "unknown" : version);
            HookStatus.put("moduleVersionError", "none");
        } catch (Throwable t) {
            HookStatus.put("module", "unavailable");
            HookStatus.put("moduleVersionError", t.getClass().getSimpleName());
        }
    }

    // ================================================================= 转场窗口（事件时间戳 + 惰性判断，无定时器）

    private static final long TRANS_WIN_MS = 800L;
    private static volatile long sLastFgSwitchAt;
    private static volatile String sLastFgPkg = "";
    private static volatile int sTransWin;
    private static volatile int sTransWinApplied;

    /** fg-switch 事件到达时记录（供转场窗口判定）。 */
    static void noteFgSwitchEvent(String pkg) {
        sLastFgPkg = pkg == null ? "" : pkg;
        sLastFgSwitchAt = SystemClock.elapsedRealtime();
    }

    /**
     * 转场窗口：刚切到白名单应用（或 force 开启）的 800ms 内。
     * 只读两个 volatile + 一次白名单查询，**不起定时器**。
     */
    static boolean inTransitionWindow() {
        if (!Gear.takeover()) return false;
        long at = sLastFgSwitchAt;
        if (at == 0) return false;
        if (SystemClock.elapsedRealtime() - at >= TRANS_WIN_MS) return false;
        if (Gear.force165()) return true;
        String pkg = sLastFgPkg;
        return pkg != null && !pkg.isEmpty() && PerApp.isWhitelisted(pkg);
    }

    /**
     * 目标是否允许 165：`byTarget` 为假时用转场窗口兜底。
     * @return true = 允许（并在窗口兜底时计数 transWin）
     */
    private static boolean allow165(boolean byTarget) {
        if (VideoFixed.blocksGlobalPromotion()) return false;
        // Never raise the game candidate itself above its native request,
        // including in freeform/PiP. SystemUI/control-center transitions use
        // their own package candidate and retain the normal 165 path.
        if (PerApp.blocks165Promotion(PerApp.foregroundPkg())) return false;
        if (byTarget) return true;
        if (inTransitionWindow()) {
            sTransWin++;
            HookStatus.put("transWin", sTransWin);
            HookStatus.applied("trans-win");
            return true;
        }
        return false;
    }

    /**
     * min_fps/面板/投票统一口径：这一步是否该按 165 下发。
     * <ul>
     *   <li>`target >= 165` → 是；</li>
     *   <li>`0 < target < 165`（固定 60/90/120/30）→ **否**（固定帧率优先，绝不被 force 顶上去）；</li>
     *   <li>`target <= 0`（无固定）→ 若等效 force（用户开关或白名单前台）或转场窗口 → 是。</li>
     * </ul>
     */
    static boolean want165(int target) {
        if (target >= 165) return true;
        if (target > 0) return false;
        if (VideoFixed.blocksGlobalPromotion()) return false;
        if (PerApp.blocks165Promotion(PerApp.foregroundPkg())) return false;
        return Gear.force165() || inTransitionWindow();
    }

    /** 窗口兜底真正产生改值时计数。 */
    private static void noteTransWinApplied(boolean byTarget) {
        if (byTarget) return;
        sTransWinApplied++;
        HookStatus.put("transWinApplied", sTransWinApplied);
    }

    // ================================================================= 切应用 boost 抑制（120 真凶）

    private static volatile int sBoostSkip;

    /**
     * `OplusRefreshRatePolicyImpl#startAppSwitchBoost(ActivityRecord)` **before**。
     *
     * <p>vendor 在切应用时会给 `PRIORITY_START_ANIMATION_BOOST` 投一张 120 票并直接
     * `setDisplayConfigSpecs(120)`（`boostRefreshId = getPkgRefreshRateId(pkg,...)`），
     * 把我们窗口的 165 压掉 → 首帧 120。这里在 takeover 内**整个跳过** boost：
     * <ul>
     *   <li>游戏 / native-only（`blocks165Promotion`）或固定帧率（`blocksGlobalPromotion`）→ 放行，不干预；</li>
     *   <li>其余（白名单与普通应用，165 档内）→ `setResult(null)`，不再下发 120；</li>
     *   <li>跳过时不动任何其它状态（厂商 2s 重置消息本来就没发，无需补偿）。</li>
     * </ul>
     * 自证：`boostSkip applied`、`boostSkipPkg`、`boostPassthrough`。
     */
    private static boolean boostSkipEnabled() {
        try {
            Context ctx = Sys.context();
            if (ctx == null) return false;
            return Settings.Global.getInt(ctx.getContentResolver(), "refresh165_boost_skip", 0) == 1;
        } catch (Throwable t) {
            return false;
        }
    }

    private static void hookAppSwitchBoost(ClassLoader cl) throws Throwable {
        Class<?> policy = XposedHelpers.findClass(
                "com.android.server.wm.OplusRefreshRatePolicyImpl", cl);
        int n = XposedBridge.hookAllMethods(policy, "startAppSwitchBoost", new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam p) {
                try {
                    if (!Gear.takeover()) {
                        HookStatus.stockPass("app-switch-boost");
                        return;
                    }
                    String pkg = pkgOfActivityRecord(p.args);
                    if (pkg == null || pkg.isEmpty()) {
                        HookStatus.stockPass("app-switch-boost");
                        return;
                    }
                    if (PerApp.blocks165Promotion(pkg) || VideoFixed.blocksGlobalPromotion()) {
                        HookStatus.stockPass("app-switch-boost");
                        HookStatus.put("boostPassthrough", pkg);
                        return;
                    }
                    // 保留厂商给桌面/最近任务的 app switch boost（用户明确要求）。
                    if (PerApp.isLauncher(pkg)) {
                        HookStatus.stockPass("app-switch-boost");
                        HookStatus.put("boostLauncher", pkg);
                        return;
                    }
                    // 保留厂商 boost（动画加速），由 mode-dispatch 把它的 120 目标改写成 165。
                    // 只有显式开关 refresh165_boost_skip=1 时才跳过整个 boost。
                    if (boostSkipEnabled()) {
                        p.setResult(null);
                        sBoostSkip++;
                        HookStatus.applied("boostSkip");
                        HookStatus.put("boostSkip", sBoostSkip);
                        HookStatus.put("boostSkipPkg", pkg);
                        return;
                    }
                    HookStatus.stockPass("app-switch-boost");
                    HookStatus.put("boostKept", pkg);
                } catch (Throwable t) {
                    HookStatus.fail("app-switch-boost", t);
                }
            }
        });
        if (n > 0) HookStatus.installed("app-switch-boost");
        else HookStatus.fail("app-switch-boost", "no-method");
        HookStatus.put("boostHooks", n);
    }

    /** 从 `startAppSwitchBoost(ActivityRecord)` 的参数里取包名（字段优先，其次 ComponentName）。 */
    private static String pkgOfActivityRecord(Object[] args) {
        if (args == null) return null;
        for (Object a : args) {
            if (a == null) continue;
            Object pkg = Reflect.field(a, "packageName");
            if (pkg instanceof String && !((String) pkg).isEmpty()) return (String) pkg;
            Object comp = Reflect.field(a, "mActivityComponent");
            if (comp instanceof android.content.ComponentName) {
                return ((android.content.ComponentName) comp).getPackageName();
            }
            Object info = Reflect.field(a, "info");
            Object pkg2 = info == null ? null : Reflect.field(info, "packageName");
            if (pkg2 instanceof String && !((String) pkg2).isEmpty()) return (String) pkg2;
        }
        return null;
    }

    // ================================================================= 最终出口兜底

    /**
     * 「最终出口」兜底（P0：切回白名单应用的首个 0.4s 采样仍是 120）。
     *
     * <p>票层与结果层都没命中的那条 120，最后一定会经过 vendor 的「最终 mode 选择」。
     * 这里在 `getFinalDisplayModeIdLocked`（及 `getFinalDisplayModeId` / `getPreferredDisplayModeId`）
     * 的 after 把 modeId 换成 165 对应的 mode：
     * <ul>
     *   <li>仅 `takeover` 且 `targetHz()==165`（白名单 / force）；</li>
     *   <li>视频固定（`effectiveRateId()>0`）一律不干预 → 固定 60 仍是 60；</li>
     *   <li>返回的 mode 已经是 165 → 不动（幂等）。</li>
     * </ul>
     * 自证：`final-mode applied`、`finalModeFrom=<旧 fps>`、`finalModeTo=165`；
     * 方法全缺失 → `final-mode=fail-install no-method`。
     */
    /** G：`unknown-mode` 同一 mode 只计一次（按 modeId 去重）。 */
    private static final java.util.Set<Integer> UNKNOWN_MODES =
            java.util.Collections.synchronizedSet(new java.util.LinkedHashSet<Integer>());

    private static void noteUnknownMode(int modeId, float fps) {
        if (fps > 0f) return;
        if (UNKNOWN_MODES.add(Integer.valueOf(modeId))) {
            HookStatus.put("unknownMode", modeId + ":" + UNKNOWN_MODES.size());
        }
    }

    private static void hookFinalDisplayMode(ClassLoader cl) throws Throwable {
        Class<?> policy = XposedHelpers.findClass(
                "com.android.server.wm.OplusRefreshRatePolicyImpl", cl);
        XC_MethodHook cb = new XC_MethodHook() {
            @Override protected void afterHookedMethod(MethodHookParam p) {
                PolicyRef.setPolicy(p.thisObject);
                if (p.getThrowable() != null) return;
                Object r = p.getResult();
                if (!(r instanceof Number)) return;
                if (!Gear.takeover()) { HookStatus.stockPass("final-mode"); return; }
                Object picker = Reflect.field(p.thisObject, PICK_DATA_FIELD);
                boolean panelExpanded = PanelRate.expanded(p.thisObject, picker);
                if (panelExpanded) {
                    // A4：遮罩分支复用完整判定（视频固定 + 温控 + blocks165Promotion + target），
                    //     不能只查 VideoFixed，否则温控/黑名单应用在控制中心下拉时会被顶到 165。
                    if (VideoFixed.effectiveRateId() > 0) {
                        HookStatus.stockPass("panel-mask");
                        return;
                    }
                    if (!ThermalGuard.interceptAllowed()) {
                        HookStatus.stockPass("panel-mask");
                        return;
                    }
                    String fgPanel = PerApp.foregroundPkg();
                    if (PerApp.blocks165Promotion(fgPanel)) {
                        HookStatus.stockPass("panel-mask");
                        return;
                    }
                    if (!want165(MinFpsState.targetHz())) {
                        HookStatus.stockPass("panel-mask");
                        return;
                    }
                    int currentMode = ((Number) r).intValue();
                    float currentHz = RateMap.hzOfModeId(currentMode);
                    int targetMode = PanelRate.finalModeId(currentMode, currentHz);
                    if (targetMode != currentMode) p.setResult(Integer.valueOf(targetMode));
                    return;
                }
                if (VideoFixed.effectiveRateId() > 0 && !panelExpanded) {
                    HookStatus.stockPass("final-mode");
                    return;                       // 视频固定优先：60 必须保持 60
                }
                int t = MinFpsState.targetHz();
                boolean byTarget = t >= 165;
                if (!allow165(byTarget || want165(t))) {
                    HookStatus.stockPass("final-mode");
                    return;
                }
                int modeId = ((Number) r).intValue();
                float fps = RateMap.hzOfModeId(modeId);
                noteUnknownMode(modeId, fps);
                if (fps >= 164.5f) { HookStatus.stockPass("final-mode"); return; }
                // E：优先 SF 编号（SfSpecs 解析），退 Display 编号；都没有 → skip 并记 modeIdSrc
                int target165 = SfSpecs.modeId165Public();
                if (target165 <= 0) {
                    HookStatus.put("finalModeSkip", "mode-id-missing");
                    HookStatus.stockPass("final-mode");
                    return;
                }
                p.setResult(Integer.valueOf(target165));
                HookStatus.applied("final-mode");
                noteTransWinApplied(byTarget);
                HookStatus.put("finalModeFrom", fps > 0 ? String.valueOf(fps) : String.valueOf(modeId));
                HookStatus.put("finalModeTo", 165);
            }
        };
        int n = 0;
        String[] names = {"getFinalDisplayModeIdLocked", "getFinalDisplayModeId",
                "getPreferredDisplayModeId"};
        for (String name : names) {
            n += XposedBridge.hookAllMethods(policy, name, cb);
        }
        if (n > 0) HookStatus.installed("final-mode");
        else HookStatus.fail("final-mode", "no-method");
        HookStatus.put("finalModeHooks", n);
    }

    // ================================================================= 前台切换即时信号

    /**
     * 前台切换的**即时信号**（P0：切回白名单应用时 0.5~1s 的 120 窗口）。
     *
     * <p>优先 `OPlusExternalRefreshRateManager#setFrontPackageName(String)`；
     * 不存在则回退 `setCurPackageName` / `onAppSwitch`（管理器与 policy 两处都试），
     * 命中记 `fgHook=<name>`，全部缺失记 `fgHook=none`。
     * after 里立刻：缓存失效 → `noteForeground` → `onCandidate` → `dispatchTargetChange("fg-switch")`，
     * **不等 500ms 解析缓存**。
     */
    private static void hookForegroundSwitch(ClassLoader cl) throws Throwable {
        // 候选类 → 候选方法名（本 ROM 全未命中时再挂 ATMS 的 Activity 切换事件）
        String[][] plan = {
                {"com.oplus.vrr.OPlusExternalRefreshRateManager",
                        "setFrontPackageName", "updateFrontPackageName", "onForegroundPackageChanged",
                        "setCurPackageName", "onAppSwitch"},
                {"com.android.server.wm.OplusRefreshRatePolicyImpl",
                        "setCurPackageName", "onPackageChanged", "setTopPackageName",
                        "setFrontPackageName", "onAppSwitch"},
                {"com.android.server.wm.ActivityTaskManagerService",
                        "setResumedActivityUncheckLocked"}
        };
        String hit = null;
        int total = 0;
        for (String[] row : plan) {
            Class<?> c;
            try {
                c = XposedHelpers.findClass(row[0], cl);
            } catch (Throwable t) {
                continue;
            }
            for (int i = 1; i < row.length; i++) {
                final String name = row[i];
                final boolean fromAtms = row[0].endsWith("ActivityTaskManagerService");
                int n = XposedBridge.hookAllMethods(c, name, new XC_MethodHook() {
                    @Override protected void beforeHookedMethod(MethodHookParam p) {
                        if (fromAtms) return;
                        try {
                            PolicyRef.setPolicy(p.thisObject);
                            PerApp.noteMirror(p.thisObject);
                            if ("setFrontPackageName".equals(name) || "setCurPackageName".equals(name)
                                    || "setTopPackageName".equals(name)) {
                                String next = firstString(p.args);
                                if (next != null) PerApp.presetForeground(next, name);
                            } else {
                                applyFgHint(p.thisObject);
                            }
                        } catch (Throwable t) {
                            HookStatus.fail("fg-hint", t);
                        }
                    }

                    @Override protected void afterHookedMethod(MethodHookParam p) {
                        // A2：after 可能持 WM 全局锁 → 只做常量提取 + 一次 post，
                        //      Settings/Keyguard/Power/effectiveRateId 全部搬到主线程
                        final Object[] snapshot = p.args;
                        final String src = fromAtms ? "atms:" + name : name;
                        sFgSwitchDeferred++;
                        HookStatus.put("fgSwitchDeferred", sFgSwitchDeferred);
                        Sys.main().post(new Runnable() {
                            @Override public void run() {
                                String pkg = fromAtms ? pkgFromActivityArgs(snapshot)
                                        : firstString(snapshot);
                                if (pkg == null || pkg.isEmpty()) return;
                                onForegroundSwitch(pkg, src);
                            }
                        });
                    }
                });
                if (n > 0) {
                    total += n;
                    if (hit == null) hit = fromAtms ? "atms:" + name : name;
                }
            }
        }
        if (total > 0) {
            HookStatus.installed("fg-switch");
            HookStatus.put("fgHook", hit);
            HookStatus.put("fgSwitchHooks", total);
        } else {
            HookStatus.fail("fg-switch", "no-method");
            HookStatus.put("fgHook", "none");
        }
    }

    /** ATMS 兜底：从 `setResumedActivityUncheckLocked(Task, String)` 的参数里取包名。 */
    private static String pkgFromActivityArgs(Object[] args) {
        if (args == null) return null;
        for (Object a : args) {
            if (a == null) continue;
            if (a instanceof String) {
                String s = (String) a;
                if (s.contains(".")) return s;            // 某些 ROM 直接传包名
                continue;
            }
            Object realActivity = Reflect.field(a, "realActivity");
            if (realActivity instanceof android.content.ComponentName) {
                return ((android.content.ComponentName) realActivity).getPackageName();
            }
            Object intent = XposedHelpers.callMethod(a, "getBaseIntent");
            if (intent instanceof android.content.Intent) {
                android.content.ComponentName cn = ((android.content.Intent) intent).getComponent();
                if (cn != null) return cn.getPackageName();
            }
            Object top = Reflect.field(a, "topActivity");
            if (top instanceof android.content.ComponentName) {
                return ((android.content.ComponentName) top).getPackageName();
            }
        }
        return null;
    }

    private static String firstString(Object[] args) {
        if (args == null) return null;
        for (Object a : args) {
            if (a instanceof String && !((String) a).isEmpty()) return (String) a;
        }
        return null;
    }

    /** 若管理器上已有「下一个/上一个包名」字段，提前按它预置一次（item 2）。 */
    private static void applyFgHint(Object manager) {
        try {
            Object next = Reflect.field(manager, "mNextPkgName");
            if (next instanceof String && !((String) next).isEmpty()) {
                PerApp.presetForeground((String) next, "fg-hint");
                HookStatus.applied("fg-hint");
            }
        } catch (Throwable t) {
            HookStatus.fail("fg-hint", t);
        }
    }

    private static volatile int sFgSwitchDeferred;

    /** 即时信号落地（主线程执行）：清缓存 → 记前台 → 立刻重算目标 → 有界重试下发。 */
    private static void onForegroundSwitch(String pkg, String source) {
        try {
            PerApp.presetForeground(pkg, source);
            PerApp.noteForeground(pkg);
            noteFgSwitchEvent(pkg);          // 转场窗口起点（事件驱动）
            if (Gear.takeover()) HookStatus.applied("fg-switch-event");   // D2：闸门之后才计数
            MinFpsState.noteFgSwitch();
            int fixedId = VideoFixed.effectiveRateId(pkg);
            MinFpsState.onCandidate(pkg, fixedId);
            HookStatus.put("lastFgSwitch", pkg + "@" + source);
        } catch (Throwable t) {
            HookStatus.fail("fg-switch-handle", t);
        }
    }

    // ================================================================= R1 温控

    /**
     * L1：票层撤票（主通道）。同一个 before 承载三个互斥职责，命中即 return（P1-10）：
     * ① prio==80 → 置 null；② R6 固定 → (hz,hz)；③ prio 85/130 的 120 → (165,60)。
     */
    private static void hookVoteLocked(ClassLoader cl) throws Throwable {
        Class<?> core = XposedHelpers.findClass(
                "com.android.server.wm.OplusRefreshRateCore", cl);
        Class<?> voteCls = XposedHelpers.findClass(
                "com.android.server.wm.OplusRefreshRateCore$Vote", cl);
        final Method forRate = voteCls.getDeclaredMethod(
                "forRefreshRate", float.class, float.class);
        forRate.setAccessible(true);
        int n = XposedBridge.hookAllMethods(core, "updateVoteLocked", new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam p) {
                if (p.args == null || p.args.length != 2 || !(p.args[0] instanceof Integer)) return;
                int prio = ((Integer) p.args[0]).intValue();

                // ---- 第 1 支：温控撤票
                if (prio == PRIO_HIGH_TEMPERATURE) {
                    Object vote = p.args[1];
                    ThermalGuard.observeVote(vote);      // 无条件观测（主判据的输入）
                    if (vote == null) { HookStatus.stockPass("thermal-vote"); return; }
                    if (!Gear.takeover() || !ThermalGuard.interceptAllowed()) {
                        HookStatus.stockPass("thermal-vote");
                        return;
                    }
                    p.args[1] = null;                    // 撤销温控票（不是改成 165）
                    HookStatus.applied("thermal-vote");
                    return;
                }

                // ---- 第 2 支：R6 固定帧率（不改票数量与排序，只改值）
                if (VideoFixed.voteFixedAllowed(prio)) {
                    float hz = VideoFixed.effectiveHz();
                    if (hz > 0) {
                        try {
                            p.args[1] = forRate.invoke(null, Float.valueOf(hz), Float.valueOf(hz));
                            HookStatus.applied("vote-fixed");
                            return;
                        } catch (Throwable t) {
                            HookStatus.fail("vote-fixed", t);
                        }
                    }
                }

                // ---- 第 2.5 支：转场窗口压制（P0：切回白名单应用时的 0.5s 120 洞）
                //      仅当目标就是 165（白名单/force），把 prio 0/40/90 的 120 票临时抬到 165；
                //      不改票表结构（不新增/不删票），只在 before 改值。
                //      覆盖范围：除 80（已撤票）/85/130（第 3 支）之外的任何 120 票，
                //      条件仍是「takeover 且目标 == 165」，只改值不加票。
                if (prio != PRIO_HIGH_TEMPERATURE && prio != PRIO_LAUNCHER && prio != PRIO_FOD) {
                    int tt = MinFpsState.targetHz();
                    boolean target165 = tt >= 165 || want165(tt);
                    // boost 可能早于 fg-switch 事件；165 档内普通应用/桌面同样按 165 处理，
                    // 否则厂商的 boost 票仍是 120，日志/瞬时 pick 会先跳 120。
                    if (!target165 && tt <= 0 && Gear.takeover()
                            && !PerApp.blocks165Promotion(PerApp.foregroundPkg())
                            && !VideoFixed.blocksGlobalPromotion()) {
                        target165 = true;
                    }
                    boolean videoFixed = VideoFixed.effectiveRateId() > 0;
                    if (Gear.takeover() && !videoFixed && p.args[1] != null
                            && allow165(target165)) {
                        float rate = Reflect.floatField(p.args[1], "mRefreshRate", -1f);
                        if (rate >= 100f && rate < 164.5f) {
                            try {
                                p.args[1] = forRate.invoke(null, Float.valueOf(165f), Float.valueOf(60f));
                                HookStatus.applied("transition-override");
                                noteTransWinApplied(target165);
                                MinFpsState.noteFgSwitchEffective();
                                return;
                            } catch (Throwable t) {
                                HookStatus.fail("transition-override", t);
                            }
                        }
                    } else {
                        HookStatus.stockPass("transition-override");
                    }
                }

                // ---- 第 3 支：launcher / FOD 的 120 抬到 165（受 takeover 门，修 K1）
                if (prio == PRIO_LAUNCHER || prio == PRIO_FOD) {
                    if (!Gear.takeover()) { HookStatus.stockPass("vote-boost"); return; }
                    if (PerApp.blocks165Promotion(PerApp.foregroundPkg())) {
                        HookStatus.stockPass("vote-boost-native-cap");
                        return;
                    }
                    if (p.args[1] == null) return;
                    float rate = Reflect.floatField(p.args[1], "mRefreshRate", -1f);
                    if (Math.abs(rate - RateMap.hzOf(RateMap.ID_120)) > 0.5f) return;
                    int id165 = RateMap.id165Or(-1);
                    if (id165 <= 0) { HookStatus.stockPass("vote-boost"); return; }
                    try {
                        p.args[1] = forRate.invoke(null, Float.valueOf(165f), Float.valueOf(60f));
                        HookStatus.applied("vote-boost");
                    } catch (Throwable t) {
                        HookStatus.fail("vote-boost", t);
                    }
                }
            }
        });
        installed("core-vote", n);
    }

    /** 纯观测：记录厂商温控请求（主判据），不改写任何东西（不是 L2 保险丝）。 */
    private static void hookThermalObserve(ClassLoader cl) throws Throwable {
        Class<?> policy = XposedHelpers.findClass(
                "com.android.server.wm.OplusRefreshRatePolicyImpl", cl);
        XC_MethodHook cb = new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam p) {
                PolicyRef.setPolicy(p.thisObject);
                if (p.args == null || p.args.length < 2
                        || !(p.args[0] instanceof Integer) || !(p.args[1] instanceof Integer)) return;
                ThermalGuard.observe(((Integer) p.args[0]).intValue(),
                        ((Integer) p.args[1]).intValue());
            }
        };
        int n = XposedBridge.hookAllMethods(policy, "setHighTemperatureStatusWithoutUpdate", cb);
        n += XposedBridge.hookAllMethods(policy, "setHighTemperatureStatus", cb);
        installed("thermal-observe", n);
    }

    /** L2 保险丝：只在 L1 装不上时启用（会吞掉 OSYNC 温度写与 thermal listener 记账）。 */
    private static void hookThermalEntryFuse(ClassLoader cl) throws Throwable {
        Class<?> policy = XposedHelpers.findClass(
                "com.android.server.wm.OplusRefreshRatePolicyImpl", cl);
        XC_MethodHook cb = new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam p) {
                if (p.args == null || p.args.length < 2
                        || !(p.args[0] instanceof Integer) || !(p.args[1] instanceof Integer)) return;
                ThermalGuard.observe(((Integer) p.args[0]).intValue(),
                        ((Integer) p.args[1]).intValue());
                if (!Gear.takeover() || !ThermalGuard.interceptAllowed()) {
                    HookStatus.stockPass("thermal-entry-fuse");
                    return;
                }
                p.setResult(Boolean.FALSE);
                HookStatus.applied("thermal-entry-fuse");
            }
        };
        int n = XposedBridge.hookAllMethods(policy, "setHighTemperatureStatusWithoutUpdate", cb);
        n += XposedBridge.hookAllMethods(policy, "setHighTemperatureStatus", cb);
        installed("thermal-entry-fuse", n);     // 必修-4①：安装包装 tag 与内部 tag 统一
        HookStatus.put("thermalEntry", n > 0 ? "fuse" : "unavailable");
    }

    // ================================================================= R5/R3 系统侧

    /**
     * R5-S1 / R1-peak：接管时 **before 短路** 返回 ID_165。
     * 同时消化「pickMode==8 且表只有 4 段 → 下标越界」与「原厂表 mDefaultRateId[3]=3」。
     */
    private static void hookDefaultRateId(ClassLoader cl) throws Throwable {
        Class<?> configs = XposedHelpers.findClass(
                "com.android.server.wm.OplusRefreshRateConfigs", cl);
        XC_MethodHook cb = new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam p) {
                if (p.args == null || p.args.length != 1 || !(p.args[0] instanceof Number)) return;
                PolicyRef.setConfigs(p.thisObject);
                int pick = ((Number) p.args[0]).intValue();
                if (!Gear.takeover()) { HookStatus.stockPass("default-rate-id"); return; }
                if (pick != Gear.SETTING_MODE_165 && pick != Gear.SETTING_MODE_EXTREME) {
                    HookStatus.stockPass("default-rate-id");
                    return;
                }
                int id = RateMap.id165Or(-1);
                if (id <= 0) { HookStatus.stockPass("default-rate-id"); return; }
                p.setResult(Integer.valueOf(id));
                HookStatus.applied("default-rate-id");
            }
        };
        int n = XposedBridge.hookAllMethods(configs, "getDefaultRateId", cb);
        installed("default-rate-id", n);
    }

    /** S2：防 vendor 把 7 换回 forceMaxRefreshSettings()。 */
    private static void hookVendorClamp(ClassLoader cl) throws Throwable {
        Class<?> policy = XposedHelpers.findClass(
                "com.android.server.wm.OplusRefreshRatePolicyImpl", cl);
        int n = XposedBridge.hookAllMethods(policy, "forceMaxRefreshRateSettings",
                new XC_MethodHook() {
                    @Override protected void afterHookedMethod(MethodHookParam p) {
                        PolicyRef.setPolicy(p.thisObject);          // 仅观测：拿 mConfigs / mCore
                        if (p.args == null || p.args.length != 1
                                || !(p.args[0] instanceof Number)) return;
                        if (!Gear.takeover()) { HookStatus.stockPass("force-max"); return; }
                        int requested = ((Number) p.args[0]).intValue();
                        HookStatus.put("forceMaxRequested", requested);
                        if (requested != Gear.SETTING_MODE_165) {
                            HookStatus.stockPass("force-max");
                            return;
                        }
                        int id = RateMap.id165Or(-1);
                        if (id <= 0) { HookStatus.stockPass("force-max"); return; }
                        p.setResult(Integer.valueOf(id));
                        HookStatus.applied("force-max");
                    }
                });
        installed("force-max", n);
    }

    // ================================================================= R3/R4/R6 窗口候选

    /**
     * 合并后的唯一 getWindowPreferredModeId 回调（清 K14）。四支按优先级排列，命中即 return：
     * R6 固定 → R4 白名单 → 锁屏 → 默认提升。120 档在第一行整体出局（R2）。
     */
    private static void hookWindowPreferredMode(ClassLoader cl) throws Throwable {
        Class<?> policy = XposedHelpers.findClass(
                "com.android.server.wm.OplusRefreshRatePolicyImpl", cl);
        int n = XposedBridge.hookAllMethods(policy, "getWindowPreferredModeId",
                new XC_MethodHook() {
                    @Override protected void beforeHookedMethod(MethodHookParam p) {
                        if (p.args == null || p.args.length < 1 || !(p.args[0] instanceof Number)) return;
                        int picked = ((Number) p.args[0]).intValue();
                        if (!Gear.takeover()) { HookStatus.stockPass("win-pref"); return; }
                        int id165 = RateMap.id165Or(-1);
                        if (id165 <= 0) { HookStatus.stockPass("win-pref"); return; }

                        Object policyObj = p.thisObject;
                        PolicyRef.setPolicy(policyObj);
                        RateMap.patchSupportMaxIfNeeded();
                        Object picker = Reflect.field(policyObj, PICK_DATA_FIELD);
                        String pkg = candidatePackage(picker);
                        PerApp.noteForeground(pkg);
                        // 候选包名为空时用多级解析结果（否则白名单/固定帧率整条链路失效）
                        if (pkg.isEmpty()) pkg = PerApp.foregroundPkg();
                        int nativeReq = candidateWindowRequestRate(picker);
                        PerApp.noteNativeRequest(pkg, nativeReq);
                        // 一次决策内只算一次 effectiveRateId（P2 §6.2），后续步骤复用
                        int fixedId = VideoFixed.effectiveRateId();
                        MinFpsState.onCandidate(pkg, fixedId);

                        // Observe the independent panel state here, but leave
                        // the game's candidate-window request untouched.
                        PanelRate.expanded(policyObj, picker);

                        // 1) R6 固定帧率（最具体的用户意图）
                        //    必修-1：**命中配置即 return**。adfr-floor(30) 与各 suspended 态
                        //    （thermal / multi-window / keyguard / screen-off / no-mode-for-hz）
                        //    下 fixedId 为 -1，但绝不能再落到 R4/force/默认提升把它顶成 165
                        //    （DESIGN §12.4.3「R6 固定优先」；回落方向是原生，不是 165）。
                        if (fixedId > 0) {
                            p.args[0] = Integer.valueOf(fixedId);
                            HookStatus.applied("win-pref-fixed");
                            return;
                        }
                        if (VideoFixed.handles(pkg)) {
                            HookStatus.stockPass("win-pref-fixed");
                            return;
                        }
                        if (PerApp.blocks165Promotion(pkg)) {
                            HookStatus.stockPass("win-pref-native-only");
                            return;
                        }
                        // 2) R4 白名单
                        if (PerApp.force165(pkg)) {
                            p.args[0] = Integer.valueOf(id165);
                            HookStatus.applied("win-pref-165-app");
                            return;
                        }
                        // 3) 锁屏
                        if (picked == RateMap.ID_120 && isKeyguardLocked()) {
                            p.args[0] = Integer.valueOf(id165);
                            HookStatus.applied("win-pref-keyguard");
                            return;
                        }
                        // 4) 默认提升（负向条件保留，失败方向为「不动」）
                        //    90/120/144 都是应用侧请求，不是面板能力上限；165 档内应统一提升。
                        int pickedHz = RateMap.hzOf(picked) > 0 ? Math.round(RateMap.hzOf(picked)) : 0;
                        boolean promotable = pickedHz == 90 || pickedHz == 120 || pickedHz == 144;
                        if (promotable
                                && defaultPromoteAllowed(policyObj, pkg, picker)) {
                            p.args[0] = Integer.valueOf(id165);
                            HookStatus.applied("win-pref-default");
                        }
                    }
                });
        installed("win-pref", n);
    }


    private static boolean isKeyguardLocked() {
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

    /**
     * 120 -> 165 的默认提升只用于没有明确低速能力上限的窗口。
     *
     * <p>系统 UI/桌面转场候选允许 165；游戏候选（无论全屏/自由窗）与显式
     * native-only 应用保留原生上限。普通应用上报的低速请求不是能力上限——
     * 例如美柚一类应用仍应可按 165 档策略提升；游戏内容才严格遵循游戏帧率。
     * 未知普通应用保留默认提升，避免没有窗口票的应用错误地锁在 120。
     */
    private static boolean defaultPromoteAllowed(Object policy, String pkg, Object picker) {
        if (pkg == null || pkg.isEmpty()) return false;
        if (STOCK_LOW_RATE_PACKAGES.contains(pkg)) return false;
        if (PerApp.isLauncher(pkg)) return true;
        if (PerApp.nativeOnly(pkg)) return false;
        if (PerApp.isGame(pkg)) return false;

        // Keep an explicit per-app low-rate override authoritative when the
        // vendor lookup is available. A missing lookup is fail-open for stock.
        int override = explicitUserOverrideRate(policy, pkg, Gear.settingMode());
        if (override > 0 && override != RateMap.ID_165) return false;
        return true;
    }

    /** Read the vendor per-app override without making it a required hook. */
    private static int explicitUserOverrideRate(Object policy, String pkg, int settingMode) {
        if (policy == null || pkg == null || pkg.isEmpty()) return -1;
        try {
            Object configs = Reflect.field(policy, "mConfigs");
            if (configs == null) return -1;
            Method getter = compatibleMethod(configs.getClass(),
                    "getPreferredRefreshRateData", 2);
            if (getter == null) return -1;
            Object data = getter.invoke(configs, pkg, null);
            if (data == null) return 0;
            Method override = Sys.method(data.getClass(), "getOverridePreferredRefreshRateId",
                    int.class);
            if (override == null) return -1;
            Object value = override.invoke(data, Integer.valueOf(Gear.modeColumn(settingMode)));
            return value instanceof Number ? ((Number) value).intValue() : -1;
        } catch (Throwable t) {
            HookStatus.fail("app-override-read", t);
            return -1;
        }
    }

    private static Method compatibleMethod(Class<?> type, String name, int arity) {
        for (Class<?> c = type; c != null; c = c.getSuperclass()) {
            try {
                for (Method m : c.getDeclaredMethods()) {
                    if (!name.equals(m.getName()) || m.getParameterTypes().length != arity) continue;
                    m.setAccessible(true);
                    return m;
                }
            } catch (Throwable ignored) { }
        }
        return null;
    }

    /** install 期之后只解析一次（P2 §6.2：每帧路径不再做方法全表扫描）。 */
    private static volatile Method sCandidatePkgMethod;
    private static volatile Class<?> sCandidatePkgOwner;
    /** `mPickRefreshRateData` 的 Field 名（Reflect 内部有 Class→Field 缓存）。 */
    private static final String PICK_DATA_FIELD = "mPickRefreshRateData";

    private static Method candidateMethod(Object picker, String name) {
        if (picker == null) return null;
        Class<?> owner = picker.getClass();
        if ("candidateWinPkgName".equals(name)) {
            Method m = sCandidatePkgMethod;
            if (m != null && sCandidatePkgOwner == owner) return m;
        }
        Method found = Sys.method(owner, name);
        if (found != null && "candidateWinPkgName".equals(name)) {
            sCandidatePkgMethod = found;
            sCandidatePkgOwner = owner;
        }
        return found;
    }

    private static String candidatePackage(Object picker) {
        if (picker == null) return "";
        try {
            Method m = candidateMethod(picker, "candidateWinPkgName");
            if (m == null) {
                HookStatus.fail("pick-candidate", "candidateWinPkgName-missing");
                return "";
            }
            Object value = m.invoke(picker);
            return value == null ? "" : String.valueOf(value);
        } catch (Throwable t) {
            // P2 §6.4：每帧路径上的 vendor 改名不能静默 —— dumpsys 必须能查
            HookStatus.fail("pick-candidate", t);
            return "";
        }
    }

    private static int candidateWindowRequestRate(Object picker) {
        if (picker == null) return -1;
        try {
            Method m = candidateMethod(picker, "candidateWinReqRateId");
            if (m == null) {
                HookStatus.fail("pick-candidate-req", "candidateWinReqRateId-missing");
                return -1;
            }
            Object value = m.invoke(picker);
            return value instanceof Number ? ((Number) value).intValue() : -1;
        } catch (Throwable t) {
            HookStatus.fail("pick-candidate-req", t);
            return -1;
        }
    }

    /** R4-B：让「应用自身请求通道」也看到 165（不改内存模型、不落盘）。 */
    private static void hookAppOverrideRefreshRate(ClassLoader cl) throws Throwable {
        Class<?> policy = XposedHelpers.findClass(
                "com.android.server.wm.OplusRefreshRatePolicyImpl", cl);
        int n = XposedBridge.hookAllMethods(policy, "getAppOverrideRefreshRate",
                new XC_MethodHook() {
                    @Override protected void afterHookedMethod(MethodHookParam p) {
                        PolicyRef.setPolicy(p.thisObject);
                        if (p.getThrowable() != null) return;
                        if (!Gear.takeover()) { HookStatus.stockPass("app-override"); return; }
                        if (p.args == null || p.args.length < 2
                                || !(p.args[0] instanceof String)
                                || !(p.args[1] instanceof Integer)) return;
                        String pkg = (String) p.args[0];
                        int mode = ((Integer) p.args[1]).intValue();
                        if (Gear.modeColumn(mode) != Gear.PER_APP_COLUMN_PICK120) {
                            HookStatus.stockPass("app-override");
                            return;
                        }
                        if (!PerApp.force165(pkg)) { HookStatus.stockPass("app-override"); return; }
                        int id = RateMap.id165Or(-1);
                        if (id <= 0) { HookStatus.stockPass("app-override"); return; }
                        if (Integer.valueOf(id).equals(p.getResult())) return;
                        p.setResult(Integer.valueOf(id));
                        HookStatus.applied("app-override");
                    }
                });
        installed("app-override", n);
    }

    // ================================================================= min_fps 三档

    /** ④-a：唯一真正把 ADFR 参数送到 HWC 的地方。表内容由 MinFpsState 统一计算。 */
    private static void hookPanelFeatureArray(ClassLoader cl) throws Throwable {
        Class<?> helper = XposedHelpers.findClass(
                "com.android.server.display.util.OplusDisplayPanelFeatureHelper", cl);
        int n = XposedBridge.hookAllMethods(helper, "setDisplayPanelFeatureValueArray",
                new XC_MethodHook() {
                    @Override protected void beforeHookedMethod(MethodHookParam p) {
                        if (p.args == null || p.args.length != 2
                                || !(p.args[0] instanceof Integer)
                                || !(p.args[1] instanceof int[])) return;
                        if (((Integer) p.args[0]).intValue() != 234) return;
                        int[] modes = (int[]) p.args[1];
                        if (modes.length < 225) return;      // P1-1：写入上界 224，守卫必须同步
                        boolean pending = MinFpsState.takePending();   // 先复位（P2-4）
                        boolean collapse = MinFpsState.takeCollapse();  // 修复 #1：收口许可
                        if (!Gear.takeover()) {
                            HookStatus.put("panelMinFps", "vendor-native");
                            // 120 档：默认零改写；只有我们自己请求的「收口」允许 bump 版号，
                            // 且单独计入 panel-collapse（panel-234 的 applied 仍为 0）
                            if (collapse) {
                                // C：stock 档**不得**推进 sPanelVerSeq（否则 panelVerSeq 8→20），
                                // 收口 bump 用独立计数器 sCollapseSeq。
                                sCollapseSeq++;
                                modes[1] = modes[1] + sCollapseSeq;
                                HookStatus.applied("panel-collapse");
                                HookStatus.put("panelCollapseSeq", sCollapseSeq);
                            } else {
                                HookStatus.stockPass("panel-234");
                            }
                            return;
                        }
                        int target = MinFpsState.targetHz();
                        if (target <= 0) {
                            // A1（MINFPS-DESIGN §2.2）：idle1 **不写值、不 bump**，交回 vendor 原厂。
                            // 依据实机矩阵：主动写 48..86=1 会被 vendor 后续覆盖，且任何一次目标误判
                            // 都会把表写成 1 并 bump，反过来覆盖之前的 165（自我破坏）。
                            HookStatus.stockPass("panel-idle1");
                            HookStatus.put("panelIdle1Mode", "leave-vendor");
                            HookStatus.put("panelMinFps", "vendor-native");
                            HookStatus.put("panelPending", pending);
                            return;
                        }
                        // lead：目标 165（白名单/force）或转场窗口内，min_fps 表**不受 peak 夹取**，
                        // 且顺序必须是「peak 先到位 → 再写 min_fps」，否则首帧会被写成 120。
                        boolean to165 = want165(target);
                        int peak = currentPeakHz();
                        int value;
                        if (to165) {
                            MinFpsState.ensurePeakFloor("whitelist-enter");
                            value = 165;
                        } else {
                            value = (peak > 0 && !MinFpsState.inPeakNudge())
                                    ? Math.min(target, peak) : target;
                            if (value < target) sMinFpsClamped++;
                        }
                        sMinFpsWrite++;
                        HookStatus.put("minFpsWrite", sMinFpsWrite);
                        HookStatus.put("minFpsClamped", sMinFpsClamped);
                        // 48..60 / 61..73 / 74..86 = 120/90/60 三张表，**不允许被 165 污染**。
                        // 唯一例外：用户手动「锁定 165」期间，先保存原值再临时写 165，解锁时恢复。
                        if (!Gear.takeover()) {
                            // 120 档/退出接管：vendor 会按原厂表重建，丢弃临时快照即可
                            sMinfpsTablesSaved = null;
                        }
                        boolean userLock = Gear.userForce165();
                        if (to165 && userLock) {
                            if (sMinfpsTablesSaved == null) {
                                sMinfpsTablesSaved = new int[39];
                                for (int i = 48; i <= 86; i++) sMinfpsTablesSaved[i - 48] = modes[i];
                                HookStatus.put("minfpsTables", "saved");
                            }
                            for (int i = 48; i <= 86; i++) modes[i] = 165;
                        } else if (sMinfpsTablesSaved != null) {
                            for (int i = 48; i <= 86; i++) modes[i] = sMinfpsTablesSaved[i - 48];
                            sMinfpsTablesSaved = null;
                            HookStatus.put("minfpsTables", "restored");
                        }
                        if (!to165) {
                            int from = 48;
                            if (value == 60) from = 74;        // minfps60 段
                            else if (value == 90) from = 61;   // minfps90 段
                            else if (value == 120) from = 48;  // minfps120 段
                            if (value <= 0 || (value != 60 && value != 90 && value != 120)) {
                                for (int i = 48; i <= 86; i++) modes[i] = value;   // 非常规值：全写
                            } else {
                                for (int i = from; i <= from + 12; i++) modes[i] = value;
                            }
                        }
                        // reserve 三段：level1 起点 186 / level2 起点 199 / level3 起点 212，各 13 项 → 186..224
                        // （原只写到 215，漏 216..224 这 9 格；原厂那 9 格含 1/33/165，是「表 165、节点 1」的来源之一）
                        for (int i = 186; i <= 224; i++) modes[i] = value;
                        sPanelVerSeq++;
                        modes[1] = modes[1] + sPanelVerSeq;
                        MinFpsState.markPanelDirty();       // 必修-3：真改写置脏，供退出恢复判据
                        markEnforcePanel();
                        HookStatus.applied("panel-234");
                        HookStatus.applied("panelForce");
                        HookStatus.put("panelVerSeq", sPanelVerSeq);
                        HookStatus.put("panelMinFps", value);
                        HookStatus.put("panelPending", pending);
                        if (sPanelLogs < 3) {
                            sPanelLogs++;
                            android.util.Log.i(TAG, "panel apply ver=" + modes[1]
                                    + " minfps=" + value + " len=" + modes.length
                                    + " seq=" + sPanelVerSeq);
                        }
                    }
                });
        installed("panel-234", n);
    }

    private static volatile int sPanelVerSeq;
    private static volatile int sCollapseSeq;
    private static volatile int sPanelIdle1Applied;
    private static volatile int sMinFpsWrite;
    private static volatile int sMinFpsClamped;
    private static volatile int sReserveStockWrites;
    private static volatile int sSamCalls;
    private static volatile int sSamApplied;
    private static volatile int sSamMismatch;
    private static volatile int sSamValue;
    private static volatile long sLastMismatchDispatchAt;

    /**
     * 对账（P0-3）：vendor 的 `getSAMinFpsByNit` 原值与目标不一致 → 记 mismatch 并再补一次下发。
     * 800ms 限频，避免 vendor 每帧求值时形成风暴。
     */
    private static void enforceMismatchCheck(int sawValue, int want) {
        sSamValue = sawValue;
        if (sawValue == want) return;
        sSamMismatch++;
        HookStatus.put("enforceMismatch", sSamMismatch);
        long now = SystemClock.elapsedRealtime();
        if (now - sLastMismatchDispatchAt < 800L) return;
        sLastMismatchDispatchAt = now;
        MinFpsState.retryEnforce();
    }

    /** `enforce=sam|panel|none`：sam 优先（最具体），其次 panel 数组写入，最后 none。 */
    private static void markEnforcePanel() {
        if (!"sam".equals(HookStatus.get("enforce"))) {
            HookStatus.put("enforce", "panel");
        }
    }

    /** 重试下发序列用：vendor 重算一次（不改档位，只触发 re-evaluate）。 */
    static void nudgeFromEnforcer() {
        nudgeRefreshRate();
    }

    private static volatile int sPanelLogs;

    private static int currentPeakHz() {
        ContentResolver cr = Sys.cr();
        if (cr == null) return -1;
        try {
            float f = Settings.System.getFloat(cr, "peak_refresh_rate", 0f);
            return f > 0 ? Math.round(f) : -1;
        } catch (Throwable t) {
            HookStatus.fail("peak-hz", t);
            return -1;
        }
    }

    /**
     * ④-b：reserve 表（`mReserveMinfpslevel1`）三态（lead 2026-10-25 定稿）。
     *
     * <table>
     *   <tr><th>档位/目标</th><th>动作</th><th>reserveMode</th></tr>
     *   <tr><td>`!takeover`（120/智能/60/90）</td><td>原样透传原厂表（R2 零改写）</td><td>stock</td></tr>
     *   <tr><td>`takeover && targetHz<=0`（无固定、force=0、无白名单）</td>
     *       <td>写 `LOW_IDLE_RESERVE_TABLE`（全 1）→ 165 档保留 LTPO，静置回 <b>1Hz</b></td>
     *       <td>idle1</td></tr>
     *   <tr><td>`takeover && targetHz>0`（固定帧率 / force=1 / 白名单前台）</td>
     *       <td>target>=165 → 全 165 表；0&lt;target&lt;165 → 透传（该形态未经实机验证，见 A4）</td>
     *       <td>target:&lt;n&gt;</td></tr>
     * </table>
     */
    private static final int[] ORIGINAL_RESERVE_TABLE =
            {10, 33, 33, 165, 33, 165, 33, 165, 33, 165, 33, 0, 0};
    /** P1-3：字段契约是 int[13]（copyReserveLevelsToModes 按 arr.length 循环）→ 11 项值 + 尾部 0,0。 */
    private static final int[] FORCE_165_RESERVE_TABLE =
            {165, 165, 165, 165, 165, 165, 165, 165, 165, 165, 165, 0, 0};
    /** 165 档 + LTPO：reserve 下限全 1 → 静置可落到 1Hz（v1.0.5 已验证形态）。 */
    private static final int[] LOW_IDLE_RESERVE_TABLE =
            {10, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 0, 0};

    /** 与 FORCE_165_RESERVE_TABLE 同构、按目标值填充（用于固定 60/90/120）。 */
    private static int[] fillReserve(int value) {
        int[] t = new int[13];
        for (int i = 0; i < 11; i++) t[i] = value;      // [11]=[12]=0（原厂尾巴）
        return t;
    }
    private static volatile int sReserveApplied;
    private static final ThreadLocal<ArrayDeque<Object[]>> RESERVE_SCOPE =
            new ThreadLocal<ArrayDeque<Object[]>>();

    private static void hookNativeReserveTable(ClassLoader cl) throws Throwable {
        Class<?> rus = XposedHelpers.findClass(
                "com.android.server.display.feature.panel.hwdpfeatures.OplusHwDisplayRusAdfr", cl);
        Method update = rus.getDeclaredMethod("updateAdfrXmlConfig", String.class);
        if (update.getReturnType() != Void.TYPE) throw new NoSuchMethodException("bad-signature");
        int n = XposedBridge.hookMethodCount(update, new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam p) {
                ArrayDeque<Object[]> stack = RESERVE_SCOPE.get();
                if (stack == null) { stack = new ArrayDeque<Object[]>(); RESERVE_SCOPE.set(stack); }
                Object[] frame = new Object[3];
                stack.push(frame);
                if (p.args != null && p.args.length == 1 && p.args[0] instanceof String) {
                    MinFpsState.noteRus(p.thisObject, (String) p.args[0]);
                }
                // 三态 ①：非接管档 → 原样透传原厂表（R2 零改写）
                if (!Gear.takeover()) {
                    // R2：stock 档一律透传，绝不写表（sReserveStockWrites 必须恒为 0）
                    HookStatus.stockPass("reserve");
                    sReserveStockCalls++;
                    HookStatus.put("reserveMode", "stock");
                    HookStatus.put("reserveTarget", "stock-gear");
                    HookStatus.put("reserveStockCalls", sReserveStockCalls);
                    HookStatus.put("reserveWritesInStock", sReserveStockWrites);
                    return;
                }
                int target = MinFpsState.targetHz();
                if (target < 165 && want165(target)) {
                    target = 165;                 // force / 白名单 / 转场窗口：按 165 下发 reserve 表
                    HookStatus.applied("reserve-force-165");
                }
                HookStatus.put("reserveTarget", target);
                int[] replacement;
                String mode;
                if (target <= 0) {
                    // 三态 ②：165 档 + 无固定 + force=0 + 无白名单 → 全 1 下限（静置 1Hz）
                    replacement = LOW_IDLE_RESERVE_TABLE.clone();
                    mode = "idle1";
                } else if (target >= 165) {
                    // 三态 ③-a：固定 165 / force=1 / 白名单 → 全 165
                    replacement = FORCE_165_RESERVE_TABLE.clone();
                    mode = "target:" + target;
                } else {
                    // 三态 ③-b：固定 60/90/120（<165）→ 与 165 表同构、按目标值填。
                    // 原厂 reserve 表里含 165 项，若不一起压下来会把 min_fps 顶回 165，
                    // 导致「固定 60 实际 165」（lead 验收标准要求 min_fps=60）。
                    replacement = fillReserve(target);
                    mode = "target:" + target;
                }
                HookStatus.put("reserveMode", mode);
                if (p.args == null || p.args.length != 1 || !(p.args[0] instanceof String)) return;
                Object config = Reflect.field(p.thisObject, "mAdfrXmlConfig");
                if (!Integer.valueOf(0).equals(Reflect.field(config, "mPanelId"))
                        || !Integer.valueOf(1).equals(Reflect.field(config, "mEnable"))
                        || !Integer.valueOf(165).equals(Reflect.field(config, "mReserveMode"))) {
                    HookStatus.stockPass("reserve");
                    HookStatus.put("reserveGate", "unverified-config-preserved");
                    return;
                }
                Object value = Reflect.field(config, "mReserveMinfpslevel1");
                if (!(value instanceof int[])) {
                    HookStatus.put("reserveGate", "table-unavailable");
                    return;
                }
                int[] original = (int[]) value;
                if (Arrays.equals(original, replacement)) {
                    // 已经是目标形态（上一次 restore 失败/厂商自己就是这个形态）
                    HookStatus.put("reserveGate", "already-" + mode);
                    return;
                }
                if (!Arrays.equals(original, ORIGINAL_RESERVE_TABLE)
                        && !Arrays.equals(original, LOW_IDLE_RESERVE_TABLE)
                        && !Arrays.equals(original, FORCE_165_RESERVE_TABLE)) {
                    HookStatus.stockPass("reserve");
                    HookStatus.put("reserveGate", "unverified-table-preserved");
                    return;
                }
                replacement = replacement.clone();
                if (!Reflect.write(config, "mReserveMinfpslevel1", replacement)) {
                    HookStatus.fail("reserve", "field-write-unavailable");
                    return;
                }
                frame[0] = config;
                frame[1] = original;
                frame[2] = replacement;
                sReserveApplied++;
                HookStatus.applied("reserve");
                HookStatus.put("reserveApplied", sReserveApplied);
            }
            @Override protected void afterHookedMethod(MethodHookParam p) {
                ArrayDeque<Object[]> stack = RESERVE_SCOPE.get();
                if (stack == null || stack.isEmpty()) return;
                Object[] frame = stack.pop();
                try {
                    if (frame[0] == null) return;
                    if (Reflect.field(frame[0], "mReserveMinfpslevel1") != frame[2]) return;
                    if (!Reflect.write(frame[0], "mReserveMinfpslevel1", frame[1])) {
                        HookStatus.fail("reserve-restore", "model-restore-failed");
                    }
                } finally {
                    if (stack.isEmpty()) RESERVE_SCOPE.remove();
                }
            }
        });
        installed("reserve", n);
    }

    /**
     * ④-c：vendor 计算 saMinFps 的最终取点 —— **持续兜底**（P0-A）。
     *
     * <p>只靠 panel 数组写一次不够：vendor 后续自己重算时会用原厂表覆盖
     * （实机：`force=1` 时 `panelMinFps=165` 但 `/sys/.../min_fps` 仍是 1）。
     * 所以这里恢复 v1.0.5 的「每次求值都返回目标值」语义，与数组写入并存：
     * <ul>
     *   <li>非 takeover → 不动（R2，`enforce=stock`）；</li>
     *   <li>takeover + 有目标（force / 白名单 / 固定）→ 返回目标值；</li>
     *   <li>takeover + idle1（target<=0）→ 返回 1（LTPO 下限放开）。</li>
     * </ul>
     * 自证：`enforce=sam|stock|none`、`enforceApplied=<n>`、`samCalls=<n>`、`samHook=<n>`。
     */
    private static void hookSamMinFps(ClassLoader cl) throws Throwable {
        Class<?> strat = XposedHelpers.findClass("com.oplus.vrr.OPlusBackLightStrategy", cl);
        int n = XposedBridge.hookAllMethods(strat, "getSAMinFpsByNit", new XC_MethodHook() {
            @Override protected void afterHookedMethod(MethodHookParam p) {
                if (p.getThrowable() != null) return;
                sSamCalls++;
                HookStatus.put("samCalls", sSamCalls);
                if (!Gear.takeover()) {
                    HookStatus.stockPass("sam-min-fps");
                    HookStatus.put("enforce", "stock");
                    return;
                }
                int target = MinFpsState.targetHz();
                // A2：idle1（无固定、无 force/白名单、非转场）→ 不接管，绝不主动压 1
                if (target <= 0 && !want165(target)) {
                    HookStatus.stockPass("sam-min-fps");
                    HookStatus.put("samMode", "leave-vendor");
                    return;
                }
                int want = target > 0 ? target : 165;
                Object r = p.getResult();
                int cur = r instanceof Number ? ((Number) r).intValue() : -1;
                if (cur == want) {
                    HookStatus.stockPass("sam-min-fps");
                    HookStatus.put("enforce", "sam");
                    HookStatus.put("samValue", cur);
                    MinFpsState.noteFgSwitchEffective();      // 生效时刻 → 算 fgSwitchLagMs
                    enforceMismatchCheck(cur, want);
                    return;
                }
                p.setResult(Integer.valueOf(want));
                sSamApplied++;
                HookStatus.applied("sam-min-fps");
                HookStatus.put("enforce", "sam");
                HookStatus.put("enforceApplied", sSamApplied);
                HookStatus.put("samValue", want);
                HookStatus.put("samFrom", cur);
                HookStatus.put("enforceMismatch", sSamMismatch);
                // P0：vendor 侧原值与目标不一致 → 对账后再补一次下发（800ms 限频）
                enforceMismatchCheck(cur, want);
            }
        });
        HookStatus.put("samHook", n);
        installed("sam-min-fps", n);
    }

    /** ③ 结果兜底（必做，裁定 D）：`private float getPerfectRefreshRate(SparseArray,int,boolean)`。 */
    private static void hookPerfectRefreshRate(ClassLoader cl) throws Throwable {
        Class<?> core = XposedHelpers.findClass(
                "com.android.server.wm.OplusRefreshRateCore", cl);
        Method m = core.getDeclaredMethod("getPerfectRefreshRate",
                android.util.SparseArray.class, int.class, boolean.class);
        int n = XposedBridge.hookMethodCount(m, new XC_MethodHook() {
            @Override protected void afterHookedMethod(MethodHookParam p) {
                if (p.getThrowable() != null) return;
                if (!Gear.takeover()) { HookStatus.stockPass("perfect-rate"); return; }
                if (!resultHookEnabled()) { HookStatus.stockPass("perfect-rate"); return; }
                Object raw = p.getResult();
                float cur = raw instanceof Number ? ((Number) raw).floatValue() : -1f;
                // ① video-fixed：result != 目标 → 改目标（抬高或压低都改）
                int id = VideoFixed.effectiveRateId();
                if (id > 0) {
                    float hz = VideoFixed.effectiveHz();
                    if (hz > 0) {
                        if (Math.abs(cur - hz) < 0.5f) { HookStatus.stockPass("perfect-rate"); return; }
                        p.setResult(Float.valueOf(hz));
                        HookStatus.applied("perfect-rate");
                        return;
                    }
                }
                // ② 白名单 / force（含转场窗口）：目标 165 但结果 <164.5 → 顶到 165
                int t = MinFpsState.targetHz();
                boolean byTarget = t >= 165;
                if (cur < 164.5f && allow165(byTarget || want165(t))) {
                    p.setResult(Float.valueOf(165f));
                    HookStatus.applied("perfect-rate");
                    HookStatus.applied("perfect-force");
                    noteTransWinApplied(byTarget);
                    return;
                }
                HookStatus.stockPass("perfect-rate");
            }
        });
        installed("perfect-rate", n);
    }

    private static volatile long sResultHookAt;
    private static volatile boolean sResultHook = true;

    /** `refresh165_video_result_hook=0` 一键关掉 ③（K22/K28）。 */
    private static boolean resultHookEnabled() {
        long now = SystemClock.elapsedRealtime();
        if (now - sResultHookAt < 5000L) return sResultHook;
        sResultHookAt = now;
        ContentResolver cr = Sys.cr();
        if (cr != null) {
            try {
                sResultHook = Settings.Global.getInt(cr, "refresh165_video_result_hook", 1) != 0;
            } catch (Throwable t) {
                HookStatus.fail("result-hook-flag", t);
            }
        }
        HookStatus.put("videoResultHook", sResultHook ? 1 : 0);
        return sResultHook;
    }

    /** DC 上限解除：只在接管时（120 档保留「DC 上限 120」这一原生行为）。 */
    private static void hookDcController(ClassLoader cl) throws Throwable {
        Class<?> dc = XposedHelpers.findClass(
                "com.android.server.wm.OplusRefreshRateDCController", cl);
        int n = XposedBridge.hookAllMethods(dc, "setLimitMaxRefreshRateId", new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam p) {
                if (!Gear.takeover()) { HookStatus.stockPass("dc-cap"); return; }
                if (p.args == null || p.args.length != 1 || !(p.args[0] instanceof Integer)) return;
                p.args[0] = Integer.valueOf(0);      // 0 = vendor 的无上限哨兵
                HookStatus.applied("dc-cap");
            }
        });
        n += XposedBridge.hookAllMethods(dc, "getDCLimitRefreshRateId", new XC_MethodHook() {
            @Override protected void afterHookedMethod(MethodHookParam p) {
                if (!Gear.takeover()) { HookStatus.stockPass("dc-cap-get"); return; }
                if (p.getThrowable() != null || p.args == null || p.args.length != 1
                        || !(p.args[0] instanceof Integer)) return;
                p.setResult(p.args[0]);
                HookStatus.applied("dc-cap-get");
            }
        });
        installed("dc-cap", n);
    }

    // ================================================================= 动画/启动加速

    private static final ThreadLocal<ArrayDeque<Boolean>> BOOST_SCOPE =
            new ThreadLocal<ArrayDeque<Boolean>>();

    private static void pushBoost(boolean eligible) {
        ArrayDeque<Boolean> frames = BOOST_SCOPE.get();
        if (frames == null) { frames = new ArrayDeque<Boolean>(); BOOST_SCOPE.set(frames); }
        frames.push(Boolean.valueOf(eligible));
    }

    private static void popBoost() {
        ArrayDeque<Boolean> frames = BOOST_SCOPE.get();
        if (frames != null && !frames.isEmpty()) frames.pop();
        if (frames == null || frames.isEmpty()) BOOST_SCOPE.remove();
    }

    private static boolean insideBoost() {
        ArrayDeque<Boolean> frames = BOOST_SCOPE.get();
        return frames != null && Boolean.TRUE.equals(frames.peek());
    }

    private static void hookOrdinaryAnimationBoost(ClassLoader cl) throws Throwable {
        Class<?> policy = XposedHelpers.findClass(
                "com.android.server.wm.OplusRefreshRatePolicyImpl", cl);
        Class<?> utils = XposedHelpers.findClass(
                "com.android.server.wm.OplusRefreshRateUtils", cl);
        int n = XposedBridge.hookAllMethods(policy, "startAppSwitchBoost", new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam p) {
                boolean eligible = false;
                if (Gear.takeover() && p.args != null && p.args.length == 1) {
                    Object pkg = Reflect.field(p.args[0], "packageName");
                    if (pkg instanceof String) {
                        try {
                                // Keep the 165 app-switch boost for the
                                // desktop only. A returning game or ordinary
                                // app must retain its own native request.
                                eligible = PerApp.isLauncher((String) pkg)
                                        && Boolean.FALSE.equals(XposedHelpers.callMethod(
                                                p.thisObject, "isActivityDisableBoostForHome", p.args[0]));
                        } catch (Throwable t) {
                            HookStatus.fail("boost-home-check", t);
                        }
                    }
                }
                pushBoost(eligible);
            }
            @Override protected void afterHookedMethod(MethodHookParam p) { popBoost(); }
        });
        n += XposedBridge.hookAllMethods(policy, "requestRefreshRate", new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam p) {
                boolean eligible = false;
                boolean animPrio = p.args != null && p.args.length == 4
                        && (Integer.valueOf(40).equals(p.args[0])
                            || Integer.valueOf(90).equals(p.args[0]))
                        && p.args[2] instanceof Float;
                if (animPrio && Gear.takeover()) {
                    try {
                        Object source = XposedHelpers.callStaticMethod(utils, "getRequestSource",
                                Reflect.field(p.thisObject, "mContext"));
                        eligible = source instanceof String && PerApp.isLauncher((String) source);
                    } catch (Throwable t) {
                        HookStatus.fail("boost-source", t);
                    }
                }
                pushBoost(eligible);
                if (eligible) {
                    p.args[2] = Float.valueOf(165f);
                    HookStatus.applied("anim-boost");
                } else {
                    HookStatus.stockPass("anim-boost");
                }
            }
            @Override protected void afterHookedMethod(MethodHookParam p) { popBoost(); }
        });
        n += XposedBridge.hookAllMethods(policy, "getLauncherRateId", new XC_MethodHook() {
            @Override protected void afterHookedMethod(MethodHookParam p) {
                if (p.getThrowable() != null) return;
                if (!insideBoost() || !Gear.takeover()) return;
                if (!Integer.valueOf(3).equals(p.getResult())) return;
                if (p.args == null || p.args.length != 1) return;
                if (!Integer.valueOf(3).equals(p.args[0]) && !Integer.valueOf(7).equals(p.args[0])) return;
                p.setResult(Integer.valueOf(7));
                HookStatus.applied("launcher-rate-id");
            }
        });
        n += XposedBridge.hookAllMethods(policy, "adjustRefreshForDefaultMax", new XC_MethodHook() {
            @Override protected void afterHookedMethod(MethodHookParam p) {
                if (p.getThrowable() != null) return;
                if (!insideBoost() || !Gear.takeover()) return;
                if (!Integer.valueOf(3).equals(p.getResult())) return;
                if (p.args == null || p.args.length != 2) return;
                if (!"limit appSwitchBoost".equals(p.args[1])) return;
                if (!Integer.valueOf(3).equals(p.args[0]) && !Integer.valueOf(7).equals(p.args[0])) return;
                p.setResult(Integer.valueOf(7));
                HookStatus.applied("anim-max");
            }
        });
        installed("anim-boost", n);
    }

    // ================================================================= 软约束例外（DC / touch-idle）

    private static volatile int sDcResetSuppressed;
    private static volatile int sTouchIdleApplied;
    private static volatile int sTouchIdleRecheck;

    /** 软约束 `dc`（DESIGN §3-#19/#23）：跳过 EP/DC 复位，不写票、不写帧率表。 */
    private static void preserveFullBrightnessDc(ClassLoader cl) throws Throwable {
        Class<?> manager = XposedHelpers.findClass(
                "com.oplus.vrr.OPlusExternalRefreshRateManager", cl);
        Method reset = manager.getDeclaredMethod("handleDcModeStatus");
        if (reset.getReturnType() != Void.TYPE) throw new NoSuchMethodException("bad-signature");
        int n = XposedBridge.hookMethodCount(reset, new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam p) {
                if (p.args != null && p.args.length != 0) return;
                Object lock = Reflect.field(p.thisObject, "mStateLock");
                if (lock == null) return;
                synchronized (lock) {
                    Object mode = Reflect.field(p.thisObject, "mCurrentEPMode");
                    Object enabled = Reflect.field(p.thisObject, "mIsGameDcFpsEnable");
                    if (Integer.valueOf(2).equals(mode) && Boolean.TRUE.equals(enabled)) {
                        p.setResult(null);
                        sDcResetSuppressed++;
                        HookStatus.put("dcResetSuppressed", sDcResetSuppressed);
                    }
                }
            }
        });
        installed("dc-reset", n);
    }

    private static volatile boolean sLowHookInstalled;
    private static final ThreadLocal<ArrayDeque<Object[]>> LOW_SCOPE =
            new ThreadLocal<ArrayDeque<Object[]>>();

    /** 软约束 `touchIdle`（DESIGN §3-#19/#24）：保留功能，只加计数与日志。 */
    private static void allowLowBrightnessDcIdle(ClassLoader cl) throws Throwable {
        Class<?> manager = XposedHelpers.findClass("com.oplus.vrr.OPlusExternalRefreshRateManager", cl);
        Method config = manager.getDeclaredMethod("handleTouchIdleConfig", Boolean.TYPE);
        if (config.getReturnType() != Void.TYPE) throw new NoSuchMethodException("bad-signature");
        int n = XposedBridge.hookMethodCount(config, new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam p) {
                ArrayDeque<Object[]> frames = LOW_SCOPE.get();
                if (frames == null) { frames = new ArrayDeque<Object[]>(); LOW_SCOPE.set(frames); }
                Object[] frame = new Object[]{p.thisObject, Boolean.FALSE};
                frames.push(frame);
                PerApp.noteMirror(p.thisObject);
                Object lock = Reflect.field(p.thisObject, "mStateLock");
                if (lock == null || p.args == null || p.args.length != 1
                        || !(p.args[0] instanceof Boolean)) return;
                synchronized (lock) {
                    if (!"ordinary-30nit".equals(lowIdleReason(p.thisObject))) return;
                    if (!Boolean.FALSE.equals(Reflect.field(p.thisObject, "mTouchIdleHwBacklightEnough"))) {
                        return;
                    }
                    if (Reflect.write(p.thisObject, "mTouchIdleHwBacklightEnough", Boolean.TRUE)) {
                        frame[1] = Boolean.TRUE;
                        sTouchIdleApplied++;
                        HookStatus.put("touchIdleApplied", sTouchIdleApplied);
                    } else {
                        HookStatus.fail("touch-idle", "field-write-unavailable");
                    }
                }
            }
            @Override protected void afterHookedMethod(MethodHookParam p) {
                ArrayDeque<Object[]> frames = LOW_SCOPE.get();
                if (frames == null || frames.isEmpty()) return;
                Object[] frame = frames.pop();
                try {
                    if (!Boolean.TRUE.equals(frame[1])) return;
                    Object lock = Reflect.field(frame[0], "mStateLock");
                    if (lock == null) return;
                    synchronized (lock) {
                        if (!Reflect.write(frame[0], "mTouchIdleHwBacklightEnough", Boolean.FALSE)) {
                            HookStatus.fail("touch-idle-restore", "restore-failed");
                        }
                    }
                } finally {
                    if (frames.isEmpty()) LOW_SCOPE.remove();
                }
            }
        });
        sLowHookInstalled = n > 0;

        XC_MethodHook recheck = new XC_MethodHook() {
            @Override protected void afterHookedMethod(MethodHookParam p) {
                if (p.getThrowable() != null) return;
                Object lock = Reflect.field(p.thisObject, "mStateLock");
                if (lock == null) return;
                synchronized (lock) {
                    if (!"ordinary-30nit".equals(lowIdleReason(p.thisObject))) return;
                    sTouchIdleRecheck++;
                    HookStatus.put("touchIdleRecheck", sTouchIdleRecheck);
                    try {
                        XposedHelpers.callMethod(p.thisObject, "handleTouchIdleConfig", Boolean.FALSE);
                    } catch (Throwable t) {
                        HookStatus.fail("touch-idle-recheck", t);
                    }
                }
            }
        };
        n += XposedBridge.hookAllMethods(manager, "handleBacklightChange", recheck);
        n += XposedBridge.hookAllMethods(manager, "setDynamicTeStatusFromEpChange", recheck);
        n += XposedBridge.hookAllMethods(manager, "setDarkMode", recheck);
        installed("touch-idle", n);
    }

    private static String lowIdleReason(Object manager) {
        Object nits = Reflect.field(manager, "mNits");
        if (!(nits instanceof Float) || !((Float) nits >= 0f && (Float) nits < 30f)) {
            return "not-low-or-unknown";
        }
        if (!Integer.valueOf(2).equals(Reflect.field(manager, "mCurrentEPMode"))
                || !Boolean.TRUE.equals(Reflect.field(manager, "mIsGameDcFpsEnable"))
                || !Boolean.FALSE.equals(Reflect.field(manager, "mPwmMode"))) return "not-full-dc";
        if (!Boolean.FALSE.equals(Reflect.field(manager, "mIsDarkMode"))) {
            return "dark-mode-policy-preserved";
        }
        Object bean = Reflect.field(manager, "mTouchIdleBean");
        if (!Integer.valueOf(30).equals(Reflect.field(bean, "mNitLimit"))
                || !Integer.valueOf(-1).equals(Reflect.field(bean, "mBrightnessLimit"))) {
            return "different-brightness-policy";
        }
        Object limits = Reflect.field(bean, "mSpecialHwNitLimitMap");
        Object pkg = Reflect.field(manager, "mCurPkgName");
        if (!(limits instanceof java.util.Map) || !(pkg instanceof String) || ((String) pkg).isEmpty()) {
            return "unknown-app-policy";
        }
        if (((java.util.Map<?, ?>) limits).containsKey(pkg)) return "special-app-limit-preserved";
        try {
            Object histogramLimit = XposedHelpers.callMethod(manager, "getMaxNitFromOtiKpMaps");
            if (histogramLimit != null && (!(histogramLimit instanceof Float)
                    || !((Float) histogramLimit >= 0f && (Float) histogramLimit <= 30f))) {
                return "higher-or-unknown-histogram-limit-preserved";
            }
        } catch (Throwable t) {
            return "histogram-policy-unavailable";
        }
        return "ordinary-30nit";
    }

    // ================================================================= 观察者 / 档位归一

    private static volatile boolean sObserversInstalled;
    /** P0-B：进入 stock 档时的基线，用来证明「120 档零改写」（累积计数器会误导）。 */
    private static volatile boolean sLastGearTakeover;
    private static volatile boolean sGearSeen;
    private static volatile int sPanelVerSeqBase;
    private static volatile int sIdle1Base;
    private static volatile int sPanel234Base;
    private static volatile int sReserveStockCalls;

    /**
     * gear 变化钩子（由 Gear.decide 调用）：进入 stock 档时打基线，
     * 并发布「stock 档内的增量」——这是 R2 的唯一有效判据（累积值含 165 档的历史）。
     */
    static void noteGearTransition(boolean takeover) {
        boolean prev = sLastGearTakeover;
        sLastGearTakeover = takeover;
        if (!sGearSeen) {
            sGearSeen = true;
            sPanelVerSeqBase = sPanelVerSeq;
            sIdle1Base = sPanelIdle1Applied;
            sPanel234Base = HookStatus.appliedCount("panel-234");
        }
        if (prev && !takeover) {
            sPanelVerSeqBase = sPanelVerSeq;
            sIdle1Base = sPanelIdle1Applied;
            sPanel234Base = HookStatus.appliedCount("panel-234");
            // 展示字段归位（避免把 165 档留下的最后一个形态当成 stock 的现状）
            HookStatus.put("panelMinFps", -1);
            HookStatus.put("minFpsTarget", -1);
            HookStatus.put("reserveMode", "stock");
            HookStatus.put("enforce", "stock");
            HookStatus.put("gearTransition", "to-stock");
        } else if (!prev && takeover) {
            HookStatus.put("gearTransition", "to-takeover");
        }
        HookStatus.put("panelVerSeqDeltaStock", sPanelVerSeq - sPanelVerSeqBase);
        HookStatus.put("panel234StockApplied",
                HookStatus.appliedCount("panel-234") - sPanel234Base);
        HookStatus.put("panelIdle1StockApplied", sPanelIdle1Applied - sIdle1Base);
        HookStatus.put("reserveWritesInStock", sReserveStockWrites);
    }
    private static volatile long sLastPickWriteAt;
    private static volatile int sPickNormalized;

    /**
     * 观察者注册（P0 修复）。
     *
     * <p>根因：开机早期 `ActivityThread.getSystemContext()` 已经非 null，但它的 ContentResolver 后端
     * （`IContentService`）尚未发布 → 首次 `registerContentObserver` 抛
     * `NullPointerException: IContentService ... on a null object reference`。
     * 上一版只在「context == null」时重试，于是这种「context 有、resolver 坏」的窗口被一次打死，
     * 三个观察者全部失效（force 开关/档位/kill switch 都不响应）。
     *
     * <p>修法（与 v1.0.5 同思路）：一次性 daemon 线程，每 1.5s **重试注册本身**，最多 60 次；
     * 部分注册成功后失败会先回滚已注册的观察者，避免重复注册。成功后再主动派发一次状态。
     */
    private static void installObservers() {
        if (sObserversInstalled) return;
        sObserversInstalled = true;
        Thread t = new Thread(new Runnable() {
            @Override public void run() {
                for (int attempt = 1; attempt <= OBSERVER_MAX_ATTEMPTS; attempt++) {
                    sObserverRetries = attempt;
                    HookStatus.put("observersRetry", attempt);
                    try {
                        if (registerAllObservers()) {
                            HookStatus.recovered("observers");
                            HookStatus.put("observers", "installed@" + attempt);
                            HookStatus.put("observerUris", "force,kill,pick,app165");
                            primeAfterObservers();
                            return;
                        }
                    } catch (Throwable e) {
                        noteObserverFailure(e);
                    }
                    try {
                        Thread.sleep(OBSERVER_RETRY_MS);
                    } catch (InterruptedException ie) {
                        return;
                    }
                }
                HookStatus.put("observers", "gave-up");
                HookStatus.failInstall("observers", "gave-up-after-" + OBSERVER_MAX_ATTEMPTS
                        + "; last=" + sObserverLastError);
            }
        }, "Refresh165Observers");
        t.setDaemon(true);
        t.start();
    }

    private static final int OBSERVER_MAX_ATTEMPTS = 60;
    private static final long OBSERVER_RETRY_MS = 1500L;
    private static volatile int sObserverRetries;
    private static volatile String sObserverLastError = "none";

    private static void noteObserverFailure(Throwable t) {
        String message = t == null ? "unknown"
                : t.getClass().getSimpleName() + ": " + String.valueOf(t.getMessage());
        sObserverLastError = message;
        HookStatus.put("observersLastError", message);
    }

    /** 注册 4 类观察者；任一失败则回滚本轮已注册的，返回 false 让外层重试。 */
    private static boolean registerAllObservers() {
        Context ctx = Sys.context();
        if (ctx == null) {
            HookStatus.put("observers", "waiting-context");
            return false;
        }
        Sys.setContext(ctx);
        final ContentResolver cr;
        try {
            cr = ctx.getContentResolver();
        } catch (Throwable t) {
            HookStatus.put("observers", "no-resolver");
            return false;
        }
        if (cr == null) {
            HookStatus.put("observers", "no-resolver");
            return false;
        }
        android.os.Handler handler = new android.os.Handler(android.os.Looper.getMainLooper());
        java.util.List<android.database.ContentObserver> registered =
                new java.util.ArrayList<android.database.ContentObserver>();
        try {
            // force 开关：三件事一起挡在 takeover 之后（§3-#25）
            android.database.ContentObserver forceObs =
                    new android.database.ContentObserver(handler) {
                        @Override public void onChange(boolean selfChange) {
                            Gear.invalidateForce();
                            if (!Gear.takeover()) {
                                HookStatus.stockPass("force-observer");
                                return;
                            }
                            Gear.invalidate();
                            boolean on = false;
                            try {
                                on = android.provider.Settings.Global.getInt(
                                        Sys.cr(), "refresh165_force", 0) == 1;
                            } catch (Throwable ignored) { }
                            MinFpsState.applyForceFloor(on);
                            MinFpsState.dispatchTargetChange("force");
                            // 让 vendor 真正重算一次 mode（min 设置本身不会被驱动立刻采纳）
                            MinFpsState.forceToggleNudge();
                            Reapply.modeForce(on ? "force-on" : "force-off");
                        }
                    };
            cr.registerContentObserver(Settings.Global.getUriFor("refresh165_force"),
                    false, forceObs);
            registered.add(forceObs);

            // L3 kill switch：立即失效判定缓存
            android.database.ContentObserver killObs =
                    new android.database.ContentObserver(handler) {
                        @Override public void onChange(boolean selfChange) {
                            Gear.invalidate();
                            Gear.decide();
                        }
                    };
            cr.registerContentObserver(Settings.Global.getUriFor("refresh165_disable"),
                    false, killObs);
            registered.add(killObs);

            // 档位：失效判定缓存 + 8→7 幂等归一（500ms 去抖）
            android.database.ContentObserver pickObs =
                    new android.database.ContentObserver(handler) {
                        @Override public void onChange(boolean selfChange) {
                            Gear.invalidate();
                            Gear.decide();
                            normalizePickOnce(cr);
                        }
                    };
            cr.registerContentObserver(
                    Settings.Secure.getUriFor("oplus_customize_screen_refresh_rate"),
                    false, pickObs);
            registered.add(pickObs);

            if (!VideoRate.installObserver(ctx)) {
                throw new IllegalStateException("video-observer-unavailable");
            }
            if (!PerApp.installObserver(ctx)) {
                throw new IllegalStateException("app165-observer-unavailable");
            }
            return true;
        } catch (Throwable t) {
            noteObserverFailure(t);
            for (android.database.ContentObserver o : registered) {
                try {
                    cr.unregisterContentObserver(o);
                } catch (Throwable ignored) {
                    // 回滚失败无副作用（下一次成功注册会覆盖语义）
                }
            }
            return false;
        }
    }

    /**
     * 注册成功后主动派发一次（P0 修复 item 2）：开机后不必等下一次设置事件。
     * 顺序：force 缓存失效 → 判定重算 → min_fps 目标重算 → 一次峰值修复。
     */
    private static void primeAfterObservers() {
        try {
            recordModuleVersion();
            Gear.invalidateForce();
            Gear.invalidate();
            Gear.decide();
            MinFpsState.primeAfterBoot();
            PeakRepair.once();
            HookStatus.applied("observers-primed");
            HookStatus.put("observersPrimed", 1);
        } catch (Throwable t) {
            HookStatus.fail("observers-prime", t);
        }
    }

    /** §7.3：只有 !hasNative185Slot() 时才把 8 归一为 7，且带 500ms 去抖（K6）。 */
    private static void normalizePickOnce(ContentResolver cr) {
        try {
            int v = Settings.Secure.getInt(cr, "oplus_customize_screen_refresh_rate", 0);
            if (v != Gear.SETTING_MODE_EXTREME) return;
            if (RateMap.hasNative185Slot()) return;
            if (!Gear.takeover()) return;
            long now = SystemClock.elapsedRealtime();
            if (now - sLastPickWriteAt < 500L) return;
            sLastPickWriteAt = now;
            if (Settings.Secure.putInt(cr, "oplus_customize_screen_refresh_rate",
                    Gear.SETTING_MODE_165)) {
                sPickNormalized++;
                HookStatus.applied("pick-normalized");
                HookStatus.put("pickNormalized", sPickNormalized);
            }
        } catch (Throwable t) {
            HookStatus.fail("pick-normalize", t);
        }
    }

    /**
     * 让 vendor 立刻重算一次（§3-#15）：只在接管内、只调 updateCurrentSettingMode，
     * **删掉 peak 抖动**（避免任何档位都瞬态改写 peak）。
     */
    private static void nudgeRefreshRate() {
        if (!Gear.takeover()) { HookStatus.stockPass("nudge"); return; }
        Object policy = PolicyRef.policy();
        if (policy == null) {
            HookStatus.fail("nudge", "no-policy");
            return;
        }
        int mode = Gear.settingMode();
        if (mode < 0) {
            HookStatus.fail("nudge", "no-setting-mode");
            return;
        }
        try {
            Method m = Sys.method(policy.getClass(), "updateCurrentSettingMode", int.class, boolean.class);
            if (m == null) { HookStatus.fail("nudge", "no-method"); return; }
            m.invoke(policy, Integer.valueOf(mode), Boolean.TRUE);
            HookStatus.applied("nudge");
        } catch (Throwable t) {
            HookStatus.fail("nudge", t);
        }
    }

    // ================================================================= settings 进程

    private static void hookSettingsPage(ClassLoader cl) throws Throwable {
        Class<?> frag = XposedHelpers.findClass(
                "com.oplus.settings.feature.display.ScreenRefreshRateFragment", cl);
        install("ui-shell", new Body() {
            @Override public void run() throws Throwable { SettingsPages.install(cl); }
        });
        install("ui-extreme-level", new Body() {
            @Override public void run() throws Throwable { hookExtremeLevel(cl); }
        });
        install("ui-gear", new Body() {
            @Override public void run() throws Throwable { hookNormalizeGear(cl); }
        });
        install("ui-visible", new Body() {
            @Override public void run() throws Throwable { hookHighRefreshVisible(cl); }
        });
        if (!HookStatus.isInstalled("ui-extreme-level")) {
            // legacy 兜底（裁定 C-2）：只抬 args[0]，不动 args[1]
            install("ui-legacy", new Body() {
                @Override public void run() throws Throwable { hookLegacySummary(cl, frag); }
            });
            HookStatus.put("r5ui", "legacy");
        } else {
            HookStatus.put("r5ui", "hook-extreme-level");
        }
        hookSettingsOnCreate(cl, frag);
    }

    /** R5 主通道：hook「读取函数」而不是改 prop（§6.1）。 */
    private static void hookExtremeLevel(ClassLoader cl) throws Throwable {
        Class<?> utils = XposedHelpers.findClass(
                "com.oplus.settings.feature.display.ScreenRefreshUtils", cl);
        int n = XposedBridge.hookAllMethods(utils, "getExtremeRefreshLevelRate",
                new XC_MethodHook() {
                    @Override protected void afterHookedMethod(MethodHookParam p) {
                        if (p.getThrowable() != null) return;
                        if (!RateMap.has165Panel()) return;
                        Object r = p.getResult();
                        if (r instanceof Number && ((Number) r).intValue() > 120) return;
                        p.setResult(Integer.valueOf(165));
                        HookStatus.applied("ui-extreme-level");
                    }
                });
        installed("ui-extreme-level", n);
    }

    /**
     * R5 必需件（N-4）：入参驱动判别式 —— 只有「用户点的就是 165/185 档」才归一为 7；
     * i==3(120)/i==4(144) 一律不动。用 argInt 按类型取参，不假定位置。
     */
    private static void hookNormalizeGear(ClassLoader cl) throws Throwable {
        Class<?> utils = XposedHelpers.findClass(
                "com.oplus.settings.feature.display.ScreenRefreshUtils", cl);
        int n = XposedBridge.hookAllMethods(utils, "normalizeHighRefreshGear",
                new XC_MethodHook() {
                    @Override protected void afterHookedMethod(MethodHookParam p) {
                        if (p.getThrowable() != null) return;
                        Object ctxArg = XposedBridge.argOf(p, Context.class);
                        if (ctxArg instanceof Context) Sys.setContext((Context) ctxArg);
                        if (!RateMap.has165Panel() || RateMap.uiNative185()) return;
                        int i = XposedBridge.argInt(p, -1);
                        if (i != RateMap.ID_165 && i != RateMap.ID_185) {
                            HookStatus.stockPass("ui-gear-165");
                            return;
                        }
                        if (Integer.valueOf(RateMap.ID_165).equals(p.getResult())) return;
                        p.setResult(Integer.valueOf(RateMap.ID_165));
                        HookStatus.applied("ui-gear-165");
                    }
                });
        installed("ui-gear", n);
    }

    /** 裁定 C-3：60/90 档下让「刷新率设定」整块保持可见（只解决可见性，不改档位）。 */
    private static void hookHighRefreshVisible(ClassLoader cl) throws Throwable {
        Class<?> utils = XposedHelpers.findClass(
                "com.oplus.settings.feature.display.ScreenRefreshUtils", cl);
        int n = XposedBridge.hookAllMethods(utils, "isHighRefreshModeIndex",
                new XC_MethodHook() {
                    @Override protected void afterHookedMethod(MethodHookParam p) {
                        if (p.getThrowable() != null) return;
                        if (!RateMap.has165Panel()) return;
                        int i = XposedBridge.argInt(p, 99);
                        if (i > 2) return;
                        if (Boolean.TRUE.equals(p.getResult())) return;
                        p.setResult(Boolean.TRUE);
                        HookStatus.applied("ui-visible");
                    }
                });
        installed("ui-visible", n);
    }

    /**
     * legacy 兜底（裁定 C-2）：只抬 args[0]（extreme），args[1]（choice）保持原值，
     * 于是 120 档自然渲染「120 Hz + 可选 165」；同时抬 setupRefreshRateSettingEntries(int)
     * 的入参，尽量让 165 条目出现。仅在 getExtremeRefreshLevelRate 钩子安装失败时启用。
     */
    private static void hookLegacySummary(ClassLoader cl, Class<?> frag) throws Throwable {
        Method summary = frag.getDeclaredMethod("refreshRateSettingSummary", int.class, int.class);
        int n = XposedBridge.hookMethodCount(summary, new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam p) {
                if (!RateMap.has165Panel()) return;
                if (p.args == null || p.args.length != 2
                        || !(p.args[0] instanceof Integer)) return;
                if (((Integer) p.args[0]).intValue() > 120) return;
                p.args[0] = Integer.valueOf(165);
                HookStatus.applied("ui-legacy-summary");
            }
        });
        n += XposedBridge.hookAllMethods(frag, "setupRefreshRateSettingEntries",
                new XC_MethodHook() {
                    @Override protected void beforeHookedMethod(MethodHookParam p) {
                        if (!RateMap.has165Panel()) return;
                        if (p.args == null || p.args.length != 1
                                || !(p.args[0] instanceof Integer)) return;
                        if (((Integer) p.args[0]).intValue() > 120) return;
                        p.args[0] = Integer.valueOf(165);
                        HookStatus.applied("ui-legacy-entries");
                    }
                });
        installed("ui-legacy", n);
    }

    /** 设置页 onCreate/onResume after：确保常驻开关与视频入口都存在且回到原生设置卡片。 */
    private static void hookSettingsOnCreate(final ClassLoader cl, final Class<?> frag) {
        int n = XposedBridge.hookAllMethods(frag, "onCreate", new XC_MethodHook() {
            @Override protected void afterHookedMethod(MethodHookParam p) {
                if (!frag.isInstance(p.thisObject)) return;
                Object ctxObj;
                Object screen;
                try {
                    ctxObj = XposedHelpers.callMethod(p.thisObject, "getContext");
                    screen = XposedHelpers.callMethod(p.thisObject, "getPreferenceScreen");
                } catch (Throwable t) {
                    HookStatus.fail("ui-row", t);
                    return;
                }
                if (!(ctxObj instanceof Context) || screen == null) return;
                final Context ctx = (Context) ctxObj;
                final Object fragment = p.thisObject;
                final Object screenRef = screen;
                Sys.setContext(ctx);
                install("ui-switch", new Body() {
                    @Override public void run() throws Throwable {
                        addLockSwitch(ctx, screenRef, cl);
                    }
                });
                install("ui-rows", new Body() {
                    @Override public void run() throws Throwable {
                        VideoRateUi.addRows(fragment, screenRef, ctx, cl);
                    }
                });
                install("ui-footer-sync", new Body() {
                    @Override public void run() throws Throwable {
                        VideoRateUi.refreshSummaries(screenRef, ctx);
                    }
                });
            }
        });
        if (n > 0) HookStatus.installed("ui-oncreate");
        else HookStatus.fail("ui-oncreate", "no-method");
        int r = XposedBridge.hookAllMethods(frag, "onResume", new XC_MethodHook() {
            @Override protected void afterHookedMethod(MethodHookParam p) {
                if (!frag.isInstance(p.thisObject)) return;
                try {
                    Object ctxObj = XposedHelpers.callMethod(p.thisObject, "getContext");
                    Object screen = XposedHelpers.callMethod(p.thisObject, "getPreferenceScreen");
                    if (ctxObj instanceof Context && screen != null) {
                        final Context ctx = (Context) ctxObj;
                        Sys.setContext(ctx);
                        // F：幂等重断言只保留 ui-rows；ui-switch 只在 onCreate 注册一次
                        //    （重复安装会把 installed 计数翻倍，且 addLockSwitch 自身已幂等）
                        VideoRateUi.addRows(p.thisObject, screen, ctx,
                                frag.getClassLoader());
                        HookStatus.put("uiResumeRows", "reassert");
                    }
                } catch (Throwable t) {
                    HookStatus.fail("ui-resume", t);
                }
            }
        });
        if (r > 0) HookStatus.installed("ui-resume");
        else HookStatus.fail("ui-resume", "no-method");
    }

    private static void addLockSwitch(final Context ctx, Object screen, ClassLoader cl) throws Throwable {
        final String key = "refresh165_lock";
        Object exist = XposedHelpers.callMethod(screen, "findPreference", key);
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
                break;
            } catch (Throwable t) {
                HookStatus.fail("ui-switch-class-" + cn.substring(cn.lastIndexOf('.') + 1), t);
            }
        }
        if (exist == null && swCls == null) throw new ClassNotFoundException("no switch preference");
        final Object sw = exist != null ? exist : swCls.getConstructor(Context.class).newInstance(ctx);
        if (exist == null) XposedHelpers.callMethod(sw, "setKey", key);
        XposedHelpers.callMethod(sw, "setTitle", "165Hz 常驻（关闭 LTPO）");
        XposedHelpers.callMethod(sw, "setSummary", switchSummary(ctx));
        XposedHelpers.callMethod(sw, "setChecked", Gear.userForce165());
        XposedHelpers.callMethod(sw, "setPersistent", Boolean.FALSE);

        Class<?> listenerCls = XposedHelpers.findClass(
                "androidx.preference.Preference$OnPreferenceChangeListener", cl);
        Object listener = java.lang.reflect.Proxy.newProxyInstance(cl, new Class<?>[]{listenerCls},
                new java.lang.reflect.InvocationHandler() {
                    @Override public Object invoke(Object proxy, java.lang.reflect.Method method,
                                                    Object[] args) {
                        if ("onPreferenceChange".equals(method.getName())
                                && args != null && args.length == 2) {
                            boolean on = Boolean.TRUE.equals(args[1]);
                            try {
                                if (!Settings.Global.putInt(ctx.getContentResolver(),
                                        "refresh165_force", on ? 1 : 0)) {
                                    HookStatus.fail("ui-switch-toggle", "write-rejected");
                                    return Boolean.FALSE;
                                }
                                Gear.invalidateForce();
                                HookStatus.applied("ui-switch-toggle");
                            } catch (Throwable t) {
                                HookStatus.fail("ui-switch-toggle", t);
                                return Boolean.FALSE;
                            }
                            return Boolean.TRUE;
                        }
                        return Boolean.TRUE;
                    }
                });
        XposedHelpers.callMethod(sw, "setOnPreferenceChangeListener", listener);
        Object group = null;
        try {
            group = XposedHelpers.callMethod(screen, "findPreference", "screen_refresh_rate_settings");
        } catch (Throwable t) {
            HookStatus.fail("ui-switch-group", t);
        }
        try {
            // 在原生「支持高刷新率的应用」后、视频入口前；不混入 60/120Hz 档位单选组。
            XposedHelpers.callMethod(sw, "setOrder", Integer.valueOf(38));
        } catch (Throwable t) {
            HookStatus.fail("ui-switch-order", t);
        }
        if (group != null) {
            XposedHelpers.callMethod(group, "setVisible", Boolean.TRUE);
            Object currentParent = null;
            try { currentParent = XposedHelpers.callMethod(sw, "getParent"); }
            catch (Throwable ignored) { }
            if (currentParent != group) {
                if (currentParent != null) {
                    try { XposedHelpers.callMethod(currentParent, "removePreference", sw); }
                    catch (Throwable t) { HookStatus.fail("ui-switch-reparent-remove", t); }
                }
                XposedHelpers.callMethod(group, "addPreference", sw);
            }
            HookStatus.put("uiSwitchParent", "screen_refresh_rate_settings");
        } else {
            // 某些 ColorOS 小版本可能改 key；保底显示，避免入口完全消失。
            Object currentParent = null;
            try { currentParent = XposedHelpers.callMethod(sw, "getParent"); }
            catch (Throwable ignored) { }
            if (currentParent == null) XposedHelpers.callMethod(screen, "addPreference", sw);
            HookStatus.put("uiSwitchParent", "screen-fallback");
        }
        HookStatus.installed("ui-switch");
        HookStatus.put("uiSwitch", exist == null ? "added" : "reasserted");
        SettingsPages.record("lockSwitch", exist == null ? "added" : "reasserted");
    }

    /** summary 动态：非 165 档时明确告知「仅在 165Hz 档生效」（K1 修复的可感知面）。 */
    private static String switchSummary(Context ctx) {
        int mode = Gear.readSettingModeRaw();
        if (mode == Gear.SETTING_MODE_165) {
            return Gear.userForce165() ? "已开启：165Hz 常驻，LTPO 关闭"
                    : "开启后始终保持165Hz，切换立即生效";
        }
        return "仅在 165Hz 档生效（当前档位未选 165）";
    }
}
