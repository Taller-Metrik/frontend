package com.metrik.app.gps

import com.metrik.app.core.geometry.Geometry
import com.metrik.app.core.geometry.Point
import com.metrik.app.testing.EvidenceRule
import com.metrik.app.testing.Evidencia
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/**
 * Esquinas espurias al final del recorrido (video «puente parque», 14/11): al volver por el puente a la partida se
 * marcaron esquinas sobre la recta de vuelta y ya en la partida; con el GPS rebotando, el final salía en zigzag con
 * lados de 3,9 / 4,5 / 7,9 m. Las esquinas reales (escalón de 4,6 m, L, figura cóncava) no se tocan.
 */
class GpsCornerCleanupTest {
    @get:Rule val ev = EvidenceRule()

    /** Punto del recorrido: [p] y si ahí se tocó «Esquina». */
    private data class Wp(val p: Point, val mark: Boolean)

    private fun wp(x: Double, y: Double, mark: Boolean = true) = Wp(Point(x, y), mark)

    /**
     * Camina de punto en punto con pasos de ~0,7 m, brújula desviada [biasDeg] y paso real [scale] veces el supuesto;
     * termina en el último punto (la partida). Devuelve el recorrido y el índice de cada esquina marcada.
     */
    private fun walk(route: List<Wp>, biasDeg: Double = 0.0, scale: Double = 1.0): Pair<List<Point>, List<Int>> {
        val pts = mutableListOf(route.first().p)
        val idx = mutableListOf<Int>()
        for ((a, b) in route.zipWithNext()) {
            val d = Geometry.euclidean(a.p, b.p)
            val steps = maxOf(1, Math.round(d / 0.7).toInt())
            val heading = Math.atan2(b.p.y - a.p.y, b.p.x - a.p.x) + Math.toRadians(biasDeg)
            repeat(steps) {
                val last = pts.last()
                pts += Point(last.x + d / steps * scale * cos(heading), last.y + d / steps * scale * sin(heading))
            }
            if (b.mark) idx += pts.lastIndex
        }
        return pts to idx
    }

    private fun area(v: List<Point>) = Geometry.analyze(v).areaM2

    private fun segDist(p: Point, a: Point, b: Point): Double {
        val dx = b.x - a.x; val dy = b.y - a.y
        val t = (((p.x - a.x) * dx + (p.y - a.y) * dy) / (dx * dx + dy * dy)).coerceIn(0.0, 1.0)
        return Geometry.euclidean(p, Point(a.x + dx * t, a.y + dy * t))
    }

    /** Cuerpo del video: ida por el puente (y = 0), rectángulo con un escalón real de 4,62 m y vuelta por el puente. */
    private val body = listOf(
        wp(0.0, 0.0, mark = false), wp(35.0, 0.0), wp(35.0, -12.0), wp(68.0, -12.0), wp(68.0, 13.0), wp(40.0, 13.0),
        wp(40.0, 8.38), wp(35.0, 8.38), wp(35.0, 3.0),
    )
    private val bodyTruth = listOf(Point(0.0, 0.0)) + body.drop(1).map { it.p }

    @Test
    @Evidencia(
        id = "FU-104", nombre = "Esquinas espurias al final: zigzag, doble toque y en la partida", requisito = "RF-18, RF-19, RF-21",
        objetivo = "Verificar que las esquinas marcadas de más al volver a la partida (sobre la recta de vuelta, en zigzag, "
            + "dos veces en el mismo sitio o ya en la partida) se descartan antes del ajuste y que el área y la forma mejoran.",
        entrada = "a) Video «puente parque»: ida 35 m, rectángulo 33 × 25 m con escalón de 4,62 m, vuelta por el puente con "
            + "3 esquinas extra (a 14, 7 y 2,5 m de la partida) y anclas GPS rebotando hacia afuera 4 / 0,5 / 3,5 m (zigzag); brújula 2°, 10 semillas, paso 0,97. "
            + "b) Rectángulo 30 × 20 m con doble toque en una esquina, zigzag de 3,9 m en el último lado y esquina a 2,2 m de la partida",
        esperado = "Se descartan justo las espurias; el error de área y el número de vértices bajan, sin lados cortitos al final",
    )
    fun esquinasEspuriasAlFinal() {
        // a) Video: 3 esquinas extra sobre la vuelta (recta de (35, 3) a la partida), con el GPS rebotando entre barandas
        val route = body + listOf(wp(14.0, 1.2), wp(7.0, 0.6), wp(2.5, 0.21), wp(0.0, 0.0, mark = false))
        val (track, idx) = walk(route, biasDeg = 2.0, scale = 0.97)
        val bounce = listOf(0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 4.0, 0.5, 3.5)
        val truth = area(bodyTruth)
        // Desvío de la forma: lo más que se aparta un vértice del borde real
        fun deviation(v: List<Point>) = v.maxOf { p -> (bodyTruth + bodyTruth.first()).zipWithNext().minOf { (a, b) -> segDist(p, a, b) } }
        fun shortSides(v: List<Point>) = (v + v.first()).zipWithNext().count { (a, b) -> Geometry.euclidean(a, b) < 4.5 }
        var errRaw = 0.0; var errClean = 0.0; var devRaw = 0.0; var devClean = 0.0; var shortRaw = 0; var shortClean = 0
        var kept: List<Int> = emptyList()
        val seeds = 1L..10L
        for (seed in seeds) {
            val rnd = java.util.Random(seed)
            val anchors = route.filter { it.mark }.mapIndexed { k, w ->
                GpsTraverse.Anchor(Point(w.p.x + rnd.nextGaussian() * 0.5, w.p.y + bounce[k] + rnd.nextGaussian() * 0.5), 1.0)
            }
            val end = GpsTraverse.Anchor(Point(0.5, 2.0), 1.0)
            kept = GpsCornerCleanup.keep(track, idx, anchors)
            assertEquals((0..7).toList(), kept)
            val raw = GpsTraverse.adjust(track, idx, anchors, end, startSigmaM = 1.0)
            val clean = GpsTraverse.adjust(track, kept.map { idx[it] }, kept.map { anchors[it] }, end, startSigmaM = 1.0)
            errRaw += abs(area(raw.vertices) - truth) / seeds.count(); errClean += abs(area(clean.vertices) - truth) / seeds.count()
            devRaw = maxOf(devRaw, deviation(raw.vertices)); devClean = maxOf(devClean, deviation(clean.vertices))
            shortRaw = maxOf(shortRaw, shortSides(raw.vertices)); shortClean = maxOf(shortClean, shortSides(clean.vertices))
        }

        // b) Rectángulo: doble toque en (30, 20), zigzag de los pasos en el último lado y esquina ya en la partida
        val rect = listOf(
            wp(0.0, 0.0, mark = false), wp(30.0, 0.0), wp(30.0, 20.0), wp(29.3, 20.0), wp(0.0, 20.0),
            wp(0.0, 12.0), wp(2.5, 9.0), wp(0.0, 2.2), wp(0.0, 0.0, mark = false),
        )
        val (t2, i2) = walk(rect)
        val a2 = List(i2.size) { null }
        val k2 = GpsCornerCleanup.keep(t2, i2, a2)
        assertEquals(listOf(0, 1, 3), k2)
        val raw2 = GpsTraverse.adjust(t2, i2, a2, null, startSigmaM = 1.0)
        val clean2 = GpsTraverse.adjust(t2, k2.map { i2[it] }, k2.map { a2[it] }, null, startSigmaM = 1.0)

        ev.obtenido = "a) esquinas ${idx.size}→${kept.size}; área real ${"%.0f".format(truth)} m², error medio (10 semillas) antes " +
            "${"%.1f".format(errRaw)}, después ${"%.1f".format(errClean)} m²; desvío máx. de un vértice antes ${"%.1f".format(devRaw)}, " +
            "después ${"%.1f".format(devClean)} m; lados < 4,5 m antes $shortRaw, después $shortClean. " +
            "b) esquinas ${i2.size}→${k2.size}; área 600 m²: antes ${"%.0f".format(area(raw2.vertices))}, después ${"%.0f".format(area(clean2.vertices))}"
        assertTrue("área: antes $errRaw, después $errClean", errClean < errRaw)
        assertTrue("forma: antes $devRaw, después $devClean", devClean < devRaw)
        assertTrue("lados cortos: antes $shortRaw, después $shortClean", shortClean < shortRaw)
        assertEquals(4, clean2.vertices.size)
        assertTrue(abs(area(clean2.vertices) - 600.0) < abs(area(raw2.vertices) - 600.0))
        assertTrue(abs(area(clean2.vertices) - 600.0) < 1.0)
    }

    @Test
    @Evidencia(
        id = "FU-105", nombre = "Las esquinas reales no se descartan", requisito = "RF-18, RF-21",
        objetivo = "Verificar que la limpieza no toca esquinas reales: el escalón de 4,62 m del rectángulo del video (giros "
            + "de 90° opuestos que dejan las rectas corridas), una L, una figura cóncava y rectángulos normales o angostos.",
        entrada = "5 figuras recorridas por pasos exactos y con brújula desviada ±4° y paso 0,95–1,05",
        esperado = "Se conservan todas las esquinas en todas las figuras y variantes",
    )
    fun esquinasRealesSeConservan() {
        val figures = mapOf(
            "video con escalón" to body + wp(0.0, 0.0, mark = false),
            "rectángulo 20×12" to listOf(wp(0.0, 0.0, false), wp(20.0, 0.0), wp(20.0, 12.0), wp(0.0, 12.0), wp(0.0, 0.0, false)),
            "angosto 20×4" to listOf(wp(0.0, 0.0, false), wp(20.0, 0.0), wp(20.0, 4.0), wp(0.0, 4.0), wp(0.0, 0.0, false)),
            "L" to listOf(
                wp(0.0, 0.0, false), wp(30.0, 0.0), wp(30.0, 10.0), wp(10.0, 10.0), wp(10.0, 25.0), wp(0.0, 25.0), wp(0.0, 0.0, false),
            ),
            "cóncava (flecha)" to listOf(
                wp(0.0, 0.0, false), wp(25.0, 0.0), wp(25.0, 20.0), wp(12.5, 8.0), wp(0.0, 20.0), wp(0.0, 0.0, false),
            ),
            "escalón desde mitad de pared" to listOf(
                wp(10.0, 0.0, false), wp(30.0, 0.0), wp(30.0, 20.0), wp(4.62, 20.0), wp(4.62, 15.38), wp(0.0, 15.38), wp(0.0, 0.0),
                wp(10.0, 0.0, false),
            ),
        )
        val variants = listOf(0.0 to 1.0, 4.0 to 0.95, -4.0 to 1.05)
        val lost = mutableListOf<String>()
        for ((name, route) in figures) for ((bias, scale) in variants) {
            val (track, idx) = walk(route, bias, scale)
            val kept = GpsCornerCleanup.keep(track, idx, List(idx.size) { null })
            if (kept != idx.indices.toList()) lost += "$name ($bias°, $scale): $kept de ${idx.size}"
        }
        ev.obtenido = if (lost.isEmpty()) "todas las esquinas conservadas en ${figures.size * variants.size} recorridos" else "perdidas: $lost"
        assertTrue("perdidas: $lost", lost.isEmpty())
    }

    @Test
    @Evidencia(
        id = "FU-115", nombre = "La limpieza final respeta las esquinas confirmadas (lados de menos de 2 m)",
        requisito = "RF-18, RF-19",
        objetivo = "En el puente del profesor (franja de 1,4 m de ancho) la última esquina queda a 1,4 m de la partida: la "
            + "regla «en la partida al final» la borraba aunque la persona la hubiera confirmado tocando dos veces. Una "
            + "esquina confirmada no se descarta por ninguna regla.",
        entrada = "Franja 20 × 1,4 m caminada con esquinas en (20, 0), (20, 1,4) y (0, 1,4); limpieza sin y con las 3 confirmadas",
        esperado = "Con las esquinas confirmadas se conservan las 3",
    )
    fun esquinasConfirmadasSeConservan() {
        val (track, idx) = walk(listOf(wp(0.0, 0.0, mark = false), wp(20.0, 0.0), wp(20.0, 1.4), wp(0.0, 1.4), wp(0.0, 0.0, mark = false)))
        val anchors = List(idx.size) { null }
        val plain = GpsCornerCleanup.keep(track, idx, anchors)
        val locked = GpsCornerCleanup.keep(track, idx, anchors, locked = idx.indices.toSet())
        ev.obtenido = "sin confirmar: $plain; confirmadas: $locked"
        assertEquals(listOf(0, 1, 2), locked)
    }
}
