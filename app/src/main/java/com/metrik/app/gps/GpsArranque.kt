package com.metrik.app.gps

import com.metrik.app.core.geometry.Geometry
import com.metrik.app.core.geometry.Point
import com.metrik.app.domain.GpsSample

/**
 * Arranque del GPS al medir la precisión: decide desde qué lectura la posición ya es confiable.
 *
 * Al encender el GPS, el chip y el Fused Location Provider todavía convergen: las primeras lecturas salen
 * corridas 5–15 m y se van acercando solas durante 5–20 s, **aunque la precisión que informan ya sea de 3–4 m**
 * (o vienen de Wi-Fi o de la red celular, sin satélites). Antes se promediaban desde la primera y la partida
 * quedaba corrida: todo el recorrido se dibujaba torcido alrededor de ella.
 *
 * 1. **Calentamiento:** se ignoran los primeros [warmupS] segundos.
 * 2. **Solo GNSS con buena señal:** precisión ≤ [maxAccuracyM], con datos de satélites ([GpsPrecision.acceptsSignal]).
 * 3. **Convergencia:** la mediana de las lecturas de los últimos [windowS] s está a ≤ [driftM] de la mediana de los
 *    [windowS] s anteriores. Mirar solo la dispersión de una ventana no basta: una deriva lenta cabe en 2 m.
 * 4. **Tope:** si en [timeoutS] s no converge (bajo techo, persona moviéndose), se sigue igual con las lecturas que
 *    haya, aceptando las que no traen datos de satélites, y queda marcado como [Status.UNSTABLE].
 *
 * Los segundos se cuentan con la hora de las lecturas, no con su cantidad (el GPS puede entregar a 1 o 2 Hz).
 */
class GpsStartGate(
    private val warmupS: Double = 5.0,
    private val windowS: Double = 5.0,
    private val driftM: Double = 1.0,
    private val timeoutS: Double = 60.0,
    private val maxAccuracyM: Double = 6.0,
    /** false = sin calentamiento ni convergencia: toda lectura precisa cuenta desde la primera. */
    private val enabled: Boolean = true,
) {
    enum class Status { WARMING_UP, STABILIZING, STABLE, UNSTABLE }

    var status: Status = if (enabled) Status.WARMING_UP else Status.STABLE
        private set
    private var firstS: Double? = null
    private val window = ArrayDeque<GpsSample>()

    /** true si la posición ya converge (o se agotó el tope): las lecturas que siguen cuentan para la partida. */
    val ready: Boolean get() = status == Status.STABLE || status == Status.UNSTABLE

    /**
     * Ofrece una lectura (todas, incluso las imprecisas: marcan el paso del tiempo). Devuelve las lecturas que
     * cuentan para la partida: ninguna mientras calienta o converge; al converger, las de la última ventana
     * (ya son buenas, no se desperdician); después, la lectura misma si es válida.
     */
    fun offer(s: GpsSample): List<GpsSample> {
        val start = firstS ?: s.timestampS.also { firstS = it }
        val elapsed = s.timestampS - start
        if (ready) return if (valid(s, allowNoGnss = status == Status.UNSTABLE || !enabled)) listOf(s) else emptyList()
        if (elapsed >= timeoutS) {
            status = Status.UNSTABLE
            val kept = window.filter { it.timestampS > s.timestampS - windowS }
            window.clear()
            return kept + listOfNotNull(s.takeIf { valid(it, allowNoGnss = true) })
        }
        if (elapsed < warmupS || !valid(s, allowNoGnss = false)) return emptyList()
        status = Status.STABILIZING
        window += s
        while (window.first().timestampS < s.timestampS - 2 * windowS) window.removeFirst()
        val cut = s.timestampS - windowS
        val older = window.filter { it.timestampS <= cut }
        val newer = window.filter { it.timestampS > cut }
        if (older.size < MIN_PER_WINDOW || newer.size < MIN_PER_WINDOW) return emptyList()
        if (window.first().timestampS > s.timestampS - 2 * windowS + SPAN_TOLERANCE_S) return emptyList()
        val origin = window.first()
        if (Geometry.euclidean(median(older, origin), median(newer, origin)) > driftM) return emptyList()
        status = Status.STABLE
        window.clear()
        return newer
    }

    private fun valid(s: GpsSample, allowNoGnss: Boolean) =
        s.accuracyM <= maxAccuracyM && GpsPrecision.acceptsSignal(s) && (allowNoGnss || s.satellites != null)

    private fun median(samples: List<GpsSample>, origin: GpsSample): Point {
        val xy = GpsMath.toEnu(samples, origin.lat, origin.lon)
        return Point(mid(xy.map { it.x }), mid(xy.map { it.y }))
    }

    private fun mid(v: List<Double>): Double {
        val s = v.sorted()
        return if (s.size % 2 == 1) s[s.size / 2] else (s[s.size / 2 - 1] + s[s.size / 2]) / 2
    }

    companion object {
        private const val MIN_PER_WINDOW = 3
        /** La ventana doble debe cubrir casi 2 × [windowS] (a 1 Hz la primera lectura cae hasta 1 s después del borde). */
        private const val SPAN_TOLERANCE_S = 1.5

        /** Sin calentamiento ni convergencia (pruebas con lecturas sintéticas, sin datos de satélites). */
        fun off() = GpsStartGate(enabled = false)
    }
}

/** Partida fijada con las lecturas quietas: centro robusto (mediana + descarte 3σ) y su incertidumbre real. */
data class StartFix(val sample: GpsSample, val sigmaM: Double)

/** Partida robusta con [GpsPrecision.fixCorner]: un rebote no la arrastra y su σ no es la mejor precisión informada. */
fun robustStart(calibration: List<GpsSample>): StartFix? {
    if (calibration.isEmpty()) return null
    val o = calibration.first()
    val fix = GpsPrecision.fixCorner(
        GpsMath.toEnu(calibration, o.lat, o.lon),
        calibration.map { GpsPrecision.sigmaFromAccuracy(it.accuracyM) },
        calibration.last().timestampS - calibration.first().timestampS + 1,
    )
    val p = GpsMath.fromEnu(fix.point, o.lat, o.lon, fix.sigmaM * GpsPrecision.ACCURACY_TO_SIGMA, calibration.maxOf { it.timestampS })
    return StartFix(p, fix.sigmaM)
}
