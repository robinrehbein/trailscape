package de.trailscape.app.ui.theme

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.tween

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
