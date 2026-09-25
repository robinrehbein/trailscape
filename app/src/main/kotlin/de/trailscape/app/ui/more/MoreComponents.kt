package de.trailscape.app.ui.more

import de.trailscape.app.ui.components.Eyebrow
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import de.trailscape.app.ui.theme.CardPadding
import de.trailscape.app.ui.theme.OneUiMotion

/**
 * Gemeinsame Bausteine der Einstellungen (Route „mehr", siehe
 * `MoreScreen.kt`).
 *
 * Die Einstellungen sind eine flache Liste aus Gruppen ([MoreGroup]); jede
 * Zeile ([SettingsNavRow]) nennt Titel und Zustand und oeffnet beim Antippen
 * ihre eigene Seite. Auf den Seiten liegen Abschnitte ([SettingsSection]) mit
 * Schaltern ([SettingsSwitchRow]), Feldern und Knoepfen
 * ([SettingsSecondaryButton]). Alle Seiten benutzen dieselben Bausteine —
 * frueher gab es vier Bauarten von Schalter-Zeilen und einen nackten
 * Schalter.
 */

/**
 * Versales Gruppenlabel ueber einer [MoreGroupCard] — One-UI-Einstellungen
 * kennzeichnen Gruppen so, nicht mit einer eigenen Karten-Ueberschrift pro
 * Karte. Der linke Einzug entspricht [CardPadding], damit das Label ueber dem
 * Zeilentext der Karte darunter steht statt darueber hinauszuragen.
 */
@Composable
fun MoreGroupLabel(text: String, modifier: Modifier = Modifier) {
    // Dieselbe Abschnittsueberschrift wie auf allen Screens (Fuehrung
    // „Klartext"): Satzschreibung, halbfett, buendig mit dem Kartentext.
    Eyebrow(
        text = text,
        modifier = modifier.padding(start = CardPadding, top = 8.dp, bottom = 4.dp),
    )
}

/**
 * Die eine Karte einer Gruppe: keine eigene Farbe oder Rundung (erbt beides
 * aus dem Theme, wie jede andere Karte der App), aber auch **kein**
 * Innenabstand auf Kartenebene — den setzt jede Zeile fuer sich, sonst
 * bekaeme die erste Zeile doppelten Abstand nach oben.
 */
@Composable
fun MoreGroupCard(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Card(modifier = modifier.fillMaxWidth(), content = content)
}

/**
 * [MoreGroupLabel] (falls [label] gesetzt) und [MoreGroupCard] als eine
 * Einheit — der uebliche Aufruf einer Gruppe in der Liste.
 */
@Composable
fun MoreGroup(label: String?, modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(modifier = modifier.fillMaxWidth()) {
        label?.let { MoreGroupLabel(text = it) }
        MoreGroupCard(content = content)
    }
}

/**
 * Eine Zeile der Einstellungsliste: Titel, darunter **immer** eine einzeilige
 * Statuszeile, rechts ein Pfeil. Antippen oeffnet die Seite der Zeile.
 *
 * Die Statuszeile ist Pflicht und nicht optional: Sie beantwortet die Frage,
 * wegen der man die Liste meist oeffnet („ist das an?", „wann zuletzt
 * gesichert?"), ohne dass man hineintippen muss. Die fruehere
 * Akkordeon-Zeile hatte dafuer einen Parameter, den nie jemand gesetzt hat.
 *
 * @param statusColor abweichende Farbe der Statuszeile — nur fuer Zustaende,
 *   die Handeln verlangen (z. B. „Noch nie gesichert" in der Warnfarbe).
 */
@Composable
fun SettingsNavRow(
    title: String,
    status: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    statusColor: Color = Color.Unspecified,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = CardPadding, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text = title, style = MaterialTheme.typography.titleMedium)
            Text(
                text = status,
                style = MaterialTheme.typography.bodySmall,
                color = if (statusColor == Color.Unspecified) {
                    MaterialTheme.colorScheme.onSurfaceVariant
                } else {
                    statusColor
                },
                maxLines = 1,
            )
        }
        Spacer(modifier = Modifier.width(8.dp))
        Icon(
            imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
            // Der Titel sagt schon alles; der Pfeil ist nur Wegweiser.
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * Ein Abschnitt einer Einstellungsseite: optionales Gruppenlabel und eine
 * Karte mit Innenabstand, in die der Inhalt untereinander faellt.
 */
@Composable
fun SettingsSection(
    label: String? = null,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        label?.let { MoreGroupLabel(text = it) }
        MoreGroupCard {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(CardPadding),
                content = content,
            )
        }
    }
}

/**
 * **Die** Schalter-Zeile aller Einstellungsseiten: Titel, optionale kurze
 * Erklaerung, rechts der Schalter.
 *
 * Die ganze Zeile schaltet (`toggleable` mit [Role.Switch]) — ein 32 dp
 * breiter Schalter ist ein unnoetig kleines Ziel, wenn daneben ohnehin nichts
 * anderes liegt. Der [Switch] selbst ist nur Anzeige (`onCheckedChange =
 * null`), sonst kaeme das Ereignis doppelt. One UI bildet die Bewegung eines
 * Schalters haptisch nach, der Leitfaden nennt Schalter ausdruecklich als Ort
 * dafuer.
 *
 * @param enabled `false` fuer Unterschalter, deren Hauptschalter aus ist —
 *   die Zeile bleibt sichtbar (man sieht, was ein Einschalten mitbraechte),
 *   ist aber nicht bedienbar und gedimmt.
 * @param indented rueckt Unterschalter unter ihren Hauptschalter ein.
 */
@Composable
fun SettingsSwitchRow(
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    enabled: Boolean = true,
    indented: Boolean = false,
) {
    val haptics = LocalHapticFeedback.current
    val hintColor = MaterialTheme.colorScheme.onSurfaceVariant
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .toggleable(
                value = checked,
                enabled = enabled,
                role = Role.Switch,
                onValueChange = {
                    haptics.performHapticFeedback(
                        if (it) HapticFeedbackType.ToggleOn else HapticFeedbackType.ToggleOff,
                    )
                    onCheckedChange(it)
                },
            )
            .padding(start = if (indented) 16.dp else 0.dp)
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                color = if (enabled) MaterialTheme.colorScheme.onSurface else hintColor,
            )
            subtitle?.let {
                Text(text = it, style = MaterialTheme.typography.bodySmall, color = hintColor)
            }
        }
        Spacer(modifier = Modifier.width(12.dp))
        Switch(checked = checked, onCheckedChange = null, enabled = enabled)
    }
}

/**
 * Nebenaktion innerhalb einer Einstellungskarte — getoente Flaeche
 * (`secondaryContainer`), 48 dp hoch.
 *
 * Bewusst **nicht** `NeutralButton`: Dessen Flaeche ist
 * `surfaceContainerHighest`, also exakt die Farbe der Karte selbst — auf
 * einer Karte war der Knopf nur noch an seiner Schrift zu erkennen.
 *
 * @param destructive dieselbe Flaeche in der Fehler-Tonung („Alle löschen").
 */
@Composable
fun SettingsSecondaryButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    destructive: Boolean = false,
    content: @Composable RowScope.() -> Unit,
) {
    FilledTonalButton(
        onClick = onClick,
        modifier = modifier.heightIn(min = 48.dp),
        enabled = enabled,
        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 8.dp),
        colors = if (destructive) {
            ButtonDefaults.filledTonalButtonColors(
                containerColor = MaterialTheme.colorScheme.errorContainer,
                contentColor = MaterialTheme.colorScheme.onErrorContainer,
            )
        } else {
            ButtonDefaults.filledTonalButtonColors()
        },
        content = content,
    )
}

/** Ein kurzer Hinweissatz unter einem Feld oder Schalter. */
@Composable
fun SettingsHint(text: String, modifier: Modifier = Modifier, color: Color = Color.Unspecified) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = if (color == Color.Unspecified) MaterialTheme.colorScheme.onSurfaceVariant else color,
        modifier = modifier,
    )
}

/**
 * „Mehr erfahren" — ein flacher Knopf, der eine laengere Erklaerung
 * aufklappt. Der Normalfall auf einer Einstellungsseite ist ein Satz pro
 * Hinweis; was darueber hinausgeht (Herleitung einer Schaetzung, Anleitung
 * zu einem Feldtest), steht hier und stoert niemanden, der es schon weiss.
 */
@Composable
fun LearnMore(text: String, modifier: Modifier = Modifier) {
    var open by rememberSaveable { mutableStateOf(false) }
    Column(modifier = modifier.fillMaxWidth()) {
        TextButton(
            onClick = { open = !open },
            contentPadding = PaddingValues(0.dp),
            // Der Text wechselt, aber eine Vorlesehilfe soll auch den Zustand
            // als solchen melden.
            modifier = Modifier.semantics {
                stateDescription = if (open) "Aufgeklappt" else "Zugeklappt"
            },
        ) { Text(if (open) "Weniger anzeigen" else "Mehr erfahren") }
        AnimatedVisibility(
            visible = open,
            // Der Leitfaden verlangt eine Dauer zwischen 100 und 500 ms auf
            // der One-UI-Kurve — ohne Spec griffe der Compose-Vorgabewert.
            enter = expandVertically(OneUiMotion.standard()) + fadeIn(OneUiMotion.standard()),
            exit = shrinkVertically(OneUiMotion.standard()) + fadeOut(OneUiMotion.standard()),
        ) {
            SettingsHint(text = text)
        }
    }
}
