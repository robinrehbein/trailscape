package de.trailscape.app.room

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import de.trailscape.app.MainActivity
import de.trailscape.core.room.RoomInvite

/**
 * Durchsichtiges Trampolin für Einladungslinks zu einem Raum
 * (`trailscape://room?server=…&room=…#<Geheimnis>`, docs/raum-protokoll.md):
 * prüft den Link und reicht ihn an die [MainActivity] weiter, die den Tab
 * „Indoor", Segment „Gemeinsam", mit dem Dialog „Raum beitreten" öffnet.
 *
 * **Hier wird nichts verbunden.** Der Link wird nur vorgemerkt; eine
 * Verbindung zum darin genannten Server entsteht erst, wenn der Nutzer im
 * Dialog — mit sichtbarer Server-Adresse — „Beitreten" bestätigt. Ein Link,
 * der nicht genau passt ([RoomInvite.parse]), öffnet nur die App.
 *
 * Exportiert, weil Messenger und Browser sie aufrufen. Der Link wird nicht
 * protokolliert und nicht gespeichert (er enthält das Raum-Geheimnis).
 */
class RoomInviteActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val link = intent?.takeIf { it.action == Intent.ACTION_VIEW }?.dataString
        val valid = link?.takeIf { it.length <= MAX_LINK_CHARS && RoomInvite.parse(it) != null }
        val open = Intent(this, MainActivity::class.java)
            // SINGLE_TOP: Eine laufende MainActivity bekommt `onNewIntent` und wird nicht neu
            // erzeugt — sonst gingen eine laufende Fahrt und ein offener Raum verloren.
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        if (valid != null) open.putExtra(EXTRA_ROOM_INVITE, valid)
        startActivity(open)
        finish()
    }

    private companion object {
        const val MAX_LINK_CHARS = 2_000
    }
}

/** Extra an der [MainActivity]: ein geprüfter Einladungslink zu einem Raum. */
const val EXTRA_ROOM_INVITE = "de.trailscape.app.extra.ROOM_INVITE"
