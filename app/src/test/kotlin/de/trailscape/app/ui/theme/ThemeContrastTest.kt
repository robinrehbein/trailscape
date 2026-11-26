package de.trailscape.app.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import kotlin.math.pow
import kotlin.test.Test
import kotlin.test.assertTrue

/** Prueft WCAG-2.1-Kontrast (>= 4,5:1 fuer normalen Text) in Atlas und Cockpit. */
class ThemeContrastTest {

    private fun relativeLuminance(c: Color): Double {
        fun channel(v: Float): Double {
            val d = v.toDouble()
            return if (d <= 0.03928) d / 12.92 else ((d + 0.055) / 1.055).pow(2.4)
        }
        return 0.2126 * channel(c.red) + 0.7152 * channel(c.green) + 0.0722 * channel(c.blue)
    }

    private fun contrastRatio(a: Color, b: Color): Double {
        val la = relativeLuminance(a)
        val lb = relativeLuminance(b)
        val lighter = maxOf(la, lb)
        val darker = minOf(la, lb)
        return (lighter + 0.05) / (darker + 0.05)
    }

    private fun assertAa(label: String, fg: Color, bg: Color, minRatio: Double = 4.5) {
        val ratio = contrastRatio(fg, bg)
        assertTrue(ratio >= minRatio, "$label hat nur Kontrast ${"%.2f".format(ratio)}:1 (erwartet >= $minRatio)")
    }

    @Test
    fun `Atlas Hell haelt WCAG AA fuer Text, Karten und orangefarbene Hauptaktion`() {
        assertAa("Light onBackground auf Background", LightOnBackground, LightBackground)
        assertAa("Light onSurface auf Karte", LightOnSurface, LightSurfaceContainerHighest)
        assertAa("Light onSurfaceVariant auf Karte", LightOnSurfaceVariant, LightSurfaceContainerHighest)
        assertAa("Light onSurfaceVariant auf Background", LightOnSurfaceVariant, LightBackground)
        assertAa("Light Primary auf Karte", LightPrimary, LightSurfaceContainerHighest)
        assertAa("Light Primary auf Background", LightPrimary, LightBackground)
        assertAa("Light onPrimary auf Primary", LightOnPrimary, LightPrimary)
        assertAa("Atlas Hell onAction auf Action-Orange", LightAtlasColors.onAction, LightAtlasColors.action)
        assertAa(
            "Atlas Hell onActionContainer auf ActionContainer",
            LightAtlasColors.onActionContainer,
            LightAtlasColors.actionContainer,
        )
    }

    @Test
    fun `Atlas Dunkel haelt WCAG AA fuer Text, Karten und orangefarbene Hauptaktion`() {
        assertAa("Dark onBackground auf Background", DarkOnBackground, DarkBackground)
        assertAa("Dark onSurface auf Karte", DarkOnSurface, DarkSurfaceContainerHighest)
        assertAa("Dark onSurfaceVariant auf Karte", DarkOnSurfaceVariant, DarkSurfaceContainerHighest)
        assertAa("Dark Primary auf Karte", DarkPrimary, DarkSurfaceContainerHighest)
        assertAa("Dark Primary auf Background", DarkPrimary, DarkBackground)
        assertAa("Dark onPrimary auf Primary", DarkOnPrimary, DarkPrimary)
        assertAa("Atlas Dunkel onAction auf Action-Orange", DarkAtlasColors.onAction, DarkAtlasColors.action)
        assertAa(
            "Atlas Dunkel onActionContainer auf ActionContainer",
            DarkAtlasColors.onActionContainer,
            DarkAtlasColors.actionContainer,
        )
    }

    @Test
    fun `Cockpit haelt hohen Kontrast fuer 1-m-Lesbarkeit`() {
        val c = DefaultCockpitColors
        assertAa("Cockpit ink auf background", c.ink, c.background, minRatio = 12.0)
        assertAa("Cockpit ink auf panel", c.ink, c.panel, minRatio = 12.0)
        assertAa("Cockpit inkMuted auf panel", c.inkMuted, c.panel, minRatio = 6.0)
        assertAa("Cockpit onAction auf action", c.onAction, c.action, minRatio = 4.5)
        assertAa("Cockpit good auf panel", c.good, c.panel, minRatio = 7.0)
        assertAa("Cockpit warning auf panel", c.warning, c.panel, minRatio = 6.0)
        assertAa("Cockpit danger auf panel", c.danger, c.panel, minRatio = 5.5)
    }

    @Test
    fun `Alpha-kompositierte Signal-Container halten WCAG AA in Hell und Dunkel`() {
        val lightContainers = listOf(
            "Light caution@0.16f auf Card" to (LightSignalColors.caution to LightSignalColors.caution.copy(alpha = 0.16f).compositeOver(LightSurfaceContainerHighest)),
            "Light caution@0.16f auf Background" to (LightSignalColors.caution to LightSignalColors.caution.copy(alpha = 0.16f).compositeOver(LightBackground)),
            "Light danger@0.14f auf Card" to (LightSignalColors.danger to LightSignalColors.danger.copy(alpha = 0.14f).compositeOver(LightSurfaceContainerHighest)),
            "Light danger@0.14f auf Background" to (LightSignalColors.danger to LightSignalColors.danger.copy(alpha = 0.14f).compositeOver(LightBackground)),
            "Light warning@0.15f auf Card" to (LightSignalColors.warning to LightSignalColors.warning.copy(alpha = 0.15f).compositeOver(LightSurfaceContainerHighest)),
            "Light warning@0.15f auf Background" to (LightSignalColors.warning to LightSignalColors.warning.copy(alpha = 0.15f).compositeOver(LightBackground)),
        )
        for ((label, pair) in lightContainers) {
            assertAa(label, pair.first, pair.second, minRatio = 4.5)
        }

        val darkContainers = listOf(
            "Dark caution@0.16f auf Card" to (DarkSignalColors.caution to DarkSignalColors.caution.copy(alpha = 0.16f).compositeOver(DarkSurfaceContainerHighest)),
            "Dark caution@0.16f auf Background" to (DarkSignalColors.caution to DarkSignalColors.caution.copy(alpha = 0.16f).compositeOver(DarkBackground)),
            "Dark danger@0.14f auf Card" to (DarkSignalColors.danger to DarkSignalColors.danger.copy(alpha = 0.14f).compositeOver(DarkSurfaceContainerHighest)),
            "Dark danger@0.14f auf Background" to (DarkSignalColors.danger to DarkSignalColors.danger.copy(alpha = 0.14f).compositeOver(DarkBackground)),
            "Dark warning@0.15f auf Card" to (DarkSignalColors.warning to DarkSignalColors.warning.copy(alpha = 0.15f).compositeOver(DarkSurfaceContainerHighest)),
            "Dark warning@0.15f auf Background" to (DarkSignalColors.warning to DarkSignalColors.warning.copy(alpha = 0.15f).compositeOver(DarkBackground)),
        )
        for ((label, pair) in darkContainers) {
            assertAa(label, pair.first, pair.second, minRatio = 4.5)
        }
    }
}
