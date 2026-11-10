package com.metrik.app.gps

import android.annotation.SuppressLint
import android.content.Context
import android.location.GnssStatus
import android.location.LocationManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
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
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.math.sqrt

/** Lecturas GPS del Fused Location Provider (GNSS + Wi-Fi + red celular). */
interface LocationSource {
    val samples: Flow<GpsSample>
}

class FusedLocationSource(context: Context) : LocationSource {
    private val client = LocationServices.getFusedLocationProviderClient(context)
    private val locationManager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager

    /** Última calidad de señal GNSS y cuándo llegó (ms de reloj monotónico). */
    @Volatile private var quality: GnssQuality? = null
    @Volatile private var qualityAtMs = 0L

    private val gnssCallback = object : GnssStatus.Callback() {
        override fun onSatelliteStatusChanged(status: GnssStatus) {
            val usedCn0 = (0 until status.satelliteCount).filter(status::usedInFix).map { status.getCn0DbHz(it).toDouble() }
            quality = GnssQuality.fromUsedCn0(usedCn0)
            qualityAtMs = SystemClock.elapsedRealtime()
        }
    }

    @SuppressLint("MissingPermission") // La pantalla solicita el permiso antes de recolectar
    override val samples: Flow<GpsSample> = callbackFlow {
        val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 1000L)
            .setMinUpdateIntervalMillis(500L)
            .build()
        val callback = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                // Solo se adjunta la calidad GNSS si es reciente: si la posición vino de Wi-Fi o de la
                // red celular (sin GNSS), no hay datos de satélites y se envían nulos
                val q = quality?.takeIf { SystemClock.elapsedRealtime() - qualityAtMs < GNSS_STATUS_MAX_AGE_MS }
                for (l in result.locations) {
                    trySend(
                        GpsSample(
                            l.latitude, l.longitude, l.accuracy.toDouble(), l.time / 1000.0,
                            satellites = q?.satellites, cn0DbHz = q?.cn0DbHz,
                        ),
                    )
                }
            }
        }
        locationManager.registerGnssStatusCallback(gnssCallback, Handler(Looper.getMainLooper()))
        client.requestLocationUpdates(request, callback, Looper.getMainLooper())
        awaitClose {
            client.removeLocationUpdates(callback)
            locationManager.unregisterGnssStatusCallback(gnssCallback)
        }
    }

    private companion object {
        const val GNSS_STATUS_MAX_AGE_MS = 5_000L
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
    fun areaPolygon(samples: List<GpsSample>, maxAccuracyM: Double = 10.0): Pair<List<Point>, PolygonAnalysis> {
        val valid = samples.filter { it.accuracyM <= maxAccuracyM }
        if (valid.size < 3) throw GeometryException("Solo ${valid.size} lecturas con precisión ≤ ${maxAccuracyM.toInt()} m (EX-04)")
        val xy = toEnu(valid)
        val accuracies = valid.map { it.accuracyM }.sorted()
        val epsilon = maxOf(1.0, 2 * accuracies[accuracies.size / 2])
        var vertices = if (xy.size > 3) Smoothing.rdp(xy, epsilon) else xy
        if (vertices.size > 3 && Geometry.euclidean(vertices.first(), vertices.last()) < maxOf(epsilon, 1.0)) {
            vertices = vertices.dropLast(1)
        }
        return vertices to Geometry.analyze(vertices)
    }
}
