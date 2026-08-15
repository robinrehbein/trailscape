package de.trailscape.app.record

import de.trailscape.core.RideStats
import de.trailscape.core.TrackPoint
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * Die Entscheidungen der dreistufigen Absturzsicherung — herausgeloest aus dem
 * [RecordingService], damit sie ohne Android pruefbar sind.
 *
 * Warum diese Datei existiert: Alles, was in einem `Service` steht, laesst sich
 * in diesem Modul gar nicht testen (`:app` hat bewusst kein Robolectric, siehe
 * `app/build.gradle.kts`). Die Absturzsicherung ist aber der Teil der App, bei
 * dem ein Fehler still Daten kostet — genau der Teil also, der Tests braucht.
 * Deshalb steht hier ausschliesslich Logik ohne einen einzigen Android-Import;
 * der Service reicht nur noch die Rohwerte herein und fuehrt das Urteil aus.
 *
 * Enthalten sind:
 *
 *  * [HeartbeatStamp]/[bewerteLebenszeichen] — wie alt ist das Lebenszeichen
 *    eines Journals wirklich, und wann ist die Antwort „unbekannt"?
 *  * [beurteileJournal] — verwaist (wiederherstellen) oder lebendig (verschonen)?
 *  * [RecoveryGate] — wer gewinnt das Wettrennen zwischen dem gerade
 *    startenden Dienst und der Wiederherstellung beim App-Start?
 *  * [vereinigePunkte] — Datei- und RAM-Punkte beim Abschluss zusammenfuehren.
 *  * [ohnePausenzeit] — die gespeicherte Dauer auf dieselbe Rechnung bringen
 *    wie die Live-Anzeige.
 */

// ---------------------------------------------------------------- Lebenszeichen

/**
 * Zeitquelle des Lebenszeichens.
 *
 * Es braucht drei Werte statt nur der Wanduhr, weil `System.currentTimeMillis()`
 * springen kann: Ein Geraet startet mit einer falschen Uhr und korrigiert sie
 * Sekunden spaeter per NTP. Ein Sprung nach vorn liesse ein frisches
 * Lebenszeichen alt aussehen — und die Wiederherstellung wuerde einem
 * *lebenden* Dienst das Journal wegnehmen. Genau das darf nicht passieren.
 *
 * Die Schnittstelle liegt hier und nicht in [RecordingJournal], damit das
 * Journal frei von Android-Importen bleibt (`SystemClock` lebt in `android.os`);
 * die Android-Umsetzung steht in `AndroidHeartbeatClock.kt`.
 */
interface HeartbeatClock {

    /** Wanduhr in ms seit Epoch. Springt bei Zeitkorrekturen. */
    fun wallClockMs(): Long

    /**
     * Monotone Uhr seit dem Systemstart in ms (Androids
     * `SystemClock.elapsedRealtime()`), oder `null`, wenn es keine gibt.
     */
    fun elapsedRealtimeMs(): Long?

    /**
     * Kennung des laufenden Boot-Vorgangs, oder `null`, wenn sie sich nicht
     * ermitteln laesst. Nur bei *gleicher* Kennung darf die monotone Uhr
     * zweier Prozesse verglichen werden.
     */
    fun bootId(): String?

    /** Alle drei Werte in einem Rutsch. */
    fun stempel(): HeartbeatStamp = HeartbeatStamp(
        wallClockMs = wallClockMs(),
        elapsedRealtimeMs = elapsedRealtimeMs(),
        bootId = bootId(),
    )
}

/**
 * Reine Wanduhr — der Rueckfall fuer Tests und fuer alles, was ohne Android
 * laeuft. Ohne monotone Uhr und ohne Boot-Kennung faellt
 * [bewerteLebenszeichen] auf das Verhalten von Version 1.x zurueck.
 */
object WallClockHeartbeatClock : HeartbeatClock {
    override fun wallClockMs(): Long = System.currentTimeMillis()
    override fun elapsedRealtimeMs(): Long? = null
    override fun bootId(): String? = null
}

/**
 * Ein Lebenszeichen, wie es in `active.lock` steht.
 *
 * ## Dateiformat (eine Zeile, UTF-8)
 * ```
 * <wanduhr-ms> [<monotone-ms>] [<boot-kennung>]
 * ```
 * Version 1.x schrieb nur die erste Zahl. Genau deshalb sind die beiden
 * hinteren Felder optional und werden durch Leerzeichen getrennt angehaengt
 * statt in ein neues Format gegossen: Ein Journal, das eine aeltere
 * App-Version hinterlassen hat, bleibt lesbar (die Zusatzfelder fehlen dann
 * eben und es gilt weiter die Wanduhr).
 */
data class HeartbeatStamp(
    val wallClockMs: Long,
    val elapsedRealtimeMs: Long? = null,
    val bootId: String? = null,
) {
    /** Zeile fuer `active.lock`; siehe Klassendoc. */
    fun serialisiere(): String = buildString {
        append(wallClockMs)
        if (elapsedRealtimeMs != null) {
            append(' ').append(elapsedRealtimeMs)
            // Die Boot-Kennung steht an dritter Stelle und darf keine
            // Leerzeichen enthalten; ein Platzhalter haelt die Stellen stabil.
            append(' ').append(bootId?.takeIf { it.isNotBlank() }?.replace(' ', '_') ?: UNBEKANNT)
        }
    }

    companion object {
        private const val UNBEKANNT = "-"

        /** Liest eine Zeile aus `active.lock`, oder `null` wenn sie unbrauchbar ist. */
        fun parse(raw: String): HeartbeatStamp? {
            val teile = raw.trim().split(' ').filter { it.isNotEmpty() }
            val wall = teile.getOrNull(0)?.toLongOrNull() ?: return null
            return HeartbeatStamp(
                wallClockMs = wall,
                elapsedRealtimeMs = teile.getOrNull(1)?.toLongOrNull(),
                bootId = teile.getOrNull(2)?.takeIf { it != UNBEKANNT },
            )
        }
    }
}

/** Wie alt das Lebenszeichen eines Journals ist. */
sealed interface HeartbeatAge {

    /** Messbares Alter in ms. */
    data class Bekannt(val ms: Long) : HeartbeatAge

    /**
     * Seit dem Lebenszeichen wurde das Geraet neu gestartet. Damit ist sicher,
     * dass niemand mehr in das Journal schreibt — kein Prozess ueberlebt einen
     * Neustart.
     */
    data object Neustart : HeartbeatAge

    /**
     * Es gibt kein lesbares Lebenszeichen. **Nicht** dasselbe wie „alt": Ein
     * fehlgeschlagener Schreibversuch (voller Speicher) sieht genauso aus wie
     * ein nie geschriebenes Lebenszeichen, und in beiden Faellen kann sehr wohl
     * ein Dienst am Schreiben sein.
     */
    data object Unbekannt : HeartbeatAge
}

/**
 * Bewertet ein gelesenes Lebenszeichen gegen den aktuellen Zeitstempel.
 *
 * Die Reihenfolge der Quellen ist bewusst so gewaehlt:
 *
 *  1. **Boot-Kennung gleich** → die monotone Uhr ist die Wahrheit. Sie kennt
 *     keine Zeitspruenge und laeuft im Suspend weiter.
 *  2. **Boot-Kennung verschieden** → das Geraet wurde neu gestartet
 *     ([HeartbeatAge.Neustart]).
 *  3. **Keine Boot-Kennung, aber beide monotonen Werte da** → laeuft die
 *     monotone Uhr vorwaerts, ist derselbe Boot plausibel und ihr Abstand
 *     gilt; ist sie zurueckgesprungen, kann das nur ein Neustart gewesen sein.
 *  4. **Sonst** → Wanduhr, wie in Version 1.x. Ein negatives Ergebnis (die Uhr
 *     wurde zurueckgestellt) wird auf 0 gezogen: „gerade eben" ist die
 *     schonende Auslegung, und schonen heisst hier, Daten nicht anzufassen.
 */
fun bewerteLebenszeichen(stempel: HeartbeatStamp?, jetzt: HeartbeatStamp): HeartbeatAge {
    if (stempel == null) return HeartbeatAge.Unbekannt

    val damalsMonoton = stempel.elapsedRealtimeMs
    val jetztMonoton = jetzt.elapsedRealtimeMs
    val damalsBoot = stempel.bootId
    val jetztBoot = jetzt.bootId

    if (damalsBoot != null && jetztBoot != null && damalsMonoton != null && jetztMonoton != null) {
        if (damalsBoot != jetztBoot) return HeartbeatAge.Neustart
        return HeartbeatAge.Bekannt((jetztMonoton - damalsMonoton).coerceAtLeast(0L))
    }

    if (damalsMonoton != null && jetztMonoton != null) {
        if (jetztMonoton < damalsMonoton) return HeartbeatAge.Neustart
        return HeartbeatAge.Bekannt(jetztMonoton - damalsMonoton)
    }

    return HeartbeatAge.Bekannt((jetzt.wallClockMs - stempel.wallClockMs).coerceAtLeast(0L))
}

/** Ergebnis von [beurteileJournal]. */
enum class JournalUrteil {

    /** Es schreibt (moeglicherweise) noch jemand — Finger weg. */
    VERSCHONEN,

    /** Niemand schreibt mehr — das Journal darf zu einer Tour werden. */
    WIEDERHERSTELLEN,
}

/**
 * Entscheidet, ob ein liegengebliebenes Journal verwaist ist.
 *
 * Der entscheidende Punkt gegenueber der ersten Fassung: [HeartbeatAge.Unbekannt]
 * heisst **nicht** „sicher tot". Ein `touchHeartbeat`, das an einem vollen
 * Speicher scheitert, hinterlaesst genau diesen Zustand — und wer daraufhin
 * `active.jsonl` umbenennt, zieht einem lebenden Dienst die Datei unter dem
 * offenen Dateideskriptor weg: Der schreibt danach stundenlang in einen
 * geloeschten Inode, und am Ende der Fahrt ist die Tour vollstaendig weg.
 *
 * Als zweite Quelle dient deshalb die Aenderungszeit von `active.jsonl` selbst.
 * Sie ist grob (die Datei wird nur beim Annehmen eines Punktes beschrieben, im
 * Stand also gar nicht), aber sie beantwortet die einzige Frage, auf die es
 * hier ankommt: Hat in den letzten Sekunden ueberhaupt noch jemand
 * geschrieben? Nur wenn beide Quellen schweigen, gilt das Journal als verwaist.
 *
 * @param alter Urteil ueber das Lebenszeichen (siehe [bewerteLebenszeichen]).
 * @param journalAlterMs Alter der letzten Aenderung an `active.jsonl` in ms,
 *   oder `null`, wenn das Dateisystem darueber nichts sagt.
 * @param verfallsalterMs Ab welchem Alter ein Lebenszeichen erloschen ist.
 */
fun beurteileJournal(
    alter: HeartbeatAge,
    journalAlterMs: Long?,
    verfallsalterMs: Long,
): JournalUrteil = when (alter) {
    is HeartbeatAge.Bekannt ->
        if (alter.ms >= verfallsalterMs) JournalUrteil.WIEDERHERSTELLEN else JournalUrteil.VERSCHONEN

    HeartbeatAge.Neustart -> JournalUrteil.WIEDERHERSTELLEN

    HeartbeatAge.Unbekannt ->
        if (journalAlterMs != null && journalAlterMs >= verfallsalterMs) {
            JournalUrteil.WIEDERHERSTELLEN
        } else {
            JournalUrteil.VERSCHONEN
        }
}

// ------------------------------------------------------------- Wettrennen

/**
 * Schiedsrichter zwischen dem gerade startenden Aufzeichnungsdienst und der
 * Wiederherstellung beim App-Start.
 *
 * ## Das Problem
 * Stirbt der Prozess mitten in der Fahrt, laufen beim `START_STICKY`-Neustart
 * zwei Dinge gleichzeitig an: `TrailscapeApplication.onCreate` stoesst die
 * Wiederherstellung auf einem IO-Thread an, und das System stellt dem Dienst
 * sein `ACTION_CONTINUE` zu. Wer zuerst am Journal ist, entscheidet ueber den
 * Rest der Fahrt — und gewinnt die Wiederherstellung, schliesst sie die
 * *laufende* Tour als „(wiederhergestellt)" ab, loescht `active.jsonl`, und der
 * Dienst findet nichts mehr zum Fortsetzen. Der Nutzer faehrt weiter und
 * zeichnet nichts mehr auf.
 *
 * ## Die Loesung
 * Der Dienst gewinnt, weil nur er weiss, ob er fortsetzen will. Die
 * Wiederherstellung wartet deshalb am Prozessanfang eine Gnadenfrist lang, ob
 * sich ein Dienst meldet ([freigeben]). Meldet sich keiner, laeuft sie nach
 * Ablauf der Frist trotzdem los — sonst bliebe ein wirklich verwaistes Journal
 * fuer immer liegen.
 *
 * ## Warum eine Frist und keine Abfrage
 * Ob gerade ein Dienst hochfaehrt, laesst sich von aussen nicht zuverlaessig
 * feststellen: `ActivityManager.getRunningServices` ist seit API 26 auf die
 * eigene App beschraenkt und ausdruecklich als unzuverlaessig dokumentiert,
 * und das Lebenszeichen im Dateisystem ist genau die Quelle, deren
 * Vertrauenswuerdigkeit hier in Frage steht. Ein Dienst, den das System
 * gerade neu startet, ruft seinen `onCreate` innerhalb weniger
 * Millisekunden nach dem der `Application` — die Frist ist also grosszuegig
 * bemessen. Bezahlt wird sie mit einer um wenige Sekunden spaeter
 * gespeicherten Tour bei einem echten Absturz; die Wiederherstellung laeuft
 * ohnehin im Hintergrund, niemand wartet darauf.
 *
 * Die Frist beginnt mit dem Erzeugen dieser Instanz, also mit dem Laden der
 * [RecordingService]-Klasse und damit praktisch mit dem Prozessstart.
 */
internal class RecoveryGate(
    private val gnadenfristMs: Long,
    private val uhrMs: () -> Long = { System.nanoTime() / 1_000_000 },
) {
    private val sperre = ReentrantLock()
    private val bedingung = sperre.newCondition()
    private val startMs = uhrMs()

    private var entschieden = false

    /**
     * Der Dienst hat entschieden (fortgesetzt, neu begonnen oder aufgegeben).
     * Eine wartende Wiederherstellung darf sofort weiter.
     */
    fun freigeben() {
        sperre.withLock {
            entschieden = true
            bedingung.signalAll()
        }
    }

    /**
     * Wartet, bis der Dienst entschieden hat oder die Gnadenfrist abgelaufen
     * ist.
     *
     * @return `true`, wenn ein Dienst entschieden hat, `false` beim Ablauf der
     *   Frist. Beide Faelle sind gueltig — der Rueckgabewert dient der Diagnose
     *   und den Tests.
     */
    fun warteAufDienst(): Boolean = sperre.withLock {
        while (!entschieden) {
            val restMs = startMs + gnadenfristMs - uhrMs()
            if (restMs <= 0L) return@withLock false
            try {
                bedingung.await(restMs, TimeUnit.MILLISECONDS)
            } catch (e: InterruptedException) {
                // Das Unterbrechen nicht verschlucken, aber auch nicht daran
                // scheitern: Die Wiederherstellung laeuft dann eben sofort.
                Thread.currentThread().interrupt()
                return@withLock entschieden
            }
        }
        true
    }
}

// -------------------------------------------------------------- Abschluss

/**
 * Fuehrt die Punkte aus der Datei und die aus dem RAM zusammen; die laengere
 * Reihe gewinnt.
 *
 * „Die Datei ist die Wahrheit" gilt nur, solange sich die Datei auch schreiben
 * laesst. Bei vollem Speicher wirft `appendPoint`, der Punkt bleibt aber im RAM
 * und in der Anzeige — waehrend die Tour am Ende alles ab dem ersten
 * Schreibfehler verloere. Da beide Reihen dieselbe Aufzeichnung in derselben
 * Reihenfolge enthalten und die RAM-Reihe hoechstens weiter fortgeschritten
 * ist, ist die laengere die vollstaendigere.
 *
 * Bewusst kein Verschmelzen Punkt fuer Punkt: Es gibt keinen Fall, in dem die
 * Datei einen Punkt enthaelt, den der RAM nicht kennt — geschrieben wird erst,
 * nachdem der Filter den Punkt angenommen hat.
 */
fun vereinigePunkte(ausDatei: List<TrackPoint>, ausRam: List<TrackPoint>): List<TrackPoint> =
    if (ausRam.size > ausDatei.size) ausRam else ausDatei

/**
 * Zieht die Pausenzeit aus der gespeicherten Dauer heraus.
 *
 * `computeStats` in `:core` rechnet `durationS` als Abstand zwischen erstem und
 * letztem Punkt — inklusive aller Pausen. Die Live-Anzeige des Dienstes rechnet
 * dagegen `jetzt - start - pausen`. Nach einer halben Stunde Pause zeigte die
 * Notification 2:00 h und die fertig gespeicherte Tour 2:30 h: derselbe
 * Sachverhalt, zwei Zahlen.
 *
 * Korrigiert wird [RideStats.durationS] und nicht [RideStats.movingTimeS]:
 * `movingTimeS` hat eine eigene, wohldefinierte Bedeutung (Zeit oberhalb von
 * 1 km/h, aus den Punktabstaenden gerechnet) und ist bereits pausenfrei —
 * waehrend `durationS` genau die Zahl ist, die in der Tourenliste als „Dauer"
 * steht und die der Nutzer mit dem Wert in der Notification vergleicht.
 *
 * `computeStats` selbst bleibt unangetastet: Es ist eine 1:1-Portierung, die
 * mit der Web-App und dem Sync-Server byteweise uebereinstimmen muss und die
 * von Pausen gar nichts wissen kann (in den Punkten steht nur eine Luecke, und
 * eine Luecke kann auch ein GPS-Ausfall sein). Das Wissen, dass die Luecke eine
 * Pause war, hat allein das Journal.
 *
 * [RideStats.avgSpeedKmh] bleibt unangetastet: `computeStats` leitet sie aus
 * `movingTimeS` ab, sobald es das gibt — und das gibt es immer, wenn es
 * ueberhaupt eine Pause gab (dafuer braucht es Punkte mit Zeitstempeln vor und
 * nach ihr). Sie ist also bereits pausenfrei gerechnet.
 *
 * Am Dateiformat aendert sich nichts: `durationS` bleibt eine Ganzzahl in
 * Sekunden an derselben Stelle. Bereits gespeicherte Touren werden nicht
 * angefasst — sie behalten ihre bisherige Dauer.
 */
fun ohnePausenzeit(stats: RideStats, pausedMs: Long): RideStats {
    if (pausedMs <= 0L) return stats
    val dauer = stats.durationS ?: return stats
    val korrigiert = dauer - (pausedMs / 1000L).toInt()
    return stats.copy(durationS = korrigiert.coerceAtLeast(0))
}
