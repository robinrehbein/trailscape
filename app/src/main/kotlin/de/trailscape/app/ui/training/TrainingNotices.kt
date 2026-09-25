package de.trailscape.app.ui.training

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import de.trailscape.app.ui.components.NoticeBox
import de.trailscape.app.ui.defaultTrainingProfile
import de.trailscape.app.ui.theme.CardPadding
import de.trailscape.app.ui.theme.LocalSignalColors

/**
 * # Hinweise im Trainings-Tab — leise, und hoechstens eine Warnflaeche
 *
 * Frueher stapelten sich hier bis zu drei orange getoente Flaechen
 * (Profil-Hinweis, „Plan und Ziel passen nicht zusammen", Anpassungs-Notiz),
 * und die Info-Icons wechselten zwischen gefuellt orange, Umriss orange und
 * gefuellt schwarz. Drei gleich laute Warnungen sind keine Warnung mehr —
 * man liest keine davon.
 *
 * Deshalb gilt im Tab:
 *  * **Eine Warnflaeche pro Screen.** Stehen mehrere Warnungen an, bekommt
 *    die wichtigste ([trainingNoticeLayout]) die Karte, die anderen werden
 *    zu ruhigen Zeilen ([QuietNoteLine]).
 *  * **Ein Icon-Stil.** Immer die Umriss-Icons: [TrainingInfoIcon] in
 *    `onSurfaceVariant` fuer Information, [TrainingWarningIcon] in der
 *    Warnfarbe nur fuer eine echte Warnung.
 */

/** Umriss-Info — reine Information, nie in Warnfarbe. */
internal val TrainingInfoIcon: ImageVector get() = Icons.Outlined.Info

/** Umriss-Warndreieck — nur fuer echte Warnungen, dann in Warnfarbe. */
internal val TrainingWarningIcon: ImageVector get() = Icons.Outlined.WarningAmber

/**
 * Die Warnungen, die oben im Tab um Aufmerksamkeit konkurrieren — in der
 * Reihenfolge ihrer Wichtigkeit.
 *
 * Der Plan, der sein Ziel nicht traegt, geht vor: Er betrifft genau die Frage,
 * fuer die man den Tab oeffnet („Schaffe ich mein Ziel?"). Das fehlende Profil
 * macht die Zahlen nur grober — das ist wahr, aber nachrangig.
 */
internal enum class TrainingWarning { PLAN_FEASIBILITY, PROFILE }

/**
 * Welche Warnung die Karte bekommt ([card]) und welche als ruhige Zeile
 * stehen ([quiet]). Leer, wenn nichts ansteht.
 */
internal data class TrainingNoticeLayout(
    val card: TrainingWarning?,
    val quiet: Set<TrainingWarning>,
)

/**
 * Verteilt die anstehenden Warnungen: die wichtigste (Reihenfolge von
 * [TrainingWarning]) als Karte, alle weiteren als ruhige Zeilen — so steht
 * nie mehr als eine Flaeche in Warnfarbe auf dem Screen.
 *
 * @param feasibilityOpen „Plan und Ziel passen nicht zusammen" steht an (und
 *   ist nicht quittiert).
 * @param profileMissing Alter und Gewicht sind noch Standardwerte.
 */
internal fun trainingNoticeLayout(feasibilityOpen: Boolean, profileMissing: Boolean): TrainingNoticeLayout {
    val pending = TrainingWarning.entries.filter {
        when (it) {
            TrainingWarning.PLAN_FEASIBILITY -> feasibilityOpen
            TrainingWarning.PROFILE -> profileMissing
        }
    }
    return TrainingNoticeLayout(card = pending.firstOrNull(), quiet = pending.drop(1).toSet())
}

/** Der Satz des Profil-Hinweises — eine Stelle fuer Karte und ruhige Zeile. */
internal val unconfirmedProfileText: String =
    "Ohne Alter und Gewicht rechnen wir mit ${defaultTrainingProfile.ageYears} Jahren und " +
        "${defaultTrainingProfile.weightKg.toInt()} kg — die Zahlen sind grob."

/** Beschriftung der Aktion am Profil-Hinweis. */
internal const val PROFILE_ACTION_LABEL = "Profil öffnen"

/**
 * Eine ruhige Hinweiszeile: Umriss-Info, `bodySmall` in `onSurfaceVariant`,
 * optional eine Textaktion rechts. Keine Flaeche — sie erklaert, sie warnt
 * nicht.
 *
 * Links um [CardPadding] eingerueckt wie die Abschnittsueberschriften: So
 * steht das Icon buendig mit dem Text in den Karten darueber und darunter,
 * statt am Kartenrand zu kleben.
 */
@Composable
internal fun QuietNoteLine(
    text: String,
    modifier: Modifier = Modifier,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
) {
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(start = CardPadding, end = 4.dp),
    ) {
        // Das Icon sitzt auf der ersten Textzeile, nicht mittig vor einem
        // mehrzeiligen Absatz — dort wirkte es verrutscht.
        Icon(
            TrainingInfoIcon,
            contentDescription = null,
            tint = muted,
            modifier = Modifier.padding(top = 1.dp).size(16.dp),
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = text,
            style = MaterialTheme.typography.bodySmall,
            color = muted,
            modifier = Modifier.weight(1f),
        )
        if (actionLabel != null && onAction != null) {
            TextButton(
                onClick = onAction,
                contentPadding = PaddingValues(horizontal = 12.dp),
                modifier = Modifier.align(Alignment.CenterVertically),
            ) {
                Text(actionLabel)
            }
        }
    }
}

/**
 * Der Hinweis, dass die Zahlen dieses Tabs auf Standardwerten beruhen.
 *
 * Mit eigener Aktion statt nur antippbarer Flaeche: Eine Flaeche, die man
 * antippen kann, ohne dass sie so aussieht, ist eine versteckte Funktion.
 * „Profil öffnen" springt in die Profilkarte der Einstellungen — genau
 * dorthin, wo Alter und Gewicht hingehoeren.
 *
 * @param asCard `true`, wenn dieser Hinweis die eine Warnflaeche des Screens
 *   ist; sonst steht er als ruhige Zeile ([trainingNoticeLayout]).
 */
@Composable
internal fun UnconfirmedProfileNotice(asCard: Boolean, onOpenProfile: () -> Unit) {
    if (asCard) {
        NoticeBox(
            icon = TrainingWarningIcon,
            color = LocalSignalColors.current.caution,
            title = "Profil eintragen",
            text = unconfirmedProfileText,
            action = {
                TextButton(onClick = onOpenProfile, contentPadding = PaddingValues(horizontal = 12.dp)) {
                    Text(PROFILE_ACTION_LABEL)
                }
            },
        )
    } else {
        QuietNoteLine(
            text = unconfirmedProfileText,
            actionLabel = PROFILE_ACTION_LABEL,
            onAction = onOpenProfile,
        )
    }
}
