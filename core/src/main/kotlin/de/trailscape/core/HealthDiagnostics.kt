package de.trailscape.core

/**
 * Diagnose der Vitaldaten: Was hat Health Connect je Datentyp geliefert, war
 * die Freigabe ueberhaupt erteilt, und aus welchen Apps kamen die Werte?
 *
 * ## Warum
 * Frueher galt ein Datentyp nur dann als „nicht verfuegbar", wenn das Lesen
 * **warf**. Eine leere Antwort sah genauso aus wie „noch keine Daten" — dabei
 * steckt dahinter fast immer etwas anderes: Samsung Health schreibt den Wert
 * gar nicht nach Health Connect (Ruhepuls, HRV und VO2max je nach Version),
 * oder die Freigabe wurde nachtraeglich entzogen. Diese Datei macht den
 * Unterschied in den Diagnose-Details sichtbar.
 */

/** Health-Connect-Leserechte, deren Zustand in die Diagnose eingeht. */
enum class HealthReadType(
    /** Deutsche Bezeichnung fuer die Diagnosezeilen. */
    val label: String,
) {
    RUHEPULS("Ruhepuls"),
    SCHLAF("Schlaf"),
    HRV("HRV"),
    VO2MAX("VO2max"),
    HERZFREQUENZ("Nacht-Puls"),
    ROUTEN("Routen"),

    /** `READ_HEALTH_DATA_HISTORY`: Daten aelter als 30 Tage vor der Freigabe. */
    HISTORIE("Verlauf > 30 Tage"),
}

/** Diagnose eines Vitaldaten-Typs aus einem [HealthSyncService.readVitals]-Lauf. */
data class VitalsTypeDiagnostics(
    val type: HealthReadType,
    /** Freigabe erteilt? `null` = unbekannt (Gateway meldet es nicht). */
    val permissionGranted: Boolean?,
    /** Anzahl gelesener Datensaetze bzw. Messungen. */
    val recordCount: Int,
    /** Paketnamen der Quell-Apps, sortiert. */
    val origins: List<String> = emptyList(),
    /** Fehlermeldung, falls das Lesen geworfen hat. */
    val error: String? = null,
    /** Nur Ruhepuls: Tage, deren Wert aus dem Nacht-Puls stammt. */
    val derivedDays: Int = 0,
    /** Nur Ruhepuls: ob der Nacht-Puls-Ersatz ueberhaupt versucht wurde. */
    val fallbackAttempted: Boolean = false,
    /** Nur Schlaf: wie viele Sitzungen in einer ueberlappenden aufgingen. */
    val mergedOverlaps: Int = 0,
)

/** Erste Zeile des Vitalwerte-Abschnitts; markiert zugleich dessen Beginn. */
const val vitalsDiagnosticsHeader: String = "Vitalwerte"

/**
 * Der Vitalwerte-Abschnitt der Diagnose-Details, z. B.:
 *
 * ```
 * Vitalwerte (60 Tage ab 12.06. 00:00)
 *   · Ruhepuls: 0 Einträge (Freigabe ja) — Samsung Health schreibt diesen Wert vermutlich nicht; Ersatz aus Nacht-Puls aktiv (41 Tage)
 *   · Schlaf: 58 Einträge (Freigabe ja) · Quelle: com.sec.android.app.shealth
 * ```
 *
 * Leer, wenn [summary] keine Diagnose traegt (alte Gateways, Attrappen).
 */
fun describeVitalsDiagnostics(summary: VitalsSummary): List<String> {
    if (summary.diagnostics.isEmpty() && summary.historyAccessGranted == null) {
        return emptyList()
    }
    val lines = mutableListOf(
        "$vitalsDiagnosticsHeader (${summary.days} Tage ab ${healthDebugTime(summary.from)})",
    )
    for (entry in summary.diagnostics) {
        lines.add("  · ${describeVitalsEntry(entry)}")
    }
    when (summary.historyAccessGranted) {
        true -> lines.add("  · ${HealthReadType.HISTORIE.label}: Freigabe ja")
        false -> lines.add(
            "  · ${HealthReadType.HISTORIE.label}: Freigabe nein — Health Connect liefert nur " +
                "die letzten 30 Tage vor der ersten Freigabe",
        )
        null -> Unit
    }
    return lines
}

/** Eine Zeile je Datentyp, ohne Aufzaehlungszeichen. */
internal fun describeVitalsEntry(entry: VitalsTypeDiagnostics): String {
    val permission = when (entry.permissionGranted) {
        true -> "ja"
        false -> "nein"
        null -> "unbekannt"
    }
    val head = if (entry.error != null) {
        "${entry.type.label}: Lesefehler (Freigabe $permission)"
    } else {
        val noun = if (entry.recordCount == 1) "Eintrag" else "Einträge"
        "${entry.type.label}: ${entry.recordCount} $noun (Freigabe $permission)"
    }
    val origins = if (entry.origins.isEmpty()) "" else " · Quelle: ${entry.origins.joinToString(", ")}"

    val hints = mutableListOf<String>()
    if (entry.error != null) {
        hints.add(entry.error)
    }
    if (entry.permissionGranted == false) {
        hints.add("Freigabe in Health Connect erteilen")
    } else if (entry.error == null && entry.recordCount == 0 && entry.type != HealthReadType.HERZFREQUENZ) {
        hints.add("Samsung Health schreibt diesen Wert vermutlich nicht")
    }
    if (entry.type == HealthReadType.RUHEPULS && entry.fallbackAttempted) {
        hints.add(
            if (entry.derivedDays > 0) {
                "Ersatz aus Nacht-Puls aktiv (${entry.derivedDays} ${if (entry.derivedDays == 1) "Tag" else "Tage"})"
            } else {
                "Ersatz aus Nacht-Puls nicht möglich (zu wenige Nachtmessungen)"
            },
        )
    }
    if (entry.mergedOverlaps > 0) {
        hints.add("${entry.mergedOverlaps} überlappende Sitzung(en) zusammengeführt")
    }
    val tail = if (hints.isEmpty()) "" else " — ${hints.joinToString("; ")}"
    return head + origins + tail
}

/**
 * Traegt die Vitaldaten-Diagnose in den Bericht ein: [HealthSyncReport.vitals]
 * und ein Vitalwerte-Abschnitt am Ende der [HealthSyncReport.debugLines].
 *
 * Idempotent: Ein schon vorhandener Vitalwerte-Abschnitt (ab der Zeile, die
 * mit [vitalsDiagnosticsHeader] beginnt) wird ersetzt, nicht verdoppelt.
 */
fun HealthSyncReport.withVitalsDiagnostics(summary: VitalsSummary): HealthSyncReport {
    val base = debugLines.takeWhile { !it.startsWith("$vitalsDiagnosticsHeader (") }
    return copy(
        vitals = summary.diagnostics,
        debugLines = base + describeVitalsDiagnostics(summary),
    )
}
