package de.trailscape.app.ui.more

import android.app.Activity
import android.bluetooth.BluetoothAdapter
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.BluetoothDisabled
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import de.trailscape.app.R
import de.trailscape.app.sensors.BleFund
import de.trailscape.app.sensors.BleKanaele
import de.trailscape.app.sensors.BleNutzer
import de.trailscape.app.sensors.BleScanner
import de.trailscape.app.sensors.BleSensors
import de.trailscape.app.sensors.bluetoothEnabled
import de.trailscape.app.sensors.bluetoothLeSupported
import de.trailscape.app.sensors.bluetoothPermissions
import de.trailscape.app.sensors.hasBluetoothPermissions
import de.trailscape.app.sensors.locationServicesNeeded
import de.trailscape.app.ui.components.NeutralButton
import de.trailscape.app.ui.components.NoticeBox
import de.trailscape.app.ui.components.OneUiDialog
import de.trailscape.app.ui.theme.LocalSignalColors
import de.trailscape.core.BleKanal
import de.trailscape.core.BleSensorTyp
import de.trailscape.core.BleVerbindung
import de.trailscape.core.GemerkterSensor
import de.trailscape.core.istFrisch
import de.trailscape.core.stillSeitS
import kotlinx.coroutines.delay

/**
 * # Mehr → Sensoren: Pulsgurt, Leistungsmesser, Trittfrequenzsensor
 *
 * Hier werden Bluetooth-Sensoren gesucht, gekoppelt (= gemerkt, siehe
 * [BleSensors]) und vergessen. Solange die Seite offen ist, verbindet die App
 * die gemerkten Sensoren und zeigt ihre Live-Werte — so laesst sich vor der
 * Fahrt pruefen, ob der Gurt sendet. Beim Verlassen trennt sie wieder.
 *
 * Gesucht wird nur auf Knopfdruck (Datenschutz, Akku), nie von selbst.
 *
 * Diese Funktion haelt nur Zustand und Plattformzugriffe; gezeichnet wird in
 * [SensorsPageContent], das ohne Bluetooth mit Beispieldaten fuer die
 * Screenshot-Tests laeuft.
 */
@Composable
fun ColumnScope.SensorsCardContent() {
    val context = LocalContext.current
    val scanner = remember { BleScanner(context.applicationContext) }
    val status by BleSensors.status.collectAsStateWithLifecycle()
    val kanaele by BleSensors.kanaele.collectAsStateWithLifecycle()
    val scanning by scanner.laeuft.collectAsStateWithLifecycle()
    val funde by scanner.treffer.collectAsStateWithLifecycle()
    val scanFehler by scanner.fehler.collectAsStateWithLifecycle()

    var gekoppelt by remember { mutableStateOf(BleSensors.gemerkte(context)) }
    var jetzt by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var bluetoothOn by remember { mutableStateOf(bluetoothEnabled(context)) }
    var permissionGranted by remember { mutableStateOf(hasBluetoothPermissions(context)) }
    var permanentlyDenied by remember { mutableStateOf(false) }
    val supported = remember { bluetoothLeSupported(context) }

    DisposableEffect(Unit) {
        BleSensors.acquire(context, BleNutzer.EINSTELLUNGEN)
        onDispose {
            scanner.stop()
            BleSensors.release(BleNutzer.EINSTELLUNGEN)
        }
    }
    // Sekundentakt: Stillzeiten und Neuversuch-Countdown laufen ohne neues
    // Paket weiter, und Bluetooth/Berechtigung koennen sich in den
    // Systemeinstellungen geaendert haben, ohne dass die Seite davon hoert.
    LaunchedEffect(Unit) {
        while (true) {
            jetzt = System.currentTimeMillis()
            bluetoothOn = bluetoothEnabled(context)
            val granted = hasBluetoothPermissions(context)
            if (granted && !permissionGranted) BleSensors.neuVerbinden()
            permissionGranted = granted
            delay(1_000)
        }
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { result ->
        val granted = result.values.all { it } && hasBluetoothPermissions(context)
        permissionGranted = granted
        if (granted) {
            permanentlyDenied = false
            BleSensors.neuVerbinden()
        } else {
            // Kein Dialog mehr moeglich („Nicht mehr fragen"): Dann fuehrt
            // nur der Weg ueber die App-Einstellungen weiter.
            val activity = context.findActivity()
            permanentlyDenied = activity != null && bluetoothPermissions(Build.VERSION.SDK_INT).none {
                activity.shouldShowRequestPermissionRationale(it)
            }
        }
    }
    val enableLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { bluetoothOn = bluetoothEnabled(context) }

    SensorsPageContent(
        state = SensorsUiState(
            bleSupported = supported,
            bluetoothOn = bluetoothOn,
            permissionGranted = permissionGranted,
            permissionPermanentlyDenied = permanentlyDenied,
            legacyPermission = locationServicesNeeded(Build.VERSION.SDK_INT),
            gekoppelt = gekoppelt,
            status = status,
            kanaele = kanaele,
            jetzt = jetzt,
            scanning = scanning,
            scanFehler = scanFehler,
            funde = funde,
        ),
        onScan = { scanner.start() },
        onStopScan = { scanner.stop() },
        onPair = { typ, fund ->
            BleSensors.merke(context, GemerkterSensor(typ, fund.adresse, fund.name))
            gekoppelt = BleSensors.gemerkte(context)
        },
        onForget = { typ ->
            BleSensors.vergiss(context, typ)
            gekoppelt = BleSensors.gemerkte(context)
        },
        onGrant = {
            if (permanentlyDenied) {
                runCatching {
                    context.startActivity(
                        Intent(
                            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                            Uri.fromParts("package", context.packageName, null),
                        ),
                    )
                }
            } else {
                permissionLauncher.launch(bluetoothPermissions(Build.VERSION.SDK_INT).toTypedArray())
            }
        },
        onEnableBluetooth = {
            // Ab Android 12 braucht der Systemdialog BLUETOOTH_CONNECT — der
            // Knopf erscheint deshalb erst nach der Berechtigung.
            runCatching { enableLauncher.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)) }
        },
    )
}

/** Alles, was [SensorsPageContent] zeigt — ohne Plattformbezug. */
internal data class SensorsUiState(
    val bleSupported: Boolean,
    val bluetoothOn: Boolean,
    val permissionGranted: Boolean,
    val permissionPermanentlyDenied: Boolean,
    /** Unter Android 12: Suche ueber die Standortfreigabe (siehe `BlePermissions.kt`). */
    val legacyPermission: Boolean,
    val gekoppelt: List<GemerkterSensor>,
    val status: Map<BleSensorTyp, BleVerbindung>,
    val kanaele: BleKanaele,
    val jetzt: Long,
    val scanning: Boolean,
    val scanFehler: Int?,
    val funde: List<BleFund>,
)

/**
 * Die Seite selbst, zustandslos: Einleitung, Hinweise (Berechtigung,
 * Bluetooth aus, kein BLE), je Messwert der gekoppelte Sensor samt
 * Statuszeile, darunter Suche und Funde. Ersetzen und Vergessen fragen per
 * [OneUiDialog] nach — ein versehentlich vergessener Gurt fehlt erst mitten
 * in der naechsten Fahrt auf.
 */
@Composable
internal fun ColumnScope.SensorsPageContent(
    state: SensorsUiState,
    onScan: () -> Unit,
    onStopScan: () -> Unit,
    onPair: (BleSensorTyp, BleFund) -> Unit,
    onForget: (BleSensorTyp) -> Unit,
    onGrant: () -> Unit,
    onEnableBluetooth: () -> Unit,
) {
    var ersetzen by remember { mutableStateOf<Pair<BleSensorTyp, BleFund>?>(null) }
    var vergessen by remember { mutableStateOf<GemerkterSensor?>(null) }
    val unbenannt = stringResource(R.string.ble_unnamed_device)
    val warnung = LocalSignalColors.current.warning

    SettingsHint(stringResource(R.string.ble_page_intro))
    Spacer(Modifier.height(12.dp))

    if (!state.bleSupported) {
        NoticeBox(
            icon = Icons.Filled.BluetoothDisabled,
            color = warnung,
            text = stringResource(R.string.ble_not_supported),
        )
        return
    }

    when {
        !state.permissionGranted -> NoticeBox(
            icon = Icons.Filled.Info,
            color = warnung,
            text = stringResource(
                if (state.legacyPermission) R.string.ble_permission_notice_legacy else R.string.ble_permission_notice,
            ),
            action = {
                TextButton(onClick = onGrant) {
                    Text(
                        stringResource(
                            if (state.permissionPermanentlyDenied) {
                                R.string.ble_permission_settings
                            } else {
                                R.string.ble_permission_grant
                            },
                        ),
                    )
                }
            },
        )
        !state.bluetoothOn -> NoticeBox(
            icon = Icons.Filled.BluetoothDisabled,
            color = warnung,
            text = stringResource(R.string.ble_bluetooth_off_notice),
            action = {
                TextButton(onClick = onEnableBluetooth) { Text(stringResource(R.string.ble_bluetooth_enable)) }
            },
        )
        else -> Unit
    }
    if (!state.permissionGranted || !state.bluetoothOn) Spacer(Modifier.height(12.dp))

    // --- Gekoppelte Sensoren, je Messwert einer
    val gekoppelt = state.gekoppelt.associateBy { it.typ }
    BleSensorTyp.entries.forEachIndexed { index, typ ->
        if (index > 0) {
            HorizontalDivider(
                modifier = Modifier.padding(vertical = 8.dp),
                color = MaterialTheme.colorScheme.outlineVariant,
            )
        }
        val sensor = gekoppelt[typ]
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(geraeteLabel(typ)),
                    style = MaterialTheme.typography.bodyLarge,
                )
                if (sensor == null) {
                    SettingsHint(stringResource(R.string.ble_none_paired))
                } else {
                    Text(
                        text = sensor.name ?: unbenannt,
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    val zeile = sensorStatusZeile(typ, state.status[typ], state.kanaele, state.jetzt)
                    SettingsHint(
                        text = if (zeile.arg != null) {
                            stringResource(zeile.res, zeile.arg)
                        } else {
                            stringResource(zeile.res)
                        },
                        color = if (zeile.warnung) warnung else Color.Unspecified,
                    )
                }
            }
            if (sensor != null) {
                Spacer(Modifier.width(12.dp))
                NeutralButton(onClick = { vergessen = sensor }) { Text(stringResource(R.string.ble_forget)) }
            }
        }
    }

    // --- Suche
    Spacer(Modifier.height(20.dp))
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(
            onClick = onScan,
            enabled = !state.scanning && state.permissionGranted && state.bluetoothOn,
            modifier = Modifier.heightIn(min = 48.dp),
        ) {
            Text(stringResource(if (state.scanning) R.string.ble_scan_running else R.string.ble_scan))
        }
        if (state.scanning) {
            TextButton(onClick = onStopScan) { Text(stringResource(R.string.ble_scan_stop)) }
        }
    }
    Spacer(Modifier.height(8.dp))
    SettingsHint(stringResource(R.string.ble_scan_hint))
    state.scanFehler?.let { code ->
        Spacer(Modifier.height(8.dp))
        SettingsHint(stringResource(R.string.ble_scan_failed, code), color = warnung)
    }

    if (state.scanning || state.funde.isNotEmpty()) {
        Spacer(Modifier.height(16.dp))
        // Buendig mit dem Kartentext — MoreGroupLabel rueckt fuer Listen ausserhalb der Karte ein.
        Text(
            text = stringResource(R.string.ble_found_heading),
            style = MaterialTheme.typography.titleMedium,
        )
        if (state.funde.isEmpty()) {
            SettingsHint(stringResource(R.string.ble_scan_empty))
        }
        for (typ in BleSensorTyp.entries) {
            val passend = state.funde.filter { typ in it.typen }
            if (passend.isEmpty()) continue
            Text(
                text = stringResource(geraeteLabel(typ)),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp, bottom = 4.dp),
            )
            for (fund in passend) {
                val schonGekoppelt = gekoppelt[typ]?.adresse == fund.adresse
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
                ) {
                    Text(
                        text = fund.name ?: unbenannt,
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    Spacer(Modifier.width(12.dp))
                    NeutralButton(
                        enabled = !schonGekoppelt,
                        onClick = {
                            val alt = gekoppelt[typ]
                            if (alt != null && alt.adresse != fund.adresse) {
                                ersetzen = typ to fund
                            } else {
                                onPair(typ, fund)
                            }
                        },
                    ) { Text(stringResource(R.string.ble_pair)) }
                }
            }
        }
    }

    Spacer(Modifier.height(16.dp))
    SettingsHint(stringResource(R.string.ble_watch_priority_note))

    ersetzen?.let { (typ, fund) ->
        val alt = gekoppelt[typ]?.name ?: unbenannt
        val neu = fund.name ?: unbenannt
        OneUiDialog(
            onDismissRequest = { ersetzen = null },
            icon = { Icon(Icons.Filled.Bluetooth, contentDescription = null) },
            title = { Text(stringResource(R.string.ble_replace_title, alt)) },
            text = { Text(stringResource(R.string.ble_replace_text, neu, alt)) },
            dismissButton = {
                TextButton(onClick = { ersetzen = null }) { Text(stringResource(R.string.ble_cancel)) }
            },
            confirmButton = {
                TextButton(onClick = {
                    ersetzen = null
                    onPair(typ, fund)
                }) { Text(stringResource(R.string.ble_replace_confirm)) }
            },
        )
    }
    vergessen?.let { sensor ->
        OneUiDialog(
            onDismissRequest = { vergessen = null },
            title = { Text(stringResource(R.string.ble_forget_title, sensor.name ?: unbenannt)) },
            text = { Text(stringResource(R.string.ble_forget_text)) },
            dismissButton = {
                TextButton(onClick = { vergessen = null }) { Text(stringResource(R.string.ble_cancel)) }
            },
            confirmButton = {
                TextButton(onClick = {
                    vergessen = null
                    onForget(sensor.typ)
                }) { Text(stringResource(R.string.ble_forget)) }
            },
        )
    }
}

@StringRes
internal fun geraeteLabel(typ: BleSensorTyp): Int = when (typ) {
    BleSensorTyp.PULS -> R.string.ble_device_heart_rate
    BleSensorTyp.LEISTUNG -> R.string.ble_device_power
    BleSensorTyp.TRITTFREQUENZ -> R.string.ble_device_cadence
}

/** Eine Statuszeile als Ressource plus optionale Zahl — reine Logik, getestet in `SensorsStatusTest`. */
internal data class StatusZeile(@StringRes val res: Int, val arg: Int? = null, val warnung: Boolean = false)

/**
 * Was unter einem gekoppelten Sensor steht: der Verbindungszustand und, wenn
 * verbunden, der aktuelle Wert — oder seit wann der Sensor schweigt.
 * Ohne Nutzer (keine Aufzeichnung, Seite gerade nicht offen) ist [verbindung]
 * `null` oder AUS: Dann verbindet er sich bei der naechsten Aufzeichnung.
 */
internal fun sensorStatusZeile(
    typ: BleSensorTyp,
    verbindung: BleVerbindung?,
    kanaele: BleKanaele,
    jetzt: Long,
): StatusZeile = when (verbindung) {
    null, BleVerbindung.AUS -> StatusZeile(R.string.ble_state_idle)
    BleVerbindung.VERBINDE -> StatusZeile(R.string.ble_state_connecting)
    BleVerbindung.WARTET -> {
        val ms = (kanaele.naechsterVersuchMs[typ] ?: jetzt) - jetzt
        StatusZeile(R.string.ble_state_retry, ((ms + 999) / 1000).toInt().coerceAtLeast(0))
    }
    BleVerbindung.KEINE_BERECHTIGUNG -> StatusZeile(R.string.ble_state_no_permission, warnung = true)
    BleVerbindung.BLUETOOTH_AUS -> StatusZeile(R.string.ble_state_bluetooth_off, warnung = true)
    BleVerbindung.VERBUNDEN -> {
        val kanal: BleKanal = when (typ) {
            BleSensorTyp.PULS -> kanaele.puls
            BleSensorTyp.LEISTUNG -> kanaele.leistung
            BleSensorTyp.TRITTFREQUENZ -> kanaele.trittfrequenz
        }
        val wert = kanal.wert
        when {
            wert != null && istFrisch(kanal.zeitMs, jetzt) -> StatusZeile(
                when (typ) {
                    BleSensorTyp.PULS -> R.string.ble_state_connected_hr
                    BleSensorTyp.LEISTUNG -> R.string.ble_state_connected_power
                    BleSensorTyp.TRITTFREQUENZ -> R.string.ble_state_connected_cadence
                },
                wert,
            )
            kanal.zeitMs != null -> StatusZeile(R.string.ble_state_silent, stillSeitS(kanal.zeitMs, jetzt))
            else -> StatusZeile(R.string.ble_state_connected_waiting)
        }
    }
}

/**
 * Statuszeile der Einstellungsliste: die gekoppelten Sensorarten
 * („Pulsgurt · Leistungsmesser") oder „Keine gekoppelt".
 */
internal fun sensorsListStatus(context: Context): String {
    val typen = BleSensors.gemerkte(context).map { it.typ }.toSet()
    if (typen.isEmpty()) return context.getString(R.string.ble_list_status_none)
    return BleSensorTyp.entries.filter { it in typen }.joinToString(" · ") { context.getString(geraeteLabel(it)) }
}

private fun Context.findActivity(): Activity? {
    var c: Context? = this
    while (c is ContextWrapper) {
        if (c is Activity) return c
        c = c.baseContext
    }
    return null
}
