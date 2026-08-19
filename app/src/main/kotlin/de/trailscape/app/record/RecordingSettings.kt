package de.trailscape.app.record

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings
import de.trailscape.app.data.trailscapePrefs

/**
 * Einstellungen rund um die Aufzeichnung — gelesen und geschrieben ueber
 * dieselben `SharedPreferences` wie der Rest der App (siehe
 * `data/PrefsStores.kt`), bewusst OHNE Umweg ueber das `AppViewModel`: Der
 * [RecordingService] braucht die Werte auf seinem eigenen Thread, und die
 * schlanke Karte „Aufzeichnung" unter Mehr (`ui/more/RecordingCard.kt`)
 * liest/schreibt sie direkt im Composable.
 *
 * Neben der Auto-Pause wohnen hier auch die Schalter der **Sprachansagen**
 * (Hauptschalter, Abbiegehinweise, Kilometer-Ansagen — gesprochen ueber
 * `voice/VoiceAnnouncer.kt`) und der **Off-Route-Vibration**
 * (`voice/Vibration.kt`); gelesen werden sie ausser vom Dienst auch vom
 * Navigations-Effekt in `ui/map/MapScreen.kt`.
 *
 * Dazu die beiden Handgriffe fuer die **Batterieoptimierung**: Manche
 * Hersteller-Energiesparer raeumen den Standort-Listener eines laufenden
 * Vordergrunddienstes ab — die Aufzeichnung laeuft dann scheinbar weiter,
 * zeichnet aber nichts mehr auf. Die Ausnahme von der Batterieoptimierung ist
 * das offizielle Mittel dagegen; angefragt wird sie einmalig beim Start einer
 * Aufzeichnung (`ui/map/MapScreen.kt`) und jederzeit unter Mehr →
 * Aufzeichnung.
 */

/** Schluessel des Auto-Pause-Schalters (Boolean, Default AN). */
internal const val PREF_AUTO_PAUSE = "trailscape.autopause"

/**
 * Schluessel des Hauptschalters „Sprachansagen" (Boolean, Default AUS).
 *
 * Bewusst AUS: Dass das Telefon ploetzlich spricht — womoeglich ueber eine
 * laufende Musikwiedergabe —, soll eine bewusste Entscheidung sein, kein
 * Ueberraschungseffekt der ersten Fahrt nach dem Update.
 */
internal const val PREF_VOICE = "trailscape.voice"

/** Schluessel des Unterschalters „Abbiegehinweise" (Boolean, Default AN). */
internal const val PREF_VOICE_TURNS = "trailscape.voiceTurns"

/** Schluessel des Unterschalters „Kilometer-Ansagen" (Boolean, Default AN). */
internal const val PREF_VOICE_MILESTONES = "trailscape.voiceMilestones"

/**
 * Schluessel des Schalters „Vibration abseits der Route" (Boolean, Default
 * AN). Bewusst UNABHAENGIG vom Hauptschalter [PREF_VOICE]: Die Vibration
 * braucht keine Sprachausgabe und warnt auch die, die nie eine Ansage hoeren
 * wollen (siehe `voice/Vibration.kt`).
 */
internal const val PREF_OFFROUTE_VIBRATION = "trailscape.offrouteVibration"

/**
 * Schluessel des Merkers, dass der Batterieoptimierungs-Hinweis beim Start
 * einer Aufzeichnung bereits gezeigt wurde (Boolean). Der Hinweis erscheint
 * hoechstens einmal automatisch — danach nur noch auf Wunsch unter Mehr.
 */
internal const val PREF_BATTERY_NOTICE_SHOWN = "trailscape.batteryNoticeShown"

/**
 * Schluessel des Kompass-Verhaltens der Navi-Kamera (Boolean, Default AN =
 * Fahrtrichtung oben). Umgeschaltet wird direkt am Kompass-Knopf auf der
 * Karte (`ui/map/MapScreen.kt`); die Wahl gilt fuer jede kuenftige
 * Navigation, bis sie wieder umgelegt wird. Ausserhalb der Navigation bleibt
 * die Karte unabhaengig davon bei Nord oben.
 */
internal const val PREF_NAV_COURSE_UP = "trailscape.nav.courseUp"

/** Ob die Auto-Pause eingeschaltet ist (Default AN). */
internal fun autoPauseAktiviert(context: Context): Boolean =
    trailscapePrefs(context).getBoolean(PREF_AUTO_PAUSE, true)

/** Schreibt den Auto-Pause-Schalter. */
internal fun setzeAutoPauseAktiviert(context: Context, aktiviert: Boolean) {
    trailscapePrefs(context).edit().putBoolean(PREF_AUTO_PAUSE, aktiviert).apply()
}

/**
 * Ob der Hauptschalter „Sprachansagen" an ist (Default AUS, siehe
 * [PREF_VOICE]). Zentraler Guard: `voice/VoiceAnnouncer.sagAn` prueft ihn bei
 * jeder Ansage selbst.
 */
internal fun sprachansagenAktiviert(context: Context): Boolean =
    trailscapePrefs(context).getBoolean(PREF_VOICE, false)

/** Schreibt den Hauptschalter „Sprachansagen". */
internal fun setzeSprachansagenAktiviert(context: Context, aktiviert: Boolean) {
    trailscapePrefs(context).edit().putBoolean(PREF_VOICE, aktiviert).apply()
}

/**
 * Ob Abbiegehinweise angesagt werden sollen (Default AN). Wirkt nur, wenn
 * auch [sprachansagenAktiviert] an ist — den Hauptschalter prueft der
 * `VoiceAnnouncer` selbst.
 */
internal fun abbiegehinweiseAktiviert(context: Context): Boolean =
    trailscapePrefs(context).getBoolean(PREF_VOICE_TURNS, true)

/** Schreibt den Unterschalter „Abbiegehinweise". */
internal fun setzeAbbiegehinweiseAktiviert(context: Context, aktiviert: Boolean) {
    trailscapePrefs(context).edit().putBoolean(PREF_VOICE_TURNS, aktiviert).apply()
}

/**
 * Ob Kilometer-Meilensteine angesagt werden sollen (Default AN). Wirkt nur,
 * wenn auch [sprachansagenAktiviert] an ist.
 */
internal fun kilometerAnsagenAktiviert(context: Context): Boolean =
    trailscapePrefs(context).getBoolean(PREF_VOICE_MILESTONES, true)

/** Schreibt den Unterschalter „Kilometer-Ansagen". */
internal fun setzeKilometerAnsagenAktiviert(context: Context, aktiviert: Boolean) {
    trailscapePrefs(context).edit().putBoolean(PREF_VOICE_MILESTONES, aktiviert).apply()
}

/**
 * Ob beim Verlassen der Route vibriert werden soll (Default AN, unabhaengig
 * vom Hauptschalter — siehe [PREF_OFFROUTE_VIBRATION]).
 */
internal fun offRouteVibrationAktiviert(context: Context): Boolean =
    trailscapePrefs(context).getBoolean(PREF_OFFROUTE_VIBRATION, true)

/** Schreibt den Schalter „Vibration abseits der Route". */
internal fun setzeOffRouteVibrationAktiviert(context: Context, aktiviert: Boolean) {
    trailscapePrefs(context).edit().putBoolean(PREF_OFFROUTE_VIBRATION, aktiviert).apply()
}

/** Ob die Navi-Kamera in Fahrtrichtung dreht (Default AN, siehe [PREF_NAV_COURSE_UP]). */
internal fun navCourseUpAktiviert(context: Context): Boolean =
    trailscapePrefs(context).getBoolean(PREF_NAV_COURSE_UP, true)

/** Schreibt das Kompass-Verhalten der Navi-Kamera. */
internal fun setzeNavCourseUpAktiviert(context: Context, aktiviert: Boolean) {
    trailscapePrefs(context).edit().putBoolean(PREF_NAV_COURSE_UP, aktiviert).apply()
}

/** Ob der einmalige Batterie-Hinweis beim Aufzeichnungsstart schon lief. */
internal fun batterieHinweisGezeigt(context: Context): Boolean =
    trailscapePrefs(context).getBoolean(PREF_BATTERY_NOTICE_SHOWN, false)

/** Merkt den Batterie-Hinweis als gezeigt (egal, wie er beantwortet wurde). */
internal fun merkeBatterieHinweisGezeigt(context: Context) {
    trailscapePrefs(context).edit().putBoolean(PREF_BATTERY_NOTICE_SHOWN, true).apply()
}

/**
 * Ob Trailscape bereits von der Batterieoptimierung ausgenommen ist. `false`
 * auch, wenn sich das nicht feststellen laesst — dann bleibt hoechstens ein
 * ueberfluessiger Hinweis, kein stiller Datenverlust.
 */
internal fun vonBatterieoptimierungAusgenommen(context: Context): Boolean = try {
    context.getSystemService(PowerManager::class.java)
        ?.isIgnoringBatteryOptimizations(context.packageName) == true
} catch (e: Exception) {
    false
}

/**
 * Systemdialog „Soll Trailscape die Batterieoptimierung ignorieren duerfen?".
 * Braucht `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` im Manifest; das
 * `BatteryLife`-Lint ist unterdrueckt, weil genau dieser Weg hier gewollt ist:
 * Ein GPS-Logger ist der dokumentierte Ausnahmefall, fuer den die Ausnahme
 * gedacht ist — ohne sie beenden manche Geraete die Aufzeichnung im
 * Hintergrund.
 */
@SuppressLint("BatteryLife")
internal fun batterieAusnahmeIntent(context: Context): Intent =
    Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
        .setData(Uri.parse("package:${context.packageName}"))
