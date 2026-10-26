#!/usr/bin/env python3
import os
from pathlib import Path
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parents[1]
JAVA_HOME = Path(os.environ.get(
    "JAVA_HOME", "/Applications/Android Studio.app/Contents/jbr/Contents/Home"))
SOURCES = {
    "android/app/KeyguardManager.java": '''package android.app; public class KeyguardManager {
 public boolean isKeyguardLocked(){return false;}
}''',
    "android/content/Context.java": '''package android.content; public class Context {
 public static final String KEYGUARD_SERVICE="keyguard", POWER_SERVICE="power";
 public Object getSystemService(String name){return name.equals(POWER_SERVICE)?new android.os.PowerManager():new android.app.KeyguardManager();}
}''',
    "android/os/PowerManager.java": '''package android.os; public class PowerManager {
 public boolean isInteractive(){return true;}
}''',
    "com/refresh165/xp/XposedHelpers.java": '''package com.refresh165.xp;
import java.lang.reflect.Method;
public class XposedHelpers {
 public static Object callMethod(Object target,String name,Object... args)throws Throwable {
  for(Class<?> c=target.getClass();c!=null;c=c.getSuperclass())
   for(Method m:c.getDeclaredMethods()) if(m.getName().equals(name)&&m.getParameterCount()==args.length){
    m.setAccessible(true);return m.invoke(target,args);
   }
  throw new NoSuchMethodException(name);
 }
}''',
    "com/refresh165/Gear.java": '''package com.refresh165; final class Gear {
 static boolean takeover(){return true;}
}''',
    "com/refresh165/VideoRate.java": '''package com.refresh165; final class VideoRate {
 static final int FOLLOW=0;
 static int hzOf(String pkg){return 60;}
}''',
    "com/refresh165/PerApp.java": '''package com.refresh165; final class PerApp {
 static String foregroundPkg(){return "com.example.video";}
 static boolean isLauncher(String pkg){return false;}
}''',
    "com/refresh165/ThermalGuard.java": '''package com.refresh165; final class ThermalGuard {
 static boolean tripped(){return false;}
}''',
    "com/refresh165/RateMap.java": '''package com.refresh165; final class RateMap {
 static float panelMaxHz(){return 165;}
 static boolean hasDisplayModeFor(float hz){return true;}
 static int rateIdOf(float hz){return 7;}
}''',
    "com/refresh165/PolicyRef.java": '''package com.refresh165; final class PolicyRef {
 static Object value;
 static Object policy(){return value;}
}''',
    "com/refresh165/Reflect.java": '''package com.refresh165; final class Reflect {
 static Object field(Object target,String name){
  for(Class<?> c=target==null?null:target.getClass();c!=null;c=c.getSuperclass())try{
   java.lang.reflect.Field f=c.getDeclaredField(name);f.setAccessible(true);return f.get(target);
  }catch(Throwable ignored){}
  return null;
 }
}''',
    "com/refresh165/Sys.java": '''package com.refresh165; final class Sys {
 static android.content.Context context(){return new android.content.Context();}
}''',
    "com/refresh165/HookStatus.java": '''package com.refresh165; final class HookStatus {
 static void fail(String tag,Throwable t){}
 static void put(String key,Object value){}
}''',
    "com/refresh165/VideoFixedRegression.java": '''package com.refresh165;
public class VideoFixedRegression {
 static int passed;
 static void check(boolean ok,String label){if(!ok)throw new AssertionError(label);passed++;System.out.println("PASS "+label);}
 static final class Policy { Object mPickRefreshRateData; }
 static final class Holder { private final WindowState mCandidateWin; Holder(WindowState w){mCandidateWin=w;} }
 static final class WindowState {
  private final int mWindowingMode; final boolean failMethods,multi,pinned;
  WindowState(int mode,boolean fail,boolean multi,boolean pinned){mWindowingMode=mode;failMethods=fail;this.multi=multi;this.pinned=pinned;}
  private boolean inMultiWindowMode(){if(failMethods)throw new IllegalStateException();return multi;}
  private boolean inPinnedWindowingMode(){if(failMethods)throw new IllegalStateException();return pinned;}
 }
 static final class UnknownWindowState {
  private boolean inMultiWindowMode(){throw new IllegalStateException();}
  private boolean inPinnedWindowingMode(){throw new IllegalStateException();}
 }
 public static void main(String[] args){
  PolicyRef.value=null;
  check(!VideoFixed.singleForeground("com.example.video"),"missing-policy-data-fails-closed");
  check(VideoFixed.blocksGlobalPromotion(),"configured-video-policy-blocks-global-promotion");
  Policy p=new Policy();PolicyRef.value=p;
  WindowState full=new WindowState(1,true,false,false);p.mPickRefreshRateData=new Holder(full);
  check(VideoFixed.singleForeground("com.example.video"),"fullscreen-mode-is-positive-evidence");
  WindowState unknown=new WindowState(-1,true,false,false);p.mPickRefreshRateData=new Holder(unknown);
  check(!VideoFixed.singleForeground("com.example.video"),"unreadable-window-mode-fails-closed");
  WindowState multi=new WindowState(1,false,true,false);p.mPickRefreshRateData=new Holder(multi);
  check(!VideoFixed.singleForeground("com.example.video"),"multi-window-rejected");
  WindowState pinned=new WindowState(1,false,false,true);p.mPickRefreshRateData=new Holder(pinned);
  check(!VideoFixed.singleForeground("com.example.video"),"pinned-window-rejected");
  WindowState fullscreen=new WindowState(1,false,false,false);p.mPickRefreshRateData=new Holder(fullscreen);
  check(VideoFixed.singleForeground("com.example.video"),"both-negative-probes-confirm-single-window");
  System.out.println("PASS total="+passed);
 }
}'''
}

with tempfile.TemporaryDirectory(prefix="refresh165-video-fixed-") as tmp:
    base = Path(tmp)
    files = []
    for name, source in SOURCES.items():
        dest = base / name
        dest.parent.mkdir(parents=True, exist_ok=True)
        dest.write_text(source, encoding="utf-8")
        files.append(str(dest))
    files.append(str(ROOT / "module/src/com/refresh165/VideoFixed.java"))
    subprocess.run([
        str(JAVA_HOME / "bin/javac"), "--release", "11", "-encoding", "UTF-8",
        "-d", str(base), *files], check=True)
    subprocess.run([
        str(JAVA_HOME / "bin/java"), "-cp", str(base),
        "com.refresh165.VideoFixedRegression"], check=True)
