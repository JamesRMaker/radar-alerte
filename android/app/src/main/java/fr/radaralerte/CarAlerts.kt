package fr.radaralerte

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.car.app.notification.CarAppExtender
import androidx.car.app.notification.CarNotificationManager
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import org.json.JSONObject

/**
 * « Widget » Android Auto : une bannière qui s'affiche en haut de l'écran de la voiture,
 * par-dessus Waze ou Maps, avec la limitation et la distance, à chaque alerte.
 * (Android Auto n'accepte pas de vrais widgets d'applis tierces.)
 */
object CarAlerts {
    private const val CHANNEL_ID = "alertes_voiture"
    private const val ALERT_ID = 10
    private var channelReady = false

    private fun ensureChannel(ctx: Context) {
        if (channelReady) return
        val channel = NotificationChannelCompat.Builder(CHANNEL_ID, NotificationManagerCompat.IMPORTANCE_HIGH)
            .setName("Alertes sur l'écran de la voiture")
            .setDescription("Bannière limite + distance sur Android Auto")
            .setSound(null, null) // la voix de l'appli suffit
            .setVibrationEnabled(false)
            .build()
        CarNotificationManager.from(ctx).createNotificationChannel(channel)
        channelReady = true
    }

    /** Appelé par RadarService après chaque position avec le JSON du moteur Rust. */
    fun onUpdate(ctx: Context, data: JSONObject) {
        val say = data.optJSONArray("say") ?: return
        if (say.length() == 0) return
        val first = say.optString(0)
        if (first.startsWith("Fin de zone")) {
            cancel(ctx)
            return
        }
        if (!data.optBoolean("beep")) return

        val titleBase = data.optString("title")
        val title = when {
            titleBase.isNotEmpty() && !data.isNull("dist") -> {
                val d = RadarWidget.formatDistance(data.optDouble("dist"))
                if (titleBase.endsWith("dans")) "$titleBase $d" else "$titleBase à $d"
            }
            titleBase.isNotEmpty() -> titleBase
            else -> first
        }
        val text = if (data.isNull("limit")) first else "Limitée à ${data.optInt("limit")} km/h"
        post(ctx, title, text)
    }

    private fun post(ctx: Context, title: String, text: String) {
        try {
            ensureChannel(ctx)
            val open = PendingIntent.getActivity(
                ctx, 5, Intent(ctx, MainActivity::class.java),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            val builder = NotificationCompat.Builder(ctx, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_notif)
                .setContentTitle(title)
                .setContentText(text)
                .setCategory(NotificationCompat.CATEGORY_NAVIGATION)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setOnlyAlertOnce(false)
                .setAutoCancel(true)
                .setTimeoutAfter(15_000)
                .setContentIntent(open)
                .extend(
                    CarAppExtender.Builder()
                        .setContentTitle(title)
                        .setContentText(text)
                        .setSmallIcon(R.drawable.ic_notif)
                        .setImportance(NotificationManagerCompat.IMPORTANCE_HIGH)
                        .build()
                )
            CarNotificationManager.from(ctx).notify(ALERT_ID, builder)
        } catch (_: SecurityException) {
            // Notifications refusées : la voix reste active.
        }
    }

    fun cancel(ctx: Context) {
        CarNotificationManager.from(ctx).cancel(ALERT_ID)
    }
}
