package fr.radaralerte

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.bluetooth.BluetoothDevice
import android.companion.AssociationInfo
import android.companion.AssociationRequest
import android.companion.BluetoothDeviceFilter
import android.companion.CompanionDeviceManager
import android.content.IntentSender
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Build
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
        const val REQ_CAR = 3
        const val REQ_FG_LOCATION = 4
        const val REQ_BG_LOCATION = 5
        val TTL_CHOICES = intArrayOf(60, 120, 180, 360, 0) // minutes, 0 = permanent
        val MARGIN_CHOICES = intArrayOf(0, 3, 5, 8)
    }

    private val prefs by lazy { getSharedPreferences(RadarCore.PREFS, Context.MODE_PRIVATE) }

    private lateinit var updateSub: TextView
    private lateinit var ttlSub: TextView
    private lateinit var marginSub: TextView
    private lateinit var mobilesSub: TextView
    private lateinit var carSub: TextView
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

        // En voiture
        val (carRow, cSub) = settingRow("Démarrage automatique", "") { onCarRowClicked() }
        val (autoRow, _) = settingRow("Afficher sur Android Auto", "Comment activer l'écran de la voiture") { showAndroidAutoHelp() }
        carSub = cSub
        content.addView(sectionTitle("En voiture"))
        content.addView(card(carRow, autoRow))

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
        val car = CarAutoStart.carName(this)
        carSub.text = when {
            car == null -> "Désactivé · appuyez pour choisir votre voiture"
            Build.VERSION.SDK_INT >= 30 && !CarAutoStart.hasBackgroundLocation(this) ->
                "$car · autorisation « Toujours » manquante"
            else -> "Activé avec $car"
        }
        carSub.visibility = android.view.View.VISIBLE
    }

    // ------------------------------------------------------------------ voiture

    private fun onCarRowClicked() {
        val car = CarAutoStart.carName(this)
        if (car == null) {
            AlertDialog.Builder(this)
                .setTitle("Démarrage automatique")
                .setMessage(
                    "Choisissez votre voiture dans la liste des appareils Bluetooth. La surveillance " +
                        "démarrera toute seule quand le téléphone s'y connecte (avec ou sans Android Auto) " +
                        "et s'arrêtera quand vous la quittez.\n\nLe Bluetooth du téléphone et de la voiture " +
                        "doit être allumé."
                )
                .setPositiveButton("Choisir ma voiture") { _, _ -> chooseCar() }
                .setNegativeButton("Annuler", null)
                .show()
            return
        }
        if (Build.VERSION.SDK_INT >= 30 && !CarAutoStart.hasBackgroundLocation(this)) {
            askBackgroundLocation()
            return
        }
        AlertDialog.Builder(this)
            .setTitle(car)
            .setMessage("La surveillance démarre automatiquement quand le téléphone se connecte à cette voiture.")
            .setPositiveButton("Changer de voiture") { _, _ -> removeCar(); chooseCar() }
            .setNegativeButton("Désactiver") { _, _ -> removeCar(); refresh() }
            .setNeutralButton("Fermer", null)
            .show()
    }

    private fun chooseCar() {
        val cdm = getSystemService(CompanionDeviceManager::class.java)
            ?: return toast("Fonction indisponible sur ce téléphone")
        val request = AssociationRequest.Builder()
            .addDeviceFilter(BluetoothDeviceFilter.Builder().build())
            .setSingleDevice(false)
            .build()
        val launch = { sender: IntentSender ->
            try {
                startIntentSenderForResult(sender, REQ_CAR, null, 0, 0, 0)
            } catch (e: Exception) {
                toast("Impossible d'ouvrir la liste des appareils")
            }
        }
        try {
            cdm.associate(request, object : CompanionDeviceManager.Callback() {
                @Deprecated("Deprecated in Java")
                override fun onDeviceFound(chooserLauncher: IntentSender) { launch(chooserLauncher) }

                override fun onAssociationPending(intentSender: IntentSender) { launch(intentSender) }

                override fun onAssociationCreated(associationInfo: AssociationInfo) {
                    val mac = associationInfo.deviceMacAddress?.toString()?.uppercase() ?: return
                    saveCar(mac, associationInfo.displayName?.toString() ?: "Ma voiture")
                }

                override fun onFailure(error: CharSequence?) {
                    toast("Aucune voiture choisie")
                }
            }, null)
        } catch (e: Exception) {
            toast("Fonction indisponible sur ce téléphone")
        }
    }

    private fun saveCar(address: String, name: String) {
        if (CarAutoStart.carAddress(this).equals(address, ignoreCase = true)) return
        prefs.edit()
            .putString(CarAutoStart.PREF_CAR_ADDRESS, address)
            .putString(CarAutoStart.PREF_CAR_NAME, name)
            .apply()
        if (Build.VERSION.SDK_INT >= 31) {
            try {
                @Suppress("DEPRECATION")
                getSystemService(CompanionDeviceManager::class.java)?.startObservingDevicePresence(address)
            } catch (_: Exception) {}
        }
        refresh()
        askBackgroundLocation()
    }

    private fun removeCar() {
        val address = CarAutoStart.carAddress(this) ?: return
        val cdm = getSystemService(CompanionDeviceManager::class.java)
        if (Build.VERSION.SDK_INT >= 31) {
            try {
                @Suppress("DEPRECATION")
                cdm?.stopObservingDevicePresence(address)
            } catch (_: Exception) {}
        }
        try {
            @Suppress("DEPRECATION")
            cdm?.disassociate(address)
        } catch (_: Exception) {}
        prefs.edit().remove(CarAutoStart.PREF_CAR_ADDRESS).remove(CarAutoStart.PREF_CAR_NAME).apply()
    }

    private fun askBackgroundLocation() {
        val fine = Manifest.permission.ACCESS_FINE_LOCATION
        if (checkSelfPermission(fine) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(fine, Manifest.permission.ACCESS_COARSE_LOCATION), REQ_FG_LOCATION)
            return
        }
        if (CarAutoStart.hasBackgroundLocation(this)) {
            toast("Démarrage automatique activé")
            refresh()
            return
        }
        AlertDialog.Builder(this)
            .setTitle("Une dernière autorisation")
            .setMessage(
                "Pour démarrer toute seule quand vous montez en voiture, l'appli doit pouvoir lire votre " +
                    "position même quand elle est fermée.\n\nSur l'écran suivant, choisissez " +
                    "« Toujours autoriser »."
            )
            .setPositiveButton("Continuer") { _, _ ->
                requestPermissions(arrayOf(Manifest.permission.ACCESS_BACKGROUND_LOCATION), REQ_BG_LOCATION)
            }
            .setNegativeButton("Plus tard", null)
            .show()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        when (requestCode) {
            REQ_FG_LOCATION ->
                if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
                    askBackgroundLocation()
                }
            REQ_BG_LOCATION -> {
                if (CarAutoStart.hasBackgroundLocation(this)) toast("Démarrage automatique activé")
                refresh()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun showAndroidAutoHelp() {
        AlertDialog.Builder(this)
            .setTitle("Radar Alerte sur Android Auto")
            .setMessage(
                "Comme l'appli ne vient pas du Play Store, Android Auto la cache tant que vous n'avez pas " +
                    "activé une option (à faire une seule fois) :\n\n" +
                    "1. Ouvrez les réglages d'Android Auto sur le téléphone (Paramètres → Appareils connectés → " +
                    "Android Auto, ou recherchez « Android Auto » dans les Paramètres).\n\n" +
                    "2. Tout en bas, appuyez 10 fois sur « Version », puis acceptez : le mode développeur s'active.\n\n" +
                    "3. Menu ⋮ en haut à droite → « Paramètres pour les développeurs » → cochez « Sources inconnues ».\n\n" +
                    "4. Rebranchez le téléphone à la voiture : Radar Alerte apparaît dans les applis. Si ce n'est " +
                    "pas le cas : réglages Android Auto → « Personnaliser le lanceur d'applications ».\n\n" +
                    "Ouvrir Radar Alerte sur l'écran de la voiture démarre aussi la surveillance."
            )
            .setPositiveButton("Compris", null)
            .show()
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
        if (requestCode == REQ_CAR) {
            if (resultCode == Activity.RESULT_OK && data != null) onCarChosen(data)
            return
        }
        val uri = data?.data ?: return
        if (requestCode != REQ_IMPORT || resultCode != Activity.RESULT_OK) return
        updateSub.text = "Import…"
        RadarDb.importFile(this, uri) { msg -> toast(msg); refresh() }
    }

    private fun onCarChosen(data: Intent) {
        if (Build.VERSION.SDK_INT >= 33) {
            val info = data.getParcelableExtra(CompanionDeviceManager.EXTRA_ASSOCIATION, AssociationInfo::class.java)
            val mac = info?.deviceMacAddress?.toString()?.uppercase() ?: return
            saveCar(mac, info.displayName?.toString() ?: "Ma voiture")
        } else {
            @Suppress("DEPRECATION")
            val device: BluetoothDevice = data.getParcelableExtra(CompanionDeviceManager.EXTRA_DEVICE) ?: return
            val name = try { device.name } catch (_: SecurityException) { null } ?: "Ma voiture"
            saveCar(device.address, name)
        }
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
