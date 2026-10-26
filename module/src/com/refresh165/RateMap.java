package com.refresh165;

import android.hardware.display.DisplayManager;
import android.os.SystemClock;
import android.provider.Settings;
import android.view.Display;
import java.lang.reflect.Method;

import com.refresh165.xp.XposedHelpers;

/**
 * rateId 语义按「值」标定（DESIGN §1.5），并给出 R5 的物理边界判定。
 *
 * <p>三个进程现实：
 * <ul>
 *   <li>system_server：`OplusRefreshRateUtils` 可反射 → 按值标定（mapOk）；</li>
 *   <li>com.android.settings：vendor 类不可见 → mapOk=false，但 {@link #hzOf}/{@link #rateIdOf}
 *       退回镜像表（DESIGN §1.2），UI 侧只需要 165/185 的模型编号；</li>
 *   <li>标定失败时 {@code Gear.takeover()} 恒为 false（P0-5），所有写点因此不发生。</li>
 * </ul>
 */
final class RateMap {
    // 镜像（DESIGN §1.2 / ANALYSIS §1.3）：getRefreshRateById 的真实语义
    private static final int[] MIRROR_RATE_ID = {1, 2, 3, 4, 5, 6, 7, 8};
    private static final float[] MIRROR_HZ = {90f, 60f, 120f, 144f, 72f, 30f, 165f, 185f};

    static volatile int ID_30 = 6;
    static volatile int ID_60 = 2;
    static volatile int ID_90 = 1;
    static volatile int ID_120 = 3;
    static volatile int ID_144 = 4;
    static volatile int ID_165 = 7;
    static volatile int ID_185 = 8;

    static volatile boolean probed;
    static volatile boolean mapOk;
    static volatile String probeErr = "not-probed";
    private static final int MAX_PROBE_ATTEMPTS = 3;
    private static final long PROBE_RETRY_MS = 5000L;
    private static volatile int sProbeAttempts;
    private static volatile boolean sProbeScheduled;

    private static volatile Class<?> sUtils;
    private static volatile Boolean sPanel165;
    private static volatile long sPanelAt;
    private static volatile boolean sPanelLast;
    private static volatile boolean sPanelAttempted;
    private static volatile Boolean sNative185;
    private static volatile float sPanelMax = -1f;
    private static volatile long sPanelMaxAt;
    private static volatile String sPanelProbe = "not-probed";
    private static volatile boolean sSupportPatched;
    private static volatile long sSupportPatchAt;
    private static volatile int sSupportPatchAttempts;

    private RateMap() { }

    // ------------------------------------------------------------- 标定

    /** 1..8 扫一遍 getRefreshRateById(int):float，按 ±0.5Hz 匹配；找不到返回 -1。 */
    static synchronized void probe(final ClassLoader cl) {
        if (probed || sProbeScheduled) return;
        if (sProbeAttempts >= MAX_PROBE_ATTEMPTS) {
            probed = true;
            return;
        }
        sProbeAttempts++;
        boolean retry = false;
        try {
            Class<?> u = XposedHelpers.findClass("com.android.server.wm.OplusRefreshRateUtils", cl);
            int id60 = findId(u, 60f);
            int id90 = findId(u, 90f);
            int id120 = findId(u, 120f);
            int id165 = findId(u, 165f);
            int id185 = findId(u, 185f);
            int id30 = findId(u, 30f);
            int id144 = findId(u, 144f);
            if (id60 > 0) ID_60 = id60;
            if (id90 > 0) ID_90 = id90;
            if (id120 > 0) ID_120 = id120;
            if (id144 > 0) ID_144 = id144;
            if (id30 > 0) ID_30 = id30;
            if (id165 > 0) ID_165 = id165;
            if (id185 > 0) ID_185 = id185;
            sUtils = u;
            mapOk = ID_120 > 0 && ID_165 > 0 && id165 == 7 && id120 == 3;
            probeErr = mapOk ? "ok" : "unmapped:id165=" + id165 + ",id120=" + id120;
            probed = mapOk;
            retry = !mapOk;
        } catch (Throwable t) {
            mapOk = false;
            probeErr = "class-missing:" + t.getClass().getSimpleName();
            retry = true;
        }
        HookStatus.put("rateMap", mapOk ? "ok" : probeErr);
        HookStatus.put("rateMapAttempts", sProbeAttempts);
        HookStatus.put("rateMapIds", "30=" + ID_30 + " 60=" + ID_60 + " 90=" + ID_90
                + " 120=" + ID_120 + " 144=" + ID_144 + " 165=" + ID_165 + " 185=" + ID_185);
        if (mapOk) {
            HookStatus.installed("rate-map");
        } else if (retry && sProbeAttempts < MAX_PROBE_ATTEMPTS) {
            HookStatus.put("rateMap", "retry-" + sProbeAttempts + ":" + probeErr);
            scheduleProbeRetry(cl);
        } else {
            probed = true;
            HookStatus.failInstall("rate-map", probeErr);
        }
    }

    private static void scheduleProbeRetry(final ClassLoader cl) {
        sProbeScheduled = true;
        try {
            Sys.main().postDelayed(new Runnable() {
                @Override public void run() {
                    synchronized (RateMap.class) { sProbeScheduled = false; }
                    probe(cl);
                }
            }, PROBE_RETRY_MS);
        } catch (Throwable t) {
            sProbeScheduled = false;
            probed = true;
            probeErr = "retry-schedule-failed:" + t.getClass().getSimpleName();
            HookStatus.put("rateMap", probeErr);
            HookStatus.failInstall("rate-map", probeErr);
        }
    }

    private static int findId(Class<?> utils, float hz) {
        for (int id : MIRROR_RATE_ID) {
            float got = callHz(utils, id);
            if (got > 0 && Math.abs(got - hz) < 0.5f) return id;
        }
        return -1;
    }

    private static float callHz(Class<?> utils, int id) {
        if (utils == null) return -1f;
        try {
            Object v = XposedHelpers.callStaticMethod(utils, "getRefreshRateById",
                    Integer.valueOf(id));
            return v instanceof Number ? ((Number) v).floatValue() : -1f;
        } catch (Throwable t) {
            return -1f;
        }
    }

    // ------------------------------------------------------------- 双向映射

    /** rateId → Hz。system_server 走 vendor；其它进程走镜像。 */
    static float hzOf(int rateId) {
        Class<?> u = sUtils;
        if (u != null) {
            float hz = callHz(u, rateId);
            if (hz > 0) return hz;
        }
        for (int i = 0; i < MIRROR_RATE_ID.length; i++) {
            if (MIRROR_RATE_ID[i] == rateId) return MIRROR_HZ[i];
        }
        return -1f;
    }

    /** Hz → rateId（±0.5Hz 匹配）；找不到返回 -1。 */
    static int rateIdOf(float hz) {
        if (hz <= 0) return -1;
        for (int id : MIRROR_RATE_ID) {
            float got = hzOf(id);
            if (got > 0 && Math.abs(got - hz) < 0.5f) return id;
        }
        return -1;
    }

    /** P0-5 防御：ID_165 不可用时返回 fallback（-1），调用方必须提前 return。 */
    static int id165Or(int fallback) {
        return ID_165 > 0 ? ID_165 : fallback;
    }

    // ------------------------------------------------------------- 面板能力

    /**
     * 面板是否物理支持 165（DESIGN §1.5 三级回退）。
     * 失败不缓存（开机早期上下文可能未就绪），成功永久缓存。
     */
    static boolean has165Panel() {
        Boolean c = sPanel165;
        if (c != null) return c.booleanValue();
        long now = SystemClock.elapsedRealtime();
        if (now - sPanelAt < 2000L) return sPanelLast;
        sPanelAt = now;
        sPanelAttempted = true;
        boolean ok = probePanel165();
        sPanelLast = ok;
        if (ok) {
            sPanel165 = Boolean.TRUE;
            HookStatus.put("panel165", "true");
        } else {
            HookStatus.put("panel165", "false");
            HookStatus.put("panelProbe", sPanelProbe);
        }
        return ok;
    }

    private static boolean probePanel165() {
        // 1) supportedModes（最可靠，两个进程都可用）
        float[] modes = supportedHz();
        if (modes != null) {
            sPanelProbe = "supportedModes";
            for (float hz : modes) {
                if (hz >= 164.5f && hz <= 165.5f) return true;
            }
            return false;
        }
        // 2) vendor 支持上限
        Object configs = PolicyRef.configs();
        Object policy = PolicyRef.policy();
        int supportMax = Reflect.intField(configs, "mSupportMaxRefreshId", -1);
        if (supportMax < 0) supportMax = Reflect.intField(policy, "mSupportMaxRefreshId", -1);
        if (supportMax > 0) {
            sPanelProbe = "mSupportMaxRefreshId=" + supportMax;
            return ID_165 > 0 && supportMax >= ID_165;
        }
        sPanelProbe = "unavailable";
        return false;
    }

    private static volatile float[] sModesCache;
    private static volatile long sModesAt;

    /** 面板 supportedModes 的刷新率列表（2s 缓存，P2 §6.2）；不可用返回 null。 */
    private static float[] supportedHz() {
        float[] cached = sModesCache;
        long now = SystemClock.elapsedRealtime();
        if (cached != null && now - sModesAt < 2000L) return cached;
        float[] fresh = readSupportedHz();
        if (fresh != null) {
            sModesCache = fresh;
            sModesAt = now;
        }
        return fresh;
    }

    private static float[] readSupportedHz() {
        android.content.Context ctx = Sys.context();
        if (ctx == null) {
            sPanelProbe = "no-context";
            return null;
        }
        try {
            DisplayManager dm = (DisplayManager) ctx.getSystemService(android.content.Context.DISPLAY_SERVICE);
            if (dm == null) { sPanelProbe = "no-display-manager"; return null; }
            Display d = dm.getDisplay(Display.DEFAULT_DISPLAY);
            if (d == null) { sPanelProbe = "no-default-display"; return null; }
            Display.Mode[] ms = d.getSupportedModes();
            if (ms == null || ms.length == 0) { sPanelProbe = "no-modes"; return null; }
            float[] out = new float[ms.length];
            for (int i = 0; i < ms.length; i++) out[i] = ms[i].getRefreshRate();
            return out;
        } catch (Throwable t) {
            sPanelProbe = "error:" + t.getClass().getSimpleName();
            HookStatus.fail("panel-modes", t);
            return null;
        }
    }

    /** 面板物理最高刷新率（R6 夹取用）。 */
    static float panelMaxHz() {
        float cached = sPanelMax;
        long now = SystemClock.elapsedRealtime();
        if (cached >= 164.5f || (cached > 0 && now - sPanelMaxAt < 5000L)) return cached;
        float[] modes = supportedHz();
        float max = 0;
        if (modes != null) for (float hz : modes) if (hz > max) max = hz;
        if (max <= 0) {
            if (hasNative185Slot()) max = 185f;
            else if (has165Panel()) max = 165f;
            else {
                // Early system_server can observe no modes yet. Do not make a
                // transient fallback permanent; the next policy query retries.
                HookStatus.put("panelMaxHz", "fallback-120-unverified");
                return 120f;
            }
        }
        sPanelMax = max;
        sPanelMaxAt = now;
        HookStatus.put("panelMaxHz", max);
        return max;
    }

    /** 面板是否有对应 Hz 的 Display.Mode（容差 ±1.0Hz，与 vendor 一致，见 N-2）。 */
    static boolean hasDisplayModeFor(float hz) {
        if (hz <= 0) return false;
        float[] modes = supportedHz();
        if (modes == null) return hz <= panelMaxHz();
        for (float m : modes) if (Math.abs(m - hz) <= 1.0f) return true;
        return false;
    }

    /** modeId → Hz（最终出口兜底用来判断「当前 mode 是不是 165」）。 */
    static float hzOfModeId(int modeId) {
        android.content.Context ctx = Sys.context();
        if (ctx == null) return -1f;
        try {
            DisplayManager dm = (DisplayManager) ctx.getSystemService(
                    android.content.Context.DISPLAY_SERVICE);
            Display d = dm == null ? null : dm.getDisplay(Display.DEFAULT_DISPLAY);
            Display.Mode[] ms = d == null ? null : d.getSupportedModes();
            if (ms == null) return -1f;
            for (Display.Mode m : ms) {
                if (m.getModeId() == modeId) return m.getRefreshRate();
            }
        } catch (Throwable t) {
            HookStatus.fail("mode-hz", t);
        }
        return -1f;
    }

    /** 165 对应的 Display.Mode.modeId（PLK110 实机 = 5），仅用于 dumpsys 对账与第二兜底。 */
    static int modeIdOfRateId(int rateId) {
        float hz = hzOf(rateId);
        if (hz <= 0) return -1;
        android.content.Context ctx = Sys.context();
        if (ctx == null) return -1;
        try {
            DisplayManager dm = (DisplayManager) ctx.getSystemService(android.content.Context.DISPLAY_SERVICE);
            Display d = dm == null ? null : dm.getDisplay(Display.DEFAULT_DISPLAY);
            Display.Mode[] ms = d == null ? null : d.getSupportedModes();
            if (ms == null) return -1;
            int best = -1;
            float bestDelta = Float.MAX_VALUE;
            for (Display.Mode m : ms) {
                float delta = Math.abs(m.getRefreshRate() - hz);
                if (delta <= 1.0f && delta < bestDelta) { bestDelta = delta; best = m.getModeId(); }
            }
            return best;
        } catch (Throwable t) {
            HookStatus.fail("mode-id", t);
            return -1;
        }
    }

    /**
     * 机型是否真有 185 槽位（P1-8 收紧）：
     * ID_185 标定可用 + mSupportMaxRefreshId>=ID_185 + mDefaultRateId[4]==ID_185。
     * 末项防「原厂 4 段表 → 用户选极速实际是 120」被判成「原生 185」。
     */
    static boolean hasNative185Slot() {
        Boolean c = sNative185;
        if (c != null) return c.booleanValue();
        boolean ok = false;
        if (hzOf(ID_185) >= 184.5f) {
            Object configs = PolicyRef.configs();
            Object policy = PolicyRef.policy();
            int supportMax = Reflect.intField(configs, "mSupportMaxRefreshId", -1);
            if (supportMax < 0) supportMax = Reflect.intField(policy, "mSupportMaxRefreshId", -1);
            if (supportMax >= ID_185) ok = defaultTableHas185(configs);
        }
        if (ok) {
            sNative185 = Boolean.TRUE;
            HookStatus.put("native185", "true");
        } else {
            HookStatus.put("native185", "false");
        }
        return ok;
    }

    /** 直接读 mDefaultRateId 数组，避免调用被我们 hook 的 getDefaultRateId（防递归）。 */
    private static boolean defaultTableHas185(Object configs) {
        if (configs == null) return false;
        Object table = Reflect.field(configs, "mDefaultRateId");
        if (table == null) {
            Object inner = Reflect.field(configs, "mConfig");
            table = Reflect.field(inner, "mDefaultRateId");
        }
        if (table instanceof int[]) {
            int[] a = (int[]) table;
            return a.length > 4 && a[4] == ID_185;
        }
        // 字段不可读：退化为一次受保护的调用（Gear.decide 有递归护栏）
        try {
            Object v = XposedHelpers.callMethod(configs, "getDefaultRateId", Integer.valueOf(8));
            return v instanceof Number && ((Number) v).intValue() == ID_185;
        } catch (Throwable t) {
            HookStatus.fail("default-table", t);
            return false;
        }
    }

    /**
     * settings 进程侧的 185 判定：vendor 类不可见（PolicyRef 为空），用厂商 prop 代理。
     * 真 185 机型的 `persist.performance.rapid.refresh_rate.level` 为 185；
     * 原厂 4 段表机型该 prop 为空/0 → 视为「非原生 185」→ 允许 8 归一为 7。
     */
    static boolean uiNative185() {
        if (PolicyRef.configs() != null) return hasNative185Slot();
        return systemPropertyInt("persist.performance.rapid.refresh_rate.level") >= 185;
    }

    private static int systemPropertyInt(String key) {
        try {
            Class<?> sp = Class.forName("android.os.SystemProperties");
            Method get = sp.getDeclaredMethod("get", String.class, String.class);
            get.setAccessible(true);
            Object v = get.invoke(null, key, "");
            if (v instanceof String) {
                String s = ((String) v).trim();
                if (s.isEmpty()) return -1;
                return Integer.parseInt(s);
            }
        } catch (Throwable t) {
            HookStatus.fail("sysprop-" + key, t);
        }
        return -1;
    }

    /**
     * R5-S3：支持上限补齐（DESIGN §6.2-S3，`[U9]`）。
     * 只在「面板确实报 165，但 vendor 的 mSupportMaxRefreshId 还没到 165」时补一次，
     * 避免 `minRefreshRateId(id, mSupportMaxRefreshId)` 把 7 夹回 120。
     */
    static void patchSupportMaxIfNeeded() {
        if (sSupportPatched) return;
        if (!Gear.takeover()) return;
        if (!mapOk || ID_165 <= 0 || !has165Panel()) return;
        long now = SystemClock.elapsedRealtime();
        if (now - sSupportPatchAt < 5000L || sSupportPatchAttempts >= 3) return;
        sSupportPatchAt = now;
        sSupportPatchAttempts++;
        Object configs = PolicyRef.configs();
        Object policy = PolicyRef.policy();
        Object target = configs;
        int supportMax = Reflect.intField(configs, "mSupportMaxRefreshId", -1);
        if (supportMax < 0) {
            target = policy;
            supportMax = Reflect.intField(policy, "mSupportMaxRefreshId", -1);
        }
        if (supportMax < 0) {
            HookStatus.put("supportMax", "unavailable:retry-" + sSupportPatchAttempts);
            return;
        }
        if (supportMax >= ID_165) {
            sSupportPatched = true;
            HookStatus.put("supportMax", supportMax);
            return;
        }
        if (Reflect.write(target, "mSupportMaxRefreshId", Integer.valueOf(ID_165))) {
            sSupportPatched = true;
            HookStatus.applied("support-max");
            HookStatus.put("supportMax", supportMax + "->" + ID_165);
            android.util.Log.i("Refresh165", "supportMax patched " + supportMax + " -> " + ID_165);
        } else {
            if (sSupportPatchAttempts >= 3) sSupportPatched = true;
            HookStatus.fail("support-max", "field-write-unavailable");
        }
    }


    static String statusLine() {
        return "rateMap=" + (mapOk ? "ok" : probeErr) + " panel165=" + sPanelLast
                + " native185=" + (sNative185 == null ? "unknown" : sNative185)
                + " panelProbe=" + sPanelProbe;
    }
}
