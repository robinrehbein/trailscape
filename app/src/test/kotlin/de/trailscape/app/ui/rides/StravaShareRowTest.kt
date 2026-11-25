package de.trailscape.app.ui.rides

import de.trailscape.core.StravaError
import kotlin.test.Test
import kotlin.test.assertEquals

/** Strava im Blatt „Teilen": fuer jede hochladbare Tour eine Zeile, auch ohne Verbindung. */
class StravaShareRowTest {

    private fun kind(
        state: StravaRideActionState = StravaRideActionState.NotUploaded,
        connected: Boolean = false,
        available: Boolean = true,
        uploadable: Boolean = true,
    ) = stravaShareRowKind(available = available, uploadable = uploadable, state = state, connected = connected)

    @Test
    fun `ohne Verbindung steht dort Mit Strava verbinden`() {
        assertEquals(StravaShareRowKind.CONNECT, kind())
        assertEquals(StravaShareRowKind.CONNECT, kind(state = StravaRideActionState.Failed(StravaError.NETWORK)))
    }

    @Test
    fun `verbunden zeigt die Zeile den Stand der Tour`() {
        assertEquals(StravaShareRowKind.STATUS, kind(connected = true))
        assertEquals(StravaShareRowKind.STATUS, kind(state = StravaRideActionState.Uploading, connected = true))
    }

    @Test
    fun `eine schon hochgeladene Tour zeigt ihren Link auch nach dem Trennen`() {
        assertEquals(StravaShareRowKind.STATUS, kind(state = StravaRideActionState.Uploaded("https://example.invalid/1")))
        assertEquals(StravaShareRowKind.STATUS, kind(state = StravaRideActionState.Duplicate("https://example.invalid/1")))
    }

    @Test
    fun `ein abgewiesener Zugang bleibt als Fehler sichtbar`() {
        assertEquals(StravaShareRowKind.STATUS, kind(state = StravaRideActionState.Failed(StravaError.UNAUTHORIZED)))
    }

    @Test
    fun `ohne Strava im Build oder ohne hochladbare Tour steht nichts`() {
        assertEquals(StravaShareRowKind.NONE, kind(available = false, connected = true))
        assertEquals(StravaShareRowKind.NONE, kind(uploadable = false, connected = true))
        assertEquals(StravaShareRowKind.NONE, kind(uploadable = false))
    }
}
