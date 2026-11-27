package de.trailscape.app.indoor.render

/**
 * # Studio-Buehne fuer die 3D-Garagen-Vorschau
 *
 * Erzeugt das flache Podest unter Fahrer und Rad sowie die passenden
 * Studio-Farben fuer das helle und dunkle Atlas-Theme — rein im Code
 * ueber [GlbWriter] und [MeshBuilder], ohne eigene Modell-Datei.
 */
internal object GarageStageModel {
    data class GarageStudioLook(
        val skyLinear: FloatArray,
        val pedestalLinear: FloatArray,
        val rimLinear: FloatArray,
    )

    fun studioLook(darkTheme: Boolean): GarageStudioLook = if (darkTheme) {
        GarageStudioLook(
            skyLinear = floatArrayOf(0.011f, 0.014f, 0.020f),
            pedestalLinear = floatArrayOf(0.028f, 0.034f, 0.045f, 1f),
            rimLinear = floatArrayOf(0.060f, 0.070f, 0.090f, 1f),
        )
    } else {
        GarageStudioLook(
            skyLinear = floatArrayOf(0.81f, 0.77f, 0.71f),
            pedestalLinear = floatArrayOf(0.72f, 0.68f, 0.62f, 1f),
            rimLinear = floatArrayOf(0.55f, 0.52f, 0.47f, 1f),
        )
    }

    /**
     * Baut einen runden Studio-Teller (Oberseite exakt auf `y = 0.0`, damit
     * die Reifen buendig aufstehen und Schatten werfen) samt feiner Aussenkante.
     */
    fun buildPedestalGlb(darkTheme: Boolean): ByteArray {
        val look = studioLook(darkTheme)
        val w = GlbWriter()
        val mPlate = w.material(
            GlbWriter.Material(
                name = "garage_pedestal",
                color = look.pedestalLinear,
                roughness = 0.90f,
            ),
        )
        val mRim = w.material(
            GlbWriter.Material(
                name = "garage_pedestal_rim",
                color = look.rimLinear,
                roughness = 0.70f,
            ),
        )

        val plate = MeshBuilder()
            .tube(V3(0.0, -0.03, 0.0), V3(0.0, 0.0, 0.0), PEDESTAL_RADIUS_M, seg = 36)
        val rim = buildRimRing(PEDESTAL_RADIUS_M, seg = 36)

        val mesh = w.mesh(
            "garage_pedestal_mesh",
            listOf(plate.primitive(mPlate), rim.primitive(mRim)),
        )
        w.node(GlbWriter.Node("garage_pedestal_node", mesh = mesh))
        return w.build()
    }

    private fun buildRimRing(radius: Double, seg: Int): MeshBuilder {
        val mb = MeshBuilder()
        val inner = radius - 0.025
        val outer = radius + 0.004
        val yTop = 0.002
        val yBot = -0.03
        for (i in 0 until seg) {
            val a0 = 2.0 * Math.PI * i / seg
            val a1 = 2.0 * Math.PI * ((i + 1) % seg) / seg
            val c0 = kotlin.math.cos(a0)
            val s0 = kotlin.math.sin(a0)
            val c1 = kotlin.math.cos(a1)
            val s1 = kotlin.math.sin(a1)
            val nOut = V3((c0 + c1) * 0.5, 0.0, (s0 + s1) * 0.5)
            // Oberer schmaler Ring
            mb.quad(
                V3(inner * c0, yTop, inner * s0),
                V3(outer * c0, yTop, outer * s0),
                V3(outer * c1, yTop, outer * s1),
                V3(inner * c1, yTop, inner * s1),
                V3.UP,
            )
            // Aussenmantel
            mb.quad(
                V3(outer * c0, yBot, outer * s0),
                V3(outer * c1, yBot, outer * s1),
                V3(outer * c1, yTop, outer * s1),
                V3(outer * c0, yTop, outer * s0),
                nOut,
            )
        }
        return mb
    }

    private const val PEDESTAL_RADIUS_M = 1.25
}
