package com.refresh165;

import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import java.text.Collator;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;

/**
 * 应用枚举（settings 进程用来渲染两页）：
 *
 * <ul>
 *   <li><b>apps165 页</b>：{@link #candidates(PackageManager, boolean)} with {@code includeAll=true}
 *       —— 全部「可启动」应用（避免系统组件），按名称排序、包名去重；</li>
 *   <li><b>视频页</b>：{@link #videoCandidates(PackageManager, StringBuilder)}
 *       —— 系统视频分类、明确的媒体播放 Intent、以及已安装应用的本地化名称语义。
 *       不把“能接收视频文件的分享/导入目标”误认成视频应用。</li>
 * </ul>
 *
 * <p>注意：`ApplicationInfo.category` 的默认值是 **-1**，必须用**相等**比较；
 * 早期用 `(category & CATEGORY_VIDEO) != 0` 会让 500+ 个应用误命中（实机 cat506）。
 */
final class VideoApps {
    static final class Entry {
        final String pkg;
        final String label;
        final String source;

        Entry(String pkg, String label, String source) {
            this.pkg = pkg;
            this.label = label;
            this.source = source;
        }
    }

    private VideoApps() { }

    // ---------------------------------------------------------------- 全量（apps165 页）

    static List<Entry> candidates(PackageManager pm, boolean includeAll) {
        if (!includeAll) return videoCandidates(pm, null);
        LinkedHashMap<String, Entry> out = new LinkedHashMap<String, Entry>();
        if (pm == null) return new ArrayList<Entry>();
        try {
            List<ApplicationInfo> all = pm.getInstalledApplications(PackageManager.MATCH_ALL);
            for (ApplicationInfo ai : all) {
                if (ai == null) continue;
                if (!launchable(pm, ai.packageName)) continue;
                add(out, pm, ai, "all");
            }
        } catch (Throwable t) {
            HookStatus.fail("video-apps-all", t);
        }
        List<Entry> list = new ArrayList<Entry>(out.values());
        sort(list);
        HookStatus.put("videoApps", list.size() + "/all");
        return list;
    }

    // ---------------------------------------------------------------- 视频（视频页）

    /**
     * @param proof 可选，输出系统查询来源自证串（`mime=… cat=…`）
     */
    static List<Entry> videoCandidates(PackageManager pm, StringBuilder proof) {
        LinkedHashMap<String, Entry> out = new LinkedHashMap<String, Entry>();
        LinkedHashSet<String> mime = new LinkedHashSet<String>();
        LinkedHashSet<String> cat = new LinkedHashSet<String>();
        LinkedHashSet<String> named = new LinkedHashSet<String>();
        LinkedHashSet<String> rejectedHandlers = new LinkedHashSet<String>();
        if (pm == null) return new ArrayList<Entry>();

        // ① MIME_VIDEO
        try {
            Intent i = new Intent(Intent.ACTION_VIEW).addCategory(Intent.CATEGORY_DEFAULT)
                    .setType("video/*");
            List<ResolveInfo> ris = pm.queryIntentActivities(i, PackageManager.MATCH_ALL);
            for (ResolveInfo ri : ris) {
                if (ri == null || ri.activityInfo == null
                        || ri.activityInfo.applicationInfo == null) continue;
                String pkg = ri.activityInfo.applicationInfo.packageName;
                if (isPlaybackActivity(ri.activityInfo.name)) mime.add(pkg);
                else rejectedHandlers.add(pkg);
            }
        } catch (Throwable t) {
            HookStatus.fail("video-apps-mime", t);
        }
        // ② CATEGORY_VIDEO（相等比较！）
        try {
            List<ApplicationInfo> all = pm.getInstalledApplications(PackageManager.MATCH_ALL);
            for (ApplicationInfo ai : all) {
                if (ai != null && ai.category == ApplicationInfo.CATEGORY_VIDEO) {
                    cat.add(ai.packageName);
                }
            }
        } catch (Throwable t) {
            HookStatus.fail("video-apps-category", t);
        }
        // 2（lead 裁定）：**删除 label 关键字兜底** —— 它把「抖音商城」这类应用也收了进来。
        // 候选规则收紧为 MIME(video/*) ∪ CATEGORY_VIDEO ∪ SEED(精确/前缀) ∪ Secure 扩展。
        int builtinHits = collectBuiltinSeeds(pm, named);

        // C：额外种子 `Settings.Secure.refresh165_video_seeds`（逗号分隔；`pkg` 精确 / `prefix*` 前缀）
        LinkedHashSet<String> extraSeeds = new LinkedHashSet<String>();
        LinkedHashSet<String> excluded = new LinkedHashSet<String>();
        collectExtraSeeds(pm, extraSeeds);
        LinkedHashSet<String> pool = new LinkedHashSet<String>();
        pool.addAll(extraSeeds);
        pool.addAll(mime);
        pool.addAll(cat);
        pool.addAll(named);
        for (String pkg : pool) {
            if (pkg == null || pkg.isEmpty()) continue;
            if (isExcluded(pkg)) {
                excluded.add(pkg);
                continue;
            }
            if (!launchable(pm, pkg)) continue;
            add(out, pm, info(pm, pkg), sourceOf(pkg, mime, cat, named));
        }
        HookStatus.put("videoSeedsExtra", extraSeeds.isEmpty() ? "none" : join(extraSeeds));
        HookStatus.put("videoExcluded", excluded.isEmpty() ? "none" : join(excluded));
        List<Entry> list = new ArrayList<Entry>(out.values());
        sort(list);
        if (proof != null) {
            proof.append("builtin=").append(builtinHits)
                    .append(" seeds=").append(join(extraSeeds))
                    .append(" excluded=").append(join(excluded))
                    .append(" mime=").append(join(mime))
                    .append(" cat=").append(join(cat))
                    .append(" ignored=").append(join(rejectedHandlers));
        }
        HookStatus.put("videoApps", list.size() + "/video");
        return list;
    }

    /** 内置 SEED（精确包名）：主流视频/短视频，存在才计入。 */
    static final String[] SEED_EXACT = {
            "tv.danmaku.bili", "tv.danmaku.bilibili",
            "com.qiyi.video", "com.qiyi.video.pad", "com.qiyi.video.lite",
            "com.youku.phone", "com.youku.phone.pad", "com.tudou.android",
            "com.tencent.qqlive", "com.tencent.qqlivepad", "com.tencent.weishi",
            "com.ss.android.ugc.aweme", "com.ss.android.ugc.aweme.lite",
            "com.ss.android.ugc.live", "com.ss.android.article.video",
            "com.hunantv.imgo.activity", "com.hunantv.imgo.activity.pad",
            "com.sohu.sohuvideo", "com.smile.gifmaker", "com.kuaishou.nebula",
            "com.cmcc.cmvideo", "com.pplive.androidphone", "com.letv.android.client",
            "com.cctv.cctv5", "com.cctv.yangshipin", "com.chunyu.video",
            "com.mxtech.videoplayer.ad", "org.videolan.vlc",
            "com.coloros.video", "com.heytap.yoli",
            "com.google.android.youtube", "com.netflix.mediaclient",
            "com.amazon.avod.thirdpartyclient", "com.disney.disneyplus",
            "com.xingin.xhs"
    };

    /** 内置 SEED（前缀族：概念版/HD/多包名）。 */
    static final String[] SEED_PREFIX = {
            "com.bilibili.", "com.miguvideo.", "com.acfun", "com.cctv.",
            "com.pplive.", "com.letv."
    };

    /** 内置 SEED 命中（存在才算），返回命中数。 */
    private static int collectBuiltinSeeds(PackageManager pm, java.util.Set<String> out) {
        java.util.List<String> installed = new ArrayList<String>();
        try {
            for (ApplicationInfo ai : pm.getInstalledApplications(PackageManager.MATCH_ALL)) {
                if (ai != null && ai.packageName != null) installed.add(ai.packageName);
            }
        } catch (Throwable t) {
            HookStatus.fail("video-seeds-installed", t);
            return 0;
        }
        int hits = 0;
        for (String p : SEED_EXACT) {
            if (installed.contains(p) && out.add(p)) hits++;
        }
        for (String prefix : SEED_PREFIX) {
            for (String pkg : installed) {
                if (pkg.startsWith(prefix) && out.add(pkg)) hits++;
            }
        }
        HookStatus.put("videoSeedsBuiltin", hits);
        return hits;
    }

    /** 内置 EXCLUDE：浏览器/搜索/文件管理/短信/设置 + 商城/相册/直播lite 非视频变体。 */
    private static final String[] EXCLUDE_KEYWORDS = {
            "browser", "search", "quark", "filemanager", "mms", "settings", "chrome",
            "mall", "photopicker"
    };

    /** 精确排除：抖音商城、相册、直播 lite 等非视频入口。 */
    private static final String[] EXCLUDE_EXACT = {
            "com.ss.android.ugc.aweme.mall",
            "com.ss.android.ugc.livelite",
            "com.coloros.album",
            "com.oplus.gallery"
    };

    static boolean isExcluded(String pkg) {
        if (pkg == null || pkg.isEmpty()) return true;
        for (String e : EXCLUDE_EXACT) {
            if (e.equals(pkg)) return true;
        }
        String lower = pkg.toLowerCase(java.util.Locale.ROOT);
        for (String k : EXCLUDE_KEYWORDS) {
            if (lower.contains(k)) return true;
        }
        return false;
    }

    /** 额外种子：`refresh165_video_seeds`（逗号分隔，`pkg` 或 `prefix*`）。 */
    private static void collectExtraSeeds(PackageManager pm, java.util.Set<String> out) {
        android.content.ContentResolver cr = Sys.cr();
        if (cr == null) return;
        String raw = null;
        try {
            raw = android.provider.Settings.Secure.getString(
                    cr, "refresh165_video_seeds");
        } catch (Throwable t) {
            HookStatus.fail("video-seeds-extra", t);
            return;
        }
        if (raw == null || raw.trim().isEmpty()) return;
        java.util.List<String> installed = new ArrayList<String>();
        try {
            for (ApplicationInfo ai : pm.getInstalledApplications(PackageManager.MATCH_ALL)) {
                if (ai != null && ai.packageName != null) installed.add(ai.packageName);
            }
        } catch (Throwable t) {
            HookStatus.fail("video-seeds-installed", t);
            return;
        }
        for (String part : raw.split(",")) {
            String p = part.trim();
            if (p.isEmpty()) continue;
            if (p.endsWith("*")) {
                String prefix = p.substring(0, p.length() - 1);
                if (prefix.isEmpty()) continue;
                for (String pkg : installed) {
                    if (pkg.startsWith(prefix)) out.add(pkg);
                }
            } else if (installed.contains(p)) {
                out.add(p);
            }
        }
    }

    private static String sourceOf(String pkg, Collection<String> mime,
                                   Collection<String> cat, Collection<String> named) {
        if (mime.contains(pkg)) return "mime";
        if (cat.contains(pkg)) return "category";
        if (named.contains(pkg)) return "label";
        return "other";
    }

    /** Only accept a resolver result when its activity is explicitly media/playback code. */
    private static boolean isPlaybackActivity(String name) {
        if (name == null || name.isEmpty()) return false;
        String n = name.toLowerCase(Locale.ROOT);
        if (containsAny(n, "share", "import", "upload", "edit", "compress", "transfer",
                "jump", "chooser", "file", "local")) return false;
        return containsAny(n, "player", "playback", "movie", "videoplayer", "videoactivity",
                "video_play", "videoplay", "mediaactivity", "media_player");
    }

    /**
     * Localized labels catch UGC/streaming apps which intentionally do not
     * register for external video/* playback. Terms describe product type,
     * not package identities; the candidates still come from installed apps.
     */
    private static boolean isVideoLabel(String label) {
        if (label == null || label.isEmpty()) return false;
        String n = label.toLowerCase(Locale.ROOT);
        return containsAny(n,
                "视频", "影视", "短剧", "红果", "抖音", "哔哩", "番剧", "电影", "直播", "快手",
                "video", "movie", "film", "player", "stream", "bilibili", "douyin",
                "tiktok", "youtube", "netflix", "twitch", "hulu", "disney", "prime video");
    }

    private static boolean containsAny(String value, String... terms) {
        for (String term : terms) if (value.contains(term)) return true;
        return false;
    }

    private static String labelOf(PackageManager pm, ApplicationInfo ai) {
        try {
            CharSequence label = ai.loadLabel(pm);
            return label == null ? "" : label.toString();
        } catch (Throwable t) {
            return "";
        }
    }

    // ---------------------------------------------------------------- 工具


    private static void add(LinkedHashMap<String, Entry> out, PackageManager pm,
                            ApplicationInfo ai, String source) {
        if (ai == null || ai.packageName == null) return;
        if (out.containsKey(ai.packageName)) return;
        String label = labelOf(pm, ai);
        if (label.isEmpty()) label = ai.packageName;
        out.put(ai.packageName, new Entry(ai.packageName, label, source));
    }

    private static ApplicationInfo info(PackageManager pm, String pkg) {
        try {
            return pm.getApplicationInfo(pkg, PackageManager.MATCH_ALL);
        } catch (Throwable t) {
            return null;
        }
    }

    static boolean installed(PackageManager pm, String pkg) {
        if (pm == null || pkg == null || pkg.isEmpty()) return false;
        try {
            pm.getApplicationInfo(pkg, PackageManager.MATCH_ALL);
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    private static boolean launchable(PackageManager pm, String pkg) {
        if (pm == null || pkg == null || pkg.isEmpty()) return false;
        try {
            return pm.getLaunchIntentForPackage(pkg) != null;
        } catch (Throwable t) {
            return false;
        }
    }

    private static void sort(List<Entry> list) {
        final Collator collator = Collator.getInstance(Locale.CHINA);
        try {
            Collections.sort(list, new Comparator<Entry>() {
                @Override public int compare(Entry a, Entry b) {
                    int c = collator.compare(a.label, b.label);
                    return c != 0 ? c : a.pkg.compareTo(b.pkg);
                }
            });
        } catch (Throwable t) {
            HookStatus.fail("video-apps-sort", t);
        }
    }

    private static String join(Collection<String> set) {
        if (set == null || set.isEmpty()) return "none";
        StringBuilder b = new StringBuilder();
        int i = 0;
        for (String s : set) {
            if (i++ >= 8) {
                b.append(",..");
                break;
            }
            if (b.length() > 0) b.append(',');
            b.append(s);
        }
        return b.toString();
    }
}
