package de.trailscape.app.widget

import android.content.Context
import de.trailscape.app.data.AppServices
import de.trailscape.core.loadPlan
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * Liest Touren und Plan vom Geraet und rechnet daraus den [WidgetState] —
 * die eine Stelle, an der Handy-Widget und Uhr-Kachel
 * ([de.trailscape.app.wear.WearGlanceSender]) ihre Zahlen herholen. Zwei
 * getrennte Rechnungen koennten auseinanderlaufen, und dann zeigte die Uhr
 * eine andere Serie als das Widget.
 *
 * Blockierend (Dateizugriff): nur von einem IO-Kontext aufrufen. Wirft nie;
 * bei einem Lesefehler kommt [WidgetState.Empty].
 */
fun loadWidgetState(context: Context): WidgetState = runCatching {
    // Das Widget kann einen Prozess wecken, dessen Application gerade
    // erst hochfaehrt; `init` ist idempotent (siehe AppServices).
    AppServices.init(context)
    val zone = ZoneId.systemDefault()
    buildWidgetState(
        // Zusammenfassungen genuegen: Die Rechnung braucht nur
        // Kennzahlen und Zeitstempel, nie GPS-Punkte.
        rides = AppServices.rideStorage.listSummaries().summaries,
        plan = loadPlan(AppServices.trainingPlanStore),
        now = LocalDateTime.now(zone),
        zoneDateOf = { ms -> Instant.ofEpochMilli(ms).atZone(zone).toLocalDate() },
        zone = zone,
        shownTarget = ShownWeekTarget.decode(
            runCatching { AppServices.keyValueStore.getString(ShownWeekTarget.STORAGE_KEY) }.getOrNull(),
        ),
    )
}.getOrDefault(WidgetState.Empty)
