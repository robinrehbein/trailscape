package de.trailscape.core.indoor

import de.trailscape.core.RouteWaySection
import de.trailscape.core.TrackPoint
import de.trailscape.core.TrainingProfile
import de.trailscape.core.TrainingSession
import de.trailscape.core.Waypoint

/**
 * # Einstiegspunkte des Slices indoor-core
 *
 * EINSTIEGSDATEI (docs/indoor.md, Abschnitt 3). Besitzer: Slice indoor-core.
 *
 * Die **Signaturen** hier sind Vertrag: Die anderen Slices rufen genau diese
 * Funktionen auf, waehrend indoor-core sie parallel umsetzt. Der Architekt hat
 * nur Platzhalter hinterlegt, die werfen — indoor-core ersetzt die Ruempfe
 * (die Umsetzung selbst gehoert in eigene Dateien dieses Pakets) und laesst
 * Namen, Parameter und Rueckgabetypen unveraendert.
 */

private fun offen(was: String): Nothing =
    throw NotImplementedError("Indoor: $was ist noch nicht umgesetzt (Slice indoor-core)")

/**
 * Baut aus den Punkten einer geplanten Runde oder gefahrenen Tour die
 * [IndoorRoute] mit allen Zusicherungen aus `IndoorRoute.kt` (neu abgetastet,
 * Hoehen ergaenzt und geglaettet, Belag je Abschnitt).
 *
 * @param points die Punkte in Fahrtrichtung; Zeitstempel und Sensorwerte werden ignoriert.
 * @param waySections BRouters Wegabschnitte zu genau dieser Linie
 *   (`PlannedRoute.waySections`) oder `null`. Weicht ihre Gesamtlaenge von der
 *   der Linie ab (Abgleich einer gefahrenen Tour), werden sie anteilig gestreckt.
 * @param source woher [waySections] stammen; ohne Abschnitte immer [IndoorRouteSource.NONE].
 * @return `null`, wenn sich nichts Fahrbares ergibt (weniger als zwei
 *   verschiedene Punkte, kuerzer als 200 m).
 */
fun buildIndoorRoute(
    name: String,
    points: List<TrackPoint>,
    waySections: List<RouteWaySection>?,
    source: IndoorRouteSource,
): IndoorRoute? = offen("buildIndoorRoute")

/**
 * Stuetzpunkte fuer den nachtraeglichen Belagsabgleich einer gespeicherten
 * Tour: Die App routet mit dem vorhandenen Router durch genau diese Punkte
 * und reicht die `waySections` der Antwort mit
 * [IndoorRouteSource.BROUTER_REMATCH] an [buildIndoorRoute]. So eng gesetzt,
 * dass der Router der Spur folgt, und hoechstens so viele, wie eine
 * Routing-Anfrage vertraegt. Leer, wenn die Spur dafuer nicht taugt.
 */
fun surfaceRematchWaypoints(points: List<TrackPoint>): List<Waypoint> = offen("surfaceRematchWaypoints")

/** Fahrer und Rad aus dem Trainingsprofil (Gewicht, Setup-Masse, CdA, Crr, FTP, Wirkungsgrad). */
fun riderSetupFrom(profile: TrainingProfile): RiderSetup = offen("riderSetupFrom")

/**
 * Die ERG-Einheit zur Planeinheit [session] — Aufwaermen, Belastungen nach
 * Intensitaet und Dauer der Einheit, Ausfahren.
 *
 * @param title Titel fuer die Anzeige (`sessionTitle(session, texts)`).
 * @return `null`, wenn sich die Einheit nicht fuer die Rolle eignet (das Zielevent selbst).
 */
fun ergWorkoutFor(session: TrainingSession, title: String): ErgWorkout? = offen("ergWorkoutFor")

/** Eine neue Sitzung in der Phase [IndoorPhase.READY]. */
fun createIndoorSession(config: IndoorSessionConfig): IndoorSession = offen("createIndoorSession")

/** Der FTMS-Trainer hinter dem Rohr [link] (Indoor Bike Data, Control Point, Wiederholen). */
fun createFtmsTrainer(link: BleLink): IndoorTrainer = offen("createFtmsTrainer")

/** Der simulierte Trainer — braucht kein Rohr und keine Berechtigung. */
fun createDemoTrainer(): DemoTrainer = offen("createDemoTrainer")

/** Der Zwift-Click-Schalttaster hinter dem Rohr [link]. */
fun createZwiftClickShifter(link: BleLink): IndoorShifter = offen("createZwiftClickShifter")
