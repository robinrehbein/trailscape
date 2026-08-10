package de.trailscape.app.update

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

/**
 * Die reine Rechenlogik des Update-Kanals — Tag-Parsing, Versionsvergleich,
 * Drosselung. Bewusst ohne Android-, Netz- und Speicherzugriff, damit sie in
 * `app/src/test` als gewoehnliche JVM-Tests laeuft (siehe
 * `UpdateCheckerTest`); alles mit Seiteneffekten steht in [UpdateChecker].
 *
 * ## Woher die Versionsnummer kommt
 * Die CI (`.github/workflows/build.yml`) veroeffentlicht bei jedem Push auf
 * `main` zwei Releases mit derselben APK:
 *  * `latest` — der stabile Download-Link, wird ueberschrieben,
 *  * `v2.0.<GITHUB_RUN_NUMBER>` — unveraenderlich, mit Changelog.
 *
 * Dieselbe Lauf-Nummer steckt im `versionCode` der APK (Offset
 * [VERSION_CODE_OFFSET], siehe `app/build.gradle.kts`) und im `versionName`
 * (`2.0.<Lauf>`). Der ganze Vergleich „gibt es etwas Neueres?" ist deshalb ein
 * Vergleich zweier `Int`s — kein SemVer-Parser noetig.
 */

/** Praefix der von der CI vergebenen unveraenderlichen Release-Tags. */
const val UPDATE_TAG_PREFIX: String = "v2.0."

/**
 * Offset zwischen `versionCode` und `GITHUB_RUN_NUMBER` (siehe
 * `app/build.gradle.kts`): `versionCode = Lauf + 2000`.
 */
const val VERSION_CODE_OFFSET: Int = 2000

/**
 * Hoechstens ein Netzzugriff pro 24 Stunden. Neue Versionen erscheinen
 * hoechstens ein paar Mal pro Woche — oefter zu fragen kostet nur Akku und
 * Datenvolumen und laesst die App bei GitHub in die Rate-Limit-Naehe geraten
 * (60 Anfragen/Stunde/IP ohne Token).
 */
const val UPDATE_CHECK_INTERVAL_MS: Long = 24L * 60L * 60L * 1000L

/**
 * Die abgefragte GitHub-API. Bewusst die Liste (nicht `/releases/latest`):
 * `/releases/latest` liefert das Release mit der „Latest"-Markierung, und die
 * traegt absichtlich der `latest`-Alias, dessen Tag-Name keine Versionsnummer
 * enthaelt. Die Liste enthaelt beides; die versionierten Tags stehen darin.
 */
const val RELEASES_API_URL: String =
    "https://api.github.com/repos/robinrehbein/trailscape/releases?per_page=10"

/**
 * Die Seite, auf der die APK liegt. Bewusst `/releases/latest` und kein
 * direkter Asset-Link: Ein Klick auf einen `.apk`-Link im Browser laedt die
 * Datei kommentarlos herunter, waehrend die Release-Seite Installationshinweis
 * und Changelog zeigt.
 */
const val RELEASE_PAGE_URL: String = "https://github.com/robinrehbein/trailscape/releases/latest"

/**
 * GitHub weist Anfragen ohne `User-Agent` mit HTTP 403 ab — der Header ist
 * hier also keine Hoeflichkeit, sondern Voraussetzung.
 */
const val UPDATE_USER_AGENT: String = "Trailscape-Android"

/**
 * Die Lauf-Nummer aus einem Release-Tag, oder `null`, wenn der Tag keiner
 * dieser App gehoert.
 *
 * Faellt `null` fuer den `latest`-Alias, fuer Tags anderer Serien
 * (`v2.1.4`, `v1.9.0`) und fuer alles mit Anhaengsel (`v2.0.12-rc1`) — nur
 * eine reine Zahl hinter [UPDATE_TAG_PREFIX] zaehlt.
 */
fun runNumberFromTag(tag: String): Int? {
    if (!tag.startsWith(UPDATE_TAG_PREFIX)) return null
    val suffix = tag.removePrefix(UPDATE_TAG_PREFIX)
    if (suffix.isEmpty() || !suffix.all { it.isDigit() }) return null
    return suffix.toIntOrNull()
}

/** Der Anzeigename einer Lauf-Nummer — identisch zum `versionName` der APK. */
fun versionNameForRun(run: Int): String = "2.0.$run"

/**
 * Die Lauf-Nummer der installierten App aus ihrem `versionCode`.
 *
 * `null` bei Codes unterhalb des Offsets: Die haette diese App nie vergeben
 * (die alte Flutter-Pipeline schon) — lieber gar nicht pruefen als gegen eine
 * unsinnige Zahl vergleichen und jedem Nutzer ein Update anbieten.
 */
fun runNumberFromVersionCode(versionCode: Long): Int? =
    (versionCode - VERSION_CODE_OFFSET).takeIf { it > 0 && it <= Int.MAX_VALUE }?.toInt()

/**
 * Die hoechste Lauf-Nummer aus der Antwort von [RELEASES_API_URL], oder
 * `null`, wenn die Antwort kein verwertbares Release enthaelt (unlesbares
 * JSON, leere Liste, nur der `latest`-Alias).
 *
 * Entwuerfe (`draft`) und Vorabversionen (`prerelease`) zaehlen nicht: Die CI
 * setzt beides nicht, ein von Hand angelegtes Test-Release soll aber niemanden
 * zu einem Update draengen.
 */
fun newestRunNumber(releasesJson: String): Int? = runCatching {
    val array = Json.parseToJsonElement(releasesJson) as? JsonArray ?: return null
    array.mapNotNull { element ->
        val release = element as? JsonObject ?: return@mapNotNull null
        if (release.flag("draft") || release.flag("prerelease")) return@mapNotNull null
        val tag = release["tag_name"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
        runNumberFromTag(tag)
    }.maxOrNull()
}.getOrNull()

private fun JsonObject.flag(name: String): Boolean =
    this[name]?.jsonPrimitive?.booleanOrNull == true

/**
 * Ob jetzt wieder ein Netzzugriff faellig ist.
 *
 * `null` (noch nie geprueft) heisst immer ja. Ein Zeitstempel aus der Zukunft
 * — moeglich, wenn die Uhr des Geraets zurueckgestellt wurde — heisst
 * ebenfalls ja: Sonst bliebe die Pruefung bis zum Erreichen dieser Zukunft
 * stumm, im Extremfall jahrelang.
 */
fun shouldCheckNow(
    lastCheckAtMs: Long?,
    nowMs: Long,
    intervalMs: Long = UPDATE_CHECK_INTERVAL_MS,
): Boolean {
    if (lastCheckAtMs == null) return true
    if (lastCheckAtMs > nowMs) return true
    return nowMs - lastCheckAtMs >= intervalMs
}

/**
 * Ob zu [versionName] noch eine Snackbar faellig ist.
 *
 * Vermerkt wird die zuletzt angekuendigte Version, nicht ein blosses „schon
 * gemeldet"-Ja/Nein: So nervt dieselbe Version nie zweimal, eine *neuere*
 * meldet sich aber wieder.
 */
fun shouldAnnounce(announcedVersionName: String?, versionName: String): Boolean =
    announcedVersionName != versionName
