package de.trailscape.app.ui.indoor

import android.content.Context
import de.trailscape.app.R
import de.trailscape.core.i18n.AppLanguage
import de.trailscape.core.i18n.formatDateShort
import de.trailscape.core.i18n.formatDecimal
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

    /** Name einer Indoor-Tour, die nicht am Anfang begann: „Indoor · <Strecke> (ab km 7,4)". */
    fun rideNameFrom(routeName: String, startKm: Double): String = rideName(routeName)

    /** Anzeigename der eingebauten Testwelt. */
    fun testWorldName(): String

    /** Anzeigename des Demo-Trainers. */
    fun demoTrainerName(): String

    /** Geist „frühere Fahrt" mit dem Datum der Tour. */
    fun ghostPastRideLabel(createdAtMs: Long): String

    /** Geist mit fester Vergleichsleistung. */
    fun ghostConstantPowerLabel(watts: Int): String

    /** Anzeigename einer Herausforderung ohne Namen des Absenders. */
    fun challengeFallbackName(): String = "Herausforderung"

    /** Titel der lockeren Runde an einem Tag ohne Planeinheit (Einheit des Tages). */
    fun dayEasyTitle(): String = "Lockere Runde"

    /** Anzeigename der neu zu planenden Runde (Einheit des Tages). */
    fun dayPlanName(): String = "Neue Runde"

    /** Name der generierten Welt zur Einheit [title]. */
    fun dayGeneratedName(title: String): String = "Generierte Strecke · $title"

    /** Sprache der Oberflaeche (`de`/`en`), fuer die Namen der Katalogstrecken. */
    fun languageTag(): String = "de"
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

    override fun rideNameFrom(routeName: String, startKm: Double): String =
        context().getString(R.string.indoor_app_ride_name_from, routeName, formatDecimal(startKm, 1, language()))

    override fun testWorldName(): String = context().getString(R.string.indoor_app_route_testworld)

    override fun demoTrainerName(): String = context().getString(R.string.indoor_app_trainer_demo_name)

    override fun ghostPastRideLabel(createdAtMs: Long): String {
        val date = Instant.ofEpochMilli(createdAtMs).atZone(ZoneId.systemDefault()).toLocalDate()
        return context().getString(R.string.idee01_ghost_outdoor_label, formatDateShort(date, language()))
    }

    override fun ghostConstantPowerLabel(watts: Int): String =
        context().getString(R.string.indoor_app_ghost_power_label, watts)

    override fun challengeFallbackName(): String = context().getString(R.string.spiel3_fallback_name)

    override fun dayEasyTitle(): String = context().getString(R.string.indoor_app_day_easy_title)

    override fun dayPlanName(): String = context().getString(R.string.indoor_app_day_plan_name)

    override fun languageTag(): String = language().tag

    override fun dayGeneratedName(title: String): String = context().getString(R.string.indoor_app_day_generated_name, title)
}
