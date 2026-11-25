package com.metrik.app.feature.sensorvalidation

import com.metrik.app.domain.GpsSample
import com.metrik.app.gps.GpsMath
import kotlinx.serialization.Serializable
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Métricas de la «Validación de sensores» (sección 5 del informe técnico). Son funciones puras: el ViewModel solo las
 * llama con lo que capturan los sensores de la app, y los valores de ejemplo viven únicamente en las pruebas.
 */
object SensorMetrics {
    /** RMSE = raíz del promedio de las distancias al cuadrado. Null si no hay distancias. */
    fun rmse(distancesM: List<Double>): Double? =
        if (distancesM.isEmpty()) null else sqrt(distancesM.sumOf { it * it } / distancesM.size)

    /** Error relativo absoluto del conteo de pasos, en %: |detectados − referencia| / referencia · 100. */
    fun stepErrorPct(referenceSteps: Int, detectedSteps: Int): Double {
        require(referenceSteps > 0) { "Los pasos de referencia deben ser un entero positivo" }
        return abs(detectedSteps - referenceSteps).toDouble() / referenceSteps * 100.0
    }

    /** Error absoluto del giro (°) entre el estimado y el de referencia, ambos con signo (+ horario, − antihorario). */
    fun turnErrorDeg(estimatedDeg: Double, referenceSignedDeg: Double): Double = abs(estimatedDeg - referenceSignedDeg)

    /**
     * Giro de referencia con signo. Convención (la misma de [com.metrik.app.sensors.TurnTracker], que usa el azimut de
     * Android): **positivo = giro a la derecha (horario visto desde arriba)**, negativo = a la izquierda.
     */
    fun signedReference(angleDeg: Double, sense: TurnSense): Double = if (sense == TurnSense.RIGHT) angleDeg else -angleDeg

    /** Pasos de referencia: solo enteros positivos (sin signo, decimales ni letras). */
    fun parseSteps(text: String): Int? = text.trim().takeIf { it.isNotEmpty() && it.all(Char::isDigit) }?.toIntOrNull()?.takeIf { it > 0 }

    /** Ángulo de referencia: mayor que 0 y hasta 360°. */
    fun parseAngle(text: String): Double? = parseNumber(text)?.takeIf { it > 0.0 && it <= 360.0 }

    /** Coordenada decimal dentro de [min, max]; acepta coma decimal. */
    fun parseCoordinate(text: String, min: Double, max: Double): Double? = parseNumber(text)?.takeIf { it in min..max }

    /** Acepta punto o coma decimal, y normaliza signos «menos» y espacios raros que meten algunos teclados. */
    private fun parseNumber(text: String): Double? =
        text.map { c ->
            when (c) {
                ',', '٫', '，' -> '.'
                '−', '–', '—', '‒', '➖', '﹣', '－' -> '-'
                else -> c
            }
        }.filterNot { it.isWhitespace() || it == ' ' || it == '‎' || it == '‏' || it == '⁠' || it == '﻿' }
            .joinToString("").toDoubleOrNull()?.takeIf { it.isFinite() }
}

enum class TurnSense(val label: String) {
    RIGHT("Derecha (horario, +)"),
    LEFT("Izquierda (antihorario, −)"),
}

/** Una lectura GPS aceptada en el ensayo, con su distancia al punto de referencia. */
@Serializable
data class GpsReading(
    val timestampS: Double,
    val lat: Double,
    val lon: Double,
    /** Precisión que informa el proveedor (dato auxiliar: la distancia real sale de las coordenadas). */
    val accuracyM: Double,
    val distanceM: Double,
    val satellites: Int? = null,
    val cn0DbHz: Double? = null,
    val ageS: Double? = null,
)

/**
 * Recibe las lecturas del proveedor GPS de MetriK **antes** de `GpsTrackFilter` (sin filtro de precisión ni de
 * movimiento) y decide cuáles entran al ensayo:
 *  - **inválida**: latitud/longitud no finitas o fuera de rango, el punto exacto (0, 0), precisión no positiva o
 *    no finita (Android informa 0 cuando no hay precisión), o marca de tiempo no válida;
 *  - **repetida**: la misma marca de tiempo que una lectura ya aceptada;
 *  - **obsoleta**: llegó con más de [MAX_AGE_S] de antigüedad, se tomó antes de empezar el ensayo (con
 *    [START_TOLERANCE_S] de margen por el desfase de relojes) o es anterior a la última aceptada (fuera de orden).
 * Se evalúa en ese orden. Las descartadas solo se cuentan.
 */
class GpsTrialCollector(
    val refLat: Double,
    val refLon: Double,
    private val startedAtEpochS: Double,
) {
    enum class Outcome { ACCEPTED, INVALID, DUPLICATE, STALE }

    private val accepted = mutableListOf<GpsReading>()
    private val seen = HashSet<Double>()
    private var lastTimestampS = Double.NEGATIVE_INFINITY

    val readings: List<GpsReading> get() = accepted
    var invalid = 0; private set
    var duplicates = 0; private set
    var stale = 0; private set
    val discarded: Int get() = invalid + duplicates + stale

    fun offer(s: GpsSample): Outcome {
        if (!isValid(s)) { invalid++; return Outcome.INVALID }
        if (s.timestampS in seen) { duplicates++; return Outcome.DUPLICATE }
        val tooOld = s.ageS?.let { it > MAX_AGE_S } == true
        if (tooOld || s.timestampS < startedAtEpochS - START_TOLERANCE_S || s.timestampS < lastTimestampS) {
            stale++; return Outcome.STALE
        }
        seen += s.timestampS
        lastTimestampS = s.timestampS
        accepted += GpsReading(
            timestampS = s.timestampS, lat = s.lat, lon = s.lon, accuracyM = s.accuracyM,
            distanceM = GpsMath.haversine(refLat, refLon, s.lat, s.lon),
            satellites = s.satellites, cn0DbHz = s.cn0DbHz, ageS = s.ageS,
        )
        return Outcome.ACCEPTED
    }

    fun rmse(): Double? = SensorMetrics.rmse(accepted.map { it.distanceM })

    private fun isValid(s: GpsSample): Boolean =
        s.lat.isFinite() && s.lon.isFinite() && s.lat in -90.0..90.0 && s.lon in -180.0..180.0 &&
            !(s.lat == 0.0 && s.lon == 0.0) &&
            s.accuracyM.isFinite() && s.accuracyM > 0.0 &&
            s.timestampS.isFinite() && s.timestampS > 0.0

    companion object {
        const val MAX_AGE_S = 2.0
        const val START_TOLERANCE_S = 2.0
    }
}
