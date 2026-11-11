package com.metrik.app.gps

import com.metrik.app.core.geometry.Point
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Pasos + brújula ajustados con GPS (navegación a pie).
 *
 * - **Forma:** cada paso avanza un largo de paso en la dirección de la brújula. Da una línea
 *   continua que sigue la caminata con detalle de un paso (el GPS solo, con 4–8 m de error, no puede).
 * - **Escala, giro y ubicación:** los pasos tienen errores sistemáticos (largo de paso mal
 *   calibrado, brújula desviada por metales). Se calcula la transformación de semejanza
 *   (escala, giro, traslación) que mejor lleva la figura de los pasos sobre TODAS las lecturas GPS
 *   (mínimos cuadrados ponderados por 1/precisión²) y se aplica a toda la figura.
 *
 * No se corrige punto por punto: eso, con el retraso del GPS, redondeaba las esquinas y achicaba
 * el área (probado: 23 % de error contra 17 % solo con pasos).
 *
 * **Sin saltos en pantalla:** el giro y la escala del ajuste se encienden recién cuando las lecturas GPS se
 * abren lo suficiente, y entonces cambian de golpe (hasta 20° y 25 %): todo el trazo saltaba 5–6 m. Lo que se
 * dibuja ([position], [track]) se acerca al ajuste de a poco ([maxRotationStepRad], [maxScaleStep] por
 * actualización); el control de rebotes usa siempre el ajuste completo y [settle] lo aplica entero al terminar.
 *
 * **Partida fija:** la partida es el centro de las lecturas quietas del inicio (el origen). El ajuste solo gira y
 * escala la figura de los pasos alrededor de ella, sin trasladarla: antes la traslación la corría metros y, al
 * volver a la partida, la figura no cerraba (29 m en una prueba real).
 *
 * Coordenadas locales ENU en metros: x = este, y = norte, origen en el punto de partida.
 */
class PdrGpsFusion(
    private val stepLengthM: Double,
    startAccuracyM: Double,
    /** La partida sale del promedio de la calibración: pesa como varias lecturas. */
    startWeightReadings: Double = 5.0,
    private val maxScaleError: Double = 0.25,
    private val maxRotationRad: Double = Math.toRadians(25.0),
    private val outlierSigmas: Double = 3.0,
    private val acceptAfterOutliers: Int = 5,
    private val maxRotationStepRad: Double = Math.toRadians(2.0),
    private val maxScaleStep: Double = 0.01,
    private val maxShiftStepM: Double = 0.5,
) {
    private data class Pair2(val pdr: Point, val gps: Point, val w: Double)

    private val raw = mutableListOf(Point(0.0, 0.0)) // figura de los pasos (sin ajustar)
    private val pairs = mutableListOf(Pair2(Point(0.0, 0.0), Point(0.0, 0.0), startWeightReadings / (startAccuracyM * startAccuracyM)))
    // Ajuste completo (objetivo) y el que se dibuja, que lo sigue de a poco
    private var targetScale = 1.0
    private var targetRotation = 0.0
    private var scale = 1.0
    private var rotation = 0.0
    private var tx = 0.0
    private var ty = 0.0
    // Centros ponderados de los pasos y del GPS: la traslación mantiene uno sobre el otro
    private var mpx = 0.0
    private var mpy = 0.0
    private var mqx = 0.0
    private var mqy = 0.0
    private var outliers = 0

    /** Posición actual ajustada. */
    val position: Point get() = transform(raw.last())

    /** Todo el recorrido con el ajuste más reciente (la figura entera se corrige junta). */
    fun track(): List<Point> = raw.map(::transform)

    /** Deja el trazo con el último ajuste completo (al terminar la medición). */
    fun settle() {
        rotation = targetRotation
        scale = targetScale
        updateTranslation()
    }

    /** Un paso en la dirección [headingRad] (0 = norte, π/2 = este). */
    fun onStep(headingRad: Double) {
        val last = raw.last()
        raw += Point(last.x + stepLengthM * sin(headingRad), last.y + stepLengthM * cos(headingRad))
        approachTarget()
    }

    /** Agrega una lectura GPS (en ENU) y reajusta. Devuelve false si se descartó por rebote. */
    fun onGps(gps: Point, accuracyM: Double): Boolean {
        val predicted = transform(raw.last(), targetRotation, targetScale)
        val tolerance = outlierSigmas * sqrt(accuracyM * accuracyM + 4.0) // + ~2 m de error propio de los pasos
        if (pairs.size >= 5 && hypot(gps.x - predicted.x, gps.y - predicted.y) > tolerance && ++outliers < acceptAfterOutliers) {
            return false
        }
        outliers = 0
        pairs += Pair2(raw.last(), gps, 1.0 / (accuracyM * accuracyM))
        refit()
        return true
    }

    private fun transform(p: Point): Point = transform(p, rotation, scale)

    /** Semejanza con [rot] y [sc] que lleva el centro de los pasos al centro del GPS. */
    private fun transform(p: Point, rot: Double, sc: Double): Point {
        val c = cos(rot)
        val s = sin(rot)
        val ox = mqx - sc * (c * mpx - s * mpy)
        val oy = mqy - sc * (s * mpx + c * mpy)
        return Point(sc * (c * p.x - s * p.y) + ox, sc * (s * p.x + c * p.y) + oy)
    }

    /** Lo que se dibuja avanza hacia el ajuste completo, con un tope por actualización (giro, escala y desplazamiento). */
    private fun approachTarget() {
        rotation += (targetRotation - rotation).coerceIn(-maxRotationStepRad, maxRotationStepRad)
        scale += (targetScale - scale).coerceIn(-maxScaleStep, maxScaleStep)
        val (wantX, wantY) = translationFor(rotation, scale)
        val dx = wantX - tx
        val dy = wantY - ty
        val d = hypot(dx, dy)
        val k = if (d > maxShiftStepM) maxShiftStepM / d else 1.0
        tx += dx * k
        ty += dy * k
    }

    private fun updateTranslation() {
        val (x, y) = translationFor(rotation, scale)
        tx = x
        ty = y
    }

    private fun translationFor(rot: Double, sc: Double): kotlin.Pair<Double, Double> {
        val c = cos(rot)
        val s = sin(rot)
        return (mqx - sc * (c * mpx - s * mpy)) to (mqy - sc * (s * mpx + c * mpy))
    }

    /** Semejanza de mínimos cuadrados ponderados (Umeyama 2D), con límites razonables. */
    private fun refit() {
        var w = 0.0; var mpx = 0.0; var mpy = 0.0; var mqx = 0.0; var mqy = 0.0 // centros (se guardan al final)
        for (p in pairs) { w += p.w; mpx += p.w * p.pdr.x; mpy += p.w * p.pdr.y; mqx += p.w * p.gps.x; mqy += p.w * p.gps.y }
        mpx /= w; mpy /= w; mqx /= w; mqy /= w
        var a = 0.0; var b = 0.0; var varP = 0.0; var varQ = 0.0; var meanAcc2 = 0.0
        for (p in pairs) {
            val px = p.pdr.x - mpx; val py = p.pdr.y - mpy
            val qx = p.gps.x - mqx; val qy = p.gps.y - mqy
            a += p.w * (px * qx + py * qy)
            b += p.w * (px * qy - py * qx)
            varP += p.w * (px * px + py * py)
            varQ += p.w * (qx * qx + qy * qy)
            meanAcc2 += p.w * (1.0 / p.w) // = Σ precisión² ponderada
        }
        meanAcc2 /= w
        // Solo se ajustan escala y giro cuando las lecturas GPS se abren bastante más que su error;
        // si no (recorrido corto), solo se ajusta la ubicación.
        val spreadEnough = varP > 0 && varQ / w > 4 * meanAcc2
        if (spreadEnough) {
            // Giro y escala alrededor de la partida (fija en el origen): sumas sin centrar
            var a0 = 0.0; var b0 = 0.0; var p0 = 0.0
            for (p in pairs) {
                a0 += p.w * (p.pdr.x * p.gps.x + p.pdr.y * p.gps.y)
                b0 += p.w * (p.pdr.x * p.gps.y - p.pdr.y * p.gps.x)
                p0 += p.w * (p.pdr.x * p.pdr.x + p.pdr.y * p.pdr.y)
            }
            targetRotation = atan2(b0, a0).coerceIn(-maxRotationRad, maxRotationRad)
            targetScale = if (p0 > 0) (hypot(a0, b0) / p0).coerceIn(1 - maxScaleError, 1 + maxScaleError) else 1.0
        } else {
            targetRotation = 0.0
            targetScale = 1.0
        }
        // Sin traslación: la partida queda en el origen
        this.mpx = 0.0; this.mpy = 0.0; this.mqx = 0.0; this.mqy = 0.0
        approachTarget()
    }
}
