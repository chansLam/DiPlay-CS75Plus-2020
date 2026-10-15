package com.shihab.diplay
import com.shihab.diplay.host.CarPlayBackgroundSession

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.IBinder
import com.shihab.diplay.host.R

/** Keeps an explicitly started connection alive when another car app is in the foreground. */
class DiPlaySessionService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            CarPlayBackgroundSession.stop()
            stopSelf()
            return START_NOT_STICKY
        }
        @Suppress("DEPRECATION")
        val pendingFlags = PendingIntent.FLAG_UPDATE_CURRENT
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, CarPlayHostActivity::class.java), pendingFlags,
        )
        val stop = PendingIntent.getService(
            this, 1,
            Intent(this, DiPlaySessionService::class.java).setAction(ACTION_STOP),
            pendingFlags,
        )
        @Suppress("DEPRECATION")
        val notification = Notification.Builder(this)
            .setSmallIcon(R.drawable.ic_carplay)
            .setContentTitle("DiPlay")
            .setContentText("CarPlay connection running")
            .setContentIntent(open)
            .setOngoing(true)
            .addAction(0, "Disconnect", stop)
            .build()
        @Suppress("DEPRECATION")
        startForeground(1, notification)
        return START_NOT_STICKY
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        CarPlayBackgroundSession.stop()
        stopSelf()
    }

    companion object {
        const val ACTION_STOP = "com.shihab.diplay.DISCONNECT"
    }
}
