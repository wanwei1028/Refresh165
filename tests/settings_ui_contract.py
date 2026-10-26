#!/usr/bin/env python3
"""Regression contracts for Refresh165's ColorOS refresh-rate settings UI."""
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
HOOK = (ROOT / "module/src/com/refresh165/Hook.java").read_text(encoding="utf-8")
VIDEO_UI = (ROOT / "module/src/com/refresh165/VideoRateUi.java").read_text(encoding="utf-8")
PAGES = (ROOT / "module/src/com/refresh165/SettingsPages.java").read_text(encoding="utf-8")
SCOPE = (ROOT / "module/assets/META-INF/xposed/scope.list").read_text(encoding="utf-8").splitlines()


def check(condition, name):
    if not condition:
        raise AssertionError(name)
    print("PASS", name)


# Keep system's native "high refresh supported apps" row; module config belongs
# to the separate native settings card, never the 60/120 gear radio group.
check('"screen_refresh_rate_settings"' in VIDEO_UI, "video-uses-native-settings-card")
check('"screen_refresh_rate_settings"' in HOOK, "165-lock-switch-uses-native-settings-card")
check('"refresh_rate_category"' not in HOOK[HOOK.index("private static void addLockSwitch"):],
      "165-lock-switch-not-in-60-120-radio-group")
check("ensureSettingsParentVisible(screen)" in VIDEO_UI
      and '"setVisible", Boolean.TRUE' in VIDEO_UI,
      "module-settings-card-is-revealed-when-oem-hides-it")
check('"screen_refresh_high"' in VIDEO_UI and "removeLegacyEntry(screen)" in VIDEO_UI,
      "native-supported-app-entry-is-preserved-without-duplicate")

# Make entry injection resilient to OEM lifecycle rebuilding/removing rows.
resume = HOOK[HOOK.index('XposedBridge.hookAllMethods(frag, "onResume"'):]
check("VideoRateUi.addRows" in resume,
          "entries-reasserted-after-native-onResume-rebuild")
preflight = PAGES[PAGES.index("private static String preflight(Activity activity)"):]
preflight = preflight[:preflight.index("// --------------------------------------------------------------- 壳内分派")]
check("if (!sNativeInitHookOk)" in preflight
      and "sNativeAppsReady" not in preflight
      and "Video uses the native page shell but not the native app-list data hooks." in preflight,
      "subpage-preflight-independent-of-unrelated-native-app-list-hook")
check('"shell-hook-incomplete"' in PAGES and '"native-hooks-incomplete"' not in PAGES,
      "video-page-not-disabled-by-unrelated-native-list-hook")

# Preserve the agreed LSPosed scope; SystemUI must not be added.
check(set(SCOPE) == {"android", "system", "com.android.settings"},
      "scope-is-framework-system-settings-only")
check(not any("systemui" in x.lower() for x in SCOPE), "systemui-not-in-scope")

print("PASS total=10")
