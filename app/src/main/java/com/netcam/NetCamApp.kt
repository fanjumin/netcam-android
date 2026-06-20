package com.netcam

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build

class NetCamApp : Application() {

    companion object {
        const val CHANNEL_ID = "netcam_server"
        const val CHANNEL_NAME = "NetCam Server"
        const val NOTIFICATION_ID = 1001
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                CHANNEL_NAME,
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "NetCam Pro streaming server notification"
                setShowBadge(false)
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }
}
