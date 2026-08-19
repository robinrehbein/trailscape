package de.trailscape.app.voice

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import de.trailscape.app.record.sprachansagenAktiviert
import java.util.Locale

/**
 * Lokale Sprachansagen ueber die Android-Sprachausgabe
 * ([android.speech.tts.TextToSpeech]) — der einzige Ort in der App, an dem
 * TTS auftaucht (dasselbe Muster wie `wear/WearBridge.kt` fuer die
 * Data-Layer-API: ein Prozess-Singleton, das jede Ausnahme schluckt, weil
 * weder Aufzeichnung noch Navigation an einer fehlenden Sprachausgabe
 * scheitern duerfen).
 *
 * **Alles lokal**: Gesprochen wird ueber die auf dem Geraet installierte
 * TTS-Engine. Trailscape schickt dafuer nichts ins Netz; ob die
 * Engine des Herstellers ihrerseits offline spricht, entscheidet deren
 * Konfiguration — die ueblichen Engines haben deutsche Offline-Stimmen.
 *
 * ## Verhalten
 *  * **Default AUS**: [sagAn] prueft den Hauptschalter „Sprachansagen"
 *    (Mehr → Aufzeichnung, siehe `record/RecordingSettings.kt`) bei jedem
 *    Aufruf selbst — die Aufrufer muessen nur noch ihre eigenen
 *    Unterschalter pruefen (Abbiegehinweise, Kilometer-Ansagen).
 *  * **Trotz Musik hoerbar, ohne sie zu stoppen**: Vor der ersten Aeusserung
 *    wird Audiofokus mit `AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK` angefragt —
 *    ein laufender Player wird leiser („Ducking"), nicht pausiert. Sobald
 *    die letzte Aeusserung der Warteschlange gesprochen ist, wird der Fokus
 *    wieder freigegeben und die Musik kehrt auf volle Lautstaerke zurueck.
 *  * **Warteschlange statt Abschneiden** ([TextToSpeech.QUEUE_ADD]): Faellt
 *    ein Abbiegehinweis mit einem Kilometer-Meilenstein zusammen, werden
 *    beide nacheinander gesprochen, keiner verschluckt.
 *  * **Graceful ohne Engine**: Meldet die Initialisierung einen Fehler oder
 *    fehlt die deutsche Sprache, verstummen alle Ansagen still ([gescheitert])
 *    — kein Absturz, keine Fehlermeldungsflut waehrend der Fahrt. Die
 *    Vibrationswarnung (siehe `Vibration.kt`) haengt bewusst NICHT an
 *    dieser Klasse und funktioniert dann weiterhin.
 *
 * ## Lebenszyklus
 * Die Engine wird beim ersten [sagAn] gebaut (lazy — wer Sprachansagen nie
 * einschaltet, bezahlt keinen TTS-Dienst) und lebt dann fuer den Prozess.
 * [shutdown] gibt sie explizit frei und setzt das Singleton so zurueck, dass
 * ein spaeteres [sagAn] sie neu baut. Die Init-Callbacks der Engine kommen
 * auf einem Binder-/Main-Thread, gesprochen wird u. a. vom
 * Aufzeichnungs-Thread des `RecordingService` — daher der [lock] um jeden
 * Zustandszugriff.
 */
object VoiceAnnouncer {

    private val lock = Any()

    private var tts: TextToSpeech? = null

    /** Engine initialisiert und Deutsch verfuegbar — es darf gesprochen werden. */
    private var bereit = false

    /**
     * Engine fehlt oder kann kein Deutsch — alle Ansagen verfallen still.
     * Wird nur von [shutdown] zurueckgesetzt (naechster Versuch z. B. nach
     * Installation einer Engine erst im naechsten Prozess bzw. nach Reset).
     */
    private var gescheitert = false

    /** Vor Abschluss der Engine-Initialisierung angefallene Texte. */
    private val wartend = ArrayDeque<String>()

    /** Zahl der an die Engine uebergebenen, noch nicht fertig gesprochenen Texte. */
    private var offen = 0

    private var laufendeId = 0L

    private var audioManager: AudioManager? = null
    private var focusRequest: AudioFocusRequest? = null

    /** Sprachcharakter der Ansagen — Navigationsdurchsage, gesprochen. */
    private val audioAttributes: AudioAttributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
        .build()

    private val progressListener = object : UtteranceProgressListener() {
        override fun onStart(utteranceId: String?) = Unit

        override fun onDone(utteranceId: String?) = aeusserungBeendet()

        @Deprecated("Deprecated in Java")
        override fun onError(utteranceId: String?) = aeusserungBeendet()

        override fun onError(utteranceId: String?, errorCode: Int) = aeusserungBeendet()
    }

    /**
     * Spricht [text], sofern der Hauptschalter „Sprachansagen" an ist und die
     * Engine verfuegbar ist. Ansonsten folgenlos — die Aufrufer brauchen
     * keinen eigenen Guard um den Hauptschalter.
     */
    fun sagAn(context: Context, text: String) {
        if (!sprachansagenAktiviert(context)) return
        sprichRoh(context, text)
    }

    /** Innerer Weg ohne den Hauptschalter-Guard von [sagAn]. */
    private fun sprichRoh(context: Context, text: String) {
        val appContext = context.applicationContext
        synchronized(lock) {
            if (gescheitert) return
            if (audioManager == null) {
                audioManager = try {
                    appContext.getSystemService(AudioManager::class.java)
                } catch (e: Exception) {
                    null
                }
            }

            val engine = tts
            if (engine == null) {
                // Erste Ansage ueberhaupt: Engine bauen, Text bis zum
                // Init-Callback zurueckstellen.
                wartend.addLast(text)
                tts = try {
                    TextToSpeech(appContext) { status -> onInit(status) }
                } catch (e: Exception) {
                    // Kein TTS-Dienst auf dem Geraet.
                    gescheitert = true
                    wartend.clear()
                    null
                }
                return
            }

            if (bereit) {
                sprichJetzt(engine, text)
            } else {
                wartend.addLast(text)
            }
        }
    }

    /** Init-Callback der Engine (fremder Thread, siehe Klassen-KDoc). */
    private fun onInit(status: Int) {
        synchronized(lock) {
            val engine = tts
            if (status != TextToSpeech.SUCCESS || engine == null) {
                verwerfeEngine()
                return
            }
            val sprache = try {
                engine.setLanguage(Locale.GERMAN)
            } catch (e: Exception) {
                TextToSpeech.LANG_NOT_SUPPORTED
            }
            if (sprache == TextToSpeech.LANG_MISSING_DATA || sprache == TextToSpeech.LANG_NOT_SUPPORTED) {
                verwerfeEngine()
                return
            }
            try {
                engine.setAudioAttributes(audioAttributes)
            } catch (e: Exception) {
                // Dann eben mit den Default-Attributen — hoerbar bleibt es.
            }
            engine.setOnUtteranceProgressListener(progressListener)
            bereit = true
            while (wartend.isNotEmpty()) {
                sprichJetzt(engine, wartend.removeFirst())
            }
        }
    }

    /** Gibt eine Engine auf, die nicht benutzbar ist. Nur unter [lock] rufen. */
    private fun verwerfeEngine() {
        gescheitert = true
        wartend.clear()
        try {
            tts?.shutdown()
        } catch (e: Exception) {
            // Nichts zu tun.
        }
        tts = null
        bereit = false
    }

    /** Uebergibt einen Text an die Engine. Nur unter [lock] rufen. */
    private fun sprichJetzt(engine: TextToSpeech, text: String) {
        if (offen == 0) holeAudioFokus()
        offen++
        val ergebnis = try {
            engine.speak(text, TextToSpeech.QUEUE_ADD, null, "trailscape-${laufendeId++}")
        } catch (e: Exception) {
            TextToSpeech.ERROR
        }
        if (ergebnis != TextToSpeech.SUCCESS) {
            offen--
            if (offen <= 0) gibAudioFokusFrei()
        }
    }

    /** Callback der Engine: eine Aeusserung ist fertig (oder gescheitert). */
    private fun aeusserungBeendet() {
        synchronized(lock) {
            offen = (offen - 1).coerceAtLeast(0)
            if (offen == 0) gibAudioFokusFrei()
        }
    }

    /**
     * Fordert voruebergehenden Audiofokus mit Ducking an — Musik wird leiser,
     * nicht gestoppt. Nur unter [lock] rufen.
     */
    private fun holeAudioFokus() {
        val manager = audioManager ?: return
        try {
            val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
                .setAudioAttributes(audioAttributes)
                .build()
            focusRequest = request
            manager.requestAudioFocus(request)
        } catch (e: Exception) {
            focusRequest = null
        }
    }

    /** Gibt den Audiofokus nach der letzten Aeusserung frei. Nur unter [lock] rufen. */
    private fun gibAudioFokusFrei() {
        val request = focusRequest ?: return
        focusRequest = null
        try {
            audioManager?.abandonAudioFocusRequest(request)
        } catch (e: Exception) {
            // Nichts zu tun.
        }
    }

    /**
     * Faehrt die Engine sauber herunter und gibt den Audiofokus frei. Danach
     * ist das Singleton wieder im Ausgangszustand — die naechste Ansage baut
     * die Engine neu auf (auch nach vorherigem [gescheitert]: vielleicht ist
     * inzwischen eine Engine installiert).
     */
    fun shutdown() {
        synchronized(lock) {
            try {
                tts?.shutdown()
            } catch (e: Exception) {
                // Nichts zu tun.
            }
            tts = null
            bereit = false
            gescheitert = false
            wartend.clear()
            offen = 0
            gibAudioFokusFrei()
        }
    }
}
