package anomaly.hex.android

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build

/**
 * The silent notification that keeps the Hex button's service in the
 * foreground. Minimum importance: no sound, vibration, or status bar icon,
 * only a quiet line at the bottom of the shade.
 */
object Foreground {
    private const val CHANNEL = "button"
    private const val ID = 1

    fun start(service: Service) {
        val manager = service.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL, "Hex button", NotificationManager.IMPORTANCE_MIN).apply {
                setShowBadge(false)
                setSound(null, null)
                enableVibration(false)
            },
        )
        val open = PendingIntent.getActivity(
            service,
            0,
            Intent(service, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = Notification.Builder(service, CHANNEL)
            .setSmallIcon(R.drawable.ic_hex_mark)
            .setContentTitle("Hex button is on")
            .setContentIntent(open)
            .setOngoing(true)
            .setShowWhen(false)
            .build()
        // A failure here only costs the extra protection, never the button.
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                service.startForeground(ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
            } else {
                service.startForeground(ID, notification)
            }
        }
    }
}
