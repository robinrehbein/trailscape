package de.trailscape.core

/**
 * Wie lange ein vom Telefon gemeldeter Puls auf der Uhr Vorrang vor der
 * eigenen Messung der Uhr hat.
 *
 * Warum ueberhaupt ein Vorrang: Das Telefon schickt im [AufzeichnungsZustand]
 * den wirksamen Live-Puls — bei frischem Brustgurt also den Gurtwert. Die Uhr
 * misst selbst etwa jede Sekunde, der Telefon-Zustand kommt aber nur alle
 * ~5 s. Wuerde jede eigene Probe einfach schreiben, zeigte die Uhr meist den
 * eigenen Wert und blinkte alle 5 s kurz den Gurtwert — die Anzeige sprang.
 *
 * Warum 12 s: gut zwei Zustandsintervalle. Geht ein einzelner Zustand verloren,
 * bleibt der Telefonwert stehen; bleibt das Telefon laenger stumm (Funkloch,
 * Telefon weg), uebernimmt wieder die eigene Messung der Uhr.
 */
const val TELEFON_PULS_VORRANG_MS: Long = 12_000L

/**
 * Darf die Uhr ihre eigene Pulsprobe anzeigen? Ja, wenn noch nie ein
 * Telefonwert kam ([telefonPulsSeitMs] `null`) oder der letzte aelter als
 * [TELEFON_PULS_VORRANG_MS] ist. Eine rueckwaerts laufende Uhr (Zeitsprung)
 * zaehlt als "frisch" und haelt den Telefonwert — lieber kurz den Gurtwert
 * stehen lassen als springen.
 */
fun eigenerPulsZaehlt(jetztMs: Long, telefonPulsSeitMs: Long?): Boolean =
    telefonPulsSeitMs == null || jetztMs - telefonPulsSeitMs > TELEFON_PULS_VORRANG_MS
