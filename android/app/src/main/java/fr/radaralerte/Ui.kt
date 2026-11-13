package fr.radaralerte

import android.app.Activity
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.os.Build
import android.view.Gravity
import android.view.View
import android.view.WindowInsets
import android.widget.LinearLayout
import android.widget.TextView

/** Couleurs et petits outils d'interface partagés (aucune bibliothèque externe). */
object Palette {
    const val BG = 0xFF0E1116.toInt()
    const val SURFACE = 0xFF171C23.toInt()
    const val DIVIDER = 0xFF232A33.toInt()
    const val TEXT = 0xFFF2F4F7.toInt()
    const val MUTED = 0xFF8A94A3.toInt()
    const val ACCENT = 0xFF3D8BFF.toInt()
    const val IDLE = 0xFF2A313C.toInt()
    const val OK = 0xFF30D158.toInt()
    const val WARN = 0xFFFF9F0A.toInt()
    const val DANGER = 0xFFFF453A.toInt()
}

object Fonts {
    val light: Typeface = Typeface.create("sans-serif-light", Typeface.NORMAL)
    val medium: Typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    val regular: Typeface = Typeface.create("sans-serif", Typeface.NORMAL)
}

fun Context.dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

fun rounded(color: Int, radius: Float, strokeColor: Int = 0, strokeWidth: Int = 0) =
    GradientDrawable().apply {
        setColor(color)
        cornerRadius = radius
        if (strokeWidth > 0) setStroke(strokeWidth, strokeColor)
    }

fun oval(color: Int, strokeColor: Int = 0, strokeWidth: Int = 0) =
    GradientDrawable().apply {
        shape = GradientDrawable.OVAL
        setColor(color)
        if (strokeWidth > 0) setStroke(strokeWidth, strokeColor)
    }

/** Fond avec effet d'appui. */
fun ripple(content: Drawable?, mask: Drawable? = content) =
    RippleDrawable(ColorStateList.valueOf(0x33FFFFFF), content, mask ?: rounded(0xFFFFFFFF.toInt(), 0f))

fun Context.label(size: Float, color: Int = Palette.TEXT, font: Typeface = Fonts.regular) =
    TextView(this).apply {
        textSize = size
        setTextColor(color)
        typeface = font
        includeFontPadding = false
    }

/** Ligne de réglage : titre + sous-titre, cliquable. */
fun Context.settingRow(title: String, subtitle: String = "", onClick: (() -> Unit)? = null): Pair<LinearLayout, TextView> {
    val sub = label(13f, Palette.MUTED).apply {
        text = subtitle
        setPadding(0, dp(4), 0, 0)
        visibility = if (subtitle.isEmpty()) View.GONE else View.VISIBLE
    }
    val row = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER_VERTICAL
        minimumHeight = dp(64)
        setPadding(dp(20), dp(14), dp(20), dp(14))
        addView(label(16f).apply { text = title })
        addView(sub)
        if (onClick != null) {
            background = ripple(null, rounded(0xFFFFFFFF.toInt(), 0f))
            isClickable = true
            setOnClickListener { onClick() }
        }
    }
    return row to sub
}

fun Context.divider() = View(this).apply {
    setBackgroundColor(Palette.DIVIDER)
    layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 1).apply {
        marginStart = dp(20)
    }
}

/** Carte arrondie qui regroupe des lignes de réglages. */
fun Context.card(vararg rows: View) = LinearLayout(this).apply {
    orientation = LinearLayout.VERTICAL
    background = rounded(Palette.SURFACE, dp(18).toFloat())
    clipToOutline = true
    rows.forEachIndexed { i, r ->
        if (i > 0) addView(divider())
        addView(r)
    }
}

fun Context.sectionTitle(text: String) = label(13f, Palette.MUTED, Fonts.medium).apply {
    this.text = text.uppercase()
    letterSpacing = 0.08f
    setPadding(dp(8), dp(24), 0, dp(10))
}

/**
 * Android 15 affiche l'appli sous la barre d'état et la barre de navigation :
 * on ajoute la marge nécessaire pour que rien ne passe dessous.
 */
fun View.padForSystemBars(base: Int) {
    setOnApplyWindowInsetsListener { v, insets ->
        val (top, bottom) = if (Build.VERSION.SDK_INT >= 30) {
            val i = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
            i.top to i.bottom
        } else {
            @Suppress("DEPRECATION")
            insets.systemWindowInsetTop to insets.systemWindowInsetBottom
        }
        v.setPadding(base, base + top, base, base + bottom)
        insets
    }
    requestApplyInsets()
}

fun Activity.darkSystemBars() {
    if (Build.VERSION.SDK_INT < 35) {
        @Suppress("DEPRECATION")
        window.statusBarColor = Palette.BG
        @Suppress("DEPRECATION")
        window.navigationBarColor = Palette.BG
    }
}
