#!/usr/bin/env python3
"""Regression: video candidates come from PackageManager, not a baked-in app list."""
import os
from pathlib import Path
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parents[1]
JAVA_HOME = Path(os.environ.get(
    "JAVA_HOME", "/Applications/Android Studio.app/Contents/jbr/Contents/Home"))
SOURCES = {
    "android/content/ContentResolver.java": '''package android.content; public class ContentResolver {}''',
    "android/provider/Settings.java": '''package android.provider; public class Settings { public static class Secure { public static String getString(android.content.ContentResolver c,String k){return null;} } }''',
    "com/refresh165/Sys.java": '''package com.refresh165; final class Sys { static android.content.ContentResolver cr(){return new android.content.ContentResolver();} }''',
    "android/content/Intent.java": '''package android.content;
public class Intent {
 public static final String ACTION_VIEW="android.intent.action.VIEW", CATEGORY_DEFAULT="android.intent.category.DEFAULT";
 public String action,type; public java.util.Set<String> categories=new java.util.HashSet<>();
 public Intent(String a){action=a;} public Intent addCategory(String c){categories.add(c);return this;}
 public Intent setType(String t){type=t;return this;}
}''',
    "android/content/pm/ApplicationInfo.java": '''package android.content.pm;
public class ApplicationInfo {
 public static final int CATEGORY_VIDEO=3; public String packageName,label; public int category=-1;
 public CharSequence loadLabel(PackageManager pm){return label;}
}''',
    "android/content/pm/ActivityInfo.java": '''package android.content.pm;
public class ActivityInfo { public ApplicationInfo applicationInfo; public String name; }''',
    "android/content/pm/ResolveInfo.java": '''package android.content.pm;
public class ResolveInfo { public ActivityInfo activityInfo; }''',
    "android/content/pm/PackageManager.java": '''package android.content.pm;
import java.util.*; import android.content.Intent;
public class PackageManager {
 public static final int MATCH_ALL=0x20000;
 public final Map<String,ApplicationInfo> apps=new LinkedHashMap<>();
 public final Set<String> handlers=new LinkedHashSet<>(),launchable=new HashSet<>(); public final Map<String,String> activityNames=new HashMap<>();
 public List<ApplicationInfo> getInstalledApplications(int flags){return new ArrayList<>(apps.values());}
 public List<ResolveInfo> queryIntentActivities(Intent i,int flags){
  if(!Intent.ACTION_VIEW.equals(i.action)||!Intent.CATEGORY_DEFAULT.equals(i.categories.iterator().next())||!"video/*".equals(i.type)) throw new IllegalArgumentException();
  List<ResolveInfo> out=new ArrayList<>(); for(String pkg:handlers){ResolveInfo r=new ResolveInfo();r.activityInfo=new ActivityInfo();r.activityInfo.applicationInfo=apps.get(pkg);r.activityInfo.name=activityNames.get(pkg);out.add(r);} return out;
 }
 public Object getLaunchIntentForPackage(String p){return launchable.contains(p)?new Object():null;}
 public ApplicationInfo getApplicationInfo(String p,int flags){ApplicationInfo a=apps.get(p);if(a==null)throw new RuntimeException("missing");return a;}
}''',
    "com/refresh165/HookStatus.java": '''package com.refresh165; final class HookStatus {
 static void fail(String tag,Throwable t){} static void put(String key,Object value){}
}''',
    "com/refresh165/VideoAppsRegression.java": '''package com.refresh165;
import android.content.pm.*; import java.util.*;
public class VideoAppsRegression {
 static int passed; static void check(boolean v,String n){if(!v)throw new AssertionError(n);passed++;System.out.println("PASS "+n);}
 static ApplicationInfo app(PackageManager pm,String pkg,String label,int category,boolean launch){
  ApplicationInfo a=new ApplicationInfo();a.packageName=pkg;a.label=label;a.category=category;pm.apps.put(pkg,a);if(launch)pm.launchable.add(pkg);return a;
 }
 public static void main(String[] args){
  PackageManager pm=new PackageManager();
  ApplicationInfo vlc=app(pm,"org.example.vlc","播放器 A",-1,true);
  app(pm,"org.example.category","播放器 B",ApplicationInfo.CATEGORY_VIDEO,true);
  app(pm,"org.example.search","搜索器（系统解析结果）",-1,true);
  app(pm,"org.example.hidden","不可启动处理器",-1,false);
  app(pm,"org.example.regular","普通应用",-1,true);
  app(pm,"org.example.douyin","抖音",-1,true);
  app(pm,"org.example.redfruit","红果免费短剧",-1,true);
  app(pm,"org.example.bili","哔哩哔哩",-1,true);
  app(pm,"org.example.qq","QQ",-1,true);
  app(pm,"org.example.quark","夸克",-1,true);
  app(pm,"org.example.baidu","百度",-1,true);
  pm.handlers.add(vlc.packageName);pm.handlers.add("org.example.search");pm.handlers.add("org.example.hidden");
  pm.handlers.add("org.example.qq");pm.handlers.add("org.example.quark");pm.handlers.add("org.example.baidu");
  pm.activityNames.put(vlc.packageName,"org.example.vlc.VideoPlayerActivity");
  pm.activityNames.put("org.example.search","org.example.SearchActivity");
  pm.activityNames.put("org.example.hidden","org.example.VideoPlayerActivity");
  pm.activityNames.put("org.example.qq","org.example.JumpActivity");
  pm.activityNames.put("org.example.quark","org.example.VideoImportActivity");
  pm.activityNames.put("org.example.baidu","org.example.VideoLocalPlayActivity");
  StringBuilder proof=new StringBuilder();List<VideoApps.Entry> entries=VideoApps.videoCandidates(pm,proof);
  Set<String> pkgs=new HashSet<>();for(VideoApps.Entry e:entries)pkgs.add(e.pkg);
  check(pkgs.equals(new HashSet<>(Arrays.asList("org.example.vlc","org.example.category"))),"candidates-are-mime-player-and-category");
  check(entries.size()==2,"share-import-search-and-nonlaunchable-handlers-are-filtered");
  boolean label=false;for(VideoApps.Entry e:entries)if(e.pkg.equals("org.example.vlc"))label="播放器 A".equals(e.label)&&"mime".equals(e.source);
  check(label,"system-label-and-resolve-source-retained");
  for(VideoApps.Entry e:entries)if(e.pkg.endsWith("douyin")||e.pkg.endsWith("redfruit")||e.pkg.endsWith("bili"))check("label".equals(e.source),"ugc-streaming-label-source-"+e.pkg.substring(e.pkg.lastIndexOf('.')+1));
  check(proof.toString().contains("mime=org.example.vlc")&&!proof.toString().contains("mime=org.example.qq"),"generic-video-handlers-are-not-video-apps");
  check(proof.toString().contains("cat=org.example.category")&&!proof.toString().contains("label="),"category-proof-without-label-heuristic");
  System.out.println("PASS total="+passed);
 }
}'''
}

with tempfile.TemporaryDirectory(prefix="refresh165-video-apps-") as tmp:
    base = Path(tmp)
    files = []
    for name, source in SOURCES.items():
        dest = base / name
        dest.parent.mkdir(parents=True, exist_ok=True)
        dest.write_text(source, encoding="utf-8")
        files.append(str(dest))
    files.append(str(ROOT / "module/src/com/refresh165/VideoApps.java"))
    subprocess.run([str(JAVA_HOME / "bin/javac"), "--release", "11", "-encoding", "UTF-8",
                    "-d", str(base), *files], check=True)
    subprocess.run([str(JAVA_HOME / "bin/java"), "-cp", str(base),
                    "com.refresh165.VideoAppsRegression"], check=True)
