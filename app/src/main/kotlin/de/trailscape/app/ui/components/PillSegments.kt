package de.trailscape.app.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.intl.LocaleList
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/**
 * # Der Pillen-Umschalter (Fuehrung „Klartext")
 *
 * Eine graue Spur, darin das gewaehlte Segment als weisse, leicht gehobene
 * Pille — wie der One-UI-Umschalter und wie im Prototyp
 * (`docs/design/prototyp-klartext.html`, `.seg`). Er ersetzt die
 * Material-Segmentleiste, die mit Haken und Umriss aus dem Rest der App
 * herausfiel.
 *
 * Ueberall derselbe: „Runde ab hier" (Untergrund), Fahr-Cockpit
 * („Daten | Karte"). Im Verlauf stand frueher „Liste | Karte"; weil
 * „Karte" dort nur den Tab wechselte, ist es jetzt ein ausgeschriebener Knopf
 * (siehe `RidesScreen`).
 *
 * [trackColor] ist nur fuer Stellen, deren Untergrund selbst schon
 * `surfaceContainerHigh` ist (etwa ein [OneUiDialog]) — dort verschwaende die
 * Spur sonst, und nur die gewaehlte Pille stuende noch da.
 *
 * [optionLocales] markiert einzelne Beschriftungen mit ihrer eigenen Sprache
 * (Index wie [options], `null` = UI-Sprache). Noetig fuer Endonyme wie
 * „Deutsch"/„English" in der Sprachwahl: Ohne Sprachmarke liest TalkBack sie
 * mit der Stimme der UI-Sprache und spricht sie falsch aus.
 *
 * [optionDescriptions] ersetzt fuer TalkBack die Beschriftung einzelner
 * Segmente (Index wie [options], `null` = die Beschriftung selbst). Fuer
 * kurze Segmente, die erst mit ihrer Ueberschrift einen Sinn ergeben: „Wege"
 * allein sagt nichts, „Neu entdecken: Wege" schon.
 *
 * [role]: Ab Werk [Role.RadioButton] — fast ueberall ist der Umschalter eine
 * Auswahl unter Optionen (Untergrund, Sprache, Format, Dauer), und TalkBack
 * sagt dann „Optionsfeld, ausgewaehlt, 2 von 3". [Role.Tab] nur dort, wo er
 * zwischen zwei Ansichten wechselt (Training: „Fortschritt | Plan").
 */
@Composable
fun PillSegments(
    options: List<String>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
    trackColor: Color = MaterialTheme.colorScheme.surfaceContainerHigh,
    optionLocales: List<LocaleList?> = emptyList(),
    optionDescriptions: List<String?> = emptyList(),
    role: Role = Role.RadioButton,
) {
    val haptics = LocalHapticFeedback.current
    Surface(
        modifier = modifier.fillMaxWidth().height(SegmentTrackHeight),
        shape = CircleShape,
        color = trackColor,
    ) {
        Row(
            modifier = Modifier.padding(SegmentInset).selectableGroup(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            options.forEachIndexed { index, label ->
                val selected = index == selectedIndex
                val description = optionDescriptions.getOrNull(index)
                Surface(
                    modifier = Modifier
                        .weight(1f)
                        .height(SegmentTrackHeight - SegmentInset * 2)
                        .clip(CircleShape)
                        .selectable(
                            selected = selected,
                            role = role,
                            onClick = {
                                if (!selected) {
                                    haptics.performHapticFeedback(HapticFeedbackType.SegmentTick)
                                    onSelect(index)
                                }
                            },
                        )
                        .then(
                            if (description != null) {
                                Modifier.semantics { contentDescription = description }
                            } else {
                                Modifier
                            },
                        ),
                    shape = CircleShape,
                    color = if (selected) MaterialTheme.colorScheme.surfaceContainerHighest else Color.Transparent,
                    shadowElevation = if (selected) 1.dp else 0.dp,
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Text(
                            text = localeTagged(label, optionLocales.getOrNull(index)),
                            style = MaterialTheme.typography.labelLarge,
                            color = if (selected) {
                                MaterialTheme.colorScheme.onSurface
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(horizontal = 8.dp),
                        )
                    }
                }
            }
        }
    }
}

/** Hoehe der ganzen Spur. */
private val SegmentTrackHeight = 44.dp

/** Luft zwischen Spur und gewaehltem Segment. */
private val SegmentInset = 3.dp

/**
 * [text] als [AnnotatedString], bei gesetzter [locale] durchgehend mit dieser
 * Sprache markiert (wird fuer die Bedienungshilfen zur `LocaleSpan`).
 */
internal fun localeTagged(text: String, locale: LocaleList?): AnnotatedString =
    if (locale == null) {
        AnnotatedString(text)
    } else {
        AnnotatedString(text, spanStyle = SpanStyle(localeList = locale))
    }
