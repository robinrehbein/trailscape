package de.trailscape.wear.complication

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon
import androidx.wear.watchface.complications.data.ComplicationData
import androidx.wear.watchface.complications.data.ComplicationType
import androidx.wear.watchface.complications.data.LongTextComplicationData
import androidx.wear.watchface.complications.data.MonochromaticImage
import androidx.wear.watchface.complications.data.PlainComplicationText
import androidx.wear.watchface.complications.data.RangedValueComplicationData
import androidx.wear.watchface.complications.data.ShortTextComplicationData
import androidx.wear.watchface.complications.datasource.ComplicationRequest
import androidx.wear.watchface.complications.datasource.SuspendingComplicationDataSourceService
import de.trailscape.core.GlanceAnzeige
import de.trailscape.core.glanceFuerHeute
import de.trailscape.wear.MainActivity
import de.trailscape.wear.R
import de.trailscape.wear.glance.GlanceStore
import de.trailscape.wear.glance.GlanceTexts
import de.trailscape.wear.localized
import java.time.LocalDate

/**
 * Komplikation fuers Zifferblatt: Serie und Wochenziel in drei Formen.
 *
 * - **SHORT_TEXT**: die Serie, z. B. „5 W", mit „Serie" als Titel.
 * - **RANGED_VALUE**: Fortschritt zum Wochenziel (gefahrene km von Ziel-km);
 *   der Text im Feld ist die gefahrene Menge.
 * - **LONG_TEXT**: „5 Wochen" mit „42 von 60 km" darunter, fuer breite Felder.
 *
 * Ein Dienst fuer alle drei: Welche Form ein Feld braucht, bestimmt das
 * Zifferblatt; die Zahlen sind dieselben. Ein Tipp oeffnet die Uhr-App.
 *
 * ## Woher die Zahlen und was davon gilt
 * Aus dem lokalen Speicher ([GlanceStore]); was davon *heute* noch stimmt,
 * entscheidet [glanceFuerHeute] — ein Stand aus der Vorwoche zeigt keine
 * erledigte Woche. Ohne jeden Stand: „–" (und die Beschreibung sagt, dass
 * noch Daten fehlen), statt eine Null zu behaupten.
 *
 * ## Aktualisierung
 * Kein Abfragetakt (`UPDATE_PERIOD_SECONDS` = 0 im Manifest): Neu gezeichnet
 * wird auf Anforderung ([de.trailscape.wear.glance.GlanceUpdater]) — nach einem
 * neuen Stand vom Telefon und beim Datumswechsel.
 */
class WochenComplicationService : SuspendingComplicationDataSourceService() {

    override fun getPreviewData(type: ComplicationType): ComplicationData? {
        val context = applicationContext.localized()
        return daten(context, type, VORSCHAU)
    }

    override suspend fun onComplicationRequest(request: ComplicationRequest): ComplicationData? {
        val context = applicationContext.localized()
        val stand = GlanceStore.laden(applicationContext) ?: GlanceStore.nachziehen(applicationContext)
        val anzeige = glanceFuerHeute(stand, LocalDate.now())
        return daten(context, request.complicationType, anzeige)
    }

    private fun daten(context: Context, type: ComplicationType, anzeige: GlanceAnzeige?): ComplicationData? {
        val tap = oeffnenIntent(context)
        val symbol = MonochromaticImage.Builder(Icon.createWithResource(context, R.drawable.ic_complication_streak)).build()
        val beschreibung = PlainComplicationText.Builder(
            if (anzeige != null) GlanceTexts.beschreibung(context, anzeige) else context.getString(R.string.wear_glance_empty),
        ).build()
        val leer = PlainComplicationText.Builder(context.getString(R.string.wear_glance_empty_short)).build()

        return when (type) {
            ComplicationType.SHORT_TEXT -> ShortTextComplicationData.Builder(
                text = if (anzeige != null) PlainComplicationText.Builder(GlanceTexts.serieKurz(context, anzeige)).build() else leer,
                contentDescription = beschreibung,
            )
                .setTitle(PlainComplicationText.Builder(context.getString(R.string.wear_glance_title_short)).build())
                .setMonochromaticImage(symbol)
                .setTapAction(tap)
                .build()

            ComplicationType.RANGED_VALUE -> {
                // Ohne Ziel gibt es nichts zu fuellen: leerer Bogen, Text zeigt die Kilometer.
                val max = (anzeige?.zielKm ?: 1).coerceAtLeast(1).toFloat()
                val wert = (anzeige?.wochenKm?.toFloat() ?: 0f).coerceIn(0f, max)
                RangedValueComplicationData.Builder(
                    value = wert,
                    min = 0f,
                    max = max,
                    contentDescription = beschreibung,
                )
                    .setText(if (anzeige != null) PlainComplicationText.Builder(GlanceTexts.km(context, anzeige.wochenKm)).build() else leer)
                    .setMonochromaticImage(symbol)
                    .setTapAction(tap)
                    .build()
            }

            ComplicationType.LONG_TEXT -> LongTextComplicationData.Builder(
                text = PlainComplicationText.Builder(
                    if (anzeige != null) GlanceTexts.fortschritt(context, anzeige) else context.getString(R.string.wear_glance_empty),
                ).build(),
                contentDescription = beschreibung,
            )
                .setTitle(
                    if (anzeige != null) PlainComplicationText.Builder(GlanceTexts.serieLang(context, anzeige)).build() else null,
                )
                .setMonochromaticImage(symbol)
                .setTapAction(tap)
                .build()

            else -> null
        }
    }

    private fun oeffnenIntent(context: Context): PendingIntent = PendingIntent.getActivity(
        context,
        0,
        Intent(context, MainActivity::class.java).setFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private companion object {
        /** Beispielwerte fuer die Zifferblatt-Auswahl: 5 Wochen, 42 von 60 km. */
        val VORSCHAU = GlanceAnzeige(
            serieWochen = 5,
            wocheErledigt = true,
            serieInGefahr = false,
            wochenKm = 42.0,
            zielKm = 60,
            veraltet = false,
        )
    }
}
