package de.trailscape.core.indoor.world

import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.sin

/**
 * # Der Gebaeude-Atlas
 *
 * Eine einzige Textur (512 × 1024, Baseline-JPEG) fuer Waende **und** Daecher
 * aller Gebaeude einer Kachel — beide Materialien verweisen auf dieselben
 * Bytes, die GLB bettet sie nur einmal ein (2,8 MB Grafikspeicher je Kachel).
 *
 * Der Atlas besteht aus waagerechten **Baendern** ueber die volle Breite. Die
 * Textur wird wiederholt (REPEAT): Jedes Band kachelt nach links und rechts
 * nahtlos, eine Wand braucht je Geschoss also nur **ein** Viereck, gleich wie
 * lang sie ist. Nach oben und unten kachelt nichts — deshalb liegt zwischen
 * den Baendern ein Rand aus wiederholten Randzeilen (kein Ausbluten in den
 * Mipmaps), und die Baender beginnen auf Vielfachen von 16 Pixeln (kein
 * gemeinsamer JPEG-Block zweier Baender).
 *
 * Fensterbaender zeigen vier Fensterachsen ([BAY_M] breit) je Wiederholung und
 * genau ein Geschoss ([STOREY_M]). Der Hintergrund jedes Fensterbands ist je
 * Achse periodisch und deckungsgleich mit dem fensterlosen Band desselben
 * Baustoffs, damit Randstuecke und Giebel ohne sichtbare Naht anschliessen.
 *
 * Putz, Blech, Dachpfannen und Zubehoer sind hell und neutral gemalt; ihre
 * Farbe kommt aus den Vertexfarben des Gebaeudes. Klinker, Schiefer, Fachwerk
 * und Holz tragen ihre echte Farbe (weisse Fensterrahmen wuerden sonst
 * mitgefaerbt) und werden nur leicht abgetoent.
 */
internal object BuildingTextures {
    const val WIDTH = 512
    const val HEIGHT = 1024

    /** Breite einer Fensterachse in Metern (eine Viertel-Atlasbreite). */
    const val BAY_M = 2.75

    /** Hoehe eines Geschosses in den Fensterbaendern. */
    const val STOREY_M = 2.75

    /** Rand je Band oben und unten in Pixeln (wiederholte Randzeile). */
    const val GUTTER = 4

    // Fenster in Achskoordinaten (Meter): Mitte der Achse, halbe Breite, Bruestung, Sturz.
    const val WINDOW_HALF_M = 0.55
    const val WINDOW_SILL_M = 0.90
    const val WINDOW_HEAD_M = 2.20

    /** Schluessel im [TextureCache]. */
    const val KEY = "buildings-atlas-v1"

    /**
     * Ein Band des Atlas. [widthM] × [heightM] ist die Flaeche, die eine
     * Wiederholung in der Welt bedeckt; [stretch] = der Inhalt aendert sich
     * senkrecht nicht (oder nur als weiche Wolkung) und darf beliebig hoch
     * gezogen werden.
     */
    enum class Band(val top: Int, val pitch: Int, val widthM: Double, val heightM: Double, val stretch: Boolean = false) {
        PLASTER_WINDOWS(0, 128, 4 * BAY_M, STOREY_M),
        TIMBER_WINDOWS(128, 128, 4 * BAY_M, STOREY_M),
        BRICK_WINDOWS(256, 128, 4 * BAY_M, STOREY_M),
        SLATE_WINDOWS(384, 128, 4 * BAY_M, STOREY_M),

        /** Tueren und Tore, siehe [Decal]. */
        SPECIAL(512, 112, 4 * BAY_M, STOREY_M),
        BRICK(624, 64, 4 * BAY_M, STOREY_M / 2),
        SLATE(688, 64, 4 * BAY_M, STOREY_M / 2),
        PLASTER(752, 32, 4 * BAY_M, STOREY_M, stretch = true),
        WOOD(784, 32, 4 * BAY_M, STOREY_M, stretch = true),
        SOCLE(816, 32, 4 * BAY_M, 1.0, stretch = true),

        /** Dachpfannen: 56 Pfannen breit, 12 Reihen hoch. */
        PANTILE(848, 128, 56 * 0.30, 12 * 0.33),
        BITUMEN(976, 16, 8.0, 8.0, stretch = true),

        /** Trapezblech: Rippen laufen senkrecht (an der Wand) bzw. in Fallrichtung (auf dem Dach). */
        SHEET(992, 16, 40 * 0.25, 1.0, stretch = true),

        /** Einfarbig hell: Ortgang, Traufbrett, Untersicht, Rinne, Attika (Farbe aus den Vertexfarben). */
        TRIM(1008, 16, 1.0, 1.0, stretch = true),
        ;

        /** Zeilen mit Inhalt. */
        val contentPx: Int get() = pitch - 2 * GUTTER

        /** Texturkoordinate der Oberkante (halbes Pixel eingerueckt). */
        val vTop: Double get() = (top + GUTTER + 0.5) / HEIGHT

        /** Texturkoordinate der Unterkante (halbes Pixel eingerueckt). */
        val vBottom: Double get() = (top + pitch - GUTTER - 0.5) / HEIGHT

        /** `v` zur Hoehe [zM] ueber der Unterkante des Bands. */
        fun v(zM: Double): Double = vBottom + (vTop - vBottom) * (zM / heightM)

        /** `u` zur Lage [xM] entlang des Bands (beliebig weit — die Textur wiederholt sich). */
        fun u(xM: Double): Double = xM / widthM
    }

    /**
     * Ein Aufsetzteil aus dem Band [Band.SPECIAL]: Ausschnitt in Pixeln und
     * Groesse in der Welt. Wird als eigenes Viereck knapp vor die Wand gesetzt.
     */
    enum class Decal(val x0: Int, val x1: Int, val widthM: Double, val heightM: Double) {
        /** Haustuer mit Glasausschnitt, hell — Farbe ueber Vertexfarbe. */
        DOOR_PANEL(0, 64, 1.10, 2.15),

        /** Brettertuer, Holz. */
        DOOR_WOOD(64, 128, 1.05, 2.05),

        /** Sektionaltor einer Garage, hell — Farbe ueber Vertexfarbe. */
        GARAGE_DOOR(128, 256, 2.45, 2.12),

        /** Zweifluegeliges Holztor (Scheune, Halle). */
        BARN_GATE(256, 400, 3.40, 3.20),

        /** Stahltuer/Nebentuer, hell. */
        DOOR_PLAIN(400, 448, 0.95, 2.02),
        ;

        val u0: Double get() = (x0 + 0.75) / WIDTH
        val u1: Double get() = (x1 - 0.75) / WIDTH
        val vTop: Double get() = Band.SPECIAL.vTop
        val vBottom: Double get() = Band.SPECIAL.vBottom
    }

    /** Der Atlas einer Welt (einmal gerechnet, dann aus dem Cache). */
    fun atlas(textures: TextureCache): EncodedTexture = textures.get(KEY, jpegQuality = 90) { render() }

    /**
     * Mittlere Farbe eines Bands, linear `[0, 1]` — fuer die untexturierten
     * Gebaeude der Stufe 1, damit sie in der Ferne denselben Ton haben wie die
     * texturierten der Stufe 0. Aus dem Atlas gemessen; der Test haelt die
     * Werte an der Textur fest.
     */
    fun meanLinear(band: Band): FloatArray = when (band) {
        Band.PLASTER_WINDOWS -> floatArrayOf(0.692f, 0.680f, 0.644f)
        Band.TIMBER_WINDOWS -> floatArrayOf(0.479f, 0.459f, 0.408f)
        Band.BRICK_WINDOWS -> floatArrayOf(0.261f, 0.149f, 0.124f)
        Band.SLATE_WINDOWS -> floatArrayOf(0.132f, 0.145f, 0.141f)
        Band.SPECIAL -> floatArrayOf(0.5f, 0.5f, 0.5f)
        Band.BRICK -> floatArrayOf(0.253f, 0.106f, 0.076f)
        Band.SLATE -> floatArrayOf(0.038f, 0.043f, 0.053f)
        Band.PLASTER -> floatArrayOf(0.797f, 0.781f, 0.736f)
        Band.WOOD -> floatArrayOf(0.160f, 0.112f, 0.072f)
        Band.SOCLE -> floatArrayOf(0.313f, 0.295f, 0.266f)
        Band.PANTILE -> floatArrayOf(0.487f, 0.466f, 0.446f)
        Band.BITUMEN -> floatArrayOf(0.563f, 0.556f, 0.544f)
        Band.SHEET -> floatArrayOf(0.565f, 0.572f, 0.578f)
        Band.TRIM -> floatArrayOf(0.888f, 0.888f, 0.871f)
    }

    // -----------------------------------------------------------------------
    // Malen
    // -----------------------------------------------------------------------

    /** Rechnet den Atlas. Deterministisch. */
    fun render(): RgbaImage {
        val img = RgbaImage(WIDTH, HEIGHT)
        for (band in Band.entries) {
            val rows = band.contentPx
            val y0 = band.top + GUTTER
            for (r in 0 until rows) {
                for (x in 0 until WIDTH) {
                    var sr = 0
                    var sg = 0
                    var sb = 0
                    // 2 × 2 Abtastungen je Pixel gegen Treppen an Rahmen und Fugen.
                    for (k in 0 until 4) {
                        val px = x + 0.25 + 0.5 * (k and 1)
                        val py = r + 0.25 + 0.5 * (k shr 1)
                        val c = sample(band, px / WIDTH * band.widthM, band.heightM * (1.0 - py / rows), px)
                        sr += Argb.red(c)
                        sg += Argb.green(c)
                        sb += Argb.blue(c)
                    }
                    img[x, y0 + r] = Argb.rgb((sr + 2) / 4, (sg + 2) / 4, (sb + 2) / 4)
                }
            }
            for (g in 0 until GUTTER) {
                for (x in 0 until WIDTH) {
                    img[x, band.top + g] = img[x, y0]
                    img[x, band.top + band.pitch - 1 - g] = img[x, y0 + rows - 1]
                }
            }
        }
        return img
    }

    /** Farbe des Bands an der Stelle [xm] (Meter entlang) / [zm] (Meter ueber der Unterkante); [px] = Pixelspalte. */
    private fun sample(band: Band, xm: Double, zm: Double, px: Double): Int = when (band) {
        Band.PLASTER_WINDOWS -> plasterBay(xm, zm)
        Band.TIMBER_WINDOWS -> timberBay(xm, zm)
        Band.BRICK_WINDOWS -> brickBay(xm, zm)
        Band.SLATE_WINDOWS -> slateBay(xm, zm)
        Band.SPECIAL -> special(px, zm / band.heightM)
        Band.BRICK -> brick(xm, zm)
        Band.SLATE -> slate(xm, zm)
        Band.PLASTER -> plaster(xm, zm, 0.0)
        Band.WOOD -> boards(xm, 0.1375, 0x51d)
        Band.SOCLE -> socle(xm, zm)
        Band.PANTILE -> pantile(xm, zm)
        Band.BITUMEN -> bitumen(xm, zm)
        Band.SHEET -> sheet(xm)
        Band.TRIM -> Argb.rgb(242, 242, 240)
    }

    private fun rgb(r: Double, g: Double, b: Double): Int =
        Argb.rgb(r.toInt().coerceIn(0, 255), g.toInt().coerceIn(0, 255), b.toInt().coerceIn(0, 255))

    private fun shade(c: Int, f: Double): Int = rgb(Argb.red(c) * f, Argb.green(c) * f, Argb.blue(c) * f)

    /** Wolkung, waagerecht periodisch mit der Bandbreite von 11 m. */
    private fun cloud(xm: Double, zm: Double, cellsPerBay: Int, seed: Int): Double =
        Noise.fbm(xm / BAY_M * cellsPerBay, zm / BAY_M * cellsPerBay, seed, 3, 4 * cellsPerBay)

    // --- Putz ---------------------------------------------------------------

    /** Glatter Putz, gebrochen weiss; [dirt] 0..1 dunkelt zusaetzlich ab (Schmutzfahnen). */
    private fun plaster(xm: Double, zm: Double, dirt: Double): Int {
        val n = cloud(xm, zm, 3, 0x9a1) * 0.022 + cloud(xm, zm, 24, 0x9a2) * 0.012
        val f = (1.0 + n) * (1.0 - 0.10 * dirt)
        return rgb(231 * f, 229 * f, 223 * f)
    }

    private fun plasterBay(xm: Double, zm: Double): Int {
        val bay = floor(xm / BAY_M).toInt() and 3
        val bx = xm - floor(xm / BAY_M) * BAY_M
        val dx = abs(bx - BAY_M / 2)
        window(dx, bx, zm, bay, WINDOW_HALF_M, frame = 0xF4F3F0, sillStone = true)?.let { return it }
        // Schmutzfahnen unter den Enden der Fensterbank.
        val streak = smoothstep(0.10, 0.02, abs(dx - WINDOW_HALF_M)) * smoothstep(WINDOW_SILL_M - 0.75, WINDOW_SILL_M - 0.06, zm) *
            (if (zm < WINDOW_SILL_M - 0.05) 1.0 else 0.0)
        return shade(plaster(xm, zm, 0.45 * streak), revealShade(dx, zm, WINDOW_HALF_M))
    }

    /** Leichte Verschattung der Laibung rund um die Oeffnung (auf der Wand, direkt am Rand). */
    private fun revealShade(dx: Double, zm: Double, half: Double): Double {
        val out = max(dx - half, max(WINDOW_SILL_M - zm, zm - WINDOW_HEAD_M))
        return if (out > 0 && out < 0.04) 0.90 else 1.0
    }

    /**
     * Ein Fenster: Rahmen, Mittelpfosten, Glas mit Himmelsverlauf, je nach
     * Achse Gardinen, halb herabgelassener Rollladen oder Kaempfer, dazu die
     * Fensterbank. `null` ausserhalb.
     */
    private fun window(dx: Double, bx: Double, zm: Double, bay: Int, half: Double, frame: Int, sillStone: Boolean): Int? {
        if (sillStone && zm <= WINDOW_SILL_M && zm > WINDOW_SILL_M - 0.055 && dx <= half + 0.07) {
            val edge = if (zm < WINDOW_SILL_M - 0.035) 0.80 else 1.0
            return rgb(196 * edge, 194 * edge, 188 * edge)
        }
        if (dx > half || zm < WINDOW_SILL_M || zm > WINDOW_HEAD_M) return null
        val fw = 0.062
        val left = bx < BAY_M / 2
        val inFrame = dx > half - fw || zm < WINDOW_SILL_M + fw || zm > WINDOW_HEAD_M - fw || dx < 0.032 ||
            (bay == 3 && abs(zm - (WINDOW_HEAD_M - 0.42)) < 0.026)
        if (inFrame) {
            // Der Sturz verschattet den oberen Rahmen, die Laibung eine Seite.
            val ao = (if (zm > WINDOW_HEAD_M - 0.03) 0.80 else 1.0) * (if (left && dx > half - 0.02) 0.90 else 1.0)
            return shade(0xFF000000.toInt() or frame, ao)
        }
        val t = ((zm - WINDOW_SILL_M) / (WINDOW_HEAD_M - WINDOW_SILL_M)).coerceIn(0.0, 1.0)
        // Dunkles Glas, oben etwas heller und blauer (Himmel), unten dunkel (Raumtiefe).
        var r = 30 + 34 * t * t
        var g = 37 + 42 * t * t
        var b = 46 + 54 * t * t
        // Rahmenschatten auf dem Glas oben.
        if (zm > WINDOW_HEAD_M - fw - 0.05) {
            r *= 0.7; g *= 0.7; b *= 0.7
        }
        when (bay) {
            1 -> if (dx > half * 0.50) {
                // Gardinen links und rechts, in Falten.
                val fold = 0.82 + 0.18 * sin(bx * 95.0)
                val k = 0.62
                r += (198 * fold - r) * k; g += (194 * fold - g) * k; b += (184 * fold - b) * k
            }
            2 -> if (zm > WINDOW_HEAD_M - fw - 0.52) {
                // Rollladen, ein Stueck herabgelassen.
                val slat = if ((zm * 1000).toInt() % 52 < 9) 0.84 else 1.0
                r = 178 * slat; g = 177 * slat; b = 171 * slat
            }
            3 -> if (zm < WINDOW_SILL_M + fw + 0.42) {
                // Halbhohe Scheibengardine.
                val k = 0.5
                r += (205 - r) * k; g += (203 - g) * k; b += (196 - b) * k
            }
        }
        return rgb(r, g, b)
    }

    // --- Klinker ------------------------------------------------------------

    private const val COURSE_M = STOREY_M / 32
    private const val BRICK_M = BAY_M / 11

    private fun brick(xm: Double, zm: Double): Int {
        val row = floor(zm / COURSE_M).toInt()
        val fz = zm / COURSE_M - row
        val xs = xm / BRICK_M + (if (row and 1 == 0) 0.0 else 0.5)
        val col = floor(xs).toInt()
        val fx = xs - col
        val mortar = fz < 0.14 || fx < 0.05
        val cl = 1.0 + cloud(xm, zm, 4, 0xb21) * 0.06
        if (mortar) return rgb(150 * cl, 141 * cl, 130 * cl)
        // Stein: rotbraun, jeder etwas anders, einzelne dunkel gebrannt.
        val h = Noise.hash01(Math.floorMod(col, 44), row and 31, 0xb17)
        val h2 = Noise.hash01(Math.floorMod(col, 44), row and 31, 0xb18)
        var f = 0.86 + 0.26 * h
        var r = 140.0
        var g = 80.0
        var b = 65.0
        if (h2 > 0.84) {
            r = 104.0; g = 63.0; b = 58.0
        } else if (h2 < 0.14) {
            r = 154.0; g = 94.0; b = 70.0
        }
        // Unterkante jedes Steins minimal dunkler (Fugenschatten).
        if (fz < 0.30) f *= 0.93
        f *= cl
        return rgb(r * f, g * f, b * f)
    }

    private fun brickBay(xm: Double, zm: Double): Int {
        val bay = floor(xm / BAY_M).toInt() and 3
        val bx = xm - floor(xm / BAY_M) * BAY_M
        val dx = abs(bx - BAY_M / 2)
        window(dx, bx, zm, bay, WINDOW_HALF_M, frame = 0xF2F1EE, sillStone = true)?.let { return it }
        // Sturz als Rollschicht: hochkant gestellte Steine ueber der Oeffnung.
        if (zm > WINDOW_HEAD_M && zm < WINDOW_HEAD_M + 0.24 && dx < WINDOW_HALF_M + 0.12) {
            val s = bx / 0.0715
            val fx = s - floor(s)
            val h = Noise.hash01(floor(s).toInt(), 7, 0xb19)
            if (fx < 0.14 || zm > WINDOW_HEAD_M + 0.228) return rgb(150.0, 141.0, 130.0)
            val f = 0.84 + 0.24 * h
            return rgb(136 * f, 77 * f, 63 * f)
        }
        return shade(brick(xm, zm), revealShade(dx, zm, WINDOW_HALF_M))
    }

    // --- Schiefer -----------------------------------------------------------

    private const val SLATE_ROW_M = STOREY_M / 24
    private const val SLATE_M = BAY_M / 12

    /** Kleinteilige Schieferverkleidung: versetzte Reihen, jede Platte etwas anders grau. */
    private fun slate(xm: Double, zm: Double): Int {
        val row = floor(zm / SLATE_ROW_M).toInt()
        val fz = zm / SLATE_ROW_M - row
        val xs = xm / SLATE_M + (if (row and 1 == 0) 0.0 else 0.5)
        val col = floor(xs).toInt()
        val fx = xs - col
        val h = Noise.hash01(Math.floorMod(col, 48), Math.floorMod(row, 24), 0x51a)
        var f = 0.80 + 0.40 * h
        // Unterkante und Stossfuge als feine dunkle Linie; die Platte darueber wirft einen Hauch Schatten.
        if (fz < 0.16 || fx < 0.06) f *= 0.62 else if (fz > 0.86) f *= 0.90
        f *= 1.0 + cloud(xm, zm, 3, 0x51b) * 0.08
        return rgb(60 * f, 64 * f, 71 * f)
    }

    private fun slateBay(xm: Double, zm: Double): Int {
        val bay = floor(xm / BAY_M).toInt() and 3
        val bx = xm - floor(xm / BAY_M) * BAY_M
        val dx = abs(bx - BAY_M / 2)
        val half = 0.50
        window(dx, bx, zm, bay, half, frame = 0xF4F3F0, sillStone = false)?.let { return it }
        // Breite weisse Holzbekleidung rund um das Fenster.
        val casing = 0.10
        if (dx <= half + casing && zm >= WINDOW_SILL_M - casing && zm <= WINDOW_HEAD_M + casing) {
            val low = if (zm < WINDOW_SILL_M - casing + 0.025) 0.82 else 1.0
            return rgb(240 * low, 239 * low, 234 * low)
        }
        // Gruene Schlaglaeden links und rechts, mit Rahmen und Lamellen.
        val s0 = half + casing
        val s1 = s0 + 0.46
        if (dx > s0 && dx <= s1 && zm >= WINDOW_SILL_M - 0.02 && zm <= WINDOW_HEAD_M + 0.02) {
            val inner = dx - s0 > 0.05 && s1 - dx > 0.05 && zm > WINDOW_SILL_M + 0.05 && zm < WINDOW_HEAD_M - 0.05 &&
                abs(zm - (WINDOW_SILL_M + WINDOW_HEAD_M) / 2) > 0.03
            val louver = if (inner && (zm * 1000).toInt() % 60 < 16) 0.74 else 1.0
            val edge = if (dx - s0 < 0.015) 0.8 else 1.0
            val f = louver * edge
            return rgb(44 * f, 86 * f, 58 * f)
        }
        val shadow = if (dx > s1 && dx < s1 + 0.03) 0.85 else 1.0
        return shade(slate(xm, zm), shadow)
    }

    // --- Fachwerk -----------------------------------------------------------

    private fun timberBay(xm: Double, zm: Double): Int {
        val bay = floor(xm / BAY_M).toInt() and 3
        val bx = xm - floor(xm / BAY_M) * BAY_M
        val dx = abs(bx - BAY_M / 2)
        val half = 0.47
        val blind = bay == 2
        if (!blind) window(dx, bx, zm, bay, half, frame = 0xF0EEE8, sillStone = false)?.let { return it }
        val beam = 0.15
        val post = 0.075
        var timber = zm < beam || zm > STOREY_M - beam || bx < post || bx > BAY_M - post
        if (blind) {
            // Feld ohne Fenster: Mittelpfosten, Riegel und zwei Streben.
            timber = timber || dx < post || abs(zm - STOREY_M * 0.52) < beam / 2
            val t = (zm - beam) / (STOREY_M * 0.52 - beam / 2 - beam)
            if (t in 0.0..1.0) {
                val span = BAY_M / 2 - 2 * post
                val diag = post + span * t
                timber = timber || abs(dx - (BAY_M / 2 - diag)) < 0.085
            }
        } else {
            // Fensterstiele, Brust- und Sturzriegel.
            timber = timber || (dx > half && dx < half + 0.14) ||
                (zm < WINDOW_SILL_M && zm > WINDOW_SILL_M - 0.14) || (zm > WINDOW_HEAD_M && zm < WINDOW_HEAD_M + 0.14)
        }
        if (timber) {
            // Dunkelbraun-schwarz gestrichenes Holz mit etwas Maserung.
            val f = 1.0 + Noise.fbm(xm * 9.0, zm * 9.0, 0xfa1, 2) * 0.16
            return rgb(52 * f, 42 * f, 36 * f)
        }
        // Gefach: gekalkt, leicht fleckig.
        val f = 1.0 + cloud(xm, zm, 5, 0xfa2) * 0.035
        return rgb(232 * f, 228 * f, 216 * f)
    }

    // --- Holz, Sockel, Blech ------------------------------------------------

    /** Senkrechte Bretter (Schuppen, Scheune): aendert sich nur entlang der Wand. */
    private fun boards(xm: Double, boardM: Double, seed: Int): Int {
        val s = xm / boardM
        val i = floor(s).toInt()
        val fx = s - i
        val h = Noise.hash01(Math.floorMod(i, 80), 3, seed)
        var f = 0.78 + 0.40 * h
        if (fx < 0.07) f *= 0.45 else if (fx > 0.93) f *= 0.82
        f *= 1.0 + 0.07 * sin(xm * 240.0 + h * 40.0)
        return rgb(116 * f, 98 * f, 79 * f)
    }

    private fun socle(xm: Double, zm: Double): Int {
        val f = 1.0 + cloud(xm, zm * 0.3, 6, 0x50c) * 0.07 + cloud(xm, 0.0, 30, 0x50d) * 0.03
        return rgb(152 * f, 148 * f, 141 * f)
    }

    private fun sheet(xm: Double): Int {
        // Trapezprofil: breite Hochsicke, schmale dunkle Tiefsicke, Kanten als feine Linien.
        val fx = xm / 0.25 - floor(xm / 0.25)
        val f = when {
            fx < 0.10 -> 0.86
            fx < 0.28 -> 0.74
            fx < 0.38 -> 0.90
            else -> 1.0
        }
        return rgb(212 * f, 213 * f, 214 * f)
    }

    // --- Daecher ------------------------------------------------------------

    /** Dachpfannen, hell und neutral (Ton, Beton oder Schiefer entscheidet die Vertexfarbe). */
    private fun pantile(xm: Double, zm: Double): Int {
        val row = floor(zm / 0.33).toInt()
        val fz = zm / 0.33 - row
        val s = xm / 0.30
        val col = floor(s).toInt()
        val fx = s - col
        val h = Noise.hash01(Math.floorMod(col, 56), Math.floorMod(row, 12), 0x7a1)
        var f = 0.90 + 0.16 * h
        // Mulde der Pfanne und Deckfalz: nur als weiche Fugenverschattung.
        f *= when {
            fx < 0.07 -> 0.72
            fx < 0.30 -> 0.90
            else -> 1.0
        }
        // Unterkante der Reihe (Ueberdeckung).
        if (fz < 0.10) f *= 0.70 else if (fz < 0.22) f *= 0.92
        // Verwitterung in grossen weichen Flecken, kachelnd.
        f *= 1.0 + Noise.fbm(xm / 16.8 * 12, zm / 3.96 * 12, 0x7a2, 3, 12) * 0.10
        return rgb(204 * f, 200 * f, 196 * f)
    }

    private fun bitumen(xm: Double, zm: Double): Int {
        val f = 1.0 + Noise.fbm(xm / 8.0 * 16, zm / 8.0 * 16, 0xb17, 3, 16) * 0.07
        return rgb(198 * f, 197 * f, 195 * f)
    }

    // --- Tueren und Tore ----------------------------------------------------

    /** Das Band der Aufsetzteile; [px] Pixelspalte, [t] 0 (unten) .. 1 (oben). */
    private fun special(px: Double, t: Double): Int {
        for (d in Decal.entries) {
            if (px >= d.x0 && px < d.x1) {
                val fx = (px - d.x0) / (d.x1 - d.x0)
                return when (d) {
                    Decal.DOOR_PANEL -> doorPanel(fx, t, d)
                    Decal.DOOR_WOOD -> doorWood(fx, t, d)
                    Decal.GARAGE_DOOR -> garageDoor(fx, t, d)
                    Decal.BARN_GATE -> barnGate(fx, t, d)
                    Decal.DOOR_PLAIN -> doorPlain(fx, t, d)
                }
            }
        }
        return Argb.rgb(128, 128, 128)
    }

    /** Zarge rundum: `true` im Rahmen der Breite [frameM]. */
    private fun inFrame(fx: Double, t: Double, d: Decal, frameM: Double): Boolean {
        val x = fx * d.widthM
        val z = t * d.heightM
        return x < frameM || x > d.widthM - frameM || z > d.heightM - frameM
    }

    private fun doorPanel(fx: Double, t: Double, d: Decal): Int {
        val x = fx * d.widthM
        val z = t * d.heightM
        if (inFrame(fx, t, d, 0.07)) return Argb.rgb(236, 236, 232)
        if (z < 0.03) return Argb.rgb(120, 118, 112)
        // Schmaler Glasausschnitt und Griffstange.
        if (x > 0.62 && x < 0.86 && z > 0.55 && z < 1.85) {
            val k = (z - 0.55) / 1.3
            return rgb(44 + 40 * k, 52 + 48 * k, 62 + 56 * k)
        }
        if (x > 0.20 && x < 0.235 && z > 0.75 && z < 1.45) return Argb.rgb(150, 150, 150)
        val groove = if (abs(x - 0.52) < 0.008) 0.86 else 1.0
        return rgb(222 * groove, 222 * groove, 220 * groove)
    }

    private fun doorWood(fx: Double, t: Double, d: Decal): Int {
        val x = fx * d.widthM
        val z = t * d.heightM
        if (inFrame(fx, t, d, 0.07)) return Argb.rgb(84, 62, 44)
        if (z < 0.03) return Argb.rgb(110, 108, 102)
        // Kassetten: zwei oben, zwei unten.
        val px = abs(x - d.widthM / 2)
        val inPanel = px > 0.05 && px < d.widthM / 2 - 0.16 && ((z > 0.22 && z < 0.92) || (z > 1.08 && z < 1.84))
        val edge = inPanel && (px < 0.07 || px > d.widthM / 2 - 0.18 || abs(z - 0.22) < 0.02 || abs(z - 1.84) < 0.02 || abs(z - 0.92) < 0.02 || abs(z - 1.08) < 0.02)
        val f = (if (edge) 0.72 else if (inPanel) 0.90 else 1.0) * (1.0 + 0.06 * sin(x * 180.0))
        if (x > 0.14 && x < 0.19 && z > 0.98 && z < 1.06) return Argb.rgb(170, 160, 120)
        return rgb(124 * f, 86 * f, 54 * f)
    }

    private fun garageDoor(fx: Double, t: Double, d: Decal): Int {
        val z = t * d.heightM
        if (inFrame(fx, t, d, 0.05)) return Argb.rgb(200, 200, 198)
        // Vier Sektionen mit feinen Sicken.
        val sec = z / (d.heightM / 4)
        val fs = sec - floor(sec)
        var f = 1.0
        if (fs < 0.035) f = 0.68 else if (abs(fs - 0.36) < 0.012 || abs(fs - 0.68) < 0.012) f = 0.90
        if (z < 0.04) f = 0.55
        if (abs(fx - 0.5) < 0.04 && z > 0.62 && z < 0.68) return Argb.rgb(90, 90, 90)
        return rgb(228 * f, 228 * f, 226 * f)
    }

    private fun barnGate(fx: Double, t: Double, d: Decal): Int {
        val x = fx * d.widthM
        val z = t * d.heightM
        if (inFrame(fx, t, d, 0.12)) return Argb.rgb(74, 60, 48)
        val c = boards(x, 0.16, 0x6a7)
        // Mittelstoss, Querriegel und Beschlaege.
        if (abs(x - d.widthM / 2) < 0.02) return shade(c, 0.35)
        if (abs(z - 0.55) < 0.07 || abs(z - 2.45) < 0.07) return shade(c, 0.78)
        return shade(c, 0.92)
    }

    private fun doorPlain(fx: Double, t: Double, d: Decal): Int {
        val x = fx * d.widthM
        val z = t * d.heightM
        if (inFrame(fx, t, d, 0.05)) return Argb.rgb(200, 200, 198)
        if (z < 0.03) return Argb.rgb(110, 110, 108)
        if (x > 0.10 && x < 0.22 && z > 0.98 && z < 1.03) return Argb.rgb(96, 96, 96)
        return Argb.rgb(226, 226, 224)
    }

    /** Linearer Mittelwert der Inhaltszeilen eines Bands (fuer den Test der Tabelle [meanLinear]). */
    fun measureMeanLinear(img: RgbaImage, band: Band): FloatArray {
        var r = 0.0
        var g = 0.0
        var b = 0.0
        val y0 = band.top + GUTTER
        val rows = band.contentPx
        for (y in y0 until y0 + rows) for (x in 0 until img.width) {
            val c = img[x, y]
            r += Argb.SRGB_TO_LINEAR[Argb.red(c)]
            g += Argb.SRGB_TO_LINEAR[Argb.green(c)]
            b += Argb.SRGB_TO_LINEAR[Argb.blue(c)]
        }
        val n = (rows * img.width).toDouble()
        return floatArrayOf((r / n).toFloat(), (g / n).toFloat(), (b / n).toFloat())
    }
}
