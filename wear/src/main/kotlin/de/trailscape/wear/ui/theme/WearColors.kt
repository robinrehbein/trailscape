package de.trailscape.wear.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * Die EINE Akzentfarbe der Uhr-App (siehe Vorgabe: "eine Akzentfarbe,
 * One-UI-Watch-Anmutung").
 *
 * Woertlich der Wert von `DarkPrimary`/`DarkOnPrimary` aus
 * `app/.../ui/theme/Color.kt` — nicht importiert, weil `:wear` bewusst nicht
 * von `:app` abhaengt (die beiden Module kennen sich nur ueber `:core`s
 * Wire-Vertrag, siehe WearProtocol.kt), sondern von Hand synchron gehalten.
 * Der DUNKLE Ton der Telefon-App ist hier richtig, nicht der satte
 * `TrailscapeSeed`: Diese App zeigt immer einen fast schwarzen Grund (Wear
 * Compose Material3s Standard-`ColorScheme` ist bereits dunkel), und genau
 * fuer diesen Kontrast ist die Telefon-App-eigene Dunkelmodus-Aufhellung
 * gedacht.
 *
 * Wer diese Werte aendert, aendert sie zusammen mit `DarkPrimary`/
 * `DarkOnPrimary` drueben — sonst laufen Telefon- und Uhr-Gruen langsam
 * auseinander.
 */
val AccentGreen = Color(0xFF6BD69C)

/** Zeichenfarbe AUF [AccentGreen] (Icon im grossen Start-Knopf, im Pause/Weiter-Knopf). */
val OnAccentGreen = Color(0xFF00391D)

/**
 * Gedaempfte Schrift fuer die stille Verbindungs-Unterzeile — woertlich
 * `DarkOnSurfaceVariant` aus der Telefon-App (dieselbe Herkunfts-Begruendung
 * wie bei [AccentGreen]).
 */
val MutedText = Color(0xFFBFC3C9)
