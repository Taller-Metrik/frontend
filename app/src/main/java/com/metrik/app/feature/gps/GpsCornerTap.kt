package com.metrik.app.feature.gps

import com.metrik.app.core.geometry.Geometry
import com.metrik.app.core.geometry.Point

/**
 * Decide qué hacer al tocar «Esquina» (RF-18) para no ensuciar el final del recorrido: en el video del puente
 * (14/11) se marcaron las esquinas 8, 9 y 10 a pocos metros entre sí y de la partida, y el plano salió con lados
 * cortitos en zigzag. Función pura (sin estado) para poder probarla sola.
 */
object GpsCornerTap {
    enum class Decision {
        /** Se marca la esquina. */
        MARK,
        /** Ya se volvió a la partida: lo que corresponde es Finalizar, no otra esquina. */
        AT_START,
        /** A menos de [DOUBLE_TAP_M] de la última esquina: es un toque repetido, no una esquina nueva. */
        DOUBLE_TAP,
    }

    /** Radio mínimo de la partida: más cerca que esto, tocar «Esquina» con ≥ 2 esquinas es volver al inicio. */
    const val MIN_START_RADIUS_M = 5.0
    /** Tope del radio de la partida: con una calibración mala no se bloquean esquinas reales lejanas. */
    const val MAX_START_RADIUS_M = 10.0
    /** Dos toques a menos de esto son la misma esquina. */
    const val DOUBLE_TAP_M = 2.0

    /** Radio de la partida: el mayor entre [MIN_START_RADIUS_M] y el error al 95 % de la calibración (con tope). */
    fun startRadiusM(calibrationR95M: Double?): Double =
        maxOf(MIN_START_RADIUS_M, minOf(calibrationR95M ?: 0.0, MAX_START_RADIUS_M))

    /**
     * @param position posición estimada (pasos) al tocar.
     * @param start partida.
     * @param lastCorner dónde se marcó la última esquina (null si ninguna).
     * @param cornersFixed esquinas ya marcadas.
     * @param warnedAt dónde se avisó «estás en la partida» la vez anterior: si se vuelve a tocar ahí mismo, la
     *   persona confirma que es una esquina real (p. ej. el lado corto de una franja angosta) y se marca.
     */
    fun decide(
        position: Point,
        start: Point,
        lastCorner: Point?,
        cornersFixed: Int,
        startRadiusM: Double,
        warnedAt: Point? = null,
    ): Decision {
        if (lastCorner != null && Geometry.euclidean(position, lastCorner) < DOUBLE_TAP_M) return Decision.DOUBLE_TAP
        val nearStart = cornersFixed >= 2 && Geometry.euclidean(position, start) < startRadiusM
        val confirmed = warnedAt != null && Geometry.euclidean(position, warnedAt) < DOUBLE_TAP_M
        return if (nearStart && !confirmed) Decision.AT_START else Decision.MARK
    }
}
