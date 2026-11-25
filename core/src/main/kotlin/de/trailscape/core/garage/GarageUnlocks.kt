package de.trailscape.core.garage

/** Bedingung fuer ein Teil der Garage. Rein kosmetisch — nichts davon bringt einen Leistungsvorteil. */
sealed interface GarageRequirement {
    /** Von Anfang an frei. */
    data object Free : GarageRequirement

    /** Ab dieser Stufe (Punkte und Stufen, docs/konzept-spiel-community.md 2.3). */
    data class Level(val level: Int) : GarageRequirement

    /**
     * Mit diesem Abzeichen (ebd. 2.4). [badgeId] ist die Kennung des Abzeichens
     * aus den Erfolgen; [number] die Nummer in der Liste des Konzepts, damit die
     * Zuordnung beim Zusammenfuehren nachpruefbar bleibt.
     */
    data class Badge(val badgeId: String, val number: Int) : GarageRequirement
}

/**
 * Was die Erfolge (Etappe 1, Branch `claude/spiel-1-erfolge`) der Garage
 * mitteilen: erreichte Stufe und vorhandene Abzeichen. Die Garage liest nur;
 * sie speichert und berechnet nichts davon.
 */
interface GarageProgress {
    val level: Int

    fun hasBadge(badgeId: String): Boolean
}

/**
 * # Freischaltungen der Garage (Schnittstelle, noch ohne Wirkung)
 *
 * Welche Muster und Sonderfarben ab welcher Stufe bzw. mit welchem Abzeichen
 * gedacht sind. Solange die Erfolge nicht zusammengefuehrt sind, reicht die App
 * `progress = null` herein, und **alles ist frei** ([isUnlocked]).
 *
 * Grundsatz (Konzept 3.5): Alle Grundfarben, alle Hauttoene und ein Muster sind
 * immer frei — niemand soll sich „haesslich" fuehlen muessen, bis er genug
 * gefahren ist. Gesperrtes zeigt die Bedingung, nicht nur ein Schloss.
 */
object GarageUnlocks {
    /** Kennungen der Abzeichen; beim Zusammenfuehren mit den Erfolgen abgleichen (Nummern aus dem Konzept 2.4). */
    const val BADGE_EVEREST = "everest"
    const val BADGE_FASTER_UP = "faster_up"

    fun pattern(p: JerseyPattern): GarageRequirement = when (p) {
        JerseyPattern.SOLID, JerseyPattern.BAND -> GarageRequirement.Free
        JerseyPattern.SPLIT -> GarageRequirement.Level(2)
        JerseyPattern.STRIPES -> GarageRequirement.Level(5)
        JerseyPattern.FADE -> GarageRequirement.Badge(BADGE_FASTER_UP, 26)
    }

    fun color(c: GarageColor): GarageRequirement = when (c.id) {
        GaragePalettes.GOLD.id -> GarageRequirement.Badge(BADGE_EVEREST, 10)
        GaragePalettes.COPPER.id -> GarageRequirement.Level(10)
        else -> GarageRequirement.Free
    }

    /** `progress == null`: noch keine Erfolge in dieser Version — dann ist alles frei. */
    fun isUnlocked(req: GarageRequirement, progress: GarageProgress?): Boolean {
        if (progress == null) return true
        return when (req) {
            GarageRequirement.Free -> true
            is GarageRequirement.Level -> progress.level >= req.level
            is GarageRequirement.Badge -> progress.hasBadge(req.badgeId)
        }
    }

    fun isUnlocked(p: JerseyPattern, progress: GarageProgress?): Boolean = isUnlocked(pattern(p), progress)

    fun isUnlocked(c: GarageColor, progress: GarageProgress?): Boolean = isUnlocked(color(c), progress)
}
