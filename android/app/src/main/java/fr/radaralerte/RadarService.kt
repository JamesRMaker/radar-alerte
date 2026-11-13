package fr.radaralerte

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import org.json.JSONObject
import java.util.Locale
import java.util.concurrent.CopyOnWriteArraySet

/**
 * Service au premier plan : reçoit le GPS chaque seconde, transmet chaque position au
 * moteur Rust et prononce les alertes qu'il renvoie. Continue de tourner écran éteint
 * ou quand une autre appli (Maps, Waze…) est au premier plan.
 */
class RadarService : Service(), LocationListener {

    companion object {
        const val ACTION_STOP = "fr.radaralerte.STOP"
        /** Démarrage déclenché par la voiture (Bluetooth / Android Auto) : arrêt auto à la déconnexion. */
        const val EXTRA_AUTO = "auto"
        private const val CHANNEL_ID = "surveillance"
        private const val NOTIF_ID = 1

        @Volatile var running = false
            private set
        @Volatile var lastLocation: Location? = null
            private set

        @Volatile var autoStarted = false
            private set

        /** Écouteurs (téléphone, écran Android Auto) appelés sur le thread principal à chaque mise à jour. */
        val listeners = CopyOnWriteArraySet<(JSONObject) -> Unit>()
    }

    private lateinit var locationManager: LocationManager
    private lateinit var audioManager: AudioManager
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var tone: ToneGenerator? = null
    private var focusRequest: AudioFocusRequest? = null
    private var lastNotifText = ""
    private val main = Handler(Looper.getMainLooper())

    private val speechAttributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
        .build()

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        locationManager = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        createChannel()
        tts = TextToSpeech(this) { status ->
            if (status == TextToSpeech.SUCCESS) {
                tts?.language = Locale.FRANCE
                tts?.setAudioAttributes(speechAttributes)
                tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(id: String?) {}
                    override fun onDone(id: String?) { releaseFocusIfIdle() }
                    @Deprecated("Deprecated in Java")
                    override fun onError(id: String?) { releaseFocusIfIdle() }
                })
                ttsReady = true
            }
        }
        tone = try { ToneGenerator(AudioManager.STREAM_MUSIC, 90) } catch (e: RuntimeException) { null }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (!running) autoStarted = intent?.getBooleanExtra(EXTRA_AUTO, false) == true
        val notif = buildNotification("Recherche du signal GPS…")
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(NOTIF_ID, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
            } else {
                startForeground(NOTIF_ID, notif)
            }
        } catch (e: Exception) {
            // Android refuse un démarrage en arrière-plan : on propose un démarrage d'un appui.
            CarAutoStart.notifyTapToStart(this)
            stopSelf()
            return START_NOT_STICKY
        }
        CarAutoStart.cancelPrompt(this)
        RadarCore.ensureLoaded(this)
        RadarCore.resetSession()
        try {
            locationManager.removeUpdates(this)
            locationManager.requestLocationUpdates(
                LocationManager.GPS_PROVIDER, 1000L, 0f, this, Looper.getMainLooper()
            )
            running = true
        } catch (e: SecurityException) {
            // Permission retirée entre-temps
            stopSelf()
        }
        return START_STICKY
    }

    override fun onDestroy() {
        running = false
        try { locationManager.removeUpdates(this) } catch (_: SecurityException) {}
        tts?.stop()
        tts?.shutdown()
        tone?.release()
        releaseFocus()
        autoStarted = false
        val stopped = JSONObject().put("text", "Surveillance arrêtée").put("stopped", true)
        listeners.forEach { it(stopped) }
        RadarWidget.render(this, null)
        super.onDestroy()
    }

    // ------------------------------------------------------------------ GPS

    override fun onLocationChanged(loc: Location) {
        lastLocation = loc
        val json = RadarCore.update(
            loc.latitude,
            loc.longitude,
            if (loc.hasSpeed()) loc.speed.toDouble() else -1.0,
            if (loc.hasBearing()) loc.bearing.toDouble() else -1.0,
            if (loc.hasAccuracy()) loc.accuracy.toDouble() else -1.0,
            System.currentTimeMillis()
        )
        val data = try { JSONObject(json) } catch (e: Exception) { return }

        val say = data.optJSONArray("say")
        if (say != null && say.length() > 0) {
            if (data.optBoolean("beep")) tone?.startTone(ToneGenerator.TONE_PROP_BEEP2, 250)
            val delay = if (data.optBoolean("beep")) 300L else 0L
            main.postDelayed({ for (i in 0 until say.length()) speak(say.getString(i)) }, delay)
        }

        val text = data.optString("text")
        if (text != lastNotifText) {
            lastNotifText = text
            getSystemService(NotificationManager::class.java).notify(NOTIF_ID, buildNotification(text))
        }
        listeners.forEach { it(data) }
        RadarWidget.render(this, data)
    }

    override fun onProviderEnabled(provider: String) {}
    override fun onProviderDisabled(provider: String) {
        speak("GPS désactivé")
    }
    @Deprecated("Deprecated in Java")
    override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}

    // ------------------------------------------------------------------ voix

    private fun speak(text: String) {
        val t = tts ?: return
        if (!ttsReady) return
        requestFocus()
        t.speak(text, TextToSpeech.QUEUE_ADD, null, "alerte-${System.nanoTime()}")
    }

    /** Baisse le volume de la musique pendant l'annonce. */
    private fun requestFocus() {
        if (focusRequest != null) return
        val req = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
            .setAudioAttributes(speechAttributes)
            .build()
        audioManager.requestAudioFocus(req)
        focusRequest = req
    }

    private fun releaseFocusIfIdle() {
        main.postDelayed({ if (tts?.isSpeaking != true) releaseFocus() }, 200)
    }

    private fun releaseFocus() {
        focusRequest?.let { audioManager.abandonAudioFocusRequest(it) }
        focusRequest = null
    }

    // ------------------------------------------------------------------ notification

    private fun createChannel() {
        val ch = NotificationChannel(CHANNEL_ID, "Surveillance radars", NotificationManager.IMPORTANCE_LOW)
        ch.description = "Affiche l'état de la surveillance pendant la conduite"
        getSystemService(NotificationManager::class.java).createNotificationChannel(ch)
    }

    private fun buildNotification(text: String): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val stop = PendingIntent.getService(
            this, 1, Intent(this, RadarService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notif)
            .setColor(Palette.ACCENT)
            .setContentTitle("Radar Alerte")
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(open)
            .addAction(Notification.Action.Builder(null, "Arrêter", stop).build())
            .build()
    }
}
