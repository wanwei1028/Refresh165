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

/** Candidate: background system-only evidence gate. Not runtime accepted. */
final class SystemViewPolicy {
    private static final String PKG=VideoEvidence.PACKAGE;
    // 已移除 disable 文件开关，恒定启用。
    private static final Object LOCK=new Object();
    private static final WeakHashMap<Object,Window> WINDOWS=new WeakHashMap<>();
    private static final ThreadLocal<Boolean> INTERNAL=new ThreadLocal<>();
    private static final EventVideoState EVENT=new EventVideoState();
    private static final java.util.concurrent.atomic.AtomicLong WINDOW_EPOCH=new java.util.concurrent.atomic.AtomicLong();
    private static volatile PlaybackEvents events;
    private static volatile PlaybackEvents.Signal signal;
    private static volatile long eventChanges;
    private static volatile int probeAttempts,probePid,probeLayer=-1;
    private static volatile String parseState="not-started";
    private static volatile Object manager;
    private static volatile boolean installed, appliedHigh, lastPolicyHigh;
    private static final java.util.concurrent.atomic.AtomicBoolean POSTED=new java.util.concurrent.atomic.AtomicBoolean();
    private static volatile long samples, errors, sampleMs;
    private static volatile String fault="none";
    private static volatile String reason="waiting-manager";
    private static Method override, update;
    private static final ScheduledExecutorService WORK=Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t=new Thread(r,"Refresh165Video");t.setDaemon(true);return t;});
    private static final class Window {
        final WeakReference<Object> policy, token, win; final int pid;
        int requested; boolean changed;
        Window(Object p,Object t,Object w,int pid,int rate) {
            policy=new WeakReference<>(p);token=new WeakReference<>(t);win=new WeakReference<>(w);
            this.pid=pid;requested=rate;
        }
    }
    private SystemViewPolicy() {}
    static String status() { return " biliViewHook="+installed+" videoGate="+appliedHigh
        +" videoReason="+reason+" videoSamples="+samples+" videoErrors="+errors+" videoSampleMs="+sampleMs
        +" videoMode=event-burst videoProbePid="+probePid+" videoProbeLayer="+probeLayer+" videoParse="+parseState+" videoProbeAttempts="+probeAttempts+" videoEvents="+eventChanges+" videoMedia="+(events==null?"not-registered":events.status)
        +" videoMediaFaultAt="+(events==null?"none":events.failureAt)
        +" videoTransport=binder videoStage="+SurfaceSnapshot.stage+" videoFailedStage="+SurfaceSnapshot.failedStage
        +" videoFault="+fault+" videoBytes="+SurfaceSnapshot.bytes; }
    static void install(ClassLoader cl) {
        List<XC_MethodHook.Unhook> hooks=new ArrayList<>();
        try {
            Class<?> policy=XposedHelpers.findClass("com.android.server.wm.OplusRefreshRatePolicyImpl",cl);
            Class<?> binder=XposedHelpers.findClass("android.os.IBinder",cl);
            Class<?> ext=XposedHelpers.findClass("com.oplus.vrr.OPlusExternalRefreshRateManager",cl);
            override=policy.getDeclaredMethod("overrideWindowRefreshRate",binder,int.class);override.setAccessible(true);
            update=ext.getDeclaredMethod("handleTouchIdleState");update.setAccessible(true);
            Method check=ext.getDeclaredMethod("checkStatusByList");check.setAccessible(true);
            if (override.getReturnType()!=void.class || update.getReturnType()!=void.class
                    || check.getReturnType()!=boolean.class) return;
            hooks.add(XposedBridge.hookMethod(override,new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam p) {
                    if (Boolean.TRUE.equals(INTERNAL.get()) || p.args==null || p.args.length!=2
                            || !binder.isInstance(p.args[0]) || !(p.args[1] instanceof Integer)) return;
                    Object wm=field(p.thisObject,"mWmService"), lock=field(wm,"mGlobalLock");
                    if (lock==null) return;
                    synchronized(lock) {
                        try {
                            Object w=XposedHelpers.callMethod(wm,"windowForClient",null,p.args[0]);
                            if (w==null || !PKG.equals(XposedHelpers.callMethod(w,"getOwningPackage"))) return;
                            Object pid=field(field(w,"mSession"),"mPid");if (!(pid instanceof Number)) return;
                            int rate=(Integer)p.args[1];
                            synchronized(LOCK) {
                                Window rec=WINDOWS.get(p.args[0]);
                                if (rec==null || rec.win.get()!=w) {
                                    WINDOW_EPOCH.incrementAndGet();
                                    rec=new Window(p.thisObject,p.args[0],w,((Number)pid).intValue(),rate);
                                    WINDOWS.put(p.args[0],rec);
                                }
                                if(rec.requested!=rate)WINDOW_EPOCH.incrementAndGet();
                                rec.requested=rate;rec.changed=false;
                                if (rate==2 && allowed()) { p.args[1]=0;rec.changed=true; }
                            }
                        } catch(Throwable ignored) { }
                    }
                    sceneChanged();
                }
            }));
            hooks.add(XposedBridge.hookMethod(check,new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam p) {
                    manager=p.thisObject;
                    if (Boolean.FALSE.equals(p.getResult()) && allowed()) p.setResult(true);
                }
            }));
            hooks.add(XposedBridge.hookMethod(update,new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam p) { manager=p.thisObject;sceneChanged(); }
            }));
            installed=true; // No recurring sampling or watchdog jobs.
        } catch(Throwable ignored) { for(XC_MethodHook.Unhook h:hooks) h.unhook();reason="unsupported"; }
    }
    private static boolean eligible() {
        Object m=manager;
        if (m==null || false || !PKG.equals(field(m,"mCurPkgName"))) return false;
        Object context=field(m,"mContext");
        if (!(context instanceof Context)) return false;
        PowerManager pm=(PowerManager)((Context)context).getSystemService(Context.POWER_SERVICE);
        if (pm==null || !pm.isInteractive()) return false;
        Object req=field(m,"mAppRequest");
        if (req!=null) try {
            if (PKG.equals(XposedHelpers.callMethod(req,"getPkgName"))) {
                Object rate=XposedHelpers.callMethod(req,"getRefreshRate");
                if (!(rate instanceof Number) || (((Number)rate).intValue()>0 && ((Number)rate).intValue()<=60)) return false;
            }
        } catch(Throwable ignored) { return false; }
        return true;
    }
    private static boolean allowed() {
        try { return EVENT.high() && eligible(); }
        catch(Throwable ignored) { return false; }
    }
    private static int visiblePid() {
        int pid=0;
        Window[] all; synchronized(LOCK) { all=WINDOWS.values().toArray(new Window[0]); }
        for(Window w:all) try {
            Object win=w.win.get(), wm=field(w.policy.get(),"mWmService"), lock=field(wm,"mGlobalLock");
            if(win==null || lock==null) continue;
            synchronized(lock) {
                if(!Boolean.TRUE.equals(XposedHelpers.callMethod(win,"isVisible"))) continue;
                if (pid!=0 && pid!=w.pid) return 0;pid=w.pid;
            }
        } catch(Throwable ignored) { return 0; }
        return pid;
    }
    private static void sceneChanged() {
        try { WORK.execute(() -> {
            if(!eligible()) {EVENT.invalidate();reason="native-background-or-low-vote";transition(false);}
            if(events==null) {
                Object c=field(manager,"mContext");
                if(c instanceof Context) {events=new PlaybackEvents(SystemViewPolicy::onSignal);events.start((Context)c);}
            }
            if(events!=null)events.sceneChanged();
        }); } catch(Throwable ignored) { }
    }
    private static void onSignal(PlaybackEvents.Signal s) {
        signal=s;
        EventVideoState.Key old=EVENT.current();
        // Clear stale qualification on the event thread, even if a dump worker is blocked.
        if(s==null || !s.playing || old!=null && (!Objects.equals(old.session,s.token)
                ||!Objects.equals(old.media,s.media)||Float.compare(old.speed,s.speed)!=0)) {
            EVENT.invalidate();reason="playback-event-revoked";transition(false);
        }
        try { WORK.execute(() -> evaluate(s)); } catch(Throwable ignored) { }
    }
    private static void evaluate(PlaybackEvents.Signal expected) {
        try {
            if(signal!=expected)return;
            EventVideoState.Key next=null;
            int pid=visiblePid();
            if(expected!=null && expected.playing && expected.media!=null && eligible() && pid>0) {
                String activity=String.valueOf(field(manager,"mCurActivityName"))+"@"+WINDOW_EPOCH.get();
                next=new EventVideoState.Key(expected.token,expected.media,activity,pid,expected.speed);
            }
            long rev=EVENT.change(next);if(rev<0)return;
            eventChanges++;transition(false);
            if(next==null){reason="no-reliable-playing-session";return;}
            reason="event-probing";probeAttempts=0;probe(new Burst(rev,next));
        }catch(Throwable e){errors++;fault=SurfaceSnapshot.errorCode(e);EVENT.invalidate();reason="event-error";transition(false);}
    }
    private static final class Burst {
        final long revision;final EventVideoState.Key key;
        final VideoStartupProbe startup=new VideoStartupProbe(SystemClock.elapsedRealtime());
        Burst(long r,EventVideoState.Key k){revision=r;key=k;}
    }
    private static void probe(Burst burst) {
        if(!EVENT.same(burst.revision))return;
        if(!burst.startup.canRead(SystemClock.elapsedRealtime()) || !EVENT.takeRead(burst.revision)) {
            if(EVENT.finish(burst.revision,false)){reason="startup-window-exhausted";transition(false);}return;
        }
        long start=SystemClock.elapsedRealtime();probePid=burst.key.pid;
        try {
            if(!eligible() || visiblePid()!=burst.key.pid) {onSignal(null);return;}
            String dump=SurfaceSnapshot.read();sampleMs=SystemClock.elapsedRealtime()-start;samples++;fault="none";
            if(!EVENT.same(burst.revision))return;
            String activity=String.valueOf(field(manager,"mCurActivityName"))+"@"+WINDOW_EPOCH.get();
            if(!eligible()||visiblePid()!=burst.key.pid||!Objects.equals(activity,burst.key.activity)){onSignal(null);return;}
            VideoEvidence.Sample sample=VideoEvidence.parse(dump,burst.key.pid);
            probeLayer=sample==null?-1:sample.id;parseState=sample==null?explainMissing(dump,burst.key.pid):"matched";
            VideoStartupProbe.Result result=burst.startup.observe(sample,SystemClock.elapsedRealtime());
            probeAttempts=burst.startup.attempts();
            if(result==VideoStartupProbe.Result.WAIT) {
                reason=sample==null?"waiting-video-layer":"confirming-video-cadence";
                WORK.schedule(() -> probe(burst),700,TimeUnit.MILLISECONDS);
            } else if(EVENT.finish(burst.revision,result==VideoStartupProbe.Result.HIGH)) {
                reason=result==VideoStartupProbe.Result.HIGH?"event-confirmed-high":
                    result==VideoStartupProbe.Result.NATIVE?"event-confirmed-native":"startup-window-exhausted";
                transition(result==VideoStartupProbe.Result.HIGH);
            }
        }catch(Throwable e) {
            errors++;fault=SurfaceSnapshot.errorCode(e);sampleMs=SystemClock.elapsedRealtime()-start;
            if(EVENT.finish(burst.revision,false)){reason="probe-error-no-retry";transition(false);}
        }
    }
    private static String explainMissing(String dump,int pid) {
        if(dump==null)return "no-dump";
        boolean layer=false,owner=false,yuv=false;
        for(String line:dump.split("\\n")) {
            if(line.contains("SurfaceView["+PKG+"/"))layer=true;
            if(line.matches(".*\\bownerPid="+pid+"\\b.*"))owner=true;
            if(line.contains(PKG)&&line.contains("requestedFrameRate:")) {
                java.util.regex.Matcher m=VideoEvidence.VOTE.matcher(line);
                if(m.find()){double r=Double.parseDouble(m.group(1));if(r>0&&r<=60.5)return "explicit-low-vote";}
            }
            if(line.contains(PKG)&&line.contains("layer:")&&line.contains("format:")
                    &&(line.contains("Y_CBCR")||line.contains("YCbCr")||line.contains("YCrCb")||line.contains("YUV")||line.contains("NV12")||line.contains("NV21")))yuv=true;
        }
        if(!layer)return "no-video-layer";
        if(!owner)return "owner-pid-not-seen";
        if(!yuv)return "no-yuv-buffer";
        return "visibility-opacity-size-or-ambiguity";
    }
    private static void transition(boolean high) {
        final boolean want=high && allowed(); appliedHigh=want;
        boolean needsWindow=false;
        synchronized(LOCK) {
            for(Window w:WINDOWS.values()) if(w.requested==2 && w.changed!=want) { needsWindow=true;break; }
        }
        if(!needsWindow && want==lastPolicyHigh) return;
        Object m=manager;Object handler=field(m,"mHandler");
        if (!(handler instanceof Handler) || !POSTED.compareAndSet(false,true)) return;
        boolean posted=false;
        try { posted=((Handler)handler).post(() -> {
            try {
                boolean now=allowed();
                Window[] all; synchronized(LOCK) { all=WINDOWS.values().toArray(new Window[0]); }
                for(Window w:all) {
                    Object token=w.token.get(),policy=w.policy.get();
                    Object wm=field(policy,"mWmService"), wmLock=field(wm,"mGlobalLock");
                    if(token==null || policy==null || wmLock==null) continue;
                    // Same lock order as the intercepted OEM method: WM then records.
                    synchronized(wmLock) {
                        synchronized(LOCK) {
                            if(w.requested!=2 || w.changed==now) continue;
                        }
                        try {
                            if(XposedHelpers.callMethod(wm,"windowForClient",null,token)!=w.win.get()) continue;
                            INTERNAL.set(true);override.invoke(policy,token,now?0:2);
                            synchronized(LOCK) { w.changed=now; }
                        } catch(Throwable ignored) { errors++; }
                        finally { INTERNAL.remove(); }
                    }
                }
                update.invoke(m);lastPolicyHigh=now;
            } catch(Throwable ignored) { errors++; }
            finally { POSTED.set(false); }
        }); } catch(Throwable ignored) { errors++; }
        if(!posted) POSTED.set(false);
    }
    private static Object field(Object o,String n) {
        if(o==null) return null;
        for(Class<?> c=o.getClass();c!=null;c=c.getSuperclass()) try {
            Field f=c.getDeclaredField(n);f.setAccessible(true);return f.get(o);
        } catch(NoSuchFieldException ignored) {} catch(Throwable ignored) { return null; }
        return null;
    }
}
