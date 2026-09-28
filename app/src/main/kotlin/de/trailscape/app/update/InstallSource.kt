package de.trailscape.app.update

import android.content.Context
import android.os.Build

/**
 * Der Paketname der App, die Trailscape installiert hat — etwa
 * [PLAY_STORE_INSTALLER] —, oder `null`, wenn Android es nicht verraet (adb,
 * manche Paketinstaller, Fehler).
 *
 * Bewusst ohne Logik: Was aus dem Wert folgt, entscheidet die reine, getestete
 * Funktion [isUpdateCheckAllowed] in `UpdateLogic.kt`. Hier steht nur die
 * duenne Android-Abfrage, die sich ohne Geraet ohnehin nicht sinnvoll testen
 * liesse.
 *
 * Ab API 30 zaehlt `installingPackageName` und nicht `initiatingPackageName`:
 * Google Play traegt sich immer als *installierendes* Paket ein (auch bei
 * internem Test und „internal app sharing"), angestossen haben kann die
 * Installation dagegen auch eine andere App. Darunter gibt es nur das
 * veraltete `getInstallerPackageName`, das denselben Wert liefert.
 *
 * Alles liegt in `runCatching`: Die Abfrage des eigenen Pakets wirft im
 * Normalbetrieb nicht, eine Ausnahme soll aber nie die App abstuerzen lassen
 * — `null` bedeutet dann „wie Sideload".
 */
fun installerPackageName(context: Context): String? = runCatching {
    val packageManager = context.packageManager
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        packageManager.getInstallSourceInfo(context.packageName).installingPackageName
    } else {
        @Suppress("DEPRECATION")
        packageManager.getInstallerPackageName(context.packageName)
    }
}.getOrNull()
