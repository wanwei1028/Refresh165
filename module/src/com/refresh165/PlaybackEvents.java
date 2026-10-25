package com.refresh165;

import android.content.*;
import android.media.MediaMetadata;
import android.media.session.*;
import android.os.*;
import java.security.MessageDigest;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** System-owned MediaSession notifications only; never hooks the player process. */
final class PlaybackEvents {
    interface Sink {void changed(Signal signal);}
    static final class Signal {
        final Object token;final String media;final float speed;final boolean playing;
        Signal(Object t,String m,float s,boolean p){token=t;media=m;speed=s;playing=p;}
    }
    private static final String PKG="tv.danmaku.bili";
    private final Sink sink;
    private final HandlerThread thread=new HandlerThread("Refresh165MediaEvents");
    private Handler handler;private Context context;private MediaSessionManager sessions;
    private MediaController selected;private MediaController.Callback callback;
    private PlaybackState playback;private String mediaIdentity;private Signal lastPublished;
    private MediaSessionManager.OnActiveSessionsChangedListener listener;
    private BroadcastReceiver screen;
    private volatile boolean ready;
    volatile String status="not-registered", failureAt="none";
    PlaybackEvents(Sink sink){this.sink=sink;}
    synchronized void start(Context c) {
        if(handler!=null)return;
        context=c;thread.start();handler=new Handler(thread.getLooper());
        handler.post(()->{
            try {
                screen=new BroadcastReceiver(){@Override public void onReceive(Context ctx,Intent intent){
                    if(Intent.ACTION_SCREEN_OFF.equals(intent.getAction()))sink.changed(null);
                    else {if(!ready)initializeSessions();publish();}
                }};
                IntentFilter f=new IntentFilter();f.addAction(Intent.ACTION_SCREEN_OFF);
                f.addAction(Intent.ACTION_SCREEN_ON);f.addAction(Intent.ACTION_BOOT_COMPLETED);
                status="screen-listener";
                c.registerReceiver(screen,f,null,handler,Context.RECEIVER_NOT_EXPORTED);
                initializeSessions();
            }catch(Throwable t){recordFailure(t);sink.changed(null);}
        });
    }
    private void initializeSessions() {
        if(ready)return;
        try {
            status="service";
            // VRR may start before MediaSessionService. Wait for a real boot/screen event, never poll.
            Object binder=Class.forName("android.os.ServiceManager").getDeclaredMethod("checkService",String.class)
                .invoke(null,"media_session");
            if(binder==null){status="waiting-media-service";return;}
            sessions=(MediaSessionManager)context.getSystemService(Context.MEDIA_SESSION_SERVICE);
            if(sessions==null)throw new IllegalStateException();
            listener=this::select;status="session-listener";
            sessions.addOnActiveSessionsChangedListener(listener,null,handler);
            ready=true;failureAt="none";select(sessions.getActiveSessions(null));
        } catch(Throwable t) {
            recordFailure(t);ready=false;
            if(sessions!=null&&listener!=null)try{sessions.removeOnActiveSessionsChangedListener(listener);}catch(Throwable ignored){}
            sink.changed(null);
        }
    }
    private void recordFailure(Throwable t) {
        failureAt=status+":"+(t.getStackTrace().length==0?"unknown":t.getStackTrace()[0].getClassName()+"#"+t.getStackTrace()[0].getMethodName());
        status="unavailable-"+t.getClass().getSimpleName();
    }
    void sceneChanged(){Handler h=handler;if(h!=null)h.post(this::publish);}
    private void select(List<MediaController> list) {
        try {
            MediaController next=null;int count=0;
            if(list!=null)for(MediaController c:list)if(PKG.equals(c.getPackageName())){next=c;count++;}
            if(count!=1)next=null; // Multiple sessions cannot reliably identify this video.
            if(selected!=null&&next!=null&&selected.getSessionToken().equals(next.getSessionToken())){publish();return;}
            if(selected!=null&&callback!=null)selected.unregisterCallback(callback);
            selected=next;callback=null;playback=null;mediaIdentity=null;lastPublished=null;sink.changed(null);
            if(next==null){status=count>1?"ambiguous-sessions":"no-bili-session";return;}
            final MediaController owner=next;
            callback=new MediaController.Callback(){
                @Override public void onPlaybackStateChanged(PlaybackState state){if(selected==owner){playback=state;publish(false);}}
                @Override public void onMetadataChanged(MediaMetadata metadata){if(selected==owner){
                    try{mediaIdentity=identity(metadata);}catch(Exception ignored){mediaIdentity=null;}publish(false);}}
                @Override public void onSessionDestroyed(){if(selected==owner){selected=null;playback=null;mediaIdentity=null;lastPublished=null;status="destroyed";sink.changed(null);}}
            };
            next.registerCallback(callback,handler);playback=next.getPlaybackState();mediaIdentity=identity(next.getMetadata());publish();
        } catch(Throwable t){status="event-error-"+t.getClass().getSimpleName();sink.changed(null);}
    }
    private void publish(){publish(true);}
    private void publish(boolean force) {
        if(!ready||selected==null){sink.changed(null);return;}
        try {
            PowerManager pm=(PowerManager)context.getSystemService(Context.POWER_SERVICE);
            PlaybackState state=playback;
            if(pm==null||!pm.isInteractive()||state==null||state.getState()!=PlaybackState.STATE_PLAYING||state.getPlaybackSpeed()<=0){
                status="not-playing";lastPublished=null;sink.changed(null);return;
            }
            String identity=mediaIdentity;
            if(identity==null){status="missing-media-identity";lastPublished=null;sink.changed(null);return;}
            status="playing-event";
            Signal next=new Signal(selected.getSessionToken(),identity,state.getPlaybackSpeed(),true);
            if(!force && lastPublished!=null && Objects.equals(next.token,lastPublished.token)
                    &&Objects.equals(next.media,lastPublished.media)&&Float.compare(next.speed,lastPublished.speed)==0)return;
            lastPublished=next;sink.changed(next);
        }catch(Throwable t){status="event-error-"+t.getClass().getSimpleName();sink.changed(null);}
    }
    private static String identity(MediaMetadata m)throws Exception {
        if(m==null)return null;
        String[] keys={MediaMetadata.METADATA_KEY_MEDIA_ID,MediaMetadata.METADATA_KEY_MEDIA_URI,
            MediaMetadata.METADATA_KEY_TITLE,MediaMetadata.METADATA_KEY_DISPLAY_TITLE,MediaMetadata.METADATA_KEY_ARTIST};
        MessageDigest hash=MessageDigest.getInstance("SHA-256");boolean present=false;
        for(String key:keys){String v=m.getString(key);if(v!=null&&!v.isEmpty())present=true;
            hash.update(key.getBytes(StandardCharsets.UTF_8));hash.update((byte)0);
            if(v!=null)hash.update(v.getBytes(StandardCharsets.UTF_8));hash.update((byte)0);}
        if(!present)return null;
        hash.update(Long.toString(m.getLong(MediaMetadata.METADATA_KEY_DURATION)).getBytes(StandardCharsets.UTF_8));
        StringBuilder s=new StringBuilder();for(byte b:hash.digest())s.append(String.format(java.util.Locale.ROOT,"%02x",b&255));
        return s.toString(); // No media titles/URIs are logged or persisted.
    }
}
