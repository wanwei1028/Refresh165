package com.refresh165;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.database.ContentObserver;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.Menu;
import android.view.View;
import android.widget.Toast;
import java.lang.ref.WeakReference;
import java.lang.reflect.Method;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

import com.refresh165.xp.XC_MethodHook;
import com.refresh165.xp.XC_MethodHook.MethodHookParam;
import com.refresh165.xp.XposedBridge;
import com.refresh165.xp.XposedHelpers;

/** 原生应用页只接管 165 档数据；只有视频页替换 PreferenceScreen。 */
final class SettingsPages {
    static final String EXTRA_SCREEN = "refresh165_screen";
    static final String SCREEN_VIDEO = "video_fps";
    static final String EXTRA_FRAG = ":settings:show_fragment";
    static final String EXTRA_ARGS = ":settings:show_fragment_args";
    static final String EXTRA_TITLE = ":settings:show_fragment_title";
    static final String SHELL_FRAGMENT =
            "com.oplus.settings.feature.display.refreshhigh.ScreenRefreshHighAppNewFragment";
    static final String SUB_SETTINGS = "com.android.settings.SubSettings";

    static final String TITLE_VIDEO = "视频应用帧率";
    static final String SCREEN_APPS165 = "apps165";
    static final String TITLE_APPS165 = "支持 165Hz 的应用";

    /** R6 六选项。 */
    private static final int[] VALUES = {0, 30, 60, 90, 120, 165};
    private static final String[] LABELS = {
            "跟随片源", "最低 30 Hz", "60 Hz", "90 Hz", "120 Hz", "165 Hz"
    };

    private static volatile boolean sInstalled;
    private static volatile int sVideoBuilt;
    private static volatile String sLastFallback = "none";
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final AtomicBoolean VIDEO_RESET_RUNNING = new AtomicBoolean();
    private static final ExecutorService VIDEO_RESET = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "Refresh165VideoReset");
        t.setDaemon(true);
        return t;
    });
    /** 跨进程自证：settings 进程写、system_server 的 dumpsys 读（lead 要求）。 */
    static final String KEY_UI_STATUS = "refresh165_ui_status";
    private static final java.util.LinkedHashMap<String, String> UI_STATE =
            new java.util.LinkedHashMap<String, String>();

    /** 记录一条 UI 状态并写入 Secure（节流：只在值变化时写）。 */
    static void record(String key, Object value) {
        String v = value == null ? "null" : String.valueOf(value);
        synchronized (UI_STATE) {
            String old = UI_STATE.get(key);
            UI_STATE.put(key, v);
            if (v.equals(old)) return;
            StringBuilder b = new StringBuilder();
            for (java.util.Map.Entry<String, String> e : UI_STATE.entrySet()) {
                if (b.length() > 0) b.append(';');
                b.append(e.getKey()).append('=').append(e.getValue());
            }
            Context ctx = Sys.context();
            if (ctx == null) return;
            try {
                android.provider.Settings.Secure.putString(
                        ctx.getContentResolver(), KEY_UI_STATUS, b.toString());
            } catch (Throwable t) {
                HookStatus.fail("ui-status", t);
            }
        }
    }

    private SettingsPages() { }

    // --------------------------------------------------------------- 安装

    private static volatile int sCrashGuard;
    static final String MARKER_KEY = "refresh165_page_marker";
    private static volatile boolean sNativeInitHookOk;

    static void install(ClassLoader cl) throws Throwable {
        if (sInstalled) return;
        sInstalled = true;
        final Class<?> shell = XposedHelpers.findClass(SHELL_FRAGMENT, cl);
        // 2（lead 口径纠正）：**复用系统原生页** —— 重新拦截原生行，但只在 165 档接管；
        //    120 档完全不拦截（原生 override 语义），两边数据互不污染。
        try {
            installNativeApps(shell, cl);
        } catch (Throwable t) {
            HookStatus.fail("ui-native-apps", t);
        }

        // 核心修复：原生 onViewCreated 会调 initListViewAddPreference 初始化「原生行」，
        // 我们把 screen 换掉后它仍会跑 → 用原生行去操作我们的 screen → 崩。
        // 仅视频壳跳过原生行初始化。
        int nativeInit = XposedBridge.hookAllMethods(shell, "initListViewAddPreference",
                new XC_MethodHook() {
                    @Override protected void beforeHookedMethod(MethodHookParam p) {
                        guard("ui-shell-native-init", new Body() {
                            @Override public void run(MethodHookParam p2) {
                                if (screenOf(p2.thisObject) == null) return;
                                p2.setResult(null);
                                HookStatus.put("ui-shell", "skip-native-init");
                                record("nativeInit", "skipped");
                            }
                        }, p);
                    }
                });
        sNativeInitHookOk = nativeInit > 0;
        if (nativeInit > 0) HookStatus.installed("ui-shell-native-init");
        else HookStatus.fail("ui-shell-native-init", "no-method");

        int n = hookAfter(shell, "onCreate", "ui-shell-create", new Body() {
            @Override public void run(MethodHookParam p) throws Throwable {
                dispatchCreate(p.thisObject);
            }
        });
        n += hookAfter(shell, "onViewCreated", "ui-shell-view", new Body() {
            @Override public void run(MethodHookParam p) throws Throwable {
                dispatchViewCreated(p.thisObject);
            }
        });
        n += hookAfter(shell, "onResume", "ui-shell-resume", new Body() {
            @Override public void run(MethodHookParam p) throws Throwable {
                dispatchResume(p.thisObject);
            }
        });
        n += hookAfter(shell, "onConfigurationChanged", "ui-shell-config", new Body() {
            @Override public void run(MethodHookParam p) { hideSearchBar(p.thisObject); }
        });
        if (n > 0) HookStatus.installed("ui-shell");
        else HookStatus.fail("ui-shell", "no-method");
        HookStatus.put("ui-shell.hooks", n + (sNativeInitHookOk ? 1 : 0));

        // 视频壳不接收原生应用列表。
        try {
            Class<?> pre = XposedHelpers.findClass(
                    PRELOAD_CLASS, cl);
            int m = XposedBridge.hookAllMethods(pre, "loadData", new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam p) {
                    guard("ui-preload", new Body() {
                        @Override public void run(MethodHookParam p2) {
                            Object cb = Reflect.field(p2.thisObject, "mScreenRefreshRatePreLoadCallback");
                            if (cb == null || screenOf(cb) == null) return;
                            p2.setResult(null);
                            HookStatus.applied("ui-preload-null");
                        }
                    }, p);
                }
            });
            if (m > 0) HookStatus.installed("ui-preload");
            else HookStatus.fail("ui-preload", "no-method");
        } catch (Throwable t) {
            HookStatus.fail("ui-preload", t);
        }
    }

    private static final String ROW_CLASS =
            "com.oplus.settings.feature.display.refreshhigh.ScreenRefreshAppPreference";
    private static final String PRELOAD_CLASS =
            "com.oplus.settings.feature.display.refreshhigh.ScreenRefreshRateAppPreLoad";
    private static final Map<Object, NativePage> NATIVE_PAGES = new WeakHashMap<Object, NativePage>();
    private static final Map<Object, NativePage> NATIVE_ROWS = new WeakHashMap<Object, NativePage>();
    private static boolean sNativeAppsReady;
    private static final java.util.concurrent.atomic.AtomicInteger LOAD_VERSION =
            new java.util.concurrent.atomic.AtomicInteger();
    private static final ThreadLocal<NativeLoad> CURRENT_LOAD = new ThreadLocal<NativeLoad>();
    private static final Map<Object, NativeLoad> NATIVE_LOADS =
            java.util.Collections.synchronizedMap(new java.util.IdentityHashMap<Object, NativeLoad>());

    private static final class NativeLoad {
        final int version;
        final int mode;
        final int choice;
        final Context context;
        final boolean valid;

        NativeLoad(Object loader) {
            version = LOAD_VERSION.get();
            context = (Context) Reflect.field(loader, "mContext");
            mode = PerApp.uiMode(context);
            choice = Settings.Secure.getInt(context.getContentResolver(), PerApp.KEY_CHOICE, 120);
            valid = (version & 1) == 0
                    && toInt(Reflect.field(loader, "mChoiceModeRate")) == choice
                    && toInt(Reflect.field(loader, "mCurrentMode")) == (choice > 120 ? 8 : 3);
        }

        boolean matches() {
            return valid && version == LOAD_VERSION.get() && mode == PerApp.uiMode(context)
                    && choice == Settings.Secure.getInt(context.getContentResolver(),
                            PerApp.KEY_CHOICE, 120);
        }
    }

    private static final class NativePage {
        final WeakReference<Object> fragment;
        final Context context;
        final int mode;
        final int choice;
        final boolean apps165;
        final Handler handler = new Handler(Looper.getMainLooper());
        final ContentObserver observer;
        Set<String> checked;
        boolean observing;
        boolean active;
        boolean ready;
        boolean stale;
        boolean posted;
        boolean recreating;

        NativePage(Object owner, Context ctx) {
            fragment = new WeakReference<Object>(owner);
            Context app = ctx.getApplicationContext();
            context = app == null ? ctx : app;
            mode = PerApp.uiMode(ctx);
            choice = Settings.Secure.getInt(ctx.getContentResolver(), PerApp.KEY_CHOICE, 120);
            apps165 = mode == Gear.SETTING_MODE_165;
            checked = apps165 ? PerApp.readSet(ctx) : java.util.Collections.<String>emptySet();
            observer = new ContentObserver(handler) {
                @Override public void onChange(boolean selfChange) {
                    requestRefresh();
                }
            };
        }

        boolean matches() {
            try {
                return !stale && mode != Integer.MIN_VALUE && PerApp.uiMode(context) == mode
                        && Settings.Secure.getInt(context.getContentResolver(),
                                PerApp.KEY_CHOICE, 120) == choice;
            } catch (Throwable t) {
                HookStatus.fail("ui-native-mode", t);
                return false;
            }
        }

        void observe() {
            if (observing) return;
            try {
                context.getContentResolver().registerContentObserver(
                        Settings.Secure.getUriFor(PerApp.KEY_MODE), false, observer);
                context.getContentResolver().registerContentObserver(
                        Settings.Secure.getUriFor(PerApp.KEY_CHOICE), false, observer);
                context.getContentResolver().registerContentObserver(
                        Settings.Secure.getUriFor(PerApp.KEY_APPS_165), false, observer);
                observing = true;
            } catch (Throwable t) {
                context.getContentResolver().unregisterContentObserver(observer);
                HookStatus.fail("ui-native-observer", t);
            }
        }

        void stop() {
            active = false;
            ready = false;
            if (observing) {
                context.getContentResolver().unregisterContentObserver(observer);
                observing = false;
            }
        }

        void requestRefresh() {
            if (posted) return;
            posted = true;
            handler.post(new Runnable() {
                @Override public void run() {
                    posted = false;
                    try {
                        refresh();
                    } catch (Throwable t) {
                        HookStatus.fail("ui-native-refresh", t);
                    }
                }
            });
        }

        void refresh() {
            Object owner = fragment.get();
            if (owner == null || !ready) return;
            int currentMode = PerApp.uiMode(context);
            if (currentMode == Integer.MIN_VALUE) return;
            int currentChoice = Settings.Secure.getInt(
                    context.getContentResolver(), PerApp.KEY_CHOICE, 120);
            if (stale || mode != currentMode || choice != currentChoice) {
                stale = true;
                if (active && !recreating) {
                    Object activity = XposedHelpers.callMethod(owner, "getActivity");
                    if (activity instanceof Activity) {
                        recreating = true;
                        ((Activity) activity).recreate();
                    }
                }
                return;
            }
            if (apps165) {
                try {
                    checked = PerApp.parse(Settings.Secure.getString(
                            context.getContentResolver(), PerApp.KEY_APPS_165));
                } catch (Throwable t) {
                    HookStatus.fail("ui-native-read-set", t);
                }
            }
            Object rows = Reflect.field(owner, "mPreferences");
            if (rows instanceof List<?>) {
                for (Object row : (List<?>) rows) {
                    NATIVE_ROWS.put(row, this);
                    XposedHelpers.callMethod(row, "refreshUiData");
                    XposedHelpers.callMethod(owner, "syncPreferenceSwitchOnVisibleHolder", row);
                }
            }
            Object search = Reflect.field(owner, "mResultAdapter");
            if (search != null) XposedHelpers.callMethod(search, "notifyDataSetChanged");
        }
    }

    /**
     * 原生「支持高刷新率的应用」页拦截（lead 2026-10-26 口径：**复用系统原生页**）。
     *
     * <p>只在 **165 档**接管：勾选写 `refresh165_apps_165`（PerApp 独立存储），**不写系统 override XML**；
     * 120 档**完全不拦截**（原生 override 语义，数据互不污染）。
     * 自证：`r6ui.native=intercept-165|passthrough-120`、`ui-native-apps=installed`。
     */
    private static void installNativeApps(final Class<?> shell, ClassLoader cl) throws Throwable {
        final Class<?> row = XposedHelpers.findClass(ROW_CLASS, cl);
        int hooks = 0;
        hooks += hookBefore(row, "applyOverride", "ui-native-write", new Body() {
            @Override public void run(MethodHookParam p) {
                Context ctx = contextOf(p.thisObject);
                if (ctx == null) return;
                if (!is165Mode(ctx)) {          // 120 档：一行不吞，原生照常写
                    HookStatus.put("r6ui.native", "passthrough-120");
                    return;
                }
                String pkg;
                try {
                    pkg = (String) XposedHelpers.callMethod(p.thisObject, "getPackageName");
                } catch (Throwable t) {
                    HookStatus.fail("ui-native-pkg", t);
                    return;
                }
                boolean want = Boolean.TRUE.equals(p.args[0]);
                boolean ok = PerApp.setChecked(ctx, pkg, want);
                if (ok) {
                    p.setResult(null);          // 165 档：接管，不写系统 override XML
                    HookStatus.put("r6ui.native", "intercept-165");
                    HookStatus.applied("ui-native-write");
                } else {
                    HookStatus.put("r6ui.nativeWrite", "failed-passthrough");
                    Toast.makeText(ctx, "保存失败，已交给系统写入", Toast.LENGTH_SHORT).show();
                }
            }
        }, boolean.class);
        hooks += hookBefore(row, "isSwitchOn", "ui-native-read", new Body() {
            @Override public void run(MethodHookParam p) {
                Context ctx = contextOf(p.thisObject);
                if (ctx == null) return;
                if (!is165Mode(ctx)) {          // 120 档：读原生 override
                    HookStatus.put("r6ui.native", "passthrough-120");
                    return;
                }
                try {
                    String pkg = (String) XposedHelpers.callMethod(p.thisObject, "getPackageName");
                    p.setResult(Boolean.valueOf(PerApp.checked(pkg)));
                    HookStatus.put("r6ui.native", "intercept-165");
                    HookStatus.applied("ui-native-read");
                } catch (Throwable t) {
                    HookStatus.fail("ui-native-read", t);
                }
            }
        });
        hooks += hookBefore(shell, "resetOverrideAppRefreshRate", "ui-native-reset", new Body() {
            @Override public void run(MethodHookParam p) {
                Context ctx = contextOf(p.thisObject);
                if (ctx == null) return;
                if (!is165Mode(ctx)) {
                    HookStatus.put("r6ui.native", "passthrough-120");
                    return;
                }
                boolean ok = PerApp.writeSet(ctx, java.util.Collections.<String>emptySet());
                if (ok) {
                    p.setResult(null);
                    HookStatus.put("r6ui.native", "intercept-165");
                    HookStatus.applied("ui-native-reset");
                } else {
                    Toast.makeText(ctx, "保存失败，已交给系统处理", Toast.LENGTH_SHORT).show();
                }
            }
        });
        sNativeAppsReady = hooks > 0;
        HookStatus.put("ui-native-apps", hooks > 0 ? "installed" : "fail-install");
        HookStatus.put("uiNativeHooks", hooks);
        if (hooks == 0) HookStatus.fail("ui-native-apps", "no-method");
    }

    /** 165 档判定（显式设置档位，不用 takeover：原生页在 165 档才该用我们的数据）。 */
    private static boolean is165Mode(Context ctx) {
        try {
            int mode = PerApp.uiMode(ctx);
            return mode == Gear.SETTING_MODE_165 || mode == Integer.MIN_VALUE;
        } catch (Throwable t) {
            return false;
        }
    }


    private static void reloadNativeApps(Object fragment) {
        try {
            ClassLoader cl = fragment.getClass().getClassLoader();
            Class<?> utils = XposedHelpers.findClass(
                    "com.oplus.settings.feature.display.ScreenRefreshUtils", cl);
            Class<?> row = XposedHelpers.findClass(ROW_CLASS, cl);
            Object pre = XposedHelpers.callStaticMethod(
                    XposedHelpers.findClass(PRELOAD_CLASS, cl), "getInstance");
            Context ctx = contextOf(fragment);
            Object choice = XposedHelpers.callStaticMethod(utils, "getChoiceModeRate", ctx);
            Object perf = XposedHelpers.callStaticMethod(utils, "isPerfMode");
            Object off = XposedHelpers.callStaticMethod(utils, "getExtremeOffRate");
            XposedHelpers.callStaticMethod(row, "setPageLevelParams", choice, perf, off);
            Object mode = XposedHelpers.callStaticMethod(row, "getCachedMode");
            XposedHelpers.callMethod(pre, "setLoadParams", mode, choice, perf, off);
            XposedHelpers.callMethod(pre, "setCallback", fragment);
            XposedHelpers.callMethod(pre, "register");
            XposedHelpers.callMethod(pre, "loadData");
        } catch (Throwable t) {
            HookStatus.fail("ui-native-reload", t);
        }
    }

    private static boolean nativeItemsMatch(NativePage page, Object items) {
        if (!(items instanceof List<?>)) return false;
        int expected = page.choice > 120 ? 8 : 3;
        for (Object item : (List<?>) items) {
            if (toInt(XposedHelpers.callMethod(item, "getMode")) != expected) return false;
        }
        return true;
    }

    private static void bindNativeRows(Object fragment) {
        NativePage page = NATIVE_PAGES.get(fragment);
        if (page == null) return;
        Object rows = Reflect.field(fragment, "mPreferences");
        if (rows instanceof List<?>) {
            for (Object row : (List<?>) rows) NATIVE_ROWS.put(row, page);
        }
        page.requestRefresh();
    }

    private static Method lifecycleMethod(Class<?> cls, String name) throws NoSuchMethodException {
        for (Class<?> type = cls; type != null; type = type.getSuperclass()) {
            for (Method method : type.getDeclaredMethods()) {
                if (method.getName().equals(name)) return method;
            }
        }
        throw new NoSuchMethodException(cls.getName() + "#" + name);
    }

    private static int hookBefore(final Class<?> cls, String name, final String tag,
                                  final Body body, Class<?>... params) throws Throwable {
        Method method = params.length == 0 ? lifecycleMethod(cls, name)
                : cls.getDeclaredMethod(name, params);
        int count = XposedBridge.hookMethodCount(method, new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam p) {
                if (cls.isInstance(p.thisObject)) guard(tag, body, p);
            }
        });
        if (count > 0) HookStatus.installed(tag);
        else HookStatus.fail(tag, "no-method");
        return count;
    }

    interface Body {
        void run(MethodHookParam p) throws Throwable;
    }

    /** 所有壳 hook 的统一异常闸：绝不允许异常抛进 Fragment 生命周期（否则进程 WIN DEATH）。 */
    private static void guard(String tag, Body body, MethodHookParam p) {
        try {
            body.run(p);
        } catch (Throwable t) {
            sCrashGuard++;
            HookStatus.put("r6ui.crashGuard", sCrashGuard);
            HookStatus.fail(tag, t);
            record("crashGuard", sCrashGuard + ":" + tag);
        }
    }

    private static int hookAfter(final Class<?> cls, String name, final String tag, final Body body) {
        try {
            int n = XposedBridge.hookMethodCount(lifecycleMethod(cls, name), new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam p) {
                    if (!cls.isInstance(p.thisObject) || p.getThrowable() != null) return;
                    guard(tag, body, p);
                }
            });
            if (n > 0) HookStatus.installed(tag);
            else HookStatus.fail(tag, "no-method");
            return n;
        } catch (Throwable t) {
            HookStatus.fail(tag, t);
            return 0;
        }
    }

    // --------------------------------------------------------------- 启动（方案 C）

    /** 预检 + 启动原生壳；返回 false 表示应降级到对话框。 */
    static boolean start(Activity activity, String screen) {
        if (!SCREEN_VIDEO.equals(screen) && !SCREEN_APPS165.equals(screen)) return false;
        String why = preflight(activity);
        if (why != null) {
            sLastFallback = "dialog:" + why;
            HookStatus.put("r6ui.fallback", sLastFallback);
            HookStatus.fail("ui-start", why);
            return false;
        }
        try {
            Class<?> cls = Class.forName(SUB_SETTINGS, true, activity.getClassLoader());
            Intent intent = new Intent(activity, cls);
            // 禁 NEW_TASK：否则 taskAffinity 会另起 task，返回栈回不到「刷新率设定」（P0-3）
            intent.removeFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            intent.putExtra(EXTRA_FRAG, SHELL_FRAGMENT);
            intent.putExtra(EXTRA_TITLE,
                    SCREEN_APPS165.equals(screen) ? TITLE_APPS165 : TITLE_VIDEO);
            Bundle args = new Bundle();
            args.putString(EXTRA_SCREEN, screen);
            intent.putExtra(EXTRA_ARGS, args);
            activity.startActivity(intent);
            sLastFallback = "none";
            HookStatus.put("r6ui.fallback", "none");
            HookStatus.put("r6ui.screen", screen);
            HookStatus.applied("ui-start");
            return true;
        } catch (Throwable t) {
            sLastFallback = "dialog:start-failed";
            HookStatus.put("r6ui.fallback", sLastFallback);
            HookStatus.fail("ui-start", t);
            return false;
        }
    }

    /** @return null = 可进入方案 C；否则返回失败原因。 */
    private static String preflight(Activity activity) {
        if (activity == null) return "no-activity";
        try {
            Class.forName(SUB_SETTINGS, false, activity.getClassLoader());
        } catch (Throwable t) {
            return "no-subsettings";
        }
        try {
            android.content.res.Resources r = activity.getResources();
            String pkg = activity.getPackageName();
            int layout = r.getIdentifier("screen_refresh_rate_high_app", "layout", pkg);
            int listId = r.getIdentifier("custom_recyclerview", "id", pkg);
            if (layout == 0) return "no-shell-layout";
            if (listId == 0) return "no-shell-list-id";
            // Video uses the native page shell but not the native app-list data hooks.
            // A failed unrelated native-list hook must not hide the video settings page.
            if (!sNativeInitHookOk) return "shell-hook-incomplete";
            HookStatus.put("r6ui.preflight", "layout=ok list=ok");
            return null;
        } catch (Throwable t) {
            return "preflight-error";
        }
    }

    // --------------------------------------------------------------- 壳内分派

    /** 读 arguments 里的 `refresh165_screen`；不是我们的页面返回 null。 */
    static String screenOf(Object fragment) {
        try {
            Object args = XposedHelpers.callMethod(fragment, "getArguments");
            if (!(args instanceof Bundle)) return null;
            String screen = ((Bundle) args).getString(EXTRA_SCREEN);
            if (SCREEN_VIDEO.equals(screen)) return SCREEN_VIDEO;
            if (SCREEN_APPS165.equals(screen)) return SCREEN_APPS165;
            return null;
        } catch (Throwable t) {
            return null;
        }
    }

    private static void dispatchCreate(Object fragment) {
        String screen = screenOf(fragment);
        if (screen == null) return;
        try {
            Context ctx = contextOf(fragment);
            if (ctx == null) {
                HookStatus.fail("ui-page", "no-context");
                return;
            }
            Sys.setContext(ctx);
            setTitle(fragment, TITLE_VIDEO);
            // 换屏时机：不在 onCreate（原生随后还会跑 initListViewAddPreference），改到 onViewCreated after
            HookStatus.put("r6ui.screen", screen);
            record("screen", screen);
            record("title", TITLE_VIDEO);
        } catch (Throwable t) {
            HookStatus.fail("ui-page", t);
        }
    }

    private static void dispatchViewCreated(Object fragment) throws Throwable {
        String screen = screenOf(fragment);
        if (screen == null) return;
        Context ctx = contextOf(fragment);
        if (ctx == null) {
            HookStatus.fail("ui-page", "no-context");
            return;
        }
        Sys.setContext(ctx);
        // after 阶段：原生 onViewCreated（含 initListViewAddPreference）已跑完 → 现在换屏
        replaceScreen(fragment, ctx, screen);
        hideSearchBar(fragment);
        syncRows(fragment, screen);      // P0-5：restoreHierarchyState 之后按 Secure 值再同步一次
        record("viewCreated", screen);
    }

    private static void dispatchResume(Object fragment) throws Throwable {
        String screen = screenOf(fragment);
        if (screen == null) return;
        Context ctx = contextOf(fragment);
        if (ctx != null) Sys.setContext(ctx);
        if (!hasMarker(fragment) && ctx != null) {
            replaceScreen(fragment, ctx, screen);   // 幂等复检：被原生覆盖则重换
            record("reSwap", screen);
        }
        hideSearchBar(fragment);
        syncRows(fragment, screen);
    }

    /** 判断当前 screen 是否我们换上去的（看 marker 行）。 */
    private static boolean hasMarker(Object fragment) {
        try {
            Object ps = XposedHelpers.callMethod(fragment, "getPreferenceScreen");
            if (ps == null) return false;
            return XposedHelpers.callMethod(ps, "findPreference", MARKER_KEY) != null;
        } catch (Throwable t) {
            return false;
        }
    }

    private static Context contextOf(Object fragment) {
        try {
            Object ctx = XposedHelpers.callMethod(fragment, "getContext");
            if (ctx instanceof Context) return (Context) ctx;
        } catch (Throwable t) {
            HookStatus.fail("ui-context", t);
        }
        try {
            Object act = XposedHelpers.callMethod(fragment, "getActivity");
            if (act instanceof Context) return (Context) act;
        } catch (Throwable t) {
            HookStatus.fail("ui-context-activity", t);
        }
        return null;
    }

    private static void setTitle(Object fragment, String title) {
        try {
            Object act = XposedHelpers.callMethod(fragment, "getActivity");
            if (act == null) return;
            XposedHelpers.callMethod(act, "setTitle", title);
        } catch (Throwable t) {
            HookStatus.fail("ui-title", t);
        }
    }

    /** P0-4：搜索栏在 onViewCreated 才被 new 出来，只能在这里隐藏。 */
    static void hideSearchBar(Object fragment) {
        if (!SCREEN_VIDEO.equals(screenOf(fragment))) return;
        try {
            Object sv = Reflect.field(fragment, "mSearchView");
            if (sv == null) {
                HookStatus.put("r6ui.search", "absent");
                return;
            }
            if (sv instanceof View) {
                ((View) sv).setVisibility(View.GONE);
                HookStatus.put("r6ui.search", "hidden");
                return;
            }
            Object parent = XposedHelpers.callMethod(sv, "getView");
            if (parent instanceof View) {
                ((View) parent).setVisibility(View.GONE);
                HookStatus.put("r6ui.search", "hidden-view");
            }
        } catch (Throwable t) {
            HookStatus.fail("ui-search", t);
        }
    }

    // --------------------------------------------------------------- 建屏

    private static void replaceScreen(Object fragment, Context ctx, String screen) throws Throwable {
        Object mgr = XposedHelpers.callMethod(fragment, "getPreferenceManager");
        Object ps = XposedHelpers.callMethod(mgr, "createPreferenceScreen", ctx);
        if (SCREEN_APPS165.equals(screen)) {
            buildApps165(ps, ctx);
            HookStatus.put("r6ui.screen", SCREEN_APPS165);
        } else {
            buildVideo(ps, ctx);
            sVideoBuilt++;
            HookStatus.put("r6ui.video", "built=" + sVideoBuilt);
        }
        addMarker(ps, ctx);
        Object screen2 = XposedHelpers.callMethod(fragment, "getPreferenceScreen");
        if (screen2 != null) {
            try {
                XposedHelpers.callMethod(screen2, "removeAll");
            } catch (Throwable t) {
                HookStatus.fail("ui-remove-all", t);
            }
        }
        XposedHelpers.callMethod(fragment, "setPreferenceScreen", ps);
    }

    /** 幂等哨兵行（key 固定，用于判断当前 screen 是否我们换上去的）。 */
    private static void addMarker(Object screen, Context ctx) {
        try {
            Class<?> cls = findPreferenceClass(new String[]{
                    "com.coui.appcompat.preference.COUIPreference",
                    "androidx.preference.Preference"
            }, ctx);
            if (cls == null) return;
            Object row = cls.getConstructor(Context.class).newInstance(ctx);
            XposedHelpers.callMethod(row, "setKey", MARKER_KEY);
            XposedHelpers.callMethod(row, "setTitle", "");
            XposedHelpers.callMethod(row, "setVisible", Boolean.FALSE);
            XposedHelpers.callMethod(row, "setPersistent", Boolean.FALSE);
            XposedHelpers.callMethod(row, "setOrder", Integer.valueOf(-1));
            XposedHelpers.callMethod(screen, "addPreference", row);
        } catch (Throwable t) {
            HookStatus.fail("ui-marker", t);
        }
    }

    /**
     * 「视频应用帧率」：COUIListPreference 行（类不可用时降级为 COUIPreference + 对话框）
     * + 页脚「全部恢复跟随片源」。
     */
    // B6：枚举结果缓存（每次建页只枚举一次；旋转复用；包变化由广播失效）
    private static volatile List<VideoApps.Entry> sVideoCache;
    private static volatile List<VideoApps.Entry> sAppsCache;
    private static volatile String sVideoProof;
    private static volatile long sVideoCacheAt;
    private static volatile boolean sEnumReceiverRegistered;
    private static final java.util.Set<Object> sVideoScreens =
            java.util.Collections.newSetFromMap(new java.util.WeakHashMap<Object, Boolean>());

    private static void ensureEnumReceiver(Context ctx) {
        if (sEnumReceiverRegistered || ctx == null) return;
        try {
            android.content.IntentFilter f = new android.content.IntentFilter();
            f.addAction(android.content.Intent.ACTION_PACKAGE_ADDED);
            f.addAction(android.content.Intent.ACTION_PACKAGE_REMOVED);
            f.addAction(android.content.Intent.ACTION_PACKAGE_CHANGED);
            f.addDataScheme("package");
            ctx.getApplicationContext().registerReceiver(new android.content.BroadcastReceiver() {
                @Override public void onReceive(android.content.Context c, android.content.Intent i) {
                    sVideoCache = null;
                    sVideoCacheAt = 0;
                    sAppsCache = null;
                    HookStatus.put("r6ui.cache", "invalidated");
                }
            }, f);
            sEnumReceiverRegistered = true;
            HookStatus.put("r6ui.enumReceiver", "registered");
        } catch (Throwable t) {
            HookStatus.fail("ui-enum-receiver", t);
        }
    }

    /** B6：视频页 —— 有缓存直接建行；否则先放 loading 行，后台枚举后回主线程建行。 */
    private static void buildVideo(final Object screen, final Context ctx) {
        ensureEnumReceiver(ctx);
        List<VideoApps.Entry> apps = sVideoCache;
        if (apps != null) {
            int added = addVideoRows(screen, ctx, apps);
            HookStatus.put("r6ui.videoCache", "hit");
            finishVideo(screen, ctx, apps, added);
            return;
        }
        final Object loading = addLoadingRow(screen, ctx, "正在扫描视频应用…");
        HookStatus.put("r6ui.videoCache", "miss");
        Thread worker = new Thread(new Runnable() {
            @Override public void run() {
                StringBuilder proof = new StringBuilder();
                final List<VideoApps.Entry> list =
                        VideoApps.videoCandidates(ctx.getPackageManager(), proof);
                sVideoProof = proof.toString();
                sVideoCache = list;
                sVideoCacheAt = android.os.SystemClock.elapsedRealtime();
                Sys.main().post(new Runnable() {
                    @Override public void run() {
                        removeRow(screen, loading);
                        int added = addVideoRows(screen, ctx, list);
                        finishVideo(screen, ctx, list, added);
                    }
                });
            }
        }, "refresh165-enum-video");
        worker.setDaemon(true);
        worker.start();
    }

    private static int addVideoRows(Object screen, Context ctx, List<VideoApps.Entry> apps) {
        int added = 0;
        for (VideoApps.Entry e : apps) {
            try {
                Object row = newList(ctx, e.pkg, e.label);
                if (row == null) {
                    HookStatus.fail("ui-video-row", "no-row-control");
                    continue;
                }
                XposedHelpers.callMethod(screen, "addPreference", row);
                added++;
            } catch (Throwable t) {
                HookStatus.fail("ui-video-row", t);
            }
        }
        return added;
    }

    private static void finishVideo(Object screen, Context ctx,
                                    List<VideoApps.Entry> apps, int added) {
        if (apps.isEmpty() || added == 0) {
            addEmptyHint(screen, ctx, apps.isEmpty() ? "未识别到视频应用" : "行控件不可用");
        }
        HookStatus.put("ui-video", added > 0 ? "installed" : "fail");
        HookStatus.put("r6ui.videoRows", added);
        String proof = sVideoProof == null ? "seedUsed=?" : sVideoProof;
        HookStatus.put("video", "rows=" + added + " " + proof);
        record("video", "rows=" + added + " " + proof);
        addVideoResetRow(screen, ctx);
    }

    /**
     * 「支持 165Hz 的应用」页（lead 2026-10-26 恢复独立入口）：
     * 全部**可启动**应用 + COUISwitch 行（默认全关）+ 页脚「全部允许（二次确认）/全部关闭」。
     * 与原生「120Hz 应用」页**完全隔离**：原生页不再被拦截（`r6ui.native=touch-none`）。
     */
    private static void buildApps165(final Object screen, final Context ctx) {
        // 5（lead）：代码保留但**无入口**（入口已删除）→ 不可达；若被手工拉起则打标记后按视频页兜底
        HookStatus.put("r6ui.apps165", "unreachable");
        ensureEnumReceiver(ctx);
        List<VideoApps.Entry> apps = sAppsCache;
        if (apps != null) {
            int added = addApps165Rows(screen, ctx, apps);
            finishApps165(screen, ctx, apps, added);
            HookStatus.put("r6ui.apps165Cache", "hit");
            return;
        }
        final Object loading = addLoadingRow(screen, ctx, "正在读取应用列表…");
        HookStatus.put("r6ui.apps165Cache", "miss");
        Thread worker = new Thread(new Runnable() {
            @Override public void run() {
                final List<VideoApps.Entry> list =
                        VideoApps.candidates(ctx.getPackageManager(), true);
                sAppsCache = list;
                Sys.main().post(new Runnable() {
                    @Override public void run() {
                        removeRow(screen, loading);
                        int added = addApps165Rows(screen, ctx, list);
                        finishApps165(screen, ctx, list, added);
                    }
                });
            }
        }, "refresh165-enum-apps165");
        worker.setDaemon(true);
        worker.start();
    }

    private static int addApps165Rows(Object screen, Context ctx, List<VideoApps.Entry> apps) {
        int added = 0;
        for (VideoApps.Entry e : apps) {
            try {
                final Object row = newSwitchRow(ctx, e.pkg, e.label,
                        PerApp.checked(e.pkg));
                if (row == null) continue;
                XposedHelpers.callMethod(screen, "addPreference", row);
                added++;
            } catch (Throwable t) {
                HookStatus.fail("ui-apps165-row", t);
            }
        }
        return added;
    }

    private static void finishApps165(Object screen, Context ctx,
                                      List<VideoApps.Entry> apps, int added) {
        if (apps.isEmpty() || added == 0) {
            addEmptyHint(screen, ctx, apps.isEmpty() ? "未读取到可启动应用" : "行控件不可用");
        }
        addApps165BulkRows(screen, ctx);
        HookStatus.put("apps165", "rows=" + added);
        HookStatus.put("r6ui.apps165Rows", added);
        record("apps165", "rows=" + added);
    }

    /** 页脚批量：全部允许（二次确认）/ 全部关闭。 */
    private static void addApps165BulkRows(Object screen, Context ctx) {
        try {
            Class<?> cls = findPreferenceClass(new String[]{
                    "com.coui.appcompat.preference.COUIPreference",
                    "androidx.preference.Preference"
            }, ctx);
            if (cls == null) return;
            final Object allow = cls.getConstructor(Context.class).newInstance(ctx);
            XposedHelpers.callMethod(allow, "setKey", "refresh165_apps165_allow_all");
            XposedHelpers.callMethod(allow, "setTitle", "全部允许");
            XposedHelpers.callMethod(allow, "setSummary", "二次确认后把当前列表全部加入");
            XposedHelpers.callMethod(allow, "setOrder", Integer.valueOf(998));
            XposedHelpers.callMethod(allow, "setPersistent", Boolean.FALSE);
            XposedHelpers.callMethod(allow, "setOnPreferenceClickListener",
                    VideoRateUi.clickListener(ctx.getClassLoader(), new Runnable() {
                        @Override public void run() {
                            confirmAllowAll(ctx);
                        }
                    }));
            XposedHelpers.callMethod(screen, "addPreference", allow);

            final Object deny = cls.getConstructor(Context.class).newInstance(ctx);
            XposedHelpers.callMethod(deny, "setKey", "refresh165_apps165_deny_all");
            XposedHelpers.callMethod(deny, "setTitle", "全部关闭");
            XposedHelpers.callMethod(deny, "setOrder", Integer.valueOf(999));
            XposedHelpers.callMethod(deny, "setPersistent", Boolean.FALSE);
            XposedHelpers.callMethod(deny, "setOnPreferenceClickListener",
                    VideoRateUi.clickListener(ctx.getClassLoader(), new Runnable() {
                        @Override public void run() {
                            PerApp.writeSet(ctx, java.util.Collections.<String>emptySet());
                            HookStatus.put("apps165Bulk", "deny:0");
                            record("apps165Bulk", "deny:0");
                        }
                    }));
            XposedHelpers.callMethod(screen, "addPreference", deny);
            HookStatus.put("apps165Bulk", "rows=2");
        } catch (Throwable t) {
            HookStatus.fail("ui-apps165-bulk", t);
        }
    }

    private static void confirmAllowAll(final Context ctx) {
        try {
            new android.app.AlertDialog.Builder(ctx)
                    .setTitle("全部允许？")
                    .setMessage("将把当前列表内全部应用加入 165Hz 白名单。")
                    .setPositiveButton("确定", new android.content.DialogInterface.OnClickListener() {
                        @Override public void onClick(android.content.DialogInterface d, int w) {
                            List<VideoApps.Entry> apps = sAppsCache;
                            java.util.Set<String> all = new java.util.LinkedHashSet<String>();
                            if (apps != null) for (VideoApps.Entry e : apps) all.add(e.pkg);
                            PerApp.writeSet(ctx, all);
                            HookStatus.put("apps165Bulk", "allow:" + all.size());
                            record("apps165Bulk", "allow:" + all.size());
                        }
                    })
                    .setNegativeButton("取消", null)
                    .show();
        } catch (Throwable t) {
            HookStatus.fail("ui-apps165-confirm", t);
        }
    }

    /** apps165 行：COUISwitchPreference（默认全关，写入走 PerApp 独立存储）。 */
    private static Object newSwitchRow(final Context ctx, final String pkg, String label,
                                       boolean checked) {
        try {
            Class<?> cls = findPreferenceClass(new String[]{
                    "com.coui.appcompat.preference.COUISwitchPreference",
                    "androidx.preference.SwitchPreferenceCompat",
                    "androidx.preference.SwitchPreference"
            }, ctx);
            if (cls == null) return null;
            final Object row = cls.getConstructor(Context.class).newInstance(ctx);
            XposedHelpers.callMethod(row, "setKey", pkg);
            XposedHelpers.callMethod(row, "setTitle", label);
            XposedHelpers.callMethod(row, "setPersistent", Boolean.FALSE);
            XposedHelpers.callMethod(row, "setChecked", Boolean.valueOf(checked));
            XposedHelpers.callMethod(row, "setOnPreferenceChangeListener",
                    VideoRateUi.stringChangeListener(ctx, new VideoRateUi.StringSink() {
                        @Override public boolean onChanged(String value) {
                            boolean want = Boolean.parseBoolean(value);
                            boolean ok = PerApp.setChecked(ctx, pkg, want);
                            HookStatus.put("apps165Write", ok ? "ok" : "failed");
                            return ok;
                        }
                    }));
            return row;
        } catch (Throwable t) {
            HookStatus.fail("ui-apps165-switch", t);
            return null;
        }
    }

    /** B6：占位 loading 行（枚举期间），完成后移除。 */
    private static Object addLoadingRow(Object screen, Context ctx, String text) {
        try {
            Class<?> cls = findPreferenceClass(new String[]{
                    "com.coui.appcompat.preference.COUIPreference",
                    "androidx.preference.Preference"
            }, ctx);
            if (cls == null) return null;
            Object row = cls.getConstructor(Context.class).newInstance(ctx);
            XposedHelpers.callMethod(row, "setKey", "refresh165_loading");
            XposedHelpers.callMethod(row, "setTitle", text);
            XposedHelpers.callMethod(row, "setPersistent", Boolean.FALSE);
            XposedHelpers.callMethod(row, "setOrder", Integer.valueOf(-5));
            XposedHelpers.callMethod(screen, "addPreference", row);
            HookStatus.put("r6ui.loading", "shown");
            return row;
        } catch (Throwable t) {
            HookStatus.fail("ui-loading", t);
            return null;
        }
    }

    private static void removeRow(Object screen, Object row) {
        if (row == null) return;
        try {
            XposedHelpers.callMethod(screen, "removePreference", row);
            HookStatus.put("r6ui.loading", "removed");
        } catch (Throwable t) {
            HookStatus.fail("ui-loading-remove", t);
        }
    }

    /** 空页兜底：给一行提示而不是白屏。 */
    private static void addEmptyHint(Object screen, Context ctx, String text) {
        try {
            Class<?> cls = findPreferenceClass(new String[]{
                    "com.coui.appcompat.preference.COUIPreference",
                    "androidx.preference.Preference"
            }, ctx);
            if (cls == null) return;
            Object row = cls.getConstructor(Context.class).newInstance(ctx);
            XposedHelpers.callMethod(row, "setKey", "refresh165_video_empty");
            XposedHelpers.callMethod(row, "setTitle", text);
            XposedHelpers.callMethod(row, "setPersistent", Boolean.FALSE);
            XposedHelpers.callMethod(row, "setOrder", Integer.valueOf(-1));
            XposedHelpers.callMethod(screen, "addPreference", row);
            HookStatus.put("videoEmptyHint", text);
        } catch (Throwable t) {
            HookStatus.fail("ui-video-empty", t);
        }
    }

    private static Object newList(final Context ctx, final String pkg, String label) {
        Class<?> cls = findPreferenceClass(new String[]{
                "com.coui.appcompat.preference.COUIListPreference",
                "com.coui.appcompat.preference.COUIListPreferenceCompat",
                "com.oplus.settings.widget.preference.OplusListPreference",
                "androidx.preference.ListPreference"
        }, ctx);
        if (cls == null) return plainRow(ctx, pkg, label);
        try {
            Object rowTmp;
            try {
                rowTmp = cls.getConstructor(Context.class).newInstance(ctx);
            } catch (NoSuchMethodException e) {
                rowTmp = cls.getConstructor(Context.class, android.util.AttributeSet.class)
                        .newInstance(ctx, null);
            }
            final Object row = rowTmp;      // 匿名监听器需要 effectively-final
            int cur = VideoRate.valueOf(pkg);
            List<Integer> values = new ArrayList<Integer>();
            List<String> labels = new ArrayList<String>();
            float panelMax = RateMap.panelMaxHz();
            boolean panel165 = RateMap.has165Panel();
            for (int i = 0; i < VALUES.length; i++) {
                int v = VALUES[i];
                if (v == 165 && (!panel165 || !Gear.uiTakeover())) continue;   // 裁定 E
                if (v != 0 && v != 30 && v > panelMax) continue;
                values.add(Integer.valueOf(v));
                labels.add(LABELS[i]);
            }
            final List<Integer> fValues = values;
            XposedHelpers.callMethod(row, "setKey", pkg);
            XposedHelpers.callMethod(row, "setTitle", label);
            XposedHelpers.callMethod(row, "setPersistent", Boolean.FALSE);   // P0-5
            XposedHelpers.callMethod(row, "setEntries", (Object) labels.toArray(new CharSequence[0]));
            String[] vals = new String[values.size()];
            for (int i = 0; i < vals.length; i++) vals[i] = String.valueOf(values.get(i));
            XposedHelpers.callMethod(row, "setEntryValues", (Object) vals);
            XposedHelpers.callMethod(row, "setValue", String.valueOf(cur < 0 ? 0 : cur));
            setAssignment(row, cur);
            XposedHelpers.callMethod(row, "setOnPreferenceChangeListener",
                    VideoRateUi.stringChangeListener(ctx, new VideoRateUi.StringSink() {
                        @Override public boolean onChanged(String value) {
                            int hz = 0;
                            try {
                                hz = Integer.parseInt(value);
                            } catch (Throwable ignored) {
                                hz = 0;
                            }
                            if (!isLegal(fValues, hz)) hz = 0;
                            if (VideoRate.put(ctx, pkg, hz)) {
                                XposedHelpers.callMethod(row, "setValue", String.valueOf(hz));
                                setAssignment(row, hz);
                                HookStatus.applied("ui-video-set");
                                return true;
                            }
                            HookStatus.fail("ui-video-set", "write-rejected");
                            Toast.makeText(ctx, "保存失败，已保留原有设置", Toast.LENGTH_SHORT).show();
                            return false;
                        }
                    }));
            return row;
        } catch (Throwable t) {
            HookStatus.fail("ui-video-new", t);
            return null;
        }
    }

    /** 行控件不可用时的降级：普通 Preference 行，点击弹原生单选对话框。 */
    private static Object plainRow(final Context ctx, final String pkg, final String label) {
        try {
            Class<?> cls = findPreferenceClass(new String[]{
                    "com.coui.appcompat.preference.COUIPreference",
                    "androidx.preference.Preference"
            }, ctx);
            if (cls == null) return null;
            final Object row = cls.getConstructor(Context.class).newInstance(ctx);
            XposedHelpers.callMethod(row, "setKey", pkg);
            XposedHelpers.callMethod(row, "setTitle", label);
            XposedHelpers.callMethod(row, "setPersistent", Boolean.FALSE);
            XposedHelpers.callMethod(row, "setSummary", summaryOf(VideoRate.valueOf(pkg)));
            XposedHelpers.callMethod(row, "setOnPreferenceClickListener",
                    VideoRateUi.clickListener(ctx.getClassLoader(), new Runnable() {
                        @Override public void run() {
                            VideoRateUi.showVideoOptionsFor(ctx, ctx.getClassLoader(), pkg, row);
                        }
                    }));
            HookStatus.applied("ui-video-plain-row");
            return row;
        } catch (Throwable t) {
            HookStatus.fail("ui-video-plain-row", t);
            return null;
        }
    }

    static String summaryOf(int hz) {
        return hz == 30 ? "最低 30 Hz" : (hz > 0 ? "固定 " + hz + " Hz" : "跟随片源");
    }

    private static boolean isLegal(List<Integer> values, int hz) {
        for (Integer v : values) if (v.intValue() == hz) return true;
        return false;
    }

    static void setAssignment(Object row, int hz) {
        String text = summaryOf(hz);
        try {
            XposedHelpers.callMethod(row, "setAssignment", text);
        } catch (Throwable t) {
            // androidx 无 setAssignment：退回 summary（观感略差，不阻断）
            try {
                XposedHelpers.callMethod(row, "setSummary", text);
            } catch (Throwable t2) {
                HookStatus.fail("ui-video-assignment", t);
            }
        }
    }

    private static Class<?> findPreferenceClass(String[] names, Context ctx) {
        ClassLoader cl = ctx.getClassLoader();
        for (String n : names) {
            try {
                return XposedHelpers.findClass(n, cl);
            } catch (Throwable t) {
                HookStatus.fail("ui-class-" + n.substring(n.lastIndexOf('.') + 1), t);
            }
        }
        return null;
    }

    // --------------------------------------------------------------- 页脚批量

    private static void addVideoResetRow(Object screen, final Context ctx) {
        try {
            Class<?> cls = findPreferenceClass(new String[]{
                    "com.coui.appcompat.preference.COUIPreference",
                    "androidx.preference.Preference"
            }, ctx);
            if (cls == null) return;
            final Object row = cls.getConstructor(Context.class).newInstance(ctx);
            XposedHelpers.callMethod(row, "setKey", "refresh165_video_reset");
            XposedHelpers.callMethod(row, "setTitle", "全部恢复跟随片源");
            XposedHelpers.callMethod(row, "setSummary", "清空本页所有固定帧率设置");
            XposedHelpers.callMethod(row, "setPersistent", Boolean.FALSE);
            XposedHelpers.callMethod(row, "setOrder", Integer.valueOf(900));
            XposedHelpers.callMethod(row, "setOnPreferenceClickListener",
                    VideoRateUi.clickListener(ctx.getClassLoader(), new Runnable() {
                        @Override public void run() {
                            resetVideoPolicy(ctx, preferenceParent(row));
                        }
                    }));
            XposedHelpers.callMethod(screen, "addPreference", row);
        } catch (Throwable t) {
            HookStatus.fail("ui-video-reset-row", t);
        }
    }

    /** 全部恢复跟随片源：>50 条直接删键（免超长写入），否则逐包写 0。 */
    private static void resetVideoPolicy(final Context ctx, final Object screen) {
        if (!VIDEO_RESET_RUNNING.compareAndSet(false, true)) return;
        VIDEO_RESET.execute(new Runnable() {
            @Override public void run() {
                final String[] message = {"恢复失败，请重试"};
                try {
                    List<VideoApps.Entry> apps = VideoApps.candidates(ctx.getPackageManager(), false);
                    if (apps.size() > 50) {
                        boolean ok = VideoRate.clear(ctx);
                        HookStatus.put("videoReset", ok ? "mode-clear" : "mode-clear-failed");
                        if (ok) {
                            HookStatus.applied("ui-video-reset");
                            message[0] = "已恢复跟随片源";
                        } else {
                            HookStatus.fail("ui-video-reset", "delete-failed");
                        }
                    } else {
                        int n = 0;
                        for (VideoApps.Entry e : apps) {
                            if (VideoRate.put(ctx, e.pkg, 0)) n++;
                        }
                        HookStatus.put("videoReset", "per-pkg:" + n);
                        int failed = apps.size() - n;
                        if (failed == 0) {
                            HookStatus.applied("ui-video-reset");
                            message[0] = "已恢复跟随片源";
                        } else {
                            HookStatus.fail("ui-video-reset", "partial-write " + n + "/" + apps.size());
                            message[0] = "部分应用恢复失败（" + failed + "）";
                        }
                    }
                } catch (Throwable t) {
                    HookStatus.fail("ui-video-reset", t);
                }
                MAIN.post(new Runnable() {
                    @Override public void run() {
                        VIDEO_RESET_RUNNING.set(false);
                        Toast.makeText(ctx, message[0], Toast.LENGTH_SHORT).show();
                        VideoRateUi.refreshSummaries(screen, ctx);
                    }
                });
            }
        });
    }

    private static Object preferenceParent(Object row) {
        try {
            return row == null ? null : XposedHelpers.callMethod(row, "getParent");
        } catch (Throwable t) {
            HookStatus.fail("ui-video-reset-parent", t);
            return null;
        }
    }

    // --------------------------------------------------------------- P0-5 行同步

    /** 按 Secure 存储重新 sync 一次（抵消 restoreHierarchyState 的旧实例态）。 */
    private static void syncRows(Object fragment, String screen) {
        try {
            Object ps = XposedHelpers.callMethod(fragment, "getPreferenceScreen");
            if (ps == null) return;
            int count = toInt(XposedHelpers.callMethod(ps, "getPreferenceCount"));
            Context ctx = contextOf(fragment);
            if (ctx == null) return;
            for (int i = 0; i < count; i++) {
                Object row = XposedHelpers.callMethod(ps, "getPreference", Integer.valueOf(i));
                if (row == null) continue;
                Object key = XposedHelpers.callMethod(row, "getKey");
                if (!(key instanceof String)) continue;
                String pkg = (String) key;
                if (pkg.startsWith("refresh165_")) continue;
                int hz = VideoRate.valueOf(pkg);
                try {
                    XposedHelpers.callMethod(row, "setValue", String.valueOf(hz < 0 ? 0 : hz));
                    setAssignment(row, hz);
                } catch (Throwable t) {
                    HookStatus.fail("ui-video-sync", t);
                }
            }
            HookStatus.put("r6ui.sync", "video:" + count);
        } catch (Throwable t) {
            HookStatus.fail("ui-sync", t);
        }
    }

    private static int toInt(Object v) {
        return v instanceof Number ? ((Number) v).intValue() : 0;
    }

    static String statusLine() {
        return "ui.shell=" + (sInstalled ? "ok" : "off")
                + " nativeApps=" + sNativeAppsReady + " videoBuilt=" + sVideoBuilt
                + " fallback=" + sLastFallback;
    }
}
