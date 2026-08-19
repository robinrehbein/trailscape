package de.trailscape.app.ui.map

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Straight
import androidx.compose.material.icons.filled.TurnLeft
import androidx.compose.material.icons.filled.TurnRight
import androidx.compose.material.icons.filled.UTurnLeft
import androidx.compose.material.icons.filled.UTurnRight
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import de.trailscape.app.ui.formatKmDe
import de.trailscape.app.ui.theme.CardPadding
import de.trailscape.app.ui.theme.OverlayCardPaddingVertical
import de.trailscape.core.ANSAGE_ANNAHME_KMH
import de.trailscape.core.ANSAGE_GLEICH_M
import de.trailscape.core.TurnRichtung
import de.trailscape.core.turnAnsageText
import kotlin.math.ceil
import kotlin.math.roundToInt

/**
 * # Navigations-HUD — die Fuehrung waehrend der Fahrt, oben auf der Karte
 *
 * Ersetzt die fruehere `NavigationCard` („16,2 km übrig / Beenden") aus
 * `MapPanels.kt`. Die war eine Statuszeile zum Nachschauen — dieses HUD ist
 * die eigentliche Fuehrung im Google-Maps-Muster: oben gross die naechste
 * Kurve (Pfeil + „In 250 m"), darunter in einer Zeile Restdistanz und
 * geschaetzte Restzeit, dazu der Lautsprecher fuer die Sprachansagen und wie
 * bisher „Beenden".
 *
 * Gerechnet wird hier nichts Navigatorisches: Die naechste Kurve kommt aus
 * `naechsteKurve()` (`TurnHints.kt` in `:core`, dort getestet), der
 * Off-Route-Zustand aus dem `RouteNavigator` — dieselbe Arbeitsteilung wie
 * beim Fahrmodus (`RideModeScreen.kt`). Nur die **Darstellungs**-Rechnungen
 * (Rundung der Kurvendistanz, Restzeit aus Tempo) stehen als reine Funktionen
 * unten in dieser Datei, getestet in `NavigationHudTextTest`.
 *
 *  * **Kurvenzeile**: Pfeil je [TurnRichtung] (links, rechts, Kehren), dazu
 *    „In 250 m" — auf 50er gerundet wie die Sprachansage
 *    ([kurveAbstandKurzText]), unter [ANSAGE_GLEICH_M] Metern „Gleich".
 *    Ist keine Kurve in Sicht (naechster Hinweis weiter als
 *    [NAECHSTE_KURVE_SICHT_M] entfernt oder keiner mehr uebrig), steht ein
 *    Geradeaus-Pfeil mit „Geradeaus" — die Zeile bleibt, damit das HUD nicht
 *    bei jeder Kurve die Hoehe wechselt.
 *  * **Off-Route**: Die Kurvenzeile weicht einer vollflaechigen Warnflaeche
 *    „Abseits der Route" (`errorContainer`) — eine Kurvenauskunft auf fremdem
 *    Weg waere eine Falschauskunft (dieselbe Regel, nach der der
 *    Navigations-Effekt in `MapScreen.kt` abseits auch nicht ansagt). Zurueck
 *    auf der Route erscheint die normale Anzeige wieder.
 *  * **Restzeit**: Distanz durch das gleitend gemittelte Tempo der laufenden
 *    Aufzeichnung ([glaetteTempo] in `MapScreen.kt` gefuettert); ohne
 *    brauchbares Tempo gilt [ANSAGE_ANNAHME_KMH] — dieselbe Annahme wie beim
 *    Ansage-Vorlauf in `:core`.
 *  * **Lautsprecher**: schaltet den Hauptschalter „Sprachansagen"
 *    (`record/RecordingSettings.kt`) direkt hier um — der Weg ueber Mehr →
 *    Aufzeichnung ist waehrend der Fahrt keiner.
 *
 * Semantik: Kurvenzeile und Restzeile sprechen ganze Saetze statt nackter
 * Zahlen — dasselbe Muster wie `BigValue` im Fahrmodus.
 */
@Composable
internal fun NavigationHud(
    label: String,
    remainingKm: Double,
    doneKm: Double?,
    offRoute: Boolean,
    /** Richtung der naechsten Kurve, `null` = keine Kurve in Sicht. */
    naechsteKurve: TurnRichtung?,
    /** Distanz bis zur naechsten Kurve entlang der Route in Metern. */
    kurveAbstandM: Double?,
    /** Gleitendes Tempo in km/h fuer die Restzeit, `null` = unbekannt. */
    tempoKmh: Double?,
    sprachansagenAn: Boolean,
    onToggleSprachansagen: () -> Unit,
    onStop: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        elevation = CardDefaults.cardElevation(defaultElevation = 4.dp),
    ) {
        Column(
            modifier = Modifier.padding(
                start = CardPadding,
                top = OverlayCardPaddingVertical,
                end = 8.dp,
                bottom = OverlayCardPaddingVertical,
            ),
        ) {
            if (offRoute) {
                OffRouteBanner()
            } else {
                TurnRow(richtung = naechsteKurve, abstandM = kurveAbstandM)
            }
            Spacer(Modifier.height(6.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                val restzeit = restzeitText(restzeitMin(remainingKm, tempoKmh))
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .clearAndSetSemantics {
                            contentDescription = "Noch ${formatKmDe(remainingKm)} Kilometer " +
                                "auf $label, geschätzte Restzeit " +
                                restzeit.removePrefix("ca. ")
                        },
                ) {
                    Text(
                        text = "${formatKmDe(remainingKm)} km · $restzeit",
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        text = if (doneKm == null) {
                            label
                        } else {
                            "$label · ${formatKmDe(doneKm)} km geschafft"
                        },
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                IconButton(onClick = onToggleSprachansagen) {
                    Icon(
                        imageVector = if (sprachansagenAn) {
                            Icons.AutoMirrored.Filled.VolumeUp
                        } else {
                            Icons.AutoMirrored.Filled.VolumeOff
                        },
                        contentDescription = if (sprachansagenAn) {
                            "Sprachansagen ausschalten"
                        } else {
                            "Sprachansagen einschalten"
                        },
                        tint = if (sprachansagenAn) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    )
                }
                TextButton(onClick = onStop) { Text("Beenden") }
            }
        }
    }
}

/**
 * Die grosse Kurvenzeile: Pfeil plus gerundete Distanz und Richtungswort.
 * Ohne Kurve in Sicht der Geradeaus-Pfeil — Begruendung im Datei-KDoc.
 */
@Composable
private fun TurnRow(richtung: TurnRichtung?, abstandM: Double?) {
    val spoken = if (richtung != null && abstandM != null) {
        "Nächste Kurve: ${turnAnsageText(richtung, abstandM)}"
    } else {
        "Keine Kurve in Sicht, dem Routenverlauf folgen."
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clearAndSetSemantics { contentDescription = spoken },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = turnRichtungIcon(richtung),
            contentDescription = null,
            modifier = Modifier.size(48.dp),
            tint = MaterialTheme.colorScheme.primary,
        )
        Spacer(Modifier.width(12.dp))
        Column {
            Text(
                text = if (richtung != null && abstandM != null) {
                    kurveAbstandKurzText(abstandM)
                } else {
                    "Geradeaus"
                },
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
            )
            if (richtung != null) {
                Text(
                    text = kurveAnzeigeWort(richtung),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/**
 * Vollflaechige Warnflaeche statt der Kurvenzeile — Flaeche statt rotem Text,
 * aus demselben Grund wie die `OffRouteWarning` des Fahrmodus: Farbe allein
 * ist bei Sonne und Vibration zu wenig.
 */
@Composable
private fun OffRouteBanner() {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(end = 8.dp),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.errorContainer,
        contentColor = MaterialTheme.colorScheme.onErrorContainer,
    ) {
        Text(
            text = "Abseits der Route",
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
        )
    }
}

/**
 * Pfeil-Symbol je Richtung; `null` (keine Kurve in Sicht) ist der
 * Geradeaus-Pfeil. Auch vom Fahrmodus benutzt (`RideModeScreen.kt`).
 */
internal fun turnRichtungIcon(richtung: TurnRichtung?): ImageVector = when (richtung) {
    TurnRichtung.LINKS -> Icons.Filled.TurnLeft
    TurnRichtung.RECHTS -> Icons.Filled.TurnRight
    TurnRichtung.KEHRE_LINKS -> Icons.Filled.UTurnLeft
    TurnRichtung.KEHRE_RECHTS -> Icons.Filled.UTurnRight
    null -> Icons.Filled.Straight
}

// ------------------------------------------------- reine Darstellungslogik
// Getestet in `NavigationHudTextTest` — hier unten steht bewusst nichts, was
// Compose oder Android braucht.

/**
 * Ab dieser Distanz (Meter entlang der Route) gilt die naechste Kurve als
 * „nicht in Sicht" und HUD wie Fahrmodus zeigen den Geradeaus-Pfeil. Ein
 * „In 4950 m links" waere keine Fuehrung, sondern Rauschen.
 */
internal const val NAECHSTE_KURVE_SICHT_M = 1000.0

/**
 * Kurzform der Kurvendistanz fuer die Anzeige: „In 250 m", auf 50er-Schritte
 * gerundet — dieselbe Rundung und derselbe Nahbereich („Gleich" unter
 * [ANSAGE_GLEICH_M]) wie die Sprachansage `turnAnsageText` in `:core`;
 * Anzeige und Ansage duerfen sich nicht widersprechen.
 */
internal fun kurveAbstandKurzText(abstandM: Double): String {
    if (abstandM < ANSAGE_GLEICH_M) return "Gleich"
    val gerundet = ((abstandM / 50.0).roundToInt() * 50).coerceAtLeast(50)
    return "In $gerundet m"
}

/** Anzeigeform des Richtungswortes — Satzanfang gross, sonst wie die Ansage. */
internal fun kurveAnzeigeWort(richtung: TurnRichtung): String = when (richtung) {
    TurnRichtung.LINKS -> "Links"
    TurnRichtung.RECHTS -> "Rechts"
    TurnRichtung.KEHRE_LINKS -> "Scharf links"
    TurnRichtung.KEHRE_RECHTS -> "Scharf rechts"
}

/**
 * Tempo unterhalb dieser Schwelle (km/h) zaehlt fuer die Restzeit als
 * „steht gerade" — ein Zwischenhalt soll die Schaetzung nicht auf Stunden
 * treiben, stattdessen greift die Annahme [ANSAGE_ANNAHME_KMH].
 */
internal const val NAV_TEMPO_MIN_KMH = 3.0

/**
 * Exponentielle Glaettung des Tempos fuer die Restzeit — ein GPS-Tempo
 * springt je Punkt um mehrere km/h, und eine Restzeit, die im Sekundentakt
 * zwischen 48 und 55 Minuten pendelt, liest niemand mehr als Auskunft.
 * `null` (unbekanntes Tempo) laesst den bisherigen Wert stehen.
 */
internal fun glaetteTempo(bisherKmh: Double?, neuKmh: Double?): Double? = when {
    neuKmh == null -> bisherKmh
    bisherKmh == null -> neuKmh
    else -> bisherKmh + (neuKmh - bisherKmh) * TEMPO_GLAETTUNG_FAKTOR
}

/** Gewicht des neuen Messwerts in [glaetteTempo] (0..1). */
internal const val TEMPO_GLAETTUNG_FAKTOR = 0.3

/**
 * Geschaetzte Restfahrzeit in Minuten, aufgerundet (wer 49,2 min braucht,
 * ist nicht „in 49 Minuten" da). Ohne brauchbares Tempo (unbekannt oder
 * unter [NAV_TEMPO_MIN_KMH]) gilt [ANSAGE_ANNAHME_KMH].
 */
internal fun restzeitMin(remainingKm: Double, tempoKmh: Double?): Int {
    val kmh = tempoKmh?.takeIf { it >= NAV_TEMPO_MIN_KMH } ?: ANSAGE_ANNAHME_KMH
    return ceil(remainingKm / kmh * 60.0).toInt().coerceAtLeast(0)
}

/** Minuten als Anzeigetext: „ca. 50 min", ab einer Stunde „ca. 1 h 10 min". */
internal fun restzeitText(minuten: Int): String {
    if (minuten < 60) return "ca. $minuten min"
    val h = minuten / 60
    val min = minuten % 60
    return if (min == 0) "ca. $h h" else "ca. $h h $min min"
}

/** Die Restzeile des HUD: „12,4 km · ca. 50 min". */
internal fun navRestZeile(remainingKm: Double, tempoKmh: Double?): String =
    "${formatKmDe(remainingKm)} km · ${restzeitText(restzeitMin(remainingKm, tempoKmh))}"
