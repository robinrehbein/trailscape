package de.trailscape.app.ui.indoor

import de.trailscape.app.challenge.ChallengeRouteInfo
import de.trailscape.app.challenge.ChallengeStore
import de.trailscape.core.KeyValueStore
import de.trailscape.core.TrackPoint
import de.trailscape.core.challenge.Challenge
import de.trailscape.core.challenge.ChallengeCurve
import de.trailscape.core.challenge.ChallengeParse
import de.trailscape.core.challenge.ChallengeReject
import de.trailscape.core.challenge.ChallengeRoute
import de.trailscape.core.challenge.createChallenge
import de.trailscape.core.challenge.parseChallenge
import de.trailscape.core.indoor.IndoorRoute
import de.trailscape.core.indoor.challengeCurveOf
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * # Herausforderungen im Indoor-Ablauf
 *
 * Alles, was „Herausforderung teilen" an Zustand braucht — getrennt vom
 * `IndoorViewModel`, wie [IndoorCatalogController] für den Katalog: gespeicherte
 * Liste, Import mit Vorschau, Teilen der eigenen Fahrt. Der Ablauf selbst
 * (Strecke und Mitfahrer wählen) bleibt im ViewModel.
 *
 * Alle Methoden laufen auf dem Hauptthread; Dateizugriffe und die Rechnung der
 * Kurve gehen über [io]. Ohne [store] (Tests, alte Einbindung) ist die Liste
 * leer und der Import meldet „nicht speicherbar".
 *
 * @param routeInfo ordnet eine Strecke dem Katalog zu (installierte Version, Name).
 * @param avatarJson das Avatar-Objekt des Absenders (Garage), `null` solange es keins gibt.
 */
class IndoorChallengeController(
    private val store: ChallengeStore?,
    private val routeInfo: (ChallengeRoute) -> ChallengeRouteInfo,
    private val scope: CoroutineScope,
    private val io: CoroutineDispatcher,
    private val kv: KeyValueStore,
    private val nowMs: () -> Long,
    private val avatarJson: () -> String? = { null },
) {
    private val cache = HashMap<String, Challenge>()

    private val _items = MutableStateFlow<List<IndoorChallengeUi>>(emptyList())

    /** Die gespeicherten Herausforderungen, neueste zuerst. */
    val items: StateFlow<List<IndoorChallengeUi>> = _items.asStateFlow()

    private val _importState = MutableStateFlow<ChallengeImportUi?>(null)

    /** Der Import einer Datei in Arbeit (Vorschau oder Rückmeldung); `null` = keiner. */
    val importState: StateFlow<ChallengeImportUi?> = _importState.asStateFlow()

    private val _share = MutableStateFlow<ChallengeShareUi?>(null)

    /** Das Teilen der eigenen Fahrt in Arbeit; `null` = keins. */
    val share: StateFlow<ChallengeShareUi?> = _share.asStateFlow()

    private class ShareContext(val curve: ChallengeCurve, val route: ChallengeRoute, val dateIso: String?, val reply: Boolean)

    private var shareContext: ShareContext? = null

    /** Liest die Liste neu (nach Import, Löschen, Strecke geladen). */
    fun refresh() {
        val st = store ?: return
        scope.launch {
            val entries = withContext(io) { runCatching { st.list() }.getOrDefault(emptyList()) }
            cache.clear()
            entries.forEach { cache[it.challenge.id] = it.challenge }
            _items.value = entries.map { uiOf(it.challenge) }
        }
    }

    /** Die Herausforderung zur Kennung (aus der zuletzt gelesenen Liste). */
    fun get(id: String): Challenge? = cache[id]

    /** Wie [get], aber nur, wenn die Strecke in genau der Version bereitliegt (sonst passt die Kurve nicht). */
    fun rideable(id: String): Challenge? =
        cache[id]?.takeIf { routeInfo(it.route).availability == de.trailscape.app.challenge.ChallengeAvailability.READY }

    fun delete(id: String) {
        val st = store ?: return
        scope.launch {
            withContext(io) { runCatching { st.delete(id) } }
            refresh()
        }
    }

    private fun uiOf(c: Challenge): IndoorChallengeUi {
        val info = routeInfo(c.route)
        return IndoorChallengeUi(
            id = c.id,
            name = c.name,
            date = c.date,
            routeKey = c.route.key,
            catalogId = (c.route as? ChallengeRoute.Catalog)?.id,
            routeName = info.name,
            availability = info.availability,
            distanceKm = c.curve.totalDistM / 1000.0,
            durationS = Math.round(c.curve.totalS).toInt(),
        )
    }

    // -----------------------------------------------------------------------
    // Import
    // -----------------------------------------------------------------------

    /**
     * Prüft die gelesenen Bytes und zeigt die Vorschau. `null` = die Datei war
     * nicht lesbar oder größer als erlaubt.
     */
    fun importBytes(bytes: ByteArray?) {
        _importState.value = when (val parsed = bytes?.let { parseChallenge(it) }) {
            null -> ChallengeImportUi.Failed(ChallengeReject.TOO_LARGE, null)
            is ChallengeParse.Rejected -> ChallengeImportUi.Failed(parsed.reason, parsed.fileVersion)
            is ChallengeParse.Ok -> ChallengeImportUi.Preview(
                challengePreviewOf(parsed.challenge, routeInfo(parsed.challenge.route)),
                parsed.challenge,
            )
        }
    }

    /** „Speichern" in der Vorschau. */
    fun confirmImport() {
        val preview = _importState.value as? ChallengeImportUi.Preview ?: return
        val st = store
        scope.launch {
            val result = if (st == null) {
                ChallengeStore.SaveResult.FAILED
            } else {
                withContext(io) { runCatching { st.save(preview.challenge, nowMs()) }.getOrDefault(ChallengeStore.SaveResult.FAILED) }
            }
            _importState.value = ChallengeImportUi.Saved(preview.preview, result)
            refresh()
        }
    }

    fun dismissImport() {
        _importState.value = null
    }

    // -----------------------------------------------------------------------
    // Eigene Fahrt teilen
    // -----------------------------------------------------------------------

    /**
     * Öffnet die Vorschau zum Teilen. [ref] = `null`, wenn die Strecke nicht
     * teilbar ist (eigene Außenstrecke, Planung, erzeugte Strecke) — dann
     * erscheint die Erklärung statt einer Datei.
     *
     * @param dateIso Tag der Fahrt `JJJJ-MM-TT` oder `null`.
     */
    fun openShare(route: IndoorRoute?, points: List<TrackPoint>, ref: ChallengeRoute?, dateIso: String?, reply: Boolean) {
        val name = kv.getString(KEY_NAME).orEmpty()
        val includeDate = kv.getString(KEY_DATE) != "0"
        if (route == null || ref == null) {
            _share.value = ChallengeShareUi(name, includeDate, null, null, null, failed = true, reply = reply)
            return
        }
        _share.value = ChallengeShareUi(name, includeDate, null, null, null, reply = reply)
        scope.launch {
            val curve = withContext(io) { runCatching { challengeCurveOf(route, points) }.getOrNull() }
            if (curve == null) {
                _share.value = ChallengeShareUi(name, includeDate, null, null, null, failed = true, reply = reply)
            } else {
                shareContext = ShareContext(curve, ref, dateIso, reply)
                rebuildShare(name, includeDate)
            }
        }
    }

    fun setShareName(name: String) {
        val current = _share.value ?: return
        kv.setString(KEY_NAME, name)
        if (shareContext != null) rebuildShare(name, current.includeDate) else _share.value = current.copy(name = name)
    }

    fun setShareIncludeDate(include: Boolean) {
        val current = _share.value ?: return
        kv.setString(KEY_DATE, if (include) "1" else "0")
        if (shareContext != null) rebuildShare(current.name, include) else _share.value = current.copy(includeDate = include)
    }

    fun closeShare() {
        shareContext = null
        _share.value = null
    }

    private fun rebuildShare(name: String, includeDate: Boolean) {
        val ctx = shareContext ?: return
        val challenge = createChallenge(ctx.route, ctx.curve, name, if (includeDate) ctx.dateIso else null, avatarJson())
        _share.value = ChallengeShareUi(
            name = name,
            includeDate = includeDate,
            preview = challengePreviewOf(challenge, routeInfo(ctx.route)),
            json = challenge.toJson(),
            fileId = challenge.id,
            reply = ctx.reply,
        )
    }

    companion object {
        const val KEY_NAME = "indoor.challenge.name"
        const val KEY_DATE = "indoor.challenge.date"
    }
}
