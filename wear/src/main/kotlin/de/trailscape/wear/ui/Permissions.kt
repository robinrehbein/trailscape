package de.trailscape.wear.ui

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat

/**
 * Die Rechte, die diese App zur Laufzeit braucht: Standort (GPS-Spur),
 * Herzfrequenz und — ab Android 13 — die Erlaubnis fuer die
 * Vordergrund-Benachrichtigung, ohne die kein Health-Services-Training
 * dauerhaft laufen darf.
 *
 * BODY_SENSORS wurde mit API 36 durch `health.READ_HEART_RATE` abgeloest;
 * beide gleichzeitig anzufragen ist auf keiner Version richtig, deshalb die
 * Fallunterscheidung. Es gibt KEINE Berechtigung namens ONGOING_ACTIVITY —
 * die laufende Anzeige haengt an der Notification und damit an
 * POST_NOTIFICATIONS.
 */
fun benoetigteRechte(): Array<String> = buildList {
    add(Manifest.permission.ACCESS_FINE_LOCATION)
    add(Manifest.permission.ACTIVITY_RECOGNITION)
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        add(Manifest.permission.POST_NOTIFICATIONS)
    }
    if (Build.VERSION.SDK_INT <= 35) {
        @Suppress("DEPRECATION")
        add(Manifest.permission.BODY_SENSORS)
    } else {
        add("android.permission.health.READ_HEART_RATE")
    }
}.toTypedArray()

fun alleErteilt(context: Context, rechte: Array<String>): Boolean =
    rechte.all {
        ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
    }
