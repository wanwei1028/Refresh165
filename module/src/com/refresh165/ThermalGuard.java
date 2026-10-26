package com.refresh165;

import android.content.Context;
import android.os.Handler;
import android.os.PowerManager;
import android.os.SystemClock;
import java.util.concurrent.Executor;

/**
 * 厂商高温请求持续 20s 或 AOSP SEVERE 时让出温控；
 * 厂商撤销且已观察到 AOSP≤MODERATE 连续 30s 后恢复。
 * 仅监听事件及可取消的到期检查，无周期轮询。
 */
final class ThermalGuard {
    static final long TRIP_DWELL_MS = 20_000L;
    static final long RELEASE_DWELL_MS = 30_000L;

    private static volatile boolean sTripped;
    private static boolean sVendorOpen;
    private static long sVendorOpenSince = -1L;
    private static long sRecoverySince = -1L;
    private static int sLastThermal = -1;
    private static boolean sThermalObserved;
    private static boolean sListenerOk;
    private static boolean sVendorObserved;
    private static boolean sVendorUnknown;
    private static String sReason = "init";
    private static long sVendorOpenCount;
    private static long sReleaseCount;
    private static boolean sInstallStarted;
    private static String sListenerState = "not-started";
    private static int sListenerAttempts;
    private static Listener sListener;
    private static DwellCheck sPending;
    private static boolean sCheckFailed;
    private static String sCheckState = "idle";
    private static long sCheckScheduled;
    /**
     * D1：本树**没有**周期兜底轮询 —— 所有热判定都由「一次性 dwell check（postDelayed）」驱动，
     * 失败即 fail-safe（interceptAllowed=false）。保留这个开关是为了让 `periodicTasks` 有真实来源，
     * 将来若加回 30s 兜底，只需在这里置 true。
     */
    private static boolean sPollFallback = false;
    private static long sCheckCancelled;
    private static long sCheckFired;

    private ThermalGuard() { }

    static synchronized void observe(int status, int rate) {
        long now = SystemClock.elapsedRealtime();
        tripIfDue(now);
        sVendorUnknown = status != 0 && rate < 0;
        sVendorObserved = !sVendorUnknown;
        boolean open = !sVendorUnknown && status != 0 && rate <= 3;
        if (open && !sVendorOpen) {
            sVendorOpenSince = now;
            sVendorOpenCount++;
        } else if (!open) {
            sVendorOpenSince = -1L;
        }
        sVendorOpen = open;
        update(now);
    }

    static synchronized void observeVote(Object vote) {
        if (vote == null) {
            observe(0, 0);
            return;
        }
        float hz = Reflect.floatField(vote, "mRefreshRate", -1f);
        observe(1, RateMap.rateIdOf(hz));
    }

    static synchronized void onThermalStatus(int status) {
        long now = SystemClock.elapsedRealtime();
        tripIfDue(now);
        sThermalObserved = status >= PowerManager.THERMAL_STATUS_NONE
                && status <= PowerManager.THERMAL_STATUS_SHUTDOWN;
        sLastThermal = sThermalObserved ? status : -1;
        sListenerState = sThermalObserved ? "ok" : "invalid-status";
        if (!sThermalObserved) HookStatus.runtimeFailed("thermal-listener", "invalid-status:" + status);
        update(now);
    }

    private static void tripIfDue(long now) {
        boolean vendorDue = sVendorOpen && sVendorOpenSince >= 0
                && now - sVendorOpenSince >= TRIP_DWELL_MS;
        boolean aospDue = sThermalObserved && sLastThermal >= PowerManager.THERMAL_STATUS_SEVERE;
        if (!sTripped && (vendorDue || aospDue)) {
            sTripped = true;
            sRecoverySince = -1L;
            sReason = vendorDue ? "vendor-20s" : "aosp-severe";
            android.util.Log.i("Refresh165", "thermalGuard TRIPPED reason=" + sReason);
        }
    }

    private static void evaluate(long now) {
        tripIfDue(now);
        // C8：释放 = 「厂商已撤销（sVendorObserved && !sVendorOpen）」+ 静置 RELEASE_DWELL_MS；
        //      AOSP 状态**只作否决**：观察到且 > MODERATE 时不允许释放（缺观察不放行也不拦）。
        boolean aospVeto = sThermalObserved
                && sLastThermal > PowerManager.THERMAL_STATUS_MODERATE;
        boolean vendorReleased = sVendorObserved && !sVendorOpen;
        boolean recovering = vendorReleased && !aospVeto;
        if (!sTripped || !recovering) {
            sRecoverySince = -1L;
        } else {
            if (sRecoverySince < 0) sRecoverySince = now;
            if (now - sRecoverySince >= RELEASE_DWELL_MS) {
                sTripped = false;
                sRecoverySince = -1L;
                sReason = "released";
                sReleaseCount++;
                android.util.Log.i("Refresh165", "thermalGuard NORMAL (released)");
            }
        }
    }

    private static void update(long now) {
        evaluate(now);
        long due = -1L;
        String phase = "none";
        if (!sTripped && sVendorOpen) {
            due = sVendorOpenSince + TRIP_DWELL_MS;
            phase = "trip";
        } else if (sTripped && sRecoverySince >= 0) {
            due = sRecoverySince + RELEASE_DWELL_MS;
            phase = "release";
        }
        scheduleCheck(due, phase, now);
        publish();
    }

    private static void scheduleCheck(long due, String phase, long now) {
        if (sPending != null && sPending.due == due && sPending.phase.equals(phase)) return;
        if (sPending != null) {
            DwellCheck previous = sPending;
            sPending = null;
            previous.handler.removeCallbacks(previous);
            sCheckCancelled++;
            sCheckState = "cancelled";
        }
        sCheckFailed = false;      // C8：每次调度都复位；一次 Handler 拒绝不永久锁死
        if (due < 0) return;
        try {
            DwellCheck check = new DwellCheck(Sys.main(), due, phase);
            sPending = check;
            if (!check.handler.postDelayed(check, Math.max(0L, due - now))) {
                sPending = null;
                sCheckFailed = true;
                HookStatus.put("thermalCheckFailed", "post-rejected");
                sCheckState = "rejected";
                HookStatus.runtimeFailed("thermal-check", "handler-rejected");
                return;
            }
            sCheckScheduled++;
            sCheckState = "scheduled";
        } catch (Throwable t) {
            sPending = null;
            sCheckFailed = true;
            sCheckState = "failed";
            HookStatus.runtimeFailed("thermal-check", t);
        }
    }

    private static final class DwellCheck implements Runnable {
        final Handler handler;
        final long due;
        final String phase;

        DwellCheck(Handler handler, long due, String phase) {
            this.handler = handler;
            this.due = due;
            this.phase = phase;
        }

        @Override public void run() {
            synchronized (ThermalGuard.class) {
                if (sPending != this) return;
                sPending = null;
                sCheckFired++;
                sCheckState = "fired";
                try {
                    update(SystemClock.elapsedRealtime());
                } catch (Throwable t) {
                    sCheckFailed = true;
                    sCheckState = "failed";
                    HookStatus.runtimeFailed("thermal-check", t);
                    publish();
                }
            }
        }
    }

    private static void publish() {
        HookStatus.put("thermalGuard", guardState());
        HookStatus.put("thermalReason", sReason);
        HookStatus.put("thermalVendorOpen", sVendorOpen ? 1 : 0);
        HookStatus.put("thermalVendorOpenCount", sVendorOpenCount);
        HookStatus.put("thermalRelease", sReleaseCount);
        HookStatus.put("thermalListener", sListenerState);
        HookStatus.put("thermalListenerRetry", sListenerAttempts);
    }

    static boolean tripped() {
        return sTripped;
    }

    /** D1：周期任务数（0 = 无；1 = 只有「热监听不可用」时的 30s 兜底轮询在跑）。 */
    static synchronized int periodicTaskCount() {
        return sPollFallback ? 1 : 0;
    }

    static synchronized boolean interceptAllowed() {
        return !sTripped && !sVendorUnknown && !sCheckFailed && available();
    }

    private static boolean available() {
        return (sListenerOk && sThermalObserved) || sVendorObserved;
    }

    private static String guardState() {
        return sTripped ? "TRIPPED" : (interceptAllowed() ? "NORMAL" : "UNAVAILABLE");
    }

    static synchronized String statusLine() {
        return guardState() + " reason=" + sReason
                + " listener=" + sListenerState + " listenerAttempts=" + sListenerAttempts
                + " aospObserved=" + sThermalObserved + " aospStatus=" + sLastThermal
                + " vendorObserved=" + sVendorObserved + " vendorUnknown=" + sVendorUnknown
                + " vendorOpen=" + sVendorOpen + " vendorOpenCount=" + sVendorOpenCount
                + " recoverySinceElapsedMs=" + sRecoverySince + " releases=" + sReleaseCount
                + " oneShot=" + (sPending == null ? "none" : sPending.phase)
                + " oneShotState=" + sCheckState + " oneShotFailed=" + sCheckFailed
                + " dueElapsedMs=" + (sPending == null ? -1L : sPending.due)
                + " scheduled=" + sCheckScheduled + " cancelled=" + sCheckCancelled
                + " fired=" + sCheckFired;
    }

    static void install() {
        synchronized (ThermalGuard.class) {
            if (sInstallStarted) return;
            sInstallStarted = true;
            sListenerState = "starting";
            publish();
        }
        try {
            Thread t = new Thread(new Runnable() {
                @Override public void run() {
                    for (int attempt = 1; attempt <= 40; attempt++) {
                        synchronized (ThermalGuard.class) {
                            sListenerAttempts = attempt;
                        }
                        if (installListener()) return;
                        if (attempt == 40) break;
                        try {
                            Thread.sleep(1500L);
                        } catch (InterruptedException ie) {
                            Thread.currentThread().interrupt();
                            listenerUnavailable("interrupted");
                            return;
                        }
                    }
                    listenerUnavailable("gave-up");
                }
            }, "Refresh165Thermal");
            t.setDaemon(true);
            t.start();
        } catch (Throwable t) {
            listenerUnavailable("start-failed");
            HookStatus.failInstall("thermal-listener", t);
        }
    }

    private static synchronized void listenerUnavailable(String state) {
        sListener = null;
        sListenerOk = false;
        sThermalObserved = false;
        sLastThermal = -1;
        sListenerState = state;
        update(SystemClock.elapsedRealtime());
        HookStatus.failInstall("thermal-listener", state);
    }

    private static boolean installListener() {
        try {
            Context ctx = Sys.context();
            if (ctx == null) {
                listenerUnavailable("waiting-context");
                return false;                   // C8：false → 外层重试继续
            }
            PowerManager pm = (PowerManager) ctx.getSystemService(Context.POWER_SERVICE);
            if (pm == null) {
                listenerUnavailable("waiting-service");
                return false;
            }
            final Handler handler = Sys.main();
            final Listener listener = new Listener();
            synchronized (ThermalGuard.class) {
                sListener = listener;
                sListenerState = "registering";
                publish();
            }
            Executor ex = new Executor() {
                @Override public void execute(Runnable r) {
                    if (!handler.post(r)) {
                        synchronized (ThermalGuard.class) {
                            if (sListener != listener) return;
                            listener.dispatchFailed = true;
                            sListenerOk = false;
                            sThermalObserved = false;
                            sLastThermal = -1;
                            sListenerState = "dispatch-failed";
                            update(SystemClock.elapsedRealtime());
                            HookStatus.runtimeFailed("thermal-listener", "handler-rejected");
                        }
                    }
                }
            };
            pm.addThermalStatusListener(ex, listener);
            synchronized (ThermalGuard.class) {
                listener.registered = true;
                sListenerOk = !listener.dispatchFailed;
                sListenerState = sListenerOk ? "registered-awaiting-status" : "dispatch-failed";
                HookStatus.installed("thermal-listener");
                if (sListenerOk && listener.pendingStatus != null) {
                    onThermalStatus(listener.pendingStatus.intValue());
                    listener.pendingStatus = null;
                } else {
                    publish();
                }
            }
            return true;
        } catch (Throwable t) {
            listenerUnavailable("retrying");
            HookStatus.failInstall("thermal-listener", t);
            return false;
        }
    }

    private static final class Listener implements PowerManager.OnThermalStatusChangedListener {
        boolean registered;
        boolean dispatchFailed;
        Integer pendingStatus;

        @Override public void onThermalStatusChanged(int status) {
            synchronized (ThermalGuard.class) {
                if (sListener != this) return;
                if (!registered) {
                    pendingStatus = Integer.valueOf(status);
                    return;
                }
                dispatchFailed = false;
                sListenerOk = true;
                onThermalStatus(status);
            }
        }
    }
}
