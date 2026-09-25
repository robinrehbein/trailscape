package de.trailscape.app.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import de.trailscape.app.ui.formatKmDe
import de.trailscape.core.formatDuration

/**
 * # Der abgesetzte ●-Knopf neben der Kapsel
 *
 * Die Designstudie „Eine Leiste" (`docs/design/ui-navigationsstudien.html`,
 * Kapitel „Eine Leiste"; Prototyp `docs/design/prototyp-eine-leiste.html`)
 * loest ein Problem, das jede fruehere Fassung der Navigation hatte: Eine
 * Fahrt starten ist keine Navigation zwischen gleichrangigen Bereichen
 * (Heute, Karte, Verlauf, Training) — es ist die **eine** Handlung, wegen der
 * die App ueberhaupt existiert. Steckt der Aufzeichnen-Knopf als fuenftes
 * Ziel *in* der [OneUiNavigationBar], sieht er wie ein Reiter unter vieren
 * aus und verliert genau dieses Gewicht; Samsung Health loest dasselbe
 * Problem, indem die runde Aktion **neben** der Pille schwebt, sichtbar
 * abgesetzt. Dieser Knopf ist dieser Nachbar: gleich hoch wie die Kapsel und
 * mit demselben Schatten, aber in der satten Akzentfarbe — er ist die eine
 * Handlung, die Kapsel die vier Orte.
 *
 * ## Die drei Zustaende (Fuehrung „Klartext", `docs/design/prototyp-klartext.html`)
 * Der Knopf ist **beschriftet**, bleibt dabei aber ein Kreis: Ein unbeschrifteter
 * Punkt verriet nicht, dass er das Fahren startet.
 *
 * [RecButtonState.Idle] — grüne Akzentflaeche, ▶ und darunter klein „Fahren".
 * [RecButtonState.RouteReady] — dieselbe Flaeche, statt „Fahren" die
 * Kilometerzahl der vorbereiteten Route. [RecButtonState.Recording] — die
 * Flaeche kippt auf Rot (Fehler-/Aufnahmefarbe), statt ▶ ein Punkt, darunter
 * die Fahrzeit; der Ring pulsiert, solange wirklich aufgezeichnet wird, und
 * steht bei einer Pause bewusst **still** — ein stehender Ring ist die
 * Auskunft „angehalten".
 *
 * ## Warum das Label IM Kreis wohnt
 * Das Mini-Label (Kilometer bzw. Fahrzeit) stand zuerst unter dem Knopf —
 * auf dem Geraet landete es damit exakt in der System-Gestenzone und war
 * unsichtbar (die Kapselzeile sitzt direkt ueber der Gestenleiste, darunter
 * ist kein nutzbarer Platz). Es steht deshalb **im** Kreis unter dem Punkt:
 * immer sichtbar, verschiebt nichts, und der Kreis bleibt exakt der
 * quadratische Knopf-Slot, dessen Mitte mit der Kapselmitte fluchtet.
 *
 * Ein einziger `onClick` traegt alle drei Zustaende — was ein Tipp bedeutet
 * (Aufzeichnung starten, Cockpit oeffnen, Route verwerfen-Dialog o. ae.),
 * entscheidet allein der Aufrufer anhand von [state].
 */
sealed interface RecButtonState {
    data object Idle : RecButtonState
    data class RouteReady(val distanceKm: Double) : RecButtonState
    data class Recording(val elapsedMs: Long, val paused: Boolean) : RecButtonState
}

@Composable
fun RecCapsuleButton(
    state: RecButtonState,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val haptics = LocalHapticFeedback.current
    val isRecording = state is RecButtonState.Recording
    val paused = (state as? RecButtonState.Recording)?.paused == true

    val containerColor = if (isRecording) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
    val dotColor = if (isRecording) MaterialTheme.colorScheme.onError else MaterialTheme.colorScheme.onPrimary
    val ringColor = MaterialTheme.colorScheme.error

    val label = when (state) {
        is RecButtonState.Idle -> "Fahren"
        is RecButtonState.RouteReady -> recRouteLabel(state.distanceKm)
        is RecButtonState.Recording -> recElapsedLabel(state.elapsedMs)
    }
    val contentDescriptionText = when (state) {
        is RecButtonState.Idle -> "Aufzeichnung starten"
        is RecButtonState.RouteReady -> "Geplante Tour starten"
        is RecButtonState.Recording -> if (state.paused) {
            "Aufzeichnung pausiert — Cockpit öffnen"
        } else {
            "Aufzeichnung läuft — Cockpit öffnen"
        }
    }

    val showStaticRing = isRecording && paused
    val showPulseRing = isRecording && !paused

    // Die Transition laeuft immer mit — nur ihr Ergebnis wird bei Bedarf
    // gezeichnet. So bleibt die Aufrufreihenfolge der Composables ueber alle
    // drei Zustaende hinweg gleich, statt eine bedingte `remember`-Kette zu
    // riskieren.
    val pulseTransition = rememberInfiniteTransition(label = "recPulse")
    val pulseProgress by pulseTransition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            // 1600 ms, linear und ohne Ueberschwingen — ein staendig
            // wiederholter Puls braucht eine ruhige, gleichmaessige Kurve,
            // sonst wirkt er nach wenigen Wiederholungen hektisch statt
            // beilaeufig.
            animation = tween(PulseDurationMillis, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "recPulseProgress",
    )
    val pulseScale = PulseMinScale + (PulseMaxScale - PulseMinScale) * pulseProgress
    val pulseAlpha = PulseMaxAlpha * (1f - pulseProgress)

    Box(
        modifier = modifier
            .width(RecButtonSlotSize)
            .height(RecButtonSlotSize),
    ) {
        Box(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .size(RecButtonSlotSize),
            contentAlignment = Alignment.Center,
        ) {
            if (showPulseRing) {
                Box(
                    modifier = Modifier
                        .size(RecRingSize)
                        .graphicsLayer {
                            scaleX = pulseScale
                            scaleY = pulseScale
                            alpha = pulseAlpha
                        }
                        .border(BorderStroke(RecRingStrokeWidth, ringColor), CircleShape),
                )
            }
            if (showStaticRing) {
                Box(
                    modifier = Modifier
                        .size(RecRingSize)
                        .border(
                            BorderStroke(RecRingStrokeWidth, ringColor.copy(alpha = StaticRingAlpha)),
                            CircleShape,
                        ),
                )
            }

            Surface(
                onClick = {
                    // Dieselbe Antwort auf Beruehrung wie der aktive Wechsel
                    // in der Navigationsleiste — One UI bestaetigt echte
                    // Handlungen fuehlbar, nicht nur sichtbar.
                    haptics.performHapticFeedback(HapticFeedbackType.SegmentTick)
                    onClick()
                },
                modifier = Modifier
                    .size(RecButtonSize)
                    .semantics { contentDescription = contentDescriptionText },
                shape = CircleShape,
                color = containerColor,
                // Satte Akzentflaeche in allen Zustaenden — ein heller Saum
                // wie bei der Kapsel wuerde darauf nur unruhig wirken.
                border = null,
                shadowElevation = RecButtonElevation,
            ) {
                Column(
                    modifier = Modifier.fillMaxSize(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    if (isRecording) {
                        Box(
                            modifier = Modifier
                                .size(RecDotSmallSize)
                                .clip(CircleShape)
                                .background(dotColor),
                        )
                    } else {
                        Icon(
                            imageVector = Icons.Rounded.PlayArrow,
                            contentDescription = null,
                            tint = dotColor,
                            modifier = Modifier.size(RecPlayIconSize),
                        )
                    }
                    Text(
                        text = label,
                        style = MaterialTheme.typography.labelSmall.copy(
                            fontSize = RecLabelFontSize,
                            lineHeight = RecLabelLineHeight,
                            fontWeight = FontWeight.Bold,
                            fontFamily = if (isRecording) {
                                FontFamily.Monospace
                            } else {
                                FontFamily.Default
                            },
                        ),
                        color = dotColor,
                        maxLines = 1,
                        modifier = Modifier
                            .padding(top = 2.dp)
                            // Die Auskunft steckt schon im
                            // `contentDescription` des Knopfs; ein
                            // tickendes Zweitlabel wuerde die
                            // Bildschirmlesehilfe bei jeder Sekunde
                            // erneut ansagen lassen.
                            .clearAndSetSemantics {},
                    )
                }
            }
        }
    }
}

/** Beruehrbare Groesse des runden Knopfs selbst. */
private val RecButtonSize = 56.dp

/**
 * Fester Platzbedarf des gesamten Bausteins: der Knopf plus eine schmale
 * Luft fuer den starren Ring. Der **Puls**-Ring darf beim Ausdehnen bewusst
 * ueber diesen Rahmen hinauszeichnen (Compose beschneidet nicht) — ein
 * breiterer Slot wuerde nur der Navigationskapsel Platz wegnehmen, deren
 * Beschriftungen auf schmalen Geraeten sonst mit Ellipse enden.
 */
private val RecButtonSlotSize = 64.dp

/** Schatten der Kapselflaeche — dieselbe Zahl wie [OneUiNavigationBar]. */
private val RecButtonElevation = 8.dp

/** Groesse des ▶ im Ruhe- und Bereit-Zustand. */
private val RecPlayIconSize = 22.dp

/** Grundgroesse des Rings — knapp groesser als der Knopf, wie ein Halo. */
private val RecRingSize = 62.dp

/** Strichstaerke des Rings, ob pulsierend oder starr. */
private val RecRingStrokeWidth = 2.dp

/** Deckkraft des starren Rings (RouteReady bzw. pausierte Aufzeichnung). */
private const val StaticRingAlpha = 0.9f

/** Dauer eines Pulses — dezent, nicht hektisch. */
private const val PulseDurationMillis = 1600

/** Start- und Endgroesse des Pulses relativ zu [RecRingSize]. */
private const val PulseMinScale = 0.9f
private const val PulseMaxScale = 1.3f

/** Deckkraft, mit der der Puls startet, bevor er auf 0 auslaeuft. */
private const val PulseMaxAlpha = 0.85f

/** Punkt waehrend der Aufzeichnung, ueber der Fahrzeit. */
private val RecDotSmallSize = 10.dp

/** Schriftmasse des Mini-Labels im Kreis — klein, aber lesbar. */
private val RecLabelFontSize = 10.sp
private val RecLabelLineHeight = 11.sp

/**
 * Masse, die die Navigationshuelle fuer die Ausrichtung des Knopfs neben der
 * Kapsel braucht — veroeffentlicht, damit `TrailscapeApp` nicht mit
 * geratenen Zahlen gegen private Konstanten rechnet.
 */
object RecCapsuleButtonDefaults {
    /**
     * Unsichtbare Ringluft zwischen Knopfkreis und Slot-Rand. Wer den Kreis
     * buendig zu einer Kante ausrichten will (etwa auf die Randflucht der
     * Kapsel), zieht diese Luft vom gewuenschten Abstand ab.
     */
    val RingAllowance: Dp = (RecButtonSlotSize - RecButtonSize) / 2
}

// --------------------------------------------------------------- Reine Helfer

/**
 * Fahrzeit als „0:07", „12:34", „1:02:03" — Stunden nur, wenn welche vergangen
 * sind, Minuten ohne fuehrende Null unterhalb einer Stunde, Sekunden immer
 * zweistellig. Reicht unveraendert an [formatDuration] (`:core`, dort
 * getestet) weiter statt eine zweite Zeitformatierung zu erfinden.
 */
fun recElapsedLabel(elapsedMs: Long): String = formatDuration((elapsedMs / 1000).toInt())

/**
 * Streckenlabel wie „44,8 km" — immer eine Nachkommastelle, deutsches Komma,
 * nie „45 km" fuer glatte Werte. Dieselbe Zusammensetzung
 * `"${formatKmDe(x)} km"`, mit der auch jede andere Kennzahl der App eine
 * Kilometerzahl beschriftet (siehe `UiFormat.kt`).
 */
fun recRouteLabel(distanceKm: Double): String = "${formatKmDe(distanceKm)} km"
