package de.trailscape.wear.glance

import android.content.ComponentName
import android.content.Context
import androidx.wear.tiles.TileService
import androidx.wear.watchface.complications.datasource.ComplicationDataSourceUpdateRequester
import de.trailscape.wear.complication.WochenComplicationService
import de.trailscape.wear.tile.WochenTileService

/**
 * Stoesst Kachel und Komplikation an, ihre Daten neu zu holen — nach einem
 * neuen Stand vom Telefon und beim Datums-, Zeitzonen- oder Sprachwechsel
 * ([TagWechselReceiver]).
 *
 * Bewusst **kein** Takt: Beide Anzeigen lesen nur den lokalen Speicher
 * ([GlanceStore]); neu gezeichnet wird, wenn sich daran oder am Kalendertag
 * etwas aendert. Fehler (kein Zifferblatt mit Komplikation, keine Kachel
 * angelegt) sind der Normalfall und bleiben still.
 */
object GlanceUpdater {

    fun aktualisiereAnzeigen(context: Context) {
        val app = context.applicationContext
        runCatching { TileService.getUpdater(app).requestUpdate(WochenTileService::class.java) }
        runCatching {
            ComplicationDataSourceUpdateRequester
                .create(app, ComponentName(app, WochenComplicationService::class.java))
                .requestUpdateAll()
        }
    }
}
