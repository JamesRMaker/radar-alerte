package fr.radaralerte

import android.content.Context
import java.io.File

/**
 * Accès au moteur Rust (libradar_alerte.so, crate rust/radar-jni).
 * Toute la logique (base de radars, géométrie, décisions d'alerte, phrases) est en Rust ;
 * ce fichier ne fait que déclarer les fonctions natives.
 */
object RadarCore {
    init {
        System.loadLibrary("radar_alerte")
    }

    /** Charge un CSV de radars fixes. Renvoie le nombre de radars, 0 si aucun, -1 si erreur. */
    @JvmStatic external fun loadRadars(path: String): Int

    /** Fichier de sauvegarde des radars mobiles signalés. Renvoie le nombre chargé. */
    @JvmStatic external fun setStorePath(path: String): Int

    @JvmStatic external fun configure(precise: Boolean, mobileTtlMin: Int, marginKmh: Int)

    /** Valeurs négatives = inconnues. Renvoie un JSON (voir rust/radar-core/src/output.rs). */
    @JvmStatic external fun update(
        lat: Double, lon: Double, speedMps: Double, bearingDeg: Double,
        accuracyM: Double, timeMs: Long
    ): String

    @JvmStatic external fun reportMobile(lat: Double, lon: Double, timeMs: Long): Int
    @JvmStatic external fun removeNearestMobile(lat: Double, lon: Double, radiusM: Double): Boolean
    @JvmStatic external fun stats(): String
    @JvmStatic external fun resetSession()

    // ------------------------------------------------------------------ côté Kotlin

    const val PREFS = "radar_prefs"
    const val PREF_PRECISE = "precise"
    const val PREF_TTL_MIN = "mobile_ttl_min"
    const val PREF_MARGIN = "margin_kmh"

    /** Source officielle : « Liste des radars fixes en France » (data.gouv.fr, Ministère de l'Intérieur). */
    const val RADARS_URL = "https://www.data.gouv.fr/api/1/datasets/r/17f7cfd9-a5fe-4b6a-9f5d-3625feaa396e"

    @Volatile private var loaded = false

    fun radarsFile(ctx: Context) = File(ctx.filesDir, "radars.csv")
    private fun mobilesFile(ctx: Context) = File(ctx.filesDir, "mobiles.csv")

    /** Charge la base et les signalements une seule fois par processus, puis applique les réglages. */
    @Synchronized
    fun ensureLoaded(ctx: Context) {
        if (!loaded) {
            val f = radarsFile(ctx)
            if (f.exists()) loadRadars(f.absolutePath)
            setStorePath(mobilesFile(ctx).absolutePath)
            loaded = true
        }
        applySettings(ctx)
    }

    fun applySettings(ctx: Context) {
        val p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        configure(
            p.getBoolean(PREF_PRECISE, false),
            p.getInt(PREF_TTL_MIN, 180),
            p.getInt(PREF_MARGIN, 3)
        )
    }
}
