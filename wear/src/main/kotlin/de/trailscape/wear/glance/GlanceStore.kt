package de.trailscape.wear.glance

import android.content.Context
import android.net.Uri
import com.google.android.gms.wearable.Wearable
import de.trailscape.core.PFAD_WOCHE
import de.trailscape.core.WochenGlance
import de.trailscape.core.dekodiereWochenGlance
import de.trailscape.core.kodiereWochenGlance
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Der zuletzt vom Telefon empfangene Wochenstand ([WochenGlance]) — lokal auf
 * der Uhr, damit Kachel und Komplikation auch ohne Verbindung etwas zeigen.
 *
 * ## Warum SharedPreferences
 * Eine einzige kleine Zeichenkette (der Draht-JSON selbst); ein DataStore
 * waere hier mehr Aufwand als Nutzen. Gespeichert wird **nur, was sich
 * dekodieren laesst**: Eine kaputte oder von einer neueren Telefon-App
 * stammende Nutzlast ueberschreibt den letzten guten Stand nicht.
 *
 * ## Warum [nachziehen]
 * Ein DataItem, das schon vor der Installation der Uhr-App im Data Layer lag,
 * loest kein `onDataChanged` aus. Fehlt noch jeder Stand, fragt die Kachel
 * bzw. Komplikation den Data Layer deshalb einmal aktiv ab.
 */
object GlanceStore {

    private const val PREFS = "trailscape_wear_glance"
    private const val KEY_STAND = "stand"

    /** Hoechstens so lange wartet eine Anzeige auf den Data Layer; danach Leerzustand. */
    private const val NACHZIEHEN_TIMEOUT_MS = 2_000L

    /** Der gespeicherte Stand oder `null` (nie etwas empfangen, nicht lesbar). */
    fun laden(context: Context): WochenGlance? = runCatching {
        prefs(context).getString(KEY_STAND, null)
            ?.let { dekodiereWochenGlance(it.toByteArray(Charsets.UTF_8)) }
    }.getOrNull()

    /** Merkt [stand]; `true`, wenn sich dadurch etwas geaendert hat. */
    fun speichern(context: Context, stand: WochenGlance): Boolean = runCatching {
        val neu = kodiereWochenGlance(stand).toString(Charsets.UTF_8)
        val alt = prefs(context).getString(KEY_STAND, null)
        if (alt == neu) {
            false
        } else {
            prefs(context).edit().putString(KEY_STAND, neu).apply()
            true
        }
    }.getOrDefault(false)

    /**
     * Holt das DataItem aktiv aus dem Data Layer, speichert es und gibt den
     * Stand zurueck. `null` ohne Item, ohne Play Services oder nach
     * [NACHZIEHEN_TIMEOUT_MS]. Wirft nie.
     */
    suspend fun nachziehen(context: Context): WochenGlance? = runCatching {
        withTimeoutOrNull(NACHZIEHEN_TIMEOUT_MS) {
            val uri = Uri.Builder().scheme("wear").path(PFAD_WOCHE).build()
            val items = Wearable.getDataClient(context).getDataItems(uri).await()
            try {
                items.firstNotNullOfOrNull { item -> item.data?.let(::dekodiereWochenGlance) }
            } finally {
                items.release()
            }
        }
    }.getOrNull()?.also { speichern(context, it) }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
