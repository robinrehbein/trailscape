package de.trailscape.app.ui.map

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Explore
import androidx.compose.material.icons.filled.MyLocation
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
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import de.trailscape.app.R
import de.trailscape.app.i18n.LocalAppFormats
import de.trailscape.app.i18n.LocalAppLanguage
import de.trailscape.app.i18n.LocalCoreTexts
import de.trailscape.app.i18n.UiText
import de.trailscape.app.i18n.asString
import de.trailscape.app.ui.theme.CardPadding
import de.trailscape.core.ANSAGE_ANNAHME_KMH
import de.trailscape.core.ANSAGE_GLEICH_M
import de.trailscape.core.TurnRichtung
import de.trailscape.core.i18n.AppLanguage
import de.trailscape.core.i18n.formatDistanceKm
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
 * Aufbau: Die Kurvenzeile steht als farbige Flaeche (`primary`) ueber die
 * ganze Kartenbreite oben, abseits der Route die Warnflaeche an ihrer Stelle;
 * darunter auf der normalen Kartenflaeche Restdistanz, Lautsprecher und das
 * X, das die Fuehrung beendet („ohne Route weiter", die Aufzeichnung laeuft).
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
        // Oben die Fuehrung als eigene, farbige Flaeche — sie ist das, wonach
        // man im Fahren schaut, und soll sich deshalb klar vom Statusteil
        // darunter abheben (das Muster der aktiven Navigation bei Google Maps).
        if (offRoute) {
            OffRouteBanner()
        } else {
            TurnRow(richtung = naechsteKurve, abstandM = kurveAbstandM)
        }
        Row(
            modifier = Modifier.padding(
                start = CardPadding,
                top = 4.dp,
                end = 4.dp,
                bottom = 4.dp,
            ),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val language = LocalAppLanguage.current
            val formats = LocalAppFormats.current
            val restDauer = restzeitDauer(restzeitMin(remainingKm, tempoKmh)).asString()
            val spoken = stringResource(
                R.string.map_nav_remaining_cd,
                formats.km(remainingKm),
                label,
                restDauer,
            )
            Column(
                modifier = Modifier
                    .weight(1f)
                    .clearAndSetSemantics { contentDescription = spoken },
            ) {
                Text(
                    text = navRestZeile(remainingKm, tempoKmh, language).asString(),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = if (doneKm == null) {
                        label
                    } else {
                        stringResource(R.string.map_nav_done_line, label, formats.km(doneKm))
                    },
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.labelMedium,
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
                        stringResource(R.string.map_nav_voice_off_cd)
                    } else {
                        stringResource(R.string.map_nav_voice_on_cd)
                    },
                    tint = if (sprachansagenAn) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
            }
            // Als Symbol statt als Textknopf: „Ohne Route weiter" frass die
            // halbe Zeile und schnitt Restdistanz und Restzeit ab. Das X
            // beendet nur die Fuehrung, die Aufzeichnung laeuft weiter — das
            // sagt die Beschreibung fuer TalkBack.
            IconButton(onClick = onStop) {
                Icon(
                    imageVector = Icons.Filled.Close,
                    contentDescription = stringResource(R.string.map_nav_stop_cd),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
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
        stringResource(R.string.map_nav_next_turn_cd, turnAnsageText(richtung, abstandM, LocalCoreTexts.current))
    } else {
        stringResource(R.string.map_nav_no_turn_cd)
    }
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clearAndSetSemantics { contentDescription = spoken },
        color = MaterialTheme.colorScheme.primary,
        contentColor = MaterialTheme.colorScheme.onPrimary,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = CardPadding, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = turnRichtungIcon(richtung),
                contentDescription = null,
                modifier = Modifier.size(52.dp),
            )
            Spacer(Modifier.width(16.dp))
            Column {
                Text(
                    text = if (richtung != null && abstandM != null) {
                        kurveAbstandKurzText(abstandM).asString()
                    } else {
                        stringResource(R.string.map_nav_straight_label)
                    },
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    text = if (richtung != null) {
                        kurveAnzeigeWort(richtung).asString()
                    } else {
                        stringResource(R.string.map_nav_follow_label)
                    },
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.titleMedium,
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
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.errorContainer,
        contentColor = MaterialTheme.colorScheme.onErrorContainer,
    ) {
        Text(
            text = stringResource(R.string.map_nav_off_route_title),
            modifier = Modifier.padding(horizontal = CardPadding, vertical = 20.dp),
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
        )
    }
}

/**
 * Der schlichte Kompass-Umschalter der Navi-Kamera: Fahrtrichtung oben
 * (course-up, gefuellt in `primary`) oder Nord oben (blass). Er steht nur
 * waehrend einer Navigation auf der Karte — ausserhalb ist die Karte ohnehin
 * immer Nord oben — und schreibt seine Wahl in die Prefs
 * (`record/RecordingSettings.kt`, `trailscape.nav.courseUp`), damit die
 * naechste Navigation gleich richtig startet.
 */
@Composable
internal fun NavKompassKnopf(
    courseUp: Boolean,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        onClick = onToggle,
        modifier = modifier.size(44.dp),
        shape = CircleShape,
        color = if (courseUp) {
            MaterialTheme.colorScheme.primary
        } else {
            MaterialTheme.colorScheme.surfaceContainerLow
        },
        contentColor = if (courseUp) {
            MaterialTheme.colorScheme.onPrimary
        } else {
            MaterialTheme.colorScheme.primary
        },
        shadowElevation = 2.dp,
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(
                imageVector = Icons.Filled.Explore,
                contentDescription = if (courseUp) {
                    stringResource(R.string.map_nav_course_up_cd)
                } else {
                    stringResource(R.string.map_nav_north_up_cd)
                },
                modifier = Modifier.size(24.dp),
            )
        }
    }
}

/**
 * „Re-zentrieren" — der Rueckweg in die Navi-Kamera, nachdem die Karte selbst
 * verschoben oder gezoomt wurde (das Verschieben pausiert das Folgen, siehe
 * `followMe` in `MapScreen.kt`). Sitzt ueber der Kompaktleiste bzw. am
 * unteren Kartenrand — dort, wo der Daumen ohnehin ist — und ist dieselbe
 * Pillen-Sprache wie die uebrigen Chips der App.
 */
@Composable
internal fun RezentrierenChip(onClick: () -> Unit, modifier: Modifier = Modifier) {
    Surface(
        onClick = onClick,
        modifier = modifier,
        shape = MaterialTheme.shapes.small,
        color = MaterialTheme.colorScheme.primary,
        contentColor = MaterialTheme.colorScheme.onPrimary,
        shadowElevation = 2.dp,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = Icons.Filled.MyLocation,
                contentDescription = null,
                modifier = Modifier.size(20.dp),
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = stringResource(R.string.map_nav_recenter_action),
                style = MaterialTheme.typography.titleSmall,
            )
        }
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
internal fun kurveAbstandKurzText(abstandM: Double): UiText {
    if (abstandM < ANSAGE_GLEICH_M) return UiText.Res(R.string.map_nav_turn_now_label)
    val gerundet = ((abstandM / 50.0).roundToInt() * 50).coerceAtLeast(50)
    return UiText.Res(R.string.map_nav_turn_in_label, listOf(gerundet))
}

/** Anzeigeform des Richtungswortes — Satzanfang gross, sonst wie die Ansage. */
internal fun kurveAnzeigeWort(richtung: TurnRichtung): UiText = UiText.Res(
    when (richtung) {
        TurnRichtung.LINKS -> R.string.map_nav_turn_left_label
        TurnRichtung.RECHTS -> R.string.map_nav_turn_right_label
        TurnRichtung.KEHRE_LINKS -> R.string.map_nav_turn_sharp_left_label
        TurnRichtung.KEHRE_RECHTS -> R.string.map_nav_turn_sharp_right_label
    },
)

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

/**
 * Minuten als Dauer ohne „ca.": „50 min", ab einer Stunde „1 h 10 min" —
 * so liest TalkBack die Restzeit vor („geschätzte Restzeit 50 min").
 */
internal fun restzeitDauer(minuten: Int): UiText {
    if (minuten < 60) return UiText.Res(R.string.map_nav_duration_minutes, listOf(minuten))
    val h = minuten / 60
    val min = minuten % 60
    return if (min == 0) {
        UiText.Res(R.string.map_nav_duration_hours, listOf(h))
    } else {
        UiText.Res(R.string.map_nav_duration_hours_minutes, listOf(h, min))
    }
}

/** Minuten als Anzeigetext: „ca. 50 min", ab einer Stunde „ca. 1 h 10 min". */
internal fun restzeitText(minuten: Int): UiText =
    UiText.Res(R.string.map_nav_eta_approx, listOf(restzeitDauer(minuten)))

/** Die Restzeile des HUD: „12,4 km · ca. 50 min" bzw. „12.4 km · approx. 50 min". */
internal fun navRestZeile(remainingKm: Double, tempoKmh: Double?, language: AppLanguage): UiText =
    UiText.Res(
        R.string.map_nav_remaining_line,
        listOf(formatDistanceKm(remainingKm, language), restzeitText(restzeitMin(remainingKm, tempoKmh))),
    )
