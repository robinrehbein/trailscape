package de.trailscape.wear.exercise

import androidx.health.services.client.ExerciseClient
import androidx.health.services.client.data.DataType
import androidx.health.services.client.data.DeltaDataType
import androidx.health.services.client.data.ExerciseType
import androidx.health.services.client.getCapabilities

/**
 * Was kann diese konkrete Uhr fuer [ExerciseType.BIKING] wirklich?
 *
 * Google garantiert fuer Radfahren nur einen Kern —
 * `HEART_RATE_BPM, LOCATION, STEPS, DISTANCE, SPEED, PACE, ELEVATION_GAIN,
 * CALORIES`. Alles darueber hinaus ist geraeteabhaengig — allen voran
 * [DataType.ABSOLUTE_ELEVATION] (Hoehe ueber NN), die auf der Galaxy Watch
 * Ultra laut Mess-Spike (docs/wear-spike.md) vorhanden ist, aber nicht auf
 * jeder Wear-OS-Uhr sein muss: `ELEVATION_GAIN` ist nur der Zuwachs seit dem
 * letzten Punkt und laesst sich nicht in absolute Hoehen zurueckrechnen.
 *
 * Deshalb wird hier NIE eine Liste hart angefordert, sondern immer die
 * Wunschliste gegen die Geraetefaehigkeiten geschnitten. Health Services
 * beantwortet ein `startExercise` mit unbekanntem Datentyp mit einer
 * Ausnahme — die Aufzeichnung wuerde also gar nicht erst starten.
 */

/**
 * Alles, was fuer eine Radaufzeichnung interessant waere — unabhaengig davon,
 * ob die konkrete Uhr es liefert. [ermittleFaehigkeiten] schneidet diese
 * Liste gegen die tatsaechlichen Geraetefaehigkeiten.
 *
 * Bewusst nur [DeltaDataType]s: Nur diese duerfen in eine [androidx.health.services.client.data.WarmUpConfig]
 * (siehe [ExerciseRecorder.vorbereiten]) — jeder angeforderte Typ soll auch
 * vorgewaermt werden koennen.
 */
val WUNSCH_DATENTYPEN: Set<DeltaDataType<*, *>> = setOf(
    DataType.LOCATION,
    DataType.HEART_RATE_BPM,
    // Der eigentliche Gegenstand der Frage.
    DataType.ABSOLUTE_ELEVATION,
    DataType.ELEVATION_GAIN,
    DataType.ELEVATION_LOSS,
    DataType.DISTANCE,
    DataType.SPEED,
    DataType.PACE,
    // Heisst in health-services-client 1.0.0 CALORIES (die Aggregat-Variante
    // CALORIES_TOTAL waere kein DeltaDataType); ein DataType.TOTAL_CALORIES
    // gibt es in dieser Version nicht.
    DataType.CALORIES,
    DataType.STEPS,
)

/**
 * Ergebnis der Faehigkeitsabfrage — anzeigbar auf der Uhr und Zeile fuer Zeile
 * ins Journal schreibbar.
 *
 * [angeforderte] ist die Schnittmenge, die tatsaechlich in die
 * [androidx.health.services.client.data.ExerciseConfig] geht; [vermisste] die
 * fuer die Auswertung viel wichtigere Gegenprobe.
 */
data class FaehigkeitsBericht(
    /** Ob die Uhr Radfahren ueberhaupt als Uebungsart kennt. */
    val radfahrenUnterstuetzt: Boolean,
    /** Schnittmenge aus [WUNSCH_DATENTYPEN] und dem, was die Uhr kann. */
    val angeforderte: Set<DeltaDataType<*, *>>,
    /** Namen der unterstuetzten Wunsch-Datentypen, alphabetisch. */
    val unterstuetzteNamen: List<String>,
    /** Namen der Wunsch-Datentypen, die die Uhr NICHT liefert. */
    val vermissteNamen: List<String>,
    /**
     * Alles, was die Uhr fuer Radfahren kann — auch was gar nicht auf der
     * Wunschliste stand. Faellt bei der Auswertung des Journals oft mehr auf
     * als die Wunschliste selbst.
     */
    val geraeteNamen: List<String>,
    val unterstuetztAutoPause: Boolean,
) {
    /** Kurzform fuer die Oberflaeche: liefert die Uhr die absolute Hoehe? */
    val hatAbsoluteHoehe: Boolean
        get() = DataType.ABSOLUTE_ELEVATION in angeforderte
}

/**
 * Fragt die Faehigkeiten der Uhr ab und schneidet [WUNSCH_DATENTYPEN] dagegen.
 *
 * Nutzt die Coroutine-Erweiterung `getCapabilities()` aus
 * health-services-client 1.0.0; die `…Async`-Variante mit ListenableFuture
 * wird nirgends von Hand ueberbrueckt.
 */
suspend fun ermittleFaehigkeiten(client: ExerciseClient): FaehigkeitsBericht {
    val faehigkeiten = client.getCapabilities()
    val rad = faehigkeiten.typeToCapabilities[ExerciseType.BIKING]
        ?: return FaehigkeitsBericht(
            radfahrenUnterstuetzt = false,
            angeforderte = emptySet(),
            unterstuetzteNamen = emptyList(),
            vermissteNamen = WUNSCH_DATENTYPEN.map { it.name }.sorted(),
            geraeteNamen = emptyList(),
            unterstuetztAutoPause = false,
        )

    // `filter` statt `intersect`: Die Standard-Schnittmenge zweier Mengen
    // verschiedener Typparameter liefert Set<DataType<*, *>> und wuerde die
    // Delta-Eigenschaft verlieren, die die WarmUpConfig verlangt.
    val angeforderte = WUNSCH_DATENTYPEN.filter { it in rad.supportedDataTypes }.toSet()
    val vermisste = WUNSCH_DATENTYPEN - angeforderte

    return FaehigkeitsBericht(
        radfahrenUnterstuetzt = true,
        angeforderte = angeforderte,
        unterstuetzteNamen = angeforderte.map { it.name }.sorted(),
        vermissteNamen = vermisste.map { it.name }.sorted(),
        geraeteNamen = rad.supportedDataTypes.map { it.name }.sorted(),
        unterstuetztAutoPause = rad.supportsAutoPauseAndResume,
    )
}
