package fr.radaralerte

import android.Manifest
import android.animation.ValueAnimator
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.location.Location
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import org.json.JSONObject
import java.util.Locale

/** Écran principal, épuré : vitesse, état, un bouton. Le reste est dans les réglages. */
class MainActivity : Activity() {

    private companion object {
        const val REQ_PERMS = 1
    }

    private lateinit var ring: GradientDrawable
    private lateinit var speedView: TextView
    private lateinit var limitSign: TextView
    private lateinit var statusView: TextView
    private lateinit var modeView: TextView
    private lateinit var reportButton: TextView
    private lateinit var startButton: TextView
    private lateinit var infoView: TextView
    private var ringColor = Palette.IDLE
    private var ringAnim: ValueAnimator? = null
    private val listener: (JSONObject) -> Unit = { showUpdate(it) }

    private val prefs by lazy { getSharedPreferences(RadarCore.PREFS, Context.MODE_PRIVATE) }
    private val precise get() = prefs.getBoolean(RadarCore.PREF_PRECISE, false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        darkSystemBars()
        RadarCore.ensureLoaded(this)
        setContentView(buildUi())
        if (!RadarCore.radarsFile(this).exists()) {
            infoView.text = "Téléchargement de la base de radars…"
            RadarDb.download(this) { msg -> toast(msg); refreshInfo() }
        }
    }

    override fun onResume() {
        super.onResume()
        RadarService.listeners.add(listener)
        refreshIdleState()
        refreshInfo()
    }

    override fun onPause() {
        RadarService.listeners.remove(listener)
        super.onPause()
    }

    // ------------------------------------------------------------------ interface

    private fun buildUi(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Palette.BG)
            padForSystemBars(dp(20))
        }

        // Barre du haut : titre + réglages
        val title = label(20f, font = Fonts.medium).apply { text = "Radar Alerte" }
        val gear = ImageView(this).apply {
            setImageResource(R.drawable.ic_settings)
            setColorFilter(Palette.MUTED)
            setPadding(dp(10), dp(10), dp(10), dp(10))
            background = ripple(null, oval(Color.WHITE))
            contentDescription = "Réglages"
            setOnClickListener { startActivity(Intent(this@MainActivity, SettingsActivity::class.java)) }
        }
        val topBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(title, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            addView(gear, LinearLayout.LayoutParams(dp(44), dp(44)))
        }

        // Cadran de vitesse
        ring = oval(Color.TRANSPARENT, Palette.IDLE, dp(10))
        speedView = label(76f, font = Fonts.light).apply { text = "–"; gravity = Gravity.CENTER }
        val unit = label(15f, Palette.MUTED).apply { text = "km/h"; gravity = Gravity.CENTER }
        val speedColumn = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            addView(speedView)
            addView(unit)
        }
        limitSign = label(24f, Color.BLACK, Fonts.medium).apply {
            gravity = Gravity.CENTER
            background = oval(Color.WHITE, Palette.DANGER, dp(6))
            visibility = View.GONE
        }
        val dial = FrameLayout(this).apply {
            background = ring
            addView(speedColumn, FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.CENTER))
            addView(limitSign, FrameLayout.LayoutParams(dp(68), dp(68), Gravity.TOP or Gravity.END))
        }

        statusView = label(20f, font = Fonts.medium).apply {
            gravity = Gravity.CENTER
            setPadding(dp(8), dp(32), dp(8), 0)
        }
        modeView = label(14f, Palette.MUTED).apply {
            gravity = Gravity.CENTER
            setPadding(0, dp(8), 0, 0)
        }
        val center = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            addView(dial, LinearLayout.LayoutParams(dp(250), dp(250)))
            addView(statusView, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
            addView(modeView, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        }

        // Bas de l'écran
        reportButton = label(16f, Palette.ACCENT, Fonts.medium).apply {
            text = "Signaler un radar ici"
            gravity = Gravity.CENTER
            setPadding(0, dp(14), 0, dp(14))
            background = ripple(null, rounded(Color.WHITE, dp(24).toFloat()))
            setOnClickListener { reportMobile() }
        }
        startButton = label(18f, Color.WHITE, Fonts.medium).apply {
            gravity = Gravity.CENTER
            setOnClickListener { toggleService() }
        }
        infoView = label(13f, Palette.MUTED).apply {
            gravity = Gravity.CENTER
            setPadding(0, dp(16), 0, 0)
        }

        root.addView(topBar)
        root.addView(center, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        root.addView(reportButton, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            bottomMargin = dp(8)
        })
        root.addView(startButton, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(60)))
        root.addView(infoView)
        return root
    }

    private fun setRingColor(target: Int) {
        if (target == ringColor) return
        ringAnim?.cancel()
        ringAnim = ValueAnimator.ofArgb(ringColor, target).apply {
            duration = 350
            addUpdateListener {
                val c = it.animatedValue as Int
                ring.setStroke(dp(10), c)
                ringColor = c
            }
            start()
        }
    }

    private fun refreshIdleState() {
        val running = RadarService.running
        startButton.text = if (running) "Arrêter" else "Démarrer"
        startButton.background = ripple(
            if (running) rounded(Palette.SURFACE, dp(30).toFloat(), Palette.DIVIDER, dp(1))
            else rounded(Palette.ACCENT, dp(30).toFloat())
        )
        reportButton.visibility = if (running) View.VISIBLE else View.INVISIBLE
        modeView.text = if (precise) "Mode précis" else "Zones de danger"
        if (running) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            if (statusView.text.isNullOrEmpty()) statusView.text = "Recherche du GPS…"
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            speedView.text = "–"
            limitSign.visibility = View.GONE
            statusView.text = "Prêt"
            setRingColor(Palette.IDLE)
        }
    }

    private fun refreshInfo() {
        val n = RadarDb.radarCount()
        infoView.text = if (n == 0) "Aucune base de radars · voir les réglages"
        else "${String.format(Locale.FRANCE, "%,d", n)} radars · base du ${RadarDb.lastUpdate(this)}"
    }

    private fun showUpdate(data: JSONObject) {
        if (data.optBoolean("stopped")) {
            refreshIdleState()
            return
        }
        speedView.text = if (data.isNull("speed")) "–" else data.optInt("speed").toString()
        if (data.isNull("limit")) {
            limitSign.visibility = View.GONE
        } else {
            limitSign.text = data.optInt("limit").toString()
            limitSign.visibility = View.VISIBLE
        }
        statusView.text = data.optString("text")
        setRingColor(
            when {
                !data.optBoolean("gps") -> Palette.WARN
                data.optBoolean("danger") -> if (precise) Palette.WARN else Palette.DANGER
                else -> Palette.OK
            }
        )
    }

    // ------------------------------------------------------------------ actions

    private fun toggleService() {
        if (RadarService.running) {
            startService(Intent(this, RadarService::class.java).setAction(RadarService.ACTION_STOP))
            startButton.postDelayed({ refreshIdleState() }, 300)
            return
        }
        val needed = mutableListOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
        if (Build.VERSION.SDK_INT >= 33) needed += Manifest.permission.POST_NOTIFICATIONS
        val missing = needed.filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isEmpty()) startRadarService() else requestPermissions(missing.toTypedArray(), REQ_PERMS)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != REQ_PERMS) return
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
            startRadarService()
        } else {
            toast("La localisation précise est nécessaire pour détecter les radars.")
        }
    }

    private fun startRadarService() {
        if (RadarDb.radarCount() == 0) toast("Aucune base de radars fixes : mettez-la à jour dans les réglages.")
        startForegroundService(Intent(this, RadarService::class.java))
        statusView.text = "Recherche du GPS…"
        startButton.postDelayed({ refreshIdleState() }, 300)
    }

    private fun currentLocation(): Location? {
        RadarService.lastLocation?.let { if (System.currentTimeMillis() - it.time < 30_000) return it }
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) return null
        val lm = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        return try {
            lm.getLastKnownLocation(LocationManager.GPS_PROVIDER)
                ?.takeIf { System.currentTimeMillis() - it.time < 60_000 }
        } catch (e: SecurityException) {
            null
        }
    }

    private fun reportMobile() {
        val loc = currentLocation() ?: return toast("Position GPS pas encore connue.")
        RadarCore.reportMobile(loc.latitude, loc.longitude, System.currentTimeMillis())
        toast("Radar signalé")
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
}
