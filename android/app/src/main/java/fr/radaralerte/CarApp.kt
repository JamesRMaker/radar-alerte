package fr.radaralerte

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.SystemClock
import androidx.car.app.CarAppService
import androidx.car.app.CarContext
import androidx.car.app.CarToast
import androidx.car.app.Screen
import androidx.car.app.Session
import androidx.car.app.model.Action
import androidx.car.app.model.Pane
import androidx.car.app.model.PaneTemplate
import androidx.car.app.model.Row
import androidx.car.app.model.Template
import androidx.car.app.validation.HostValidator
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import org.json.JSONObject

/**
 * Écran Radar Alerte sur Android Auto : vitesse, limite, état, et deux boutons.
 * La surveillance elle-même reste le RadarService du téléphone (moteur Rust).
 */
class RadarCarAppService : CarAppService() {
    // Appli installée hors Play Store : on accepte tous les hôtes Android Auto.
    override fun createHostValidator(): HostValidator = HostValidator.ALLOW_ALL_HOSTS_VALIDATOR
    override fun onCreateSession(): Session = RadarCarSession()
}

class RadarCarSession : Session() {
    override fun onCreateScreen(intent: Intent): Screen = RadarCarScreen(carContext)
}

class RadarCarScreen(ctx: CarContext) : Screen(ctx) {

    private var data: JSONObject? = null
    private var shownKey = ""
    private var lastRefresh = 0L
    private var autoStartTried = false
    private val listener: (JSONObject) -> Unit = { onData(it) }

    init {
        lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) {
                RadarService.listeners.add(listener)
                RadarCore.ensureLoaded(carContext)
                if (!autoStartTried) {
                    autoStartTried = true
                    if (!RadarService.running) start()
                }
                invalidate()
            }

            override fun onStop(owner: LifecycleOwner) {
                RadarService.listeners.remove(listener)
            }
        })
    }

    /** Rafraîchit l'écran seulement quand ce qui est affiché change (Android Auto limite les mises à jour). */
    private fun onData(d: JSONObject) {
        data = if (d.optBoolean("stopped")) null else d
        val speedBucket = if (d.isNull("speed")) -1 else d.optInt("speed") / 2
        val key = listOf(d.optString("text"), d.opt("limit"), d.optBoolean("danger"), speedBucket).joinToString("|")
        if (key == shownKey) return
        val importantChange = key.substringBeforeLast('|') != shownKey.substringBeforeLast('|')
        val now = SystemClock.elapsedRealtime()
        if (importantChange || now - lastRefresh >= 2000) {
            shownKey = key
            lastRefresh = now
            invalidate()
        }
    }

    private fun start() {
        val fine = Manifest.permission.ACCESS_FINE_LOCATION
        if (carContext.checkSelfPermission(fine) != PackageManager.PERMISSION_GRANTED) {
            // La demande s'affiche sur le téléphone.
            carContext.requestPermissions(listOf(fine, Manifest.permission.ACCESS_COARSE_LOCATION)) { granted, _ ->
                if (fine in granted) start()
            }
            return
        }
        try {
            carContext.startForegroundService(
                Intent(carContext, RadarService::class.java).putExtra(RadarService.EXTRA_AUTO, true)
            )
        } catch (e: Exception) {
            CarToast.makeText(carContext, "Ouvrez Radar Alerte sur le téléphone pour démarrer", CarToast.LENGTH_LONG).show()
        }
        invalidate()
    }

    private fun stop() {
        try {
            carContext.startService(Intent(carContext, RadarService::class.java).setAction(RadarService.ACTION_STOP))
        } catch (_: Exception) {}
        data = null
        invalidate()
    }

    private fun report() {
        val loc = RadarService.lastLocation
        if (loc == null) {
            CarToast.makeText(carContext, "Position GPS pas encore connue", CarToast.LENGTH_SHORT).show()
            return
        }
        RadarCore.reportMobile(loc.latitude, loc.longitude, System.currentTimeMillis())
        CarToast.makeText(carContext, "Radar signalé", CarToast.LENGTH_SHORT).show()
    }

    override fun onGetTemplate(): Template {
        val running = RadarService.running
        val d = data
        val pane = Pane.Builder()
        if (!running) {
            pane.addRow(Row.Builder().setTitle("Surveillance arrêtée").addText("Appuyez sur Démarrer").build())
        } else if (d == null) {
            pane.addRow(Row.Builder().setTitle("Recherche du GPS…").build())
        } else {
            pane.addRow(Row.Builder().setTitle(d.optString("text").ifEmpty { "Surveillance active" }).build())
            val speed = if (d.isNull("speed")) "–" else d.optInt("speed").toString()
            pane.addRow(Row.Builder().setTitle("Vitesse").addText("$speed km/h").build())
            if (!d.isNull("limit")) {
                pane.addRow(Row.Builder().setTitle("Limite").addText("${d.optInt("limit")} km/h").build())
            }
        }
        pane.addAction(
            Action.Builder()
                .setTitle(if (running) "Arrêter" else "Démarrer")
                .setOnClickListener { if (RadarService.running) stop() else start() }
                .build()
        )
        if (running) {
            pane.addAction(Action.Builder().setTitle("Signaler un radar").setOnClickListener { report() }.build())
        }
        return PaneTemplate.Builder(pane.build())
            .setTitle("Radar Alerte")
            .setHeaderAction(Action.APP_ICON)
            .build()
    }
}
