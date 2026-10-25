package com.refresh165;

import android.os.IBinder;
import android.os.ParcelFileDescriptor;
import android.os.SystemClock;
import android.system.ErrnoException;
import android.system.Os;
import android.system.OsConstants;
import android.system.StructPollfd;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.util.concurrent.*;

/** Bounded read-only Binder dump; no exec, shell, permission changes or logs. */
final class SurfaceSnapshot {
    static final int MAX_BYTES=1_500_000;
    static final long TIMEOUT_MS=1500;
    static volatile String stage="not-started", failure="none", failedStage="none";
    static volatile long bytes, durationMs;
    private static volatile IBinder service;
    private static final ScheduledExecutorService CLOSER=Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t=new Thread(r,"Refresh165PipeDeadline");t.setDaemon(true);return t;
    });
    private SurfaceSnapshot() {}
    static String read() throws Exception {
        return read(service(),TIMEOUT_MS,MAX_BYTES);
    }
    private static IBinder service() throws Exception {
        try {
            stage="service";
            IBinder b=service;
            if(b==null || !b.isBinderAlive()) {
                b=(IBinder)Class.forName("android.os.ServiceManager")
                    .getDeclaredMethod("checkService",String.class).invoke(null,"SurfaceFlinger");
                if(b==null) throw new IOException("service-missing");
                service=b;
            }
            return b;
        } catch(Exception e) { failedStage=stage;failure=errorCode(e);throw e; }
    }
    static String read(IBinder b,long timeoutMs,int maxBytes) throws Exception {
        long start=SystemClock.elapsedRealtime();bytes=0;
        ParcelFileDescriptor[] pipe=null;ScheduledFuture<?> deadline=null;
        java.util.concurrent.atomic.AtomicBoolean completed=new java.util.concurrent.atomic.AtomicBoolean();
        try {
            stage="pipe";pipe=ParcelFileDescriptor.createPipe();
            final ParcelFileDescriptor[] owned=pipe;
            // Also bounds a stalled one-way transaction's open descriptors.
            deadline=CLOSER.schedule(() -> {
                if(completed.compareAndSet(false,true)) {
                    failedStage=stage;failure="deadline";close(owned[0]);close(owned[1]);
                }
            },timeoutMs,TimeUnit.MILLISECONDS);
            stage="transact";b.dumpAsync(pipe[1].getFileDescriptor(),new String[0]);
            close(pipe[1]);
            stage="read";
            StructPollfd fd=new StructPollfd();fd.fd=pipe[0].getFileDescriptor();
            fd.events=(short)(OsConstants.POLLIN|OsConstants.POLLHUP|OsConstants.POLLERR);
            Os.fcntlInt(fd.fd,OsConstants.F_SETFL,Os.fcntlInt(fd.fd,OsConstants.F_GETFL,0)|OsConstants.O_NONBLOCK);
            ByteArrayOutputStream out=new ByteArrayOutputStream(65536);byte[] buf=new byte[8192];
            for(;;) {
                long left=timeoutMs-(SystemClock.elapsedRealtime()-start);
                if(left<=0) throw new IOException("deadline");
                int ready;
                try { ready=Os.poll(new StructPollfd[]{fd},(int)Math.min(left,100)); }
                catch(ErrnoException e) { if(e.errno==OsConstants.EINTR) continue;throw e; }
                if(ready==0) continue;
                if((fd.revents&OsConstants.POLLNVAL)!=0) throw new IOException("closed-or-deadline");
                int n;
                try { n=Os.read(fd.fd,buf,0,buf.length); }
                catch(ErrnoException e) { if(e.errno==OsConstants.EAGAIN||e.errno==OsConstants.EINTR)continue;throw e; }
                if(n==0) break;
                if(n<0 || out.size()+n>maxBytes) throw new IOException("oversize");
                out.write(buf,0,n);bytes=out.size();
            }
            stage="validate";
            String result=out.toString("UTF-8");
            if(result.isEmpty() || result.contains("Permission Denial") || result.contains("Permission denied"))
                throw new IOException("empty-or-denied");
            // Reject an incomplete/foreign dump before policy sees it.
            if(!result.contains("Display identification data:") || !result.contains("Scheduler:"))
                throw new IOException("unrecognized-dump");
            if(!completed.compareAndSet(false,true))throw new IOException("deadline");
            failure="none";failedStage="none";stage="ok";return result;
        } catch(Exception e) { failedStage=stage;failure=errorCode(e);throw e; }
        finally {
            completed.set(true);durationMs=SystemClock.elapsedRealtime()-start;
            if(deadline!=null)deadline.cancel(false);
            if(pipe!=null) {close(pipe[0]);close(pipe[1]);}
        }
    }
    private static void close(ParcelFileDescriptor fd) {
        if(fd!=null)try {fd.close();}catch(Exception ignored){}
    }
    static String errorCode(Throwable t) {
        while(t instanceof InvocationTargetException && t.getCause()!=null)t=t.getCause();
        if(t instanceof ErrnoException)return "errno-"+((ErrnoException)t).errno;
        // Only our literal codes, never arbitrary exception text/user data.
        if(t instanceof IOException) {
            String m=t.getMessage();
            if("deadline".equals(m)||"oversize".equals(m)||"empty-or-denied".equals(m)
                ||"unrecognized-dump".equals(m)||"closed-or-deadline".equals(m)||"service-missing".equals(m))return m;
        }
        return t.getClass().getSimpleName();
    }
}
