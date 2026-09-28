package de.trailscape.app.ui.rides

import de.trailscape.app.R
import de.trailscape.app.i18n.UiText
import de.trailscape.app.ui.localOfEpochMs
import de.trailscape.app.ui.map.ElevationSample
import de.trailscape.app.ui.map.buildElevationSamples
import de.trailscape.core.Ride
import de.trailscape.core.RideStats
import de.trailscape.core.i18n.AppLanguage
import de.trailscape.core.i18n.formatDistanceKm
import de.trailscape.core.i18n.formatWeekdayDateYear
import de.trailscape.core.safeFileName
import de.trailscape.core.trimTrackEnds
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * # Das teilbare Tour-Bild — die reine Rechnung
 *
 * Eine Tour laesst sich nicht nur als GPX teilen, sondern auch als Bild: die
 * Spur als Linie, darunter ein kleines Hoehenprofil, drei, hoechstens vier
 * Zahlen, Name, Datum und eine leise Wortmarke. Das ist der Kanal, ueber den
 * Radfahrende einander Apps zeigen — ein Bild in der Story oder im Gruppenchat.
 *
 * ## Warum ohne Karte
 * Kacheln hiessen: Netzwerk beim Teilen (die App verspricht, nur auf eine
 * konkrete Handlung hin nach aussen zu sprechen, und eine gerenderte Karte
 * waere ein zweiter, versteckter Abruf), dazu die Lizenzfrage, ob gerenderte
 * Kacheln in einem geteilten Bild weitergegeben werden duerfen. Die Form einer
 * Runde auf ruhigem Grund erkennt man ohnehin besser als auf einer vollen
 * Karte — dieselbe Ueberlegung wie bei der Mini-Spur in der Liste
 * ([RideThumbnail]).
 *
 * ## Warum hier, ohne Android-Import
 * Alles, was sich ausrechnen laesst — Projektion, Einpassen, Ausduennen,
 * Profil, Texte, welche Zahlen erscheinen, wo was steht — liegt in dieser
 * Datei und ist als reiner JVM-Test pruefbar (`ShareCardTest`). Das Zeichnen
 * mit `android.graphics` (`ShareCardRenderer.kt`) setzt nur noch um. Die Texte
 * kommen als [UiText] aus den Ressourcen; aufgeloest werden sie ueber den
 * `resolve`-Parameter von [shareCardContent] — in der App gegen den Kontext,
 * im Test gegen die Ressourcendateien. Die Datei
 * liegt in `:app` statt in `:core`, weil sie [thumbnailPolyline] und
 * [buildElevationSamples] wiederverwendet, die ebenfalls hier wohnen.
 *
 * ## Start und Ziel ausblenden
 * Ab Werk kuerzt der Teilen-Dialog die Spur an beiden Enden um einen Kreis
 * von 300 m ([trimTrackEnds] in `:core`), damit das Bild die Haustuer nicht
 * verraet; Start- und Zielmarke entfallen dann (siehe `ShareCardRenderer.kt`).
 * Kennzahlen und Hoehenprofil beschreiben trotzdem die **ganze** Tour: Sie
 * verraten keinen Ort, und ein Bild, das 41 statt 42 km behauptet, waere
 * unehrlich zur gefahrenen Strecke. Ist die Tour fuer die Kuerzung zu kurz,
 * bleibt keine Spur — das Layout schaltet dann wie bei einer Tour ohne Punkte
 * auf „nur Kennzahlen" ([ShareTrackNote.TOO_SHORT]).
 *
 * Alle Masse sind Pixel eines 1080 px breiten Bildes; die Vorschau zeichnet
 * dieselben Koordinaten nur verkleinert.
 */

/**
 * Die zwei Bildformate: Story (9:16) und Quadrat (1:1), beide 1080 px breit.
 *
 * [fileSuffix] ist bewusst sprachneutral („story", „square"): Der Dateiname
 * erreicht die Empfaenger, deren Sprache die App nicht kennt, und haengt so
 * nicht an der Spracheinstellung des Absenders.
 */
enum class ShareCardFormat(val widthPx: Int, val heightPx: Int, val fileSuffix: String) {
    STORY(1080, 1920, "story"),
    SQUARE(1080, 1080, "square"),
}

/** Ein achsenparalleles Rechteck in Bildpixeln. */
internal data class CardRect(val left: Float, val top: Float, val right: Float, val bottom: Float) {
    val width: Float get() = right - left
    val height: Float get() = bottom - top
}

/**
 * Was das Bild von der Strecke zeigt — bestimmt den Hinweis im Teilen-Dialog
 * und, ob Start- und Zielmarke gezeichnet werden.
 */
internal enum class ShareTrackNote {
    /** Die ganze Spur samt Start und Ziel. */
    FULL,

    /** Die Spur, an beiden Enden gekuerzt; ohne Start- und Zielmarke. */
    ENDS_HIDDEN,

    /** Es gaebe eine Spur, sie ist aber zu kurz zum Kuerzen: nur Kennzahlen. */
    TOO_SHORT,

    /** Die Tour hat keine zeigbare Spur (keine Punkte, alle an einer Stelle). */
    NONE,
}

/** Eine Kennzahl auf dem Bild: gross die Zahl, klein darunter ihre Einheit. */
internal data class ShareStat(val value: String, val label: String)

/** Wie [ShareStat], die Einheit noch als [UiText] — was [shareCardStats] liefert. */
internal data class ShareStatText(val value: String, val label: UiText)

/**
 * Was auf dem Bild steht, unabhaengig vom Format.
 *
 * Bewusst keine `data class`: [trackUnit] ist ein Feld, und eine
 * Gleichheit, die Felder nur per Referenz vergleicht, waere eine Falle.
 *
 * @property trackUnit die Spur im Einheitsquadrat, abwechselnd x, y (siehe
 *   [thumbnailPolyline]); leer, wenn die Tour keine Punkte hat.
 * @property profile Hoehen-Stuetzstellen; leer ohne Hoehendaten.
 * @property trackNote was von der Strecke zu sehen ist (siehe [ShareTrackNote]).
 */
internal class ShareCardContent(
    val title: String,
    val dateLine: String,
    val stats: List<ShareStat>,
    val trackUnit: FloatArray,
    val profile: List<ElevationSample>,
    val trackNote: ShareTrackNote,
) {
    /** Start und Ziel sind ausgeblendet: keine Marken an den gekuerzten Enden. */
    val endsHidden: Boolean get() = trackNote == ShareTrackNote.ENDS_HIDDEN

    /**
     * Mindestens zwei Punkte **und** eine Ausdehnung: Liegen alle Punkte auf
     * einer Stelle, liefert [thumbnailPolyline] einen Punkt in der Mitte —
     * das ist keine Spur, die man zeigen koennte.
     */
    val hasTrack: Boolean get() = hasDrawableTrack(trackUnit)

    /**
     * Wie bei [hasTrack]: Ohne zurueckgelegte Strecke (etwa eine Aufzeichnung
     * auf der Rolle mit festem Standort und Barometerhoehe) zeichnet
     * [profilePolyline] nichts — dann darf das Layout auch keinen Platz
     * dafuer freihalten.
     */
    val hasProfile: Boolean
        get() = profile.size >= 2 && profile.last().distanceKm > profile.first().distanceKm
}

/**
 * Hoechstzahl der Stuetzpunkte der Spur. Auf 1080 px sieht man mehr als die 48
 * der Mini-Karte, aber nicht mehr als ein paar hundert; das haelt auch eine
 * zehnstuendige Aufzeichnung klein.
 */
internal const val SHARE_TRACK_MAX_POINTS = 600

/**
 * Baut den Bildinhalt einer Tour.
 *
 * Die Spur kommt aus [thumbnailPolyline] — dieselbe Projektion (Kosinus der
 * mittleren Breite), dasselbe Ausduennen und derselbe immer behaltene letzte
 * Punkt wie bei der Mini-Spur in der Liste, damit eine Runde auf dem Bild so
 * aussieht wie in der App.
 *
 * @param language Sprache fuer Zahlen und Datum — die der Oberflaeche.
 * @param resolve loest die [UiText]-Beschriftungen auf (App: `{ it.resolve(context) }`).
 * @param endRadiusM `null`: die ganze Spur. Sonst wird sie vorher an beiden
 *   Enden um einen Kreis mit diesem Radius gekuerzt ([trimTrackEnds]) — nur die
 *   Linie, nicht Kennzahlen und Profil (siehe Datei-KDoc).
 * @param toLocal Umrechnung des Zeitstempels in Ortszeit; im Test fest.
 */
internal fun shareCardContent(
    ride: Ride,
    load: Double?,
    language: AppLanguage,
    resolve: (UiText) -> String,
    endRadiusM: Double? = null,
    toLocal: (Long) -> LocalDateTime = ::localOfEpochMs,
): ShareCardContent {
    val trackPoints = if (endRadiusM == null) ride.points else trimTrackEnds(ride.points, endRadiusM)
    val trackUnit = thumbnailPolyline(trackPoints, SHARE_TRACK_MAX_POINTS)
    val hasTrack = hasDrawableTrack(trackUnit)
    val trackNote = when {
        endRadiusM == null -> if (hasTrack) ShareTrackNote.FULL else ShareTrackNote.NONE
        hasTrack -> ShareTrackNote.ENDS_HIDDEN
        // Ohne Kuerzung haette es eine Spur gegeben: Das Bild zeigt nur Zahlen,
        // weil die Tour zu kurz ist — das soll der Hinweis sagen.
        hasDrawableTrack(thumbnailPolyline(ride.points, SHARE_TRACK_MAX_POINTS)) -> ShareTrackNote.TOO_SHORT
        else -> ShareTrackNote.NONE
    }
    return ShareCardContent(
        title = ride.name.trim().ifEmpty { resolve(UiText.Res(R.string.rides_share_card_fallback_title)) },
        dateLine = resolve(shareCardDateLine(toLocal(ride.createdAt), ride.planned, language)),
        stats = shareCardStats(
            stats = ride.stats,
            load = load,
            planned = ride.planned,
            hasElevation = ride.points.any { it.ele != null },
            language = language,
        ).map { ShareStat(it.value, resolve(it.label)) },
        trackUnit = trackUnit,
        profile = buildElevationSamples(ride.points),
        trackNote = trackNote,
    )
}

/**
 * Die Zahlen auf dem Bild, in fester Reihenfolge: km, Dauer, Hoehenmeter und
 * hoechstens eine vierte. Mehr als vier Spalten werden auf 1080 px zu eng, und
 * ein geteiltes Bild ist keine Auswertung.
 *
 *  - Die Dauer ist die Gesamtdauer; fehlt sie, springt die Fahrzeit ein. Die
 *    Kennzahlenzeile der Detailansicht zeigt dann „–" — ein geteiltes Bild
 *    soll aber keinen Platzhalterstrich tragen. Fehlen beide, entfaellt die
 *    Zahl ganz.
 *  - Hoehenmeter erscheinen nur, wenn es sie gibt: Ein Import ohne Hoehen soll
 *    kein „0 Hm" behaupten. Hat die Spur Hoehen und ist trotzdem flach, steht
 *    die ehrliche 0 da.
 *  - Als vierte Zahl gewinnt der Puls — ihn versteht jeder. Sonst die
 *    Trainingslast, aber nie bei einer Planung, fuer die niemand im Sattel sass.
 */
internal fun shareCardStats(
    stats: RideStats,
    load: Double?,
    planned: Boolean,
    hasElevation: Boolean,
    language: AppLanguage,
): List<ShareStatText> = buildList {
    add(ShareStatText(formatDistanceKm(stats.distanceKm, language), UiText.Res(R.string.rides_share_card_km_label)))
    (stats.durationS ?: stats.movingTimeS)?.let {
        add(ShareStatText(formatHoursMinutes(it), UiText.Res(R.string.rides_share_card_hours_label)))
    }
    if (stats.ascentM >= 1 || hasElevation) {
        add(ShareStatText("${stats.ascentM.roundToInt()}", UiText.Res(R.string.rides_share_card_elevation_label)))
    }
    val hr = stats.avgHrBpm
    when {
        hr != null -> add(ShareStatText("$hr", UiText.Res(R.string.rides_share_card_avg_hr_label)))
        !planned && load != null && load > 0 ->
            add(ShareStatText("${load.roundToInt()}", UiText.Res(R.string.rides_share_card_load_label)))
    }
}

/** Tag, Monat und Jahr ohne Wochentag, z. B. „23. September 2026" / „23 September 2026". */
private val dateLinePlannedDe = DateTimeFormatter.ofPattern("d. MMMM yyyy", AppLanguage.DE.locale)
private val dateLinePlannedEn = DateTimeFormatter.ofPattern("d MMMM yyyy", AppLanguage.EN.locale)

/**
 * Die Datumszeile, immer **mit** Jahr: Ein geteiltes Bild lebt laenger als
 * die Monatsueberschrift der Liste, und „Dienstag, 23. September" sagt in
 * einem Jahr nichts mehr. Eine Planung sagt, dass sie eine ist.
 */
internal fun shareCardDateLine(at: LocalDateTime, planned: Boolean, language: AppLanguage): UiText =
    if (planned) {
        val date = (if (language == AppLanguage.DE) dateLinePlannedDe else dateLinePlannedEn).format(at)
        UiText.Res(R.string.rides_share_card_planned_date_line, listOf(date))
    } else {
        UiText.Plain(formatWeekdayDateYear(at, language))
    }

/**
 * Dateiname des Bildes, z. B. `Feierabendrunde-story.png`. Das Format steht
 * im Namen, damit zwei kurz nacheinander geteilte Fassungen derselben Tour
 * einander nicht ueberschreiben, waehrend die Empfaenger-App noch liest.
 */
internal fun shareCardFileName(rideName: String, format: ShareCardFormat): String =
    "${safeFileName(rideName)}-${format.fileSuffix}.png"

/**
 * Wo was auf dem Bild steht, in Pixeln.
 *
 * @property dateBaseline Grundlinie der Datumszeile.
 * @property titleTop Oberkante des (umbrechenden) Titels.
 * @property track Flaeche der Spur; `null`, wenn es keine gibt.
 * @property profile Flaeche des Hoehenprofils; `null` ohne Profil.
 * @property stats Band der Kennzahlen (Zahl oben, Einheit unten).
 * @property wordmarkRight `true`: Wortmarke rechtsbuendig auf der
 *   Datumsgrundlinie (Quadrat, unten ist kein Platz); sonst unten mittig.
 */
internal data class ShareCardLayout(
    val width: Int,
    val height: Int,
    val padding: Float,
    val dateBaseline: Float,
    val titleTop: Float,
    val titleSize: Float,
    val titleMaxLines: Int,
    val track: CardRect?,
    val profile: CardRect?,
    val stats: CardRect,
    val statValueSize: Float,
    val statLabelSize: Float,
    val wordmarkBaseline: Float,
    val wordmarkRight: Boolean,
)

/** Die festen Masse eines Formats — alles, was [shareCardLayout] daraus ableitet. */
private class LayoutSpec(
    val format: ShareCardFormat,
    val padding: Float,
    val dateBaseline: Float,
    val titleTop: Float,
    val titleSize: Float,
    val titleMaxLines: Int,
    val trackTop: Float,
    val trackBottom: Float,
    val profileTop: Float,
    val profileBottom: Float,
    val statsTop: Float,
    val statsBottom: Float,
    val statValueSize: Float,
    val statValueSizeAlone: Float,
    val wordmarkBaseline: Float,
    val wordmarkRight: Boolean,
)

private val StorySpec = LayoutSpec(
    format = ShareCardFormat.STORY,
    padding = 96f,
    dateBaseline = 200f,
    titleTop = 236f,
    titleSize = 76f,
    titleMaxLines = 2,
    trackTop = 440f,
    trackBottom = 1300f,
    profileTop = 1340f,
    profileBottom = 1500f,
    statsTop = 1560f,
    statsBottom = 1720f,
    statValueSize = 84f,
    statValueSizeAlone = 120f,
    wordmarkBaseline = 1840f,
    wordmarkRight = false,
)

private val SquareSpec = LayoutSpec(
    format = ShareCardFormat.SQUARE,
    padding = 72f,
    dateBaseline = 128f,
    titleTop = 156f,
    titleSize = 64f,
    titleMaxLines = 1,
    trackTop = 270f,
    trackBottom = 740f,
    profileTop = 770f,
    profileBottom = 850f,
    statsTop = 880f,
    statsBottom = 1000f,
    statValueSize = 72f,
    statValueSizeAlone = 96f,
    wordmarkBaseline = 128f,
    wordmarkRight = true,
)

/** Groesse der Einheit unter einer Zahl; der Zeichner verkleinert bei Bedarf bis [STAT_LABEL_MIN_SIZE]. */
internal const val STAT_LABEL_SIZE = 32f

/** Kleiner wird eine Einheit nicht — sonst ist „Trainingslast" („Training load") in vier Spalten nicht mehr lesbar. */
internal const val STAT_LABEL_MIN_SIZE = 24f

/**
 * Das Layout eines Formats.
 *
 *  - Ohne Profil waechst die Spur nach unten bis zur Unterkante des
 *    Profilbereichs — Platz fuer die Form, statt eines Lochs.
 *  - Ohne Spur (Import ohne Punkte, alle Punkte an einer Stelle) ist
 *    [ShareCardLayout.track] `null`. Die Kennzahlen stehen dann groesser und
 *    mittig in der frei gewordenen Flaeche, ein vorhandenes Profil direkt
 *    darueber — ein Bild nur mit Zahlen soll nicht wie ein halb leeres wirken.
 */
internal fun shareCardLayout(format: ShareCardFormat, hasTrack: Boolean, hasProfile: Boolean): ShareCardLayout {
    val spec = when (format) {
        ShareCardFormat.STORY -> StorySpec
        ShareCardFormat.SQUARE -> SquareSpec
    }
    val left = spec.padding
    val right = format.widthPx - spec.padding
    val profileGap = spec.profileTop - spec.trackBottom
    val profileHeight = spec.profileBottom - spec.profileTop
    val statsTextBlock = (spec.statsBottom - spec.statsTop) - spec.statValueSize

    val track: CardRect?
    val profile: CardRect?
    val stats: CardRect
    val valueSize: Float
    if (hasTrack) {
        track = CardRect(left, spec.trackTop, right, if (hasProfile) spec.trackBottom else spec.profileBottom)
        profile = if (hasProfile) CardRect(left, spec.profileTop, right, spec.profileBottom) else null
        stats = CardRect(left, spec.statsTop, right, spec.statsBottom)
        valueSize = spec.statValueSize
    } else {
        // Die freie Flaeche reicht von der Oberkante der Spur bis zur
        // Unterkante der Kennzahlen; darin steht der Block aus Profil und
        // Zahlen mittig.
        valueSize = spec.statValueSizeAlone
        val statsHeight = valueSize + statsTextBlock
        val blockHeight = statsHeight + if (hasProfile) profileHeight + profileGap else 0f
        val freeTop = spec.trackTop
        val freeBottom = spec.statsBottom
        val blockTop = freeTop + ((freeBottom - freeTop) - blockHeight) / 2
        track = null
        profile = if (hasProfile) CardRect(left, blockTop, right, blockTop + profileHeight) else null
        val statsTop = blockTop + blockHeight - statsHeight
        stats = CardRect(left, statsTop, right, statsTop + statsHeight)
    }

    return ShareCardLayout(
        width = format.widthPx,
        height = format.heightPx,
        padding = spec.padding,
        dateBaseline = spec.dateBaseline,
        titleTop = spec.titleTop,
        titleSize = spec.titleSize,
        titleMaxLines = spec.titleMaxLines,
        track = track,
        profile = profile,
        stats = stats,
        statValueSize = valueSize,
        statLabelSize = STAT_LABEL_SIZE,
        wordmarkBaseline = spec.wordmarkBaseline,
        wordmarkRight = spec.wordmarkRight,
    )
}

/** Siehe [ShareCardContent.hasTrack]. */
private fun hasDrawableTrack(unit: FloatArray): Boolean = unit.size >= 4 && unitExtent(unit) > 0f

/** Die groessere der beiden Ausdehnungen einer x/y-Punktfolge. */
private fun unitExtent(coords: FloatArray): Float {
    val box = bounds(coords) ?: return 0f
    return maxOf(box.width, box.height)
}

/** Bounding-Box einer x/y-Punktfolge; `null` bei weniger als einem Punkt. */
private fun bounds(coords: FloatArray): CardRect? {
    if (coords.size < 2) return null
    var minX = Float.POSITIVE_INFINITY
    var minY = Float.POSITIVE_INFINITY
    var maxX = Float.NEGATIVE_INFINITY
    var maxY = Float.NEGATIVE_INFINITY
    var i = 0
    while (i + 1 < coords.size) {
        minX = min(minX, coords[i])
        maxX = maxOf(maxX, coords[i])
        minY = min(minY, coords[i + 1])
        maxY = maxOf(maxY, coords[i + 1])
        i += 2
    }
    return CardRect(minX, minY, maxX, maxY)
}

/**
 * Passt die Spur aus dem Einheitsquadrat in [rect] ein: einheitlich skaliert
 * (das Seitenverhaeltnis bleibt, nichts wird in die Breite gezerrt) und
 * mittig.
 *
 * Gemessen wird an der tatsaechlichen Bounding-Box, nicht an 0..1:
 * [thumbnailPolyline] setzt die kuerzere Achse mittig ins Quadrat, eine
 * Nord-Sued-Strecke liegt dort also als schmaler Streifen um x = 0,5. Eine
 * Achse ohne Ausdehnung (ein reiner Nord-Sued-Strich) bestimmt den Massstab
 * nicht mit. Haben beide keine, gibt es nichts zu zeichnen: leeres Feld.
 */
internal fun fitPolyline(unit: FloatArray, rect: CardRect): FloatArray {
    if (unit.size < 4) return FloatArray(0)
    val box = bounds(unit) ?: return FloatArray(0)
    val bw = box.width
    val bh = box.height
    if (bw <= 0f && bh <= 0f) return FloatArray(0)
    val scaleX = if (bw > 0f) rect.width / bw else Float.POSITIVE_INFINITY
    val scaleY = if (bh > 0f) rect.height / bh else Float.POSITIVE_INFINITY
    val scale = min(scaleX, scaleY)
    val offsetX = rect.left + (rect.width - bw * scale) / 2
    val offsetY = rect.top + (rect.height - bh * scale) / 2

    val out = FloatArray(unit.size - unit.size % 2)
    var i = 0
    while (i + 1 < unit.size) {
        out[i] = offsetX + (unit[i] - box.left) * scale
        out[i + 1] = offsetY + (unit[i + 1] - box.top) * scale
        i += 2
    }
    return out
}

/**
 * Kleinster Hoehenbereich des Profils in Metern. Ohne ihn wuerden 2 m
 * Wellen auf einer Deichrunde auf die volle Hoehe gestreckt — die flache Tour
 * saehe aus wie ein Pass.
 */
internal const val PROFILE_MIN_RANGE_M = 60.0

/**
 * Das Hoehenprofil als Linie in [rect]: x linear ueber die Distanz von links
 * nach rechts, y von unten (tiefster Punkt) nach oben (hoechster Punkt). Ein
 * Bereich unter [PROFILE_MIN_RANGE_M] wird um seine Mitte aufgeweitet.
 * Weniger als zwei Stuetzstellen oder keine Distanz: leeres Feld.
 */
internal fun profilePolyline(samples: List<ElevationSample>, rect: CardRect): FloatArray {
    if (samples.size < 2) return FloatArray(0)
    val d0 = samples.first().distanceKm
    val span = samples.last().distanceKm - d0
    if (span <= 0.0) return FloatArray(0)

    var lo = samples.minOf { it.eleM }
    var hi = samples.maxOf { it.eleM }
    if (hi - lo < PROFILE_MIN_RANGE_M) {
        val mid = (hi + lo) / 2
        lo = mid - PROFILE_MIN_RANGE_M / 2
        hi = mid + PROFILE_MIN_RANGE_M / 2
    }
    val range = hi - lo

    val out = FloatArray(samples.size * 2)
    samples.forEachIndexed { index, sample ->
        val fx = ((sample.distanceKm - d0) / span).coerceIn(0.0, 1.0)
        val fy = ((sample.eleM - lo) / range).coerceIn(0.0, 1.0)
        out[2 * index] = (rect.left + fx * rect.width).toFloat()
        out[2 * index + 1] = (rect.bottom - fy * rect.height).toFloat()
    }
    return out
}
