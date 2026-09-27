package de.trailscape.core.i18n

/**
 * Nutzersichtbare Texte der Health-Connect-Anbindung.
 *
 * Die Diagnose- und Protokollzeilen (`HealthConnection.describe()`,
 * `HealthSyncLogic`-Debugliste, `HealthDiagnostics`) bleiben bewusst
 * deutsch: Sie landen in Fehlerberichten an den Entwickler, nicht in der
 * Oberflaeche (siehe `docs/i18n.md`).
 */
interface HealthTexts {
    fun notSupported(): String
    fun notInstalled(): String
    fun updateNeeded(): String
    fun connected(): String
    fun permissionNeeded(): String

    /** Lesefehler; [detail] ist die (technische) Ursache. */
    fun workoutsUnreadable(detail: String): String

    /** Name einer importierten Tour, z. B. „Tour 08.08.2026 (Watch)". */
    fun importedRideName(day: Int, month: Int, year: Int, indoor: Boolean): String

    // Import-Zusammenfassung
    fun noNewRides(): String
    fun ridesImported(count: Int): String
    fun ridesEnrichedWithHeartRate(count: Int, standalone: Boolean): String
    fun ridesWithoutRouteConsent(count: Int): String
    fun ridesWithoutGps(count: Int): String
}

private fun two(value: Int) = value.toString().padStart(2, '0')

internal object HealthTextsDe : HealthTexts {
    override fun notSupported() = "Health Connect wird auf diesem Gerät nicht unterstützt."
    override fun notInstalled() =
        "Health Connect ist nicht installiert. Bitte installiere die App aus " +
            "dem Play Store, damit Trailscape auf die Watch-Daten zugreifen kann."
    override fun updateNeeded() =
        "Health Connect muss aktualisiert werden, bevor Trailscape darauf zugreifen kann."
    override fun connected() = "Health Connect ist verbunden."
    override fun permissionNeeded() =
        "Trailscape braucht noch deine Zustimmung, um Health-Connect-Daten zu lesen."
    override fun workoutsUnreadable(detail: String) =
        "Die Trainings konnten nicht aus Health Connect gelesen werden: $detail"

    override fun importedRideName(day: Int, month: Int, year: Int, indoor: Boolean) =
        "Tour ${two(day)}.${two(month)}.$year (Watch)" + if (indoor) " (Indoor)" else ""

    private fun touren(n: Int) = plural(n, "1 Tour", "$n Touren")
    override fun noNewRides() = "Keine neuen Touren"
    override fun ridesImported(count: Int) = "${touren(count)} importiert"
    override fun ridesEnrichedWithHeartRate(count: Int, standalone: Boolean) =
        if (standalone) "${touren(count)} mit Puls ergänzt" else "$count mit Puls ergänzt"
    override fun ridesWithoutRouteConsent(count: Int) = "$count ohne Route (Freigabe in Health Connect nötig)"
    override fun ridesWithoutGps(count: Int) = "$count ohne GPS-Daten"
}

internal object HealthTextsEn : HealthTexts {
    override fun notSupported() = "Health Connect isn’t supported on this device."
    override fun notInstalled() =
        "Health Connect isn’t installed. Please install the app from the Play Store " +
            "so Trailscape can access your watch data."
    override fun updateNeeded() =
        "Health Connect needs to be updated before Trailscape can access it."
    override fun connected() = "Health Connect is connected."
    override fun permissionNeeded() =
        "Trailscape still needs your permission to read Health Connect data."
    override fun workoutsUnreadable(detail: String) =
        "The workouts couldn’t be read from Health Connect: $detail"

    override fun importedRideName(day: Int, month: Int, year: Int, indoor: Boolean) =
        "Ride $day ${java.time.Month.of(month).getDisplayName(java.time.format.TextStyle.SHORT, AppLanguage.EN.locale)} $year (watch)" +
            if (indoor) " (indoor)" else ""

    private fun rides(n: Int) = plural(n, "1 ride", "$n rides")
    override fun noNewRides() = "No new rides"
    override fun ridesImported(count: Int) = "${rides(count)} imported"
    override fun ridesEnrichedWithHeartRate(count: Int, standalone: Boolean) =
        if (standalone) "${rides(count)} enriched with heart rate" else "$count enriched with heart rate"
    override fun ridesWithoutRouteConsent(count: Int) =
        "$count without a route (permission needed in Health Connect)"
    override fun ridesWithoutGps(count: Int) = "$count without GPS data"
}
