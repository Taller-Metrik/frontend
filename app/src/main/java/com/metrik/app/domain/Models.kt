package com.metrik.app.domain

import com.metrik.app.core.geometry.Point
import kotlinx.serialization.Serializable

enum class SurveyMode(val api: String, val label: String) {
    INTERIOR("interior", "Interior (odómetro)"),
    EXTERIOR("exterior", "Exterior (GPS)"),
}

/**
 * Lectura GPS. [satellites] y [cn0DbHz] describen la calidad de la señal (RF-20); [vertexId] marca las
 * lecturas tomadas quieto sobre una esquina (stop-and-go, RF-18). Los tres son opcionales: los
 * recorridos guardados antes de esta versión se siguen leyendo igual.
 */
@Serializable
data class GpsSample(
    val lat: Double,
    val lon: Double,
    val accuracyM: Double,
    val timestampS: Double,
    val satellites: Int? = null,
    val cn0DbHz: Double? = null,
    val vertexId: Int? = null,
)

@Serializable
data class CaptureInfo(val odometry: String, val trackingLossS: Double, val durationS: Double, val transport: String? = null)

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
