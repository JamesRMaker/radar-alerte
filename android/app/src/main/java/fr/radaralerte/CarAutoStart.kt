package fr.radaralerte

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.bluetooth.BluetoothDevice
import android.companion.AssociationInfo
import android.companion.CompanionDeviceService
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.annotation.RequiresApi

/**
 * Démarrage / arrêt automatiques quand le téléphone se connecte à la voiture.
 *
 * - Android 12 et plus : la voiture est associée à l'appli (CompanionDeviceManager) ; Android
 *   prévient CarPresenceService quand elle se connecte en Bluetooth. Cette association autorise
 *   aussi l'appli à démarrer la surveillance en arrière-plan.
 * - Android 8 à 11 : un récepteur Bluetooth classique suffit.
 * - Si Android refuse malgré tout, une notification « Voiture connectée » permet de démarrer d'un appui.
 */
object CarAutoStart {

    const val PREF_CAR_ADDRESS = "car_address"
    const val PREF_CAR_NAME = "car_name"
    private const val CHANNEL_ID = "voiture"
    private const val PROMPT_ID = 2

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(RadarCore.PREFS, Context.MODE_PRIVATE)

    fun carAddress(ctx: Context): String? = prefs(ctx).getString(PREF_CAR_ADDRESS, null)
    fun carName(ctx: Context): String? = prefs(ctx).getString(PREF_CAR_NAME, null)

    fun hasBackgroundLocation(ctx: Context): Boolean =
        Build.VERSION.SDK_INT < 29 ||
            ctx.checkSelfPermission(Manifest.permission.ACCESS_BACKGROUND_LOCATION) == PackageManager.PERMISSION_GRANTED

    /** La voiture vient de se connecter. */
    fun onCarConnected(ctx: Context) {
        if (RadarService.running) return
        val fine = ctx.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        // Depuis Android 11, la position n'est accessible en arrière-plan qu'avec « Toujours autoriser ».
        if (!fine || (Build.VERSION.SDK_INT >= 30 && !hasBackgroundLocation(ctx))) {
            notifyTapToStart(ctx)
            return
        }
        try {
            ctx.startForegroundService(Intent(ctx, RadarService::class.java).putExtra(RadarService.EXTRA_AUTO, true))
        } catch (e: Exception) {
            notifyTapToStart(ctx)
        }
    }

    /** La voiture s'est déconnectée : on arrête si c'est elle qui avait démarré la surveillance. */
    fun onCarDisconnected(ctx: Context) {
        cancelPrompt(ctx)
        if (RadarService.running && RadarService.autoStarted) {
            try {
                ctx.startService(Intent(ctx, RadarService::class.java).setAction(RadarService.ACTION_STOP))
            } catch (_: Exception) {}
        }
    }

    fun notifyTapToStart(ctx: Context) {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Voiture connectée", NotificationManager.IMPORTANCE_HIGH)
        )
        val start = PendingIntent.getForegroundService(
            ctx, 3,
            Intent(ctx, RadarService::class.java).putExtra(RadarService.EXTRA_AUTO, true),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val n = Notification.Builder(ctx, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notif)
            .setColor(Palette.ACCENT)
            .setContentTitle("Voiture connectée")
            .setContentText("Appuyez pour démarrer la surveillance des radars")
            .setContentIntent(start)
            .setAutoCancel(true)
            .addAction(Notification.Action.Builder(null, "Démarrer", start).build())
            .build()
        try {
            nm.notify(PROMPT_ID, n)
        } catch (_: SecurityException) {}
    }

    fun cancelPrompt(ctx: Context) {
        ctx.getSystemService(NotificationManager::class.java).cancel(PROMPT_ID)
    }
}

/** Android 12+ : appelé par le système quand la voiture associée se connecte / se déconnecte. */
@RequiresApi(31)
class CarPresenceService : CompanionDeviceService() {
    @Deprecated("Remplacé à partir d'Android 13")
    override fun onDeviceAppeared(address: String) = CarAutoStart.onCarConnected(this)

    @Deprecated("Remplacé à partir d'Android 13")
    override fun onDeviceDisappeared(address: String) = CarAutoStart.onCarDisconnected(this)

    @RequiresApi(33)
    override fun onDeviceAppeared(associationInfo: AssociationInfo) = CarAutoStart.onCarConnected(this)

    @RequiresApi(33)
    override fun onDeviceDisappeared(associationInfo: AssociationInfo) = CarAutoStart.onCarDisconnected(this)
}

/** Android 8 à 11 : connexion / déconnexion Bluetooth de la voiture choisie. */
class CarBluetoothReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        if (Build.VERSION.SDK_INT >= 31) return // géré par CarPresenceService
        @Suppress("DEPRECATION")
        val device: BluetoothDevice = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE) ?: return
        val car = CarAutoStart.carAddress(ctx) ?: return
        val address = try { device.address } catch (_: SecurityException) { return }
        if (!address.equals(car, ignoreCase = true)) return
        when (intent.action) {
            BluetoothDevice.ACTION_ACL_CONNECTED -> CarAutoStart.onCarConnected(ctx)
            BluetoothDevice.ACTION_ACL_DISCONNECTED -> CarAutoStart.onCarDisconnected(ctx)
        }
    }
}
