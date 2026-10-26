package com.refresh165;

import android.content.ContentResolver;
import android.content.Context;
import android.database.ContentObserver;
import android.os.Handler;
import android.os.SystemClock;
import android.provider.Settings;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * R6 存储（DESIGN §12.3）：`Settings.Secure.refresh165_video_policy`，settings 写、system_server 只读。
 *
 * <p>格式 `v1|pkg=hz|pkg=hz`，`hz∈{0,30,60,90,120,165}`，`0` = 显式跟随片源。
 * 键不存在 = 全部未配置 = 跟随片源（与 v1.0.5 逐帧等价）。
 */
// 已知限制（CODE-REVIEW §5.1 文档项）：`Settings.Secure` 按进程用户读写，system_server 侧读到的是
// user 0；次用户 / 工作资料里配置的固定帧率不会生效。多用户各自隔离需改 ForUser(...) 变体（@hide），
// 发布说明里必须写明「仅主用户生效」。
final class VideoRate {
    static final String KEY = "refresh165_video_policy";
    static final int UNCONFIGURED = -1;
    static final int FOLLOW = 0;
    static final int HZ_MIN = 30;
    static final int HZ_MAX = 165;
    private static final String PREFIX = "v1|";
    private static final long TTL_MS = 400L;

    private static boolean validHz(int hz) {
        return hz == FOLLOW || hz == 30 || hz == 60 || hz == 90 || hz == 120 || hz == 165;
    }

    private static volatile Map<String, Integer> sMap = Collections.emptyMap();
    private static volatile long sAt;
    private static volatile String sStore = "none";
    private static volatile int sBadSchema;
    private static volatile int sExplicitFollow;
    private static volatile boolean sObserverInstalled;

    private VideoRate() { }

    // ---------------------------------------------------------------- 读

    static int hzOf(String pkg) {
        if (pkg == null || pkg.isEmpty()) return UNCONFIGURED;
        Map<String, Integer> m = snapshot();
        Integer v = m.get(pkg);
        if (v == null) return UNCONFIGURED;
        int hz = v.intValue();
        if (hz == FOLLOW) {
            sExplicitFollow++;
            HookStatus.put("videoExplicitFollow", sExplicitFollow);
            return FOLLOW;
        }
        return hz;
    }

    private static Map<String, Integer> snapshot() {
        long now = SystemClock.elapsedRealtime();
        Map<String, Integer> m = sMap;
        if (now - sAt < TTL_MS && sAt != 0) return m;
        ContentResolver cr = Sys.cr();
        if (cr == null) {
            sStore = "no-context";
            return m;
        }
        String secure = null;
        String system = null;
        try {
            secure = Settings.Secure.getString(cr, KEY);
        } catch (Throwable t) {
            HookStatus.fail("video-read-secure", t);
        }
        try {
            system = Settings.System.getString(cr, KEY);
        } catch (Throwable t) {
            HookStatus.fail("video-read-system", t);
        }
        String raw;
        if (secure != null && system != null) {
            raw = secure;
            sStore = "both";
            HookStatus.applied("video-store-both");
        } else if (secure != null) {
            raw = secure;
            sStore = "secure";
        } else if (system != null) {
            raw = system;
            sStore = "system-fallback";
        } else {
            raw = null;
            sStore = "empty";
        }
        Map<String, Integer> parsed = parse(raw);
        sMap = parsed;
        sAt = now;
        HookStatus.put("videoStore", sStore);
        HookStatus.put("videoEntries", parsed.size());
        return parsed;
    }

    static Map<String, Integer> parse(String raw) {
        Map<String, Integer> out = new LinkedHashMap<String, Integer>();
        if (raw == null || raw.trim().isEmpty()) return Collections.unmodifiableMap(out);
        String s = raw.trim();
        // C：混合 schema 时**不再整体丢弃** —— 保留可解析条目，丢弃坏条目并计数
        boolean prefixed = s.startsWith(PREFIX);
        String body = prefixed ? s.substring(PREFIX.length()) : s;
        int dropped = 0;
        if (body.isEmpty()) return Collections.unmodifiableMap(out);
        for (String part : body.split("\\|")) {
            int eq = part.indexOf('=');
            if (eq <= 0 || eq == part.length() - 1) {
                if (!part.trim().isEmpty()) dropped++;
                continue;
            }
            String pkg = part.substring(0, eq).trim();
            String val = part.substring(eq + 1).trim();
            if (!validPkg(pkg)) { dropped++; continue; }
            int hz;
            try {
                hz = Integer.parseInt(val);
            } catch (NumberFormatException e) {
                dropped++;
                continue;
            }
            if (!validHz(hz)) { dropped++; continue; }
            out.put(pkg, Integer.valueOf(hz));
        }
        if (!prefixed || dropped > 0) {
            sBadSchema++;
            HookStatus.put("videoStore-badschema", sBadSchema);
            HookStatus.put("videoSchema", "mixed:" + dropped + (prefixed ? "" : "/no-prefix"));
        } else {
            HookStatus.put("videoSchema", "ok");
        }
        HookStatus.put("videoEntries", out.size());
        return Collections.unmodifiableMap(out);
    }

    private static boolean validPkg(String pkg) {
        if (pkg.isEmpty() || pkg.length() > 128) return false;
        for (int i = 0; i < pkg.length(); i++) {
            char c = pkg.charAt(i);
            boolean ok = (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z')
                    || (c >= '0' && c <= '9') || c == '.' || c == '_';
            if (!ok) return false;
        }
        return true;
    }

    static String format(Map<String, Integer> map) {
        StringBuilder b = new StringBuilder(PREFIX);
        boolean first = true;
        for (Map.Entry<String, Integer> e : map.entrySet()) {
            if (!validPkg(e.getKey()) || e.getValue() == null
                    || !validHz(e.getValue().intValue())) continue;
            if (!first) b.append('|');
            b.append(e.getKey()).append('=').append(e.getValue().intValue());
            first = false;
        }
        return b.toString();
    }

    // ---------------------------------------------------------------- 写（仅 settings 进程）

    /** J：异步入口 —— binder 写放后台，完成后回主线程刷 summary；同步 path 保留给非 UI 调用方。 */
    static void putAsync(final Context ctx, final String pkg, final int hz, final Runnable after) {
        HookStatus.put("videoWrite", "async");
        final Context app = ctx == null ? null : ctx.getApplicationContext();
        Thread t = new Thread(new Runnable() {
            @Override public void run() {
                final boolean ok = put(app, pkg, hz);
                Sys.main().post(new Runnable() {
                    @Override public void run() {
                        HookStatus.put("videoWriteResult", ok ? "ok" : "failed");
                        if (after != null) after.run();
                    }
                });
            }
        }, "refresh165-video-write");
        t.setDaemon(true);
        t.start();
    }

    static synchronized boolean put(Context ctx, String pkg, int hz) {
        if (ctx == null || pkg == null || !validPkg(pkg) || !validHz(hz)) return false;
        Map<String, Integer> next = new LinkedHashMap<String, Integer>(snapshot());
        if (hz == FOLLOW) next.put(pkg, Integer.valueOf(FOLLOW));
        else next.put(pkg, Integer.valueOf(hz));
        String value = format(pruneUninstalled(ctx, next));
        ContentResolver cr = ctx.getContentResolver();
        boolean secureOk = false;
        try {
            secureOk = Settings.Secure.putString(cr, KEY, value);
        } catch (Throwable t) {
            HookStatus.fail("video-write-secure", t);
        }
        if (secureOk) {
            // P1-7：Secure 写成功必须删掉 System 同键，否则清空 Secure 后旧值会复活
            boolean systemDeleted = false;
            try {
                systemDeleted = Settings.System.putString(cr, KEY, null);
                if (!systemDeleted) {
                    HookStatus.fail("video-delete-system", "write-rejected");
                }
            } catch (Throwable t) {
                HookStatus.fail("video-delete-system", t);
            }
            sStore = systemDeleted ? "secure" : "secure-stale-system";
        } else {
            String existingSecure;
            try {
                existingSecure = Settings.Secure.getString(cr, KEY);
            } catch (Throwable t) {
                HookStatus.fail("video-check-secure", t);
                sAt = 0;
                return false;
            }
            if (existingSecure != null) {
                HookStatus.fail("video-write-secure", "rejected-with-existing-secure-value");
                sAt = 0;
                return false;
            }
            try {
                if (!Settings.System.putString(cr, KEY, value)) {
                    HookStatus.fail("video-write-system", "write-rejected");
                    sAt = 0;
                    return false;
                }
                sStore = "system-fallback";
                HookStatus.applied("video-store-fallback");
            } catch (Throwable t) {
                HookStatus.fail("video-write-system", t);
                sAt = 0;
                return false;
            }
        }
        Map<String, Integer> parsed = parse(value);
        sMap = parsed;
        sAt = SystemClock.elapsedRealtime();
        HookStatus.put("videoStore", sStore);
        HookStatus.put("videoEntries", parsed.size());
        return true;
    }

    /** 下次写入时顺带清理未安装项（DESIGN §12.2.2）。 */
    private static Map<String, Integer> pruneUninstalled(Context ctx, Map<String, Integer> in) {
        try {
            android.content.pm.PackageManager pm = ctx.getPackageManager();
            Map<String, Integer> out = new LinkedHashMap<String, Integer>();
            int orphan = 0;
            for (Map.Entry<String, Integer> e : in.entrySet()) {
                if (VideoApps.installed(pm, e.getKey())) out.put(e.getKey(), e.getValue());
                else orphan++;
            }
            if (orphan > 0) HookStatus.put("videoOrphan", orphan);
            return out;
        } catch (Throwable t) {
            HookStatus.fail("video-prune", t);
            return in;
        }
    }

    static String summary(Context ctx) {
        Map<String, Integer> m = snapshot();
        if ("no-context".equals(sStore)) return "暂不可用";
        int fixed = 0;
        int needsGear = 0;
        for (Map.Entry<String, Integer> e : m.entrySet()) {
            if (e.getValue().intValue() == FOLLOW) continue;
            if (ctx != null && !VideoApps.installed(ctx.getPackageManager(), e.getKey())) continue;
            fixed++;
            if (e.getValue().intValue() == 165 && !Gear.uiTakeover()) needsGear++;
        }
        String base = fixed == 0 ? "全部跟随片源" : fixed + " 个应用已固定";
        if (needsGear > 0) base = base + "（165 需在 165Hz 档生效）";
        return base;
    }

    static int configuredCount() {
        int n = 0;
        for (Integer v : snapshot().values()) if (v.intValue() != FOLLOW) n++;
        return n;
    }

    static Map<String, Integer> all() {
        return new LinkedHashMap<String, Integer>(snapshot());
    }

    static int valueOf(String pkg) {
        Integer v = snapshot().get(pkg);
        return v == null ? UNCONFIGURED : v.intValue();
    }

    /** 删除整键（「全部恢复跟随片源」在条目 >50 时用，免超长写入）。 */
    static synchronized boolean clear(Context ctx) {
        if (ctx == null) return false;
        android.content.ContentResolver cr = ctx.getContentResolver();
        final String secureBefore;
        final String systemBefore;
        try {
            // Clearing spans the preferred Secure store and the legacy System
            // fallback. Snapshot both first so a partial delete cannot expose
            // a stale fallback value or silently discard the previous policy.
            secureBefore = Settings.Secure.getString(cr, KEY);
            systemBefore = Settings.System.getString(cr, KEY);
        } catch (Throwable t) {
            HookStatus.fail("video-clear-snapshot", t);
            return false;
        }
        boolean secureOk = false;
        boolean systemOk = false;
        try {
            secureOk = Settings.Secure.putString(cr, KEY, null);
        } catch (Throwable t) {
            HookStatus.fail("video-clear-secure", t);
        }
        try {
            systemOk = Settings.System.putString(cr, KEY, null);
        } catch (Throwable t) {
            HookStatus.fail("video-clear-system", t);
        }
        if (!secureOk || !systemOk) {
            HookStatus.fail("video-clear", "delete-rejected secure=" + secureOk
                    + " system=" + systemOk);
            sAt = 0;
            boolean rollbackOk = true;
            if (secureOk && secureBefore != null) {
                try {
                    rollbackOk &= Settings.Secure.putString(cr, KEY, secureBefore);
                } catch (Throwable t) {
                    rollbackOk = false;
                    HookStatus.fail("video-clear-rollback-secure", t);
                }
            }
            if (systemOk && systemBefore != null) {
                try {
                    rollbackOk &= Settings.System.putString(cr, KEY, systemBefore);
                } catch (Throwable t) {
                    rollbackOk = false;
                    HookStatus.fail("video-clear-rollback-system", t);
                }
            }
            HookStatus.put("videoClearRollback", rollbackOk ? "restored" : "incomplete");
            if (!rollbackOk) HookStatus.fail("video-clear-rollback", "restore-incomplete");
            return false;
        }
        sMap = Collections.emptyMap();
        sAt = SystemClock.elapsedRealtime();
        sStore = "empty";
        HookStatus.applied("video-clear");
        return true;
    }

    static void invalidate() {
        sAt = 0;
    }

    /**
     * 两进程各注册一次（DESIGN §12.3.3）。
     * @return false = 注册失败（调用方会重试；失败时**不置已安装标志**）
     */
    static boolean installObserver(Context ctx) {
        if (sObserverInstalled) return true;
        if (ctx == null) return false;
        try {
            ContentResolver cr = ctx.getContentResolver();
            ContentObserver obs = new ContentObserver(new Handler(android.os.Looper.getMainLooper())) {
                @Override public void onChange(boolean selfChange) {
                    sAt = 0;
                }
            };
            cr.registerContentObserver(Settings.Secure.getUriFor(KEY), false, obs);
            try {
                cr.registerContentObserver(Settings.System.getUriFor(KEY), false, obs);
            } catch (Throwable t) {
                // 兜底键的观察者可缺（读路径本身有 TTL 兜底）
                HookStatus.fail("video-observer-system", t);
            }
            sObserverInstalled = true;
            HookStatus.installed("video-observer");
            return true;
        } catch (Throwable t) {
            sObserverInstalled = false;
            HookStatus.fail("video-observer", t);
            return false;
        }
    }

    static String statusLine() {
        return "videoStore=" + sStore + " entries=" + configuredCount() + " badschema=" + sBadSchema;
    }
}
