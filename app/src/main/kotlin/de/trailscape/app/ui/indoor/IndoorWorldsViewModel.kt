package de.trailscape.app.ui.indoor

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import de.trailscape.app.data.AppServices
import de.trailscape.app.indoor.world.WorldLibrary
import java.io.File
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Weltverwaltung (docs/indoor.md, Abschnitt 10.4): Liste der Welten laden,
 * einzelne Welt loeschen, Zwischenspeicher leeren.
 *
 * Alle Dateizugriffe laufen auf [io]. Welten, deren Tour es nicht mehr gibt,
 * erscheinen als `orphaned` und werden nur zum Loeschen **angeboten** — dieses
 * ViewModel loescht nie von sich aus.
 *
 * @param knownRideIds die Kennungen aller vorhandenen Touren (blockierend,
 *   wird auf [io] gerufen).
 */
class IndoorWorldsViewModel(
    private val library: WorldLibrary,
    private val knownRideIds: () -> Set<String>,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) : ViewModel() {

    private val _state = MutableStateFlow(IndoorWorldsUi(worlds = emptyList(), totalBytes = 0L, cacheBytes = 0L, loading = true))
    val state: StateFlow<IndoorWorldsUi> = _state.asStateFlow()

    init {
        refresh()
    }

    /** Liest die Liste neu ein. */
    fun refresh() {
        viewModelScope.launch { reload() }
    }

    /** Loescht die Welt [worldId] und laedt die Liste neu. */
    fun delete(worldId: String) {
        viewModelScope.launch {
            _state.update { it.copy(loading = true) }
            withContext(io) { library.delete(worldId) }
            reload()
        }
    }

    /** Loescht alle verwaisten Welten (nur auf ausdruecklichen Wunsch der Nutzerin). */
    fun deleteOrphaned() {
        viewModelScope.launch {
            _state.update { it.copy(loading = true) }
            withContext(io) {
                // Ohne verlaessliche Tourenliste wird nichts geloescht.
                val known = runCatching { knownRideIds() }.getOrNull() ?: return@withContext
                library.listWorlds().filter { library.isOrphaned(it, known) }.forEach { library.delete(it.dirName) }
            }
            reload()
        }
    }

    /** Leert den Geodaten-Zwischenspeicher. */
    fun clearCache() {
        viewModelScope.launch {
            _state.update { it.copy(loading = true) }
            withContext(io) { library.clearCache() }
            reload()
        }
    }

    private suspend fun reload() {
        val ui = withContext(io) {
            val infos = library.listWorlds()
            // Scheitert die Tourenliste, gilt keine Welt als verwaist (nie zu Unrecht zum Loeschen anbieten).
            val known = runCatching { knownRideIds() }.getOrNull()
                ?: infos.mapNotNull { it.source?.rideId }.toSet()
            IndoorWorldsUi(
                worlds = infos.map { library.toEntryUi(it, known) },
                totalBytes = infos.sumOf { it.bytes },
                cacheBytes = library.cacheBytes(),
                loading = false,
            )
        }
        _state.value = ui
    }

    companion object {
        /**
         * Die Fabrik fuer `viewModel(factory = …)`: Welten unter
         * `<filesDir>/indoor/worlds`, Cache unter `<cacheDir>/indoor/geo`,
         * Touren aus `AppServices.rideStorage`.
         */
        fun factory(context: Context): ViewModelProvider.Factory {
            val app = context.applicationContext
            return object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T {
                    val library = WorldLibrary(
                        worldsDir = File(app.filesDir, "indoor/worlds"),
                        geoCacheDir = File(app.cacheDir, "indoor/geo"),
                    )
                    return IndoorWorldsViewModel(
                        library = library,
                        knownRideIds = { AppServices.rideStorage.listSummaries().summaries.map { it.id }.toSet() },
                    ) as T
                }
            }
        }
    }
}
