package com.refresh165;

import android.app.PendingIntent;
import android.content.ComponentName;
import android.content.Intent;
import android.provider.Settings;
import android.service.quicksettings.Tile;
import android.service.quicksettings.TileService;

/**
 * 快捷设置磁贴（B7，audit）：普通应用**没有** `WRITE_SECURE_SETTINGS`，
 * 因此**不写** Secure/Global，只做「读状态展示 + 点击打开设置页」。
 *
 * <p>为什么改：原实现点一下就 `Settings.Global.putInt/Secure.putInt`，在 3rd-party app 上必然
 * `SecurityException`；`tileAutoMode=1` 的自证也永远不会出现（假自证），同时把「档位自动切 7」
 * 这个危险动作暴露给一次误触。
 *
 * <p>现在：点击 = 打开「屏幕刷新率」设置页（`SubSettings` + `show_fragment`），
 * 打不开只 Toast；磁贴状态只反映当前档位/开关（只读）。
 */
public class Force165Tile extends TileService {
    static final String KEY = "refresh165_force";
    private static final String MODE_KEY = "oplus_customize_screen_refresh_rate";
    private static final int MODE_165 = 7;

    private static final String SETTINGS_PKG = "com.android.settings";
    private static final String SUB_SETTINGS = "com.android.settings.SubSettings";
    private static final String FRAGMENT =
            "com.oplus.settings.feature.display.ScreenRefreshRateFragment";

    @Override
    public void onStartListening() {
        super.onStartListening();
        refresh();
    }

    @Override
    public void onClick() {
        openSettings();
        refresh();
    }

    /** 只读：打开原生「屏幕刷新率」页，失败仅 Toast（不写任何设置）。 */
    private void openSettings() {
        try {
            Intent intent = new Intent();
            intent.setComponent(new ComponentName(SETTINGS_PKG, SUB_SETTINGS));
            intent.putExtra(":settings:show_fragment", FRAGMENT);
            intent.putExtra(":settings:show_fragment_title", "屏幕刷新率");
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            PendingIntent pending = PendingIntent.getActivity(this, 0, intent,
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
            startActivityAndCollapse(pending);
            HookStatus.put("tileOpen", "ok");
        } catch (Throwable t) {
            HookStatus.fail("tile-open", t);
            HookStatus.put("tileOpen", "failed");
            android.widget.Toast.makeText(this, "打不开屏幕刷新率设置，请手动进入设置",
                    android.widget.Toast.LENGTH_SHORT).show();
        }
    }

    private boolean isOn() {
        try {
            return Settings.Global.getInt(getContentResolver(), KEY, 0) == 1;
        } catch (Throwable t) {
            return false;
        }
    }

    private boolean isGear165() {
        try {
            return Settings.Secure.getInt(getContentResolver(), MODE_KEY, 0) == MODE_165;
        } catch (Throwable t) {
            return false;
        }
    }

    private void refresh() {
        Tile tile = getQsTile();
        if (tile == null) return;
        boolean on = isOn();
        boolean gear = isGear165();
        tile.setState(on && gear ? Tile.STATE_ACTIVE : Tile.STATE_INACTIVE);
        tile.setLabel(!gear ? "165Hz（非 165 档）" : (on ? "165Hz 常驻" : "165Hz LTPO"));
        tile.setSubtitle("点击打开屏幕刷新率设置");
        tile.updateTile();
    }
}
