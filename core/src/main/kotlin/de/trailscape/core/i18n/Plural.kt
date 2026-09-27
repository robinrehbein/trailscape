package de.trailscape.core.i18n

/**
 * Waehlt Singular oder Plural. Deutsch und Englisch kennen nur die
 * Kategorien „one" und „other" — die Zahl 1 ist Singular, alles andere
 * (auch 0) Plural. Nur fuer die CoreTexts-Implementierungen; die App nutzt
 * `<plurals>`-Ressourcen.
 */
internal fun plural(n: Int, one: String, other: String): String = if (n == 1) one else other
