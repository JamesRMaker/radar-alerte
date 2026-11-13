package fr.radaralerte

import android.app.Activity
import android.net.Uri
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.text.DateFormat
import java.util.Date

/** Téléchargement / import de la base de radars fixes (le chargement lui-même est fait en Rust). */
object RadarDb {

    fun radarCount(): Int = JSONObject(RadarCore.stats()).optInt("radars")
    fun mobileCount(): Int = JSONObject(RadarCore.stats()).optInt("mobiles")

    fun lastUpdate(activity: Activity): String {
        val f = RadarCore.radarsFile(activity)
        return if (f.exists()) DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(f.lastModified())) else "jamais"
    }

    /** Télécharge la liste officielle ; `done` est appelé sur le thread principal avec un message. */
    fun download(activity: Activity, done: (String) -> Unit) {
        Thread {
            val tmp = File(activity.filesDir, "radars_download.tmp")
            val result = try {
                val conn = URL(RadarCore.RADARS_URL).openConnection() as HttpURLConnection
                conn.connectTimeout = 15_000
                conn.readTimeout = 30_000
                conn.instanceFollowRedirects = true
                conn.setRequestProperty("User-Agent", "RadarAlerte/1.0 (Android)")
                if (conn.responseCode !in 200..299) throw Exception("erreur ${conn.responseCode}")
                conn.inputStream.use { input -> tmp.outputStream().use { input.copyTo(it) } }
                conn.disconnect()
                install(activity, tmp)
            } catch (e: Exception) {
                "Téléchargement impossible. Vérifiez votre connexion."
            }
            activity.runOnUiThread { done(result) }
        }.start()
    }

    fun importFile(activity: Activity, uri: Uri, done: (String) -> Unit) {
        Thread {
            val tmp = File(activity.filesDir, "radars_import.tmp")
            val result = try {
                activity.contentResolver.openInputStream(uri)!!.use { input ->
                    tmp.outputStream().use { input.copyTo(it) }
                }
                install(activity, tmp)
            } catch (e: Exception) {
                "Import impossible : ${e.message}"
            }
            activity.runOnUiThread { done(result) }
        }.start()
    }

    /** Ne remplace l'ancienne base que si le nouveau fichier est valide. */
    private fun install(activity: Activity, tmp: File): String {
        val n = RadarCore.loadRadars(tmp.absolutePath)
        return if (n > 0) {
            tmp.renameTo(RadarCore.radarsFile(activity))
            "Base à jour : $n radars"
        } else {
            tmp.delete()
            "Fichier non reconnu"
        }
    }
}
