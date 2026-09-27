package de.trailscape.app.ui.rides

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.Shader
import android.graphics.Typeface
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.text.TextUtils
import androidx.compose.ui.graphics.toArgb
import androidx.core.content.FileProvider
import de.trailscape.app.R
import de.trailscape.app.i18n.languageOf
import de.trailscape.app.ui.prepareShareDirectory
import de.trailscape.app.ui.theme.DarkPrimary
import de.trailscape.core.Ride
import java.io.File
import kotlin.math.max
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/*
 * # Das Tour-Bild zeichnen
 *
 * Setzt [shareCardLayout] und [ShareCardContent] mit `android.graphics` in
 * eine Bitmap um; gerechnet wird hier nichts, was sich testen liesse — das
 * steht in `ShareCard.kt`.
 *
 * ## Ein festes, dunkles Design
 * Unabhaengig vom App-Thema: Ein geteiltes Bild soll ueberall gleich aussehen,
 * und ein dunkler Grund traegt in Stories, wo helle Flaechen neben Fotos
 * grell wirken. Die Farben sind die Markenfarben des dunklen Themas.
 */

/**
 * Oberer Ton des Hintergrundverlaufs — Markengruen, fast schwarz. Auch die
 * Flaeche, die der Teilen-Dialog zeigt, solange die Vorschau entsteht.
 */
internal const val BG_TOP: Int = 0xFF0F1A14.toInt()

/** Unterer Ton des Hintergrundverlaufs. */
private const val BG_BOTTOM: Int = 0xFF1C3226.toInt()

private const val TEXT_PRIMARY: Int = 0xFFFFFFFF.toInt()
private const val TEXT_DATE: Int = 0xB3FFFFFF.toInt()
private const val TEXT_LABEL: Int = 0x99FFFFFF.toInt()
private const val TEXT_WORDMARK: Int = 0x80FFFFFF.toInt()

private const val DATE_SIZE = 36f
private const val WORDMARK_SIZE = 40f

private const val TRACK_GLOW_WIDTH = 26f
private const val TRACK_WIDTH = 10f
private const val START_RADIUS = 14f
private const val FINISH_RADIUS = 12f
private const val PROFILE_LINE_WIDTH = 4f

/** Die Akzentfarbe des dunklen Themas, mit neuem Alpha (0..255). */
private fun accent(alpha: Int): Int = (DarkPrimary.toArgb() and 0x00FFFFFF) or (alpha shl 24)

/**
 * Zeichnet das Tour-Bild. [scale] verkleinert fuer die Vorschau (0,5 ergibt
 * 540 px Breite); gezeichnet wird immer in den 1080er-Koordinaten des Layouts.
 */
internal fun renderShareCard(content: ShareCardContent, format: ShareCardFormat, scale: Float = 1f): Bitmap {
    val layout = shareCardLayout(format, content.hasTrack, content.hasProfile)
    val bitmap = Bitmap.createBitmap(
        max(1, (layout.width * scale).toInt()),
        max(1, (layout.height * scale).toInt()),
        Bitmap.Config.ARGB_8888,
    )
    val canvas = Canvas(bitmap)
    canvas.scale(scale, scale)

    drawBackground(canvas, layout)
    layout.track?.let { drawTrack(canvas, fitPolyline(content.trackUnit, it)) }
    layout.profile?.let { drawProfile(canvas, profilePolyline(content.profile, it), it) }
    drawHeader(canvas, layout, content)
    drawStats(canvas, layout, content.stats)
    drawWordmark(canvas, layout)
    return bitmap
}

private fun drawBackground(canvas: Canvas, layout: ShareCardLayout) {
    val w = layout.width.toFloat()
    val h = layout.height.toFloat()
    val base = Paint().apply {
        shader = LinearGradient(0f, 0f, 0f, h, BG_TOP, BG_BOTTOM, Shader.TileMode.CLAMP)
    }
    canvas.drawRect(0f, 0f, w, h, base)

    // Ein weicher Lichthof hinter der Spur (ohne Spur hinter den Zahlen),
    // damit der Grund nicht flach wirkt.
    val focus = layout.track ?: layout.stats
    val cx = (focus.left + focus.right) / 2
    val cy = (focus.top + focus.bottom) / 2
    val radius = max(focus.width, focus.height) * 0.75f
    val glow = Paint().apply {
        shader = RadialGradient(cx, cy, radius, accent(0x26), accent(0x00), Shader.TileMode.CLAMP)
    }
    canvas.drawRect(0f, 0f, w, h, glow)
}

private fun polylinePath(coords: FloatArray): Path = Path().apply {
    moveTo(coords[0], coords[1])
    var i = 2
    while (i + 1 < coords.size) {
        lineTo(coords[i], coords[i + 1])
        i += 2
    }
}

private fun drawTrack(canvas: Canvas, coords: FloatArray) {
    if (coords.size < 4) return
    val path = polylinePath(coords)
    val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    stroke.color = accent(0x40)
    stroke.strokeWidth = TRACK_GLOW_WIDTH
    canvas.drawPath(path, stroke)
    stroke.color = accent(0xFF)
    stroke.strokeWidth = TRACK_WIDTH
    canvas.drawPath(path, stroke)

    // Ziel zuerst, Start obendrauf: Bei einer Runde liegen beide uebereinander,
    // und der weisse Startpunkt mit gruenem Ring bleibt lesbar.
    val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    fill.color = accent(0xFF)
    canvas.drawCircle(coords[coords.size - 2], coords[coords.size - 1], FINISH_RADIUS, fill)
    fill.color = TEXT_PRIMARY
    canvas.drawCircle(coords[0], coords[1], START_RADIUS, fill)
    stroke.color = accent(0xFF)
    stroke.strokeWidth = 5f
    canvas.drawCircle(coords[0], coords[1], START_RADIUS, stroke)
}

private fun drawProfile(canvas: Canvas, coords: FloatArray, rect: CardRect) {
    if (coords.size < 4) return
    val line = polylinePath(coords)
    val area = Path(line).apply {
        lineTo(coords[coords.size - 2], rect.bottom)
        lineTo(coords[0], rect.bottom)
        close()
    }
    canvas.drawPath(
        area,
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            color = accent(0x38)
        },
    )
    canvas.drawPath(
        line,
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = PROFILE_LINE_WIDTH
            strokeJoin = Paint.Join.ROUND
            strokeCap = Paint.Cap.ROUND
            color = accent(0xCC)
        },
    )
}

private val BoldSans: Typeface = Typeface.create("sans-serif", Typeface.BOLD)
private val RegularSans: Typeface = Typeface.create("sans-serif", Typeface.NORMAL)

private fun drawHeader(canvas: Canvas, layout: ShareCardLayout, content: ShareCardContent) {
    val datePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = RegularSans
        textSize = DATE_SIZE
        color = TEXT_DATE
    }
    // Im Quadrat teilt sich die Datumszeile die Kopfzeile mit der Wortmarke.
    val dateWidth = layout.width - 2 * layout.padding - if (layout.wordmarkRight) WORDMARK_RESERVE else 0f
    val date = TextUtils.ellipsize(content.dateLine, datePaint, dateWidth, TextUtils.TruncateAt.END)
    canvas.drawText(date, 0, date.length, layout.padding, layout.dateBaseline, datePaint)

    val titlePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = BoldSans
        textSize = layout.titleSize
        color = TEXT_PRIMARY
    }
    val titleWidth = (layout.width - 2 * layout.padding).toInt()
    val title = StaticLayout.Builder
        .obtain(content.title, 0, content.title.length, titlePaint, titleWidth)
        .setAlignment(Layout.Alignment.ALIGN_NORMAL)
        .setMaxLines(layout.titleMaxLines)
        .setEllipsize(TextUtils.TruncateAt.END)
        .setIncludePad(false)
        .build()
    canvas.save()
    canvas.translate(layout.padding, layout.titleTop)
    title.draw(canvas)
    canvas.restore()
}

/** Platz, den die Datumszeile im Quadrat fuer die Wortmarke rechts freilaesst. */
private const val WORDMARK_RESERVE = 280f

private fun drawStats(canvas: Canvas, layout: ShareCardLayout, stats: List<ShareStat>) {
    if (stats.isEmpty()) return
    val rect = layout.stats
    val column = rect.width / stats.size
    val valuePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = BoldSans
        textSize = layout.statValueSize
        color = TEXT_PRIMARY
        textAlign = Paint.Align.CENTER
        fontFeatureSettings = "tnum"
    }
    val labelPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = RegularSans
        color = TEXT_LABEL
        textAlign = Paint.Align.CENTER
    }
    // Ziffern stehen ohne Unterlaenge: Die Grundlinie der Zahl liegt knapp
    // unter der Oberkante plus Versalhoehe, die Einheit am unteren Rand.
    val valueBaseline = rect.top + layout.statValueSize * 0.8f
    val labelBaseline = rect.bottom - 8f
    val maxTextWidth = column - 16f

    stats.forEachIndexed { index, stat ->
        val cx = rect.left + column * (index + 0.5f)

        valuePaint.textSize = layout.statValueSize
        while (valuePaint.measureText(stat.value) > maxTextWidth && valuePaint.textSize > layout.statValueSize * 0.6f) {
            valuePaint.textSize -= 2f
        }
        canvas.drawText(stat.value, cx, valueBaseline, valuePaint)

        labelPaint.textSize = layout.statLabelSize
        while (labelPaint.measureText(stat.label) > maxTextWidth && labelPaint.textSize > STAT_LABEL_MIN_SIZE) {
            labelPaint.textSize = max(STAT_LABEL_MIN_SIZE, labelPaint.textSize - 1f)
        }
        canvas.drawText(stat.label, cx, labelBaseline, labelPaint)
    }
}

private fun drawWordmark(canvas: Canvas, layout: ShareCardLayout) {
    val paint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = BoldSans
        textSize = WORDMARK_SIZE
        letterSpacing = 0.02f
        color = TEXT_WORDMARK
    }
    if (layout.wordmarkRight) {
        paint.textAlign = Paint.Align.RIGHT
        canvas.drawText(WORDMARK, layout.width - layout.padding, layout.wordmarkBaseline, paint)
    } else {
        paint.textAlign = Paint.Align.CENTER
        canvas.drawText(WORDMARK, layout.width / 2f, layout.wordmarkBaseline, paint)
    }
}

private const val WORDMARK = "Trailscape"

/**
 * Rendert das Tour-Bild in voller Groesse und teilt es als PNG ueber das
 * System-Share-Sheet — derselbe Weg wie der GPX-Export (`TourList.kt`):
 * Datei unter `<cacheDir>/geteilte-touren` ([prepareShareDirectory], alte
 * Exporte werden dabei nach einer Stunde weggeraeumt), Uri ueber den
 * FileProvider, Leserecht nur fuer die gewaehlte App.
 *
 * Kein Netzwerk: Das Bild entsteht vollstaendig auf dem Geraet. Die Bitmap
 * (rund 8 MB bei 1080x1920) wird nach dem Schreiben sofort freigegeben.
 */
internal suspend fun shareRideImage(context: Context, ride: Ride, load: Double?, format: ShareCardFormat) {
    // Texte in der Sprache der Oberflaeche: [context] ist der Activity-Kontext
    // mit dem Locale-Override (siehe `i18n/AppLocale.kt`).
    val language = languageOf(context.resources.configuration)
    val uri = withContext(Dispatchers.Default) {
        val content = shareCardContent(ride, load, language, resolve = { it.resolve(context) })
        val bitmap = renderShareCard(content, format)
        try {
            withContext(Dispatchers.IO) {
                val file = File(prepareShareDirectory(context.cacheDir), shareCardFileName(ride.name, format))
                val written = file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
                check(written) { "PNG nicht geschrieben" }
                FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
            }
        } finally {
            bitmap.recycle()
        }
    }

    val send = Intent(Intent.ACTION_SEND).apply {
        type = "image/png"
        putExtra(Intent.EXTRA_STREAM, uri)
        putExtra(Intent.EXTRA_TITLE, ride.name)
        // Mit ClipData zeigt das Share-Sheet eine Vorschau des Bildes.
        clipData = ClipData.newRawUri(ride.name, uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    context.startActivity(Intent.createChooser(send, context.getString(R.string.rides_share_image_chooser_title)))
}
