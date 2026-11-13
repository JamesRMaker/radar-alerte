package fr.radaralerte

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.location.Location
import android.location.LocationManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/** Écran principal : démarrer/arrêter, signaler un radar mobile, mettre à jour la base, réglages. */
class MainActivity : Activity() {

    private companion object {
        const val REQ_PERMS = 1
        const val REQ_IMPORT = 2
        val TTL_CHOICES = intArrayOf(60, 120, 180, 360, 0) // minutes, 0 = permanent
        val MARGIN_CHOICES = intArrayOf(0, 3, 5, 8)
        const val BG = 0xFF101418.toInt()
        const val BG_DANGER = 0xFFB71C1C.toInt()
        const val BG_NEAR = 0xFFE65100.toInt()
    }

    private lateinit var root: LinearLayout
    private lateinit var speedView: TextView
    private lateinit var statusView: TextView
    private lateinit var infoView: TextView
    private lateinit var startButton: Button
    private lateinit var ttlButton: Button
    private lateinit var marginButton: Button
    private lateinit var preciseSwitch: Switch

    private val prefs by lazy { getSharedPreferences(RadarCore.PREFS, Context.MODE_PRIVATE) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        RadarCore.ensureLoaded(this)
        buildUi()
        refreshInfo()
        if (!RadarCore.radarsFile(this).exists()) {
            AlertDialog.Builder(this)
                .setTitle("Base de radars vide")
                .setMessage("Télécharger maintenant la liste officielle des radars fixes (data.gouv.fr, ~170 Ko) ?")
                .setPositiveButton("Télécharger") { _, _ -> downloadRadars() }
                .setNegativeButton("Plus tard", null)
                .show()
        }
    }

    override fun onResume() {
        super.onResume()
        RadarService.onUpdate = { showUpdate(it) }
        refreshStartButton()
    }

    override fun onPause() {
        RadarService.onUpdate = null
        super.onPause()
    }

    // ------------------------------------------------------------------ interface

    private fun buildUi() {
        val pad = dp(16)
        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
            setBackgroundColor(BG)
        }
        fun text(size: Float, bold: Boolean = false) = TextView(this).apply {
            textSize = size
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            if (bold) setTypeface(typeface, Typeface.BOLD)
            setPadding(0, dp(6), 0, dp(6))
        }
        fun button(label: String, onClick: () -> Unit) = Button(this).apply {
            text = label
            isAllCaps = false
            textSize = 17f
            setOnClickListener { onClick() }
        }

        speedView = text(64f, bold = true).apply { text = "– km/h" }
        statusView = text(22f, bold = true).apply { text = "Surveillance arrêtée" }
        infoView = text(14f).apply { setTextColor(0xFFB0BEC5.toInt()) }
        startButton = button("▶  Démarrer la surveillance") { toggleService() }
        val report = button("📍  Signaler un radar mobile ici") { reportMobile() }
        val remove = button("✖  Radar mobile parti (supprimer le plus proche)") { removeMobile() }
        val update = button("⬇  Mettre à jour les radars fixes") { downloadRadars() }
        val importBtn = button("📂  Importer un fichier CSV de radars") { importCsv() }
        ttlButton = button("") { cycleTtl() }
        marginButton = button("") { cycleMargin() }
        preciseSwitch = Switch(this).apply {
            text = "Mode précis (type et distance du radar)"
            setTextColor(Color.WHITE)
            textSize = 16f
            setPadding(0, dp(12), 0, dp(12))
            isChecked = prefs.getBoolean(RadarCore.PREF_PRECISE, false)
            setOnCheckedChangeListener { _, checked -> onPreciseToggled(checked) }
        }
        val legal = text(12f).apply {
            setTextColor(0xFF90A4AE.toInt())
            gravity = Gravity.START
            text = "Par défaut, l'appli annonce des « zones de danger » (500 m en ville, 2 km sur route, " +
                "4 km sur autoroute) sans révéler l'emplacement exact des radars, comme l'exige la loi française " +
                "pour les assistants d'aide à la conduite. Le mode précis est destiné à un usage hors de France."
        }

        listOf(speedView, statusView, startButton, report, remove).forEach { root.addView(it) }
        root.addView(spacer())
        listOf(infoView, update, importBtn, ttlButton, marginButton, preciseSwitch, legal).forEach { root.addView(it) }
        setContentView(ScrollView(this).apply { setBackgroundColor(BG); addView(root) })
        refreshSettingsLabels()
    }

    private fun spacer() = View(this).apply {
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(24))
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun showUpdate(data: JSONObject) {
        if (data.optBoolean("stopped")) {
            speedView.text = "– km/h"
            statusView.text = data.optString("text")
            root.setBackgroundColor(BG)
            refreshStartButton()
            return
        }
        speedView.text = if (data.isNull("speed")) "– km/h" else "${data.optInt("speed")} km/h"
        statusView.text = data.optString("text")
        val bg = when {
            !data.optBoolean("danger") -> BG
            prefs.getBoolean(RadarCore.PREF_PRECISE, false) -> BG_NEAR
            else -> BG_DANGER
        }
        root.setBackgroundColor(bg)
        infoView.text = "Base : ${data.optInt("radars")} radars fixes · ${data.optInt("mobiles")} mobiles signalés"
    }

    private fun refreshInfo() {
        val s = JSONObject(RadarCore.stats())
        val f = RadarCore.radarsFile(this)
        val date = if (f.exists())
            java.text.DateFormat.getDateInstance().format(java.util.Date(f.lastModified())) else "jamais"
        infoView.text = "Base : ${s.optInt("radars")} radars fixes (mise à jour : $date)\n" +
            "${s.optInt("mobiles")} radars mobiles signalés"
    }

    private fun refreshStartButton() {
        startButton.text = if (RadarService.running) "■  Arrêter la surveillance" else "▶  Démarrer la surveillance"
    }

    private fun refreshSettingsLabels() {
        val ttl = prefs.getInt(RadarCore.PREF_TTL_MIN, 180)
        ttlButton.text = "⏱  Durée d'un radar mobile : " + if (ttl == 0) "permanent" else "${ttl / 60} h"
        marginButton.text = "⚙  Alerte de vitesse au-delà de la limite +${prefs.getInt(RadarCore.PREF_MARGIN, 3)} km/h"
    }

    // ------------------------------------------------------------------ actions

    private fun toggleService() {
        if (RadarService.running) {
            startService(Intent(this, RadarService::class.java).setAction(RadarService.ACTION_STOP))
            startButton.postDelayed({ refreshStartButton() }, 300)
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
            toast("La localisation précise est indispensable pour détecter les radars.")
        }
    }

    private fun startRadarService() {
        if (JSONObject(RadarCore.stats()).optInt("radars") == 0) {
            toast("Attention : aucune base de radars fixes chargée. Seuls les radars mobiles signalés seront annoncés.")
        }
        startForegroundService(Intent(this, RadarService::class.java))
        statusView.text = "Recherche du signal GPS…"
        startButton.postDelayed({ refreshStartButton() }, 300)
    }

    private fun currentLocation(): Location? {
        RadarService.lastLocation?.let { if (System.currentTimeMillis() - it.time < 30_000) return it }
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) return null
        val lm = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        return try {
            lm.getLastKnownLocation(LocationManager.GPS_PROVIDER)
                ?.takeIf { System.currentTimeMillis() - it.time < 60_000 }
        } catch (e: SecurityException) { null }
    }

    private fun reportMobile() {
        val loc = currentLocation() ?: return toast("Position GPS inconnue : démarrez la surveillance d'abord.")
        val n = RadarCore.reportMobile(loc.latitude, loc.longitude, System.currentTimeMillis())
        toast("Radar mobile enregistré ($n actif(s))")
        refreshInfo()
    }

    private fun removeMobile() {
        val loc = currentLocation() ?: return toast("Position GPS inconnue.")
        val ok = RadarCore.removeNearestMobile(loc.latitude, loc.longitude, 500.0)
        toast(if (ok) "Radar mobile supprimé" else "Aucun radar mobile signalé à moins de 500 m")
        refreshInfo()
    }

    private fun cycleTtl() {
        val cur = prefs.getInt(RadarCore.PREF_TTL_MIN, 180)
        val next = TTL_CHOICES[(TTL_CHOICES.indexOf(cur) + 1).mod(TTL_CHOICES.size)]
        prefs.edit().putInt(RadarCore.PREF_TTL_MIN, next).apply()
        RadarCore.applySettings(this)
        refreshSettingsLabels()
    }

    private fun cycleMargin() {
        val cur = prefs.getInt(RadarCore.PREF_MARGIN, 3)
        val next = MARGIN_CHOICES[(MARGIN_CHOICES.indexOf(cur) + 1).mod(MARGIN_CHOICES.size)]
        prefs.edit().putInt(RadarCore.PREF_MARGIN, next).apply()
        RadarCore.applySettings(this)
        refreshSettingsLabels()
    }

    private fun onPreciseToggled(checked: Boolean) {
        if (!checked) {
            prefs.edit().putBoolean(RadarCore.PREF_PRECISE, false).apply()
            RadarCore.applySettings(this)
            return
        }
        AlertDialog.Builder(this)
            .setTitle("Mode précis")
            .setMessage(
                "En France, signaler l'emplacement précis des radars pendant la conduite est interdit " +
                    "(article R413-15 du Code de la route : jusqu'à 1 500 € d'amende, 6 points et confiscation). " +
                    "N'activez ce mode que si vous roulez dans un pays où c'est autorisé."
            )
            .setPositiveButton("Activer") { _, _ ->
                prefs.edit().putBoolean(RadarCore.PREF_PRECISE, true).apply()
                RadarCore.applySettings(this)
            }
            .setNegativeButton("Annuler") { _, _ -> preciseSwitch.isChecked = false }
            .setOnCancelListener { preciseSwitch.isChecked = false }
            .show()
    }

    // ------------------------------------------------------------------ base de radars

    private fun downloadRadars() {
        toast("Téléchargement de la liste des radars…")
        Thread {
            val tmp = File(filesDir, "radars_download.tmp")
            val result = try {
                val conn = URL(RadarCore.RADARS_URL).openConnection() as HttpURLConnection
                conn.connectTimeout = 15_000
                conn.readTimeout = 30_000
                conn.instanceFollowRedirects = true
                conn.setRequestProperty("User-Agent", "RadarAlerte/1.0 (Android)")
                if (conn.responseCode !in 200..299) throw Exception("HTTP ${conn.responseCode}")
                conn.inputStream.use { input -> tmp.outputStream().use { input.copyTo(it) } }
                conn.disconnect()
                installRadarFile(tmp)
            } catch (e: Exception) {
                "Échec du téléchargement : ${e.message}. Vous pouvez importer un fichier CSV à la place."
            }
            runOnUiThread { toast(result); refreshInfo() }
        }.start()
    }

    private fun importCsv() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
            putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("text/csv", "text/comma-separated-values", "text/plain", "application/octet-stream"))
        }
        startActivityForResult(intent, REQ_IMPORT)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        val uri: Uri = data?.data ?: return
        if (requestCode != REQ_IMPORT || resultCode != Activity.RESULT_OK) return
        Thread {
            val tmp = File(filesDir, "radars_import.tmp")
            val result = try {
                contentResolver.openInputStream(uri)!!.use { input -> tmp.outputStream().use { input.copyTo(it) } }
                installRadarFile(tmp)
            } catch (e: Exception) {
                "Import impossible : ${e.message}"
            }
            runOnUiThread { toast(result); refreshInfo() }
        }.start()
    }

    /** Charge le fichier dans le moteur ; ne remplace l'ancienne base que s'il est valide. */
    private fun installRadarFile(tmp: File): String {
        val n = RadarCore.loadRadars(tmp.absolutePath)
        return if (n > 0) {
            tmp.renameTo(RadarCore.radarsFile(this))
            "$n radars chargés"
        } else {
            tmp.delete() // l'ancienne base reste en place dans le moteur
            "Fichier non reconnu (colonnes latitude/longitude introuvables)"
        }
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
}
