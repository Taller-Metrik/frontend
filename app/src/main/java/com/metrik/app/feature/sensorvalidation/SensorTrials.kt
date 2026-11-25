package com.metrik.app.feature.sensorvalidation

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.io.File
import java.math.BigDecimal
import java.time.Instant

enum class TrialKind(val label: String, val csvName: String, val metric: String, val unit: String) {
    GPS("GPS", "GPS", "RMSE_HORIZONTAL", "m"),
    STEPS("Pasos", "PASOS", "ERROR_RELATIVO_ABSOLUTO_PASOS", "%"),
    TURN("Giro", "GIRO", "ERROR_ABSOLUTO_GIRO", "°"),
}

/**
 * Un ensayo completado. [reference], [observed] y [error] están en [unit] y sin redondear (el redondeo es solo de
 * pantalla). GPS: la «referencia» es la distancia esperada al punto conocido (0 m), lo observado es el RMSE y las
 * lecturas guardan coordenadas y distancias para recalcularlo.
 */
@Serializable
data class SensorTrial(
    val id: String,
    val createdAtMs: Long,
    val device: String,
    val appVersion: String,
    val kind: TrialKind,
    val durationS: Double,
    val reference: Double,
    val observed: Double,
    val error: Double,
    val unit: String,
    /** Proveedor GPS, detector de pasos o sensor de orientación usado. */
    val source: String,
    /** Configuración con la que se tomó el ensayo (para poder interpretarlo). */
    val config: String,
    val refLat: Double? = null,
    val refLon: Double? = null,
    val readings: List<GpsReading> = emptyList(),
    val discardedInvalid: Int = 0,
    val discardedDuplicate: Int = 0,
    val discardedStale: Int = 0,
    /** Solo giro: sentido de referencia elegido. */
    val turnSense: String? = null,
)

/** Los ensayos se guardan solo en el celular (archivo JSON propio de la app); no se sincronizan con el backend. */
interface SensorTrialStore {
    suspend fun all(): List<SensorTrial>
    suspend fun add(trial: SensorTrial)
    suspend fun delete(id: String)
}

class JsonSensorTrialStore(private val file: File) : SensorTrialStore {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val serializer = ListSerializer(SensorTrial.serializer())
    private val lock = Mutex()

    override suspend fun all(): List<SensorTrial> = lock.withLock { read() }

    override suspend fun add(trial: SensorTrial) = lock.withLock { write(read() + trial) }

    override suspend fun delete(id: String) = lock.withLock { write(read().filterNot { it.id == id }) }

    private suspend fun read(): List<SensorTrial> = withContext(Dispatchers.IO) {
        if (!file.exists()) emptyList()
        else runCatching { json.decodeFromString(serializer, file.readText()) }.getOrDefault(emptyList())
    }

    private suspend fun write(trials: List<SensorTrial>) = withContext(Dispatchers.IO) {
        file.parentFile?.mkdirs()
        val tmp = File(file.path + ".tmp")
        tmp.writeText(json.encodeToString(serializer, trials))
        if (!tmp.renameTo(file)) { file.writeText(tmp.readText()); tmp.delete() }
    }
}

/**
 * CSV de los ensayos, una sola tabla con la misma cabecera:
 *  - GPS: una fila `LECTURA` por lectura aceptada (coordenadas de referencia y observadas, tiempo, precisión del
 *    proveedor, distancia al punto) y una fila `RESUMEN` con el RMSE de la sesión.
 *  - Pasos y giro: una fila `RESUMEN` con referencia, observado, error y unidad.
 * Los números se escriben con toda su precisión (sin redondear) y con punto decimal, para recalcular las métricas.
 */
object SensorTrialCsv {
    val HEADER = listOf(
        "ensayo_id", "fecha_utc", "dispositivo", "version_app", "tipo", "metrica", "fila",
        "tiempo_epoch_s", "lat_ref", "lon_ref", "lat_obs", "lon_obs", "precision_proveedor_m", "distancia_m",
        "satelites", "cn0_dbhz", "edad_lectura_s",
        "valor_referencia", "valor_observado", "error", "unidad",
        "fuente", "configuracion", "sentido_giro",
        "lecturas_validas", "descartadas_invalidas", "descartadas_repetidas", "descartadas_obsoletas", "duracion_s",
    )

    fun build(trials: List<SensorTrial>): String {
        val sb = StringBuilder(HEADER.joinToString(",")).append("\r\n")
        for (t in trials) {
            if (t.kind == TrialKind.GPS) for (r in t.readings) sb.append(row(t, "LECTURA", reading = r)).append("\r\n")
            sb.append(row(t, "RESUMEN")).append("\r\n")
        }
        return sb.toString()
    }

    private fun row(t: SensorTrial, fila: String, reading: GpsReading? = null): String {
        val summary = reading == null
        val cells = listOf(
            t.id, Instant.ofEpochMilli(t.createdAtMs).toString(), t.device, t.appVersion, t.kind.csvName, t.kind.metric, fila,
            num(reading?.timestampS), num(t.refLat), num(t.refLon), num(reading?.lat), num(reading?.lon),
            num(reading?.accuracyM), num(reading?.distanceM),
            reading?.satellites?.toString().orEmpty(), num(reading?.cn0DbHz), num(reading?.ageS),
            if (summary) num(t.reference) else "", if (summary) num(t.observed) else "", if (summary) num(t.error) else "",
            if (summary) t.unit else "",
            t.source, t.config, t.turnSense.orEmpty(),
            if (summary && t.kind == TrialKind.GPS) t.readings.size.toString() else "",
            if (summary && t.kind == TrialKind.GPS) t.discardedInvalid.toString() else "",
            if (summary && t.kind == TrialKind.GPS) t.discardedDuplicate.toString() else "",
            if (summary && t.kind == TrialKind.GPS) t.discardedStale.toString() else "",
            if (summary) num(t.durationS) else "",
        )
        return cells.joinToString(",") { escape(it) }
    }

    /** Número con punto decimal y la precisión completa del Double (ida y vuelta exacta). */
    fun num(v: Double?): String = v?.let { BigDecimal.valueOf(it).stripTrailingZeros().toPlainString() }.orEmpty()

    private fun escape(s: String): String =
        if (s.any { it == ',' || it == '"' || it == '\n' || it == '\r' }) "\"" + s.replace("\"", "\"\"") + "\"" else s
}
