package com.refresh165;

import android.util.Log;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 安装结果位图 + 每个 tag 的 applied/stockPass 计数 + dumpsys 文本。
 *
 * <p>DESIGN §1.6-5/§1.6-6/§9.3 第 0 步：
 * ① 安装失败必须可见（installed 位图 + state=fail-install + err）；
 * ② 每个改写点必须有 applied / stockPass 成对计数，120 档「零改写」靠
 *    「applied==0 && stockPass>0」自证（DESIGN §3 自证块）。
 */
final class HookStatus {
    private static final String TAG = "Refresh165";

    private static final ConcurrentHashMap<String, AtomicInteger> APPLIED =
            new ConcurrentHashMap<String, AtomicInteger>();
    private static final ConcurrentHashMap<String, AtomicInteger> PASS =
            new ConcurrentHashMap<String, AtomicInteger>();
    private static final Map<String, String> STATE = new ConcurrentHashMap<String, String>();
    private static final Map<String, String> ERROR = new ConcurrentHashMap<String, String>();
    private static final Map<String, String> RUNTIME_ERROR = new ConcurrentHashMap<String, String>();
    private static final Map<String, String> UNCLASSIFIED_ERROR = new ConcurrentHashMap<String, String>();
    private static final Set<String> INSTALLED =
            Collections.newSetFromMap(new ConcurrentHashMap<String, Boolean>());
    private static final Map<String, Object> KV = new ConcurrentHashMap<String, Object>();
    /** 必修-4③：DESIGN §9.2 期望名 → 实现 tag（验收脚本可直接对照）。 */
    private static final String ALIAS =
            "voteLocked:core-vote,winPref:win-pref,panel234:panel-234,"
            + "getDefaultRateId:default-rate-id,samMinFps:sam-min-fps,"
            + "forceMax:force-max,thermalVote:thermal-vote,thermalEntry:thermal-entry-fuse,"
            + "appOverride:app-override,perfectRate:perfect-rate,dcCap:dc-cap,"
            + "animBoost:anim-boost,viewPolicy:view-policy,rateMap:rate-map,"
            + "statusDump:status-dump,"
            + "reapply:reapply,sfSpecs:sf-specs,modeDispatch:mode-dispatch,"
            + "fgSwitch:fg-switch,finalMode:final-mode,viewPolicyInternal:view-policy-internal,"
            + "minRestore:min-restore-recovered,collapseResend:collapse-resend,"
            + "videoWrite:video-write,searchLate:search-hidden-late,tileOpen:tile-open";

    private static final Set<String> LOGGED =
            Collections.newSetFromMap(new ConcurrentHashMap<String, Boolean>());

    private HookStatus() { }

    // ---------------------------------------------------------------- 安装结果

    static synchronized void ok(String tag) {
        if (INSTALLED.contains(tag) || ERROR.containsKey(tag)
                || RUNTIME_ERROR.containsKey(tag) || UNCLASSIFIED_ERROR.containsKey(tag)) return;
        STATE.put(tag, "ok");
    }

    /**
     * C10：`install(tag, body)` 成功时调用 —— 进 `installed=` 位图，但**不**清错误分类，
     * 也**不**伪装成「子系统自证成功」（那是 {@link #installed(String)} 的语义）。
     */
    static synchronized void markInstalled(String tag) {
        if (ERROR.containsKey(tag) || RUNTIME_ERROR.containsKey(tag)
                || UNCLASSIFIED_ERROR.containsKey(tag)) return;
        INSTALLED.add(tag);
        STATE.put(tag, "ok");
    }

    static synchronized void installed(String tag) {
        INSTALLED.add(tag);
        ERROR.remove(tag);
        STATE.put(tag, RUNTIME_ERROR.containsKey(tag) ? "fail-runtime"
                : (UNCLASSIFIED_ERROR.containsKey(tag) ? "fail-unclassified" : "ok"));
    }

    /** Mark a retried asynchronous install as healthy after a transient startup failure. */
    static synchronized void recovered(String tag) {
        INSTALLED.add(tag);
        ERROR.remove(tag);
        UNCLASSIFIED_ERROR.remove(tag);
        STATE.put(tag, RUNTIME_ERROR.containsKey(tag) ? "fail-runtime" : "ok");
    }

    static synchronized boolean isInstalled(String tag) {
        return INSTALLED.contains(tag);
    }

    /** 安装位独立保留；旧调用无法判定阶段时为 fail-unclassified。 */
    static synchronized String state(String tag) {
        return STATE.get(tag);
    }

    static synchronized boolean failed(String tag) {
        return !INSTALLED.contains(tag)
                && (ERROR.containsKey(tag) || UNCLASSIFIED_ERROR.containsKey(tag));
    }

    static void fail(String tag, Throwable t) {
        fail(tag, message(t));
    }

    /** 旧调用没有阶段参数，不能把首次运行时失败冒充安装失败。 */
    static synchronized void fail(String tag, String why) {
        if (INSTALLED.contains(tag) || APPLIED.containsKey(tag) || PASS.containsKey(tag)) {
            runtimeFailed(tag, why);
        } else {
            String msg = why == null ? "unknown" : why;
            UNCLASSIFIED_ERROR.put(tag, msg);
            if (!ERROR.containsKey(tag) && !RUNTIME_ERROR.containsKey(tag)) {
                STATE.put(tag, "fail-unclassified");
            }
            logFailure(tag, "unclassified", msg);
        }
    }

    static void failInstall(String tag, Throwable t) {
        failInstall(tag, message(t));
    }

    static synchronized void failInstall(String tag, String why) {
        String msg = why == null ? "unknown" : why;
        ERROR.put(tag, msg);
        STATE.put(tag, "fail-install");
        logFailure(tag, "install", msg);
    }

    static void runtimeFailed(String tag, Throwable t) {
        runtimeFailed(tag, message(t));
    }

    static synchronized void runtimeFailed(String tag, String why) {
        String msg = why == null ? "unknown" : why;
        RUNTIME_ERROR.put(tag, msg);
        if (INSTALLED.contains(tag) || !ERROR.containsKey(tag)) STATE.put(tag, "fail-runtime");
        logFailure(tag, "runtime", msg);
    }

    private static String message(Throwable t) {
        return t == null ? "unknown" : (t.getClass().getSimpleName() + ": " + t.getMessage());
    }

    private static void logFailure(String tag, String phase, String msg) {
        if (LOGGED.add(tag + "|" + phase + "|" + msg)) {
            Log.e(TAG, "hook " + tag + " failed-" + phase + ": " + msg);
        }
    }

    /** xp 适配层安装失败的回报入口（DESIGN §9.3 0-a）。 */
    static void installFailed(String target, Throwable t) {
        failInstall("xp:" + target, t);
    }

    // ---------------------------------------------------------------- 计数

    static void applied(String tag) {
        bump(APPLIED, tag);
    }

    static void stockPass(String tag) {
        bump(PASS, tag);
    }

    /** P2：原子化（computeIfAbsent + incrementAndGet，避免 putIfAbsent 竞争下丢一次自增）。 */
    private static void bump(ConcurrentHashMap<String, AtomicInteger> map, String tag) {
        AtomicInteger counter = map.computeIfAbsent(tag, k -> new AtomicInteger());
        counter.incrementAndGet();
    }

    static int appliedCount(String tag) {
        AtomicInteger v = APPLIED.get(tag);
        return v == null ? 0 : v.get();
    }

    static int passCount(String tag) {
        AtomicInteger v = PASS.get(tag);
        return v == null ? 0 : v.get();
    }

    // ---------------------------------------------------------------- 标量观测字段

    static void put(String key, Object value) {
        KV.put(key, value == null ? "null" : String.valueOf(value));
    }

    static String get(String key) {
        Object v = KV.get(key);
        return v == null ? "" : String.valueOf(v);
    }


    // ---------------------------------------------------------------- dumpsys

    static synchronized String dump() {
        StringBuilder b = new StringBuilder(2048);
        b.append("Refresh165 status\n");
        b.append("alias=").append(ALIAS).append('\n');
        Map<String, Object> kv = new TreeMap<String, Object>(KV);
        // D1：不再剔除 timers/polling/timerList/thermalPoll（诊断信息必须可见）
        for (Map.Entry<String, Object> e : kv.entrySet()) {
            b.append(e.getKey()).append('=').append(e.getValue()).append('\n');
        }
        b.append("installed=");
        boolean first = true;
        for (String s : new ArrayList<String>(new TreeMap<String, String>(toMap(INSTALLED)).keySet())) {
            if (!first) b.append(',');
            b.append(s);
            first = false;
        }
        if (first) b.append("none");
        b.append('\n');
        b.append("exceptions=dc,touchIdle,viewPolicy,sampling,minFpsCollapse,minFpsNudge\n");

        TreeMap<String, String> states = new TreeMap<String, String>(STATE);
        for (String tag : APPLIED.keySet()) {
            if (!states.containsKey(tag)) states.put(tag, "observed");
        }
        for (String tag : PASS.keySet()) {
            if (!states.containsKey(tag)) states.put(tag, "observed");
        }
        for (Map.Entry<String, String> e : states.entrySet()) {
            String tag = e.getKey();
            b.append(tag).append('=').append(e.getValue())
                    .append(" applied=").append(appliedCount(tag))
                    .append(" stockPass=").append(passCount(tag))
                    .append(" installed=").append(INSTALLED.contains(tag) ? 1 : 0);
            String err = ERROR.get(tag);
            if (err != null) b.append(" err=").append(err);
            String runtimeErr = RUNTIME_ERROR.get(tag);
            if (runtimeErr != null) b.append(" runtimeErr=").append(runtimeErr);
            String unclassifiedErr = UNCLASSIFIED_ERROR.get(tag);
            if (unclassifiedErr != null) b.append(" unclassifiedErr=").append(unclassifiedErr);
            b.append('\n');
        }
        return b.toString();
    }

    private static Map<String, String> toMap(Set<String> set) {
        Map<String, String> m = new LinkedHashMap<String, String>();
        for (String s : set) m.put(s, s);
        return m;
    }

}
