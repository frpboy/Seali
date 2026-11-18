package com.frpboy.seali

import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.util.Log
import com.frpboy.seali.util.NotificationUtil
import com.frpboy.seali.util.NotificationUtil.SERVICE_NOTIFICATION_ID

private const val TAG = "DownloadService"

/** This `Service` does nothing */
class DownloadService : Service() {

    override fun onBind(intent: Intent): IBinder {
        val pendingIntent: PendingIntent =
            Intent(this, MainActivity::class.java).let { notificationIntent ->
                PendingIntent.getActivity(this, 0, notificationIntent, PendingIntent.FLAG_IMMUTABLE)
            }
        val notification = NotificationUtil.makeServiceNotification(pendingIntent)
        try {
            startForeground(SERVICE_NOTIFICATION_ID, notification)
        } catch (e: IllegalStateException) {
            // Android 15+ refuses a new dataSync foreground start once its time
            // budget is used up (until the app is visible again). Stay a plain
            // bound service instead of crashing; downloads then run best-effort.
            Log.w(TAG, "startForeground refused", e)
        }
        return DownloadServiceBinder()
    }

    override fun onUnbind(intent: Intent?): Boolean {
        Log.d(TAG, "onUnbind: ")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            stopForeground(true)
        }
        stopSelf()
        return super.onUnbind(intent)
    }

    /**
     * Android 15+ limits a dataSync foreground service to a cumulative time
     * budget. When the system reports the timeout, the service must stop
     * itself promptly; downloads themselves are not owned by this service,
     * so stopping the foreground state only drops the notification.
     */
    override fun onTimeout(startId: Int, fgsType: Int) {
        Log.w(TAG, "onTimeout: fgsType=$fgsType")
        stopForeground(STOP_FOREGROUND_REMOVE)
        // Unbind so App.isServiceRunning resets and the service can be started
        // again (with a fresh foreground budget) the next time downloads run.
        App.stopService()
        stopSelf()
    }

    override fun onTimeout(startId: Int) {
        onTimeout(startId, 0)
    }

    inner class DownloadServiceBinder : Binder() {
        fun getService(): DownloadService = this@DownloadService
    }
}
