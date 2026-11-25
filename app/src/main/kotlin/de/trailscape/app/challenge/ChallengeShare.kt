package de.trailscape.app.challenge

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import de.trailscape.app.R
import de.trailscape.app.ui.prepareShareDirectory
import de.trailscape.core.challenge.CHALLENGE_FILE_EXTENSION
import de.trailscape.core.challenge.CHALLENGE_MIME_TYPE
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Teilt die Datei einer Herausforderung ueber das System-Share-Sheet.
 *
 * Der Weg ist der der GPX-Datei (`ui/rides/RideShare.kt`): Die Datei liegt unter
 * `<cacheDir>/geteilte-touren`, dem einzigen Pfad, den `res/xml/file_paths.xml`
 * freigibt; alte Exporte raeumt [prepareShareDirectory] auf. Der Dateiname
 * enthaelt **keinen** Namen des Absenders und kein Datum, nur `herausforderung`
 * und die Kennung — er verraet nichts, was nicht in der Vorschau stand.
 */
internal suspend fun shareChallengeFile(context: Context, json: String, fileId: String) {
    val uri = withContext(Dispatchers.IO) {
        val dir = prepareShareDirectory(context.cacheDir)
        val file = File(dir, "herausforderung-$fileId.$CHALLENGE_FILE_EXTENSION")
        file.writeText(json, Charsets.UTF_8)
        FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
    }
    val send = Intent(Intent.ACTION_SEND).apply {
        type = CHALLENGE_MIME_TYPE
        putExtra(Intent.EXTRA_STREAM, uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    context.startActivity(
        Intent.createChooser(send, context.getString(R.string.spiel3_share_chooser_title)),
    )
}
