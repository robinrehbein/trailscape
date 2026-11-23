package de.trailscape.app.ui.indoor

import android.content.Context
import de.trailscape.app.R
import de.trailscape.core.i18n.AppLanguage
import de.trailscape.core.i18n.formatDateShort
import java.time.Instant
import java.time.ZoneId

/**
 * Die wenigen Saetze, die das `IndoorViewModel` selbst bildet (Name der
 * gespeicherten Tour, Anzeigenamen) — als Schnittstelle, damit Tests ohne
 * Ressourcen auskommen koennen. In der App [ContextIndoorTexts].
 */
interface IndoorTexts {
    /** Name der gespeicherten Indoor-Tour: „Indoor · <Strecke>". */
    fun rideName(routeName: String): String

    /** Anzeigename der eingebauten Testwelt. */
    fun testWorldName(): String

    /** Anzeigename des Demo-Trainers. */
    fun demoTrainerName(): String

    /** Geist „frühere Fahrt" mit dem Datum der Tour. */
    fun ghostPastRideLabel(createdAtMs: Long): String

    /** Geist mit fester Vergleichsleistung. */
    fun ghostConstantPowerLabel(watts: Int): String
}

/**
 * [IndoorTexts] aus den Ressourcen (`strings_indoor_app.xml`).
 *
 * @param context liefert einen Kontext in der aktuellen App-Sprache
 *   (`AppServices.localizedContext()`), je Aufruf frisch — so gilt ein
 *   Sprachwechsel ohne neues ViewModel.
 */
class ContextIndoorTexts(
    private val context: () -> Context,
    private val language: () -> AppLanguage,
) : IndoorTexts {
    override fun rideName(routeName: String): String =
        context().getString(R.string.indoor_app_ride_name, routeName)

    override fun testWorldName(): String = context().getString(R.string.indoor_app_route_testworld)

    override fun demoTrainerName(): String = context().getString(R.string.indoor_app_trainer_demo_name)

    override fun ghostPastRideLabel(createdAtMs: Long): String {
        val date = Instant.ofEpochMilli(createdAtMs).atZone(ZoneId.systemDefault()).toLocalDate()
        return context().getString(R.string.indoor_app_ghost_past_label, formatDateShort(date, language()))
    }

    override fun ghostConstantPowerLabel(watts: Int): String =
        context().getString(R.string.indoor_app_ghost_power_label, watts)
}
