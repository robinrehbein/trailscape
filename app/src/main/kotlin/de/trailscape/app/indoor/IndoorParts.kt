package de.trailscape.app.indoor

import android.content.Context
import de.trailscape.app.indoor.render.createWorldRenderer
import de.trailscape.app.sensors.indoor.IndoorDevices
import de.trailscape.core.RouteWaySection
import de.trailscape.core.TrackPoint
import de.trailscape.core.TrainingProfile
import de.trailscape.core.TrainingSession
import de.trailscape.core.Waypoint
import de.trailscape.core.indoor.BleLink
import de.trailscape.core.indoor.DemoTrainer
import de.trailscape.core.indoor.ErgWorkout
import de.trailscape.core.indoor.IndoorDeviceRole
import de.trailscape.core.indoor.IndoorRoute
import de.trailscape.core.indoor.IndoorRouteSource
import de.trailscape.core.indoor.IndoorSession
import de.trailscape.core.indoor.IndoorSessionConfig
import de.trailscape.core.indoor.IndoorShifter
import de.trailscape.core.indoor.IndoorTrainer
import de.trailscape.core.indoor.RiderSetup
import de.trailscape.core.indoor.buildIndoorRoute
import de.trailscape.core.indoor.createIndoorSession
import de.trailscape.core.indoor.createZwiftClickShifter
import de.trailscape.core.indoor.ergWorkoutFor
import de.trailscape.core.indoor.riderSetupFrom
import de.trailscape.core.indoor.surfaceRematchWaypoints
import de.trailscape.core.indoor.world.GeoFetcher
import de.trailscape.core.indoor.world.RasterDecoder
import de.trailscape.core.indoor.world.WorldBuilder
import de.trailscape.core.indoor.world.WorldRenderer
import de.trailscape.core.indoor.world.createGeoPipeline
import de.trailscape.core.indoor.world.createWorldBuilder
import java.io.File

/**
 * # Die Teile der anderen Slices, gebuendelt hinter einer Schnittstelle
 *
 * Der Indoor-Ablauf ruft fremde Slices (Kern, Weltbau, Funk, Darstellung)
 * **nur** ueber diese Schnittstelle auf. Zwei Gruende:
 *
 *  1. **Tests.** Auf dem Branch von indoor-app sind die Einstiege der anderen
 *     Slices Platzhalter (`docs/indoor.md`, Abschnitt 2.2). Tests reichen eine
 *     Attrappe herein; nach dem Zusammenfuehren greifen in der App ohne Umbau
 *     die echten Teile ([DefaultIndoorParts]).
 *  2. **Robustheit.** Jeder Aufruf kann werfen — ein Platzhalter mit
 *     `NotImplementedError`, ein Programmfehler in einem fremden Teil. Die
 *     Aufrufer fangen das mit [indoorCatching] und machen eine sichtbare
 *     Meldung ([IndoorProblem]) daraus, nie einen Absturz.
 *
 * Alle Methoden sind blockierend und duerfen auf jedem Thread laufen, ausser
 * [createRenderer] (Hauptthread, wie die Darstellung selbst).
 */
interface IndoorParts {
    fun buildRoute(
        name: String,
        points: List<TrackPoint>,
        waySections: List<RouteWaySection>?,
        source: IndoorRouteSource,
    ): IndoorRoute?

    fun rematchWaypoints(points: List<TrackPoint>): List<Waypoint>

    fun riderSetup(profile: TrainingProfile): RiderSetup

    fun ergWorkout(session: TrainingSession, title: String): ErgWorkout?

    fun createSession(config: IndoorSessionConfig): IndoorSession

    fun createDemoTrainer(): DemoTrainer

    fun createFtmsTrainer(link: BleLink): IndoorTrainer

    fun createShifter(link: BleLink): IndoorShifter

    /** Ein neues, ungeoeffnetes Rohr zum gemerkten Geraet der Rolle, oder `null`. */
    fun openLink(role: IndoorDeviceRole): BleLink?

    fun worldBuilder(): WorldBuilder

    /** Die Huelle (Positivliste, Cache, Drosselung) um den rohen Abruf. */
    fun geoPipeline(): GeoFetcher

    fun rasterDecoder(): RasterDecoder

    /** Nur vom Hauptthread. */
    fun createRenderer(): WorldRenderer
}

/**
 * Die echten Teile: ruft die vier Einstiegsdateien der anderen Slices.
 *
 * @param geoCacheDir Wurzel des Plattencaches der Geodaten
 *   (`<cacheDir>`; die Huelle legt darunter `indoor/geo/…` an).
 * @param rawFetcher der rohe Netzabruf (OkHttp, siehe `indoor/geo/`).
 */
class DefaultIndoorParts(
    context: Context,
    private val geoCacheDir: File,
    private val rawFetcher: GeoFetcher,
    private val decoder: RasterDecoder,
) : IndoorParts {
    private val appContext = context.applicationContext

    override fun buildRoute(
        name: String,
        points: List<TrackPoint>,
        waySections: List<RouteWaySection>?,
        source: IndoorRouteSource,
    ): IndoorRoute? = buildIndoorRoute(name, points, waySections, source)

    override fun rematchWaypoints(points: List<TrackPoint>): List<Waypoint> = surfaceRematchWaypoints(points)

    override fun riderSetup(profile: TrainingProfile): RiderSetup = riderSetupFrom(profile)

    override fun ergWorkout(session: TrainingSession, title: String): ErgWorkout? = ergWorkoutFor(session, title)

    override fun createSession(config: IndoorSessionConfig): IndoorSession = createIndoorSession(config)

    override fun createDemoTrainer(): DemoTrainer = de.trailscape.core.indoor.createDemoTrainer()

    override fun createFtmsTrainer(link: BleLink): IndoorTrainer = de.trailscape.core.indoor.createFtmsTrainer(link)

    override fun createShifter(link: BleLink): IndoorShifter = createZwiftClickShifter(link)

    override fun openLink(role: IndoorDeviceRole): BleLink? = IndoorDevices.openLink(appContext, role)

    override fun worldBuilder(): WorldBuilder = createWorldBuilder()

    override fun geoPipeline(): GeoFetcher = createGeoPipeline(rawFetcher, geoCacheDir)

    override fun rasterDecoder(): RasterDecoder = decoder

    override fun createRenderer(): WorldRenderer = createWorldRenderer(appContext)
}

/** Welcher Teil versagt hat — die Oberflaeche waehlt daraus ihren Text. */
enum class IndoorPart {
    /** Strecke, Sitzung, Trainer-Protokoll (Slice indoor-core). */
    CORE,

    /** Weltbau und Geodaten (Slice indoor-world). */
    WORLD,

    /** Bluetooth-Rohr (Slice indoor-ble). */
    BLE,

    /** 3D-Darstellung (Slice indoor-render). */
    RENDER,

    /** Laufzeit, Speichern, Dateien (dieser Slice). */
    APP,
}

/**
 * Ein abgefangener Fehler eines Teils.
 *
 * @property notImplemented der Teil ist (auf diesem Stand) nur ein Platzhalter.
 * @property detail deutscher Diagnosetext — nicht als Satz in die Oberflaeche; die
 *   Fehlerkarten zeigen ihn nur als kopierbare „Technische Einzelheit".
 */
data class IndoorProblem(val part: IndoorPart, val notImplemented: Boolean, val detail: String)

/**
 * Fuehrt [block] aus und macht aus **jedem** Throwable (auch
 * `NotImplementedError` und anderen `Error`s eines fremden Teils) ein
 * [IndoorProblem]. Abbruch von Koroutinen wird durchgereicht.
 */
inline fun <T> indoorCatching(part: IndoorPart, block: () -> T): Result<T> = try {
    Result.success(block())
} catch (e: kotlinx.coroutines.CancellationException) {
    throw e
} catch (e: Throwable) {
    Result.failure(IndoorPartException(problemOf(part, e), e))
}

/** Traegt das [IndoorProblem] durch ein `Result`. */
class IndoorPartException(val problem: IndoorProblem, cause: Throwable) : Exception(problem.detail, cause)

fun problemOf(part: IndoorPart, e: Throwable): IndoorProblem = when (e) {
    is IndoorPartException -> e.problem
    else -> IndoorProblem(
        part = part,
        notImplemented = e is NotImplementedError,
        detail = "${e::class.java.simpleName}: ${e.message ?: "ohne Meldung"}",
    )
}

/** Das [IndoorProblem] eines gescheiterten `Result` aus [indoorCatching]. */
fun Result<*>.problemOrNull(): IndoorProblem? = exceptionOrNull()?.let { problemOf(IndoorPart.APP, it) }

/** Obergrenze der technischen Einzelheit in der Oberflaeche ([technicalDetailOf]). */
const val TECHNICAL_DETAIL_MAX_CHARS: Int = 300

/** Pakete, deren Stack-Zeilen nichts ueber die eigene Stelle sagen. */
private val FOREIGN_FRAME_PREFIXES = listOf(
    "java.", "javax.", "jdk.", "sun.", "kotlin.", "kotlinx.", "android.", "androidx.", "dalvik.",
    "libcore.", "com.android.", "okhttp3.", "okio.", "org.json.",
)

/**
 * Die **technische Einzelheit** eines Fehlers fuer die Fehlerkarten — kurz,
 * kopierbar und so, dass ein Nutzer sie melden kann:
 * `<Klasse der innersten Ursache>: <Meldung> @ <erste eigene Stack-Zeile>`.
 *
 * * Innerste Ursache: die Kette ueber `cause` bis zum Ende (hoechstens 16
 *   Glieder, Zyklen abgefangen). Hat sie keine Meldung, steht die Meldung
 *   des naechsten aeusseren Glieds mit Meldung dahinter in Klammern —
 *   sonst bliebe von `WorldBuildFailed("Budget …", NullPointerException())`
 *   nur „NullPointerException".
 * * Stack-Zeile: die erste aus `de.trailscape…` (erst der innersten
 *   Ursache, dann der aeusseren Glieder); gibt es keine (im Release
 *   benennt R8 die Klassen um), die erste ausserhalb von JDK, Kotlin,
 *   Android und OkHttp — mit `mapping.txt` rueckuebersetzbar.
 * * Gekuerzt auf [maxChars] Zeichen (mit „…").
 *
 * Wirft nie.
 */
fun technicalDetailOf(e: Throwable, maxChars: Int = TECHNICAL_DETAIL_MAX_CHARS): String = try {
    val chain = ArrayList<Throwable>()
    var current: Throwable? = e
    while (current != null && chain.size < 16 && chain.none { it === current }) {
        chain += current
        current = current.cause
    }
    val root = chain.last()
    val name = root.javaClass.name.removePrefix("java.lang.")
    val rootMessage = root.message?.trim()?.takeIf { it.isNotEmpty() }
    val outerMessage = if (rootMessage == null) {
        chain.asReversed().drop(1)
            .firstOrNull { it !is IndoorPartException && !it.message.isNullOrBlank() }
            ?.message?.trim()
    } else {
        null
    }
    val frames = root.stackTrace.orEmpty()
    val frame = frames.firstOrNull { it.className.startsWith("de.trailscape.") }
        ?: chain.asReversed().drop(1).firstNotNullOfOrNull { t ->
            t.stackTrace.orEmpty().firstOrNull { it.className.startsWith("de.trailscape.") }
        }
        ?: frames.firstOrNull { f -> FOREIGN_FRAME_PREFIXES.none { f.className.startsWith(it) } }
    val text = buildString {
        append(name)
        if (rootMessage != null) append(": ").append(rootMessage)
        if (outerMessage != null) append(" (").append(outerMessage).append(')')
        if (frame != null) {
            append(" @ ").append(frame.className).append('.').append(frame.methodName)
            if (frame.lineNumber >= 0) append(':').append(frame.lineNumber)
        }
    }.replace(Regex("\\s+"), " ")
    if (text.length <= maxChars) text else text.take((maxChars - 1).coerceAtLeast(1)) + "…"
} catch (_: Throwable) {
    e.javaClass.name
}
