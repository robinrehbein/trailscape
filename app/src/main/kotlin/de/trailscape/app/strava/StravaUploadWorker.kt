package de.trailscape.app.strava

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import de.trailscape.app.data.AppServices
import de.trailscape.core.StravaError
import de.trailscape.core.StravaException
import de.trailscape.core.StravaUploadOutcome
import de.trailscape.core.StravaUploadRecord
import de.trailscape.core.StravaUploadState
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Laedt eine Tour zu Strava hoch — im Hintergrund, sobald Netz da ist.
 *
 * Warum WorkManager auch fuer „Zu Strava hochladen" von Hand: Der Upload
 * samt Verarbeitung bei Strava dauert Sekunden bis eine Minute, und niemand
 * soll dafuer in der Tour warten muessen. Ohne Netz wartet die Arbeit, bis
 * es wieder da ist; das Ergebnis steht danach im Vermerk der Tour
 * ([StravaServices.records]). Bewusst **keine** Benachrichtigung — auch bei
 * einem Fehlschlag nicht: Der Stand steht ruhig in der Tour.
 *
 * ## Wiederholen
 *  * Netzfehler, Serverfehler, Drosselung (429) und „noch in Verarbeitung"
 *    → `retry` mit exponentiellem Backoff ab einer Minute, hoechstens
 *    [MAX_ATTEMPTS] Versuche; danach „fehlgeschlagen".
 *  * Ist die Upload-ID schon bekannt, fragt ein weiterer Versuch nur noch den
 *    Status ab, statt die Tour ein zweites Mal hochzuladen.
 *  * Kein Zugang mehr, Datei abgelehnt, Tour ungeeignet → sofort
 *    „fehlgeschlagen", ohne Wiederholung.
 */
class StravaUploadWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        // Der Worker kann in einem frischen Prozess starten; beide init sind
        // idempotent.
        AppServices.init(applicationContext)
        StravaServices.init(applicationContext)
        val rideId = inputData.getString(KEY_RIDE_ID) ?: return@withContext Result.failure()
        val client = StravaServices.client ?: return@withContext Result.failure()

        val ride = AppServices.rideStorage.loadRide(rideId)
        if (ride == null) {
            // Die Tour wurde inzwischen geloescht — nichts mehr zu tun.
            StravaServices.forget(rideId)
            return@withContext Result.success()
        }

        val previous = StravaServices.recordFor(rideId)
        val knownUploadId = previous?.uploadId?.takeIf { previous.state == StravaUploadState.UPLOADING }
        val outcome = try {
            if (knownUploadId != null) client.resumePolling(knownUploadId) else client.upload(ride)
        } catch (e: CancellationException) {
            throw e
        } catch (e: StravaException) {
            StravaUploadOutcome.Failed(e.error, e.message)
        } catch (e: Exception) {
            // Unerwartet (etwa der Keystore beim Speichern eines erneuerten
            // Zugangs): als unbekannter Fehler vermerken, nicht wiederholen.
            finish(rideId, StravaUploadState.FAILED, error = null, detail = e.javaClass.simpleName)
            return@withContext Result.failure()
        }

        when (outcome) {
            is StravaUploadOutcome.Uploaded -> {
                finish(rideId, StravaUploadState.DONE, activityId = outcome.activityId)
                Result.success()
            }
            is StravaUploadOutcome.Duplicate -> {
                finish(rideId, StravaUploadState.DUPLICATE, activityId = outcome.activityId)
                Result.success()
            }
            is StravaUploadOutcome.Processing -> retryOrFail(rideId, outcome.uploadId, error = null, detail = "processing")
            is StravaUploadOutcome.Failed -> when (outcome.error) {
                StravaError.NETWORK, StravaError.SERVER, StravaError.RATE_LIMITED ->
                    retryOrFail(rideId, knownUploadId, outcome.error, outcome.detail)
                StravaError.UNAUTHORIZED, StravaError.FILE, StravaError.NOT_UPLOADABLE -> {
                    finish(rideId, StravaUploadState.FAILED, error = outcome.error, detail = outcome.detail)
                    // Der Client hat einen ungueltigen Zugang womoeglich
                    // vergessen — die Oberflaeche soll das sehen.
                    if (outcome.error == StravaError.UNAUTHORIZED) StravaServices.refreshConnection()
                    Result.failure()
                }
            }
        }
    }

    private fun retryOrFail(rideId: String, uploadId: Long?, error: StravaError?, detail: String?): Result {
        return if (runAttemptCount + 1 < MAX_ATTEMPTS) {
            // Vermerk auffrischen: Er bleibt „wird hochgeladen" (nicht
            // veraltet) und behaelt die Upload-ID fuer den naechsten Versuch.
            StravaServices.record(
                StravaUploadRecord(
                    rideId = rideId,
                    state = StravaUploadState.UPLOADING,
                    uploadId = uploadId,
                    error = error,
                    detail = detail,
                    updatedAtMs = System.currentTimeMillis(),
                ),
            )
            Result.retry()
        } else {
            finish(rideId, StravaUploadState.FAILED, error = error, detail = detail)
            Result.failure()
        }
    }

    private fun finish(
        rideId: String,
        state: StravaUploadState,
        activityId: Long? = null,
        error: StravaError? = null,
        detail: String? = null,
    ) {
        StravaServices.record(
            StravaUploadRecord(
                rideId = rideId,
                state = state,
                activityId = activityId,
                error = error,
                detail = detail,
                updatedAtMs = System.currentTimeMillis(),
            ),
        )
    }

    companion object {
        const val KEY_RIDE_ID = "rideId"

        /** Versuche insgesamt, bevor ein voruebergehender Fehler als Fehlschlag gilt. */
        const val MAX_ATTEMPTS = 5
    }
}

/** Reiht [StravaUploadWorker] ein — je Tour hoechstens eine Arbeit. */
object StravaUploadScheduler {
    /** Gemeinsamer Tag aller Strava-Arbeiten; „Trennen" bricht sie darueber ab. */
    const val TAG = "trailscape.strava"

    fun uniqueName(rideId: String): String = "trailscape.strava.upload.$rideId"

    /**
     * @param replace `true` ersetzt eine schon eingereihte Arbeit derselben
     *   Tour (von Hand, „Erneut versuchen"), `false` laesst sie stehen
     *   (Auto-Upload, doppelte Meldung).
     */
    fun enqueue(context: Context, rideId: String, replace: Boolean) {
        val request = OneTimeWorkRequestBuilder<StravaUploadWorker>()
            .setInputData(workDataOf(StravaUploadWorker.KEY_RIDE_ID to rideId))
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 1, TimeUnit.MINUTES)
            .addTag(TAG)
            .build()
        WorkManager.getInstance(context.applicationContext).enqueueUniqueWork(
            uniqueName(rideId),
            if (replace) ExistingWorkPolicy.REPLACE else ExistingWorkPolicy.KEEP,
            request,
        )
    }
}
