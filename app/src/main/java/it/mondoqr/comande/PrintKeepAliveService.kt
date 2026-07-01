package it.mondoqr.comande

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager

/**
 * Servizio in primo piano (notifica persistente) che tiene VIVO il processo dell'app
 * e la CPU (partial wakelock), così il polling/stampa della WebView continua anche con
 * l'app in background o lo schermo spento. NON sopravvive alla chiusura totale dell'app
 * (swipe via): lì la WebView (il "cervello") viene distrutta.
 */
class PrintKeepAliveService : Service() {

    private var wakeLock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startInForeground()
        if (wakeLock == null) {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "MondoQR:print").apply {
                setReferenceCounted(false)
                acquire()
            }
        }
        return START_STICKY
    }

    private fun startInForeground() {
        val channelId = "comande_mondoqr"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (nm.getNotificationChannel(channelId) == null) {
                nm.createNotificationChannel(
                    NotificationChannel(channelId, "Comande MondoQR", NotificationManager.IMPORTANCE_LOW)
                )
            }
        }
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, channelId)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }
        val notif: Notification = builder
            .setContentTitle("Comande MondoQR attivo")
            .setContentText("Pronto a stampare le comande")
            .setSmallIcon(android.R.drawable.ic_menu_upload)
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(1, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(1, notif)
        }
    }

    override fun onDestroy() {
        try { wakeLock?.release() } catch (_: Exception) { }
        wakeLock = null
        super.onDestroy()
    }
}
