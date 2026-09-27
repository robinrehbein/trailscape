package de.trailscape.app.ui.map

import kotlin.math.max

// Reine Rechnung ohne Android: Wie viel Rand die Kamera beim Zentrieren und
// Einpassen um das Kartenblatt herum laesst (siehe [MapController.fitToPoints]
// und [MapController.moveTo]).

/**
 * Hoechstens dieser Anteil der Kartenhoehe darf als „vom Blatt verdeckt"
 * gelten. Ein ganz hochgezogenes Blatt kann hoeher sein als die Karte
 * darueber; ein Kamera-Rand in dieser Groesse liess MapLibre mit NaN rechnen
 * und die Karte schwarz stehen (Geraetebericht nach Welle 5).
 */
private const val MAX_OBSCURED_SHARE = 0.6

/** Mindestens so viele Pixel der Karte bleiben fuer das Einpassen frei. */
private const val MIN_FIT_AREA_PX = 96

/** Verdeckter Rand unten, begrenzt auf [MAX_OBSCURED_SHARE] der Kartenhoehe. */
internal fun clampObscuredBottom(obscuredBottomPx: Int, mapHeightPx: Int): Int {
    if (mapHeightPx <= 0) return 0
    return obscuredBottomPx.coerceIn(0, (mapHeightPx * MAX_OBSCURED_SHARE).toInt())
}

/**
 * Rand fuer das Einpassen einer Route: [base] plus der verdeckte Teil unten —
 * aber immer so, dass waagerecht und senkrecht mindestens [MIN_FIT_AREA_PX]
 * Karte frei bleiben. Sonst liefert `getCameraForLatLngBounds` keinen
 * sinnvollen Zoom.
 */
internal fun fitPaddingPx(
    mapWidthPx: Int,
    mapHeightPx: Int,
    base: MapPadding,
    obscuredBottomPx: Int,
): IntArray {
    val bottomWanted = max(base.bottom, clampObscuredBottom(obscuredBottomPx, mapHeightPx) + base.left)
    fun shrink(a: Int, b: Int, total: Int): Pair<Int, Int> {
        val room = total - MIN_FIT_AREA_PX
        if (room <= 0) return 0 to 0
        if (a + b <= room) return a to b
        val scale = room.toDouble() / (a + b)
        return (a * scale).toInt() to (b * scale).toInt()
    }
    val (left, right) = shrink(base.left, base.right, mapWidthPx)
    val (top, bottom) = shrink(base.top, bottomWanted, mapHeightPx)
    return intArrayOf(left, top, right, bottom)
}

/**
 * Passt einen in der Kamera **gespeicherten** Rand an eine neue Kartenhoehe
 * an — `null`, wenn er schon passt.
 *
 * [MapController.moveTo] und die Navi-Kamera legen ihren Rand in die
 * `CameraPosition`, und dort bleibt er, auch wenn die Karte danach kleiner
 * wird (Tastatur der Wegpunkt-Suche, Splitscreen, Drehen). Dann ist der
 * gespeicherte Rand ploetzlich hoeher als die Karte selbst — dieselbe Lage,
 * in der MapLibre mit NaN rechnet und die Karte schwarz oder starr stehen
 * bleibt. Deshalb nach jeder Groessenaenderung: unten hoechstens
 * [MAX_OBSCURED_SHARE] der Hoehe, oben so viel, dass mindestens
 * [MIN_FIT_AREA_PX] frei bleiben.
 *
 * @param padding links, oben, rechts, unten in Pixeln (MapLibre-Reihenfolge).
 */
internal fun reclampedCameraPadding(padding: DoubleArray, mapHeightPx: Int): DoubleArray? {
    if (padding.size < 4 || mapHeightPx <= 0) return null
    val bottom = padding[3].coerceIn(0.0, mapHeightPx * MAX_OBSCURED_SHARE)
    val top = padding[1].coerceIn(0.0, max(0.0, mapHeightPx - MIN_FIT_AREA_PX - bottom))
    if (bottom == padding[3] && top == padding[1]) return null
    return doubleArrayOf(padding[0], top, padding[2], bottom)
}
