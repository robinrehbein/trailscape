package de.trailscape.app.ui.rides

import de.trailscape.core.StravaError
import de.trailscape.core.StravaUploadRecord
import de.trailscape.core.StravaUploadState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class StravaRideActionStateTest {

    private val now = 100_000_000L
    private val sixHours = 6 * 60 * 60 * 1000L

    private fun record(state: StravaUploadState, updatedAtMs: Long = now, activityId: Long? = null, error: StravaError? = null) =
        StravaUploadRecord(rideId = "r", state = state, activityId = activityId, error = error, detail = "d", updatedAtMs = updatedAtMs)

    @Test
    fun `ohne Vermerk noch nicht hochgeladen`() {
        assertEquals(StravaRideActionState.NotUploaded, stravaRideActionState(null, now))
    }

    @Test
    fun `wird hochgeladen, ab sechs Stunden veraltet`() {
        assertEquals(StravaRideActionState.Uploading, stravaRideActionState(record(StravaUploadState.UPLOADING), now))
        assertEquals(
            StravaRideActionState.Uploading,
            stravaRideActionState(record(StravaUploadState.UPLOADING, updatedAtMs = now - sixHours), now),
        )
        assertEquals(
            StravaRideActionState.Stale,
            stravaRideActionState(record(StravaUploadState.UPLOADING, updatedAtMs = now - sixHours - 1), now),
        )
    }

    @Test
    fun `hochgeladen mit Link auf die Aktivitaet`() {
        assertEquals(
            StravaRideActionState.Uploaded("https://www.strava.com/activities/42"),
            stravaRideActionState(record(StravaUploadState.DONE, activityId = 42), now),
        )
    }

    @Test
    fun `Duplikat mit und ohne Aktivitaet`() {
        assertEquals(
            StravaRideActionState.Duplicate("https://www.strava.com/activities/7"),
            stravaRideActionState(record(StravaUploadState.DUPLICATE, activityId = 7), now),
        )
        assertEquals(StravaRideActionState.Duplicate(null), stravaRideActionState(record(StravaUploadState.DUPLICATE), now))
    }

    @Test
    fun `fehlgeschlagen traegt die Fehlerklasse`() {
        assertEquals(
            StravaRideActionState.Failed(StravaError.RATE_LIMITED),
            stravaRideActionState(record(StravaUploadState.FAILED, error = StravaError.RATE_LIMITED), now),
        )
        assertEquals(StravaRideActionState.Failed(null), stravaRideActionState(record(StravaUploadState.FAILED), now))
    }

    @Test
    fun `verbunden ist die Zeile immer sichtbar`() {
        listOf(
            StravaRideActionState.NotUploaded,
            StravaRideActionState.Uploading,
            StravaRideActionState.Stale,
            StravaRideActionState.Duplicate(null),
            StravaRideActionState.Failed(StravaError.NETWORK),
        ).forEach { assertTrue(stravaRideActionVisible(it, connected = true), it.toString()) }
    }

    @Test
    fun `ohne Verbindung nur mit Link oder nach abgewiesenem Zugang`() {
        assertTrue(stravaRideActionVisible(StravaRideActionState.Uploaded("u"), connected = false))
        assertTrue(stravaRideActionVisible(StravaRideActionState.Duplicate("u"), connected = false))
        // Strava hat den Zugang beendet: Die Tour sagt, warum sie nicht angekommen ist.
        assertTrue(stravaRideActionVisible(StravaRideActionState.Failed(StravaError.UNAUTHORIZED), connected = false))

        assertFalse(stravaRideActionVisible(StravaRideActionState.NotUploaded, connected = false))
        assertFalse(stravaRideActionVisible(StravaRideActionState.Uploaded(null), connected = false))
        assertFalse(stravaRideActionVisible(StravaRideActionState.Duplicate(null), connected = false))
        assertFalse(stravaRideActionVisible(StravaRideActionState.Failed(StravaError.NETWORK), connected = false))
        assertFalse(stravaRideActionVisible(StravaRideActionState.Stale, connected = false))
    }
}
