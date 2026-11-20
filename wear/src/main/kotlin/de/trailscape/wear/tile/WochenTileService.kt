package de.trailscape.wear.tile

import android.content.Context
import androidx.compose.ui.graphics.toArgb
import androidx.wear.protolayout.ActionBuilders
import androidx.wear.protolayout.ColorBuilders.argb
import androidx.wear.protolayout.DimensionBuilders.degrees
import androidx.wear.protolayout.DimensionBuilders.dp
import androidx.wear.protolayout.DimensionBuilders.expand
import androidx.wear.protolayout.DimensionBuilders.sp
import androidx.wear.protolayout.LayoutElementBuilders
import androidx.wear.protolayout.LayoutElementBuilders.Arc
import androidx.wear.protolayout.LayoutElementBuilders.ArcLine
import androidx.wear.protolayout.LayoutElementBuilders.Box
import androidx.wear.protolayout.LayoutElementBuilders.Column
import androidx.wear.protolayout.LayoutElementBuilders.FontStyle
import androidx.wear.protolayout.LayoutElementBuilders.Spacer
import androidx.wear.protolayout.LayoutElementBuilders.Text
import androidx.wear.protolayout.ModifiersBuilders
import androidx.wear.protolayout.ModifiersBuilders.Clickable
import androidx.wear.protolayout.ModifiersBuilders.Semantics
import androidx.wear.protolayout.TimelineBuilders.Timeline
import androidx.wear.protolayout.material3.MaterialScope
import androidx.wear.tiles.RequestBuilders
import androidx.wear.tiles.TileBuilders.Tile
import androidx.wear.tiles.Material3TileService
import de.trailscape.core.GlanceAnzeige
import de.trailscape.core.glanceFuerHeute
import de.trailscape.wear.MainActivity
import de.trailscape.wear.R
import de.trailscape.wear.glance.GlanceStore
import de.trailscape.wear.glance.GlanceTexts
import de.trailscape.wear.localized
import de.trailscape.wear.ui.theme.AccentGreen
import de.trailscape.wear.ui.theme.MutedText
import java.time.Duration
import java.time.LocalDateTime

/**
 * Die Kachel „Serie & Wochenziel": die Wochenserie gross, darunter der
 * Fortschritt zum Wochenziel als Bogen am Rand und als Zeile „42 von 60 km".
 * Ein Tipp oeffnet die Uhr-App.
 *
 * ## Woher die Zahlen
 * Nur aus dem lokalen Speicher ([GlanceStore]); das Telefon schreibt ihn
 * ueber [de.trailscape.wear.glance.WochenListenerService]. Was davon heute
 * noch gilt, entscheidet [glanceFuerHeute] in `:core` (getestet): Ein Stand
 * aus der Vorwoche behauptet nie „diese Woche geschafft".
 *
 * ## Warum kein Freshness-Takt
 * Die Kachel meldet sich bis kurz nach Mitternacht als frisch — also hoechstens
 * einmal am Tag eine Neuanforderung, damit am Montag nicht der Stand von
 * Sonntag stehen bleibt. Sonst loesen Datenaenderungen und der
 * Datumswechsel ([de.trailscape.wear.glance.TagWechselReceiver]) das Aktualisieren aus.
 *
 * ## Optik
 * Schwarzer Grund (die Kachel selbst ist transparent auf dunkel), die eine
 * Akzentfarbe [AccentGreen] fuer den Bogen, gedaempftes Grau fuer Nebentext —
 * ruhig, ohne Farbspiel, nahe an One UI Watch. Die Texte aus den Wear-Ressourcen.
 *
 * Gebaut mit den klassischen ProtoLayout-Bausteinen (Text, Arc, Box) in einem
 * [Material3TileService]: Der Dienst liefert die Kotlin-`suspend`-Schnittstelle
 * (hier wird der Data Layer gegebenenfalls abgefragt); die Bausteine sind
 * stabil und brauchen keine Material-Schluessel, die eine Wear-App ohne eigene
 * Material-Tokens nicht hat.
 */
class WochenTileService : Material3TileService(allowDynamicTheme = false) {

    override suspend fun MaterialScope.tileResponse(requestParams: RequestBuilders.TileRequest): Tile {
        val context = applicationContext.localized()
        val stand = GlanceStore.laden(applicationContext) ?: GlanceStore.nachziehen(applicationContext)
        val jetzt = LocalDateTime.now()
        val anzeige = glanceFuerHeute(stand, jetzt.toLocalDate())
        val layout = layout(context, anzeige)
        return Tile.Builder()
            .setResourcesVersion(RESOURCES_VERSION)
            .setTileTimeline(Timeline.fromLayoutElement(layout))
            .setFreshnessIntervalMillis(bisNachMitternacht(jetzt))
            .build()
    }

    companion object {
        private const val RESOURCES_VERSION = "1"
        private const val CLICK_ID = "open"

        /** Puffer nach Mitternacht, damit der Tageswechsel sicher vorbei ist. */
        private const val PUFFER_MINUTEN = 1L

        /** Bogen: 270 Grad, Anfang unten links (225 Grad im Uhrzeigersinn ab oben), Ende unten rechts. */
        private const val BOGEN_START_GRAD = 225f
        private const val BOGEN_LAENGE_GRAD = 270f
        private const val BOGEN_DICKE_DP = 7f

        private const val GROSS_SP = 40f
        private const val MITTEL_SP = 15f
        private const val KLEIN_SP = 12f

        /** Abstand der Bogenspur vom Rand, in dp. */
        private const val RAND_DP = 4f

        private val HINTERGRUND_SPUR = argb(0xFF2B3036.toInt())
        private val WEISS = argb(0xFFFFFFFF.toInt())
        private val GRAU = argb(MutedText.toArgb())
        private val AKZENT = argb(AccentGreen.toArgb())

        /** Millisekunden bis eine Minute nach dem naechsten Mitternacht. */
        internal fun bisNachMitternacht(jetzt: LocalDateTime): Long {
            val ziel = jetzt.toLocalDate().plusDays(1).atStartOfDay().plusMinutes(PUFFER_MINUTEN)
            return Duration.between(jetzt, ziel).toMillis().coerceAtLeast(PUFFER_MINUTEN * 60_000L)
        }

        private fun layout(context: Context, anzeige: GlanceAnzeige?): LayoutElementBuilders.LayoutElement {
            val oeffnen = Clickable.Builder()
                .setId(CLICK_ID)
                .setOnClick(
                    ActionBuilders.LaunchAction.Builder()
                        .setAndroidActivity(
                            ActionBuilders.AndroidActivity.Builder()
                                .setPackageName(context.packageName)
                                .setClassName(MainActivity::class.java.name)
                                .build(),
                        )
                        .build(),
                )
                .build()
            val beschreibung = if (anzeige != null) {
                GlanceTexts.beschreibung(context, anzeige)
            } else {
                context.getString(R.string.wear_glance_empty)
            }
            val modifiers = ModifiersBuilders.Modifiers.Builder()
                .setClickable(oeffnen)
                .setSemantics(
                    Semantics.Builder()
                        .setContentDescription(beschreibung)
                        .build(),
                )
                .build()

            val box = Box.Builder()
                .setWidth(expand())
                .setHeight(expand())
                .setModifiers(modifiers)
            if (anzeige == null) {
                return box.addContent(leerInhalt(context)).build()
            }
            return box
                .addContent(bogen(anzeige))
                .addContent(inhalt(context, anzeige))
                .build()
        }

        private fun leerInhalt(context: Context): LayoutElementBuilders.LayoutElement =
            Text.Builder()
                .setText(context.getString(R.string.wear_glance_empty))
                .setMaxLines(3)
                .setMultilineAlignment(LayoutElementBuilders.TEXT_ALIGN_CENTER)
                .setFontStyle(stil(MITTEL_SP, GRAU))
                .setModifiers(
                    ModifiersBuilders.Modifiers.Builder()
                        .setPadding(ModifiersBuilders.Padding.Builder().setAll(dp(24f)).build())
                        .build(),
                )
                .build()

        /** Spur und Fortschritt als Bogen am Rand; ohne Ziel nur die Spur. */
        private fun bogen(anzeige: GlanceAnzeige): LayoutElementBuilders.LayoutElement {
            val fortschritt = BOGEN_LAENGE_GRAD * anzeige.anteil
            val arc = Arc.Builder()
                .setAnchorAngle(degrees(BOGEN_START_GRAD))
                .setAnchorType(LayoutElementBuilders.ARC_ANCHOR_START)
                .addContent(
                    ArcLine.Builder()
                        .setLength(degrees(BOGEN_LAENGE_GRAD))
                        .setThickness(dp(BOGEN_DICKE_DP))
                        .setColor(HINTERGRUND_SPUR)
                        .build(),
                )
            if (fortschritt > 0f) {
                arc.addContent(
                    ArcLine.Builder()
                        .setLength(degrees(fortschritt))
                        .setThickness(dp(BOGEN_DICKE_DP))
                        .setColor(AKZENT)
                        .build(),
                )
            }
            return Box.Builder()
                .setWidth(expand())
                .setHeight(expand())
                .setModifiers(
                    ModifiersBuilders.Modifiers.Builder()
                        .setPadding(ModifiersBuilders.Padding.Builder().setAll(dp(RAND_DP)).build())
                        .build(),
                )
                .addContent(arc.build())
                .build()
        }

        /** Status klein oben, Serie gross in der Mitte, Kilometer darunter. */
        private fun inhalt(context: Context, anzeige: GlanceAnzeige): LayoutElementBuilders.LayoutElement =
            Column.Builder()
                .setHorizontalAlignment(LayoutElementBuilders.HORIZONTAL_ALIGN_CENTER)
                .addContent(zeile(GlanceTexts.status(context, anzeige), KLEIN_SP, GRAU))
                .addContent(Spacer.Builder().setHeight(dp(2f)).build())
                .addContent(zeile(GlanceTexts.serieLang(context, anzeige), GROSS_SP, WEISS, fett = true))
                .addContent(Spacer.Builder().setHeight(dp(2f)).build())
                .addContent(zeile(GlanceTexts.fortschritt(context, anzeige), MITTEL_SP, AKZENT))
                .build()

        private fun zeile(
            text: String,
            groesseSp: Float,
            farbe: androidx.wear.protolayout.ColorBuilders.ColorProp,
            fett: Boolean = false,
        ): LayoutElementBuilders.LayoutElement =
            Text.Builder()
                .setText(text)
                .setMaxLines(1)
                .setFontStyle(stil(groesseSp, farbe, fett))
                .build()

        private fun stil(
            groesseSp: Float,
            farbe: androidx.wear.protolayout.ColorBuilders.ColorProp,
            fett: Boolean = false,
        ): FontStyle =
            FontStyle.Builder()
                .setSize(sp(groesseSp))
                .setColor(farbe)
                .setWeight(
                    if (fett) LayoutElementBuilders.FONT_WEIGHT_BOLD else LayoutElementBuilders.FONT_WEIGHT_NORMAL,
                )
                .build()

    }
}
