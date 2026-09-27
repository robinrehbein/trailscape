package de.trailscape.app.ui.rides

import de.trailscape.core.StravaError
import de.trailscape.core.StravaUploadRecord
import de.trailscape.core.StravaUploadState
import kotlin.test.Test
import kotlin.test.assertEquals

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
    fun `fehlgeschlagen traegt Fehlerklasse und Detail`() {
        assertEquals(
            StravaRideActionState.Failed(StravaError.RATE_LIMITED, "d"),
            stravaRideActionState(record(StravaUploadState.FAILED, error = StravaError.RATE_LIMITED), now),
        )
        assertEquals(StravaRideActionState.Failed(null, "d"), stravaRideActionState(record(StravaUploadState.FAILED), now))
    }
}
