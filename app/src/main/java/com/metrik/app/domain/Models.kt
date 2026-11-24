package com.metrik.app.domain

import com.metrik.app.core.geometry.Point
import kotlinx.serialization.Serializable

/**
 * Tipo de medición, igual que en el backend. [IMPORTED] = medido sobre un plano a escala importado (CU-05): la app no
 * lo crea, pero lo recibe al abrir una medición del servidor. [shortLabel] es el texto de las listas.
 */
enum class SurveyMode(val api: String, val label: String, val shortLabel: String) {
    INTERIOR("interior", "Interior (odómetro)", "Interior"),
    EXTERIOR("exterior", "Exterior (GPS)", "Exterior (GPS)"),
    IMPORTED("imported", "Plano importado", "Plano importado"),
    ;

    /** Un ambiente con muros (interior o plano importado): admite acabados, wincha y plano de vivienda. */
    val isRoom: Boolean get() = this != EXTERIOR

    companion object {
        /** Modo según el texto de la API; uno desconocido se trata como interior (como antes). */
        fun fromApi(value: String): SurveyMode = entries.firstOrNull { it.api == value } ?: INTERIOR
    }
}

@Serializable
data class GpsSample(val lat: Double, val lon: Double, val accuracyM: Double, val timestampS: Double)

/** Puerta marcada al medir («Puerta aquí»): nombre del ambiente al que lleva y posición en el recorrido (m). */
@Serializable
data class DoorMark(val name: String, val x: Double, val y: Double)

/**
 * Metadatos de la medición. [doors] y [startDoor] sirven para unir los ambientes automáticamente
 * (CU-08): las puertas marcadas en un ambiente de referencia (el pasillo) y la puerta en la que empezó
 * este ambiente. Solo se guardan en el celular: al backend se envían los puntos al armar la planta.
 */
@Serializable
data class CaptureInfo(
    val odometry: String,
    val trackingLossS: Double,
    val durationS: Double,
    val transport: String? = null,
    val doors: List<DoorMark> = emptyList(),
    val startDoor: String? = null,
)

/** Recorrido guardado en el celular (se sincroniza con el backend cuando hay conexión, RF-17). */
data class LocalSurvey(
    val id: Long = 0,
    val projectId: Int,
    val name: String,
    val mode: SurveyMode,
    val points: List<Point> = emptyList(),
    val corners: List<Point>? = null,
    val gps: List<GpsSample>? = null,
    val capture: CaptureInfo? = null,
    val vertices: List<Point>,
    val areaM2: Double,
    val perimeterM: Double,
    val scaleDenominator: Int,
    val closed: Boolean = true,
    val remoteId: Int? = null,
    val createdAt: Long = System.currentTimeMillis(),
) {
    val synced: Boolean get() = remoteId != null
}
