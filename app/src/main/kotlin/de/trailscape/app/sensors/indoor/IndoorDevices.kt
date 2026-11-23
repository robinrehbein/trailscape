package de.trailscape.app.sensors.indoor

import android.content.Context
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import de.trailscape.core.indoor.BleLink
import de.trailscape.core.indoor.IndoorDeviceRole
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * # Einstiegspunkte des Slices indoor-ble
 *
 * EINSTIEGSDATEI (docs/indoor.md, Abschnitt 3). Besitzer: Slice indoor-ble.
 *
 * Die **Signaturen** hier sind Vertrag: Der Slice indoor-app ruft genau sie
 * auf. Der Architekt hat Platzhalter hinterlegt, die bauen und laufen, aber
 * kein Geraet kennen — indoor-ble ersetzt die Ruempfe (Umsetzung in eigenen
 * Dateien dieses Pakets) und laesst Namen, Parameter und Rueckgabetypen
 * unveraendert.
 */

/** Ein gekoppeltes („gemerktes") Indoor-Geraet. Koppeln heisst wie bei den Sensoren nur: Adresse merken. */
data class IndoorDeviceRef(val role: IndoorDeviceRole, val address: String, val name: String?)

/**
 * Die gemerkten Indoor-Geraete und der Weg zu ihnen.
 *
 * Anders als `BleSensors` haelt dieses Objekt **keine** Verbindung von sich
 * aus: Verbunden wird nur ueber ein [openLink]-Rohr, und nur solange, bis
 * dessen Besitzer es schliesst.
 */
object IndoorDevices {
    private val platzhalter = MutableStateFlow<Map<IndoorDeviceRole, IndoorDeviceRef>>(emptyMap())

    /** Die gemerkten Geraete je Rolle; aendert sich beim Koppeln und Vergessen. */
    fun remembered(context: Context): StateFlow<Map<IndoorDeviceRole, IndoorDeviceRef>> = platzhalter

    /**
     * Ein neues, noch nicht geoeffnetes Rohr zum gemerkten Geraet der Rolle
     * [role] — oder `null`, wenn keines gemerkt ist oder das Handy kein
     * Bluetooth LE hat. Fehlende Berechtigung und ausgeschaltetes Bluetooth
     * sind KEIN `null`: Das Rohr meldet sie als `BleLinkState`.
     */
    fun openLink(context: Context, role: IndoorDeviceRole): BleLink? = null

    /** Vergisst das Geraet der Rolle [role]. */
    fun forget(context: Context, role: IndoorDeviceRole) = Unit
}

/**
 * Koppeln, Status und Vergessen von Trainer und Schalttaster samt
 * Berechtigungsabfrage — ein in sich geschlossener Abschnitt, der sowohl unter
 * Mehr → Sensoren als auch im Indoor-Ablauf (Schritt „Koppeln") eingebettet
 * wird. Bringt seine Ueberschriften selbst mit.
 */
@Composable
fun IndoorDevicesSection(modifier: Modifier = Modifier) {
    Box(modifier)
}
