package de.trailscape.app.sensors

import android.Manifest
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat

/**
 * Bluetooth-Berechtigungen und -Faehigkeiten — plattformduenn, damit die
 * Versionsweiche ([bluetoothPermissions], [locationServicesNeeded]) ohne
 * Geraet pruefbar ist (`BlePermissionsTest`).
 *
 * ## Die Weiche bei API 31
 * Ab Android 12 gibt es „Geraete in der Naehe": BLUETOOTH_SCAN (im Manifest
 * mit `neverForLocation`) und BLUETOOTH_CONNECT. Darunter sucht Android nur
 * mit ACCESS_FINE_LOCATION und eingeschaltetem Standortdienst — dieselbe
 * Freigabe, die die Aufzeichnung fuer GPS ohnehin braucht; BLUETOOTH und
 * BLUETOOTH_ADMIN sind dort Installationsrechte ohne Dialog.
 */

/** Die Laufzeitberechtigungen, die Suche und Verbindung auf [sdkInt] brauchen. */
fun bluetoothPermissions(sdkInt: Int): List<String> =
    if (sdkInt >= Build.VERSION_CODES.S) {
        listOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
    } else {
        listOf(Manifest.permission.ACCESS_FINE_LOCATION)
    }

/** Unter API 31 liefert die Suche ohne eingeschalteten Standortdienst nichts. */
fun locationServicesNeeded(sdkInt: Int): Boolean = sdkInt < Build.VERSION_CODES.S

/** Ob alle Berechtigungen aus [bluetoothPermissions] erteilt sind. */
fun hasBluetoothPermissions(context: Context): Boolean =
    bluetoothPermissions(Build.VERSION.SDK_INT).all {
        ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
    }

/** Ob das Geraet Bluetooth LE kann und einen Adapter hat. */
fun bluetoothLeSupported(context: Context): Boolean =
    context.packageManager.hasSystemFeature(PackageManager.FEATURE_BLUETOOTH_LE) &&
        bluetoothAdapter(context) != null

/**
 * Ob Bluetooth eingeschaltet ist. `isEnabled` braucht keine Berechtigung;
 * ein Hersteller-Stack, der trotzdem wirft, zaehlt als „aus".
 */
fun bluetoothEnabled(context: Context): Boolean =
    runCatching { bluetoothAdapter(context)?.isEnabled == true }.getOrDefault(false)

internal fun bluetoothAdapter(context: Context) =
    (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter
