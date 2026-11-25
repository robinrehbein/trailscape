package de.trailscape.app.ui.indoor.garage

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import de.trailscape.core.garage.RiderAvatar
import de.trailscape.core.garage.RiderLook
import de.trailscape.core.garage.RiderZone
import kotlin.math.min
import kotlin.math.sqrt

/**
 * # 2D-Vorschau der Garage
 *
 * Eine gezeichnete Seitenansicht von Fahrer und Rad in den Farben der Zonen
 * ([RiderLook.zoneColors]) — dieselben Felder wie im 3D-Modell: Trikot von
 * unten nach oben (Bund, unten, Mitte, Brust, oben, Schultern und Aermel,
 * Kragen), dunkle Seitenteile, Hose, Helm, Schuhe, Rahmen mit Akzent,
 * Lenkerband, Haut.
 *
 * Bewusst 2D: Eine 3D-Vorschau braeuchte eine zweite Filament-Darstellung
 * neben der Fahrt (eigene Engine oder eine Szene ohne Strecke). Das ist ein
 * weiterer nativer Pfad, der auf manchen Geraeten den Prozess beenden kann
 * (docs/indoor/garage.md); die Zeichnung kann das nicht.
 */
@Composable
fun GaragePreview(avatar: RiderAvatar, description: String, modifier: Modifier = Modifier) {
    val zones = RiderLook.zoneColors(avatar)
    Canvas(modifier = modifier.semantics { contentDescription = description }) {
        val s = min(size.width / VIEW_W, size.height / VIEW_H)
        val dx = (size.width - VIEW_W * s) / 2f
        val dy = (size.height - VIEW_H * s) / 2f
        withTransform({
            translate(dx, dy)
            scale(s, s, pivot = Offset.Zero)
        }) {
            drawRider(zones)
        }
    }
}

/** Zeichenflaeche in eigenen Einheiten (Seitenansicht, Fahrtrichtung rechts). */
private const val VIEW_W = 320f
private const val VIEW_H = 200f

private val TIRE = Color(0xFF1C1C1D)
private val RIM = Color(0xFF5A5C61)
private val BLACK = Color(0xFF1D1D20)
private val GLOVE = Color(0xFF202126)
private val SOCK = Color(0xFFEEEEEA)
private val SOLE = Color(0xFF1E1E21)
private val GLASSES = Color(0xFF16181C)
private val GROUND = Color(0x22000000)

private fun srgb(c: Int): Color = Color(0xFF000000.toInt() or (c and 0xFFFFFF))

private fun Color.darker(k: Float): Color = Color(red * k, green * k, blue * k, alpha)

private fun DrawScope.limb(a: Offset, b: Offset, width: Float, color: Color) =
    drawLine(color, a, b, strokeWidth = width, cap = StrokeCap.Round)

private fun lerp(a: Offset, b: Offset, t: Float) = Offset(a.x + (b.x - a.x) * t, a.y + (b.y - a.y) * t)

private fun DrawScope.drawRider(zones: Map<RiderZone, Int>) {
    fun z(zone: RiderZone) = srgb(zones.getValue(zone))
    val skin = z(RiderZone.SKIN)

    // Boden
    drawLine(GROUND, Offset(20f, 192f), Offset(300f, 192f), strokeWidth = 3f, cap = StrokeCap.Round)

    // Laufraeder
    val rear = Offset(74f, 150f)
    val front = Offset(242f, 150f)
    for (c in listOf(rear, front)) {
        drawCircle(TIRE, radius = 40f, center = c, style = Stroke(width = 7f))
        drawCircle(RIM, radius = 34f, center = c, style = Stroke(width = 2f))
        drawCircle(BLACK, radius = 4f, center = c)
    }

    // Fernes Bein (abgedunkelt, hinter dem Rad)
    val hip = Offset(118f, 80f)
    val knee2 = Offset(160f, 118f)
    val ankle2 = Offset(130f, 158f)
    limb(hip, knee2, 20f, z(RiderZone.BIBS).darker(0.7f))
    limb(knee2, ankle2, 13f, skin.darker(0.7f))
    limb(ankle2, Offset(146f, 166f), 9f, z(RiderZone.SHOES).darker(0.7f))

    // Rahmen
    val bb = Offset(140f, 152f)
    val seatTop = Offset(124f, 92f)
    val headTop = Offset(212f, 94f)
    val headBottom = Offset(218f, 116f)
    val frame = z(RiderZone.FRAME)
    limb(bb, rear, 5f, frame)
    limb(seatTop, rear, 4.5f, frame)
    limb(bb, seatTop, 6.5f, frame)
    limb(seatTop, headTop, 6f, frame)
    limb(headBottom, bb, 7f, frame)
    limb(lerp(headBottom, bb, 0.3f), lerp(headBottom, bb, 0.7f), 7f, z(RiderZone.FRAME_ACCENT))
    limb(headTop, headBottom, 8f, frame)
    limb(headBottom, front, 5f, frame)
    // Sattelstuetze, Sattel, Vorbau
    limb(seatTop, Offset(121f, 85f), 4f, BLACK)
    limb(Offset(104f, 83f), Offset(134f, 84f), 5f, BLACK)
    limb(headTop, Offset(228f, 89f), 4.5f, BLACK)
    // Rennlenker mit Band
    val bar = Path().apply {
        moveTo(228f, 89f)
        cubicTo(238f, 89f, 243f, 96f, 241f, 104f)
        cubicTo(239f, 110f, 233f, 112f, 228f, 110f)
    }
    drawPath(bar, z(RiderZone.BAR_TAPE), style = Stroke(width = 5f, cap = StrokeCap.Round))
    // Kurbel
    drawCircle(BLACK, radius = 7f, center = bb)
    limb(bb, Offset(152f, 166f), 4f, BLACK)

    // Nahes Bein: Hose bis ueber das Knie, Abschluss etwas dunkler, Wade, Socke, Schuh
    val knee = Offset(156f, 110f)
    val ankle = Offset(150f, 150f)
    val bibs = z(RiderZone.BIBS)
    drawCircle(bibs, radius = 15f, center = hip)
    limb(hip, lerp(hip, knee, 0.85f), 21f, bibs)
    limb(lerp(hip, knee, 0.82f), lerp(hip, knee, 0.95f), 20f, bibs.darker(0.7f))
    limb(knee, lerp(knee, ankle, 0.75f), 14f, skin)
    limb(lerp(knee, ankle, 0.72f), ankle, 12f, SOCK)
    limb(ankle, Offset(168f, 156f), 10f, z(RiderZone.SHOES))
    limb(Offset(150f, 159f), Offset(168f, 160f), 3f, SOLE)

    // Oberkoerper: Felder entlang der Wirbelsaeule von der Huefte zur Schulter
    val shoulder = Offset(194f, 58f)
    drawTorso(hip, shoulder, zones)

    // Hals und Kopf mit Helm und Brille
    val head = Offset(214f, 38f)
    limb(Offset(198f, 55f), Offset(209f, 45f), 10f, skin)
    drawCircle(skin, radius = 12.5f, center = head)
    drawArc(
        color = z(RiderZone.HELMET),
        startAngle = 175f,
        sweepAngle = 195f,
        useCenter = true,
        topLeft = Offset(head.x - 15f, head.y - 17f),
        size = Size(31f, 31f),
    )
    limb(Offset(218f, 39f), Offset(226f, 40f), 3f, GLASSES)

    // Arm: Aermel in der Schulterfarbe mit Abschluss, Unterarm, Handschuh am Lenker
    val elbow = Offset(206f, 80f)
    val hand = Offset(232f, 90f)
    limb(lerp(shoulder, elbow, 0.62f), elbow, 10f, skin)
    limb(elbow, hand, 9f, skin)
    limb(shoulder, lerp(shoulder, elbow, 0.55f), 14f, z(RiderZone.JERSEY_YOKE))
    limb(lerp(shoulder, elbow, 0.5f), lerp(shoulder, elbow, 0.62f), 13f, z(RiderZone.JERSEY_TRIM))
    drawCircle(GLOVE, radius = 5.5f, center = hand)
}

/** Rumpf als Band entlang der Wirbelsaeule, in Feldern quer dazu; Seitenteil am Bauch entlang. */
private fun DrawScope.drawTorso(hip: Offset, shoulder: Offset, zones: Map<RiderZone, Int>) {
    fun z(zone: RiderZone) = srgb(zones.getValue(zone))
    val d = shoulder - hip
    val len = sqrt(d.x * d.x + d.y * d.y)
    val along = Offset(d.x / len, d.y / len)
    // Normale zum Bauch (nach unten vorn) und zum Ruecken.
    val belly = Offset(-along.y, along.x)
    val back = 15f
    val front = 17f

    fun quad(t0: Float, t1: Float, from: Float, to: Float, color: Color) {
        val a = lerp(hip, shoulder, t0)
        val b = lerp(hip, shoulder, t1)
        val p = Path().apply {
            moveTo(a.x + belly.x * from, a.y + belly.y * from)
            lineTo(b.x + belly.x * from, b.y + belly.y * from)
            lineTo(b.x + belly.x * to, b.y + belly.y * to)
            lineTo(a.x + belly.x * to, a.y + belly.y * to)
            close()
        }
        drawPath(p, color)
    }

    // Runde Enden zuerst (die Felder liegen darueber): an der Huefte die Hose, an der Schulter die Passe.
    drawCircle(z(RiderZone.BIBS), radius = (back + front) / 2f, center = lerp(hip, shoulder, 0.04f) + belly * ((front - back) / 2f))
    drawCircle(z(RiderZone.JERSEY_YOKE), radius = (back + front) / 2f - 1f, center = lerp(hip, shoulder, 0.9f) + belly * ((front - back) / 2f))

    val fields = listOf(
        0f to RiderZone.JERSEY_TRIM,
        0.06f to RiderZone.JERSEY_LOW,
        0.3f to RiderZone.JERSEY_MID,
        0.5f to RiderZone.JERSEY_BAND,
        0.62f to RiderZone.JERSEY_UP,
        0.8f to RiderZone.JERSEY_YOKE,
        0.96f to RiderZone.JERSEY_TRIM,
    )
    for ((i, f) in fields.withIndex()) {
        val t1 = if (i + 1 < fields.size) fields[i + 1].first else 1f
        quad(f.first, t1 + 0.002f, -back, front, z(f.second))
    }
    // Seitenteil: ein Streifen am Bauch entlang, unterhalb der Brust.
    quad(0.06f, 0.72f, front * 0.45f, front, z(RiderZone.JERSEY_SIDE))
}
