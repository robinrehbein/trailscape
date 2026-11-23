package de.trailscape.app.sensors.indoor

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import de.trailscape.app.data.trailscapePrefs
import de.trailscape.app.sensors.BleSensors
import de.trailscape.app.sensors.bluetoothLeSupported
import de.trailscape.core.indoor.BleLink
import de.trailscape.core.indoor.IndoorDeviceRole
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * # Einstiegspunkte des Slices indoor-ble
 *
 * EINSTIEGSDATEI (docs/indoor.md, Abschnitt 3). Besitzer: Slice indoor-ble.
 *
 * Die **Signaturen** hier sind Vertrag: Der Slice indoor-app ruft genau sie
 * auf. Die Umsetzung steht in eigenen Dateien dieses Pakets:
 * [GattBleLink] (das Rohr, Logik in [GattLinkKern]), [IndoorScanner] (die
 * Suche), `IndoorGeraeteLogik.kt` (Speicherformat, Rollen aus der Werbung)
 * und `IndoorDevicesUi.kt` (der Abschnitt zum Koppeln).
 */

/** Ein gekoppeltes („gemerktes") Indoor-Geraet. Koppeln heisst wie bei den Sensoren nur: Adresse merken. */
data class IndoorDeviceRef(val role: IndoorDeviceRole, val address: String, val name: String?)

/**
 * Die gemerkten Indoor-Geraete und der Weg zu ihnen.
 *
 * Anders als `BleSensors` haelt dieses Objekt **keine** Verbindung von sich
 * aus: Verbunden wird nur ueber ein [openLink]-Rohr, und nur solange, bis
 * dessen Besitzer es schliesst.
 *
 * Gespeichert unter [PREF_INDOOR_DEVICES] in den App-Prefs (Format siehe
 * `kodiereIndoorGeraete`), getrennt von den Sensoren — ein Trainer, der auch
 * als Leistungsmesser gekoppelt ist, bleibt in beiden Rollen unabhaengig.
 */
object IndoorDevices {
    /** Prefs-Schluessel der gemerkten Indoor-Geraete (docs/indoor.md, Abschnitt 5.1). */
    const val PREF_INDOOR_DEVICES = "trailscape.indoor.devices"

    private val lock = Any()
    private var flow: MutableStateFlow<Map<IndoorDeviceRole, IndoorDeviceRef>>? = null

    private fun flow(context: Context): MutableStateFlow<Map<IndoorDeviceRole, IndoorDeviceRef>> =
        synchronized(lock) {
            flow ?: MutableStateFlow(lies(context)).also { flow = it }
        }

    private fun lies(context: Context): Map<IndoorDeviceRole, IndoorDeviceRef> =
        dekodiereIndoorGeraete(trailscapePrefs(context).getString(PREF_INDOOR_DEVICES, null))

    /** Die gemerkten Geraete je Rolle; aendert sich beim Koppeln und Vergessen. */
    fun remembered(context: Context): StateFlow<Map<IndoorDeviceRole, IndoorDeviceRef>> =
        flow(context).asStateFlow()

    /**
     * Ein neues, noch nicht geoeffnetes Rohr zum gemerkten Geraet der Rolle
     * [role] — oder `null`, wenn keines gemerkt ist oder das Handy kein
     * Bluetooth LE hat. Fehlende Berechtigung und ausgeschaltetes Bluetooth
     * sind KEIN `null`: Das Rohr meldet sie als `BleLinkState`.
     */
    fun openLink(context: Context, role: IndoorDeviceRole): BleLink? {
        val ref = flow(context).value[role] ?: return null
        val app = context.applicationContext
        if (!bluetoothLeSupported(app)) return null
        return GattBleLink(app, ref, BleSensors.bleThreadHandler())
    }

    /** Vergisst das Geraet der Rolle [role]. */
    fun forget(context: Context, role: IndoorDeviceRole) {
        aendere(context) { it - role }
    }

    /** Merkt [ref] und ersetzt dabei ein Geraet derselben Rolle. */
    internal fun remember(context: Context, ref: IndoorDeviceRef) {
        aendere(context) { it + (ref.role to ref) }
    }

    private fun aendere(
        context: Context,
        aenderung: (Map<IndoorDeviceRole, IndoorDeviceRef>) -> Map<IndoorDeviceRole, IndoorDeviceRef>,
    ) {
        val f = flow(context)
        synchronized(lock) {
            val neu = aenderung(f.value)
            trailscapePrefs(context).edit().putString(PREF_INDOOR_DEVICES, kodiereIndoorGeraete(neu)).apply()
            f.value = neu
        }
    }
}

/**
 * Koppeln, Status und Vergessen von Trainer und Schalttaster samt
 * Berechtigungsabfrage — ein in sich geschlossener Abschnitt, der sowohl unter
 * Mehr → Sensoren als auch im Indoor-Ablauf (Schritt „Koppeln") eingebettet
 * wird. Bringt seine Ueberschriften selbst mit.
 */
@Composable
fun IndoorDevicesSection(modifier: Modifier = Modifier) {
    IndoorDevicesSectionMitZustand(modifier = modifier, systemHinweise = true)
}
