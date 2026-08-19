package de.trailscape.app.voice

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import de.trailscape.app.record.offRouteVibrationAktiviert

/**
 * Die im README zugesagte Vibrationswarnung beim Verlassen der Route.
 *
 * Bewusst getrennt von [VoiceAnnouncer]: Die Vibration haengt NICHT am
 * Hauptschalter „Sprachansagen" und funktioniert auch ohne (oder mit
 * kaputter) TTS-Engine — sie hat ihren eigenen Schalter „Vibration abseits
 * der Route" (Default AN, siehe `record/RecordingSettings.kt`). Wer mit dem
 * Telefon in der Lenkertasche faehrt, spuert die Warnung selbst dann, wenn
 * er alle Ansagen abbestellt hat.
 *
 * Braucht die `VIBRATE`-Berechtigung (Normal-Level, kein Laufzeitdialog) —
 * seit dieser Datei steht sie im Manifest.
 */

/**
 * Deutliches Vibrationsmuster fuer „abseits der Route": drei kraeftige
 * Impulse. Lang genug, um sich vom Klopfen des Untergrunds abzuheben, kurz
 * genug, um keine Daueralarm-Anmutung zu erzeugen. Erste Zahl = Wartezeit
 * vor dem ersten Impuls.
 */
private val OFF_ROUTE_MUSTER = longArrayOf(0, 400, 200, 400, 200, 600)

/**
 * Vibriert das Off-Route-Muster, sofern der Schalter „Vibration abseits der
 * Route" an ist. Folgenlos auf Geraeten ohne Vibrationsmotor.
 *
 * Ab API 31 ueber den [VibratorManager] (der alte Weg ist dort veraltet),
 * darunter ueber den klassischen [Vibrator] — minSdk 26 garantiert
 * [VibrationEffect].
 */
internal fun vibriereOffRoute(context: Context) {
    if (!offRouteVibrationAktiviert(context)) return
    val vibrator = try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            context.getSystemService(VibratorManager::class.java)?.defaultVibrator
        } else {
            context.getSystemService(Vibrator::class.java)
        }
    } catch (e: Exception) {
        null
    } ?: return

    try {
        if (!vibrator.hasVibrator()) return
        vibrator.vibrate(VibrationEffect.createWaveform(OFF_ROUTE_MUSTER, -1))
    } catch (e: Exception) {
        // Eine gescheiterte Vibration ist keine Stoerung der Navigation.
    }
}
