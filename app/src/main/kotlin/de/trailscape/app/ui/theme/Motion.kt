package de.trailscape.app.ui.theme

import androidx.compose.animation.ContentTransform
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp

/**
 * # Die Bewegung von One UI
 *
 * Samsung schreibt Bewegung mit zwei Zahlen fest: **Dauer zwischen 100 und
 * 500 ms** — darunter nimmt niemand die Bewegung wahr, darüber steht sie der
 * nächsten Handlung im Weg — und **eine** Basiskurve, die schnell anzieht und
 * sanft ausläuft (`cubic-bezier(0.22, 0.25, 0.00, 1.00)`, One UI Basic Path
 * Interpolator).
 *
 * Vorher stand in der App an keiner einzigen Stelle eine Kurve oder eine
 * Dauer. Animiert wurde entweder mit einer parameterlosen `spring()` oder mit
 * gar nichts — dann greift der Vorgabewert der Compose-Version. Beides ist
 * nicht falsch aussehend, aber es ist **unbestimmt**: Eine Feder hat
 * konstruktionsbedingt keine feste Dauer, und ein Bibliotheks-Vorgabewert
 * kann sich mit dem nächsten BOM-Update lautlos ändern. Was hier steht, steht
 * dagegen im Code und lässt sich prüfen.
 *
 * ## Benutzung
 * ```kotlin
 * val farbe by animateColorAsState(ziel, animationSpec = OneUiMotion.standard())
 * AnimatedVisibility(sichtbar, enter = fadeIn(OneUiMotion.standard()), …)
 * ```
 *
 * Wer eine neue Animation baut, nimmt eine dieser drei Dauern statt eine
 * vierte Zahl zu erfinden.
 */
object OneUiMotion {

    /**
     * Die Basiskurve von One UI. Schnell anziehen, sanft auslaufen — der
     * zweite Kontrollpunkt liegt bei `x = 0`, deshalb steht am Anfang die
     * ganze Beschleunigung und am Ende ein langer, ruhiger Auslauf.
     */
    val Easing: Easing = CubicBezierEasing(0.22f, 0.25f, 0.00f, 1.00f)

    /**
     * 200 ms — für kleine Zustandswechsel, die unmittelbar auf eine Berührung
     * folgen: Farbe eines Reiters, Skalierung eines Symbols, ein Punkt, der
     * zum Balken wird. Näher an der unteren Grenze, weil hier der Finger noch
     * auf dem Glas liegt und jede Verzögerung als Trägheit ankommt.
     */
    const val ShortMillis: Int = 200

    /**
     * 300 ms — der Regelfall: etwas klappt auf, ein Blatt rastet ein, eine
     * Fläche wechselt.
     */
    const val MediumMillis: Int = 300

    /**
     * 450 ms — für große Flächen, die den halben Bildschirm zurücklegen.
     * Bewusst unter den 500 ms, die der Leitfaden als Obergrenze nennt; wer
     * hier mehr braucht, bewegt zu viel auf einmal.
     */
    const val LongMillis: Int = 450

    /** [ShortMillis] mit der Basiskurve. */
    fun <T> short(): FiniteAnimationSpec<T> = tween(ShortMillis, easing = Easing)

    /** [MediumMillis] mit der Basiskurve — die Vorgabe, wenn nichts dagegen spricht. */
    fun <T> standard(): FiniteAnimationSpec<T> = tween(MediumMillis, easing = Easing)

    /** [LongMillis] mit der Basiskurve. */
    fun <T> long(): FiniteAnimationSpec<T> = tween(LongMillis, easing = Easing)
}

/**
 * # Die Übergänge nach Material 3
 *
 * Die sechs Übergangsmuster aus
 * [m3.material.io › Transitions](https://m3.material.io/styles/motion/transitions/transition-patterns)
 * und
 * [Applying transitions](https://m3.material.io/styles/motion/transitions/applying-transitions),
 * so umgesetzt wie in der Android-Implementierung, auf die der Leitfaden
 * selbst verweist (Material Components „Motion": Shared Axis, Fade Through).
 * Wo in der App welches Muster steht:
 *
 * | Muster | M3 | Hier |
 * |---|---|---|
 * | **Top level** (Fade through) | Navigationsleiste | Heute ↔ Karte ↔ Verlauf ↔ Training |
 * | **Forward and backward** (Shared axis X) | eine Ebene tiefer | Zahnrad → Einstellungen → Unterseite, Tour → Detail |
 * | **Enter and exit, über den Rand** | Blätter, Navigationsleiste | Kartenblätter, schwebende Kapsel |
 * | **Enter and exit, im Bildschirm** | Karten, Menüs, Snackbars | Orts-, Tour- und Aufnahmekarte über dem Blatt |
 * | **Lateral** | Tabs, Karussell | Einführung (Pager) |
 *
 * Die Zahlen: 300 ms insgesamt; beim Überblenden wird das Alte in den
 * ersten 35 % **ganz** ausgeblendet, erst danach blendet das Neue ein
 * („Clean fades" — keine halbdurchsichtigen Doppelbilder). Seitlich wandert
 * der Inhalt nur 30 dp statt der ganzen Bildschirmbreite („Android uses a
 * fade as screens slide … reduces the amount of motion"). Nichts federt
 * („Simple style — no bouncy springs").
 *
 * „Animationen entfernen" in den Bedienungshilfen stellt die
 * Animator-Dauer des Systems auf 0 — Compose springt dann bei allen
 * `tween`-Übergängen direkt ans Ende, ohne dass hier etwas zu tun ist.
 */
object M3Transitions {

    /** Gesamtdauer eines Seitenübergangs. */
    const val DurationMillis: Int = 300

    /** Das Ausblenden des Alten: die ersten 35 % der Dauer. */
    const val OutgoingMillis: Int = DurationMillis * 35 / 100

    /** Das Einblenden des Neuen: die übrigen 65 %, nach dem Ausblenden. */
    const val IncomingMillis: Int = DurationMillis - OutgoingMillis

    /** Seitlicher Weg beim Vor- und Zurückgehen. */
    val SlideDistance = 30.dp

    /** Anfangsgröße des neuen Ziels beim Wechsel zwischen den Tabs. */
    private const val FadeThroughScale = 0.92f

    /** Ein Blatt fährt herein … */
    private const val SheetEnterMillis: Int = 300

    /** … und schneller wieder hinaus (M3: Austritt beschleunigt, kürzer). */
    private const val SheetExitMillis: Int = 200

    // ------------------------------------------------------------ Top level

    /** Tabwechsel: das neue Ziel blendet ein und wächst von 92 % auf 100 %. */
    fun topLevelEnter(): EnterTransition =
        fadeIn(tween(IncomingMillis, delayMillis = OutgoingMillis, easing = LinearOutSlowInEasing)) +
            scaleIn(
                tween(IncomingMillis, delayMillis = OutgoingMillis, easing = LinearOutSlowInEasing),
                initialScale = FadeThroughScale,
            )

    /** Tabwechsel: das alte Ziel blendet schnell und vollständig aus. */
    fun topLevelExit(): ExitTransition =
        fadeOut(tween(OutgoingMillis, easing = FastOutLinearInEasing))

    // ------------------------------------------------- Forward and backward

    /**
     * Eine Ebene tiefer ([forward] = true) oder zurück: Das Neue kommt 30 dp
     * aus Laufrichtung und blendet nach dem Alten ein.
     */
    fun sharedAxisXEnter(forward: Boolean, density: Density): EnterTransition {
        val distance = with(density) { SlideDistance.roundToPx() }
        return slideInHorizontally(tween(DurationMillis, easing = FastOutSlowInEasing)) {
            if (forward) distance else -distance
        } + fadeIn(tween(IncomingMillis, delayMillis = OutgoingMillis, easing = LinearOutSlowInEasing))
    }

    /** Gegenstück zu [sharedAxisXEnter]: Das Alte weicht 30 dp gegen die Laufrichtung. */
    fun sharedAxisXExit(forward: Boolean, density: Density): ExitTransition {
        val distance = with(density) { SlideDistance.roundToPx() }
        return slideOutHorizontally(tween(DurationMillis, easing = FastOutSlowInEasing)) {
            if (forward) -distance else distance
        } + fadeOut(tween(OutgoingMillis, easing = FastOutLinearInEasing))
    }

    /** Beide Hälften als ein [ContentTransform] für `AnimatedContent`. */
    fun sharedAxisX(forward: Boolean, density: Density): ContentTransform =
        sharedAxisXEnter(forward, density) togetherWith sharedAxisXExit(forward, density)

    // ------------------------------------------------------ Enter and exit

    /**
     * Ein Blatt oder die Navigationsleiste kommt über den unteren Rand herein
     * — ohne Überblenden („Don't fade a bottom sheet as it enters and exits").
     */
    fun sheetEnter(): EnterTransition =
        slideInVertically(tween(SheetEnterMillis, easing = LinearOutSlowInEasing)) { it }

    /** Gegenstück zu [sheetEnter]: beschleunigt über den unteren Rand hinaus. */
    fun sheetExit(): ExitTransition =
        slideOutVertically(tween(SheetExitMillis, easing = FastOutLinearInEasing)) { it }

    /**
     * Eine Karte im Bildschirm (über dem Blatt): klappt entlang der Hochachse
     * von unten auf, weg vom Rand, mit kurzem Einblenden.
     */
    fun cardEnter(): EnterTransition =
        expandVertically(tween(SheetEnterMillis, easing = LinearOutSlowInEasing), expandFrom = Alignment.Bottom) +
            fadeIn(tween(OutgoingMillis, easing = LinearOutSlowInEasing))

    /** Gegenstück zu [cardEnter]. */
    fun cardExit(): ExitTransition =
        shrinkVertically(tween(SheetExitMillis, easing = FastOutLinearInEasing), shrinkTowards = Alignment.Bottom) +
            fadeOut(tween(OutgoingMillis, easing = FastOutLinearInEasing))
}
