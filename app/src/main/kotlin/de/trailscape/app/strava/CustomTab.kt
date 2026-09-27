package de.trailscape.app.strava

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle

/**
 * Oeffnet [uri] als Custom Tab — ohne `androidx.browser`.
 *
 * Custom Tabs sind ein dokumentiertes Intent-Protokoll: ein gewoehnliches
 * `ACTION_VIEW` mit dem Extra `android.support.customtabs.extra.SESSION` (ein
 * Binder, hier `null` = ohne Sitzung) in einem Bundle. Ein Browser, der das
 * Protokoll kennt (Chrome, Samsung Internet, Firefox …), zeigt die Seite dann
 * als Blatt ueber der App statt als eigenen Browser-Task; einer, der es nicht
 * kennt, oeffnet sie einfach normal. Die Bibliothek wuerde dafuer eine
 * weitere Abhaengigkeit mitbringen, die hier nichts Zusaetzliches taete.
 *
 * Fuer die Strava-Anmeldung heisst das: Man meldet sich direkt bei Strava an,
 * Trailscape sieht die Seite nicht — und wer die Strava-App hat, landet per
 * App-Link gleich dort.
 *
 * @param toolbarColor ARGB-Farbe der Titelleiste, passend zum Theme.
 * @return `false`, wenn kein Browser installiert ist.
 */
fun openInCustomTab(context: Context, uri: Uri, toolbarColor: Int? = null): Boolean {
    val intent = Intent(Intent.ACTION_VIEW, uri).apply {
        putExtras(Bundle().apply { putBinder(EXTRA_SESSION, null) })
        toolbarColor?.let { putExtra(EXTRA_TOOLBAR_COLOR, it) }
        if (context !is Activity) addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    return try {
        context.startActivity(intent)
        true
    } catch (e: ActivityNotFoundException) {
        false
    }
}

private const val EXTRA_SESSION = "android.support.customtabs.extra.SESSION"
private const val EXTRA_TOOLBAR_COLOR = "android.support.customtabs.extra.TOOLBAR_COLOR"
