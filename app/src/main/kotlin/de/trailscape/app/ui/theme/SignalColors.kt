package de.trailscape.app.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/**
 * # Die Ampel- und Akzentfarben der App — an EINER Stelle
 *
 * Alles, was nicht aus `MaterialTheme.colorScheme` kommt und trotzdem *auf*
 * einer Theme-Flaeche liegt (Readiness-Ampel, Erholungssignale, Wochentypen des
 * Trainingsplans, Warnhinweise im Mehr-Tab), steht hier. Vorher lagen dieselben
 * Farbwerte in vier Dateien verstreut — teils doppelt (`Color(0xFF4CAF50)`
 * gleich dreimal), teils als privates Duplikat in einem anderen Paket.
 *
 * ## Warum hell und dunkel getrennt
 * Die urspruenglichen Werte sind 1:1 die Flutter-Materialtoene der alten App
 * (`Colors.green`, `Colors.amber.shade800`, …). Auf dem dunklen Untergrund
 * (`DarkSurface` = #101410) ist etwa das Markengruen #2D5A3D als *Textfarbe*
 * praktisch unlesbar (Kontrast ≈ 1,7:1). Deshalb gibt es je Rolle einen
 * zweiten, aufgehellten Ton — dieselbe Logik, mit der Material 3 seine
 * Tonpalette im Dunkelmodus umdreht. Die Zuordnung Rolle → Bedeutung bleibt in
 * beiden Modi identisch, nur die Helligkeit passt sich an.
 *
 * Fuer den **Hellmodus** war dieselbe Pruefung nie gelaufen: Die Materialtoene
 * sind fuer weisse Flaechen als *Flaechen*farbe gedacht, nicht als Textfarbe,
 * und lagen deshalb allesamt unter dem AA-Kontrast. Sie sind jetzt
 * tonwerterhaltend abgedunkelt — die Rechnung steht bei [LightSignalColors].
 *
 * ## Abgrenzung zu `ui/map/MapColors.kt`
 * Die drei Kartenfarben dort liegen auf **Kachelbildern**, nicht auf einer
 * Theme-Flaeche; sie muessen in beiden Modi gleich bleiben, damit eine Tour
 * immer gleich aussieht. Sie gehoeren deshalb bewusst nicht hierher.
 *
 * ## Benutzung
 * ```kotlin
 * val signals = LocalSignalColors.current
 * Text("Achtung", color = signals.warning)
 * ```
 * Der Wert wird von [TrailscapeTheme] passend zum Hell-/Dunkelmodus gestellt.
 */
@Immutable
data class SignalColors(
    /** Grün: alles in Ordnung (Readiness „hart", grüne Erholungsampel). */
    val good: Color,
    /** Gelb/Bernstein: erste Stufe der Zurueckhaltung. */
    val caution: Color,
    /** Orange: deutliche Warnung (Deload, Belastungssprung, fehlende Route). */
    val warning: Color,
    /** Rot: Ruhe/Abbruch — die staerkste Ampelstufe. */
    val danger: Color,
    /** Materialgruen der Wochentypen „Aufbau" und des Fitnesslevel-Chips. */
    val accentGreen: Color,
    /** Materialblau des Wochentyps „Erholung". */
    val accentBlue: Color,
    /** Materialrot der „Zielwoche". */
    val accentRed: Color,
)

/**
 * Helle Fassung — dieselben Farbtoene wie die urspruenglichen Flutter-
 * Materialwerte, aber so weit abgedunkelt, dass sie auf [LightSurface]
 * (#F6FBF4) den WCAG-AA-Kontrast 4,5:1 fuer normalen Text erreichen.
 *
 * ## Warum das noetig war
 * Der Dunkelmodus wurde beim Umzug hierher aufgehellt (siehe unten), die
 * Gegenrichtung fehlte: Gegen die *helle* Flaeche gerechnet lagen die
 * Ausgangswerte durchweg unter der Schwelle — allen voran [caution] mit
 * 2,2:1, und genau dieser Ton traegt die Erholungszahl der Startseite
 * (`displaySmall`) im haeufigsten Fall (Band 60–79).
 *
 * ```
 * Rolle         vorher            nachher
 * caution       #FF8F00 2,18:1    #A55D00 4,81:1
 * warning       #EF6C00 2,94:1    #B45100 4,86:1
 * accentGreen   #4CAF50 2,65:1    #367D39 4,83:1
 * accentBlue    #2196F3 2,98:1    #1972B9 4,83:1
 * accentRed     #F44336 3,51:1    #CC382D 4,80:1
 * danger        #D32F2F 4,75:1    unveraendert
 * good          #2D5A3D 7,58:1    unveraendert
 * ```
 *
 * ## Wie abgedunkelt wurde
 * Streng **tonwerterhaltend**: Die drei sRGB-Kanaele werden mit demselben
 * Faktor skaliert, der HSL-Farbton bleibt dadurch auf ±0,2° gleich (z. B.
 * caution 33,6° → 33,8°). Die Ampel-Metapher traegt weiter — der Abstand
 * zwischen den beiden kritischen Nachbarstufen caution und warning bleibt mit
 * ΔE 12,8 (vorher 15,9) deutlich ueber der Wahrnehmungsschwelle.
 *
 * ## Auswirkung auf die getoenten Flaechen
 * [de.trailscape.app.ui.components.NoticeBox] legt dieselbe Farbe mit
 * `alpha = 0.12` als Flaeche unter den Text. Die Flaeche wird dadurch minimal
 * dunkler (L* 92–94 → L* 91–92, ΔE 1,6–4,3), bleibt also der blasse Hauch, der
 * sie war — eine eigene, hellere Flaechenfarbe je Rolle waere sieben
 * zusaetzliche Werte fuer einen Unterschied, den man nebeneinander gehalten
 * kaum sieht.
 */
internal val LightSignalColors = SignalColors(
    good = TrailscapeSeed,
    caution = Color(0xFFA55D00),
    warning = Color(0xFFB45100),
    danger = Color(0xFFD32F2F),
    accentGreen = Color(0xFF367D39),
    accentBlue = Color(0xFF1972B9),
    accentRed = Color(0xFFCC382D),
)

/**
 * Dunkle Fassung: dieselben Farbtoene, auf die dunkle Flaeche aufgehellt.
 * [SignalColors.good] ist bewusst `DarkPrimary` — im Dunkelmodus ist das
 * ohnehin die gruene Markenfarbe des Schemas, und Ampelgruen und Primaerfarbe
 * sollen nicht auseinanderlaufen.
 */
internal val DarkSignalColors = SignalColors(
    good = DarkPrimary,
    caution = Color(0xFFFFC246),
    warning = Color(0xFFFFA95E),
    danger = Color(0xFFFF8A7A),
    accentGreen = Color(0xFF81C784),
    accentBlue = Color(0xFF7EC0F5),
    accentRed = Color(0xFFFF8A80),
)

/**
 * Die aktuell gueltigen Ampelfarben. `static`, weil der Wert sich nur beim
 * Wechsel des Hell-/Dunkelmodus aendert — dann wird ohnehin alles neu gebaut.
 */
val LocalSignalColors = staticCompositionLocalOf { LightSignalColors }
