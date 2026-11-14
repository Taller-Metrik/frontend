package com.metrik.app.gps

import android.annotation.SuppressLint
import android.content.Context
import android.location.GnssStatus
import android.location.LocationManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import java.util.concurrent.atomic.AtomicReference
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.metrik.app.core.geometry.Geometry
import com.metrik.app.core.geometry.GeometryException
import com.metrik.app.core.geometry.Point
import com.metrik.app.core.geometry.PolygonAnalysis
import com.metrik.app.core.geometry.Smoothing
import com.metrik.app.domain.GpsSample
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.shareIn
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.math.sqrt

/** Lecturas GPS del Fused Location Provider (GNSS + Wi-Fi + red celular). */
interface LocationSource {
    val samples: Flow<GpsSample>
}

/**
 * El GPS se comparte en [scope] y sigue encendido [keepAliveMs] después de que se va el último recolector: así no se
 * apaga entre la medición de precisión y el recorrido. Antes cada fase pedía el GPS de nuevo y el recorrido
 * empezaba con otro arranque del receptor (las primeras lecturas corridas varios metros).
 */
class FusedLocationSource(context: Context, scope: CoroutineScope, keepAliveMs: Long = KEEP_ALIVE_MS) : LocationSource {
    private val client = LocationServices.getFusedLocationProviderClient(context)
    private val locationManager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager

    override val samples: Flow<GpsSample> by lazy {
        updates().shareIn(scope, SharingStarted.WhileSubscribed(stopTimeoutMillis = keepAliveMs))
    }

    @SuppressLint("MissingPermission") // La pantalla solicita el permiso antes de recolectar
    private fun updates(): Flow<GpsSample> = callbackFlow {
        // Un callback GNSS por suscripción al GPS (con el flujo compartido hay una sola mientras siga encendido)
        val latest = AtomicReference<Pair<GnssQuality, Long>?>(null) // calidad y cuándo llegó (elapsedRealtime)
        val gnssCallback = object : GnssStatus.Callback() {
            override fun onSatelliteStatusChanged(status: GnssStatus) {
                val usedCn0 = (0 until status.satelliteCount).filter(status::usedInFix).map { status.getCn0DbHz(it).toDouble() }
                latest.set(GnssQuality.fromUsedCn0(usedCn0) to SystemClock.elapsedRealtime())
            }
        }
        // Solo ubicaciones nuevas: sin la última guardada (puede ser de minutos atrás) y esperando la primera precisa
        val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 1000L)
            .setMinUpdateIntervalMillis(500L)
            .setMaxUpdateAgeMillis(0L)
            .setWaitForAccurateLocation(true)
            .build()
        val callback = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                // Solo se adjunta la calidad GNSS si es reciente: si la posición vino de Wi-Fi o de la
                // red celular (sin GNSS), no hay datos de satélites y se envían nulos
                val q = latest.get()?.takeIf { SystemClock.elapsedRealtime() - it.second < GNSS_STATUS_MAX_AGE_MS }?.first
                for (l in result.locations) {
                    // Una lectura atrasada (llegó tarde o en lote) ya no corresponde a donde está la persona
                    val ageNs = SystemClock.elapsedRealtimeNanos() - l.elapsedRealtimeNanos
                    if (ageNs > MAX_FIX_AGE_NS) continue
                    trySend(
                        GpsSample(
                            l.latitude, l.longitude, l.accuracy.toDouble(), l.time / 1000.0,
                            satellites = q?.satellites, cn0DbHz = q?.cn0DbHz,
                            speedMps = if (l.hasSpeed()) l.speed.toDouble() else null,
                            ageS = maxOf(0L, ageNs) / 1e9,
                        ),
                    )
                }
            }
        }
        // Si no se puede leer el estado GNSS, la ubicación sigue funcionando (sin datos de señal)
        val gnssRegistered = runCatching {
            locationManager.registerGnssStatusCallback(gnssCallback, Handler(Looper.getMainLooper()))
        }.getOrDefault(false)
        client.requestLocationUpdates(request, callback, Looper.getMainLooper())
        awaitClose {
            client.removeLocationUpdates(callback)
            if (gnssRegistered) locationManager.unregisterGnssStatusCallback(gnssCallback)
        }
    }

    private companion object {
        const val GNSS_STATUS_MAX_AGE_MS = 5_000L
        /** El GPS sigue encendido 3 min sin recolectores (pantalla «Recorrer» tras medir la precisión). */
        const val KEEP_ALIVE_MS = 180_000L
        /** Antigüedad máxima de una lectura al llegar (2 s). */
        const val MAX_FIX_AGE_NS = 2_000_000_000L
    }
}

/** Configuración del GPS según cómo se recorre (igual que backend/app/services/gps.py). */
data class TransportProfile(
    val key: String,
    val label: String,
    val description: String,
    val maxSpeedMps: Double,
    val maxAccuracyM: Double,
)

object Transport {
    val ALL = listOf(
        TransportProfile("walk", "Caminata", "Hasta 11 km/h · precisión ≤ 10 m", 3.0, 10.0),
        TransportProfile("bike", "Bici", "Hasta 43 km/h · precisión ≤ 12 m", 12.0, 12.0),
        TransportProfile("moto", "Moto", "Hasta 108 km/h · precisión ≤ 15 m", 30.0, 15.0),
        TransportProfile("car", "Carro", "Hasta 144 km/h · precisión ≤ 15 m", 40.0, 15.0),
    )

    fun of(key: String) = ALL.firstOrNull { it.key == key } ?: ALL.first()

    /** true si la lectura es aceptable: precisión suficiente y sin salto de velocidad imposible. */
    fun accept(profile: TransportProfile, last: GpsSample?, next: GpsSample): Boolean {
        if (next.accuracyM > profile.maxAccuracyM) return false
        if (last == null) return true
        val dt = next.timestampS - last.timestampS
        if (dt <= 0) return true
        return GpsMath.haversine(last.lat, last.lon, next.lat, next.lon) / dt <= profile.maxSpeedMps * 1.5
    }
}

/**
 * Filtro del recorrido GPS. Antes cada lectura se comparaba solo con la última aceptada: si esa
 * era un rebote de la señal (multitrayecto en un edificio), todas las lecturas buenas siguientes
 * parecían un "salto imposible" y el recorrido quedaba anclado en un punto donde la persona no estaba.
 * Ahora, si llegan [recoverAfter] lecturas precisas y coherentes entre sí que contradicen al ancla,
 * el ancla era el error y se acepta el tramo nuevo.
 */
class GpsTrackFilter(
    private val profile: TransportProfile,
    private val recoverAfter: Int = 3,
    /**
     * Avance mínimo para agregar un punto. Quieto, el GPS "pasea" varios metros aunque la persona
     * no se mueva; sin este umbral esas lecturas se agregaban y el recorrido avanzaba solo.
     * Se usa el mayor entre este valor y la precisión de la lectura: un movimiento menor que el
     * error del GPS no se puede distinguir del "paseo" de la señal.
     */
    private val minMoveM: Double = 3.0,
) {
    private var anchor: GpsSample? = null
    private val pending = mutableListOf<GpsSample>()

    /** Punto de partida ya conocido (centro de la calibración). */
    fun seed(start: GpsSample) { anchor = start }

    /**
     * Lecturas a agregar al recorrido: ninguna (descartada, quieto o en espera), una, o varias al
     * recuperarse. [moving] = false cuando el acelerómetro dice que la persona no está caminando.
     */
    fun offer(s: GpsSample, moving: Boolean = true): List<GpsSample> {
        if (s.accuracyM > profile.maxAccuracyM) return emptyList()
        if (!moving) { pending.clear(); return emptyList() }
        anchor?.let { a ->
            if (GpsMath.haversine(a.lat, a.lon, s.lat, s.lon) < maxOf(minMoveM, s.accuracyM)) return emptyList()
        }
        if (Transport.accept(profile, anchor, s)) {
            pending.clear()
            anchor = s
            return listOf(s)
        }
        // Contradice al ancla: se guarda en espera si es coherente con las otras en espera
        if (pending.isNotEmpty() && !Transport.accept(profile, pending.last(), s)) pending.clear()
        pending += s
        if (pending.size < recoverAfter) return emptyList()
        val recovered = pending.toList()
        pending.clear()
        anchor = recovered.last()
        return recovered
    }
}

/** Cálculos GPS en el celular (versión ligera de backend/app/services/gps.py). */
object GpsMath {
    const val EARTH_RADIUS_M = 6_371_000.0

    /** Bajo esta velocidad Doppler la persona está quieta (caminar despacio ya es ~0,8 m/s). */
    const val STILL_SPEED_MPS = 0.3

    /**
     * true si el GPS dice que el celular está quieto. Quieto, la posición "pasea" varios metros por los
     * rebotes de la señal, pero la velocidad Doppler sigue cerca de 0. Sin dato de velocidad: false.
     */
    fun isStill(s: GpsSample): Boolean = s.speedMps?.let { it < STILL_SPEED_MPS } == true

    /**
     * Punto de partida a partir de las lecturas de calibración (la persona estaba quieta en el inicio):
     * promedio ponderado por 1/precisión², mucho más estable que una lectura suelta. Su precisión es
     * la mejor de las lecturas. Null si no hay lecturas.
     */
    fun startPoint(calibration: List<GpsSample>): GpsSample? {
        if (calibration.isEmpty()) return null
        var wSum = 0.0; var lat = 0.0; var lon = 0.0
        for (s in calibration) {
            val w = 1.0 / maxOf(s.accuracyM, 0.5).let { it * it }
            wSum += w; lat += s.lat * w; lon += s.lon * w
        }
        return GpsSample(lat / wSum, lon / wSum, calibration.minOf { it.accuracyM }, calibration.maxOf { it.timestampS })
    }

    /** Inversa de [toEnu]: punto local (x = este, y = norte, en m) → latitud/longitud. */
    fun fromEnu(p: Point, originLat: Double, originLon: Double, accuracyM: Double, timestampS: Double): GpsSample {
        val lat = originLat + Math.toDegrees(p.y / EARTH_RADIUS_M)
        val lon = originLon + Math.toDegrees(p.x / (EARTH_RADIUS_M * cos(Math.toRadians(originLat))))
        return GpsSample(lat, lon, accuracyM, timestampS)
    }

    fun haversine(aLat: Double, aLon: Double, bLat: Double, bLon: Double): Double {
        val lat1 = Math.toRadians(aLat)
        val lat2 = Math.toRadians(bLat)
        val dLat = lat2 - lat1
        val dLon = Math.toRadians(bLon - aLon)
        val h = sin(dLat / 2) * sin(dLat / 2) + cos(lat1) * cos(lat2) * sin(dLon / 2) * sin(dLon / 2)
        return 2 * EARTH_RADIUS_M * asin(sqrt(h))
    }

    /** Proyección local equirrectangular: (lat, lon) → (x este, y norte) en metros. */
    fun toEnu(
        samples: List<GpsSample>,
        originLat: Double = samples.first().lat,
        originLon: Double = samples.first().lon,
    ): List<Point> {
        val cosLat0 = cos(Math.toRadians(originLat))
        return samples.map {
            Point(
                EARTH_RADIUS_M * Math.toRadians(it.lon - originLon) * cosLat0,
                EARTH_RADIUS_M * Math.toRadians(it.lat - originLat),
            )
        }
    }

    data class StaticError(val n: Int, val cep50M: Double, val r95M: Double, val needsCalibration: Boolean)

    /** Margen de error de lecturas con el celular quieto (respecto a su media) y decisión de calibrar (RF-09). */
    fun staticError(samples: List<GpsSample>, thresholdM: Double = 8.0): StaticError {
        if (samples.size < 2) throw GeometryException("Se necesitan al menos 2 lecturas")
        val lat = samples.sumOf { it.lat } / samples.size
        val lon = samples.sumOf { it.lon } / samples.size
        val d = toEnu(samples, lat, lon).map { hypot(it.x, it.y) }.sorted()
        val cep = percentile(d, 0.5)
        return StaticError(samples.size, cep, percentile(d, 0.95), cep > thresholdM)
    }

    private fun percentile(sorted: List<Double>, q: Double): Double {
        if (sorted.size == 1) return sorted[0]
        val pos = (sorted.size - 1) * q
        val lo = pos.toInt()
        val hi = minOf(lo + 1, sorted.lastIndex)
        return sorted[lo] + (sorted[hi] - sorted[lo]) * (pos - lo)
    }

    /** Polígono métrico del terreno recorrido: filtra por precisión, simplifica y analiza (RF-08). */
    fun areaPolygon(samples: List<GpsSample>, maxAccuracyM: Double = 10.0): Pair<List<Point>, PolygonAnalysis> =
        closedPolygon(samples, maxAccuracyM).let { it.vertices to it.analysis }

    /**
     * Polígono del terreno. [closureM] = distancia entre el final del recorrido y el punto de partida (antes de
     * repartirla); null si el recorrido no volvió al inicio. [droppedTail] = lecturas descartadas después de volver.
     */
    data class ClosedPolygon(val vertices: List<Point>, val analysis: PolygonAnalysis, val closureM: Double?, val droppedTail: Int)

    /** Recorrido en metros ya cerrado: [points] empieza en el punto de partida y termina justo antes de volver a él. */
    data class Loop(val points: List<Point>, val closed: Boolean, val closureM: Double?, val droppedTail: Int)

    /**
     * Cierra el recorrido en el punto de partida, como se ajusta una poligonal en topografía:
     * 1. **Vuelta al inicio:** el punto de la segunda mitad del recorrido más cercano al inicio. Si después de él las
     *    lecturas se alejan más que [toleranceM] (por ejemplo, la señal se degrada bajo un techo al terminar), esa
     *    cola se descarta: es la que cruzaba el polígono.
     * 2. **Regla de la brújula (Bowditch):** la diferencia entre ese punto y el inicio se reparte en todo el recorrido
     *    en proporción a la distancia caminada; así el final coincide exactamente con el inicio, sin un quiebre al final.
     * Si el recorrido no volvió cerca del inicio (más de 3 × [toleranceM] y del 10 % de lo caminado), no se fuerza.
     */
    fun closeAtStart(xy: List<Point>, toleranceM: Double): Loop {
        if (xy.size < 4) return Loop(xy, false, null, 0)
        val cum = DoubleArray(xy.size)
        for (i in 1 until xy.size) cum[i] = cum[i - 1] + Geometry.euclidean(xy[i - 1], xy[i])
        val total = cum.last()
        if (total <= 0.0) return Loop(xy, false, null, 0)
        val start = xy.first()
        val dist = xy.map { Geometry.euclidean(it, start) }
        val nearest = xy.indices.filter { cum[it] >= total / 2 }.minBy { dist[it] }
        val wandersAway = (nearest + 1 until xy.size).maxOfOrNull { dist[it] }?.let { it - dist[nearest] > toleranceM } == true
        val k = if (wandersAway) nearest else xy.lastIndex
        val gap = dist[k]
        if (cum[k] <= 0.0 || gap > maxOf(3 * toleranceM, 0.1 * cum[k])) return Loop(xy, false, null, 0)
        val ex = xy[k].x - start.x
        val ey = xy[k].y - start.y
        val adjusted = (0..k).map { i -> Point(xy[i].x - ex * cum[i] / cum[k], xy[i].y - ey * cum[i] / cum[k]) }
        return Loop(adjusted.dropLast(1), true, gap, xy.lastIndex - k)
    }

    /** Polígono métrico del terreno recorrido, cerrado en el punto de partida: filtra, cierra, simplifica y analiza (RF-08). */
    fun closedPolygon(samples: List<GpsSample>, maxAccuracyM: Double = 10.0): ClosedPolygon {
        val valid = samples.filter { it.accuracyM <= maxAccuracyM }
        if (valid.size < 3) throw GeometryException("Solo ${valid.size} lecturas con precisión ≤ ${maxAccuracyM.toInt()} m (EX-04)")
        val xy = toEnu(valid)
        val accuracies = valid.map { it.accuracyM }.sorted()
        val epsilon = maxOf(1.0, 2 * accuracies[accuracies.size / 2])
        val loop = closeAtStart(xy, epsilon)
        var vertices: List<Point>
        if (loop.closed && loop.points.size >= 3) {
            // Lazo cerrado: se simplifica de inicio a inicio y el inicio queda como vértice...
            vertices = Smoothing.rdp(loop.points + loop.points.first(), epsilon).dropLast(1)
            // ...salvo que esté a mitad de un lado (se empezó a mitad de un borde): entonces no es esquina
            if (vertices.size > 3 && segmentDistance(vertices[0], vertices.last(), vertices[1]) < epsilon / 2) {
                vertices = vertices.drop(1)
            }
        } else {
            vertices = if (xy.size > 3) Smoothing.rdp(xy, epsilon) else xy
            if (vertices.size > 3 && Geometry.euclidean(vertices.first(), vertices.last()) < maxOf(epsilon, 1.0)) {
                vertices = vertices.dropLast(1)
            }
        }
        return ClosedPolygon(vertices, Geometry.analyze(vertices), loop.closureM, loop.droppedTail)
    }

    private fun segmentDistance(p: Point, a: Point, b: Point): Double {
        val dx = b.x - a.x
        val dy = b.y - a.y
        val len2 = dx * dx + dy * dy
        if (len2 == 0.0) return Geometry.euclidean(p, a)
        val t = (((p.x - a.x) * dx + (p.y - a.y) * dy) / len2).coerceIn(0.0, 1.0)
        return Geometry.euclidean(p, Point(a.x + dx * t, a.y + dy * t))
    }
}
