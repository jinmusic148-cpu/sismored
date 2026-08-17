package com.sismored.app.mesh

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat

class SosForegroundService : Service() {

    companion object {
        const val CANAL_ID = "sismored_canal"
        const val NOTIF_ID = 1
    }

    private lateinit var meshManager: SosMeshManager
    private var vecinosActuales = 0

    override fun onCreate() {
        super.onCreate()
        meshManager = SosMeshManager(applicationContext)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        crearCanalNotificacion()
        val notificacion = construirNotificacion()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIF_ID, notificacion,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
            )
        } else {
            startForeground(NOTIF_ID, notificacion)
        }

        meshManager.onVecinosActualizados = { cantidad ->
            vecinosActuales = cantidad
            actualizarNotificacion()
        }
        meshManager.iniciarRed()

        return START_STICKY
    }

    private fun crearCanalNotificacion() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val canal = NotificationChannel(
                CANAL_ID,
                "Red de emergencia activa",
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(canal)
        }
    }

    private fun construirNotificacion(): Notification {
        return NotificationCompat.Builder(this, CANAL_ID)
            .setContentTitle("SismoRed")
            .setContentText("$vecinosActuales dispositivos cerca")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setOngoing(true)
            .build()
    }

    private fun actualizarNotificacion() {
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(NOTIF_ID, construirNotificacion())
    }

    override fun onDestroy() {
        meshManager.detenerRed()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
