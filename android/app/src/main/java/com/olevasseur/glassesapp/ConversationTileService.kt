package com.olevasseur.glassesapp

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService

class ConversationTileService : TileService() {
    override fun onStartListening() {
        qsTile?.apply { state = Tile.STATE_INACTIVE; label = "Glasses conversation"; updateTile() }
    }

    @SuppressLint("StartActivityAndCollapseDeprecated") // Intent overload is required below API 34.
    @Suppress("DEPRECATION")
    override fun onClick() {
        super.onClick()
        // A visible Activity provides the while-in-use microphone permission on Android 14+.
        val launch = {
            val intent = Intent(this, MainActivity::class.java).setAction(ConversationService.START)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            if (Build.VERSION.SDK_INT >= 34) startActivityAndCollapse(PendingIntent.getActivity(this, 1, intent,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
            else startActivityAndCollapse(intent)
        }
        if (isLocked) unlockAndRun(launch) else launch()
    }
}
