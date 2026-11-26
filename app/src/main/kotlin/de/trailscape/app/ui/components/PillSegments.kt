package de.trailscape.app.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.intl.LocaleList
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.roundToInt

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
    val baseStyle = MaterialTheme.typography.labelLarge
    Surface(
        modifier = modifier.fillMaxWidth().heightIn(min = SegmentTrackHeight),
        shape = CircleShape,
        color = trackColor,
    ) {
        ProportionalSegmentRow(
            modifier = Modifier
                .padding(SegmentInset)
                .selectableGroup(),
        ) {
            options.forEachIndexed { index, label ->
                val selected = index == selectedIndex
                val description = optionDescriptions.getOrNull(index)
                val contentColor = if (selected) {
                    MaterialTheme.colorScheme.onSurface
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                }
                Surface(
                    modifier = Modifier
                        .heightIn(min = SegmentTrackHeight - SegmentInset * 2)
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
                    Box(
                        modifier = Modifier.padding(horizontal = SegmentLabelPadding, vertical = 6.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        BasicText(
                            text = localeTagged(label, optionLocales.getOrNull(index)),
                            style = baseStyle.copy(color = contentColor),
                            maxLines = 1,
                            softWrap = false,
                            autoSize = TextAutoSize.StepBased(
                                minFontSize = 11.sp,
                                maxFontSize = baseStyle.fontSize,
                                stepSize = 0.5.sp,
                            ),
                        )
                    }
                }
            }
        }
    }
}

/**
 * Verteilt die Segmentbreiten nach natuerlichem Textbedarf statt starrer
 * Gleichverteilung, damit laengere Woerter („Gemeinsam", „Fortschritt") bei
 * schmalen Bildschirmen und grosser Schrift nicht abgeschnitten werden.
 */
@Composable
private fun ProportionalSegmentRow(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Layout(
        modifier = modifier,
        content = content,
    ) { measurables, constraints ->
        if (measurables.isEmpty()) {
            return@Layout layout(constraints.minWidth, constraints.minHeight) {}
        }
        val availableWidth = constraints.maxWidth.coerceAtLeast(1)
        val count = measurables.size
        val naturalWidths = measurables.map { it.maxIntrinsicWidth(constraints.maxHeight).coerceAtLeast(1) }
        val naturalSum = naturalWidths.sum()
        val targetWidths = if (naturalSum <= availableWidth) {
            val extra = availableWidth - naturalSum
            val perItem = extra / count
            var remainder = extra % count
            naturalWidths.map { w ->
                val bonus = if (remainder > 0) {
                    remainder--
                    1
                } else {
                    0
                }
                w + perItem + bonus
            }
        } else {
            val minSlot = (availableWidth / (count * 2)).coerceAtLeast(1)
            val scaled = MutableList(count) { i ->
                ((naturalWidths[i].toLong() * availableWidth) / naturalSum).toInt().coerceAtLeast(minSlot)
            }
            var diff = availableWidth - scaled.sum()
            while (diff != 0) {
                var changed = false
                for (i in scaled.indices) {
                    if (diff == 0) break
                    if (diff > 0) {
                        scaled[i]++
                        diff--
                        changed = true
                    } else if (scaled[i] > minSlot) {
                        scaled[i]--
                        diff++
                        changed = true
                    }
                }
                if (!changed) break
            }
            scaled
        }

        val minH = if (constraints.hasBoundedHeight) constraints.minHeight else 0
        val placeables = measurables.mapIndexed { index, measurable ->
            measurable.measure(
                Constraints(
                    minWidth = targetWidths[index],
                    maxWidth = targetWidths[index],
                    minHeight = minH,
                    maxHeight = constraints.maxHeight,
                ),
            )
        }
        val rowHeight = placeables.maxOf { it.height }.coerceIn(constraints.minHeight, constraints.maxHeight)
        layout(availableWidth, rowHeight) {
            var x = 0
            placeables.forEach { placeable ->
                val y = (rowHeight - placeable.height) / 2
                placeable.placeRelative(x, y)
                x += placeable.width
            }
        }
    }
}

/** Hoehe der ganzen Spur. */
private val SegmentTrackHeight = 44.dp

/** Luft zwischen Spur und gewaehltem Segment. */
private val SegmentInset = 3.dp

/** Waagerechtes Innenpolster eines einzelnen Segments. */
private val SegmentLabelPadding = 10.dp

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
