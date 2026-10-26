#!/usr/bin/env python3
import os
from pathlib import Path
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parents[1]
JAVA_HOME = Path(os.environ.get(
    "JAVA_HOME", "/Applications/Android Studio.app/Contents/jbr/Contents/Home"))
SOURCES = {
    "io/github/libxposed/api/XposedInterface.java": '''package io.github.libxposed.api;
import java.lang.reflect.Executable; import java.util.List;
public interface XposedInterface {
 void log(int priority, String tag, String message);
 HookBuilder hook(Executable executable);
 interface HookHandle { void unhook(); }
 interface HookBuilder { HookHandle intercept(Hooker hooker); }
 interface Hooker { Object intercept(Chain chain) throws Throwable; }
 interface Chain {
  Object getThisObject(); List<Object> getArgs(); Executable getExecutable();
  Object proceed(Object... args) throws Throwable;
 }
}''',
    "com/refresh165/xp/AdapterRegression.java": '''package com.refresh165.xp;
import io.github.libxposed.api.XposedInterface;
import java.lang.reflect.Executable; import java.lang.reflect.Method;
import java.util.Arrays; import java.util.List;
public class AdapterRegression {
 static int passed;
 static void check(boolean ok, String name) {
  if (!ok) throw new AssertionError(name); passed++; System.out.println("PASS " + name);
 }
 static final class Module implements XposedInterface {
  Hooker hooker;
  public void log(int p, String t, String m) { }
  public HookBuilder hook(Executable e) { return h -> { hooker = h; return () -> { }; }; }
 }
 static final class Target { public String call(String value) { return value; } }
 static final class Call implements XposedInterface.Chain {
  final Method method; final Throwable failure; int proceeds;
  Call(Method m, Throwable t) { method=m; failure=t; }
  public Object getThisObject() { return new Target(); }
  public List<Object> getArgs() { return Arrays.<Object>asList("input"); }
  public Executable getExecutable() { return method; }
  public Object proceed(Object... args) throws Throwable {
   proceeds++; if (failure != null) throw failure; return "original:" + args[0];
  }
 }
 public static void main(String[] args) throws Throwable {
  Module module = new Module(); XposedBridge.init(module, AdapterRegression.class.getClassLoader());
  Method method = Target.class.getDeclaredMethod("call", String.class);
  final int[] after = {0};
  XposedBridge.hookMethod(method, new XC_MethodHook() {
   @Override protected void beforeHookedMethod(MethodHookParam p) { p.setResult("short"); }
   @Override protected void afterHookedMethod(MethodHookParam p) {
    after[0]++; p.setResult("after:" + p.getResult());
   }
  });
  Call shortCall = new Call(method, null);
  check("after:short".equals(module.hooker.intercept(shortCall))
    && after[0] == 1 && shortCall.proceeds == 0, "after-runs-after-before-short-circuit");

  final IllegalStateException original = new IllegalStateException("original");
  XposedBridge.hookMethod(method, new XC_MethodHook() {
   @Override protected void afterHookedMethod(MethodHookParam p) { p.setResult("recovered"); }
  });
  Call recoveredCall = new Call(method, original);
  check("recovered".equals(module.hooker.intercept(recoveredCall)), "after-can-recover-original-throwable");

  XposedBridge.hookMethod(method, new XC_MethodHook() { });
  Call throwingCall = new Call(method, original);
  try { module.hooker.intercept(throwingCall); throw new AssertionError("lost-original-throwable"); }
  catch (IllegalStateException actual) { check(actual == original, "unhandled-original-throwable-propagates"); }

  XposedBridge.hookMethod(method, new XC_MethodHook() {
   @Override protected void beforeHookedMethod(MethodHookParam p) throws Throwable {
    p.args[0] = "partial"; throw new IllegalArgumentException("before");
   }
  });
  Call beforeFailure = new Call(method, null);
  check("original:input".equals(module.hooker.intercept(beforeFailure))
    && beforeFailure.proceeds == 1, "before-failure-proceeds-with-original-args");
  System.out.println("PASS total=" + passed);
 }
}'''
}

with tempfile.TemporaryDirectory(prefix="refresh165-xposed-") as tmp:
    base = Path(tmp)
    files = []
    for name, source in SOURCES.items():
        dest = base / name
        dest.parent.mkdir(parents=True, exist_ok=True)
        dest.write_text(source, encoding="utf-8")
        files.append(str(dest))
    files.extend([
        str(ROOT / "module/src/com/refresh165/xp/XC_MethodHook.java"),
        str(ROOT / "module/src/com/refresh165/xp/XposedBridge.java"),
    ])
    subprocess.run([
        str(JAVA_HOME / "bin/javac"), "--release", "11", "-encoding", "UTF-8",
        "-d", str(base), *files], check=True)
    subprocess.run([
        str(JAVA_HOME / "bin/java"), "-cp", str(base),
        "com.refresh165.xp.AdapterRegression"], check=True)
