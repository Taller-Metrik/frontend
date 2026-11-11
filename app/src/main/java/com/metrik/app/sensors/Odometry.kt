package com.metrik.app.sensors

import com.metrik.app.core.geometry.Geometry
import com.metrik.app.core.geometry.Point
import com.metrik.app.core.geometry.Smoothing
import kotlinx.coroutines.flow.Flow

/** Una lectura del odómetro: posición sobre el piso (o null si no hay seguimiento). */
data class OdometrySample(
    val position: Point?,
    val timestampNs: Long,
    val message: String? = null,
    /**
     * Posición del celular (la persona) cuando [position] es el punto de la mira en el piso.
     * La quietud se juzga con ella: inclinar la mano estando parado mueve la mira, pero no es caminar.
     */
    val device: Point? = null,
)

/** Fuente de posiciones: ARCore o pasos (acelerómetro + giroscopio). */
interface OdometrySource {
    /** "arcore" | "steps" (se envía al backend como variable del modelo ML). */
    val kind: String
    val samples: Flow<OdometrySample>
}

data class Wall(val lengthM: Double, val turnRawDeg: Double?, val turnSnappedDeg: Double?)

/**
 * Lógica pura del odómetro (como una rueda de medición): acumula la distancia de cada tramo,
 * ignora el temblor y registra el tiempo sin seguimiento. Ver docs/03-algoritmos.md §1.
 */
class OdometerAccumulator(
    private val jitterM: Double = 0.005,
    private val maxJumpM: Double = 1.0,
    private val maxSpeedMps: Double = 2.5, // nadie mide caminando a más de 9 km/h: más rápido es un salto del seguimiento
    /**
     * Detección de quietud (ZUPT, como en los odómetros de pasos): si en la última ventana el
     * avance NETO es menor que [minSpeedMps]·ventana, la persona está quieta y no se suma nada.
     * Una zona muerta sola no basta: el balanceo del cuerpo va y viene, la cruza una y otra vez
     * y cada cruce sumaba distancia. 0 = desactivada.
     */
    private val stillWindowNs: Long = 0L,
    private val minSpeedMps: Double = 0.15,
) {
    private val window = ArrayDeque<Pair<Long, Point>>()
    val track = mutableListOf<Point>()
    var totalM = 0.0; private set
    var segmentM = 0.0; private set
    var trackingLossS = 0.0; private set
    var jumps = 0; private set
    private var lostSinceNs: Long? = null
    private var lastNs: Long? = null

    /**
     * Corrección acumulada por los saltos del seguimiento: cuando ARCore se relocaliza, el trazo
     * se "reengancha" en el último punto bueno (como una rueda que no se levanta del piso) en vez
     * de dibujar una línea que cruza el plano.
     */
    private var offset = Point(0.0, 0.0)

    /** Posición cruda del sensor ya corregida por los saltos anteriores. */
    fun corrected(raw: Point?): Point? = raw?.let { it + offset }

    /** Devuelve true si el punto se agregó al trazo. */
    fun onSample(sample: OdometrySample): Boolean {
        val p = corrected(sample.position)
        if (p == null) {
            if (lostSinceNs == null) lostSinceNs = sample.timestampNs
            return false
        }
        val resuming = lostSinceNs != null
        lostSinceNs?.let { trackingLossS += (sample.timestampNs - it) / 1e9 }
        lostSinceNs = null
        val last = track.lastOrNull()
        if (last == null) { track += p; lastNs = sample.timestampNs; return true }
        val d = Geometry.euclidean(last, p)
        if (resuming) {
            // Al recuperar el seguimiento la pose de ARCore suele volver corrida (deriva o relocalización).
            // Como una rueda que se levantó del piso: se reengancha en el último punto bueno sin sumar
            // nada. Antes, la deriva acumulada durante la pérdida se contaba como distancia caminada.
            if (d >= jitterM) { jumps++; offset = offset + (last - p) }
            lastNs = sample.timestampNs
            window.clear()
            return false
        }
        // Quieto: se ignora el temblor, pero si la mira avanzó a propósito (≥ 30 cm, p. ej. parado
        // en la esquina recorriendo la pared con la mira) sí cuenta, como una rueda empujada.
        if (stillWindowNs > 0 && isStill(sample.timestampNs, sample.device ?: p) && d < STILL_OVERRIDE_M) return false
        if (d < jitterM) return false
        val dt = lastNs?.let { (sample.timestampNs - it) / 1e9 } ?: 0.0
        lastNs = sample.timestampNs
        val tooFast = dt > 0 && d / dt > maxSpeedMps
        if (d > maxJumpM || tooFast) {
            // Relocalización de ARCore: no suma distancia ni agrega el punto; se reengancha el trazo
            jumps++
            offset = offset + (last - p)
            // La ventana de quietud NO se borra: guarda la posición del cuerpo, que el salto de la mira
            // no afecta. Antes se borraba y durante 1 s se creía "quieto" aunque la persona caminara.
            return false
        }
        totalM += d
        segmentM += d
        track += p
        return true
    }

    /** Quieto = el avance neto en la ventana es menor que lo que recorrería a [minSpeedMps]. */
    private fun isStill(nowNs: Long, p: Point): Boolean {
        window.addLast(nowNs to p)
        while (window.size > 1 && nowNs - window.first().first > stillWindowNs) window.removeFirst()
        val net = Geometry.euclidean(window.first().second, p)
        return net < minSpeedMps * (stillWindowNs / 1e9)
    }

    companion object {
        private const val STILL_OVERRIDE_M = 0.30

        /**
         * Odómetro para caminar con el celular en la mano: zona muerta de 3 cm para el ruido de
         * ARCore y quietud si en 1 s no se avanzó al menos 15 cm (caminar despacio es ≥ 0,3 m/s).
         */
        fun walking() = OdometerAccumulator(jitterM = 0.03, stillWindowNs = 1_000_000_000L, minSpeedMps = 0.15)
    }

    /** Cierra el tramo actual (muro) y devuelve su longitud. */
    fun closeSegment(): Double = segmentM.also { segmentM = 0.0 }

    fun reset() {
        track.clear(); totalM = 0.0; segmentM = 0.0; trackingLossS = 0.0; jumps = 0; lostSinceNs = null; lastNs = null; offset = Point(0.0, 0.0)
        window.clear()
    }
}

/**
 * Compuerta de estabilidad de ARCore: tras empezar (o recuperar) el seguimiento, la pose salta
 * mientras ARCore arma su mapa. Solo se usan las posiciones después de [warmupNs] de seguimiento
 * continuo; antes de eso el odómetro está "calibrando" y no suma nada.
 */
class TrackingGate(
    private val warmupNs: Long = 1_500_000_000L,
    /** Cortes del seguimiento más cortos que esto no reinician la espera (antes cualquier frame suelto la reiniciaba y se quedaba trabado). */
    private val toleranceNs: Long = 300_000_000L,
) {
    private var trackingSinceNs: Long? = null
    private var lastTrackingNs: Long? = null

    /** Devuelve true si la pose de este frame ya es confiable. */
    fun onFrame(tracking: Boolean, nowNs: Long): Boolean {
        if (!tracking) {
            val last = lastTrackingNs
            if (last == null || nowNs - last > toleranceNs) trackingSinceNs = null
            return false
        }
        lastTrackingNs = nowNs
        val since = trackingSinceNs ?: nowNs.also { trackingSinceNs = it }
        return nowNs - since >= warmupNs
    }
}

/**
 * Evita que los avisos parpadeen: un mensaje nuevo (o su desaparición) solo se muestra si se
 * mantiene al menos [holdNs]. ARCore cambia de estado por frames sueltos y antes cada cambio
 * se mostraba al instante.
 */
class MessageDebouncer(private val holdNs: Long = 700_000_000L) {
    var shown: String? = null; private set
    private var candidate: String? = null
    private var candidateSinceNs = 0L

    fun onMessage(message: String?, nowNs: Long): String? {
        if (message == shown) { candidate = shown; return shown }
        if (message != candidate) { candidate = message; candidateSinceNs = nowNs }
        if (nowNs - candidateSinceNs >= holdNs) shown = message
        return shown
    }
}

/**
 * Giro entre esquinas a partir del rumbo del giroscopio (RF-02).
 * El rumbo se desenrolla para que los cruces de ±180° no generen saltos.
 */
class TurnTracker(private val toleranceDeg: Double = 15.0) {
    private var unwrapped: Double? = null
    private var lastRaw: Double? = null
    private var atLastCorner: Double? = null

    val headingDeg: Double? get() = unwrapped

    fun onHeading(rawDeg: Double) {
        val prev = lastRaw
        val current = unwrapped
        unwrapped = if (prev == null || current == null) rawDeg else {
            var delta = rawDeg - prev
            while (delta > 180) delta -= 360
            while (delta < -180) delta += 360
            current + delta
        }
        lastRaw = rawDeg
        if (atLastCorner == null) atLastCorner = unwrapped
    }

    /** Giro desde la esquina anterior: (crudo, ajustado a 90°). Null si no hay giroscopio. */
    fun corner(): Pair<Double, Double>? {
        val now = unwrapped ?: return null
        val raw = now - (atLastCorner ?: now)
        atLastCorner = now
        return raw to Smoothing.snapTurnAngle(raw, toleranceDeg)
    }

    fun reset() { unwrapped = null; lastRaw = null; atLastCorner = null }
}

/**
 * Detector de pasos por picos del acelerómetro (odómetro de respaldo sin ARCore).
 * Filtro exponencial del módulo, umbral sobre la gravedad e intervalo mínimo entre pasos.
 */
class StepDetector(
    private val thresholdMs2: Double = 0.8, // calibrado con 13 recorridos reales (docs/05 §8)
    private val minIntervalNs: Long = 250_000_000L,
    private val alpha: Double = 0.25,
) {
    private var filtered = GRAVITY
    private var above = false
    private var lastStepNs = Long.MIN_VALUE / 2
    var steps = 0; private set

    /** Devuelve true si la muestra completa un paso. */
    fun onAcceleration(x: Double, y: Double, z: Double, timestampNs: Long): Boolean {
        val magnitude = kotlin.math.sqrt(x * x + y * y + z * z)
        filtered += alpha * (magnitude - filtered)
        val isAbove = filtered - GRAVITY > thresholdMs2
        val step = isAbove && !above && timestampNs - lastStepNs >= minIntervalNs
        above = isAbove
        if (step) { steps++; lastStepNs = timestampNs }
        return step
    }

    companion object { const val GRAVITY = 9.80665 }
}

/**
 * Filtro de pasos por racha. El [StepDetector] cuenta como paso cualquier pico de aceleración, y con el
 * celular en la mano un gesto (mirar la pantalla, girar la muñeca) ya lo produce: quieto, el recorrido
 * avanzaba solo. Aquí un paso solo cuenta si forma parte de una racha de [confirmSteps] pasos seguidos,
 * sin pausas mayores que [maxGapNs] (caminar normal: un paso cada 0,4–0,7 s). Los pasos de la racha se
 * guardan y se entregan juntos al confirmarla, así una caminata real no pierde sus primeros pasos.
 */
class WalkingGate(
    private val confirmSteps: Int = 3,
    private val maxGapNs: Long = 1_500_000_000L,
) {
    private val pending = mutableListOf<Double>()
    private var lastNs: Long? = null
    private var walking = false

    init { require(confirmSteps >= 1) { "confirmSteps debe ser al menos 1" } }

    /** Registra un paso con su rumbo; devuelve los rumbos de los pasos que ya se pueden aplicar (0, 1 o varios). */
    fun onStep(timestampNs: Long, headingRad: Double): List<Double> {
        val continues = lastNs?.let { timestampNs - it <= maxGapNs } == true
        lastNs = timestampNs
        if (!continues) { walking = false; pending.clear() }
        if (walking) return listOf(headingRad)
        pending += headingRad
        if (pending.size < confirmSteps) return emptyList()
        walking = true
        return pending.toList().also { pending.clear() }
    }
}
