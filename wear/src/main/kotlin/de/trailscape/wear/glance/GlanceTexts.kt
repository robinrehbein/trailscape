package de.trailscape.wear.glance

import android.content.Context
import de.trailscape.core.GlanceAnzeige
import de.trailscape.core.HeuteVorschlag
import de.trailscape.wear.R
import java.util.Locale
import kotlin.math.floor

/**
 * Die Texte von Kachel und Komplikation aus einer [GlanceAnzeige].
 *
 * An einer Stelle, damit beide dieselben Worte und Zahlen zeigen. Der Kontext
 * sollte einer in der Uhrsprache sein (`Context.localized()` aus `WearLocale.kt`).
 */
object GlanceTexts {

    /** Ab dieser Menge werden Kilometer ohne Nachkommastelle gezeigt; darunter eine. */
    private const val GANZE_KM_AB = 10.0

    /** Toleranz gegen Gleitkomma-Fehler beim Abrunden (42,0 darf nicht 41 werden). */
    private const val EPS = 1e-9

    /**
     * Kilometer: ganz ab 10, sonst eine Nachkommastelle, im Zahlformat der
     * Sprache. Es wird abgerundet, damit „60 von 60 km" erst bei erreichtem
     * Ziel dasteht.
     */
    fun km(context: Context, km: Double): String {
        val locale = locale(context)
        return if (km >= GANZE_KM_AB) {
            String.format(locale, "%d", floor(km + EPS).toLong())
        } else {
            String.format(locale, "%.1f", floor(km * 10.0 + EPS) / 10.0)
        }
    }

    /** „5 Wochen" / „1 Woche". */
    fun serieLang(context: Context, a: GlanceAnzeige): String =
        context.resources.getQuantityString(R.plurals.wear_glance_weeks, a.serieWochen, a.serieWochen)

    /** „5 W", fuer das enge Feld einer Komplikation. */
    fun serieKurz(context: Context, a: GlanceAnzeige): String =
        context.getString(R.string.wear_glance_weeks_short, a.serieWochen)

    /** „42 von 60 km" oder, ohne Ziel, „42 km diese Woche". */
    fun fortschritt(context: Context, a: GlanceAnzeige): String {
        val gefahren = km(context, a.wochenKm)
        val ziel = a.zielKm
        return if (ziel != null) {
            context.getString(R.string.wear_glance_km_of, gefahren, km(context, ziel.toDouble()))
        } else {
            context.getString(R.string.wear_glance_km_only, gefahren)
        }
    }

    /** Eine ruhige Statuszeile: Ziel erreicht, geschafft, Serie in Gefahr oder Aufforderung zum Start. */
    fun status(context: Context, a: GlanceAnzeige): String = context.getString(
        when {
            a.zielErreicht -> R.string.wear_glance_status_goal
            a.wocheErledigt -> R.string.wear_glance_status_done
            a.serieInGefahr -> R.string.wear_glance_status_risk
            else -> R.string.wear_glance_status_start
        },
    )

    /** Fuer Screenreader: „Serie 5 Wochen, 42 von 60 km". */
    fun beschreibung(context: Context, a: GlanceAnzeige): String =
        context.getString(R.string.wear_glance_cd, serieLang(context, a), fortschritt(context, a))

    /**
     * Die Zeile zur Einheit des Tages: „Heute drinnen · 60 min", „Heute
     * draußen" oder „Einheit erledigt ✓" — aus dem Vorschlag, den „Heute" auf
     * dem Telefon zuletzt gezeigt hat (samt Wahl des Nutzers).
     */
    fun heute(context: Context, v: HeuteVorschlag): String {
        if (v.erledigt) return context.getString(R.string.idee02_wear_today_done)
        val min = v.minuten
        return when {
            v.drinnen && min != null -> context.getString(R.string.idee02_wear_today_indoor_min, min)
            v.drinnen -> context.getString(R.string.idee02_wear_today_indoor)
            min != null -> context.getString(R.string.idee02_wear_today_outdoor_min, min)
            else -> context.getString(R.string.idee02_wear_today_outdoor)
        }
    }

    private fun locale(context: Context): Locale =
        context.resources.configuration.locales[0] ?: Locale.getDefault()
}
