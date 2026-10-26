package com.refresh165;

import android.content.ContentResolver;
import android.content.Context;
import android.database.ContentObserver;
import android.os.SystemClock;
import android.provider.Settings;
import java.lang.reflect.Method;
import java.lang.reflect.Field;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;


/**
 * 原生「支持高刷新率的应用」在 165 档使用的独立名单。
 *
 * <p><b>数据</b>：`Settings.Secure.refresh165_apps_165`，逗号分隔包名；**settings 进程唯一写者**，
 * system_server 只读；键不存在 = 空集（默认全部不勾）。系统原生的
 * `refresh_rate_config_user_override.xml` 模块**不读也不改**，120 档语义完全归系统。
 *
 * <p><b>语义</b>：名单内的应用在 takeover（165 档）期间：窗口请求强制 rateId 165
 * （`win-pref-165-app`）且 min_fps 固定 165（`MinFpsState.targetHz()`）。120 档不生效（R2）。
 *
 * <p><b>失败语义</b>：读失败 → 视为空集（不强制，K18 方向）；写失败 → 调用方记 HookStatus 并保持
 * 原持久化状态（UI 以持久化状态为准，即"回滚"）。
 *
 * <p>历史基线（`refresh165_perapp_legacy`）已废弃：键若已写入设备**保留不清理**，但不再读取。
 */
final class PerApp {
    /** 白名单存储键（settings 唯一写者）。 */
    static final String KEY_APPS_165 = "refresh165_apps_165";
    /** User-maintained native-only packages. No app is hard-coded here. */
    /** 已废弃（I）：无写入点，保留键名仅供注释追溯。 */
    @Deprecated
    static final String KEY_APPS_NATIVE_ONLY_DEPRECATED = "refresh165_apps_native_only";
    /** @deprecated 兼容旧调用/测试；不再读写。 */
    @Deprecated static final String KEY_APPS_NATIVE_ONLY = KEY_APPS_NATIVE_ONLY_DEPRECATED;

    /** 已废弃的基线键：仅保留常量供文档/排障引用，代码不再读。 */
    /** 已废弃（I）：历史基线键，不再读取也不清理设备上的残留值。 */
    @Deprecated
    static final String KEY_LEGACY_BASELINE_DEPRECATED = "refresh165_perapp_legacy";

    private static final long TTL_MS = 400L;
    private static final long FG_TTL_MS = 2000L;
    private static final int VALUE_165 = 7;

    private static volatile Set<String> sSet = Collections.emptySet();
    private static volatile long sAt;
    private static volatile String sSource = "none";
    private static volatile boolean sBroken;
    private static volatile boolean sObserverInstalled;
    private static volatile Set<String> sNativeOnlySet = Collections.emptySet();
    private static volatile long sNativeOnlyAt;

    private static volatile String sLastPkg = "";
    private static volatile long sLastPkgAt;
    private static volatile boolean sLastChecked;
    private static volatile String sMirrorPkg = "";

    private PerApp() { }

    // ------------------------------------------------------------- 存储

    /** system_server 侧读（400ms TTL + ContentObserver 立即失效）。 */
    static Set<String> checkedSet() {
        Set<String> cached = sSet;
        long now = SystemClock.elapsedRealtime();
        if (now - sAt < TTL_MS && sAt != 0) return cached;
        ContentResolver cr = Sys.cr();
        if (cr == null) {
            sSource = "none";
            sSet = Collections.emptySet();
            return sSet;
        }
        String raw;
        try {
            raw = Settings.Secure.getString(cr, KEY_APPS_165);
        } catch (Throwable t) {
            // 读失败 → 空集（不强制），方向固定为「未勾选」
            HookStatus.fail("app165-read", t);
            sBroken = true;
            sSet = Collections.emptySet();
            sAt = now;
            sSource = "none";
            publish();
            return sSet;
        }
        Set<String> parsed = parse(raw);
        sSet = parsed;
        sAt = now;
        sSource = raw == null ? "none" : "secure";
        sBroken = false;
        publish();
        return parsed;
    }

    static Set<String> parse(String raw) {
        Set<String> out = new LinkedHashSet<String>();
        if (raw == null) return Collections.unmodifiableSet(out);
        for (String part : raw.split(",")) {
            String p = part.trim();
            if (p.isEmpty() || p.length() > 128) continue;
            if (!validPkg(p)) continue;
            out.add(p);
        }
        return Collections.unmodifiableSet(out);
    }

    private static boolean validPkg(String pkg) {
        for (int i = 0; i < pkg.length(); i++) {
            char c = pkg.charAt(i);
            boolean ok = (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z')
                    || (c >= '0' && c <= '9') || c == '.' || c == '_';
            if (!ok) return false;
        }
        return true;
    }

    static String join(Set<String> set) {
        StringBuilder b = new StringBuilder();
        for (String s : set) {
            if (s == null || s.isEmpty()) continue;
            if (b.length() > 0) b.append(',');
            b.append(s);
        }
        return b.toString();
    }

    /** settings 进程侧读（UI 渲染用，不看 TTL 缓存）。 */
    static Set<String> readSet(Context ctx) {
        if (ctx == null) return Collections.emptySet();
        try {
            return parse(Settings.Secure.getString(ctx.getContentResolver(), KEY_APPS_165));
        } catch (Throwable t) {
            HookStatus.fail("app165-read-ui", t);
            return Collections.emptySet();
        }
    }

    static final String KEY_MODE = "oplus_customize_screen_refresh_rate";
    static final String KEY_CHOICE = "choice_mode_rate";

    static int uiMode(Context ctx) {
        if (ctx == null) return Integer.MIN_VALUE;
        try {
            int mode = Settings.Secure.getInt(ctx.getContentResolver(), KEY_MODE, Integer.MIN_VALUE);
            // 原生先写 8，system_server 随后归一为 7；两者均属于用户选择的 165 档。
            if (mode == Gear.SETTING_MODE_EXTREME
                    && Settings.Secure.getInt(ctx.getContentResolver(), KEY_CHOICE, 120) == 165) {
                return Gear.SETTING_MODE_165;
            }
            return mode;
        } catch (Throwable t) {
            HookStatus.fail("app165-mode", t);
            return Integer.MIN_VALUE;
        }
    }

    static synchronized boolean setChecked(Context ctx, String pkg, boolean checked) {
        if (ctx == null || pkg == null || pkg.isEmpty() || !validPkg(pkg)) return false;
        try {
            if (uiMode(ctx) != Gear.SETTING_MODE_165) return false;
            Set<String> next = new LinkedHashSet<String>(parse(Settings.Secure.getString(
                    ctx.getContentResolver(), KEY_APPS_165)));
            boolean changed = checked ? next.add(pkg) : next.remove(pkg);
            return !changed || writeSet(ctx, next);
        } catch (Throwable t) {
            HookStatus.fail("app165-toggle", t);
            return false;
        }
    }

    /** settings 进程侧写；@return false = 写被拒（调用方保持原状并提示）。 */
    static synchronized boolean writeSet(Context ctx, Set<String> set) {
        if (ctx == null || uiMode(ctx) != Gear.SETTING_MODE_165) return false;
        try {
            boolean ok = Settings.Secure.putString(ctx.getContentResolver(),
                    KEY_APPS_165, join(set));
            if (!ok) {
                HookStatus.fail("app165-write", "putString-returned-false");
                return false;
            }
            // 本进程不是 system_server，缓存只用于观测字段，不影响判定
            sAt = 0;
            HookStatus.applied("app165-write");
            return true;
        } catch (Throwable t) {
            HookStatus.fail("app165-write", t);
            return false;
        }
    }

    /**
     * 两进程各注册一次；键变化立即失效缓存。
     * @return false = 注册失败（调用方会重试；失败时不置已安装标志）
     */
    static boolean installObserver(Context ctx) {
        if (sObserverInstalled) return true;
        if (ctx == null) return false;
        final ContentResolver cr = ctx.getContentResolver();
        if (cr == null) return false;
        ContentObserver observer = new ContentObserver(new android.os.Handler(
                android.os.Looper.getMainLooper())) {
            @Override public void onChange(boolean selfChange) {
                sAt = 0;
                sNativeOnlyAt = 0;
                try {
                    if (PolicyRef.policy() != null && Gear.takeover()) {
                        checkedSet();
                        MinFpsState.onCandidate(foregroundPkg());
                    }
                } catch (Throwable t) {
                    HookStatus.fail("app165-change", t);
                }
            }
        };
        boolean appsRegistered = false;
        try {
            cr.registerContentObserver(Settings.Secure.getUriFor(KEY_APPS_165), false, observer);
            appsRegistered = true;
            // I：native_only 键已废弃 → 不再注册观察者
            sObserverInstalled = true;
            HookStatus.installed("app165-observer");
            HookStatus.put("app165Observer", "ok");
            return true;
        } catch (Throwable t) {
            if (appsRegistered) {
                try { cr.unregisterContentObserver(observer); }
                catch (Throwable cleanup) { HookStatus.fail("app165-observer-cleanup", cleanup); }
            }
            sObserverInstalled = false;
            HookStatus.fail("app165-observer", t);
            return false;
        }
    }

    // ------------------------------------------------------------- 判定

    static boolean force165(String pkg) {
        if (pkg == null || pkg.isEmpty()) return false;
        if (!Gear.takeover()) return false;      // 120 档：名单不生效（R2）
        // 不在这里因 sBroken 提前返回：失败只影响本次（读失败 = 空集），
        // 下次调用会重试并在成功时把 sBroken 清掉（避免一次瞬时失败把 R4 锁死到重启）。
        return checked(pkg);
    }

    /** 语义别名：该包是否在白名单里（转场窗口判定用）。 */
    static boolean isWhitelisted(String pkg) {
        return checked(pkg);
    }

    /** 名单包含该包即命中（默认全部不勾 = 空集 = 谁都不强制）。 */
    static boolean checked(String pkg) {
        if (pkg == null || pkg.isEmpty()) return false;
        return checkedSet().contains(pkg);
    }

    static int countChecked() {
        return checkedSet().size();
    }

    // ------------------------------------------------------- 前台包名解析（四级）

    /** 解析缓存 TTL：每帧路径也只做一次多级反射。 */
    private static final long RESOLVE_TTL_MS = 500L;
    private static volatile String sResolvedPkg = "";
    private static volatile String sResolvedSrc = "none";
    private static volatile long sResolvedAt;
    private static volatile Object sManager;
    private static volatile Object sGameService;
    private static volatile String sGamePkg = "";
    private static volatile long sGameAt;
    private static volatile boolean sGameValue;
    private static volatile String sNativeReqPkg = "";
    private static volatile int sNativeReqId = -1;
    private static volatile long sNativeReqAt;
    private static volatile Class<?> sCandidateWindowOwner;
    private static volatile Method sCandidateWindowMethod;
    private static volatile Field sCandidateWindowField;
    private static volatile boolean sCandidateWindowResolved;

    /**
     * 前台包名解析（P0：`app165Foreground` / VideoFixed 都依赖它，之前 `candidateWinPkgName()`
     * 返回空就整条链路失效）。优先级：
     * <ol>
     *   <li>`mPickRefreshRateData.candidateWinPkgName()`（决策现场最准）；</li>
     *   <li>`OPlusExternalRefreshRateManager.mCurPkgName`（VRR 全局镜像，由 touch-idle hook 存引用）；</li>
     *   <li>`OplusRefreshRatePolicyImpl.mCurPkgName`（厂商策略字段）；</li>
     *   <li>`OplusRefreshRatePolicyImpl.mLastPickedPackage`（兜底）。</li>
     * </ol>
     * 每级失败/为空都会把 `fgSrc` 打到 dumpsys。
     */
    static String resolveForeground() {
        long now = SystemClock.elapsedRealtime();
        if (now - sResolvedAt < RESOLVE_TTL_MS) return sResolvedPkg;
        sResolvedAt = now;
        Object policy = PolicyRef.policy();
        String pkg;
        // ① 决策现场
        Object picker = Reflect.field(policy, "mPickRefreshRateData");
        pkg = callString(picker, "candidateWinPkgName");
        if (!isEmpty(pkg)) return setResolved(pkg, "pick-candidate");
        // ② VRR 全局镜像
        pkg = callString(sManager, "getCurPkgName");
        if (isEmpty(pkg)) {
            Object v = Reflect.field(sManager, "mCurPkgName");
            if (v instanceof String) pkg = (String) v;
        }
        if (!isEmpty(pkg)) return setResolved(pkg, "vrr-manager");
        // ③ 厂商策略字段
        Object v = Reflect.field(policy, "mCurPkgName");
        if (v instanceof String && !isEmpty((String) v)) {
            return setResolved((String) v, "policy-cur-pkg");
        }
        // ④ 兜底字段
        v = Reflect.field(policy, "mLastPickedPackage");
        if (v instanceof String && !isEmpty((String) v)) {
            return setResolved((String) v, "policy-last-picked");
        }
        return setResolved("", "none");
    }

    private static String setResolved(String pkg, String src) {
        sResolvedPkg = pkg;
        sResolvedSrc = src;
        HookStatus.put("fgPkg", pkg);
        HookStatus.put("fgSrc", src);
        if (!pkg.isEmpty() && checked(pkg)) {
            HookStatus.put("app165Foreground", pkg);
        } else {
            HookStatus.put("app165Foreground", "");
        }
        return pkg;
    }

    private static String callString(Object obj, String method) {
        if (obj == null) return "";
        try {
            java.lang.reflect.Method m = Sys.method(obj.getClass(), method);
            if (m == null) return "";
            Object v = m.invoke(obj);
            return v instanceof String ? (String) v : "";
        } catch (Throwable t) {
            HookStatus.fail("fg-" + method, t);
            return "";
        }
    }

    private static boolean isEmpty(String s) {
        return s == null || s.isEmpty() || "null".equals(s);
    }

    /** 桌面/系统 UI 不算「前台应用」：白名单与固定帧率都不应在桌面命中。 */
    static boolean isLauncher(String pkg) {
        if (isEmpty(pkg)) return false;
        if (pkg.startsWith("com.android.launcher")) return true;
        if (pkg.startsWith("com.oppo.launcher")) return true;
        if (pkg.startsWith("com.coloros.launcher")) return true;
        if (pkg.startsWith("com.android.systemui")) return true;
        if (pkg.startsWith("com.android.settings")) return false;
        return pkg.contains(".launcher") || pkg.endsWith("launcher");
    }

    /** Games keep their own cadence; SystemUI is a separate candidate and may use 165. */
    static boolean isGame(String pkg) {
        if (isEmpty(pkg) || isLauncher(pkg) || pkg.startsWith("com.android.systemui")) return false;
        long now = SystemClock.elapsedRealtime();
        if (pkg.equals(sGamePkg)) {
            long age = now - sGameAt;
            // The vendor flips mIsGaming shortly after the foreground package
            // callback. Do not let an early false result mask that edge for
            // the full positive-result cache lifetime.
            if ((sGameValue && age < 5000L) || (!sGameValue && age < 500L)) {
                return sGameValue;
            }
        }
        boolean game = false;
        try {
            // ColorOS often does not set ApplicationInfo.CATEGORY_GAME for
            // shipped games. Its VRR manager has the authoritative runtime
            // signal, scoped to the same foreground package.
            game = managerReportsGame(pkg);
        } catch (Throwable ignored) {
            // Fall through to package metadata.
        }
        try {
            if (game) {
                sGamePkg = pkg;
                sGameAt = now;
                sGameValue = true;
                HookStatus.put("fgGame", pkg);
                return true;
            }
            Context ctx = Sys.context();
            Object pm = ctx == null ? null : ctx.getPackageManager();
            if (pm != null) {
                Method m = pm.getClass().getMethod("getApplicationInfo", String.class, int.class);
                Object ai = m.invoke(pm, pkg, Integer.valueOf(0));
                game = Reflect.intField(ai, "category", -1) == 0; // ApplicationInfo.CATEGORY_GAME
            }
        } catch (Throwable ignored) {
            // Unknown metadata is fail-open for ordinary apps; native requests remain authoritative.
        }
        sGamePkg = pkg;
        sGameAt = now;
        sGameValue = game;
        HookStatus.put("fgGame", game ? pkg : "");
        return game;
    }

    private static boolean managerReportsGame(String pkg) {
        if (isEmpty(pkg)) return false;
        if (reportsGame(sManager, pkg)) return true;
        return reportsGame(sGameService, pkg);
    }

    private static boolean reportsGame(Object owner, String pkg) {
        if (owner == null) return false;
        String current = null;
        String[] packageFields = {"mCurPkgName", "mCurrPkgName", "mCurrentPkgName", "mFrontPkgName"};
        for (String name : packageFields) {
            Object value = Reflect.field(owner, name);
            if (value instanceof String && !isEmpty((String) value)) {
                current = (String) value;
                break;
            }
        }
        if (current == null || !pkg.equals(current)) return false;
        String[] flags = {"mIsGaming", "mIsGamePolicy", "mGamePolicy", "mGlobalGameBattleStatus"};
        for (String name : flags) {
            Object flag = Reflect.field(owner, name);
            if (Boolean.TRUE.equals(flag)) return true;
            if (flag instanceof Number && ((Number) flag).intValue() != 0) return true;
        }
        return false;
    }

    /** 兼容旧调用：当前窗口是否为全屏游戏。 */
    static boolean isGameFullscreen(String pkg) {
        return isGame(pkg) && gameWindowingMode(pkg) == 1;
    }

    /** 兼容旧调用：最近记录的原生低帧请求是否仍在 3s 窗口内。 */
    static boolean nativeRequestCap(String pkg) {
        if (isEmpty(pkg) || !isGame(pkg) || checked(pkg)) return false;
        return pkg.equals(sNativeReqPkg) && SystemClock.elapsedRealtime() - sNativeReqAt < 3000L
                && sNativeReqId > 0 && sNativeReqId != RateMap.rateIdOf(165f);
    }

    static boolean blocks165Promotion(String pkg) {
        if (nativeOnly(pkg)) return true;
        if (!isGame(pkg)) return false;
        // A game candidate must retain its own/native cadence in fullscreen,
        // freeform and PiP. Its low request is not a cap for ordinary apps:
        // ordinary UI/apps may legitimately request 90/120 while the module
        // still needs to promote the display to 165 (e.g. the shade/transition).
        // Missing native evidence is fail-closed only for a confirmed game.
        int hz = nativeRequestHz(pkg);
        return hz <= 0 || hz < 165;
    }

    /** Record the package's own window request (rate id), if the vendor exposes one. */
    static void noteNativeRequest(String pkg, int requestId) {
        if (isEmpty(pkg) || isLauncher(pkg) || pkg.startsWith("com.android.systemui")) return;
        if (requestId == 0 && pkg.equals(sNativeReqPkg)) {
            sNativeReqPkg = "";
            sNativeReqId = -1;
            sNativeReqAt = 0;
            HookStatus.put("nativeReq", "cleared:" + pkg);
            return;
        }
        if (requestId < 0) return;
        if (requestId == 0) return;
        sNativeReqPkg = pkg;
        sNativeReqId = requestId;
        sNativeReqAt = SystemClock.elapsedRealtime();
        HookStatus.put("nativeReq", pkg + "@" + requestId);
    }


    /** Recent native request as Hz, or -1 when the vendor did not expose one. */
    static int nativeRequestHz(String pkg) {
        if (isEmpty(pkg) || !pkg.equals(sNativeReqPkg)
                || SystemClock.elapsedRealtime() - sNativeReqAt >= 3000L) return -1;
        float hz = RateMap.hzOf(sNativeReqId);
        return hz > 0 ? Math.round(hz) : -1;
    }

    /** Context discriminator used to invalidate an unchanged numeric target. */
    static String promotionContext(String pkg) {
        if (isEmpty(pkg)) return "blank";
        if (isLauncher(pkg)) return "system-ui:" + pkg;
        if (nativeOnly(pkg)) return "native-only:" + pkg;
        if (isGame(pkg)) {
            int mode = gameWindowingMode(pkg);
            return (mode == 1 ? "game-full:" : (mode == 5 || mode == 2)
                    ? "game-free:" : "game-unknown:") + pkg;
        }
        return "ordinary:" + pkg;
    }


    private static int gameWindowingMode(String pkg) {
        if (!isGame(pkg)) return -1;
        Object data = Reflect.field(PolicyRef.policy(), "mPickRefreshRateData");
        if (data == null) return -1;
        Object win = candidateWindow(data);
        return windowingMode(win);
    }

    /** Best-effort candidate window lookup across ColorOS revisions. */
    private static Object candidateWindow(Object data) {
        Class<?> owner = data.getClass();
        if (!sCandidateWindowResolved || sCandidateWindowOwner != owner) {
            synchronized (PerApp.class) {
                if (!sCandidateWindowResolved || sCandidateWindowOwner != owner) {
                    resolveCandidateWindowAccessor(owner);
                }
            }
        }
        try {
            Method method = sCandidateWindowMethod;
            if (method != null) return method.invoke(data);
            Field field = sCandidateWindowField;
            return field == null ? null : field.get(data);
        } catch (Throwable t) {
            HookStatus.fail("candidate-window", t);
            return null;
        }
    }

    private static void resolveCandidateWindowAccessor(Class<?> owner) {
        sCandidateWindowOwner = owner;
        sCandidateWindowMethod = null;
        sCandidateWindowField = null;
        String[] methods = {"candidateWin", "candidateWindow", "getCandidateWin",
                "getCandidateWindow", "candidateWindowState"};
        for (String name : methods) {
            Method m = Sys.method(owner, name);
            if (m != null && m.getParameterTypes().length == 0) {
                sCandidateWindowMethod = m;
                break;
            }
        }
        if (sCandidateWindowMethod == null) {
            sCandidateWindowField = findCandidateWindowField(owner, false);
        }
        if (sCandidateWindowField == null) {
            sCandidateWindowField = findCandidateWindowField(owner, true);
        }
        sCandidateWindowResolved = true;
        HookStatus.put("gameWindowAccessor", sCandidateWindowMethod != null ? "method"
                : sCandidateWindowField != null ? "field:" + sCandidateWindowField.getName() : "none");
    }

    private static Field findCandidateWindowField(Class<?> owner, boolean byType) {
        for (Class<?> c = owner; c != null; c = c.getSuperclass()) {
            try {
                for (Field f : c.getDeclaredFields()) {
                    String name = f.getName();
                    String type = f.getType().getName();
                    boolean candidate = byType
                            ? type.endsWith("WindowState") || type.endsWith("ActivityRecord")
                            : "mCandidateWin".equals(name) || "mCandidateWindow".equals(name);
                    if (!candidate) continue;
                    f.setAccessible(true);
                    return f;
                }
            } catch (Throwable t) {
                HookStatus.fail("candidate-window-field", t);
            }
        }
        return null;
    }

    /** WindowConfiguration is used by newer builds instead of mWindowingMode. */
    private static int windowingMode(Object win) {
        if (win == null) return -1;
        Object value = Reflect.field(win, "mWindowingMode");
        if (!(value instanceof Number)) value = Reflect.field(win, "windowingMode");
        if (value instanceof Number) return ((Number) value).intValue();
        Object config = Reflect.field(win, "mWindowConfiguration");
        if (config != null) {
            value = Reflect.field(config, "mWindowingMode");
            if (value instanceof Number) return ((Number) value).intValue();
            try {
                Method m = Sys.method(config.getClass(), "getWindowingMode");
                Object v = m == null ? null : m.invoke(config);
                if (v instanceof Number) return ((Number) v).intValue();
            } catch (Throwable ignored) {
                HookStatus.fail("read-windowing-config", ignored);
            }
        }
        try {
            Method m = Sys.method(win.getClass(), "getWindowingMode");
            Object v = m == null ? null : m.invoke(win);
            if (v instanceof Number) return ((Number) v).intValue();
        } catch (Throwable ignored) {
            HookStatus.fail("read-windowing-window", ignored);
        }
        return -1;
    }

    /** Explicit native-only list; empty by default so user policy remains authoritative. */
    static boolean nativeOnly(String pkg) {
        if (isEmpty(pkg)) return false;
        try {
            long now = SystemClock.elapsedRealtime();
            Set<String> cached = sNativeOnlySet;
            if (now - sNativeOnlyAt >= 1000L) {
                ContentResolver cr = Sys.cr();
                String raw = null;      // I：已废弃键不再读取
                cached = parse(raw);
                sNativeOnlySet = cached;
                sNativeOnlyAt = now;
            }
            if (cached.contains(pkg)) {
                HookStatus.put("nativeOnly", pkg);
                return true;
            }
        } catch (Throwable t) {
            HookStatus.fail("native-only-read", t);
        }
        return false;
    }

    /** 决策现场最准（getWindowPreferredRate 每帧都过）；空则走多级解析。 */
    static void noteForeground(String candidate) {
        String pkg = isEmpty(candidate) ? resolveForeground() : candidate;
        if (!isEmpty(candidate)) {
            sResolvedPkg = candidate;
            sResolvedSrc = "pick-candidate";
            sResolvedAt = SystemClock.elapsedRealtime();
            HookStatus.put("fgPkg", candidate);
            HookStatus.put("fgSrc", "pick-candidate");
        }
        sLastPkg = pkg == null ? "" : pkg;
        sLastPkgAt = SystemClock.elapsedRealtime();
        sLastChecked = checked(sLastPkg);
        HookStatus.put("app165Foreground", sLastChecked ? sLastPkg : "");
    }

    /**
     * 即时信号/预置：直接写解析缓存（不等 500ms 轮询），来源记到 `fgSrc`。
     * 与 {@link #resolveForeground()} 共用同一个可见性出口。
     */
    static void presetForeground(String pkg, String src) {
        if (pkg == null || pkg.isEmpty()) return;
        sResolvedPkg = pkg;
        sResolvedSrc = src;
        sResolvedAt = SystemClock.elapsedRealtime();
        sLastPkg = pkg;
        sLastPkgAt = SystemClock.elapsedRealtime();
        // R-1：**不做 binder**（此路径可能跑在 vendor 持 WM 锁的方法内）。
        // 只读缓存命中就更新，miss 不动 —— 主线程的 noteForeground(pkg) 会重算同一组字段。
        Set<String> cached = sSet == null ? null : sSet;
        if (cached != null) {
            sLastChecked = cached.contains(pkg);
        }
        HookStatus.put("fgPkg", pkg);
        HookStatus.put("fgSrc", src);
        HookStatus.put("fgChecked", cached.isEmpty() ? "cache-empty" : "cache-hit");
        if (!cached.isEmpty()) HookStatus.put("app165Foreground", sLastChecked ? pkg : "");
    }

    /** 全局镜像（OPlusExternalRefreshRateManager），由 touch-idle hook 刷新并保存实例引用。 */
    static void noteMirror(Object manager) {
        if (manager != null) sManager = manager;
        Object pkg = Reflect.field(manager, "mCurPkgName");
        if (pkg instanceof String) sMirrorPkg = (String) pkg;
    }

    static void noteGameService(Object service) {
        if (service != null) sGameService = service;
    }

    /** 唯一的「当前前台包名」入口（空白/launcher 由调用方按 targetReason 处理）。 */
    static String foregroundPkg() {
        long now = SystemClock.elapsedRealtime();
        if (!sLastPkg.isEmpty() && now - sLastPkgAt < FG_TTL_MS) return sLastPkg;
        return resolveForeground();
    }

    /** dumpsys 自证（lead 指定字段）。 */
    private static void publish() {
        Set<String> set = sSet;
        HookStatus.put("app165", set.size());
        HookStatus.put("app165List", sample(set));
        HookStatus.put("app165Source", sSource);
        if (sBroken) HookStatus.put("app165", "unavailable");
    }

    private static String sample(Set<String> set) {
        if (set.isEmpty()) return "empty";
        StringBuilder b = new StringBuilder();
        int i = 0;
        for (String s : set) {
            if (i++ >= 3) { b.append(",…"); break; }
            if (b.length() > 0) b.append(',');
            b.append(s);
        }
        return b.toString();
    }


    static String statusLine() {
        Set<String> set = sSet;
        return "app165=" + (sBroken ? "unavailable" : set.size())
                + " source=" + sSource + " list=" + sample(set);
    }
}
