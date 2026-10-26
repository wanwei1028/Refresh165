package com.refresh165;

import android.content.Context;
import android.content.DialogInterface;
import android.os.Handler;
import android.os.Looper;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import com.refresh165.xp.XposedHelpers;

/** 视频设置入口及对话框兜底；应用名单保留原生入口。 */
final class VideoRateUi {
    static final String KEY_APPS165 = "refresh165_apps_165_entry";
    static final String KEY_VIDEO = "refresh165_video_fps_entry";
    static final String PARENT_KEY = "screen_refresh_rate_settings";
    static final String HIGH_APP_KEY = "screen_refresh_high";
    static final String FOOTER_KEY = "refresh_rate_footer_description";
    static final String TITLE_VIDEO = SettingsPages.TITLE_VIDEO;
    static final String TITLE_APPS165 = SettingsPages.TITLE_APPS165;

    private static final int[] VALUES = {0, 30, 60, 90, 120, 165};
    private static final String[] LABELS = {
            "跟随片源", "最低 30 Hz", "60 Hz", "90 Hz", "120 Hz", "165 Hz"
    };
    private static final ExecutorService APP_SCAN = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "Refresh165AppScan");
        t.setDaemon(true);
        return t;
    });
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final AtomicInteger APP_SCAN_GENERATION = new AtomicInteger();

    private VideoRateUi() { }

    // ------------------------------------------------------------- 回调工具

    interface StringSink {
        boolean onChanged(String value);
    }

    /** 同上，但回调 String 值（ListPreference 用）。 */
    static Object stringChangeListener(Context ctx, final StringSink sink) {
        try {
            ClassLoader cl = ctx.getClassLoader();
            Class<?> cls = XposedHelpers.findClass(
                    "androidx.preference.Preference$OnPreferenceChangeListener", cl);
            return Proxy.newProxyInstance(cl, new Class<?>[]{cls}, new InvocationHandler() {
                @Override public Object invoke(Object proxy, Method method, Object[] args) {
                    if ("onPreferenceChange".equals(method.getName())
                            && args != null && args.length == 2) {
                        try {
                            return Boolean.valueOf(sink != null
                                    && sink.onChanged(String.valueOf(args[1])));
                        } catch (Throwable t) {
                            HookStatus.fail("ui-string-listener", t);
                        }
                        return Boolean.FALSE;
                    }
                    return Boolean.TRUE;
                }
            });
        } catch (Throwable t) {
            HookStatus.fail("ui-string-listener", t);
            return null;
        }
    }

    /** androidx `Preference$OnPreferenceClickListener` 代理（null = 只关闭）。 */
    static Object clickListener(ClassLoader cl, final Runnable action) {
        try {
            Class<?> cls = XposedHelpers.findClass(
                    "androidx.preference.Preference$OnPreferenceClickListener", cl);
            return Proxy.newProxyInstance(cl, new Class<?>[]{cls}, new InvocationHandler() {
                @Override public Object invoke(Object proxy, Method method, Object[] args) {
                    if ("onPreferenceClick".equals(method.getName())) {
                        try {
                            if (action != null) action.run();
                        } catch (Throwable t) {
                            HookStatus.fail("ui-click-listener", t);
                        }
                        return Boolean.TRUE;
                    }
                    return Boolean.FALSE;
                }
            });
        } catch (Throwable t) {
            HookStatus.fail("ui-click-listener", t);
            return null;
        }
    }

    // ------------------------------------------------------------- 入口行

    /** 保留视频入口，宿主恢复时移除旧版本的额外应用入口。 */
    static void addRows(Object hostFragment, Object screen, Context ctx, ClassLoader cl) {
        if (screen == null || ctx == null) return;
        Sys.setContext(ctx);
        installObservers(ctx);
        // 回退（lead 2026-10-26 口径纠正）：**复用系统原生页** → 删除我们的 apps165 入口，
        // 只保留「支持高刷新率的应用」(原生, order 37) 与「视频应用帧率」(order 39)。
        removeLegacyEntry(screen);
        ensureSettingsParentVisible(screen);
        ensureEntry(screen, ctx, cl, KEY_VIDEO, TITLE_VIDEO, 39,
                SettingsPages.SCREEN_VIDEO);
        bumpFooter(screen);
        HookStatus.put("r6ui.order",
                "native=" + HIGH_APP_KEY + "=37," + KEY_VIDEO + "=39," + FOOTER_KEY + "=40");
        SettingsPages.record("entries",
                "native=" + rowState(screen, HIGH_APP_KEY)
                        + ",apps165=" + rowState(screen, KEY_APPS165)
                        + "(removed),video=" + rowState(screen, KEY_VIDEO));
    }

    /** The vendor hides this whole card outside its high-refresh gear; retain module controls. */
    private static void ensureSettingsParentVisible(Object screen) {
        try {
            Object parent = XposedHelpers.callMethod(screen, "findPreference", PARENT_KEY);
            if (parent != null) {
                XposedHelpers.callMethod(parent, "setVisible", Boolean.TRUE);
                HookStatus.put("r6ui.parentVisible", "true");
            } else {
                HookStatus.put("r6ui.parentVisible", "missing");
            }
        } catch (Throwable t) {
            HookStatus.fail("ui-entry-parent-visible", t);
        }
    }

    private static void removeLegacyEntry(Object screen) {
        try {
            Object row = XposedHelpers.callMethod(screen, "findPreference", KEY_APPS165);
            if (row == null) return;
            Object parent = XposedHelpers.callMethod(row, "getParent");
            if (parent != null) XposedHelpers.callMethod(parent, "removePreference", row);
        } catch (Throwable t) {
            HookStatus.fail("ui-remove-legacy-entry", t);
        }
    }

    private static void installObservers(Context ctx) {
        try {
            VideoRate.installObserver(ctx);
        } catch (Throwable t) {
            HookStatus.fail("ui-obs-video", t);
        }
        try {
            PerApp.installObserver(ctx);
        } catch (Throwable t) {
            HookStatus.fail("ui-obs-apps165", t);
        }
    }

    /** 行存在性探针（自证用）。 */
    static String rowState(Object screen, String key) {
        try {
            Object row = XposedHelpers.callMethod(screen, "findPreference", key);
            if (row == null) return "missing";
            Object order = XposedHelpers.callMethod(row, "getOrder");
            return "ok order=" + (order instanceof Number ? order : "?");
        } catch (Throwable t) {
            return "probe-error";
        }
    }

    /** 一行 = 一次独立安装尝试；已存在则只刷新 order/summary。 */
    private static void ensureEntry(Object screen, Context ctx, ClassLoader cl, String key,
                                    String title, int order, String targetScreen) {
        String tag = "ui-entry-" + order;
        try {
            Object exist = XposedHelpers.callMethod(screen, "findPreference", key);
            if (exist != null) {
                try {
                    XposedHelpers.callMethod(exist, "setOrder", Integer.valueOf(order));
                    XposedHelpers.callMethod(exist, "setSummary", safeSummary(ctx, targetScreen));
                    Object parent = XposedHelpers.callMethod(screen, "findPreference", PARENT_KEY);
                    if (parent != null) {
                        Object oldParent = XposedHelpers.callMethod(exist, "getParent");
                        if (oldParent != parent) {
                            if (oldParent != null) XposedHelpers.callMethod(oldParent, "removePreference", exist);
                            XposedHelpers.callMethod(parent, "addPreference", exist);
                        }
                    }
                } catch (Throwable t) {
                    HookStatus.fail(tag + "-order", t);
                }
                HookStatus.installed(tag);
                return;
            }
            addEntry(screen, ctx, cl, key, title, order, safeSummary(ctx, targetScreen),
                    targetScreen);
        } catch (Throwable t) {
            HookStatus.fail(tag, t);
            SettingsPages.record("entry" + order, "fail:" + t.getClass().getSimpleName());
        }
    }

    /** summary 计算绝不抛（否则会连带吃掉整个入口行）。 */
    private static String safeSummary(Context ctx, String targetScreen) {
        try {
            return videoSummary(ctx);
        } catch (Throwable t) {
            HookStatus.fail("ui-summary-safe", t);
            return "全部跟随片源";
        }
    }

    private static void addEntry(Object screen, Context ctx, ClassLoader cl, String key,
                                 String title, int order, String summary,
                                 final String targetScreen) throws Throwable {
        try {
            Object exist = XposedHelpers.callMethod(screen, "findPreference", key);
            if (exist != null) return;
            Class<?> prefCls = findPreferenceClass(cl, new String[]{
                    "com.oplus.settings.widget.preference.SettingClickableIfDisabledJumpPreference",
                    "com.coui.appcompat.preference.COUIPreference",
                    "androidx.preference.Preference"
            });
            if (prefCls == null) {
                HookStatus.fail("ui-entry", "no-preference-class");
                return;
            }
            final Object row = prefCls.getConstructor(Context.class).newInstance(ctx);
            XposedHelpers.callMethod(row, "setKey", key);
            XposedHelpers.callMethod(row, "setTitle", title);
            XposedHelpers.callMethod(row, "setSummary", summary);
            XposedHelpers.callMethod(row, "setPersistent", Boolean.FALSE);
            XposedHelpers.callMethod(row, "setOrder", Integer.valueOf(order));
            final Context fctx = ctx;
            final ClassLoader fcl = cl;
            XposedHelpers.callMethod(row, "setOnPreferenceClickListener",
                    clickListener(cl, new Runnable() {
                        @Override public void run() {
                            open(fctx, fcl, targetScreen, row);
                        }
                    }));
            Object parent = null;
            try {
                parent = XposedHelpers.callMethod(screen, "findPreference", PARENT_KEY);
            } catch (Throwable t) {
                HookStatus.fail("ui-entry-parent", t);
            }
            if (parent != null) {
                XposedHelpers.callMethod(parent, "addPreference", row);
                HookStatus.put("r6ui.parent", PARENT_KEY);
            } else {
                XposedHelpers.callMethod(screen, "addPreference", row);
                HookStatus.put("r6ui.parent", "fallback");
            }
            HookStatus.installed("ui-entry-" + order);
            SettingsPages.record("entry" + order, "added");
        } catch (Throwable t) {
            HookStatus.fail("ui-entry-" + order, t);
            SettingsPages.record("entry" + order, "fail:" + t.getClass().getSimpleName());
            throw t;
        }
    }

    /** 方案 C 优先；预检失败落回对话框（记 r6ui.fallback）。 */
    private static void open(Context ctx, ClassLoader cl, String screen, Object row) {
        Object act = null;
        try {
            act = ctx instanceof android.app.Activity ? ctx
                    : Reflect.field(ctx, "mBase");
        } catch (Throwable t) {
            HookStatus.fail("ui-open-activity", t);
        }
        if (act instanceof android.app.Activity
                && SettingsPages.start((android.app.Activity) act, screen)) {
            return;
        }
        showVideoDialog(ctx, cl, row);
    }

    private static void bumpFooter(Object screen) {
        try {
            Object footer = XposedHelpers.callMethod(screen, "findPreference", FOOTER_KEY);
            if (footer == null) return;
            XposedHelpers.callMethod(footer, "setOrder", Integer.valueOf(40));
            HookStatus.put("r6ui.footer", "order=40");
        } catch (Throwable t) {
            HookStatus.fail("ui-footer", t);
        }
    }

    static void refreshSummaries(Object screen, Context ctx) {
        if (screen == null || ctx == null) return;
        try {
            Object video = XposedHelpers.callMethod(screen, "findPreference", KEY_VIDEO);
            if (video != null) {
                XposedHelpers.callMethod(video, "setSummary", safeSummary(ctx, SettingsPages.SCREEN_VIDEO));
            }
        } catch (Throwable t) {
            HookStatus.fail("ui-summary-video", t);
        }
        HookStatus.put("r6ui.summary", "video=" + safeSummary(ctx, SettingsPages.SCREEN_VIDEO));
    }

    static String videoSummary(Context ctx) {
        int n = VideoRate.configuredCount();
        return n == 0 ? "全部跟随片源" : n + " 个已固定";
    }

    private static Class<?> findPreferenceClass(ClassLoader cl, String[] names) {
        for (String n : names) {
            try {
                return XposedHelpers.findClass(n, cl);
            } catch (Throwable t) {
                HookStatus.fail("ui-class-" + n.substring(n.lastIndexOf('.') + 1), t);
            }
        }
        return null;
    }

    // ------------------------------------------------------------- 对话框兜底

    /** 「视频应用帧率」兜底：视频列表 → 单应用 6 选 1。 */
    private static void showVideoDialog(final Context ctx, final ClassLoader cl,
                                        final Object row) {
        if (ctx == null) return;
        final int generation = APP_SCAN_GENERATION.incrementAndGet();
        final java.lang.ref.WeakReference<Context> contextRef =
                new java.lang.ref.WeakReference<Context>(ctx);
        final java.lang.ref.WeakReference<Object> rowRef =
                new java.lang.ref.WeakReference<Object>(row);
        final android.content.pm.PackageManager pm;
        try {
            pm = ctx.getPackageManager();
        } catch (Throwable t) {
            HookStatus.fail("ui-video-dialog", t);
            return;
        }
        APP_SCAN.execute(new Runnable() {
            @Override public void run() {
                final List<VideoApps.Entry> apps;
                try {
                    apps = VideoApps.videoCandidates(pm, null);
                } catch (Throwable t) {
                    HookStatus.fail("ui-video-scan", t);
                    return;
                }
                MAIN.post(new Runnable() {
                    @Override public void run() {
                        Context live = contextRef.get();
                        if (live == null || generation != APP_SCAN_GENERATION.get()) return;
                        showVideoCandidates(live, cl, rowRef.get(), apps);
                    }
                });
            }
        });
    }

    private static void showVideoCandidates(final Context ctx, final ClassLoader cl,
                                           final Object row, final List<VideoApps.Entry> apps) {
        try {
            if (apps.isEmpty()) {
                HookStatus.fail("ui-video-dialog", "no-video-apps");
                return;
            }
            final String[] pkgs = new String[apps.size()];
            CharSequence[] names = new CharSequence[apps.size()];
            for (int i = 0; i < apps.size(); i++) {
                pkgs[i] = apps.get(i).pkg;
                int hz = VideoRate.valueOf(apps.get(i).pkg);
                names[i] = apps.get(i).label + (hz > 0 ? "（固定 " + hz + " Hz）" : "");
            }
            Object builder = builder(ctx, cl);
            if (builder == null) {
                HookStatus.fail("ui-video-dialog", "no-builder");
                return;
            }
            XposedHelpers.callMethod(builder, "setTitle", TITLE_VIDEO);
            XposedHelpers.callMethod(builder, "setItems", names,
                    new ItemClickListener(cl, new IntSink() {
                        @Override public void onPick(int which) {
                            if (which >= 0 && which < pkgs.length) {
                                showVideoOptions(ctx, cl, pkgs[which], row);
                            }
                        }
                    }));
            callNegative(builder, "取消", cl);
            XposedHelpers.callMethod(builder, "show");
            HookStatus.put("r6ui.dialog", dialogKind());
        } catch (Throwable t) {
            HookStatus.fail("ui-video-dialog", t);
        }
    }

    /** 供 SettingsPages 的降级行复用（行控件不可用时用对话框设置帧率）。 */
    static void showVideoOptionsFor(Context ctx, ClassLoader cl, String pkg, Object row) {
        showVideoOptions(ctx, cl, pkg, row);
    }

    private static void showVideoOptions(final Context ctx, final ClassLoader cl,
                                         final String pkg, final Object row) {
        try {
            final int current = VideoRate.valueOf(pkg);
            List<Integer> values = new ArrayList<Integer>();
            List<String> labels = new ArrayList<String>();
            float panelMax = RateMap.panelMaxHz();
            boolean panel165 = RateMap.has165Panel();
            for (int i = 0; i < VALUES.length; i++) {
                int v = VALUES[i];
                if (v == 165 && (!panel165 || !Gear.uiTakeover())) continue;
                if (v != 0 && v != 30 && v > panelMax) continue;
                values.add(Integer.valueOf(v));
                labels.add(LABELS[i]);
            }
            int selected = current == VideoRate.UNCONFIGURED ? VideoRate.FOLLOW : current;
            int checked = -1;
            for (int i = 0; i < values.size(); i++) {
                if (values.get(i).intValue() == selected) { checked = i; break; }
            }
            String appLabel = appLabel(ctx, pkg);
            String title = appLabel + "（" + pkg + "）";
            if (checked < 0 && current > 0) {
                title = appLabel + "（" + pkg + "；当前："
                        + (current == 30 ? "最低 30 Hz" : "固定 " + current + " Hz")
                        + (current == 165 && !Gear.uiTakeover() ? "，需 165Hz 档生效" : "") + "）";
            }
            Object builder = builder(ctx, cl);
            if (builder == null) return;
            XposedHelpers.callMethod(builder, "setTitle", title);
            final List<Integer> fValues = values;
            XposedHelpers.callMethod(builder, "setSingleChoiceItems",
                    labels.toArray(new CharSequence[0]), Integer.valueOf(checked),
                    new SingleChoiceListener(cl, new IntSink() {
                        @Override public void onPick(int which) {
                            try {
                                if (which >= 0 && which < fValues.size()) {
                                    if (VideoRate.put(ctx, pkg, fValues.get(which).intValue())) {
                                        if (row != null && pkg.equals(XposedHelpers.callMethod(row, "getKey"))) {
                                            SettingsPages.setAssignment(row, fValues.get(which).intValue());
                                        }
                                        refreshSummary(screen(row), ctx);
                                    } else {
                                        android.widget.Toast.makeText(ctx, "保存失败，已保留原有设置",
                                                android.widget.Toast.LENGTH_SHORT).show();
                                    }
                                }
                            } catch (Throwable t) {
                                HookStatus.fail("ui-video-save", t);
                            }
                        }
                    }));
            callNegative(builder, "取消", cl);
            XposedHelpers.callMethod(builder, "show");
        } catch (Throwable t) {
            HookStatus.fail("ui-video-options", t);
        }
    }

    /** Show the system's installed application label; retain package as an unambiguous ID. */
    private static String appLabel(Context ctx, String pkg) {
        try {
            android.content.pm.ApplicationInfo info = ctx.getPackageManager()
                    .getApplicationInfo(pkg, android.content.pm.PackageManager.MATCH_ALL);
            CharSequence label = info.loadLabel(ctx.getPackageManager());
            if (label != null && label.length() > 0) return label.toString();
        } catch (Throwable t) {
            HookStatus.put("videoDialogLabel", "fallback:" + t.getClass().getSimpleName());
        }
        return pkg;
    }

    private static Object screen(Object row) {
        try {
            return row == null ? null : XposedHelpers.callMethod(row, "getParent");
        } catch (Throwable t) {
            return null;
        }
    }

    private static void refreshSummary(Object screen, Context ctx) {
        if (screen == null) return;
        try {
            Object video = XposedHelpers.callMethod(screen, "findPreference", KEY_VIDEO);
            if (video != null) XposedHelpers.callMethod(video, "setSummary", videoSummary(ctx));
        } catch (Throwable t) {
            HookStatus.fail("ui-summary", t);
        }
    }

    interface IntSink {
        void onPick(int which);
    }

    private static final class ItemClickListener implements DialogInterface.OnClickListener {
        private final IntSink sink;

        ItemClickListener(ClassLoader cl, IntSink sink) {
            this.sink = sink;
        }

        @Override public void onClick(DialogInterface dialog, int which) {
            try {
                sink.onPick(which);
            } catch (Throwable t) {
                HookStatus.fail("ui-item-click", t);
            }
        }
    }

    private static final class SingleChoiceListener implements DialogInterface.OnClickListener {
        private final IntSink sink;

        SingleChoiceListener(ClassLoader cl, IntSink sink) {
            this.sink = sink;
        }

        @Override public void onClick(DialogInterface dialog, int which) {
            try {
                sink.onPick(which);
            } catch (Throwable t) {
                HookStatus.fail("ui-single-choice", t);
            }
            dialog.dismiss();
        }
    }

    private static void callNegative(Object builder, String text, ClassLoader cl) {
        try {
            XposedHelpers.callMethod(builder, "setNegativeButton", text,
                    new DialogInterface.OnClickListener() {
                        @Override public void onClick(DialogInterface dialog, int which) {
                            dialog.dismiss();
                        }
                    });
        } catch (Throwable t) {
            HookStatus.fail("ui-dialog-negative", t);
        }
    }

    private static String dialogKind() {
        String kind = HookStatus.get("r6ui.builder");
        return kind == null || kind.isEmpty() || "null".equals(kind) ? "androidx" : "coui";
    }

    /** COUI Builder → androidx Builder 候选链；全部失败返回 null。 */
    static Object builder(Context ctx, ClassLoader cl) {
        String[] candidates = {
                "com.coui.appcompat.dialog.COUIDialog$Builder",
                "com.coui.appcompat.alertdialog.COUIAlertDialog$Builder",
                "androidx.appcompat.app.AlertDialog$Builder"
        };
        for (String cn : candidates) {
            try {
                Class<?> c = XposedHelpers.findClass(cn, cl);
                Constructor<?> ctor;
                try {
                    ctor = c.getConstructor(Context.class);
                } catch (NoSuchMethodException e) {
                    ctor = c.getConstructor(Context.class, int.class);
                    return ctor.newInstance(ctx, Integer.valueOf(0));
                }
                Object b = ctor.newInstance(ctx);
                HookStatus.put("r6ui.builder", shortName(cn));
                return b;
            } catch (Throwable t) {
                HookStatus.fail("ui-builder-" + shortName(cn), t);
            }
        }
        return null;
    }

    private static String shortName(String fqcn) {
        int i = fqcn.lastIndexOf('.');
        return i < 0 ? fqcn : fqcn.substring(i + 1);
    }
}
