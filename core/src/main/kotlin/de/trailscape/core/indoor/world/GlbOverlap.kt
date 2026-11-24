package de.trailscape.core.indoor.world

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/**
 * # Koplanare Ueberlappungen in einer Kachel-GLB
 *
 * Sucht Dreiecke verschiedener Primitive (Netz × Material), die sich im Grundriss
 * ueberdecken und dort in derselben Hoehe liegen (Abstand < [tolM]) — die Ursache
 * fuer Flimmern (Z-Fighting) auf dem Geraet. Geprueft wird je Dreieck ein Punkt
 * im Innern (Schwerpunkt), nur flache Dreiecke (Normale mit `|y| > 0,5`) und nur
 * die Knoten [nodes] samt ihrer Kinder.
 *
 * Ergebnis: je Paar „Material A / Material B“ die Zahl der Treffer.
 */
fun coplanarOverlaps(
    glb: ByteArray,
    nodes: Set<String> = setOf(WorldNames.NODE_TERRAIN, WorldNames.NODE_ROUTE, WorldNames.NODE_ROADS, WorldNames.NODE_WATER),
    tolM: Double = 0.004,
): Map<String, Int> {
    val bb = ByteBuffer.wrap(glb).order(ByteOrder.LITTLE_ENDIAN)
    val jsonLen = bb.getInt(12)
    val json = Json.parseToJsonElement(String(glb, 20, jsonLen, Charsets.UTF_8)).jsonObject
    val binStart = 20 + jsonLen + 8
    val jNodes = json["nodes"]?.jsonArray ?: return emptyMap()
    val jMeshes = json["meshes"]?.jsonArray ?: return emptyMap()
    val acc = json["accessors"]!!.jsonArray
    val views = json["bufferViews"]!!.jsonArray
    val mats = json["materials"]?.jsonArray ?: JsonArray(emptyList())

    // Oberster benannter Knoten je Knoten (Szene → Knoten der Namensliste → Kinder).
    val owner = arrayOfNulls<String>(jNodes.size)
    fun mark(i: Int, name: String?) {
        val n = jNodes[i].jsonObject
        val own = n["name"]?.jsonPrimitive?.content
        val top = name ?: own
        owner[i] = top
        n["children"]?.jsonArray?.forEach { mark(it.jsonPrimitive.int, top?.let { t -> if (t.startsWith("terrain_sub")) WorldNames.NODE_TERRAIN else t }) }
    }
    val roots = json["scenes"]?.jsonArray?.firstOrNull()?.jsonObject?.get("nodes")?.jsonArray?.map { it.jsonPrimitive.int } ?: jNodes.indices.toList()
    for (r in roots) mark(r, null)

    fun floats(ai: Int): FloatArray {
        val a = acc[ai].jsonObject
        val v = views[a["bufferView"]!!.jsonPrimitive.int].jsonObject
        val off = binStart + (v["byteOffset"]?.jsonPrimitive?.int ?: 0) + (a["byteOffset"]?.jsonPrimitive?.int ?: 0)
        val count = a["count"]!!.jsonPrimitive.int
        return FloatArray(count * 3) { bb.getFloat(off + it * 4) }
    }
    fun ints(ai: Int): IntArray {
        val a = acc[ai].jsonObject
        val v = views[a["bufferView"]!!.jsonPrimitive.int].jsonObject
        val off = binStart + (v["byteOffset"]?.jsonPrimitive?.int ?: 0) + (a["byteOffset"]?.jsonPrimitive?.int ?: 0)
        val count = a["count"]!!.jsonPrimitive.int
        return when (a["componentType"]!!.jsonPrimitive.int) {
            5123 -> IntArray(count) { bb.getShort(off + it * 2).toInt() and 0xFFFF }
            5125 -> IntArray(count) { bb.getInt(off + it * 4) }
            else -> IntArray(count) { glb[off + it].toInt() and 0xFF }
        }
    }

    // Dreiecke: x0 z0 y0 x1 z1 y1 x2 z2 y2 (glTF y = oben), je Dreieck sein Primitiv.
    val tri = ArrayList<DoubleArray>()
    val prim = ArrayList<Int>()
    val primName = ArrayList<String>()
    for ((ni, nd) in jNodes.withIndex()) {
        val meshIdx = nd.jsonObject["mesh"]?.jsonPrimitive?.int ?: continue
        val top = owner[ni] ?: continue
        if (top !in nodes) continue
        for (p in jMeshes[meshIdx].jsonObject["primitives"]!!.jsonArray) {
            val po = p.jsonObject
            if ((po["mode"]?.jsonPrimitive?.int ?: 4) != 4) continue
            val pos = floats(po["attributes"]!!.jsonObject["POSITION"]!!.jsonPrimitive.int)
            val idx = po["indices"]?.let { ints(it.jsonPrimitive.int) } ?: IntArray(pos.size / 3) { it }
            val mi = po["material"]?.jsonPrimitive?.int
            val mname = mi?.let { (mats[it] as JsonObject)["name"]?.jsonPrimitive?.content } ?: "?"
            val id = primName.size
            primName += "$top:$mname"
            for (t in 0 until idx.size / 3) {
                val d = DoubleArray(9)
                for (k in 0..2) {
                    val v = idx[t * 3 + k]
                    d[k * 3] = pos[v * 3].toDouble()
                    d[k * 3 + 1] = pos[v * 3 + 2].toDouble()
                    d[k * 3 + 2] = pos[v * 3 + 1].toDouble()
                }
                // flach genug?
                val ux = d[3] - d[0]; val uz = d[4] - d[1]; val uy = d[5] - d[2]
                val vx = d[6] - d[0]; val vz = d[7] - d[1]; val vy = d[8] - d[2]
                val ny = ux * vz - uz * vx
                val nx = uy * vz - uz * vy
                val nz = ux * vy - uy * vx
                val len = kotlin.math.sqrt(nx * nx + ny * ny + nz * nz)
                if (len < 1e-9 || abs(ny) / len < 0.5) continue
                tri += d
                prim += id
            }
        }
    }
    if (tri.isEmpty()) return emptyMap()
    val cell = 4.0
    val grid = HashMap<Long, MutableList<Int>>()
    fun key(x: Int, z: Int) = (x.toLong() shl 32) xor (z.toLong() and 0xffffffffL)
    for ((i, d) in tri.withIndex()) {
        val x0 = floor(min(d[0], min(d[3], d[6])) / cell).toInt()
        val x1 = floor(max(d[0], max(d[3], d[6])) / cell).toInt()
        val z0 = floor(min(d[1], min(d[4], d[7])) / cell).toInt()
        val z1 = floor(max(d[1], max(d[4], d[7])) / cell).toInt()
        if ((x1 - x0 + 1).toLong() * (z1 - z0 + 1) > 400) continue // riesige Dreiecke (Schuerzen o. ae.) auslassen
        for (x in x0..x1) for (z in z0..z1) grid.getOrPut(key(x, z)) { ArrayList() }.add(i)
    }
    val out = HashMap<String, Int>()
    val seen = HashSet<Long>()
    for ((i, a) in tri.withIndex()) {
        val cx = (a[0] + a[3] + a[6]) / 3
        val cz = (a[1] + a[4] + a[7]) / 3
        val cy = (a[2] + a[5] + a[8]) / 3
        val list = grid[key(floor(cx / cell).toInt(), floor(cz / cell).toInt())] ?: continue
        for (j in list) {
            if (prim[j] == prim[i]) continue
            val b = tri[j]
            // Baryzentrisch im Grundriss, deutlich im Innern.
            val d = (b[4] - b[7]) * (b[0] - b[6]) + (b[6] - b[3]) * (b[1] - b[7])
            if (abs(d) < 1e-9) continue
            val l1 = ((b[4] - b[7]) * (cx - b[6]) + (b[6] - b[3]) * (cz - b[7])) / d
            val l2 = ((b[7] - b[1]) * (cx - b[6]) + (b[0] - b[6]) * (cz - b[7])) / d
            val l3 = 1 - l1 - l2
            if (l1 < 0.02 || l2 < 0.02 || l3 < 0.02) continue
            val hy = l1 * b[2] + l2 * b[5] + l3 * b[8]
            if (abs(hy - cy) >= tolM) continue
            val p1 = min(prim[i], prim[j])
            val p2 = max(prim[i], prim[j])
            val pairKey = (min(i, j).toLong() shl 32) or max(i, j).toLong()
            if (!seen.add(pairKey)) continue
            val k = "${primName[p1]} / ${primName[p2]}"
            out[k] = (out[k] ?: 0) + 1
        }
    }
    return out
}

/**
 * # Auslaufende Vertex-Deckung in durchsichtigen Flaechen
 *
 * Zaehlt je Material mit `alphaMode = BLEND` die Eckpunkte, deren `COLOR_0`-Deckung unter 1 liegt.
 *
 * Warum das zaehlt: Filament zeichnet mit 4× MSAA in einen Float-Puffer und wertet den Shader eines
 * Dreiecks am Pixelmittelpunkt aus, auch wenn nur einige Abtastpunkte im Dreieck liegen und der
 * Mittelpunkt ausserhalb (keine `centroid`-Interpolation). Eine Vertex-Deckung, die zum Rand hin auf
 * null faellt, wird dort **negativ** fortgeschrieben; beim Mischen (`ONE, ONE_MINUS_SRC_ALPHA`) hellt
 * das den Hintergrund auf — auf dem Geraet weisse, gepunktete Linien an jeder Aussenkante weicher
 * Raender. Der Betrachter zeichnet in einen 8-Bit-Puffer, der vor dem Mischen auf 0..1 klemmt, und
 * zeigt das nicht. Weiche Raender muessen ihre Deckung deshalb aus der Textur holen (Werte bleiben in
 * 0..1, fortgeschriebene UV landen am geklemmten Rand); `MASK` ist unkritisch (negativ = verworfen).
 */
fun translucentVertexAlpha(glb: ByteArray): Map<String, Int> {
    val bb = ByteBuffer.wrap(glb).order(ByteOrder.LITTLE_ENDIAN)
    val jsonLen = bb.getInt(12)
    val json = Json.parseToJsonElement(String(glb, 20, jsonLen, Charsets.UTF_8)).jsonObject
    val binStart = 20 + jsonLen + 8
    val jMeshes = json["meshes"]?.jsonArray ?: return emptyMap()
    val acc = json["accessors"]!!.jsonArray
    val views = json["bufferViews"]!!.jsonArray
    val mats = json["materials"]?.jsonArray ?: return emptyMap()
    val out = HashMap<String, Int>()
    for (mesh in jMeshes) {
        for (p in mesh.jsonObject["primitives"]!!.jsonArray) {
            val po = p.jsonObject
            val mi = po["material"]?.jsonPrimitive?.int ?: continue
            val mat = mats[mi].jsonObject
            if (mat["alphaMode"]?.jsonPrimitive?.content != "BLEND") continue
            val ci = po["attributes"]!!.jsonObject["COLOR_0"]?.jsonPrimitive?.int ?: continue
            val a = acc[ci].jsonObject
            val v = views[a["bufferView"]!!.jsonPrimitive.int].jsonObject
            val off = binStart + (v["byteOffset"]?.jsonPrimitive?.int ?: 0) + (a["byteOffset"]?.jsonPrimitive?.int ?: 0)
            val count = a["count"]!!.jsonPrimitive.int
            val comps = if (a["type"]!!.jsonPrimitive.content == "VEC4") 4 else 3
            if (comps < 4) continue
            var low = 0
            when (a["componentType"]!!.jsonPrimitive.int) {
                5121 -> {
                    val stride = v["byteStride"]?.jsonPrimitive?.int ?: 4
                    for (i in 0 until count) if ((glb[off + i * stride + 3].toInt() and 0xFF) < 255) low++
                }
                5123 -> {
                    val stride = v["byteStride"]?.jsonPrimitive?.int ?: 8
                    for (i in 0 until count) if ((bb.getShort(off + i * stride + 6).toInt() and 0xFFFF) < 65535) low++
                }
                else -> {
                    val stride = v["byteStride"]?.jsonPrimitive?.int ?: 16
                    for (i in 0 until count) if (bb.getFloat(off + i * stride + 12) < 1f) low++
                }
            }
            if (low > 0) {
                val name = mat["name"]?.jsonPrimitive?.content ?: "?"
                out[name] = (out[name] ?: 0) + low
            }
        }
    }
    return out
}
