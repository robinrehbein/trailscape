package de.trailscape.core.i18n

/** Fehlermeldungen des Selfhost-Syncs (`SyncClient.kt`). */
interface SyncTexts {
    fun serverUnreachable(): String
    fun tokenRejected(): String
    fun syncFailed(httpStatus: Int): String
    fun uploadUnreachable(rideName: String): String
    fun uploadFailed(rideName: String, httpStatus: Int): String
    fun deleteUnreachable(): String
    fun deleteFailed(httpStatus: Int): String
    fun downloadUnreachable(rideName: String): String
    fun downloadFailed(rideName: String, httpStatus: Int): String
    fun downloadInvalid(rideName: String): String
    fun notConfigured(): String
}

internal object SyncTextsDe : SyncTexts {
    override fun serverUnreachable() = "Sync-Server nicht erreichbar."
    override fun tokenRejected() = "Token wird vom Server abgelehnt."
    override fun syncFailed(httpStatus: Int) = "Sync fehlgeschlagen (HTTP $httpStatus)."
    override fun uploadUnreachable(rideName: String) =
        "Hochladen der Tour \"$rideName\" fehlgeschlagen: Sync-Server nicht erreichbar."
    override fun uploadFailed(rideName: String, httpStatus: Int) =
        "Hochladen der Tour \"$rideName\" fehlgeschlagen (HTTP $httpStatus)."
    override fun deleteUnreachable() =
        "Löschen einer Tour auf dem Server fehlgeschlagen: Sync-Server nicht erreichbar."
    override fun deleteFailed(httpStatus: Int) =
        "Löschen einer Tour auf dem Server fehlgeschlagen (HTTP $httpStatus)."
    override fun downloadUnreachable(rideName: String) =
        "Herunterladen der Tour \"$rideName\" fehlgeschlagen: Sync-Server nicht erreichbar."
    override fun downloadFailed(rideName: String, httpStatus: Int) =
        "Herunterladen der Tour \"$rideName\" fehlgeschlagen (HTTP $httpStatus)."
    override fun downloadInvalid(rideName: String) =
        "Herunterladen der Tour \"$rideName\" fehlgeschlagen: ungültige Daten vom Server."
    override fun notConfigured() = "Sync ist nicht konfiguriert."
}

internal object SyncTextsEn : SyncTexts {
    override fun serverUnreachable() = "Can’t reach the sync server."
    override fun tokenRejected() = "The server rejects the token."
    override fun syncFailed(httpStatus: Int) = "Sync failed (HTTP $httpStatus)."
    override fun uploadUnreachable(rideName: String) =
        "Uploading the ride “$rideName” failed: can’t reach the sync server."
    override fun uploadFailed(rideName: String, httpStatus: Int) =
        "Uploading the ride “$rideName” failed (HTTP $httpStatus)."
    override fun deleteUnreachable() =
        "Deleting a ride on the server failed: can’t reach the sync server."
    override fun deleteFailed(httpStatus: Int) =
        "Deleting a ride on the server failed (HTTP $httpStatus)."
    override fun downloadUnreachable(rideName: String) =
        "Downloading the ride “$rideName” failed: can’t reach the sync server."
    override fun downloadFailed(rideName: String, httpStatus: Int) =
        "Downloading the ride “$rideName” failed (HTTP $httpStatus)."
    override fun downloadInvalid(rideName: String) =
        "Downloading the ride “$rideName” failed: invalid data from the server."
    override fun notConfigured() = "Sync isn’t set up."
}
