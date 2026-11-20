package de.trailscape.app.ui.today

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Route
import androidx.compose.material3.Card
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import de.trailscape.app.R
import de.trailscape.app.data.AppServices
import de.trailscape.app.i18n.LocalCoreTexts
import de.trailscape.app.i18n.asString
import de.trailscape.app.ui.map.lastKnownLocationIfPermitted
import de.trailscape.app.ui.theme.CardPadding
import de.trailscape.app.ui.theme.LocalSignalColors
import de.trailscape.core.PlannedRide
import de.trailscape.core.RideInfo
import de.trailscape.core.TrackPoint
import de.trailscape.core.WeekForecast
import de.trailscape.core.WeekForecastStatus
import de.trailscape.core.WeekPlan
import de.trailscape.core.WeekPlanStatus
import de.trailscape.core.loadWeekForecast
import de.trailscape.core.pruneExpiredWeekForecast
import de.trailscape.core.readWeekPlanWeatherEnabled
import de.trailscape.core.riddenRides
import de.trailscape.core.weekPlanWeatherPoint
import de.trailscape.core.writeWeekPlanWeatherEnabled
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.temporal.ChronoUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * # Karte „Wochen-Fahrplan" auf „Heute"
 *
 * Steht unter „Diese Woche", solange das mitwachsende Wochenziel offen ist
 * und kein Trainingsplan laeuft. Je vorgeschlagenem Tag eine Zeile („Do ·
 * 35 km", darunter „trocken, 14 °C") mit „Runde planen" — derselbe Weg an den
 * Rundkurs-Generator wie „Nächste Bestweite". Gerechnet wird in
 * `core/…/WeekPlan.kt`, formuliert in `WeekPlanWording.kt`.
 *
 * Unten der Schalter „Wetter für den Wochenplan" (ab Werk aus) mit dem
 * Hinweis auf die Anfrage — die Einwilligung steht dort, wo sie wirkt.
 */
@Composable
internal fun WeekPlanCard(
    plan: WeekPlan,
    today: LocalDate,
    weather: WeekPlanWeatherUi,
    onWeatherEnabledChange: (Boolean) -> Unit,
    onPlanRide: (PlannedRide) -> Unit,
) {
    val texts = LocalCoreTexts.current
    val theme = MaterialTheme.colorScheme
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(CardPadding),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Column {
                Text(
                    text = stringResource(R.string.today_week_plan_title),
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.semantics { heading() },
                )
                Spacer(modifier = Modifier.padding(top = 2.dp))
                Text(
                    text = weekPlanSummary(plan).asString(),
                    style = MaterialTheme.typography.bodyMedium,
                    color = theme.onSurfaceVariant,
                )
            }

            plan.rides.forEachIndexed { index, ride ->
                if (index > 0) HorizontalDivider(color = theme.outlineVariant)
                WeekPlanRow(
                    title = weekPlanRowTitle(ride, today, texts).asString(),
                    reason = weekPlanReason(ride).asString(),
                    warning = weekPlanReasonIsWarning(ride.reason),
                    buildDescription = stringResource(
                        R.string.today_week_plan_build_cd,
                        ride.km,
                        weekPlanDayLong(ride.date, today, texts).asString(),
                    ),
                    onBuild = { onPlanRide(ride) },
                )
            }

            weekPlanTightNote(plan)?.let { note ->
                Text(
                    text = note.asString(),
                    style = MaterialTheme.typography.bodySmall,
                    color = LocalSignalColors.current.caution,
                )
            }

            if (plan.status != WeekPlanStatus.KEINE_TAGE) {
                Text(
                    text = weekPlanWeatherHint(weather, plan).asString(),
                    style = MaterialTheme.typography.labelSmall,
                    color = theme.onSurfaceVariant,
                )
            }
            // Der Schalter steht auch ohne Fahrtage da: Wer das Wetter wieder
            // ausschalten will, soll das nicht erst naechste Woche koennen.
            HorizontalDivider(color = theme.outlineVariant)
            WeatherSwitchRow(
                checked = weather != WeekPlanWeatherUi.AUS,
                onCheckedChange = onWeatherEnabledChange,
            )
        }
    }
}

/** Eine Zeile: Tag und km, darunter der Grund; rechts „Runde planen". */
@Composable
private fun WeekPlanRow(
    title: String,
    reason: String,
    warning: Boolean,
    buildDescription: String,
    onBuild: () -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text = title, style = MaterialTheme.typography.titleSmall)
            Text(
                text = reason,
                style = MaterialTheme.typography.bodySmall,
                color = if (warning) LocalSignalColors.current.caution else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(modifier = Modifier.width(12.dp))
        FilledTonalButton(
            onClick = onBuild,
            modifier = Modifier
                .heightIn(min = 48.dp)
                .semantics { contentDescription = buildDescription },
            contentPadding = PaddingValues(horizontal = 14.dp),
        ) {
            Icon(Icons.Filled.Route, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                text = stringResource(R.string.today_week_plan_build_action),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/**
 * Der Schalter samt Hinweis auf die Anfrage. Die ganze Zeile ist die
 * Tippflaeche (`toggleable`, `Role.Switch`), damit TalkBack Titel und Hinweis
 * als Beschriftung vorliest — es ist eine Einwilligung, die muss man hoeren
 * koennen. Muster: Schalterzeile der Rundkurs-Suche.
 */
@Composable
private fun WeatherSwitchRow(checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .toggleable(value = checked, role = Role.Switch, onValueChange = onCheckedChange),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text = stringResource(R.string.today_week_plan_weather_switch), style = MaterialTheme.typography.titleSmall)
            Text(
                text = stringResource(R.string.today_week_plan_weather_switch_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(modifier = Modifier.width(12.dp))
        Switch(checked = checked, onCheckedChange = null)
    }
}

/**
 * Zustand des Wetters fuer die Karte: Schalter und geladene Vorhersage.
 *
 * @property enabled der Schalter.
 * @property forecast `null`, solange (noch) nichts geladen ist.
 */
internal class WeekPlanWeatherHolder(enabled: Boolean) {
    var enabled by mutableStateOf(enabled)
    var forecast by mutableStateOf<WeekForecast?>(null)

    /**
     * Single-flight: Es laeuft hoechstens ein Laden zugleich. Beim Oeffnen
     * startet der Effekt einmal mit der Komposition und gleich noch einmal mit
     * dem ersten Resume; die abgebrochene Anfrage laeuft auf dem IO-Thread
     * aber weiter. Der zweite Durchlauf wartet deshalb, bis der erste fertig
     * ist, und findet dann den frischen Zwischenspeicher statt eine zweite
     * Anfrage zu schicken.
     */
    val loadMutex = Mutex()

    val ui: WeekPlanWeatherUi get() = weekPlanWeatherUi(enabled, forecast?.status)
}

/**
 * Schalter lesen und — nur wenn er an ist und die Karte Fahrtage zeigt
 * ([active]) — die Vorhersage laden: aus dem Zwischenspeicher oder mit einer
 * Anfrage (`core/…/WeekPlanWeather.kt`, [loadWeekForecast]). Erneut beim
 * Umschalten, bei einem neuen Tag und beim Zurueckkehren in die App; der
 * Zwischenspeicher sorgt dafuer, dass das hoechstens alle paar Stunden
 * wirklich ins Netz geht. Eine abgelaufene Vorhersage wird bei jedem Durchlauf
 * geloescht, auch ohne Karte ([pruneExpiredWeekForecast]).
 */
@Composable
internal fun rememberWeekPlanWeather(active: Boolean, today: LocalDate, rides: List<RideInfo>): WeekPlanWeatherHolder {
    val context = LocalContext.current
    val holder = remember {
        WeekPlanWeatherHolder(runCatching { readWeekPlanWeatherEnabled(AppServices.keyValueStore) }.getOrDefault(false))
    }
    var resumeTick by remember { mutableStateOf(0) }
    LifecycleResumeEffect(Unit) {
        resumeTick++
        onPauseOrDispose { }
    }
    val lastRide = remember(rides) { riddenRides(rides).maxByOrNull { it.createdAt } }
    val currentLastRide by rememberUpdatedState(lastRide)
    LaunchedEffect(holder.enabled, active, today, resumeTick) {
        withContext(Dispatchers.IO) { pruneExpiredWeekForecast(AppServices.keyValueStore, System.currentTimeMillis()) }
        if (!holder.enabled) {
            holder.forecast = WeekForecast(emptyList(), WeekForecastStatus.AUS)
            return@LaunchedEffect
        }
        if (!active) return@LaunchedEffect
        val ride = currentLastRide
        val loaded = withContext(Dispatchers.IO) {
            holder.loadMutex.withLock {
                val nowMs = System.currentTimeMillis()
                // Die Tour wird nur gelesen, wenn keine frische Position vorliegt.
                val point = weekPlanWeatherPoint(
                    nowMs = nowMs,
                    lastKnown = lastKnownPoint(context),
                    lastRideAtMs = ride?.createdAt,
                    lastRideStart = { ride?.id?.let(::rideStartPoint) },
                )
                runCatching {
                    loadWeekForecast(
                        store = AppServices.keyValueStore,
                        point = point,
                        nowMs = nowMs,
                        client = AppServices.weatherHttpClient,
                    )
                }.getOrElse { WeekForecast(emptyList(), WeekForecastStatus.NICHT_ERREICHBAR) }
            }
        }
        // Nur uebernehmen, wenn der Schalter noch an ist — sonst stuende nach dem
        // Ausschalten wieder eine Vorhersage auf der Karte.
        if (holder.enabled) holder.forecast = loaded
    }
    return holder
}

/** Schreibt den Schalter und setzt den Zustand; beim Ausschalten verschwindet die Vorhersage sofort. */
internal fun WeekPlanWeatherHolder.setEnabled(value: Boolean) {
    runCatching { writeWeekPlanWeatherEnabled(AppServices.keyValueStore, value) }
    forecast = if (value) null else WeekForecast(emptyList(), WeekForecastStatus.AUS)
    enabled = value
}

/** Zuletzt bekannte Position samt Zeitpunkt des Fixes (fuer die Altersgrenze). */
private fun lastKnownPoint(context: Context): TrackPoint? =
    runCatching { lastKnownLocationIfPermitted(context) }.getOrNull()
        ?.let { TrackPoint(lat = it.latitude, lon = it.longitude, time = it.time) }

/**
 * Startpunkt je Tour, einmal gelesen und gemerkt: Der Index der Touren kennt
 * keine Koordinaten, also muss die Tour-Datei einmal geparst werden — aber
 * nicht bei jedem Resume. Gemerkt wird nur die juengste Tour (ein Eintrag);
 * eine neue Tour hat eine neue ID und wird dann einmal gelesen. Ein
 * Lesefehler wird nicht gemerkt.
 */
private object RideStartMemo {
    @Volatile
    var entry: Pair<String, TrackPoint?>? = null
}

private fun rideStartPoint(rideId: String): TrackPoint? {
    RideStartMemo.entry?.let { (id, point) -> if (id == rideId) return point }
    val loaded = runCatching { AppServices.rideStorage.loadRide(rideId)?.points?.firstOrNull() }
    loaded.onSuccess { RideStartMemo.entry = rideId to it }
    return loaded.getOrNull()
}

/**
 * „Jetzt" auf die volle Stunde — fuer die 18-Uhr-Regel des Wochenplans. Wie
 * `rememberNow`, nur mit der Stunde statt dem Kalendertag als Takt: beim
 * Zurueckkehren in die App neu gelesen und, solange die Seite sichtbar ist,
 * puenktlich zur naechsten vollen Stunde. Der Plan aendert sich damit nur
 * stuendlich, nicht bei jeder Recomposition.
 *
 * Der Wecker haengt an einem Zaehler, nicht am Wert: In der doppelten Stunde
 * der Herbst-Zeitumstellung ergibt die volle Stunde zweimal dasselbe „02:00",
 * und ein Schluessel auf dem Wert wuerde den naechsten Wecker nie stellen.
 */
@Composable
internal fun rememberHourNow(): LocalDateTime {
    var now by remember { mutableStateOf(LocalDateTime.now().truncatedTo(ChronoUnit.HOURS)) }
    var tick by remember { mutableStateOf(0) }
    val scope = rememberCoroutineScope()
    LifecycleResumeEffect(tick) {
        val fresh = LocalDateTime.now().truncatedTo(ChronoUnit.HOURS)
        if (fresh != now) now = fresh
        val alarm = scope.launch {
            val current = LocalDateTime.now()
            val next = current.truncatedTo(ChronoUnit.HOURS).plusHours(1)
            // Eine Sekunde Luft, damit `now()` sicher schon in der neuen Stunde liegt.
            delay(ChronoUnit.MILLIS.between(current, next).coerceAtLeast(0) + 1_000)
            now = LocalDateTime.now().truncatedTo(ChronoUnit.HOURS)
            tick++
        }
        onPauseOrDispose { alarm.cancel() }
    }
    return now
}
