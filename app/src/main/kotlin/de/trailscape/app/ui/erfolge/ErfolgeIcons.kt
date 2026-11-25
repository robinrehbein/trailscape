package de.trailscape.app.ui.erfolge

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathBuilder
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp
import de.trailscape.core.AchievementGroup
import de.trailscape.core.AchievementId

/**
 * # Symbole der Erfolge — selbst gezeichnet
 *
 * Einfache, einfarbige Liniensymbole auf einem 24er-Raster, Strichstaerke 2,
 * runde Enden — dieselbe Sprache wie die „Rounded"-Symbole der App. Keine
 * fremden Grafiken: Jede Linie steht hier im Code. Die Farbe kommt beim
 * Zeichnen aus `Icon(tint = …)`; der schwarze Strich ist nur Platzhalter.
 */
internal object ErfolgeIcons {

    /** Medaille an zwei Baendern — Stufe und das Abzeichen allgemein. */
    val Medal: ImageVector by lazy {
        icon("ErfolgeMedal") {
            moveTo(8f, 3f)
            lineTo(10.5f, 9.5f)
            moveTo(16f, 3f)
            lineTo(13.5f, 9.5f)
            circle(12f, 15f, 5.5f)
            moveTo(12f, 12.8f)
            lineTo(12f, 17.2f)
        }
    }

    /** Laufrad mit Speichen — einzelne Touren. */
    val Wheel: ImageVector by lazy {
        icon("ErfolgeWheel") {
            circle(12f, 12f, 8.5f)
            circle(12f, 12f, 2f)
            moveTo(12f, 3.5f)
            lineTo(12f, 10f)
            moveTo(12f, 14f)
            lineTo(12f, 20.5f)
            moveTo(3.5f, 12f)
            lineTo(10f, 12f)
            moveTo(14f, 12f)
            lineTo(20.5f, 12f)
        }
    }

    /** Drei wachsende Saeulen — Summen. */
    val Bars: ImageVector by lazy {
        icon("ErfolgeBars") {
            moveTo(3.5f, 20.5f)
            lineTo(20.5f, 20.5f)
            moveTo(6.5f, 17f)
            lineTo(6.5f, 13f)
            moveTo(12f, 17f)
            lineTo(12f, 9f)
            moveTo(17.5f, 17f)
            lineTo(17.5f, 4.5f)
        }
    }

    /** Kalenderblatt mit Haken — Woche fuer Woche dranbleiben. */
    val Calendar: ImageVector by lazy {
        icon("ErfolgeCalendar") {
            moveTo(5f, 6f)
            lineTo(19f, 6f)
            lineTo(19f, 20f)
            lineTo(5f, 20f)
            close()
            moveTo(5f, 10f)
            lineTo(19f, 10f)
            moveTo(8.5f, 3.5f)
            lineTo(8.5f, 7.5f)
            moveTo(15.5f, 3.5f)
            lineTo(15.5f, 7.5f)
            moveTo(9f, 15f)
            lineTo(11f, 17f)
            lineTo(15f, 13f)
        }
    }

    /** Klemmbrett mit Haken — Trainingsplan. */
    val Plan: ImageVector by lazy {
        icon("ErfolgePlan") {
            moveTo(9f, 4.5f)
            lineTo(6f, 4.5f)
            lineTo(6f, 21f)
            lineTo(18f, 21f)
            lineTo(18f, 4.5f)
            lineTo(15f, 4.5f)
            moveTo(9f, 3f)
            lineTo(15f, 3f)
            lineTo(15f, 6.5f)
            lineTo(9f, 6.5f)
            close()
            moveTo(9f, 13.5f)
            lineTo(11f, 15.5f)
            lineTo(15f, 11.5f)
        }
    }

    /** Haus mit Rad darin — drinnen auf dem Rollentrainer. */
    val Indoor: ImageVector by lazy {
        icon("ErfolgeIndoor") {
            moveTo(3f, 11f)
            lineTo(12f, 4f)
            lineTo(21f, 11f)
            moveTo(5.5f, 9.5f)
            lineTo(5.5f, 20f)
            lineTo(18.5f, 20f)
            lineTo(18.5f, 9.5f)
            circle(12f, 14.5f, 3f)
        }
    }

    /** Berg mit Fahne — Anstiege, Runden und neue Wege. */
    val Explore: ImageVector by lazy {
        icon("ErfolgeExplore") {
            moveTo(2.5f, 20f)
            lineTo(9.5f, 9f)
            lineTo(13.5f, 15f)
            lineTo(16f, 11.5f)
            lineTo(21.5f, 20f)
            close()
            moveTo(9.5f, 9f)
            lineTo(9.5f, 3f)
            lineTo(14f, 4.5f)
            lineTo(9.5f, 6f)
        }
    }

    /** Sanduhr — kommt bald. */
    val Soon: ImageVector by lazy {
        icon("ErfolgeSoon") {
            moveTo(7f, 3f)
            lineTo(17f, 3f)
            moveTo(7f, 21f)
            lineTo(17f, 21f)
            moveTo(8f, 3f)
            lineTo(16f, 3f)
            lineTo(12f, 12f)
            lineTo(16f, 21f)
            lineTo(8f, 21f)
            lineTo(12f, 12f)
            close()
        }
    }

    /** Zwei Balken — Pausenwoche. */
    val Pause: ImageVector by lazy {
        icon("ErfolgePause") {
            moveTo(9f, 5f)
            lineTo(9f, 19f)
            moveTo(15f, 5f)
            lineTo(15f, 19f)
        }
    }

    /** Das Symbol der Gruppe von [id]. */
    fun forBadge(id: AchievementId): ImageVector = forGroup(id.group)

    fun forGroup(group: AchievementGroup): ImageVector = when (group) {
        AchievementGroup.RIDES -> Wheel
        AchievementGroup.TOTALS -> Bars
        AchievementGroup.ROUTINE -> Calendar
        AchievementGroup.PLAN -> Plan
        AchievementGroup.INDOOR -> Indoor
        AchievementGroup.EXPLORE -> Explore
    }

    private fun icon(name: String, block: PathBuilder.() -> Unit): ImageVector =
        ImageVector.Builder(
            name = name,
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f,
        ).apply {
            path(
                fill = null,
                stroke = SolidColor(Color.Black),
                strokeLineWidth = 2f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
                pathBuilder = block,
            )
        }.build()

    /** Ein Kreis aus zwei Halbboegen. */
    private fun PathBuilder.circle(cx: Float, cy: Float, r: Float) {
        moveTo(cx - r, cy)
        arcToRelative(r, r, 0f, true, true, 2 * r, 0f)
        arcToRelative(r, r, 0f, true, true, -2 * r, 0f)
        close()
    }
}
