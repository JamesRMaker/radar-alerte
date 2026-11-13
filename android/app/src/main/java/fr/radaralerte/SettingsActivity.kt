package fr.radaralerte

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.location.LocationManager
import android.os.Bundle
import android.view.Gravity
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast

/** Réglages : base de radars, mode d'alerte, radars signalés. */
class SettingsActivity : Activity() {

    private companion object {
        const val REQ_IMPORT = 2
        val TTL_CHOICES = intArrayOf(60, 120, 180, 360, 0) // minutes, 0 = permanent
        val MARGIN_CHOICES = intArrayOf(0, 3, 5, 8)
    }

    private val prefs by lazy { getSharedPreferences(RadarCore.PREFS, Context.MODE_PRIVATE) }

    private lateinit var updateSub: TextView
    private lateinit var ttlSub: TextView
    private lateinit var marginSub: TextView
    private lateinit var mobilesSub: TextView
    private lateinit var preciseSwitch: Switch
    private var updating = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        darkSystemBars()
        RadarCore.ensureLoaded(this)
        setContentView(buildUi())
        refresh()
    }

    private fun buildUi(): ScrollView {
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            padForSystemBars(dp(16))
        }

        // Barre du haut
        val back = ImageView(this).apply {
            setImageResource(R.drawable.ic_back)
            setColorFilter(Palette.TEXT)
            setPadding(dp(10), dp(10), dp(10), dp(10))
            background = ripple(null, oval(Color.WHITE))
            contentDescription = "Retour"
            setOnClickListener { finish() }
        }
        content.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(back, LinearLayout.LayoutParams(dp(44), dp(44)))
            addView(label(22f, font = Fonts.medium).apply {
                text = "Réglages"
                setPadding(dp(8), 0, 0, 0)
            })
        })

        // Base de radars
        val (updateRow, uSub) = settingRow("Mettre à jour la base de radars", "") { update() }
        val (importRow, _) = settingRow("Importer un fichier CSV", "Pour une autre source ou un autre pays") { pickFile() }
        updateSub = uSub
        content.addView(sectionTitle("Radars fixes"))
        content.addView(card(updateRow, importRow))

        // Alertes
        preciseSwitch = Switch(this).apply {
            isChecked = prefs.getBoolean(RadarCore.PREF_PRECISE, false)
            setOnCheckedChangeListener { _, checked -> onPreciseToggled(checked) }
        }
        val (preciseText, _) = settingRow("Mode précis", "Annonce le type de radar et la distance exacte")
        preciseText.setPadding(0, 0, 0, 0)
        val preciseRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(64)
            setPadding(dp(20), dp(14), dp(16), dp(14))
            addView(preciseText, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            addView(preciseSwitch)
            background = ripple(null, rounded(Color.WHITE, 0f))
            setOnClickListener { preciseSwitch.toggle() }
        }
        val (marginRow, mSub) = settingRow("Alerte de vitesse", "") { cycleMargin() }
        marginSub = mSub
        content.addView(sectionTitle("Alertes"))
        content.addView(card(preciseRow, marginRow))

        // Radars signalés
        val (ttlRow, tSub) = settingRow("Durée d'un radar signalé", "") { cycleTtl() }
        val (removeRow, rSub) = settingRow("Retirer le radar signalé le plus proche", "") { removeNearest() }
        ttlSub = tSub
        mobilesSub = rSub
        content.addView(sectionTitle("Radars signalés"))
        content.addView(card(ttlRow, removeRow))

        content.addView(label(12f, Palette.MUTED).apply {
            setLineSpacing(0f, 1.25f)
            setPadding(dp(8), dp(24), dp(8), dp(8))
            text = "En France, l'appli annonce des « zones de danger » (500 m en ville, 2 km sur route, " +
                "4 km sur autoroute) sans donner l'emplacement exact des radars, comme la loi l'impose. " +
                "Le mode précis est réservé à l'étranger.\n\nSource des radars fixes : data.gouv.fr " +
                "(Ministère de l'Intérieur)."
        })

        return ScrollView(this).apply {
            setBackgroundColor(Palette.BG)
            isFillViewport = true
            addView(content)
        }
    }

    private fun refresh() {
        val n = RadarDb.radarCount()
        if (!updating) {
            updateSub.text = if (n == 0) "Aucune base chargée"
            else "${String.format(java.util.Locale.FRANCE, "%,d", n)} radars · ${RadarDb.lastUpdate(this)}"
        }
        val ttl = prefs.getInt(RadarCore.PREF_TTL_MIN, 180)
        ttlSub.text = if (ttl == 0) "Permanent" else "${ttl / 60} h"
        ttlSub.visibility = android.view.View.VISIBLE
        val margin = prefs.getInt(RadarCore.PREF_MARGIN, 3)
        marginSub.text = if (margin == 0) "Dès la limite dépassée" else "Au-delà de la limite + $margin km/h"
        marginSub.visibility = android.view.View.VISIBLE
        val m = RadarDb.mobileCount()
        mobilesSub.text = if (m == 0) "Aucun radar signalé" else "$m radar(s) signalé(s) en mémoire"
        mobilesSub.visibility = android.view.View.VISIBLE
        updateSub.visibility = android.view.View.VISIBLE
    }

    private fun update() {
        if (updating) return
        updating = true
        updateSub.text = "Téléchargement…"
        RadarDb.download(this) { msg ->
            updating = false
            toast(msg)
            refresh()
        }
    }

    private fun pickFile() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
        }
        startActivityForResult(intent, REQ_IMPORT)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        val uri = data?.data ?: return
        if (requestCode != REQ_IMPORT || resultCode != Activity.RESULT_OK) return
        updateSub.text = "Import…"
        RadarDb.importFile(this, uri) { msg -> toast(msg); refresh() }
    }

    private fun cycleTtl() {
        val cur = prefs.getInt(RadarCore.PREF_TTL_MIN, 180)
        val next = TTL_CHOICES[(TTL_CHOICES.indexOf(cur) + 1).mod(TTL_CHOICES.size)]
        prefs.edit().putInt(RadarCore.PREF_TTL_MIN, next).apply()
        RadarCore.applySettings(this)
        refresh()
    }

    private fun cycleMargin() {
        val cur = prefs.getInt(RadarCore.PREF_MARGIN, 3)
        val next = MARGIN_CHOICES[(MARGIN_CHOICES.indexOf(cur) + 1).mod(MARGIN_CHOICES.size)]
        prefs.edit().putInt(RadarCore.PREF_MARGIN, next).apply()
        RadarCore.applySettings(this)
        refresh()
    }

    private fun removeNearest() {
        val loc = RadarService.lastLocation ?: run {
            if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) null
            else try {
                (getSystemService(Context.LOCATION_SERVICE) as LocationManager)
                    .getLastKnownLocation(LocationManager.GPS_PROVIDER)
            } catch (e: SecurityException) {
                null
            }
        }
        if (loc == null) return toast("Position GPS inconnue")
        val ok = RadarCore.removeNearestMobile(loc.latitude, loc.longitude, 500.0)
        toast(if (ok) "Radar signalé retiré" else "Aucun radar signalé à moins de 500 m")
        refresh()
    }

    private fun onPreciseToggled(checked: Boolean) {
        if (!checked) {
            prefs.edit().putBoolean(RadarCore.PREF_PRECISE, false).apply()
            RadarCore.applySettings(this)
            return
        }
        AlertDialog.Builder(this)
            .setTitle("Activer le mode précis ?")
            .setMessage(
                "En France, signaler l'emplacement exact des radars en roulant est interdit " +
                    "(jusqu'à 1 500 € d'amende et 6 points). À n'utiliser qu'à l'étranger."
            )
            .setPositiveButton("Activer") { _, _ ->
                prefs.edit().putBoolean(RadarCore.PREF_PRECISE, true).apply()
                RadarCore.applySettings(this)
            }
            .setNegativeButton("Annuler") { _, _ -> preciseSwitch.isChecked = false }
            .setOnCancelListener { preciseSwitch.isChecked = false }
            .show()
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
}
