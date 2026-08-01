package com.passpress.bleKeyboard;

import android.os.Build;
import android.service.quicksettings.Tile;
import android.service.quicksettings.TileService;
import android.widget.Toast;
import android.content.SharedPreferences;
import android.content.Context;

public class PassPressTileService extends TileService {

    @Override
    public void onStartListening() {
        super.onStartListening();
        updateTileState();
    }

    @Override
    public void onClick() {
        super.onClick();
        HidKeyboardService svc = HidKeyboardService.getInstance();
        if (svc != null) {
            if (svc.getConnectedDevice() != null) {
                svc.disconnectDevice();
                Toast.makeText(this, "PassPress Disconnected", Toast.LENGTH_SHORT).show();
            } else {
                SharedPreferences prefs = getSharedPreferences("passpress_prefs", Context.MODE_PRIVATE);
                String lastDevice = prefs.getString("last_connected_device", null);
                if (lastDevice != null) {
                    svc.connectToDevice(lastDevice);
                    Toast.makeText(this, "PassPress Connecting...", Toast.LENGTH_SHORT).show();
                } else {
                    Toast.makeText(this, "No saved device to connect to", Toast.LENGTH_SHORT).show();
                }
            }
        } else {
            Toast.makeText(this, "Keyboard Service not running", Toast.LENGTH_SHORT).show();
        }
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
