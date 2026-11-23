package de.trailscape.core.indoor.world

import java.io.IOException
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * # Luftbilder aus den amtlichen WMS der Laender
 *
 * Je ENU-Rechteck ein `GetMap` mit `CRS=EPSG:4326` ([TileMath.wmsGetMapUrl]).
 * Weil die ENU-Abbildung in Laenge und Breite linear ist, liegt das Bild
 * exakt auf dem Rechteck. Die Bytes werden spaeter **unveraendert** in die
 * GLB eingebettet — deshalb wird progressives JPEG (das Filament nicht
 * zuverlaessig laedt) durch eine PNG-Anfrage ersetzt.
 *
 * Erkannt und verworfen (`null`, der Weltbau nimmt dann den erzeugten Boden):
 * Fehlerstatus, WMS-Fehlermeldungen (XML/`ServiceException`, die Dienste
 * antworten damit oft mit Status 200), unlesbare Bilder und **Leerbilder**
 * (ausserhalb der Abdeckung liefern die Dienste ein weisses Bild).
 */

/**
 * Ein Luftbild: [bytes] genau wie geliefert, [mimeType] `image/jpeg`
 * (Baseline) oder `image/png`.
 */
internal class AerialImage(
    val bytes: ByteArray,
    val mimeType: String,
    val width: Int,
    val height: Int,
    val source: AerialSource,
)

/** Anteil identischer Pixel, ab dem ein Bild als leer gilt. */
internal const val BLANK_IDENTICAL_SHARE: Double = 0.98

/** Streuung je Farbkanal (0–255), unter der ein Bild als einfarbig gilt. */
internal const val BLANK_MAX_STDDEV: Double = 1.5

/**
 * Die Dienste aus [sources], deren Umgebungsrechteck die Mitte von [box]
 * enthaelt, in Registerreihenfolge. Die Rechtecke der Laender ueberlappen;
 * welcher Dienst die Stelle wirklich abdeckt, zeigt erst die Antwort (Leerbild).
 */
internal fun selectAerialSources(sources: List<AerialSource>, box: LatLonBox): List<AerialSource> =
    sources.filter { it.covers(box.centerLat, box.centerLon) }

/**
 * Holt das Luftbild fuer das ENU-Rechteck vom ersten passenden Dienst, der
 * kein Leerbild liefert. Die laengere Kante bekommt [sizePx] Pixel
 * (hoechstens `maxSizePx` des Dienstes), die kuerzere im Seitenverhaeltnis.
 *
 * @return `null`, wenn kein Dienst passt, alle passenden einen Fehler oder ein
 *   Leerbild liefern oder das Netz fehlt.
 * @throws IllegalArgumentException wenn die URL nicht auf der Positivliste steht (Programmfehler).
 */
internal fun fetchAerial(
    fetcher: GeoFetcher,
    decoder: RasterDecoder,
    origin: GeoOrigin,
    minE: Double,
    minN: Double,
    maxE: Double,
    maxN: Double,
    sizePx: Int,
    sources: List<AerialSource>,
): AerialImage? {
    require(maxE > minE && maxN > minN) { "Leeres Luftbild-Rechteck" }
    require(sizePx > 0) { "Bildgroesse muss positiv sein" }
    val box = TileMath.enuToLatLon(origin, minE, minN, maxE, maxN)
    for (source in selectAerialSources(sources, box)) {
        fetchFrom(fetcher, decoder, source, box, maxE - minE, maxN - minN, sizePx)?.let { return it }
    }
    return null
}

private fun fetchFrom(
    fetcher: GeoFetcher,
    decoder: RasterDecoder,
    source: AerialSource,
    box: LatLonBox,
    widthM: Double,
    heightM: Double,
    sizePx: Int,
): AerialImage? {
    val longest = minOf(sizePx, source.maxSizePx)
    val w: Int
    val h: Int
    if (widthM >= heightM) {
        w = longest
        h = max(1, (longest * heightM / widthM).roundToInt())
    } else {
        h = longest
        w = max(1, (longest * widthM / heightM).roundToInt())
    }
    return try {
        val jpeg = fetchOne(fetcher, decoder, source, box, w, h, "image/jpeg") ?: return null
        if (jpeg.mimeType == "image/jpeg" && isProgressiveJpeg(jpeg.bytes)) {
            fetchOne(fetcher, decoder, source, box, w, h, "image/png")
        } else {
            jpeg
        }
    } catch (_: IOException) {
        null
    }
}

private fun fetchOne(
    fetcher: GeoFetcher,
    decoder: RasterDecoder,
    source: AerialSource,
    box: LatLonBox,
    w: Int,
    h: Int,
    format: String,
): AerialImage? {
    val url = TileMath.wmsGetMapUrl(source, box, w, h, format)
    val resp = fetcher.fetch(GeoRequest(GeoSourceKind.AERIAL, url, accept = format))
    if (resp.status != 200 || isWmsError(resp.contentType, resp.bytes)) return null
    val mime = when {
        isJpeg(resp.bytes) -> "image/jpeg"
        isPng(resp.bytes) -> "image/png"
        else -> return null
    }
    val img = decoder.decode(resp.bytes) ?: return null
    if (isBlankImage(img)) return null
    return AerialImage(resp.bytes, mime, img.width, img.height, source)
}

internal fun isJpeg(b: ByteArray): Boolean =
    b.size >= 3 && b[0] == 0xFF.toByte() && b[1] == 0xD8.toByte() && b[2] == 0xFF.toByte()

internal fun isPng(b: ByteArray): Boolean =
    b.size >= 8 && b[0] == 0x89.toByte() && b[1] == 'P'.code.toByte() && b[2] == 'N'.code.toByte() &&
        b[3] == 'G'.code.toByte() && b[4] == 0x0D.toByte() && b[5] == 0x0A.toByte() &&
        b[6] == 0x1A.toByte() && b[7] == 0x0A.toByte()

/**
 * `true` bei einer WMS-Fehlermeldung: XML-Inhaltstyp oder Bytes, die mit
 * `<` (nach Leerraum/BOM) beginnen oder `ServiceException` enthalten.
 */
internal fun isWmsError(contentType: String?, bytes: ByteArray): Boolean {
    if (isJpeg(bytes) || isPng(bytes)) return false
    val ct = contentType?.lowercase().orEmpty()
    if ("xml" in ct || "html" in ct || ct.startsWith("text/")) return true
    val head = String(bytes, 0, minOf(bytes.size, 2048), Charsets.ISO_8859_1).trimStart('﻿', 'ï', '»', '¿', ' ', '\n', '\r', '\t')
    return head.startsWith("<") || "ServiceException" in head
}

/**
 * Progressives JPEG: Marker `FF C2` (bzw. `C6`, `CA`, `CE`) vor dem ersten
 * `FF DA` (Start of Scan). Laeuft die Segmentkette ins Leere, gilt das Bild
 * als nicht progressiv.
 */
internal fun isProgressiveJpeg(b: ByteArray): Boolean {
    if (!isJpeg(b)) return false
    var i = 2
    while (i + 3 < b.size) {
        if (b[i] != 0xFF.toByte()) return false
        val marker = b[i + 1].toInt() and 0xFF
        when {
            marker == 0xFF -> { i++; continue } // Fuellbyte
            marker == 0xDA -> return false
            marker == 0xC2 || marker == 0xC6 || marker == 0xCA || marker == 0xCE -> return true
            marker == 0xD8 || marker in 0xD0..0xD7 || marker == 0x01 -> { i += 2; continue }
        }
        val len = ((b[i + 2].toInt() and 0xFF) shl 8) or (b[i + 3].toInt() and 0xFF)
        if (len < 2) return false
        i += 2 + len
    }
    return false
}

/**
 * Leerbild: mehr als [BLANK_IDENTICAL_SHARE] identische Pixel, ueberwiegend
 * durchsichtig, oder fast keine Streuung in allen Kanaelen
 * ([BLANK_MAX_STDDEV]) — so sehen die Antworten ausserhalb der Abdeckung aus.
 */
internal fun isBlankImage(img: RasterImage): Boolean {
    if (img.argb.isEmpty()) return true
    // Stichprobe von hoechstens 2^18 Pixeln, sortiert: der laengste Lauf ist die haeufigste Farbe.
    val stride = maxOf(1, img.argb.size / (1 shl 18))
    val px = IntArray((img.argb.size + stride - 1) / stride) { img.argb[it * stride] }
    val sorted = px.copyOf().also { it.sort() }
    var best = 0
    var run = 0
    for (k in sorted.indices) {
        run = if (k > 0 && sorted[k] == sorted[k - 1]) run + 1 else 1
        if (run > best) best = run
    }
    var transparent = 0
    var sr = 0.0; var sg = 0.0; var sb = 0.0
    var qr = 0.0; var qg = 0.0; var qb = 0.0
    for (p in px) {
        if ((p ushr 24) < 16) transparent++
        val r = ((p ushr 16) and 0xFF).toDouble()
        val g = ((p ushr 8) and 0xFF).toDouble()
        val b = (p and 0xFF).toDouble()
        sr += r; sg += g; sb += b
        qr += r * r; qg += g * g; qb += b * b
    }
    val n = px.size.toDouble()
    if (best > BLANK_IDENTICAL_SHARE * n || transparent > BLANK_IDENTICAL_SHARE * n) return true
    fun sd(s: Double, q: Double) = sqrt(maxOf(0.0, q / n - (s / n) * (s / n)))
    return sd(sr, qr) < BLANK_MAX_STDDEV && sd(sg, qg) < BLANK_MAX_STDDEV && sd(sb, qb) < BLANK_MAX_STDDEV
}
