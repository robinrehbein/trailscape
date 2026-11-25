package de.trailscape.core.indoor

/**
 * # Fahrgefühl: die zwei Stellschrauben nach den ersten Fahrten
 *
 * Widerstand, virtuelle Gänge und Belag sind bisher nur gerechnet
 * (docs/indoor.md, Abschnitt 5.3, und docs/indoor/core.md). Ob sich das auf dem
 * eigenen Trainer richtig anfühlt, zeigt erst die Fahrt. Zwei Regler gleichen
 * das aus, ohne die Physik der **virtuellen** Fahrt (Tempo, Zeit, Strecke) zu
 * verändern — beide wirken nur auf das, was der Trainer bekommt bzw. wie der
 * Belag den Rollwiderstand spreizt:
 *
 *  * [resistance] („Gesamtwiderstand“, leichter … schwerer) ist der Faktor
 *    `RiderSetup.trainerScale`: Er gleicht aus, dass die vom Trainer
 *    angenommene Masse unbekannt ist. 1,0 = heutiges Verhalten.
 *  * [surfaceStrength] („Stärke des Belags“) spreizt die Crr-Tabelle der
 *    Belagsklassen um den Mischwert ([effectiveCrr] mit Stärke): 0 = jeder
 *    Belag fühlt sich wie Mischwert an, 1 = heutige Tabelle, 2 = doppelt so
 *    deutlich. 1,0 = heutiges Verhalten.
 *
 * Grenzen: Der Widerstand darf um ±30 % abweichen — weiter weg liegt kein
 * plausibler Massenunterschied mehr, sondern ein Messfehler oder ein Trainer,
 * der ohnehin klemmt. Die Belagsstärke 0..2 — darüber würden Wiese und Sand
 * für Gravelreifen unplausibel zäh.
 */
data class RideFeel(
    val resistance: Double = DEFAULT_RESISTANCE,
    val surfaceStrength: Double = DEFAULT_SURFACE,
) {
    /** Auf die Grenzen geklemmt; nicht endliche Werte werden zur Vorgabe. */
    fun sanitized(): RideFeel = RideFeel(
        resistance = resistance.takeIf { it.isFinite() }?.coerceIn(RESISTANCE_MIN, RESISTANCE_MAX) ?: DEFAULT_RESISTANCE,
        surfaceStrength = surfaceStrength.takeIf { it.isFinite() }?.coerceIn(SURFACE_MIN, SURFACE_MAX) ?: DEFAULT_SURFACE,
    )

    /** Ob beides auf der Vorgabe steht (dann ändert sich gegenüber heute nichts). */
    val isDefault: Boolean
        get() = sanitized().let { it.resistance == DEFAULT_RESISTANCE && it.surfaceStrength == DEFAULT_SURFACE }

    /**
     * Legt das Fahrgefühl auf [rider]: `trainerScale` wird **ersetzt** durch
     * [resistance] (die Vorgabe des Setups ist 1,0), `surfaceStrength` durch
     * [surfaceStrength].
     */
    fun applyTo(rider: RiderSetup): RiderSetup {
        val f = sanitized()
        return rider.copy(trainerScale = f.resistance, surfaceStrength = f.surfaceStrength)
    }

    companion object {
        const val DEFAULT_RESISTANCE: Double = 1.0
        const val RESISTANCE_MIN: Double = 0.7
        const val RESISTANCE_MAX: Double = 1.3
        const val DEFAULT_SURFACE: Double = 1.0
        const val SURFACE_MIN: Double = 0.0
        const val SURFACE_MAX: Double = 2.0

        /** Das Fahrgefühl von heute. */
        val DEFAULT = RideFeel()

        /** Eine Stufe der Regler (Schrittweite, auch für die Anzeige in Prozent). */
        const val STEP: Double = 0.05

        /** Aus gespeicherten Texten (`null`/kaputt → Vorgabe, sonst geklemmt). */
        fun fromStored(resistance: String?, surfaceStrength: String?): RideFeel = RideFeel(
            resistance = resistance?.trim()?.toDoubleOrNull() ?: DEFAULT_RESISTANCE,
            surfaceStrength = surfaceStrength?.trim()?.toDoubleOrNull() ?: DEFAULT_SURFACE,
        ).sanitized()
    }
}
