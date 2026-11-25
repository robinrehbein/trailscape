package de.trailscape.app.ui.indoor.garage

import androidx.annotation.StringRes
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import de.trailscape.app.R
import de.trailscape.app.ui.components.Eyebrow
import de.trailscape.app.ui.indoor.IndoorPage
import de.trailscape.app.ui.indoor.MutedText
import de.trailscape.core.garage.GarageColor
import de.trailscape.core.garage.GaragePalettes
import de.trailscape.core.garage.GarageProgress
import de.trailscape.core.garage.GarageRequirement
import de.trailscape.core.garage.GarageSlot
import de.trailscape.core.garage.GarageUnlocks
import de.trailscape.core.garage.JerseyPattern
import de.trailscape.core.garage.RiderAvatar

/**
 * # Garage: Fahrer und Rad anpassen (docs/indoor/garage.md)
 *
 * Oben die gezeichnete Vorschau ([GaragePreview]), darunter drei Abschnitte —
 * **Fahrer** (Hautton, Helm), **Kleidung** (Trikot, Muster, Akzentfarbe, Hose,
 * Schuhe), **Rad** (Rahmen, Rahmen-Akzent, Lenkerband) — jeweils aus
 * kuratierten Paletten ([GaragePalettes]). Rein kosmetisch.
 *
 * Zustandslos: jede Wahl geht als neuer [RiderAvatar] an [onChange].
 * [progress] kommt spaeter aus den Erfolgen; `null` heisst „alles frei"
 * ([GarageUnlocks]). Gesperrtes zeigt seine Bedingung, nicht nur ein Schloss.
 */
@Composable
fun GarageScreen(
    avatar: RiderAvatar,
    onChange: (RiderAvatar) -> Unit,
    onBack: () -> Unit,
    progress: GarageProgress? = null,
    safeMode: Boolean = false,
) {
    IndoorPage(title = stringResource(R.string.garage_title), onBack = onBack) {
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                GaragePreview(
                    avatar = avatar,
                    description = stringResource(R.string.garage_preview_cd),
                    modifier = Modifier.fillMaxWidth().height(190.dp),
                )
                MutedText(stringResource(R.string.garage_preview_hint))
            }
        }
        if (safeMode) MutedText(stringResource(R.string.garage_safe_mode_hint))

        Eyebrow(stringResource(R.string.garage_section_rider))
        GarageCard {
            SkinRow(selected = avatar.skin, onSelect = { onChange(avatar.copy(skin = it)) })
            Divider()
            ColorRow(R.string.garage_slot_helmet, GarageSlot.HELMET, avatar.helmet, progress) { onChange(avatar.copy(helmet = it)) }
        }

        Eyebrow(stringResource(R.string.garage_section_kit))
        GarageCard {
            ColorRow(R.string.garage_slot_jersey, GarageSlot.JERSEY, avatar.jersey, progress) { onChange(avatar.copy(jersey = it)) }
            Divider()
            PatternRow(selected = avatar.pattern, progress = progress, onSelect = { onChange(avatar.copy(pattern = it)) })
            if (avatar.pattern != JerseyPattern.SOLID) {
                Divider()
                ColorRow(R.string.garage_slot_jersey_accent, GarageSlot.JERSEY_ACCENT, avatar.jerseyAccent, progress) {
                    onChange(avatar.copy(jerseyAccent = it))
                }
            }
            Divider()
            ColorRow(R.string.garage_slot_bibs, GarageSlot.BIBS, avatar.bibs, progress) { onChange(avatar.copy(bibs = it)) }
            Divider()
            ColorRow(R.string.garage_slot_shoes, GarageSlot.SHOES, avatar.shoes, progress) { onChange(avatar.copy(shoes = it)) }
        }

        Eyebrow(stringResource(R.string.garage_section_bike))
        GarageCard {
            ColorRow(R.string.garage_slot_frame, GarageSlot.FRAME, avatar.frame, progress) { onChange(avatar.copy(frame = it)) }
            Divider()
            ColorRow(R.string.garage_slot_frame_accent, GarageSlot.FRAME_ACCENT, avatar.frameAccent, progress) {
                onChange(avatar.copy(frameAccent = it))
            }
            Divider()
            ColorRow(R.string.garage_slot_bar_tape, GarageSlot.BAR_TAPE, avatar.barTape, progress) { onChange(avatar.copy(barTape = it)) }
        }

        MutedText(stringResource(R.string.garage_cosmetic_note))
        OutlinedButton(
            onClick = { onChange(RiderAvatar.DEFAULT) },
            enabled = avatar != RiderAvatar.DEFAULT,
            modifier = Modifier.fillMaxWidth(),
        ) { Text(stringResource(R.string.garage_reset_action)) }
    }
}

@Composable
private fun GarageCard(content: @Composable () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(vertical = 4.dp)) { content() }
    }
}

@Composable
private fun Divider() = HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))

@Composable
private fun RowTitle(text: String) {
    Text(text = text, style = MaterialTheme.typography.titleSmall)
}

/** Hauttoene: ohne Namen und ohne Sperre (sie sind keine Freischaltung). */
@Composable
private fun SkinRow(selected: Int, onSelect: (Int) -> Unit) {
    Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        RowTitle(stringResource(R.string.garage_slot_skin))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            GaragePalettes.SKIN.forEachIndexed { i, srgb ->
                Swatch(
                    color = swatchColor(srgb),
                    name = stringResource(R.string.garage_skin_tone_cd, i + 1),
                    selected = i == selected,
                    lockedHint = null,
                    onClick = { onSelect(i) },
                )
            }
        }
    }
}

@Composable
private fun ColorRow(@StringRes title: Int, slot: GarageSlot, selected: Int, progress: GarageProgress?, onSelect: (Int) -> Unit) {
    val palette = GaragePalettes.forSlot(slot)
    Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        RowTitle(stringResource(title))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            // Eine gespeicherte Farbe ausserhalb der Palette (etwa aus einer neueren Version) bleibt sichtbar gewaehlt.
            if (palette.none { it.srgb == selected }) {
                Swatch(
                    color = swatchColor(selected),
                    name = stringResource(R.string.garage_color_other, RiderAvatar.hex(selected)),
                    selected = true,
                    lockedHint = null,
                    onClick = {},
                )
            }
            for (c in palette) {
                val unlocked = GarageUnlocks.isUnlocked(c, progress)
                Swatch(
                    color = swatchColor(c.srgb),
                    name = stringResource(colorName(c)),
                    selected = c.srgb == selected,
                    lockedHint = if (unlocked) null else requirementText(GarageUnlocks.color(c)),
                    onClick = { onSelect(c.srgb) },
                )
            }
        }
    }
}

@Composable
private fun PatternRow(selected: JerseyPattern, progress: GarageProgress?, onSelect: (JerseyPattern) -> Unit) {
    Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        RowTitle(stringResource(R.string.garage_slot_pattern))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            for (p in JerseyPattern.entries) {
                val unlocked = GarageUnlocks.isUnlocked(p, progress)
                val name = stringResource(patternName(p))
                val hint = if (unlocked) null else requirementText(GarageUnlocks.pattern(p))
                FilterChip(
                    selected = p == selected,
                    onClick = { onSelect(p) },
                    enabled = unlocked,
                    label = { Text(if (hint == null) name else "$name · $hint") },
                    leadingIcon = if (p == selected || hint != null) {
                        {
                            Icon(
                                if (p == selected) Icons.Filled.Check else Icons.Filled.Lock,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp),
                            )
                        }
                    } else {
                        null
                    },
                )
            }
        }
    }
}

/** Ein Farbfeld (48 dp Tippflaeche), ausgewaehlt mit Haken, gesperrt mit Schloss und Bedingung im Vorlesetext. */
@Composable
private fun Swatch(color: Color, name: String, selected: Boolean, lockedHint: String?, onClick: () -> Unit) {
    val locked = lockedHint != null
    val description = when {
        locked -> stringResource(R.string.garage_option_locked_cd, name, lockedHint!!)
        selected -> stringResource(R.string.garage_option_selected_cd, name)
        else -> name
    }
    val ring = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant
    val mark = if (color.luminance() > 0.5f) Color(0xFF1D1D20) else Color.White
    Box(
        modifier = Modifier
            .size(48.dp)
            .clip(CircleShape)
            .selectable(selected = selected, enabled = !locked, role = Role.RadioButton, onClick = onClick)
            .semantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .size(40.dp)
                .alpha(if (locked) 0.45f else 1f)
                .border(BorderStroke(if (selected) 3.dp else 1.dp, ring), CircleShape)
                .padding(if (selected) 5.dp else 2.dp)
                .clip(CircleShape)
                .background(color),
            contentAlignment = Alignment.Center,
        ) {
            when {
                locked -> Icon(Icons.Filled.Lock, contentDescription = null, tint = mark, modifier = Modifier.size(16.dp))
                selected -> Icon(Icons.Filled.Check, contentDescription = null, tint = mark, modifier = Modifier.size(18.dp))
            }
        }
    }
}

private fun swatchColor(srgb: Int): Color = Color(0xFF000000.toInt() or (srgb and 0xFFFFFF))

@Composable
private fun requirementText(req: GarageRequirement): String? = when (req) {
    GarageRequirement.Free -> null
    is GarageRequirement.Level -> stringResource(R.string.garage_locked_level, req.level)
    is GarageRequirement.Badge -> stringResource(R.string.garage_locked_badge, req.number)
}

@StringRes
internal fun patternName(p: JerseyPattern): Int = when (p) {
    JerseyPattern.SOLID -> R.string.garage_pattern_solid
    JerseyPattern.BAND -> R.string.garage_pattern_band
    JerseyPattern.SPLIT -> R.string.garage_pattern_split
    JerseyPattern.FADE -> R.string.garage_pattern_fade
    JerseyPattern.STRIPES -> R.string.garage_pattern_stripes
}

@StringRes
internal fun colorName(c: GarageColor): Int = when (c.id) {
    "red" -> R.string.garage_color_red
    "orange" -> R.string.garage_color_orange
    "yellow" -> R.string.garage_color_yellow
    "lime" -> R.string.garage_color_lime
    "green" -> R.string.garage_color_green
    "teal" -> R.string.garage_color_teal
    "sky" -> R.string.garage_color_sky
    "blue" -> R.string.garage_color_blue
    "navy" -> R.string.garage_color_navy
    "purple" -> R.string.garage_color_purple
    "pink" -> R.string.garage_color_pink
    "white" -> R.string.garage_color_white
    "silver" -> R.string.garage_color_silver
    "grey" -> R.string.garage_color_grey
    "anthracite" -> R.string.garage_color_anthracite
    "black" -> R.string.garage_color_black
    "olive" -> R.string.garage_color_olive
    "brown" -> R.string.garage_color_brown
    "cork" -> R.string.garage_color_cork
    "gold" -> R.string.garage_color_gold
    "copper" -> R.string.garage_color_copper
    else -> R.string.garage_color_unnamed
}
