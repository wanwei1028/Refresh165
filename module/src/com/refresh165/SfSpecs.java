package com.refresh165;

import android.os.IBinder;
import java.lang.reflect.Array;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.concurrent.atomic.AtomicInteger;

import com.refresh165.xp.XC_MethodHook;
import com.refresh165.xp.XposedBridge;
import com.refresh165.xp.XposedHelpers;

final class SfSpecs {
    private static final float HZ_165 = 165f;

    private static volatile Field fPrimary, fAppRequest, fPhysical, fRender, fMin, fMax, fDefault;
    private static volatile Field fToken;
    private static volatile Constructor<?> cSpecsCopy;
    private static volatile boolean sTokenArgument;
    private static final AtomicInteger sEntered = new AtomicInteger();
    private static final AtomicInteger sChanged = new AtomicInteger();
    private static final AtomicInteger sSubmitted = new AtomicInteger();
    private static final AtomicInteger sAccepted = new AtomicInteger();
    private static final AtomicInteger sRejected = new AtomicInteger();
    private static final AtomicInteger sMulti = new AtomicInteger();
    private static volatile int sSfModeId = -1;
    private static volatile String sFrom = "unknown";
    private static volatile String sToken = "unknown";
    private static volatile String sModeSrc = "unknown";
    private static volatile String sSkip = "none";

    private SfSpecs() { }

    static void install(ClassLoader cl) throws Throwable {
        Class<?> sc;
        Class<?> specsCls;
        try {
            sc = XposedHelpers.findClass("android.view.SurfaceControl", cl);
            specsCls = XposedHelpers.findClass(
                    "android.view.SurfaceControl$DesiredDisplayModeSpecs", cl);
        } catch (Throwable t) {
            HookStatus.failInstall("sf-specs", t);
            return;
        }
        resolveFields(specsCls);
        int n = 0;
        for (Method method : sc.getDeclaredMethods()) {
            if (!matches(method, specsCls)) continue;
            try {
                n += XposedBridge.hookMethodCount(method, new SpecsHook());
            } catch (Throwable t) {
                HookStatus.fail("sf-specs-hook", t);
            }
        }
        if (n > 0) {
            HookStatus.put("sfSpecsHooks", n);
            HookStatus.put("sfSpecsFields", fieldsOk() ? "ready" : "partial");
            HookStatus.put("sfSpecsAppliedMeaning", "changed-parameters-accepted-not-panel");
            if (fieldsOk()) HookStatus.installed("sf-specs");
            else HookStatus.failInstall("sf-specs", "required-reflection-unavailable");
        } else {
            HookStatus.failInstall("sf-specs", "no-method");
        }
    }

    private static boolean matches(Method method, Class<?> specsCls) {
        if (!method.getName().equals("setDesiredDisplayModeSpecs")) return false;
        Class<?>[] types = method.getParameterTypes();
        if (types.length != 2 || types[0] != IBinder.class) return false;
        Class<?> second = types[1].isArray() ? types[1].getComponentType() : types[1];
        return second == specsCls;
    }

    private static void resolveFields(Class<?> specsCls) {
        StringBuilder missing = new StringBuilder();
        ClassLoader cl = specsCls.getClassLoader();
        Class<?> rangesCls = null;
        Class<?> rangeCls = null;
        try {
            rangesCls = Class.forName("android.view.SurfaceControl$RefreshRateRanges", false, cl);
        } catch (Throwable t) {
            missing.append("RefreshRateRanges,");
        }
        try {
            rangeCls = Class.forName("android.view.SurfaceControl$RefreshRateRange", false, cl);
        } catch (Throwable t) {
            missing.append("RefreshRateRange,");
        }
        fPrimary = tryField(specsCls, "primaryRanges", missing);
        fAppRequest = tryField(specsCls, "appRequestRanges", missing);
        fDefault = tryField(specsCls, "defaultMode", missing);
        fPhysical = rangesCls == null ? null : tryField(rangesCls, "physical", missing);
        fRender = rangesCls == null ? null : tryField(rangesCls, "render", missing);
        fMin = rangeCls == null ? null : tryField(rangeCls, "min", missing);
        fMax = rangeCls == null ? null : tryField(rangeCls, "max", missing);
        fToken = null;
        sTokenArgument = false;
        try {
            Field token = specsCls.getDeclaredField("displayToken");
            token.setAccessible(true);
            fToken = token;
        } catch (NoSuchFieldException t) {
            sTokenArgument = true;
        } catch (Throwable t) {
            missing.append("displayToken,");
        }
        cSpecsCopy = null;
        try {
            Constructor<?> copy = specsCls.getDeclaredConstructor(specsCls);
            copy.setAccessible(true);
            cSpecsCopy = copy;
        } catch (Throwable t) {
            HookStatus.put("sfSpecsClone", "copy-constructor-unavailable: " + t);
            missing.append("copy-constructor,");
        }
        HookStatus.put("sfSpecsMissing", missing.length() == 0 ? "none" : missing.toString());
        if (missing.length() > 0) HookStatus.fail("sf-specs-fields", "missing=" + missing);
    }

    private static Field tryField(Class<?> owner, String name, StringBuilder missing) {
        try {
            Field f = owner.getDeclaredField(name);
            f.setAccessible(true);
            return f;
        } catch (Throwable t) {
            missing.append(name).append(',');
            return null;
        }
    }

    private static boolean fieldsOk() {
        return fPrimary != null && fAppRequest != null && fPhysical != null
                && fRender != null && fMin != null && fMax != null && fDefault != null
                && (fToken != null || sTokenArgument) && cSpecsCopy != null;
    }

    private static final class CallState {
        Object submitted;
    }

    private static final class SpecsHook extends XC_MethodHook {
        @Override protected void beforeHookedMethod(MethodHookParam p) {
            CallState state = new CallState();
            p.setExtra(state);
            HookStatus.put("sfSpecsEntered", sEntered.incrementAndGet());
            try {
                apply(p, state);
            } catch (Throwable t) {
                skip("apply-failed: " + t);
                HookStatus.fail("sf-specs-apply", t);
            }
        }

        @Override protected void afterHookedMethod(MethodHookParam p) {
            noteResult(p);
        }
    }

    private static void noteResult(XC_MethodHook.MethodHookParam p) {
        Object extra = p.getExtra();
        if (!(extra instanceof CallState)) return;
        CallState state = (CallState) extra;
        if (state.submitted == null) return;
        String reject = null;
        if (p.getThrowable() != null) {
            reject = p.getThrowable().toString();
        } else if (Boolean.FALSE.equals(p.getResult())) {
            reject = "returned-false";
        } else if (p.resultSet || p.args == null || p.args.length < 2
                || p.args[1] != state.submitted) {
            reject = "submission-not-confirmed";
        } else if (!Boolean.TRUE.equals(p.getResult()) && p.method.getReturnType() != void.class) {
            reject = "unconfirmed-result";
        }
        if (reject != null) {
            int rejected = sRejected.incrementAndGet();
            HookStatus.put("sfSpecsRejected", rejected);
            HookStatus.put("sf-specs-rejected", rejected);
            HookStatus.put("sfSpecsRejectErr", reject);
            return;
        }
        int accepted = sAccepted.incrementAndGet();
        HookStatus.put("sfSpecsAccepted", accepted);
        HookStatus.put("sfSpecsApplied", accepted);
        HookStatus.applied("sf-specs");
    }

    private static void apply(XC_MethodHook.MethodHookParam p, CallState state) throws Throwable {
        if (!Gear.takeover()) {
            pass("takeover-off");
            return;
        }
        if (VideoFixed.effectiveRateId() > 0) {
            pass("video-fixed");
            return;
        }
        if (!Hook.want165(MinFpsState.targetHz())) {
            pass("not-165");
            return;
        }
        if (p.resultSet || p.getThrowable() != null || p.args == null || p.args.length != 2
                || !(p.args[0] instanceof IBinder) || p.args[1] == null) {
            pass("invalid-or-short-circuited-call");
            return;
        }
        if (!fieldsOk()) {
            pass("fields-missing");
            return;
        }
        sToken = "unknown";
        sModeSrc = "unknown";
        Object policy = PolicyRef.policy();
        if (policy == null || Reflect.intField(policy, "mDisplayId", -1) != 0) {
            pass("primary-policy-unknown");
            return;
        }
        Object primary = Reflect.field(policy, "mActiveDisplayToken");
        Object modes = Reflect.field(policy, "mSfDisplayModes");
        Object info = Reflect.field(policy, "mDisplayInfo");
        if (!(primary instanceof IBinder) || info == null || modes == null
                || !modes.getClass().isArray() || Array.getLength(modes) == 0) {
            pass("primary-state-unknown");
            return;
        }
        HookStatus.put("sfTokenSrc", "policy-primary");
        HookStatus.put("sfModeCount", Array.getLength(modes));
        Object original = p.args[1];
        boolean array = original.getClass().isArray();
        int len = array ? Array.getLength(original) : 1;
        if (len > 1) HookStatus.put("sfSpecsMulti", sMulti.incrementAndGet());
        Object replacement = original;
        for (int i = 0; i < len; i++) {
            Object specs = array ? Array.get(original, i) : original;
            if (specs == null) {
                skip("null-spec");
                continue;
            }
            // 数组首参是 applyToken，不能代替任何元素的 displayToken。
            Object token = fToken != null ? fToken.get(specs)
                    : (!array && sTokenArgument ? p.args[0] : null);
            if (!(token instanceof IBinder) || !sameToken(token, primary)) {
                sToken = token == null ? "unknown" : "non-primary";
                HookStatus.put("sfSpecsToken", sToken);
                skip("spec-token-" + sToken);
                continue;
            }
            sToken = "policy-primary";
            HookStatus.put("sfSpecsToken", sToken);
            int mode = sfModeId(specs, modes);
            if (mode < 0) continue;
            Object copy;
            try {
                copy = copySpecs(specs);
            } catch (Throwable t) {
                skip("clone-failed: " + t);
                HookStatus.put("sfSpecsClone", sSkip);
                continue;
            }
            try {
                boolean changed = forceGroup(copy, fPrimary);
                changed |= forceGroup(copy, fAppRequest);
                changed |= setInt(copy, fDefault, mode);
                if (!changed) continue;
            } catch (Throwable t) {
                skip("copy-update-failed: " + t);
                continue;
            }
            if (replacement == original) {
                sFrom = describe(specs);
                if (array) {
                    replacement = Array.newInstance(original.getClass().getComponentType(), len);
                    System.arraycopy(original, 0, replacement, 0, len);
                }
            }
            if (array) Array.set(replacement, i, copy);
            else replacement = copy;
            sSfModeId = mode;
        }
        if (replacement == original) {
            HookStatus.stockPass("sf-specs");
            return;
        }
        HookStatus.put("sfSpecsChanged", sChanged.incrementAndGet());
        if (!Gear.takeover() || VideoFixed.effectiveRateId() > 0
                || !Hook.want165(MinFpsState.targetHz())) {
            pass("policy-gate-changed");
            return;
        }
        if (PolicyRef.policy() != policy || Reflect.intField(policy, "mDisplayId", -1) != 0
                || !sameToken(primary, Reflect.field(policy, "mActiveDisplayToken"))
                || Reflect.field(policy, "mSfDisplayModes") != modes
                || Reflect.field(policy, "mDisplayInfo") != info) {
            pass("primary-state-changed");
            return;
        }
        p.args[1] = replacement;
        state.submitted = replacement;
        HookStatus.put("sfSpecsSubmitted", sSubmitted.incrementAndGet());
        HookStatus.put("sfSpecsFrom", sFrom);
        HookStatus.put("sfModeId", sSfModeId);
    }

    private static boolean sameToken(Object a, Object b) {
        return a instanceof IBinder && b instanceof IBinder && (a == b || a.equals(b));
    }

    private static int sfModeId(Object specs, Object modes) throws IllegalAccessException {
        int requested = fDefault.getInt(specs);
        Object base = null;
        for (int i = 0; i < Array.getLength(modes); i++) {
            Object mode = Array.get(modes, i);
            if (mode != null && requested >= 0 && Reflect.intField(mode, "id", -1) == requested) {
                base = mode;
                break;
            }
        }
        int width = base == null ? -1 : Reflect.intField(base, "width", -1);
        int height = base == null ? -1 : Reflect.intField(base, "height", -1);
        if (width <= 0 || height <= 0) {
            sModeSrc = "default-mode-unknown";
            HookStatus.put("sfModeSrc", sModeSrc);
            skip(sModeSrc);
            return -1;
        }
        int selected = -1;
        for (int i = 0; i < Array.getLength(modes); i++) {
            Object mode = Array.get(modes, i);
            if (mode == null || Reflect.intField(mode, "width", -1) != width
                    || Reflect.intField(mode, "height", -1) != height
                    || !is165(Reflect.floatField(mode, "peakRefreshRate", Float.NaN))
                    || !is165(Reflect.floatField(mode, "vsyncRate", Float.NaN))) continue;
            int id = Reflect.intField(mode, "id", -1);
            if (id < 0) continue;
            if (selected < 0 || id == requested) selected = id;
            if (id == requested) break;
        }
        sModeSrc = selected < 0 ? "no-165-at-default-resolution" : "policy-same-resolution";
        HookStatus.put("sfModeSrc", sModeSrc);
        if (selected < 0) skip(sModeSrc);
        return selected;
    }

    private static boolean is165(float rate) {
        return Math.abs(rate - HZ_165) <= 0.5f;
    }

    private static Object copySpecs(Object original) throws Throwable {
        Constructor<?> copy = cSpecsCopy;
        if (copy == null) throw new IllegalStateException("copy-constructor-unavailable");
        Object result = copy.newInstance(original);
        Object[] sourceRanges = ranges(original);
        Object[] copiedRanges = ranges(result);
        for (Object target : copiedRanges) {
            if (target == null) throw new IllegalStateException("null-copied-range");
            for (Object source : sourceRanges) {
                if (target == source) throw new IllegalStateException("shared-copied-range");
            }
        }
        return result;
    }

    private static Object[] ranges(Object specs) throws IllegalAccessException {
        Object primary = fPrimary.get(specs);
        Object app = fAppRequest.get(specs);
        if (primary == null || app == null) throw new IllegalStateException("null-ranges");
        return new Object[]{primary, app, fPhysical.get(primary), fRender.get(primary),
                fPhysical.get(app), fRender.get(app)};
    }

    private static boolean forceGroup(Object specs, Field groupField) throws IllegalAccessException {
        Object group = groupField.get(specs);
        boolean changed = forceRange(group, fPhysical);
        changed |= forceRange(group, fRender);
        return changed;
    }

    private static boolean forceRange(Object group, Field rangeField) throws IllegalAccessException {
        Object range = rangeField.get(group);
        boolean changed = setFloat(range, fMin);
        changed |= setFloat(range, fMax);
        return changed;
    }

    private static boolean setFloat(Object target, Field f) throws IllegalAccessException {
        if (Float.compare(f.getFloat(target), HZ_165) == 0) return false;
        f.setFloat(target, HZ_165);
        return true;
    }

    private static boolean setInt(Object target, Field f, int value) throws IllegalAccessException {
        if (f.getInt(target) == value) return false;
        f.setInt(target, value);
        return true;
    }

    private static void skip(String reason) {
        sSkip = reason;
        HookStatus.put("sfSpecsSkip", reason);
    }

    private static void pass(String reason) {
        skip(reason);
        HookStatus.stockPass("sf-specs");
    }

    private static String describe(Object specs) {
        try {
            Object group = fPrimary.get(specs);
            Object phys = fPhysical.get(group);
            float min = fMin.getFloat(phys);
            float max = fMax.getFloat(phys);
            return min + "/" + max + "@" + fDefault.getInt(specs);
        } catch (Throwable t) {
            return "unknown";
        }
    }

    /** E：对外暴露 165 的 **SF** modeId（供 PanelRate/final-mode 使用；找不到返回 -1）。 */
    static int modeId165Public() {
        int id = sSfModeId;                  // apply() 期间解析出的 **SF** modeId
        if (id > 0) {
            HookStatus.put("modeIdSrc", "sf");
            return id;
        }
        int disp = RateMap.modeIdOfRateId(RateMap.ID_165);
        HookStatus.put("modeIdSrc", disp > 0 ? "display" : "missing");
        return disp;
    }

    static String statusLine() {
        return "sfSpecs entered=" + sEntered.get() + " changed=" + sChanged.get()
                + " submitted=" + sSubmitted.get() + " accepted=" + sAccepted.get()
                + " rejected=" + sRejected.get() + " applied=" + sAccepted.get()
                + " appliedScope=parameters-not-panel from=" + sFrom + " token=" + sToken
                + " mode=" + sModeSrc + " modeId=" + sSfModeId
                + " multi=" + sMulti.get() + " skip=" + sSkip;
    }
}
