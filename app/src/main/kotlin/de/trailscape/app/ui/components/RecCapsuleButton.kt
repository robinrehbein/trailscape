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
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
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
import de.trailscape.app.ui.formatKmDe
import de.trailscape.app.ui.theme.LocalNavigationBarColors
import de.trailscape.core.formatDuration

/**
 * # Der abgesetzte ●-Knopf neben der Kapsel
 *
 * Die Designstudie „Eine Leiste" (`docs/design/ui-navigationsstudien.html`,
 * Kapitel „Eine Leiste"; Prototyp `docs/design/prototyp-eine-leiste.html`)
 * loest ein Problem, das jede fruehere Fassung der Navigation hatte: Eine
 * Fahrt starten ist keine Navigation zwischen gleichrangigen Bereichen
 * (Heute, Karte, Touren, Training) — es ist die **eine** Handlung, wegen der
 * die App ueberhaupt existiert. Steckt der Aufzeichnen-Knopf als fuenftes
 * Ziel *in* der [OneUiNavigationBar], sieht er wie ein Reiter unter vieren
 * aus und verliert genau dieses Gewicht; Samsung Health loest dasselbe
 * Problem, indem die runde Aktion **neben** der Pille schwebt, sichtbar
 * abgesetzt, aber im selben Atemzug aus derselben Flaeche gebaut. Dieser
 * Knopf ist dieser Nachbar: dieselbe Flaechenfarbe wie die Kapsel im Ruhe-
 * zustand ([LocalNavigationBarColors]), dieselbe Randlicht-plus-Schatten-
 * Schichtung ([OneUiNavigationBar]) — beide wirken dadurch als **ein**
 * Ensemble, nicht als zwei zufaellig benachbarte Bauteile.
 *
 * ## Die drei Zustaende
 * [RecButtonState.Idle] — keine Route geladen, keine Aufzeichnung: neutrale
 * Kapselflaeche, mittig ein Punkt in der Akzentfarbe. [RecButtonState
 * .RouteReady] — der Karten-Tab hat eine Route vorbereitet: dieselbe Flaeche,
 * zusaetzlich ein duenner Akzent-Ring und darunter die Kilometerzahl, damit
 * die Bereitschaft schon aus der Ferne ablesbar ist, ohne den Knopf zu
 * beruehren. [RecButtonState.Recording] — die Aufzeichnung laeuft: Die
 * Flaeche kippt auf die Akzentfarbe (dieselbe Umkehrung, die eine gefuellte
 * Kachel im Fahrmodus zeigt), der Ring pulsiert weich, solange wirklich
 * aufgezeichnet wird, und wird bei einer Pause bewusst **starr** — ein
 * stehender statt eines laufenden Rings ist hier die Auskunft "angehalten",
 * nicht nur eine Nebenwirkung der Animation.
 *
 * ## Warum das Label nicht das Layout verschiebt
 * Das Mini-Label unter dem Knopf (Kilometer bzw. Fahrzeit) darf die
 * Beruehrungsflaeche nicht wandern lassen, wenn ein Zustand mit Label auf
 * einen ohne folgt — sonst zielt der zweite Fingertipp knapp daneben. Und es
 * darf auch die **Ausrichtung** nicht verzerren: Der Nachbar in der Zeile ist
 * die Navigationskapsel, und deren Mitte soll mit der Mitte des *Kreises*
 * fluchten, nicht mit der Mitte aus Kreis plus Anhaengsel. Der Wurzel-[Box]
 * misst deshalb nur den quadratischen Knopf-Slot (Kreis samt symmetrischer
 * Ringluft); das Label haengt per [Alignment.BottomCenter] und einem
 * `offset` **unterhalb** dieses Rahmens, ohne Hoehe zu beanspruchen — es
 * ragt in den Randstreifen ueber der Gestenleiste, wo nichts anderes wohnt.
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
    val navColors = LocalNavigationBarColors.current
    val haptics = LocalHapticFeedback.current
    val isRecording = state is RecButtonState.Recording
    val paused = (state as? RecButtonState.Recording)?.paused == true

    val containerColor = if (isRecording) MaterialTheme.colorScheme.primary else navColors.container
    val dotColor = if (isRecording) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.primary
    val ringColor = MaterialTheme.colorScheme.primary

    val label = when (state) {
        is RecButtonState.Idle -> null
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

    val showStaticRing = state is RecButtonState.RouteReady || (isRecording && paused)
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
                // Das Randlicht der Kapsel bleibt der Ruheflaeche vorbehalten
                // — auf der satten Akzentflaeche des Aufzeichnungszustands
                // wuerde ein zusaetzlicher heller Saum nur unruhig wirken.
                border = if (isRecording) null else BorderStroke(1.dp, navColors.rim),
                shadowElevation = RecButtonElevation,
            ) {
                Box(
                    modifier = Modifier
                        .size(RecDotSize)
                        .clip(CircleShape)
                        .background(dotColor),
                )
            }
        }

        if (label != null) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall.copy(
                    fontWeight = FontWeight.Bold,
                    fontFamily = if (isRecording) FontFamily.Monospace else FontFamily.Default,
                ),
                color = MaterialTheme.colorScheme.primary,
                maxLines = 1,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    // Haengt unterhalb des gemessenen Rahmens (siehe
                    // Kopfkommentar, "Warum das Label nicht das Layout
                    // verschiebt").
                    .offset(y = RecLabelGap + RecLabelSlotHeight)
                    // Die Auskunft steckt schon im `contentDescription` des
                    // Knopfs; ein tickendes Zweitlabel wuerde die
                    // Bildschirmlesehilfe bei jeder Sekunde erneut
                    // ansagen lassen.
                    .clearAndSetSemantics {},
            )
        }
    }
}

/** Beruehrbare Groesse des runden Knopfs selbst. */
private val RecButtonSize = 56.dp

/**
 * Fester Platzbedarf des gesamten Bausteins (Knopf plus der Luft, die der
 * Ring beim Pulsieren braucht). Bleibt ueber alle drei Zustaende hinweg
 * gleich, damit der Knopf niemals wandert, wenn ein Ring erscheint oder
 * verschwindet.
 */
private val RecButtonSlotSize = 84.dp

/** Schatten der Kapselflaeche — dieselbe Zahl wie [OneUiNavigationBar]. */
private val RecButtonElevation = 8.dp

/** Durchmesser des Punkts in der Mitte des Knopfs. */
private val RecDotSize = 14.dp

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

/** Abstand zwischen Knopf und Mini-Label. */
private val RecLabelGap = 2.dp

/** Fester Platz fuer das Mini-Label — reserviert, auch wenn es (Idle) fehlt. */
private val RecLabelSlotHeight = 14.dp

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
