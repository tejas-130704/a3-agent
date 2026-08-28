package com.agent.ai.service

import android.content.Intent
import android.os.Build
import android.provider.Settings
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService

/**
 * Quick Settings tile — opens the system connectivity panel so user can toggle WiFi/mobile data.
 * Apps cannot silently toggle WiFi on API 29+; this is the honest one-tap shortcut.
 */
class ConnectivityTileService : TileService() {

    override fun onStartListening() {
        qsTile?.apply {
            state = Tile.STATE_ACTIVE
            label = "Agent Network"
            subtitle = "Tap to open connectivity panel"
            updateTile()
        }
    }

    override fun onClick() {
        val panelIntent = Intent(Settings.Panel.ACTION_INTERNET_CONNECTIVITY).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startActivityAndCollapse(panelIntent)
        } else {
            startActivityAndCollapse(panelIntent)
        }
    }
}
