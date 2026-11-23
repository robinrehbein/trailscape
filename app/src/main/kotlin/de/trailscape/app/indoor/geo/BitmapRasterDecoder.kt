package de.trailscape.app.indoor.geo

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import de.trailscape.core.indoor.world.RasterDecoder
import de.trailscape.core.indoor.world.RasterImage

/**
 * Dekodiert PNG und JPEG mit `BitmapFactory` fuer den Weltbau
 * (docs/indoor.md, Abschnitt 10.5).
 *
 * Wichtig fuer die Hoehenkacheln (Terrarium: Meter stecken in R, G, B): ohne
 * Vormultiplikation (`inPremultiplied = false`), `ARGB_8888`, ohne Skalierung
 * und **ohne** Farbraumumwandlung — `inPreferredColorSpace` bleibt ungesetzt,
 * damit nichts die Kanalwerte veraendert.
 *
 * Liefert `null` bei unlesbaren Bytes und wirft nie, auch nicht bei zu wenig
 * Speicher ([OutOfMemoryError]).
 */
class BitmapRasterDecoder : RasterDecoder {
    override fun decode(bytes: ByteArray): RasterImage? {
        if (bytes.isEmpty()) return null
        var bitmap: Bitmap? = null
        return try {
            val options = BitmapFactory.Options().apply {
                inPremultiplied = false
                inPreferredConfig = Bitmap.Config.ARGB_8888
                inScaled = false
                inMutable = false
            }
            bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options) ?: return null
            val width = bitmap.width
            val height = bitmap.height
            if (width <= 0 || height <= 0) return null
            val argb = IntArray(width * height)
            bitmap.getPixels(argb, 0, width, 0, 0, width, height)
            RasterImage(width, height, argb)
        } catch (_: Exception) {
            null
        } catch (_: OutOfMemoryError) {
            null
        } finally {
            try {
                bitmap?.recycle()
            } catch (_: Exception) {
                // nichts zu tun
            }
        }
    }
}
