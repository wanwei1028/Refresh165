package com.refresh165;

import java.util.Objects;

/** Event identity and bounded evidence. No clock, timer, polling, or device access. */
final class EventVideoState {
    static final class Key {
        final Object session; final String media, activity; final int pid; final float speed;
        Key(Object session,String media,String activity,int pid,float speed) {
            this.session=session;this.media=media;this.activity=activity;this.pid=pid;this.speed=speed;
        }
        @Override public boolean equals(Object o) {
            if(!(o instanceof Key))return false;Key k=(Key)o;
            return Objects.equals(session,k.session)&&Objects.equals(media,k.media)
                &&Objects.equals(activity,k.activity)&&pid==k.pid&&Float.compare(speed,k.speed)==0;
        }
        @Override public int hashCode(){return Objects.hash(session,media,activity,pid,speed);}
    }
    private Key key;private long revision;private boolean high;private int reads;
    /** -1 means identical event: do not sample or send policy requests again. */
    synchronized long change(Key next) {
        if(Objects.equals(key,next))return -1;
        key=next;high=false;reads=0;return ++revision;
    }
    synchronized void invalidate(){key=null;high=false;reads=0;revision++;}
    synchronized Key current(){return key;}
    synchronized boolean same(long r){return key!=null&&revision==r;}
    synchronized boolean takeRead(long r){if(!same(r)||reads>=6)return false;reads++;return true;}
    synchronized boolean finish(long r,boolean qualified){if(!same(r))return false;high=qualified;return true;}
    synchronized boolean high(){return key!=null&&high;}
    synchronized int reads(){return reads;}
}
