package com.metrik.app.feature.gps

import com.metrik.app.core.geometry.Geometry
import com.metrik.app.core.geometry.Point

/**
 * Decide qué hacer al tocar «Esquina» (RF-18) para no ensuciar el final del recorrido: en el video del puente
 * (14/11) se marcaron las esquinas 8, 9 y 10 a pocos metros entre sí y de la partida, y el plano salió con lados
 * cortitos en zigzag. Función pura (sin estado) para poder probarla sola.
 *
 * Ningún toque se descarta en silencio: un lado de menos de [SHORT_SIDE_M] (el puente del profesor) o una esquina
 * junto a la partida se piden confirmar, y tocar otra vez en el mismo lugar la marca como **confirmada** (la limpieza
 * del final, [com.metrik.app.gps.GpsCornerCleanup], ya no la borra). El GPS (±3–4 m) no distingue un lado de 1,5 m:
 * la confirmación de la persona sí.
 */
object GpsCornerTap {
    enum class Decision {
        /** Se marca la esquina. */
        MARK,
        /** Se marca y queda confirmada por la persona: segundo toque en el lugar del aviso. */
        CONFIRMED,
        /** Ya se volvió a la partida: lo que corresponde es Finalizar, no otra esquina (salvo que se confirme). */
        AT_START,
        /** A menos de [SHORT_SIDE_M] de la última esquina: puede ser un lado corto real o un toque de más; se confirma. */
        SHORT_SIDE,
    }

    /** Radio mínimo de la partida: más cerca que esto, tocar «Esquina» con ≥ 2 esquinas es volver al inicio. */
    const val MIN_START_RADIUS_M = 5.0
    /** Tope del radio de la partida: con una calibración mala no se bloquean esquinas reales lejanas. */
    const val MAX_START_RADIUS_M = 10.0
    /** Un lado más corto que esto pide confirmar la esquina (antes se descartaba). */
    const val SHORT_SIDE_M = 2.0
    /** Un segundo toque a menos de esto del aviso es «en el mismo lugar»: confirma la esquina. */
    const val CONFIRM_RADIUS_M = 2.0

    /** Radio de la partida: el mayor entre [MIN_START_RADIUS_M] y el error al 95 % de la calibración (con tope). */
    fun startRadiusM(calibrationR95M: Double?): Double =
        maxOf(MIN_START_RADIUS_M, minOf(calibrationR95M ?: 0.0, MAX_START_RADIUS_M))

    /**
     * @param position posición estimada (pasos) al tocar.
     * @param start partida.
     * @param lastCorner dónde se marcó la última esquina (null si ninguna).
     * @param cornersFixed esquinas ya marcadas.
     * @param warnedAt dónde se avisó la vez anterior («lado corto» o «estás en la partida»): si se vuelve a tocar ahí
     *   mismo, la persona confirma que es una esquina real y se marca como confirmada.
     */
    fun decide(
        position: Point,
        start: Point,
        lastCorner: Point?,
        cornersFixed: Int,
        startRadiusM: Double,
        warnedAt: Point? = null,
    ): Decision {
        if (warnedAt != null && Geometry.euclidean(position, warnedAt) < CONFIRM_RADIUS_M) return Decision.CONFIRMED
        if (lastCorner != null && Geometry.euclidean(position, lastCorner) < SHORT_SIDE_M) return Decision.SHORT_SIDE
        val nearStart = cornersFixed >= 2 && Geometry.euclidean(position, start) < startRadiusM
        return if (nearStart) Decision.AT_START else Decision.MARK
    }
}
