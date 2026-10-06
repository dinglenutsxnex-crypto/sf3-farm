package com.sf3farm

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat

/** Foreground service so overnight grinds survive. No VPN, plain TCP. */
class FarmService : Service() {

    override fun onCreate() {
        super.onCreate()
        FarmRunner.prefs = FarmRunner.prefs ?: Prefs(applicationContext)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForegroundNotification()
        when (intent?.action) {
            ACTION_STOP -> {
                FarmRunner.stop()
                stopSelf()
            }
        }
        return START_STICKY
    }

    private fun startForegroundNotification() {
        val mgr = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26) {
            mgr.createNotificationChannel(
                NotificationChannel(CH, "SF3 Farm", NotificationManager.IMPORTANCE_LOW)
            )
        }
        val n: Notification = NotificationCompat.Builder(this, CH)
            .setContentTitle("SF3 Farm running")
            .setContentText("Grinding duels. Stop from the app.")
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(1, n)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val CH = "farm"
        const val ACTION_STOP = "com.sf3farm.STOP"
    }
}
