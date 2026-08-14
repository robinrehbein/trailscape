package de.trailscape.app.ui

/**
 * # Fehlermeldungen: erst der deutsche Satz, dann die technische Ursache
 *
 * An sechs Stellen stand bisher `"…: ${e.message}"` oder gar
 * `e.message ?: "…"` — im zweiten Fall **gewinnt** die technische Meldung, und
 * der sorgfaeltig formulierte deutsche Satz kommt nie zum Vorschein. Was die
 * Nutzerin dann liest, ist `java.io.FileNotFoundException: /document/…: open
 * failed: ENOENT (No such file or directory)` oder ein englischer MapLibre-Text
 * — beides sagt ihr nicht, was sie tun soll.
 *
 * Diese Datei dreht die Reihenfolge um: Der deutsche Satz steht vorn und sagt,
 * was passiert ist und was zu tun ist; die technische Ursache folgt in
 * Klammern, damit ein Fehlerbericht sie noch enthaelt — aber nur, wenn es
 * ueberhaupt eine gibt.
 */

/**
 * [message], gefolgt von [cause] in Klammern — oder nur [message], wenn die
 * Ursache fehlt, leer ist oder wortgleich schon im Satz steht.
 *
 * Der letzte Fall kommt oefter vor, als man denkt: `:core` wirft seine
 * [de.trailscape.core.FormatException] bereits mit einem fertigen deutschen
 * Satz, und der wird an manchen Stellen zugleich als [message] durchgereicht.
 * „X. (X.)" waere die schlechtere Fassung von „X.".
 */
fun withCause(message: String, cause: String?): String {
    val detail = cause?.trim()?.takeIf { it.isNotEmpty() } ?: return message
    if (message.contains(detail)) return message
    return "$message ($detail)"
}

/** Wie [withCause], mit der Meldung einer Ausnahme als Ursache. */
fun withCause(message: String, error: Throwable?): String =
    withCause(message, error?.message)
