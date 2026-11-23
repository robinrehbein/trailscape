package de.trailscape.app.sensors.indoor

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
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.DirectionsBike
import androidx.compose.material.icons.filled.BluetoothDisabled
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.TouchApp
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleStartEffect
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import de.trailscape.app.R
import de.trailscape.app.sensors.bluetoothEnabled
import de.trailscape.app.sensors.bluetoothLeSupported
import de.trailscape.app.sensors.bluetoothPermissions
import de.trailscape.app.sensors.hasBluetoothPermissions
import de.trailscape.app.sensors.locationServicesNeeded
import de.trailscape.app.ui.components.NeutralButton
import de.trailscape.app.ui.components.NoticeBox
import de.trailscape.app.ui.components.OneUiDialog
import de.trailscape.app.ui.map.isLocationEnabled
import de.trailscape.app.ui.more.SettingsHint
import de.trailscape.app.ui.theme.LocalSignalColors
import de.trailscape.core.indoor.IndoorDeviceRole
import kotlinx.coroutines.delay

/**
 * Der Abschnitt „Indoor-Training" mit Zustand und Plattformzugriffen: Suche,
 * Berechtigung, Bluetooth-Schalter, Koppeln und Vergessen ueber
 * [IndoorDevices]. Gezeichnet wird in [IndoorDevicesContent].
 *
 * Er verbindet **nicht** zu den Geraeten (anders als die Sensoren auf
 * derselben Seite): Ein Trainer nimmt oft nur eine Verbindung an, und die
 * gehoert der Fahrt. Die Suche laeuft nur auf Knopfdruck und endet beim
 * Verlassen der Seite (an den Lebenszyklus gebunden, nicht an die
 * Komposition).
 *
 * @param systemHinweise Hinweise zu Berechtigung, Bluetooth aus und fehlendem
 *   Bluetooth LE zeigen. Unter Mehr → Sensoren `false`: Die Seite zeigt sie
 *   darueber schon einmal fuer alle Geraete.
 */
@Composable
internal fun IndoorDevicesSectionMitZustand(modifier: Modifier = Modifier, systemHinweise: Boolean) {
    val context = LocalContext.current
    val scanner = remember { IndoorScanner(context.applicationContext) }
    val gemerkt by remember { IndoorDevices.remembered(context) }.collectAsStateWithLifecycle()
    val scanning by scanner.laeuft.collectAsStateWithLifecycle()
    val funde by scanner.treffer.collectAsStateWithLifecycle()
    val scanFehler by scanner.fehler.collectAsStateWithLifecycle()

    var bluetoothOn by remember { mutableStateOf(bluetoothEnabled(context)) }
    var permissionGranted by remember { mutableStateOf(hasBluetoothPermissions(context)) }
    var permanentlyDenied by remember { mutableStateOf(false) }
    var standortAn by remember { mutableStateOf(isLocationEnabled(context)) }
    val supported = remember { bluetoothLeSupported(context) }

    LifecycleStartEffect(scanner) {
        onStopOrDispose { scanner.stop() }
    }
    // Bluetooth und Berechtigung koennen sich in den Systemeinstellungen
    // aendern, ohne dass die Seite davon hoert. Im Hintergrund ruht der Takt.
    val lifecycleOwner = LocalLifecycleOwner.current
    LaunchedEffect(lifecycleOwner) {
        lifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) {
                bluetoothOn = bluetoothEnabled(context)
                permissionGranted = hasBluetoothPermissions(context)
                standortAn = isLocationEnabled(context)
                delay(1_000)
            }
        }
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { result ->
        val granted = result.values.all { it } && hasBluetoothPermissions(context)
        permissionGranted = granted
        permanentlyDenied = if (granted) {
            false
        } else {
            // Kein Dialog mehr moeglich („Nicht mehr fragen"): Dann fuehrt
            // nur der Weg ueber die App-Einstellungen weiter.
            val activity = context.findActivity()
            activity != null && bluetoothPermissions(Build.VERSION.SDK_INT).none {
                activity.shouldShowRequestPermissionRationale(it)
            }
        }
    }
    val enableLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { bluetoothOn = bluetoothEnabled(context) }

    IndoorDevicesContent(
        state = IndoorDevicesUiState(
            bleSupported = supported,
            bluetoothOn = bluetoothOn,
            permissionGranted = permissionGranted,
            permissionPermanentlyDenied = permanentlyDenied,
            legacyPermission = locationServicesNeeded(Build.VERSION.SDK_INT),
            systemHinweise = systemHinweise,
            gemerkt = gemerkt,
            scanning = scanning,
            scanFehler = scanFehler,
            funde = funde,
            standortAus = locationServicesNeeded(Build.VERSION.SDK_INT) && !standortAn,
        ),
        modifier = modifier,
        onScan = { scanner.start() },
        onStopScan = { scanner.stop() },
        onPair = { rolle, fund -> IndoorDevices.remember(context, IndoorDeviceRef(rolle, fund.adresse, fund.name)) },
        onForget = { rolle -> IndoorDevices.forget(context, rolle) },
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

/** Alles, was [IndoorDevicesContent] zeigt — ohne Plattformbezug. */
internal data class IndoorDevicesUiState(
    val bleSupported: Boolean,
    val bluetoothOn: Boolean,
    val permissionGranted: Boolean,
    val permissionPermanentlyDenied: Boolean,
    /** Unter Android 12: Suche ueber die Standortfreigabe (siehe `BlePermissions.kt`). */
    val legacyPermission: Boolean,
    val systemHinweise: Boolean,
    val gemerkt: Map<IndoorDeviceRole, IndoorDeviceRef>,
    val scanning: Boolean,
    val scanFehler: Int?,
    val funde: List<IndoorFund>,
    /** Unter Android 12 ist der Standortdienst aus — dann findet die Suche nichts. */
    val standortAus: Boolean = false,
)

/**
 * Der Abschnitt selbst, zustandslos: Ueberschrift und Einleitung, Hinweise,
 * je Rolle das gemerkte Geraet, Suche und Funde. Ersetzen und Vergessen
 * fragen per [OneUiDialog] nach — ein versehentlich vergessener Trainer fehlt
 * erst beim naechsten Training auf.
 */
@Composable
internal fun IndoorDevicesContent(
    state: IndoorDevicesUiState,
    onScan: () -> Unit,
    onStopScan: () -> Unit,
    onPair: (IndoorDeviceRole, IndoorFund) -> Unit,
    onForget: (IndoorDeviceRole) -> Unit,
    onGrant: () -> Unit,
    onEnableBluetooth: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var ersetzen by remember { mutableStateOf<Pair<IndoorDeviceRole, IndoorFund>?>(null) }
    var vergessen by remember { mutableStateOf<IndoorDeviceRef?>(null) }
    val unbenannt = stringResource(R.string.indoor_ble_unnamed_label)
    val warnung = LocalSignalColors.current.warning

    Column(modifier = modifier.fillMaxWidth()) {
        Text(
            text = stringResource(R.string.indoor_ble_section_title),
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.semantics { heading() },
        )
        Spacer(Modifier.height(4.dp))
        SettingsHint(stringResource(R.string.indoor_ble_section_intro))
        Spacer(Modifier.height(12.dp))

        if (!state.bleSupported) {
            if (state.systemHinweise) {
                NoticeBox(
                    icon = Icons.Filled.BluetoothDisabled,
                    color = warnung,
                    text = stringResource(R.string.indoor_ble_not_supported_body),
                )
            }
            return@Column
        }

        val hinweis = state.systemHinweise && (!state.permissionGranted || !state.bluetoothOn)
        when {
            !state.systemHinweise -> Unit
            !state.permissionGranted -> NoticeBox(
                icon = Icons.Filled.Info,
                color = warnung,
                text = stringResource(
                    if (state.legacyPermission) {
                        R.string.indoor_ble_permission_legacy_body
                    } else {
                        R.string.indoor_ble_permission_body
                    },
                ),
                action = {
                    TextButton(onClick = onGrant) {
                        Text(
                            stringResource(
                                if (state.permissionPermanentlyDenied) {
                                    R.string.indoor_ble_permission_settings_action
                                } else {
                                    R.string.indoor_ble_permission_grant_action
                                },
                            ),
                        )
                    }
                },
            )
            !state.bluetoothOn -> NoticeBox(
                icon = Icons.Filled.BluetoothDisabled,
                color = warnung,
                text = stringResource(R.string.indoor_ble_bluetooth_off_body),
                action = {
                    TextButton(onClick = onEnableBluetooth) {
                        Text(stringResource(R.string.indoor_ble_bluetooth_enable_action))
                    }
                },
            )
        }
        if (hinweis) Spacer(Modifier.height(12.dp))

        // --- Gemerkte Geraete, je Rolle eines
        IndoorDeviceRole.entries.forEachIndexed { index, rolle ->
            if (index > 0) {
                HorizontalDivider(
                    modifier = Modifier.padding(vertical = 8.dp),
                    color = MaterialTheme.colorScheme.outlineVariant,
                )
            }
            val ref = state.gemerkt[rolle]
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(text = stringResource(rollenLabel(rolle)), style = MaterialTheme.typography.bodyLarge)
                    if (ref == null) {
                        SettingsHint(stringResource(leerHinweis(rolle)))
                    } else {
                        Text(
                            text = ref.name ?: unbenannt,
                            style = MaterialTheme.typography.bodyMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                if (ref != null) {
                    Spacer(Modifier.width(12.dp))
                    NeutralButton(onClick = { vergessen = ref }) {
                        Text(stringResource(R.string.indoor_ble_forget_action))
                    }
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
                Text(
                    stringResource(
                        if (state.scanning) R.string.indoor_ble_scan_running_label else R.string.indoor_ble_scan_action,
                    ),
                )
            }
            if (state.scanning) {
                TextButton(onClick = onStopScan) { Text(stringResource(R.string.indoor_ble_scan_stop_action)) }
            }
        }
        Spacer(Modifier.height(8.dp))
        when {
            // Unter Mehr → Sensoren steht der Hinweis oben auf der Seite, oft
            // ausserhalb des Bildes — der gesperrte Knopf sagt, wo es weitergeht.
            !state.systemHinweise && (!state.permissionGranted || !state.bluetoothOn) -> {
                SettingsHint(stringResource(R.string.indoor_ble_scan_blocked_hint), color = warnung)
                Spacer(Modifier.height(4.dp))
            }
            state.permissionGranted && state.standortAus -> {
                SettingsHint(stringResource(R.string.indoor_ble_location_off_hint), color = warnung)
                Spacer(Modifier.height(4.dp))
            }
        }
        SettingsHint(stringResource(R.string.indoor_ble_scan_hint))
        state.scanFehler?.let { code ->
            Spacer(Modifier.height(8.dp))
            SettingsHint(stringResource(R.string.indoor_ble_scan_error, code), color = warnung)
        }

        if (state.scanning || state.funde.isNotEmpty()) {
            Spacer(Modifier.height(16.dp))
            Text(
                text = stringResource(R.string.indoor_ble_found_title),
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.semantics { heading() },
            )
            if (state.funde.isEmpty()) SettingsHint(stringResource(R.string.indoor_ble_found_empty_hint))
            for (rolle in IndoorDeviceRole.entries) {
                val passend = state.funde.filter { rolle in it.rollen }
                if (passend.isEmpty()) continue
                Text(
                    text = stringResource(rollenLabel(rolle)),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp, bottom = 4.dp),
                )
                for (fund in passend) {
                    val schonGemerkt = state.gemerkt[rolle]?.address == fund.adresse
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
                    ) {
                        Icon(
                            when (rolle) {
                                IndoorDeviceRole.TRAINER -> Icons.AutoMirrored.Filled.DirectionsBike
                                IndoorDeviceRole.SHIFTER -> Icons.Filled.TouchApp
                            },
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.width(12.dp))
                        Text(
                            text = fund.name ?: unbenannt,
                            style = MaterialTheme.typography.bodyMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                        Spacer(Modifier.width(12.dp))
                        NeutralButton(
                            enabled = !schonGemerkt,
                            onClick = {
                                val alt = state.gemerkt[rolle]
                                if (alt != null && alt.address != fund.adresse) {
                                    ersetzen = rolle to fund
                                } else {
                                    onPair(rolle, fund)
                                }
                            },
                        ) {
                            // Deaktiviert allein saehe nach Stoerung aus; „Gekoppelt"
                            // sagt (auch TalkBack), warum hier nichts zu tun ist.
                            Text(
                                stringResource(
                                    if (schonGemerkt) R.string.indoor_ble_paired_label else R.string.indoor_ble_pair_action,
                                ),
                            )
                        }
                    }
                }
            }
        }
    }

    ersetzen?.let { (rolle, fund) ->
        val alt = state.gemerkt[rolle]?.name ?: unbenannt
        val neu = fund.name ?: unbenannt
        OneUiDialog(
            onDismissRequest = { ersetzen = null },
            title = { Text(stringResource(R.string.indoor_ble_replace_confirm_title, alt)) },
            text = { Text(stringResource(R.string.indoor_ble_replace_confirm_body, neu, alt)) },
            dismissButton = {
                TextButton(onClick = { ersetzen = null }) { Text(stringResource(R.string.indoor_ble_cancel_action)) }
            },
            confirmButton = {
                TextButton(onClick = {
                    ersetzen = null
                    onPair(rolle, fund)
                }) { Text(stringResource(R.string.indoor_ble_replace_action)) }
            },
        )
    }
    vergessen?.let { ref ->
        OneUiDialog(
            onDismissRequest = { vergessen = null },
            title = { Text(stringResource(R.string.indoor_ble_forget_confirm_title, ref.name ?: unbenannt)) },
            text = { Text(stringResource(R.string.indoor_ble_forget_confirm_body)) },
            dismissButton = {
                TextButton(onClick = { vergessen = null }) { Text(stringResource(R.string.indoor_ble_cancel_action)) }
            },
            confirmButton = {
                TextButton(onClick = {
                    vergessen = null
                    onForget(ref.role)
                }) { Text(stringResource(R.string.indoor_ble_forget_action)) }
            },
        )
    }
}

@StringRes
internal fun rollenLabel(rolle: IndoorDeviceRole): Int = when (rolle) {
    IndoorDeviceRole.TRAINER -> R.string.indoor_ble_role_trainer_label
    IndoorDeviceRole.SHIFTER -> R.string.indoor_ble_role_shifter_label
}

@StringRes
private fun leerHinweis(rolle: IndoorDeviceRole): Int = when (rolle) {
    IndoorDeviceRole.TRAINER -> R.string.indoor_ble_trainer_none_hint
    IndoorDeviceRole.SHIFTER -> R.string.indoor_ble_shifter_none_hint
}

private fun Context.findActivity(): Activity? {
    var c: Context? = this
    while (c is ContextWrapper) {
        if (c is Activity) return c
        c = c.baseContext
    }
    return null
}
