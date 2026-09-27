package de.trailscape.app.ui.map

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Air
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import de.trailscape.app.R
import de.trailscape.app.i18n.LocalAppFormats
import de.trailscape.app.i18n.LocalCoreTexts
import de.trailscape.app.i18n.UiText
import de.trailscape.app.i18n.asString
import de.trailscape.app.ui.components.NoticeBox
import de.trailscape.app.ui.theme.CardPadding
import de.trailscape.app.ui.theme.LocalSignalColors
import de.trailscape.app.ui.theme.OverlayCardPaddingVertical
import de.trailscape.core.PlannedRoute
import de.trailscape.core.RouteCandidate
import de.trailscape.core.RouteTarget
import de.trailscape.core.RouteTargetSource
import de.trailscape.core.TrackPoint
import de.trailscape.core.ascentPreferenceLabel
import de.trailscape.core.formatHours
import de.trailscape.core.i18n.AppLanguage
import de.trailscape.core.i18n.formatDecimal
import de.trailscape.core.i18n.formatDistanceKm
import de.trailscape.core.isTailwindHome
import de.trailscape.core.sessionIntensityLabel
import de.trailscape.core.terrainLabel
import de.trailscape.core.unpavedLabel
import de.trailscape.core.windLine
import de.trailscape.core.windOptimisedLabel
import de.trailscape.core.i18n.CoreTexts
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * # Die Rundenwahl — als unteres Blatt, nicht als Karte oben
 *
 * „Trainingsempfehlung → passende Runde": Der Trainings-Tab schickt ueber
 * [de.trailscape.app.ui.AppViewModel.requestRouteGeneration] ein Ziel her, und
 * dieses Blatt fuehrt durch drei Zustaende — Ziel bestaetigen → suchen (mit
 * Fortschritt und Abbruch) → einen Vorschlag waehlen und uebernehmen.
 *
 * ## Warum das hier unten sitzt und nicht mehr oben
 * Bis dahin war das eine `Card` im **oberen** Stapel des Karten-Screens,
 * waehrend das Planungsblatt unten mitlief. Zwei Flaechen an
 * gegenueberliegenden Bildschirmraendern fuer **eine** Aufgabe — und weil das
 * untere Blatt beim Aufziehen nach oben waechst, musste es irgendwann in die
 * Kandidatenliste hineinlaufen. Das war kein Zufall, das war die Bauart.
 *
 * Jetzt gibt es zu jedem Zeitpunkt **ein** Blatt mit **einer** Aufgabe: erst
 * waehlen (dieses hier), dann planen (`PlanningSheet`). Der obere Stapel
 * behaelt nur, was sich ueber die Karte legen *muss* — Hinweise, Navigation,
 * Downloadfortschritt.
 *
 * Dass die Planungswerkzeuge dabei zur Seite treten, kostet nichts: Eine
 * generierte Runde hat keine Wegpunkte, die sich auflisten liessen, und das
 * Routenprofil-Dropdown ist bei ihr ohnehin abgeschaltet (gesucht wird mit dem
 * zuvor im Planungsblatt gewaehlten Profil; eine fertige Runde laesst sich
 * ohne Wegpunkte nicht nachrechnen). Was wirklich hilft, ist das Hoehenprofil
 * der Auswahl — und das steht im Koerper, einen Zug entfernt.
 *
 * ## Was im Peek steht und was im Koerper
 * Im **Peek** die Entscheidung: Ziel, die Kandidaten, „Übernehmen". Sie ist die
 * Aufgabe und darf nicht hinter einer Geste liegen — dieselbe Lehre wie bei der
 * Ortssuche im Erkunden-Blatt.
 *
 * Im **Koerper** das Zusatzwissen: das Hoehenprofil der gewaehlten Runde und
 * die Zweitaktionen. Solange noch gar nicht gesucht wurde, traegt er
 * stattdessen die Erklaerung, was die Suche ueberhaupt tut und wie lange sie
 * dauert — so hat der Griff in jeder Phase etwas zu zeigen.
 *
 * ## Ein Ausweg statt vier
 * Vorher standen unter „Übernehmen" drei weitere Aktionen — „Andere
 * Vorschläge", „Neu suchen", „Verwerfen" — und oben rechts ein X, das ebenfalls
 * verwarf. Vier Auswege, zwei davon deckungsgleich. „Verwerfen" ist entfallen;
 * das X sagt dasselbe und ist die Stelle, an der man es sucht.
 *
 * Der [de.trailscape.core.RouteCandidate.score] wird **nicht** angezeigt: Es
 * sind Strafpunkte, also ein internes Mass ohne Einheit. Was die Nutzerin
 * braucht, steht ohnehin da — die Reihenfolge (bester zuerst) und die
 * Abweichung vom Ziel in Prozent.
 *
 * War „Wind berücksichtigen" an und kam der Wind an, steht unter der
 * Quellzeile eine schlichte Windzeile zum gewaehlten Vorschlag („Wind 18 km/h
 * aus West – Rückenwind auf dem Heimweg"), und windguenstige Vorschlaege
 * tragen die Pille „Rückenwind heim". Bewusst Text statt [NoticeBox]: Das ist
 * eine Information, keine Warnung — und ohne Wind fehlt sie einfach.
 *
 * Ist der Schalter aus und kam die Runde **nicht** aus „Runde ab hier" (also
 * aus Heute oder Training), steht an derselben Stelle ein leiser Tipp, wo es
 * ihn gibt: Nur dort sitzt der Schalter samt Hinweis, was an Open-Meteo geht.
 * Wer aus „Runde ab hier" kommt, hat ihn gerade gesehen und braucht keinen.
 *
 * @param windEnabled Stand des Schalters „Wind berücksichtigen".
 * @param route Die Vorschau der gewaehlten Runde. Kommt aus dem Karten-Screen,
 *   der sie beim Waehlen setzt — dieses Blatt zeichnet daraus nur das
 *   Hoehenprofil und rechnet nichts.
 * @param candidatesMaxHeight Obergrenze fuer die Kandidatenliste im Peek. Der
 *   Peek ist immer sichtbar; ohne Deckel schoeben viele Vorschlaege die
 *   schwebenden Knoepfe darueber vom Bildschirm.
 */
@Composable
internal fun RouteGenerationSheet(
    state: RouteGenerationState,
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    route: PlannedRoute?,
    candidatesMaxHeight: Dp,
    bodyMaxHeight: Dp,
    locating: Boolean = false,
    onStart: () -> Unit,
    onCancel: () -> Unit,
    onSelect: (Int) -> Unit,
    onNextSuggestions: () -> Unit,
    onApply: () -> Unit,
    onDiscard: () -> Unit,
    onHoverPoint: (TrackPoint?) -> Unit,
    modifier: Modifier = Modifier,
    bottomInset: Dp = 0.dp,
    windEnabled: Boolean = false,
) {
    val coreTexts = LocalCoreTexts.current
    val target = state.target ?: return
    val theme = MaterialTheme.colorScheme
    val signals = LocalSignalColors.current
    val hasCandidates = state.candidates.isNotEmpty() && !state.running && !locating

    SwipeableSheet(
        bottomInset = bottomInset,
        expanded = expanded,
        onExpandedChange = onExpandedChange,
        modifier = modifier,
        peek = {
            Column(
                modifier = Modifier.padding(
                    start = CardPadding,
                    top = 2.dp,
                    end = 4.dp,
                    bottom = OverlayCardPaddingVertical,
                ),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = stringResource(R.string.map_generation_title),
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.titleMedium,
                    )
                    // Der einzige Ausweg — siehe „Ein Ausweg statt vier" oben.
                    IconButton(onClick = onDiscard) {
                        Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.map_generation_discard_cd))
                    }
                }

                Text(
                    text = targetLine(target, coreTexts).asString(),
                    modifier = Modifier.padding(end = 8.dp),
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    text = sourceLine(target).asString(),
                    modifier = Modifier.padding(end = 8.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = theme.onSurfaceVariant,
                )
                if (hasCandidates) {
                    state.wind?.let { wind ->
                        val shape = state.selected?.windShape
                        val favourable = shape != null && isTailwindHome(shape)
                        Row(
                            modifier = Modifier.padding(end = 8.dp, top = 2.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            val color = if (favourable) theme.primary else theme.onSurfaceVariant
                            Icon(
                                Icons.Filled.Air,
                                contentDescription = null,
                                tint = color,
                                modifier = Modifier.size(16.dp),
                            )
                            Spacer(Modifier.width(6.dp))
                            Text(
                                text = windLine(wind, shape, coreTexts),
                                style = MaterialTheme.typography.bodySmall,
                                color = color,
                            )
                        }
                    }
                    if (shouldOfferWindTip(target, windUsed = state.wind != null, windEnabled = windEnabled)) {
                        Text(
                            text = stringResource(R.string.map_generation_wind_tip),
                            modifier = Modifier.padding(end = 8.dp, top = 2.dp),
                            style = MaterialTheme.typography.bodySmall,
                            color = theme.onSurfaceVariant,
                        )
                    }
                }

                Spacer(Modifier.height(8.dp))

                for (hint in state.hints) {
                    NoticeBox(
                        icon = Icons.Filled.Info,
                        color = signals.caution,
                        text = hint,
                        modifier = Modifier.padding(end = 8.dp, bottom = 8.dp),
                    )
                }

                if (state.fromMapCenter) {
                    NoticeBox(
                        icon = Icons.Filled.Info,
                        color = signals.caution,
                        text = stringResource(R.string.map_generation_map_center_hint),
                        modifier = Modifier.padding(end = 8.dp, bottom = 8.dp),
                    )
                }

                state.error?.let { error ->
                    NoticeBox(
                        icon = Icons.Filled.Warning,
                        color = signals.danger,
                        text = error.asString(),
                        modifier = Modifier.padding(end = 8.dp, bottom = 8.dp),
                    )
                    // Derselbe Ausweg wie im Fehlerzweig der manuellen Planung
                    // (siehe `PlanningSheet` in `PlanningPanel.kt`): Der Fehler
                    // nennt den Server, kennt aber den Ausweg nicht. Fehlen fuer
                    // die versuchten Runden Kacheln, bietet Trailscape den
                    // Download direkt an (`AppViewModel.offerMissingSegments`,
                    // ausgeloest im Fehlerzweig des `RouteGenerationController`).
                    NoticeBox(
                        icon = Icons.Filled.Info,
                        color = signals.caution,
                        text = stringResource(R.string.map_generation_offline_hint),
                        modifier = Modifier.padding(end = 8.dp, bottom = 8.dp),
                    )
                }

                when {
                    locating -> Row(
                        modifier = Modifier.padding(end = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        CircularProgressIndicator(
                            strokeWidth = 2.dp,
                            modifier = Modifier.size(18.dp),
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = stringResource(R.string.map_generation_locating_status),
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }

                    state.running -> SearchProgress(
                        done = state.done,
                        total = state.total,
                        onCancel = onCancel,
                    )

                    state.candidates.isEmpty() -> PrimaryButton(
                        text = if (state.error == null) {
                            stringResource(R.string.map_generation_search_action)
                        } else {
                            stringResource(R.string.map_generation_retry_action)
                        },
                        onClick = onStart,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(end = 8.dp),
                    )

                    else -> {
                        // Gedeckelt und scrollbar: Der Peek steht immer im
                        // Bild, und ueber ihm liegen noch die beiden
                        // schwebenden Knoepfe. Ohne Deckel schoebe eine lange
                        // Liste sie vom Bildschirm.
                        Column(
                            modifier = Modifier
                                .heightIn(max = candidatesMaxHeight)
                                .verticalScroll(rememberScrollState()),
                        ) {
                            state.candidates.forEachIndexed { index, candidate ->
                                CandidateRow(
                                    candidate = candidate,
                                    rank = index + 1,
                                    selected = index == state.selectedIndex,
                                    onClick = { onSelect(index) },
                                    modifier = Modifier.padding(end = 8.dp, bottom = 6.dp),
                                )
                            }
                        }

                        Spacer(Modifier.height(2.dp))
                        PrimaryButton(
                            text = stringResource(R.string.map_generation_apply_action),
                            onClick = onApply,
                            enabled = state.selected != null,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(end = 8.dp),
                        )
                    }
                }
            }
        },
        body = {
            Column(
                modifier = Modifier
                    .heightIn(max = bodyMaxHeight)
                    .verticalScroll(rememberScrollState())
                    .padding(
                        start = CardPadding,
                        end = CardPadding,
                        bottom = OverlayCardPaddingVertical,
                    ),
            ) {
                if (hasCandidates) {
                    if (route != null && route.points.size >= 2) {
                        ElevationProfile(
                            points = route.points,
                            lineColor = theme.primary,
                            onHover = onHoverPoint,
                        )
                        Spacer(Modifier.height(8.dp))
                    }
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        TextButton(onClick = onNextSuggestions) {
                            Icon(
                                Icons.Filled.Refresh,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp),
                            )
                            Spacer(Modifier.width(4.dp))
                            Text(stringResource(R.string.map_generation_other_action))
                        }
                        // Gleiche Runden-Variation, aber Startpunkt neu
                        // bestimmen — nach einem GPS-Fix oder einer
                        // verschobenen Karte.
                        TextButton(onClick = onStart) { Text(stringResource(R.string.map_generation_research_action)) }
                    }
                } else {
                    // Solange nichts zu waehlen ist, traegt der Koerper die
                    // Erklaerung. Vorher stand sie im immer sichtbaren Teil und
                    // war ab dem zweiten Mal nur noch Text im Weg.
                    Text(
                        text = stringResource(
                            R.string.map_generation_intro_body,
                            RouteGenerationController.CANDIDATE_COUNT,
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = theme.onSurfaceVariant,
                    )
                }
            }
        },
    )
}


/** Fortschritt der laufenden Suche mit Abbruch. */
@Composable
private fun SearchProgress(done: Int, total: Int, onCancel: () -> Unit) {
    Column(modifier = Modifier.padding(end = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = if (total <= 0) {
                    stringResource(R.string.map_generation_running_status)
                } else {
                    stringResource(R.string.map_generation_progress_status, (done + 1).coerceAtMost(total), total)
                },
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.bodySmall,
            )
            TextButton(onClick = onCancel) { Text(stringResource(R.string.map_generation_cancel_action)) }
        }
        if (total > 0) {
            LinearProgressIndicator(
                progress = { (done.toFloat() / total.toFloat()).coerceIn(0f, 1f) },
                modifier = Modifier.fillMaxWidth(),
                            )
        } else {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        }
    }
}

/** Ein Vorschlag in der Ergebnisliste. */
@Composable
private fun CandidateRow(
    candidate: RouteCandidate,
    rank: Int,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val coreTexts = LocalCoreTexts.current
    val theme = MaterialTheme.colorScheme
    // Ausserhalb des `semantics`-Blocks festhalten: Dort verdeckt die
    // gleichnamige Semantik-Eigenschaft den Parameter.
    val isSelected = selected
    Surface(
        onClick = onClick,
        modifier = modifier
            .fillMaxWidth()
            // Ohne dies stand die Auswahl nur in der Flaechenfarbe: Wer sich
            // die Vorschlaege vorlesen laesst, hoerte viermal dasselbe und
            // erfuhr nie, welcher gerade auf der Karte liegt.
            .semantics { this.selected = isSelected },
        shape = MaterialTheme.shapes.medium,
        // Unselektiert eine Container-Stufe ueber der Karte des Panels, damit
        // die waehlbaren Reihen auf ihr sichtbar bleiben; ausgewaehlt heben
        // sie sich im One-UI-Gruen der `primaryContainer` ab.
        color = if (selected) theme.primaryContainer else theme.surfaceContainer,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            DirectionChip(bearingDeg = candidate.bearingDeg, highlighted = selected)
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(
                        R.string.map_generation_candidate_headline,
                        LocalAppFormats.current.km(candidate.distanceKm),
                        candidate.ascentM.roundToInt(),
                    ),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.titleSmall,
                )
                Text(
                    text = candidateDetailLine(candidate, coreTexts).asString(),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.bodySmall,
                    color = theme.onSurfaceVariant,
                )
                // Eigene Zeile statt eines weiteren „·"-Glieds: Die Zeile
                // darueber ist auf einem schmalen Geraet schon voll, und der
                // Belag ist fuer Gravel die Angabe, die nicht abgeschnitten
                // werden darf. Fehlt er (keine Daten oder zu viel
                // Unbekanntes), faellt die Zeile ganz weg statt „–" zu zeigen.
                unpavedLabel(candidate.route, coreTexts)?.let { surface ->
                    Text(
                        text = surface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.bodySmall,
                        color = theme.onSurfaceVariant,
                    )
                }
            }
            Spacer(Modifier.width(8.dp))
            Column(horizontalAlignment = Alignment.End) {
                Text(
                    text = if (rank == 1) stringResource(R.string.map_generation_best_label) else "#$rank",
                    style = MaterialTheme.typography.labelSmall,
                    color = theme.onSurfaceVariant,
                )
                // Neue Kacheln der Runde (Squadrats-Idee): „+9 neu" gruen,
                // eine Runde ganz durch Bekanntes heisst „bekannt".
                if (candidate.totalTileCount > 0) {
                    val novel = candidate.newTileCount > 0
                    Surface(
                        shape = MaterialTheme.shapes.small,
                        color = if (novel) theme.primaryContainer else theme.surfaceVariant,
                        contentColor = if (novel) theme.onPrimaryContainer else theme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 2.dp),
                    ) {
                        Text(
                            text = newTilesLabel(candidate.newTileCount).asString(),
                            style = MaterialTheme.typography.labelSmall,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 1.dp),
                        )
                    }
                }
                // Hin gegen den Wind, heim mit Rueckenwind (nur mit Schalter
                // und genug Wind — sonst ist `windShape` null).
                if (candidate.windShape?.let(::isTailwindHome) == true) {
                    Surface(
                        shape = MaterialTheme.shapes.small,
                        color = theme.primaryContainer,
                        contentColor = theme.onPrimaryContainer,
                        modifier = Modifier.padding(top = 2.dp),
                    ) {
                        Text(
                            text = windOptimisedLabel(coreTexts),
                            style = MaterialTheme.typography.labelSmall,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 1.dp),
                        )
                    }
                }
            }
        }
    }
}

/** Himmelsrichtung des Rundkurses als runder Chip (Pille aus `shapes.small`). */
@Composable
private fun DirectionChip(bearingDeg: Double, highlighted: Boolean) {
    Surface(
        modifier = Modifier.size(36.dp),
        shape = MaterialTheme.shapes.small,
        color = if (highlighted) RouteBlue else MaterialTheme.colorScheme.surfaceVariant,
        contentColor = if (highlighted) Color.White else MaterialTheme.colorScheme.onSurfaceVariant,
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text(
                text = compassLabel(bearingDeg).asString(),
                style = MaterialTheme.typography.labelMedium,
            )
        }
    }
}

// --------------------------------------------------------------------- Texte

/**
 * „Wellig · 12 Hm/km · +3,4 % zum Ziel" — die zweite Zeile eines Vorschlags.
 *
 * Die Gelaendeart steht vorn, weil sie dieselben Worte benutzt wie die
 * Zielzeile oben im Blatt („≈ 45 km · Wellig · …", siehe [targetLine]): So
 * sieht man ohne Rechnen, ob eine Runde zum Ziel passt. Die Hm/km bleiben
 * dahinter fuer alle, die es genau wissen wollen; Schwellen siehe
 * [terrainLabel].
 */
internal fun candidateDetailLine(candidate: RouteCandidate, texts: CoreTexts): UiText =
    UiText.Res(
        R.string.map_generation_candidate_detail,
        listOf(
            terrainLabel(candidate.ascentPerKm, texts),
            candidate.ascentPerKm.roundToInt(),
            deviationLabel(candidate, texts.language),
        ),
    )

/** „+9 neu" bzw. „bekannt" — Kacheln, die die Runde neu entdecken wuerde. */
internal fun newTilesLabel(newTileCount: Int): UiText =
    if (newTileCount > 0) {
        UiText.Res(R.string.map_generation_new_tiles_label, listOf(newTileCount))
    } else {
        UiText.Res(R.string.map_generation_known_tiles_label)
    }

/** „≈ 45 km · Flach · locker · ca. 2,5 h" */
internal fun targetLine(target: RouteTarget, texts: CoreTexts): UiText {
    val km = formatDistanceKm(target.distanceKm, texts.language)
    val ascent = ascentPreferenceLabel(target.ascentPreference, texts)
    val intensity = sessionIntensityLabel(target.intensity, texts)
    val hours = target.durationH?.takeIf { it.isFinite() && it > 0 }
    return if (hours != null) {
        UiText.Res(
            R.string.map_generation_target_line_duration,
            listOf(km, ascent, intensity, formatHours(hours, texts)),
        )
    } else {
        UiText.Res(R.string.map_generation_target_line, listOf(km, ascent, intensity))
    }
}

/**
 * Ob unter den Vorschlaegen der leise Wind-Tipp (`map_generation_wind_tip`)
 * steht: nur, wenn ohne Wind gesucht wurde ([windUsed] `false`), der Schalter
 * aus ist und das Ziel nicht aus „Runde ab hier" kommt — dort steht der
 * Schalter ja schon im Blatt davor.
 *
 * Nie bei der **ersten Runde** ([RouteTarget.isFirstRound]): Sie startet direkt nach
 * der Einfuehrung bzw. von „Heute" und soll ruhig bleiben — ein Tipp, der auf
 * ein anderes Blatt und eine Open-Meteo-Freigabe verweist, von denen dort
 * noch keine Rede war, waere in der ersten Minute nur Rauschen.
 */
internal fun shouldOfferWindTip(target: RouteTarget, windUsed: Boolean, windEnabled: Boolean): Boolean =
    !windUsed && !windEnabled &&
        target.source != RouteTargetSource.SELBST_GEWAEHLT &&
        !target.isFirstRound

/** „aus: GA1-Einheit (Trainingsplan)" */
internal fun sourceLine(target: RouteTarget): UiText {
    // Selbst gewaehlte Runden kommen aus keinem Trainingsziel. Frueher fehlte
    // `:core` dafuer ein Wert und die Beschriftung erkannte sie notduerftig an
    // ihrem festen Label; seit es [RouteTargetSource.SELBST_GEWAEHLT] gibt,
    // steht es an der Quelle statt am Text.
    if (target.source == RouteTargetSource.SELBST_GEWAEHLT) {
        return UiText.Res(R.string.map_generation_source_self)
    }
    val source = when (target.source) {
        RouteTargetSource.PLAN -> R.string.map_generation_source_plan
        RouteTargetSource.TAGESEMPFEHLUNG -> R.string.map_generation_source_today
        RouteTargetSource.SELBST_GEWAEHLT -> R.string.map_generation_source_own
    }
    return UiText.Res(R.string.map_generation_source_line, listOf(target.label, UiText.Res(source)))
}

/*
 * Zwei feste Saetze dieses Blatts stehen in `strings_map.xml`:
 *  * `map_generation_self_target_label` — Beschriftung eines Ziels, das die
 *    Nutzerin selbst auf der Karte eingegeben hat („Runde ab hier über 50 km").
 *  * `map_generation_first_round_no_position` — Snackbar, wenn die
 *    automatisch gestartete erste Runde keinen Standort hat (Freigabe
 *    abgelehnt oder kein Fix). Die Suche startet dann bewusst nicht ab der
 *    Kartenmitte — nach einer Neuinstallation ist das die
 *    Deutschland-Uebersicht. Das Panel bleibt offen; der Satz sagt, wie es
 *    von dort weitergeht.
 */

/**
 * Abweichung vom Ziel mit Vorzeichen, z. B. `+3,4 %` (Englisch `+3.4%`).
 *
 * [RouteCandidate.distanceDeviation] ist der Betrag; die Richtung ergibt sich
 * aus dem Vergleich mit [RouteCandidate.targetKm].
 */
internal fun deviationLabel(candidate: RouteCandidate, language: AppLanguage): UiText {
    val percent = candidate.distanceDeviation * 100
    if (!percent.isFinite() || abs(percent) < 0.05) {
        return UiText.Res(R.string.map_generation_deviation_zero)
    }
    val sign = if (candidate.distanceKm >= candidate.targetKm) "+" else "−"
    return UiText.Res(R.string.map_generation_deviation, listOf(sign, formatDecimal(percent, 1, language)))
}

/**
 * Himmelsrichtung eines Kurses in acht Stufen (`N`, `NO`, … bzw. englisch
 * `N`, `NE`, …). 0° ist Nord, gezaehlt wird im Uhrzeigersinn — dieselbe
 * Konvention wie [RouteCandidate.bearingDeg].
 */
internal fun compassLabel(bearingDeg: Double): UiText {
    if (!bearingDeg.isFinite()) return UiText.Plain("–")
    val normalized = ((bearingDeg % 360) + 360) % 360
    val index = ((normalized + 22.5) / 45).toInt() % COMPASS_LABELS.size
    return UiText.Res(COMPASS_LABELS[index])
}

private val COMPASS_LABELS = listOf(
    R.string.map_generation_compass_n,
    R.string.map_generation_compass_ne,
    R.string.map_generation_compass_e,
    R.string.map_generation_compass_se,
    R.string.map_generation_compass_s,
    R.string.map_generation_compass_sw,
    R.string.map_generation_compass_w,
    R.string.map_generation_compass_nw,
)
