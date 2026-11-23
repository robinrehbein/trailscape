package de.trailscape.app.indoor.geo

import android.app.Application
import android.graphics.Bitmap
import java.io.ByteArrayOutputStream
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Der Dekodierer mit echtem `BitmapFactory` (Robolectric, NATIVE graphics):
 * Pixelwerte bleiben unveraendert (Terrarium: Meter stecken in R, G, B),
 * Muellbytes ergeben `null`.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
// Schlichte Application: Die echte TrailscapeApplication startet WorkManager-Arbeit
// im Hintergrund, deren Fehler sonst spaeteren Compose-Tests angelastet wuerde.
@Config(sdk = [35], application = Application::class)
class BitmapRasterDecoderTest {

    private fun png(width: Int, height: Int, pixel: (Int, Int) -> Int): ByteArray {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        for (y in 0 until height) for (x in 0 until width) bitmap.setPixel(x, y, pixel(x, y))
        val out = ByteArrayOutputStream()
        check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, out))
        return out.toByteArray()
    }

    @Test
    fun `dekodiert ein PNG mit unveraenderten Kanalwerten`() {
        val bytes = png(4, 3) { x, y -> (0xFF shl 24) or ((x * 60) shl 16) or ((y * 80) shl 8) or (x * 20 + y) }

        val image = assertNotNull(BitmapRasterDecoder().decode(bytes))

        assertEquals(4, image.width)
        assertEquals(3, image.height)
        assertEquals(12, image.argb.size)
        // Zeilenweise von oben links.
        assertEquals((0xFF shl 24) or (0 shl 16) or (0 shl 8) or 0, image.argb[0])
        assertEquals((0xFF shl 24) or (180 shl 16) or (160 shl 8) or (60 + 2), image.argb[2 * 4 + 3])
    }

    @Test
    fun `Muellbytes ergeben null`() {
        val decoder = BitmapRasterDecoder()
        assertNull(decoder.decode(byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8)))
        assertNull(decoder.decode(ByteArray(0)))
        assertNull(decoder.decode("kein Bild".toByteArray()))
    }

    @Test
    fun `abgeschnittenes PNG wirft nicht`() {
        val bytes = png(16, 16) { x, y -> (0xFF shl 24) or (x shl 16) or (y shl 8) }
        val cut = bytes.copyOf(bytes.size / 2)
        // Ergebnis darf null oder ein Teilbild sein, aber nie eine Ausnahme.
        BitmapRasterDecoder().decode(cut)
    }
}
