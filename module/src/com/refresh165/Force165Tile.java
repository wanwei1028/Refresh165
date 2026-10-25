package com.refresh165;

import android.provider.Settings;
import android.service.quicksettings.Tile;
import android.service.quicksettings.TileService;

/**
 * 快捷设置磁贴：一键在「LTPO（静止可到 1Hz）」与「强制全局 165Hz（关闭 LTPO）」之间切换。
 * 状态写入 Settings.Global.refresh165_force，由 Hook 侧读取后决定 reserve 表。
 */
public class Force165Tile extends TileService {
    static final String KEY = "refresh165_force";

    @Override
    public void onStartListening() {
        super.onStartListening();
        refresh();
    }

    @Override
    public void onClick() {
        boolean on = isOn();
        try {
            Settings.Global.putInt(getContentResolver(), KEY, on ? 0 : 1);
        } catch (Throwable ignored) {
            return;
        }
        refresh();
    }

    private boolean isOn() {
        try {
            return Settings.Global.getInt(getContentResolver(), KEY, 0) == 1;
        } catch (Throwable t) {
            return false;
        }
    }

    private void refresh() {
        Tile tile = getQsTile();
        if (tile == null) {
            return;
        }
        tile.setState(isOn() ? Tile.STATE_ACTIVE : Tile.STATE_INACTIVE);
        tile.setLabel(isOn() ? "165Hz 锁定" : "165Hz LTPO");
        tile.updateTile();
    }
}
