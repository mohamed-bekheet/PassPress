package com.passpress.bleKeyboard;

import android.os.Build;
import android.service.quicksettings.Tile;
import android.service.quicksettings.TileService;
import android.widget.Toast;
import android.content.SharedPreferences;
import android.content.Context;
import android.content.Intent;

public class PassPressTileService extends TileService {

    @Override
    public void onStartListening() {
        super.onStartListening();
        updateTileState();
    }

    @Override
    public void onClick() {
        super.onClick();
        Intent intent = new Intent(this, QuickMenuActivity.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        startActivityAndCollapse(intent);
        updateTileState();
    }

    private void updateTileState() {
        Tile tile = getQsTile();
        if (tile != null) {
            HidKeyboardService svc = HidKeyboardService.getInstance();
            if (svc != null && svc.getConnectedDevice() != null) {
                tile.setState(Tile.STATE_ACTIVE);
                tile.setLabel("PassPress (Connected)");
            } else {
                tile.setState(Tile.STATE_INACTIVE);
                tile.setLabel("PassPress");
            }
            tile.updateTile();
        }
    }
}
