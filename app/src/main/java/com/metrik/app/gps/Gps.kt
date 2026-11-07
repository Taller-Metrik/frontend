package com.metrik.app.gps

import android.annotation.SuppressLint
import android.content.Context
import android.os.Looper
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

    @SuppressLint("MissingPermission") // La pantalla solicita el permiso antes de recolectar
    override val samples: Flow<GpsSample> = callbackFlow {
        val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 1000L)
            .setMinUpdateIntervalMillis(500L)
            .build()
        val callback = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                for (l in result.locations) {
                    trySend(GpsSample(l.latitude, l.longitude, l.accuracy.toDouble(), l.time / 1000.0))
                }
            }
        }
        client.requestLocationUpdates(request, callback, Looper.getMainLooper())
        awaitClose { client.removeLocationUpdates(callback) }
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

/** Cálculos GPS en el celular (versión ligera de backend/app/services/gps.py). */
object GpsMath {
    const val EARTH_RADIUS_M = 6_371_000.0

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
