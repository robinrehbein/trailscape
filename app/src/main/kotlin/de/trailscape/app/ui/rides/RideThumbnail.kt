package de.trailscape.app.ui.rides

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.unit.dp
import de.trailscape.core.Ride
import de.trailscape.core.RideInfo
import de.trailscape.core.TrackPoint
import kotlin.math.cos
import kotlin.math.max
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext

/** Hoechstzahl der Stuetzpunkte einer Mini-Spur — mehr sieht man auf 44 dp nicht. */
internal const val ThumbnailMaxPoints: Int = 48

/**
 * Duennt [points] auf hoechstens [maxPoints] Stuetzpunkte aus und normiert sie
 * auf das Einheitsquadrat: abwechselnd x, y im Bereich 0..1, y nach **unten**
 * wachsend (Norden oben), wie eine Zeichenflaeche es erwartet.
 *
 * - Laengengrade werden mit dem Kosinus der mittleren Breite gestaucht, damit
 *   eine Runde in Norddeutschland nicht breiter aussieht, als sie ist.
 * - Das Seitenverhaeltnis bleibt erhalten; die kuerzere Achse wird mittig
 *   ausgerichtet. Eine reine Nord-Sued-Strecke steht also als senkrechter
 *   Strich in der Mitte, statt auf die volle Breite gezerrt zu werden.
 * - Ausgeduennt wird mit festem Schritt, der letzte Punkt bleibt immer dabei —
 *   sonst endete eine Runde sichtbar vor ihrem Start.
 *
 * Weniger als zwei Punkte ergeben ein leeres Feld (nichts zu zeichnen).
 */
internal fun thumbnailPolyline(points: List<TrackPoint>, maxPoints: Int = ThumbnailMaxPoints): FloatArray {
    if (points.size < 2 || maxPoints < 2) return FloatArray(0)

    // Schrittweite so, dass die regelmaessig gezogenen Punkte plus der immer
    // angehaengte letzte zusammen hoechstens [maxPoints] ergeben.
    val gaps = points.size - 1
    val slots = (maxPoints - 2).coerceAtLeast(1)
    val step = max(1, (gaps + slots - 1) / slots)
    val picked = buildList {
        var i = 0
        while (i < points.size) {
            add(points[i])
            i += step
        }
        if (last() !== points.last()) add(points.last())
    }

    val meanLat = picked.sumOf { it.lat } / picked.size
    val lonScale = cos(Math.toRadians(meanLat))
    val xs = DoubleArray(picked.size) { picked[it].lon * lonScale }
    val ys = DoubleArray(picked.size) { -picked[it].lat }

    val minX = xs.min()
    val minY = ys.min()
    val spanX = xs.max() - minX
    val spanY = ys.max() - minY
    val span = max(spanX, spanY)
    if (span <= 0.0) {
        // Alle Punkte auf derselben Stelle: ein Punkt in der Mitte.
        return floatArrayOf(0.5f, 0.5f, 0.5f, 0.5f)
    }
    val offsetX = (span - spanX) / 2
    val offsetY = (span - spanY) / 2

    val out = FloatArray(picked.size * 2)
    for (i in picked.indices) {
        out[2 * i] = ((xs[i] - minX + offsetX) / span).toFloat()
        out[2 * i + 1] = ((ys[i] - minY + offsetY) / span).toFloat()
    }
    return out
}

/**
 * Im Speicher gehaltene Mini-Spuren, Schluessel (ID, `updatedAt`) — nach einem
 * Umbenennen aendert sich zwar `updatedAt`, die Spur aber nicht; der eine
 * erneute Ladevorgang ist der Preis dafuer, dass ein HF-Merge oder ein
 * zurueckgespieltes Backup nie eine veraltete Linie zeigt.
 *
 * Prozessweit (ein `object`), damit ein Tabwechsel die schon gezeichneten
 * Linien nicht verwirft; begrenzt auf [MaxEntries] Eintraege (zuletzt benutzt
 * bleibt), das sind bei 48 Punkten unter 150 KB.
 */
internal object RideThumbnailCache {
    private const val MaxEntries = 400

    private val entries = object : LinkedHashMap<String, FloatArray>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, FloatArray>?): Boolean =
            size > MaxEntries
    }

    /** Hoechstens zwei Touren gleichzeitig von der Platte — siehe Datei-KDoc. */
    private val loads = Semaphore(2)

    private fun key(ride: RideInfo) = "${ride.id}@${ride.updatedAt}"

    fun peek(ride: RideInfo): FloatArray? = synchronized(entries) { entries[key(ride)] }

    /**
     * Liefert die Mini-Spur aus dem Speicher oder laedt sie ueber [loadRide]
     * nach. `null`, wenn die Tour nicht mehr lesbar ist; das wird nicht
     * gemerkt, der naechste Anlauf versucht es erneut.
     */
    suspend fun get(ride: RideInfo, loadRide: suspend (String) -> Ride?): FloatArray? {
        peek(ride)?.let { return it }
        return loads.withPermit {
            // Nach dem Warten nochmals nachsehen: Dieselbe Zeile kann beim
            // Hin- und Herscrollen zweimal angefragt haben.
            peek(ride)?.let { return@withPermit it }
            val full = runCatching { loadRide(ride.id) }.getOrNull() ?: return@withPermit null
            val line = withContext(Dispatchers.Default) { thumbnailPolyline(full.points) }
            synchronized(entries) { entries[key(ride)] = line }
            line
        }
    }
}

/**
 * # Die Mini-Karte einer Tour in der Verlaufsliste
 *
 * Zieldesign `docs/design/prototyp-klartext.html`, Klasse `.thumb`: ein
 * 44-dp-Quadrat mit runden Ecken, kartengetoenter Grund, darauf die Spur in
 * Akzentfarbe. Keine Kachel, kein MapLibre — eine Polylinie auf einer
 * Compose-`Canvas`. Eine echte Karte je Zeile waere eine OpenGL-Flaeche je
 * Zeile; fuer das Wiedererkennen einer Runde genuegt ihre Form.
 *
 * ## Woher die Punkte kommen
 * Die Liste haelt nur [de.trailscape.core.RideSummary] — bewusst ohne Punkte
 * (siehe deren KDoc: bei ein paar hundert Touren waeren das hunderte MB). Die
 * Mini-Spur wird deshalb **pro sichtbarer Zeile** nachgeladen: die volle Tour
 * von der Platte (auf `Dispatchers.IO`, ueber den Lader des Aufrufers), daraus
 * eine auf hoechstens [ThumbnailMaxPoints] Punkte ausgeduennte, auf das
 * Einheitsquadrat normierte Linie ([thumbnailPolyline], auf
 * `Dispatchers.Default`), die dann im Speicher liegen bleibt
 * ([RideThumbnailCache]). Die volle Tour faellt danach sofort wieder dem
 * Garbage Collector zu.
 *
 * Nicht auf der Platte zwischengespeichert: Eine normierte Linie ist ein paar
 * hundert Byte, ihre Berechnung aus einer frisch gelesenen Tour eine Sache von
 * Millisekunden, und ein weiterer Cache neben `rides/index.json` und
 * `last-cache.json` waere eine dritte Datei, die mit den Touren
 * auseinanderlaufen kann. Die `LazyColumn` fragt ohnehin nur die sichtbaren
 * Zeilen an, und [RideThumbnailCache] begrenzt parallele Ladevorgaenge, damit
 * schnelles Scrollen die Platte nicht mit Dutzenden Lesezugriffen flutet.
 *
 * Solange die Spur noch laedt (oder die Tour keine Punkte hat), bleibt nur
 * der getoente Grund stehen — kein Ladekreis, der in jeder Zeile flackerte.
 *
 * @param loadRide laedt die volle Tour, in der App `AppViewModel.loadRide`.
 */
@Composable
internal fun RideThumbnail(
    ride: RideInfo,
    loadRide: suspend (String) -> Ride?,
    modifier: Modifier = Modifier,
) {
    val line by produceState(RideThumbnailCache.peek(ride), ride.id, ride.updatedAt) {
        if (value == null) value = RideThumbnailCache.get(ride, loadRide)
    }
    val trackColor = MaterialTheme.colorScheme.primary
    val background = mapTint()

    Box(
        modifier = modifier
            .size(ThumbnailSize)
            .clip(RoundedCornerShape(ThumbnailCorner))
            .background(background),
    ) {
        val coords = line
        if (coords != null && coords.size >= 4) {
            Canvas(
                modifier = Modifier
                    .size(ThumbnailSize)
                    .padding(ThumbnailInset),
            ) {
                val w = size.width
                val h = size.height
                val path = Path().apply {
                    moveTo(coords[0] * w, coords[1] * h)
                    var i = 2
                    while (i + 1 < coords.size) {
                        lineTo(coords[i] * w, coords[i + 1] * h)
                        i += 2
                    }
                }
                drawPath(
                    path = path,
                    color = trackColor,
                    style = Stroke(
                        width = 2.dp.toPx(),
                        cap = StrokeCap.Round,
                        join = StrokeJoin.Round,
                    ),
                )
                // Startpunkt als kleiner Punkt: Bei einer Runde verraet sonst
                // nichts, wo sie begann.
                drawCircle(
                    color = trackColor,
                    radius = 2.5.dp.toPx(),
                    center = Offset(coords[0] * w, coords[1] * h),
                )
            }
        }
    }
}

/**
 * Der kartengetoente Grund — die Werte von `--map` aus dem Zieldesign, hell
 * und dunkel. Welcher gilt, entscheidet die Flaeche des aktiven Schemas, nicht
 * die Systemeinstellung: Die App kann ein eigenes Schema erzwingen.
 */
@Composable
private fun mapTint(): Color =
    if (MaterialTheme.colorScheme.surface.luminance() < 0.5f) MapTintDark else MapTintLight

private val MapTintLight = Color(0xFFE6ECE6)
private val MapTintDark = Color(0xFF161B20)

private val ThumbnailSize = 44.dp
private val ThumbnailCorner = 12.dp
private val ThumbnailInset = 6.dp
