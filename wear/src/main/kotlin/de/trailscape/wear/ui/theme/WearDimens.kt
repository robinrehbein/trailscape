package de.trailscape.wear.ui.theme

import androidx.compose.ui.unit.dp

/**
 * Die Masse dieser Uhr-App — bewusst nur zwei Zahlen fuer zwei Bildschirme.
 * Fuer alles Weitere gelten die Vorgaben von `androidx.wear.compose.material3`
 * direkt (`ButtonDefaults`, das `contentPadding` von `ScreenScaffold`).
 */

/**
 * Durchmesser der runden Knoepfe am unteren Bogen des Live-Bildschirms
 * (Pause/Weiter, Beenden).
 *
 * 48 dp ist dieselbe Mindestflaeche wie ueberall in der Telefon-App (siehe
 * `RideModeActionHeight`/`NeutralButton` in `app/.../ui/theme/Dimens.kt`) —
 * hier zugleich die Obergrenze: Auf dem runden 45-mm-Display der Watch Ultra
 * bleibt neben der grossen Herzfrequenz-Ziffer sonst kein Platz mehr fuer
 * zwei Knoepfe nebeneinander am Bogen.
 */
val LiveActionButtonSize = 48.dp

/**
 * Durchmesser des grossen Start-Knopfs.
 *
 * Deutlich ueber der Mindestflaeche, weil er auf dem Startbildschirm die
 * einzige Bedienung ist — One-UI-Watch zeigt seine Hauptaktion genauso
 * gross und mittig, nicht auf 48 dp verkleinert nur weil es die
 * Untergrenze waere.
 */
val StartButtonSize = 132.dp
