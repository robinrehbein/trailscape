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
