package de.trailscape.app.strava

import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import de.trailscape.core.StravaTokenStore
import de.trailscape.core.StravaTokens
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Legt den Strava-Zugang verschluesselt in den App-Prefs ab
 * (`trailscape_prefs.xml`, Schluessel [PREFS_KEY]).
 *
 * ## Warum nicht `androidx.security:security-crypto`
 * Die Bibliothek ist nicht im Projekt, und fuer einen einzigen Wert lohnt
 * keine neue Abhaengigkeit (die zudem inzwischen als veraltet gilt). Das hier
 * ist dasselbe Prinzip in kurz: AES-256-GCM mit einem Schluessel, der im
 * Android Keystore erzeugt wird und ihn nie verlaesst. Abgelegt wird
 * `base64(iv ‖ ciphertext)`.
 *
 * Ohne Nutzerauthentifizierung: Der Auto-Upload laeuft im Hintergrund, auch
 * bei gesperrtem Bildschirm.
 *
 * ## Wenn das Entschluesseln scheitert
 * Manche Hersteller verlieren Keystore-Schluessel nach einem Update oder
 * Wechsel der Bildschirmsperre. Dann gilt der Zugang als verloren: Der Wert
 * wird geloescht, die App zeigt „nicht verbunden" und bittet um neues
 * Verbinden — kein Absturz, keine Endlosschleife.
 *
 * Die Prefs-Datei ist vom Systembackup ausgenommen (siehe `PrefsStores.kt`);
 * der Zugang wandert also nicht auf ein neues Geraet, wo ihn der dortige
 * Keystore ohnehin nicht lesen koennte.
 */
class KeystoreStravaTokenStore(private val prefs: SharedPreferences) : StravaTokenStore {

    override fun read(): StravaTokens? {
        val stored = prefs.getString(PREFS_KEY, null) ?: return null
        return try {
            val raw = Base64.decode(stored, Base64.NO_WRAP)
            require(raw.size > IV_LENGTH) { "zu kurz" }
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(TAG_BITS, raw, 0, IV_LENGTH))
            val plain = cipher.doFinal(raw, IV_LENGTH, raw.size - IV_LENGTH)
            StravaTokens.fromJsonStringOrNull(String(plain, Charsets.UTF_8))
                ?: throw IllegalStateException("unlesbarer Inhalt")
        } catch (e: Exception) {
            clear()
            null
        }
    }

    override fun write(tokens: StravaTokens) {
        val encoded = try {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.ENCRYPT_MODE, key())
            val iv = cipher.iv
            check(iv.size == IV_LENGTH) { "unerwartete IV-Laenge ${iv.size}" }
            val cipherText = cipher.doFinal(tokens.toJson().toString().toByteArray(Charsets.UTF_8))
            Base64.encodeToString(iv + cipherText, Base64.NO_WRAP)
        } catch (e: Exception) {
            throw StravaKeystoreException(e)
        }
        // commit statt apply: Direkt danach kann der Prozess enden (Worker),
        // und ein frisch erneuerter Refresh-Token darf nicht verloren gehen —
        // der alte gilt bei Strava dann schon nicht mehr.
        prefs.edit().putString(PREFS_KEY, encoded).commit()
    }

    override fun clear() {
        prefs.edit().remove(PREFS_KEY).commit()
    }

    private fun key(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return generator.generateKey()
    }

    private companion object {
        const val PREFS_KEY = "trailscape.strava.tokens"
        const val KEY_ALIAS = "trailscape.strava"
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val IV_LENGTH = 12
        const val TAG_BITS = 128
    }
}

/** Der sichere Speicher des Geraets ist nicht nutzbar; der Zugang laesst sich nicht ablegen. */
class StravaKeystoreException(cause: Throwable) : Exception("Android Keystore nicht nutzbar", cause)
