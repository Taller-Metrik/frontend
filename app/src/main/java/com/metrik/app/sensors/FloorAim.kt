package com.metrik.app.sensors

import com.metrik.app.core.geometry.Point
import kotlin.math.floor
import kotlin.math.sqrt

/**
 * Altura del piso (eje Y de ARCore, vertical con la gravedad) sin depender de que ARCore
 * declare un "plano": en pisos lisos o brillantes (cerámica, porcelanato, pasillos) casi nunca lo hace.
 *
 * Fuentes, de mejor a peor:
 * 1. Plano horizontal de ARCore (el de mayor área, por si detecta también una mesa).
 * 2. Nube de puntos 3D que ARCore usa para el seguimiento: el piso es la altura donde más puntos
 *    se acumulan entre [minBelowCameraM] y [maxBelowCameraM] por debajo del celular.
 */
class FloorEstimator(
    private val minBelowCameraM: Double = 0.5,
    private val maxBelowCameraM: Double = 2.2,
    private val binM: Double = 0.03,
    private val minPoints: Int = 40,
    private val maxTotal: Int = 6000,
) {
    private val bins = HashMap<Int, Int>()
    private var total = 0
    private var planeY: Double? = null
    private var assumedY: Double? = null

    /** Altura del piso, o null si todavía no hay datos suficientes. */
    val floorY: Double? get() = planeY ?: densestLevel() ?: assumedY

    /** De dónde sale la altura del piso (para el diagnóstico en pantalla). */
    val source: String get() = when {
        planeY != null -> "plano"
        densestLevel() != null -> "puntos"
        assumedY != null -> "estimado"
        else -> "buscando"
    }

    /**
     * Respaldo para pisos sin textura (tierra lisa, mayólica de un color): si ARCore no da puntos ni
     * plano, se asume el piso a [heightM] bajo el celular (altura típica en la mano). Un plano o
     * puntos reales lo reemplazan apenas aparecen.
     */
    fun assume(cameraY: Double, heightM: Double = DEFAULT_PHONE_HEIGHT_M) {
        if (assumedY == null) assumedY = cameraY - heightM
    }

    companion object { const val DEFAULT_PHONE_HEIGHT_M = 1.30 }

    private fun inRange(y: Double, cameraY: Double) = (cameraY - y) in minBelowCameraM..maxBelowCameraM

    /** Planos horizontales visibles en este momento: (altura, área en m²). */
    fun onPlanes(planes: List<Pair<Double, Double>>, cameraY: Double) {
        planes.filter { inRange(it.first, cameraY) }.maxByOrNull { it.second }?.let { planeY = it.first }
    }

    fun onPoint(y: Double, cameraY: Double) {
        if (!inRange(y, cameraY)) return
        val key = floor(y / binM).toInt()
        bins[key] = (bins[key] ?: 0) + 1
        if (++total > maxTotal) decay()
    }

    /** Olvida de a poco los puntos viejos (por si la altura de ARCore deriva en un recorrido largo). */
    private fun decay() {
        val it = bins.entries.iterator()
        total = 0
        while (it.hasNext()) {
            val e = it.next()
            e.setValue(e.value / 2)
            if (e.value == 0) it.remove() else total += e.value
        }
    }

    private fun densestLevel(): Double? {
        // Se suman los vecinos: un piso real puede caer entre dos intervalos de 3 cm
        fun around(k: Int) = (bins[k] ?: 0) + (bins[k - 1] ?: 0) + (bins[k + 1] ?: 0)
        val best = bins.keys.maxByOrNull(::around) ?: return null
        val count = around(best)
        if (count < minPoints) return null
        // Promedio ponderado de los tres intervalos
        var sum = 0.0
        for (k in best - 1..best + 1) sum += (bins[k] ?: 0) * (k + 0.5) * binM
        return sum / count
    }

    fun reset() { bins.clear(); total = 0; planeY = null; assumedY = null }
}

/** Mira en el centro de la pantalla: dónde toca el piso el rayo de la cámara. */
object FloorAim {
    /**
     * Intersección del rayo (origen [ox],[oy],[oz]; dirección [dx],[dy],[dz]) con el piso horizontal
     * a la altura [floorY]. Devuelve el punto en el plano del dibujo (x, −z), o null si la cámara
     * no apunta hacia abajo al menos ~10° o si el punto queda a más de [maxDistanceM] (muy impreciso).
     */
    fun intersect(
        ox: Double, oy: Double, oz: Double,
        dx: Double, dy: Double, dz: Double,
        floorY: Double,
        maxDistanceM: Double = 4.0,
        minDownward: Double = 0.17,
    ): Point? {
        val norm = sqrt(dx * dx + dy * dy + dz * dz)
        if (norm == 0.0) return null
        val ux = dx / norm
        val uy = dy / norm
        val uz = dz / norm
        if (uy > -minDownward) return null
        val t = (floorY - oy) / uy
        if (t <= 0 || t > maxDistanceM) return null
        return Point(ox + ux * t, -(oz + uz * t))
    }
}
