package fr.radaralerte

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.view.View
import android.widget.RemoteViews
import org.json.JSONObject
import java.util.Locale

/**
 * Widget d'écran d'accueil : panneau de limitation + distance (au radar en mode précis,
 * à la fin de la zone de danger sinon). Mis à jour par RadarService à chaque position.
 */
class RadarWidget : AppWidgetProvider() {

    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        lastKey = ""
        render(context, lastData)
    }

    companion object {
        @Volatile private var lastData: JSONObject? = null
        @Volatile private var lastKey = ""

        /** `data` = JSON du moteur Rust, ou null quand la surveillance est arrêtée. */
        fun render(ctx: Context, data: JSONObject?) {
            lastData = data
            val manager = AppWidgetManager.getInstance(ctx)
            val ids = manager.getAppWidgetIds(ComponentName(ctx, RadarWidget::class.java))
            if (ids.isEmpty()) return

            val limit = if (data == null || data.isNull("limit")) null else data.optInt("limit")
            val dist = if (data == null || data.isNull("dist")) null else data.optDouble("dist")
            val title = data?.optString("title").orEmpty()
            val danger = data?.optBoolean("danger") == true

            val big: String
            val small: String
            when {
                data == null -> { big = "—"; small = "Surveillance arrêtée" }
                !data.optBoolean("gps", true) -> { big = "GPS…"; small = "Signal imprécis" }
                dist != null && title.isNotEmpty() -> { big = formatDistance(dist); small = title }
                else -> {
                    big = if (data.isNull("speed")) "—" else "${data.optInt("speed")} km/h"
                    small = "Aucun radar à l'approche"
                }
            }
            val key = "$limit|$big|$small|$danger"
            if (key == lastKey) return
            lastKey = key

            val views = RemoteViews(ctx.packageName, R.layout.widget_radar).apply {
                setTextViewText(R.id.w_distance, big)
                setTextViewText(R.id.w_title, small)
                if (limit != null) {
                    setTextViewText(R.id.w_limit, limit.toString())
                    setViewVisibility(R.id.w_limit, View.VISIBLE)
                } else {
                    setViewVisibility(R.id.w_limit, View.GONE)
                }
                setInt(R.id.w_root, "setBackgroundResource",
                    if (danger) R.drawable.widget_bg_danger else R.drawable.widget_bg)
                setOnClickPendingIntent(R.id.w_root, PendingIntent.getActivity(
                    ctx, 0, Intent(ctx, MainActivity::class.java),
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                ))
            }
            manager.updateAppWidget(ids, views)
        }

        fun formatDistance(m: Double): String =
            if (m < 1000) "${(Math.round(m / 10.0) * 10)} m"
            else String.format(Locale.FRANCE, "%.1f km", m / 1000.0)
    }
}
