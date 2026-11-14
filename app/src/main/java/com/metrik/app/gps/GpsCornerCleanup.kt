package com.metrik.app.gps

import com.metrik.app.core.geometry.Geometry
import com.metrik.app.core.geometry.Point
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot

/**
 * Limpieza de esquinas espurias antes de compensar la poligonal ([GpsTraverse.adjust]).
 *
 * Al volver a la partida por un lugar angosto (un puente, una vereda) es fácil tocar «Esquina» de más: dos veces en
 * el mismo sitio, a mitad de un lado recto o ya parado en la partida. Con el GPS rebotando entre barandas, cada una de
 * esas esquinas recibe un ancla corrida unos metros hacia un lado u otro y el final sale en zigzag con lados cortitos.
 *
 * Todo se mide sobre el recorrido (pasos + brújula, o el trazo), no sobre las anclas GPS: lo que se caminó dice si
 * hubo un giro. Los criterios son conservadores; una esquina se descarta solo si:
 * 1. **Doble toque:** está a menos de [DOUBLE_TAP_M] de la esquina anterior y se caminó menos de [DOUBLE_TAP_PATH_M]
 *    entre ambas.
 * 2. **En la partida al final:** es de las últimas (todas las que la siguen también lo están) y queda a menos de
 *    [START_RADIUS_M] del final del recorrido, o a menos de [START_RADIUS_SOFT_M] si además no dobla (desvío menor
 *    que [START_SOFT_OFFSET_M] respecto de la recta a la partida). El cierre ya lo da Finalizar.
 * 2b. **Ida y vuelta junto a la partida:** entre las últimas esquinas que quedan todas a menos de
 *    [CLUSTER_RADIUS_M] del final, una en la que el recorrido vuelve atrás (giro > [CLUSTER_REVERSAL_DEG]) con algún
 *    lado de menos de [CLUSTER_SIDE_M] (buscando la partida se va y se viene). Del grupo se conserva solo la que más
 *    abarca la figura (el borde de afuera: el final del puente de vuelta); las demás eran la búsqueda de la partida.
 * 3. **Zigzag:** dos esquinas seguidas, a menos de [ZIGZAG_SIDE_M], con giros fuertes (> [ZIGZAG_TURN_DEG]) en
 *    sentidos opuestos, y la recta de antes y la de después son la misma: casi paralelas (< [ZIGZAG_PARALLEL_DEG]),
 *    se separan menos de [ZIGZAG_OFFSET_M] y ninguna de las dos esquinas se aparta más de [ZIGZAG_CHORD_M] de la
 *    cuerda entre sus vecinas.
 *    Un escalón real (giros de 90° que dejan las rectas corridas, p. ej. 4,6 m) no cumple esto último.
 * 4. **Casi colineal:** el rumbo cambia menos de [COLLINEAR_TURN_DEG] y la esquina se aparta menos de
 *    [COLLINEAR_OFFSET_M] de la recta entre sus vecinas.
 *
 * Las esquinas que la persona confirmó tocando dos veces (lado de menos de 2 m, esquina junto a la partida; ver
 * [com.metrik.app.feature.gps.GpsCornerTap]) no se descartan por ninguna regla.
 *
 * Siempre quedan al menos [MIN_CORNERS] esquinas (lo que exige el ajuste). Si no hay nada espurio devuelve todas.
 */
object GpsCornerCleanup {
    const val DOUBLE_TAP_M = 2.0
    const val DOUBLE_TAP_PATH_M = 4.0
    const val START_RADIUS_M = 3.0
    const val START_RADIUS_SOFT_M = 6.0
    const val START_SOFT_OFFSET_M = 2.0
    const val CLUSTER_RADIUS_M = 12.0
    const val CLUSTER_REVERSAL_DEG = 120.0
    const val CLUSTER_SIDE_M = 8.0
    const val ZIGZAG_SIDE_M = 6.0
    const val ZIGZAG_TURN_DEG = 30.0
    const val ZIGZAG_OFFSET_M = 2.0
    const val ZIGZAG_PARALLEL_DEG = 25.0
    const val ZIGZAG_CHORD_M = 3.0
    const val COLLINEAR_TURN_DEG = 15.0
    const val COLLINEAR_OFFSET_M = 1.5
    const val MIN_CORNERS = 2

    /**
     * @param track recorrido con la partida en el primer punto y el final (de vuelta en la partida) en el último.
     * @param cornerIdx índice del recorrido de cada esquina, en orden.
     * @param anchors ancla GPS de cada esquina (o null): ante un doble toque se conserva la que tiene ancla.
     * @param locked posiciones (en [cornerIdx]) de las esquinas que la persona confirmó tocando dos veces (un lado de
     *   menos de 2 m, una esquina junto a la partida): ninguna regla las descarta.
     * @return posiciones (en [cornerIdx]) de las esquinas que se conservan, en orden.
     */
    fun keep(track: List<Point>, cornerIdx: List<Int>, anchors: List<GpsTraverse.Anchor?>, locked: Set<Int> = emptySet()): List<Int> {
        require(cornerIdx.size == anchors.size) { "Cada esquina necesita su ancla (o null)" }
        if (track.size < 2 || cornerIdx.size <= MIN_CORNERS || cornerIdx.any { it !in track.indices }) return cornerIdx.indices.toList()
        val cum = DoubleArray(track.size)
        for (i in 1 until track.size) cum[i] = cum[i - 1] + Geometry.euclidean(track[i - 1], track[i])
        val kept = cornerIdx.indices.toMutableList()
        val start = track.first()
        val end = track.last()
        fun p(j: Int) = track[cornerIdx[kept[j]]]
        fun prev(j: Int) = if (j == 0) start else p(j - 1)
        fun next(j: Int) = if (j == kept.lastIndex) end else p(j + 1)
        // Confirmada por la persona (segundo toque): ninguna regla la descarta
        fun free(j: Int) = kept[j] !in locked

        while (kept.size > MIN_CORNERS) {
            // 1. Doble toque: se queda la que tiene ancla (si la segunda la tiene y la primera no). Si alguna de las dos
            //    está confirmada, el lado corto es real y no se toca
            val tap = (1 until kept.size).firstOrNull { j ->
                free(j - 1) && free(j) &&
                    Geometry.euclidean(p(j - 1), p(j)) < DOUBLE_TAP_M && cum[cornerIdx[kept[j]]] - cum[cornerIdx[kept[j - 1]]] < DOUBLE_TAP_PATH_M
            }
            if (tap != null) {
                val drop = if (anchors[kept[tap - 1]] == null && anchors[kept[tap]] != null) tap - 1 else tap
                kept.removeAt(drop); continue
            }
            // 2. En la partida al final: solo la última esquina (luego se revisa la anterior)
            val last = kept.lastIndex
            val dEnd = Geometry.euclidean(p(last), end)
            if (free(last) && (dEnd < START_RADIUS_M || (dEnd < START_RADIUS_SOFT_M && offset(p(last), prev(last), end) < START_SOFT_OFFSET_M))) {
                kept.removeAt(last); continue
            }
            // 2b. Ida y vuelta junto a la partida: las últimas esquinas, todas cerca del final
            var first = kept.size
            while (first > 0 && Geometry.euclidean(p(first - 1), end) < CLUSTER_RADIUS_M) first--
            val back = (first until kept.size).filter { j ->
                abs(turnDeg(prev(j), p(j), next(j))) > CLUSTER_REVERSAL_DEG &&
                    minOf(Geometry.euclidean(prev(j), p(j)), Geometry.euclidean(p(j), next(j))) < CLUSTER_SIDE_M
            }
            if (back.isNotEmpty()) {
                // Del ir y venir se queda una sola: la que más abarca la figura (el borde de afuera, p. ej. el final
                // del puente de vuelta); las que quedan adentro son la búsqueda de la partida
                val outside = kept.subList(0, first).map { track[cornerIdx[it]] }
                val sign = signedArea(listOf(start) + outside + kept.subList(first, kept.size).map { track[cornerIdx[it]] }).let { if (it < 0) -1.0 else 1.0 }
                val best = (first until kept.size).maxByOrNull { j -> sign * signedArea(listOf(start) + outside + p(j)) }!!
                val candidates = (first until kept.size).filter { it != best }.reversed()
                // Si la única del grupo es la que vuelve atrás, sale ella (salvo las confirmadas)
                val drop = (candidates.ifEmpty { listOf(best) }).filter(::free).take(kept.size - MIN_CORNERS)
                if (drop.isNotEmpty()) {
                    drop.forEach { kept.removeAt(it) }
                    continue
                }
            }
            // 3. Zigzag: dos esquinas cercanas que giran fuerte en sentidos opuestos y vuelven a la misma recta
            if (kept.size - 2 >= MIN_CORNERS) {
                val zig = (0 until kept.lastIndex).firstOrNull { j ->
                    if (!free(j) || !free(j + 1)) return@firstOrNull false
                    val a = p(j); val b = p(j + 1); val pa = prev(j); val nb = next(j + 1)
                    val ta = turnDeg(pa, a, b); val tb = turnDeg(a, b, nb)
                    Geometry.euclidean(a, b) < ZIGZAG_SIDE_M && abs(ta) > ZIGZAG_TURN_DEG && abs(tb) > ZIGZAG_TURN_DEG &&
                        ta * tb < 0 && minOf(lineDistance(nb, pa, a), lineDistance(pa, b, nb)) < ZIGZAG_OFFSET_M &&
                        abs(turnDeg(pa, a, Point(a.x + nb.x - b.x, a.y + nb.y - b.y))) < ZIGZAG_PARALLEL_DEG &&
                        maxOf(offset(a, pa, nb), offset(b, pa, nb)) < ZIGZAG_CHORD_M
                }
                if (zig != null) { kept.removeAt(zig + 1); kept.removeAt(zig); continue }
            }
            // 4. Casi colineal: se descarta la que menos dobla
            val flat = kept.indices.filter { j ->
                free(j) && abs(turnDeg(prev(j), p(j), next(j))) < COLLINEAR_TURN_DEG && offset(p(j), prev(j), next(j)) < COLLINEAR_OFFSET_M
            }.minByOrNull { j -> abs(turnDeg(prev(j), p(j), next(j))) }
            if (flat != null) { kept.removeAt(flat); continue }
            break
        }
        return kept
    }

    /** Giro (grados, con signo) al pasar por [b] viniendo de [a] y yendo a [c]; 0 si algún lado no tiene largo. */
    private fun turnDeg(a: Point, b: Point, c: Point): Double {
        val ux = b.x - a.x; val uy = b.y - a.y
        val vx = c.x - b.x; val vy = c.y - b.y
        if (hypot(ux, uy) < 1e-6 || hypot(vx, vy) < 1e-6) return 0.0
        return Math.toDegrees(atan2(ux * vy - uy * vx, ux * vx + uy * vy))
    }

    /** Área con signo (fórmula de Gauss) del polígono [v]: positiva en sentido antihorario. */
    private fun signedArea(v: List<Point>): Double =
        v.indices.sumOf { i -> val a = v[i]; val b = v[(i + 1) % v.size]; a.x * b.y - b.x * a.y } / 2

    /** Distancia de [p] al segmento [a]–[b]. */
    private fun offset(p: Point, a: Point, b: Point): Double {
        val dx = b.x - a.x; val dy = b.y - a.y
        val len2 = dx * dx + dy * dy
        if (len2 == 0.0) return Geometry.euclidean(p, a)
        val t = (((p.x - a.x) * dx + (p.y - a.y) * dy) / len2).coerceIn(0.0, 1.0)
        return Geometry.euclidean(p, Point(a.x + dx * t, a.y + dy * t))
    }

    /** Distancia de [p] a la recta (infinita) por [a] y [b]. */
    private fun lineDistance(p: Point, a: Point, b: Point): Double {
        val dx = b.x - a.x; val dy = b.y - a.y
        val len = hypot(dx, dy)
        if (len < 1e-6) return Geometry.euclidean(p, a)
        return abs((p.x - a.x) * dy - (p.y - a.y) * dx) / len
    }
}
