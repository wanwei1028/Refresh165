package com.refresh165;

import android.content.Context;
import android.os.Handler;
import android.os.PowerManager;
import android.os.SystemClock;
import java.io.*;
import java.lang.ref.WeakReference;
import java.lang.reflect.*;
import java.util.*;
import java.util.concurrent.*;
import com.refresh165.xp.*;

/**
 * Bilibili 播放期「窗口 override 豁免」策略。
 *
 * <p>v1.0.6 的 R2 硬约束（DESIGN §3-#17/#20/#21/#22）：三条路径在非接管档位必须零改写 ——
 * <ul>
 *   <li>{@code overrideWindowRefreshRate} 写入点（#17）</li>
 *   <li>{@code checkStatusByList} after 的 {@code setResult(true)}（#20）</li>
 *   <li>{@code transition()} 的内部自调用（#21，绕过了自己的 hook，所以必须单独加闸）</li>
 * </ul>
 * 采样链路（#22）登记为软约束 {@code sampling}：{@code eligible()} 首行加闸后，
 * 120 档 {@code videoSamples} 不再增长。
 */
final class SystemViewPolicy {
    private static final String PKG = VideoEvidence.PACKAGE;
    private static final Object LOCK = new Object();
    private static final WeakHashMap<Object, Window> WINDOWS = new WeakHashMap<Object, Window>();
    private static final ThreadLocal<Boolean> INTERNAL = new ThreadLocal<Boolean>();
    private static final EventVideoState EVENT = new EventVideoState();
    private static final java.util.concurrent.atomic.AtomicLong WINDOW_EPOCH =
            new java.util.concurrent.atomic.AtomicLong();
    private static volatile PlaybackEvents events;
    private static volatile PlaybackEvents.Signal signal;
    private static volatile long eventChanges;
    private static volatile int probeAttempts, probePid, probeLayer = -1;
    private static volatile String parseState = "not-started";
    private static volatile Object manager;
    private static volatile boolean installed, appliedHigh, lastPolicyHigh;
    private static final java.util.concurrent.atomic.AtomicBoolean POSTED =
            new java.util.concurrent.atomic.AtomicBoolean();
    private static volatile long samples, errors, sampleMs;
    private static volatile String fault = "none";
    private static volatile String reason = "waiting-manager";
    private static volatile Method override, update;
    private static final ScheduledExecutorService WORK =
            Executors.newSingleThreadScheduledExecutor(new ThreadFactory() {
                @Override public Thread newThread(Runnable r) {
                    Thread t = new Thread(r, "Refresh165Video");
                    t.setDaemon(true);
                    return t;
                }
            });

    private static final class Window {
        final WeakReference<Object> policy, token, win;
        final int pid;
        int requested;
        boolean changed;

        Window(Object p, Object t, Object w, int pid, int rate) {
            policy = new WeakReference<Object>(p);
            token = new WeakReference<Object>(t);
            win = new WeakReference<Object>(w);
            this.pid = pid;
            requested = rate;
        }
    }

    private SystemViewPolicy() { }

    static String status() {
        HookStatus.put("viewPolicy", installed ? "ok" : reason);
        HookStatus.put("videoGate", appliedHigh);
        HookStatus.put("videoSamples", samples);
        HookStatus.put("videoErrors", errors);
        return " biliViewHook=" + installed + " videoGate=" + appliedHigh
                + " videoReason=" + reason + " videoSamples=" + samples + " videoErrors=" + errors
                + " videoSampleMs=" + sampleMs + " videoMode=event-burst"
                + " videoProbePid=" + probePid + " videoProbeLayer=" + probeLayer
                + " videoParse=" + parseState + " videoProbeAttempts=" + probeAttempts
                + " videoEvents=" + eventChanges
                + " videoMedia=" + (events == null ? "not-registered" : events.status)
                + " videoMediaFaultAt=" + (events == null ? "none" : events.failureAt)
                + " videoTransport=binder videoStage=" + SurfaceSnapshot.stage
                + " videoFailedStage=" + SurfaceSnapshot.failedStage
                + " videoFault=" + fault + " videoBytes=" + SurfaceSnapshot.bytes;
    }

    /** @return 是否安装成功（DESIGN §3 自证需要区分「没装」与「没动手」）。 */
    static boolean install(ClassLoader cl) {
        List<XC_MethodHook.Unhook> hooks = new ArrayList<XC_MethodHook.Unhook>();
        try {
            Class<?> policy = XposedHelpers.findClass(
                    "com.android.server.wm.OplusRefreshRatePolicyImpl", cl);
            Class<?> binder = XposedHelpers.findClass("android.os.IBinder", cl);
            Class<?> ext = XposedHelpers.findClass(
                    "com.oplus.vrr.OPlusExternalRefreshRateManager", cl);
            override = policy.getDeclaredMethod("overrideWindowRefreshRate", binder, int.class);
            override.setAccessible(true);
            update = ext.getDeclaredMethod("handleTouchIdleState");
            update.setAccessible(true);
            Method check = ext.getDeclaredMethod("checkStatusByList");
            check.setAccessible(true);
            if (override.getReturnType() != void.class || update.getReturnType() != void.class
                    || check.getReturnType() != boolean.class) {
                reason = "signature-mismatch";
                return false;
            }
            hooks.add(XposedBridge.hookMethod(override, new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam p) {
                    // C9：INTERNAL（我们自己触发的收口调用）必须在 takeover 之前放行，
                    //     否则 120 档收口会被这里拦掉，窗口状态残留。
                    if (Boolean.TRUE.equals(INTERNAL.get())) {
                        HookStatus.stockPass("view-policy-internal");
                        return;
                    }
                    // #17 硬约束：120 档/非接管一行出局（放在 allowed() 之前）
                    if (!Gear.takeover()) { HookStatus.stockPass("view-policy"); return; }
                    if (p.args == null || p.args.length != 2
                            || !binder.isInstance(p.args[0]) || !(p.args[1] instanceof Integer)) return;
                    Object wm = field(p.thisObject, "mWmService");
                    Object lock = field(wm, "mGlobalLock");
                    if (lock == null) return;
                    synchronized (lock) {
                        try {
                            Object w = XposedHelpers.callMethod(wm, "windowForClient", null, p.args[0]);
                            if (w == null
                                    || !PKG.equals(XposedHelpers.callMethod(w, "getOwningPackage"))) {
                                return;
                            }
                            Object pid = field(field(w, "mSession"), "mPid");
                            if (!(pid instanceof Number)) return;
                            int rate = ((Integer) p.args[1]).intValue();
                            synchronized (LOCK) {
                                Window rec = WINDOWS.get(p.args[0]);
                                if (rec == null || rec.win.get() != w) {
                                    WINDOW_EPOCH.incrementAndGet();
                                    rec = new Window(p.thisObject, p.args[0], w,
                                            ((Number) pid).intValue(), rate);
                                    WINDOWS.put(p.args[0], rec);
                                }
                                if (rec.requested != rate) WINDOW_EPOCH.incrementAndGet();
                                rec.requested = rate;
                                // C9：改写成功才置 changed；失败/未接管保留 false 让下一轮重试，
                                //      绝不因为一次「尝试过」就把窗口标记成已完成。
                                boolean rewrote = false;
                                if (rate == 2 && allowed()) {
                                    try {
                                        p.args[1] = Integer.valueOf(0);
                                        rewrote = Integer.valueOf(0).equals(p.args[1]);
                                    } catch (Throwable t) {
                                        HookStatus.fail("view-policy-rewrite", t);
                                        rewrote = false;
                                    }
                                }
                                if (rewrote) {
                                    rec.changed = true;
                                    HookStatus.applied("view-policy");
                                } else {
                                    rec.changed = false;
                                    HookStatus.stockPass("view-policy");
                                }
                            }
                        } catch (Throwable t) {
                            HookStatus.fail("view-policy-window", t);
                        }
                    }
                    sceneChanged();
                }
            }));
            hooks.add(XposedBridge.hookMethod(check, new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam p) {
                    manager = p.thisObject;
                    // #20 硬约束：在 manager 赋值之后、setResult 之前
                    if (!Gear.takeover()) { HookStatus.stockPass("view-check"); return; }
                    if (Boolean.FALSE.equals(p.getResult()) && allowed()) {
                        p.setResult(true);
                        HookStatus.applied("view-check");
                    } else {
                        HookStatus.stockPass("view-check");
                    }
                }
            }));
            hooks.add(XposedBridge.hookMethod(update, new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam p) {
                    manager = p.thisObject;
                    PerApp.noteMirror(manager);
                    sceneChanged();
                }
            }));
            installed = true;
            reason = "installed";
            return true;
        } catch (Throwable t) {
            for (XC_MethodHook.Unhook h : hooks) h.unhook();
            reason = "unsupported";
            HookStatus.fail("view-policy", t);
            return false;
        }
    }

    private static boolean eligible() {
        // #22 软约束 sampling：非接管直接 false，120 档不采样、不驱动 transition
        if (!Gear.takeover()) return false;
        Object m = manager;
        if (m == null || !PKG.equals(field(m, "mCurPkgName"))) return false;
        Object context = field(m, "mContext");
        if (!(context instanceof Context)) return false;
        PowerManager pm = (PowerManager) ((Context) context).getSystemService(Context.POWER_SERVICE);
        if (pm == null || !pm.isInteractive()) return false;
        Object req = field(m, "mAppRequest");
        if (req != null) {
            try {
                if (PKG.equals(XposedHelpers.callMethod(req, "getPkgName"))) {
                    Object rate = XposedHelpers.callMethod(req, "getRefreshRate");
                    if (!(rate instanceof Number)
                            || (((Number) rate).intValue() > 0 && ((Number) rate).intValue() <= 60)) {
                        return false;
                    }
                }
            } catch (Throwable t) {
                HookStatus.fail("view-eligible", t);
                return false;
            }
        }
        return true;
    }

    private static boolean allowed() {
        if (!Gear.takeover()) return false;
        try {
            return EVENT.high() && eligible();
        } catch (Throwable t) {
            HookStatus.fail("view-allowed", t);
            return false;
        }
    }

    private static int visiblePid() {
        int pid = 0;
        Window[] all;
        synchronized (LOCK) { all = WINDOWS.values().toArray(new Window[0]); }
        for (Window w : all) {
            try {
                Object win = w.win.get();
                Object wm = field(w.policy.get(), "mWmService");
                Object lock = field(wm, "mGlobalLock");
                if (win == null || lock == null) continue;
                synchronized (lock) {
                    if (!Boolean.TRUE.equals(XposedHelpers.callMethod(win, "isVisible"))) continue;
                    if (pid != 0 && pid != w.pid) return 0;
                    pid = w.pid;
                }
            } catch (Throwable t) {
                return 0;
            }
        }
        return pid;
    }

    private static void sceneChanged() {
        // 修复 #3（DESIGN §3-#22）：stock 档既不采样也不注册媒体监听，除非还有自己的
        // override 需要收口（lastPolicyHigh 表示我们先前抬升过，需要走 transition(false) 还原）。
        if (!Gear.takeover() && !lastPolicyHigh) {
            HookStatus.stockPass("view-scene");
            return;
        }
        try {
            WORK.execute(new Runnable() {
                @Override public void run() {
                    if (!eligible()) {
                        EVENT.invalidate();
                        reason = Gear.takeover() ? "native-background-or-low-vote" : "stock-gear";
                        transition(false);
                    }
                    // 修复 #3：只在接管档按需注册（MediaSession/binder 订阅属「干预」，
                    // 120 档不创建）；已创建的在 stock 档也不再驱动（无写操作，仅保留订阅）。
                    if (Gear.takeover() && events == null) {
                        Object c = field(manager, "mContext");
                        if (c instanceof Context) {
                            events = new PlaybackEvents(new PlaybackEvents.Sink() {
                                @Override public void changed(PlaybackEvents.Signal s) { onSignal(s); }
                            });
                            events.start((Context) c);
                            HookStatus.put("videoMedia", "registered");
                        }
                    }
                    if (events != null && Gear.takeover()) events.sceneChanged();
                }
            });
        } catch (Throwable t) {
            HookStatus.fail("view-scene", t);
        }
    }

    private static void onSignal(PlaybackEvents.Signal s) {
        signal = s;
        EventVideoState.Key old = EVENT.current();
        if (s == null || !s.playing || (old != null && (!Objects.equals(old.session, s.token)
                || !Objects.equals(old.media, s.media) || Float.compare(old.speed, s.speed) != 0))) {
            EVENT.invalidate();
            reason = "playback-event-revoked";
            transition(false);
        }
        final PlaybackEvents.Signal expected = s;
        try {
            WORK.execute(new Runnable() {
                @Override public void run() { evaluate(expected); }
            });
        } catch (Throwable t) {
            HookStatus.fail("view-evaluate-queue", t);
        }
    }

    private static void evaluate(PlaybackEvents.Signal expected) {
        try {
            if (signal != expected) return;
            EventVideoState.Key next = null;
            int pid = visiblePid();
            if (expected != null && expected.playing && expected.media != null
                    && eligible() && pid > 0) {
                String activity = String.valueOf(field(manager, "mCurActivityName"))
                        + "@" + WINDOW_EPOCH.get();
                next = new EventVideoState.Key(expected.token, expected.media, activity, pid,
                        expected.speed);
            }
            long rev = EVENT.change(next);
            if (rev < 0) return;
            eventChanges++;
            transition(false);
            if (next == null) {
                reason = "no-reliable-playing-session";
                return;
            }
            reason = "event-probing";
            probeAttempts = 0;
            probe(new Burst(rev, next));
        } catch (Throwable e) {
            errors++;
            fault = SurfaceSnapshot.errorCode(e);
            EVENT.invalidate();
            reason = "event-error";
            HookStatus.fail("view-evaluate", e);
            transition(false);
        }
    }

    private static final class Burst {
        final long revision;
        final EventVideoState.Key key;
        final VideoStartupProbe startup = new VideoStartupProbe(SystemClock.elapsedRealtime());

        Burst(long r, EventVideoState.Key k) {
            revision = r;
            key = k;
        }
    }

    private static void probe(final Burst burst) {
        if (!EVENT.same(burst.revision)) return;
        if (!burst.startup.canRead(SystemClock.elapsedRealtime())
                || !EVENT.takeRead(burst.revision)) {
            if (EVENT.finish(burst.revision, false)) {
                reason = "startup-window-exhausted";
                transition(false);
            }
            return;
        }
        long start = SystemClock.elapsedRealtime();
        probePid = burst.key.pid;
        try {
            if (!eligible() || visiblePid() != burst.key.pid) { onSignal(null); return; }
            String dump = SurfaceSnapshot.read();
            sampleMs = SystemClock.elapsedRealtime() - start;
            samples++;
            fault = "none";
            if (!EVENT.same(burst.revision)) return;
            String activity = String.valueOf(field(manager, "mCurActivityName"))
                    + "@" + WINDOW_EPOCH.get();
            if (!eligible() || visiblePid() != burst.key.pid
                    || !Objects.equals(activity, burst.key.activity)) {
                onSignal(null);
                return;
            }
            VideoEvidence.Sample sample = VideoEvidence.parse(dump, burst.key.pid);
            probeLayer = sample == null ? -1 : sample.id;
            parseState = sample == null ? explainMissing(dump, burst.key.pid) : "matched";
            VideoStartupProbe.Result result = burst.startup.observe(sample,
                    SystemClock.elapsedRealtime());
            probeAttempts = burst.startup.attempts();
            if (result == VideoStartupProbe.Result.WAIT) {
                reason = sample == null ? "waiting-video-layer" : "confirming-video-cadence";
                WORK.schedule(new Runnable() {
                    @Override public void run() { probe(burst); }
                }, 700, TimeUnit.MILLISECONDS);
            } else if (EVENT.finish(burst.revision, result == VideoStartupProbe.Result.HIGH)) {
                reason = result == VideoStartupProbe.Result.HIGH ? "event-confirmed-high"
                        : result == VideoStartupProbe.Result.NATIVE ? "event-confirmed-native"
                        : "startup-window-exhausted";
                transition(result == VideoStartupProbe.Result.HIGH);
            }
        } catch (Throwable e) {
            errors++;
            fault = SurfaceSnapshot.errorCode(e);
            sampleMs = SystemClock.elapsedRealtime() - start;
            if (EVENT.finish(burst.revision, false)) {
                reason = "probe-error-no-retry";
                transition(false);
            }
        }
    }

    private static String explainMissing(String dump, int pid) {
        if (dump == null) return "no-dump";
        boolean layer = false, owner = false, yuv = false;
        for (String line : dump.split("\\n")) {
            if (line.contains("SurfaceView[" + PKG + "/")) layer = true;
            if (line.matches(".*\\bownerPid=" + pid + "\\b.*")) owner = true;
            if (line.contains(PKG) && line.contains("requestedFrameRate:")) {
                java.util.regex.Matcher m = VideoEvidence.VOTE.matcher(line);
                if (m.find()) {
                    double r = Double.parseDouble(m.group(1));
                    if (r > 0 && r <= 60.5) return "explicit-low-vote";
                }
            }
            if (line.contains(PKG) && line.contains("layer:") && line.contains("format:")
                    && (line.contains("Y_CBCR") || line.contains("YCbCr") || line.contains("YCrCb")
                        || line.contains("YUV") || line.contains("NV12") || line.contains("NV21"))) {
                yuv = true;
            }
        }
        if (!layer) return "no-video-layer";
        if (!owner) return "owner-pid-not-seen";
        if (!yuv) return "no-yuv-buffer";
        return "visibility-opacity-size-or-ambiguity";
    }

    /**
     * #21 硬约束：非接管时只允许「下降/收口」（把 bili 窗口的 0 覆盖写回 2，即恢复原生），
     * 不允许抬升。内部自调用路径因此也不会在 120 档改写。
     */
    private static void transition(boolean high) {
        if (high && !Gear.takeover()) {
            EVENT.invalidate();
            HookStatus.stockPass("view-transition");
            return;
        }
        final boolean want = high && allowed();
        appliedHigh = want;
        boolean needsWindow = false;
        synchronized (LOCK) {
            for (Window w : WINDOWS.values()) {
                if (w.requested == 2 && w.changed != want) { needsWindow = true; break; }
            }
        }
        if (!needsWindow && want == lastPolicyHigh) return;
        final Object m = manager;
        Object handler = field(m, "mHandler");
        if (!(handler instanceof Handler) || !POSTED.compareAndSet(false, true)) return;
        boolean posted = false;
        try {
            posted = ((Handler) handler).post(new Runnable() {
                @Override public void run() {
                    try {
                        boolean now = allowed();
                        Window[] all;
                        synchronized (LOCK) { all = WINDOWS.values().toArray(new Window[0]); }
                        for (Window w : all) {
                            Object token = w.token.get();
                            Object policy = w.policy.get();
                            Object wm = field(policy, "mWmService");
                            Object wmLock = field(wm, "mGlobalLock");
                            if (token == null || policy == null || wmLock == null) continue;
                            // 与 OEM 方法同锁序：WM 锁 → 记录锁
                            synchronized (wmLock) {
                                synchronized (LOCK) {
                                    if (w.requested != 2 || w.changed == now) continue;
                                }
                                try {
                                    if (XposedHelpers.callMethod(wm, "windowForClient", null, token)
                                            != w.win.get()) continue;
                                    INTERNAL.set(Boolean.TRUE);
                                    override.invoke(policy, token, Integer.valueOf(now ? 0 : 2));
                                    synchronized (LOCK) { w.changed = now; }
                                    HookStatus.applied("view-transition");
                                } catch (Throwable t) {
                                    errors++;
                                    HookStatus.fail("view-transition-write", t);
                                } finally {
                                    INTERNAL.remove();
                                }
                            }
                        }
                        update.invoke(m);
                        lastPolicyHigh = now;
                    } catch (Throwable t) {
                        errors++;
                        HookStatus.fail("view-transition-task", t);
                    } finally {
                        POSTED.set(false);
                    }
                }
            });
        } catch (Throwable t) {
            errors++;
            HookStatus.fail("view-transition-post", t);
        }
        if (!posted) POSTED.set(false);
    }

    private static Object field(Object o, String n) {
        return Reflect.field(o, n);
    }
}
