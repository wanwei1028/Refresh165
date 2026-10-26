package com.refresh165;

import java.lang.reflect.Method;

/**
 * Control-center refresh rate is a display transition, not a change to the
 * foreground application's frame-rate request. The OEM picker exposes this
 * state separately from its candidate window on the supported PLK110 build.
 */
final class PanelRate {
    private static volatile String sSource = "unknown";

    private PanelRate() { }

    /**
     * Prefer the policy's live query (it checks both NotificationShade and
     * current focus); fall back to the picker's live query, then its cached
     * state only when the methods are unavailable.
     */
    static boolean expanded(Object policy, Object picker) {
        Boolean value = invokeBoolean(policy, "isPanelExpened");
        if (value != null) {
            sSource = "policy.isPanelExpened";
            publish(value.booleanValue());
            return value.booleanValue();
        }

        value = invokeBoolean(picker, "hasPanelExpened");
        if (value != null) {
            sSource = "picker.hasPanelExpened";
            publish(value.booleanValue());
            return value.booleanValue();
        }

        Object cached = Reflect.field(picker, "mIsPanelExpened");
        Object shade = Reflect.field(picker, "mNotificationShadeWin");
        if (cached instanceof Boolean && Boolean.TRUE.equals(cached) && shade != null) {
            Boolean drawn = invokeBoolean(shade, "isDrawn");
            boolean active = drawn == null || drawn.booleanValue();
            sSource = "picker.cached";
            publish(active);
            return active;
        }
        sSource = "unavailable";
        publish(false);
        return false;
    }

    /**
     * Choose only the final display mode while the shade is open. The window
     * candidate/vote is deliberately not changed: it can still belong to a
     * game whose native request must remain intact.
     */
    static int finalModeId(int currentModeId, float currentHz) {
        if (!Gear.takeover()) {
            HookStatus.stockPass("final-mode-panel");
            return currentModeId;
        }
        // A user-fixed video rate and active thermal intervention remain
        // authoritative even while the shade is open.
        if (!allowed() || VideoFixed.blocksGlobalPromotion()) {
            HookStatus.stockPass("final-mode-panel-guard");
            return currentModeId;
        }
        if (currentHz >= 164.5f) {
            HookStatus.stockPass("final-mode-panel-already-165");
            return currentModeId;
        }
        // R-5：与主路径一致 —— 优先 SF 编号（SfSpecs 解析），退 Display 编号；都没有则 skip
        int target = SfSpecs.modeId165Public();
        if (target <= 0) {
            HookStatus.put("panelModeSrc", "missing");
            HookStatus.stockPass("final-mode-panel");
            return currentModeId;
        }
        HookStatus.put("panelModeFrom", currentHz > 0
                ? String.valueOf(currentHz) : String.valueOf(currentModeId));
        HookStatus.put("panelModeTo", 165);
        HookStatus.applied("final-mode-panel");
        return target;
    }

    static boolean allowed() {
        // A panel transition may briefly force the panel to its top mode, so
        // only do it when thermal telemetry is positively available. Unknown
        // or failed thermal observation must leave the vendor path untouched.
        return Gear.takeover() && ThermalGuard.interceptAllowed();
    }

    private static Boolean invokeBoolean(Object target, String name) {
        if (target == null) return null;
        try {
            Method method = Sys.method(target.getClass(), name);
            if (method == null || method.getParameterTypes().length != 0) return null;
            Object value = method.invoke(target);
            return value instanceof Boolean ? (Boolean) value : null;
        } catch (Throwable t) {
            HookStatus.fail("panel-detect-" + name, t);
            return null;
        }
    }

    private static void publish(boolean expanded) {
        HookStatus.put("panelExpanded", expanded ? 1 : 0);
        HookStatus.put("panelDetect", sSource);
    }
}
