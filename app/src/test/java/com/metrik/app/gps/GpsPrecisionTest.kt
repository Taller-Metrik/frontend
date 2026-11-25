package com.metrik.app.gps

import com.metrik.app.core.geometry.Geometry
import com.metrik.app.core.geometry.Point
import com.metrik.app.data.remote.MetrikJson
import com.metrik.app.data.repository.toDto
import com.metrik.app.domain.GpsSample
import com.metrik.app.testing.EvidenceRule
import com.metrik.app.testing.Evidencia
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import kotlin.math.cos
import kotlin.math.sqrt
import kotlin.random.Random

/** Pruebas unitarias de la mejora de precisión GPS en la app (RF-18 a RF-21). */
class GpsPrecisionTest {
    @get:Rule val ev = EvidenceRule()

    @Test
    @Evidencia(
        id = "FU-43", nombre = "Resumen de la calidad de señal GNSS", requisito = "RF-20",
        objetivo = "Verificar que se cuentan los satélites usados en la solución y se promedia el C/N0 de los 4 más fuertes.",
        entrada = "C/N0 de los satélites usados: 40, 38, 35, 33, 20 y 15 dB-Hz; y una lista vacía",
        esperado = "6 satélites y C/N0 = 36,5 dB-Hz; sin satélites: 0 y C/N0 nulo",
    )
    fun resumenCalidadSenal() {
        val q = GnssQuality.fromUsedCn0(listOf(40.0, 38.0, 35.0, 33.0, 20.0, 15.0))
        val vacia = GnssQuality.fromUsedCn0(emptyList())
        ev.obtenido = "satélites=${q.satellites}, C/N0=${q.cn0DbHz}; vacía: ${vacia.satellites}, ${vacia.cn0DbHz}"
        assertEquals(6, q.satellites)
        assertEquals(36.5, q.cn0DbHz!!, 1e-9)
        assertEquals(0, vacia.satellites)
        assertEquals(null, vacia.cn0DbHz)
    }

    @Test
    @Evidencia(
        id = "FU-44", nombre = "Fijación de una esquina con descarte de saltos", requisito = "RF-18",
        objetivo = "Verificar el promedio ponderado y el descarte de lecturas a más de 3σ (mismo algoritmo que el backend).",
        entrada = "30 lecturas quietas en (10, 20) con σ=1,5 m y 3 saltos de 25 m",
        esperado = "Esquina a menos de 0,8 m del punto real, al menos los 3 saltos descartados (≥ 28 lecturas usadas) y σ ≥ 0,5 m",
    )
    fun fijarEsquina() {
        val rng = Random(3)
        val pts = List(30) { Point(10 + gauss(rng) * 1.5, 20 + gauss(rng) * 1.5) } +
            listOf(Point(35.0, 20.0), Point(10.0, -5.0), Point(-15.0, 20.0))
        val fix = GpsPrecision.fixCorner(pts, List(pts.size) { 1.5 }, durationS = 33.0)
        val err = Geometry.euclidean(fix.point, Point(10.0, 20.0))
        ev.obtenido = "error=${"%.2f".format(err)} m, σ=${"%.2f".format(fix.sigmaM)} m, descartadas=${fix.rejected}"
        assertTrue(err < 0.8)
        // El criterio de 3σ también descarta ~1 % de lecturas buenas: se exige que caigan los 3 saltos
        assertTrue(fix.rejected >= 3 && fix.used >= 28)
        assertTrue(fix.sigmaM >= 0.5)
    }

    @Test
    @Evidencia(
        id = "FU-45", nombre = "Incertidumbre del área de un cuadrado", requisito = "RF-21",
        objetivo = "Verificar σ_A² = ¼ Σ σᵢ² dᵢ² en el celular con el caso cerrado σ_A = √2·σ·L.",
        entrada = "Cuadrado de 100 m de lado, σ = 1 m por vértice, k = 2",
        esperado = "U95 del área = 282,8 m² (2,83 %); U95 del perímetro = 5,66 m",
    )
    fun incertidumbreCuadrado() {
        val sq = listOf(Point(0.0, 0.0), Point(100.0, 0.0), Point(100.0, 100.0), Point(0.0, 100.0))
        val u = GpsPrecision.uncertainty(sq, List(4) { 1.0 })
        ev.obtenido = "U95 área=${"%.1f".format(u.areaU95M2)} m² (${"%.2f".format(u.areaU95Pct)} %), U95 perímetro=${"%.2f".format(u.perimeterU95M)} m"
        assertEquals(2 * sqrt(2.0) * 100, u.areaU95M2, 1e-6)
        assertEquals(2.828, u.areaU95Pct, 0.01)
        assertEquals(4 * sqrt(2.0), u.perimeterU95M, 1e-6)
        assertEquals(2.0, u.k, 0.0)
    }

    @Test
    @Evidencia(
        id = "FU-46", nombre = "Polígono con esquinas fijadas y cierre", requisito = "RF-18 / RF-19",
        objetivo = "Verificar que el celular arma el terreno con las esquinas fijadas y mide el error de cierre.",
        entrada = "Terreno 100×60 m: 60 lecturas quietas por esquina (σ=1,5 m) y la primera esquina vuelta a fijar al final",
        esperado = "4 vértices, área 6000 m² ± 3 %, cierre informado y área real dentro de ± U95",
    )
    fun poligonoEsquinasFijadas() {
        val samples = simulateStopAndGo(seed = 4)
        val poly = GpsPrecision.cornerPolygon(samples)
        val area = Geometry.analyze(poly.vertices).areaM2
        val u = GpsPrecision.uncertainty(poly.vertices, poly.sigmasM, poly.k)
        ev.obtenido = "vértices=${poly.vertices.size}, área=${"%.1f".format(area)} m² ± ${"%.1f".format(u.areaU95M2)}, cierre=${"%.2f".format(poly.closureM)} m"
        assertEquals(4, poly.vertices.size)
        assertEquals(6000.0, area, 180.0)
        assertNotNull(poly.closureM)
        assertTrue(kotlin.math.abs(area - 6000) <= u.areaU95M2)
    }

    @Test
    @Evidencia(
        id = "FU-47", nombre = "Datos nuevos de la lectura GPS en el JSON del backend", requisito = "RF-18 / RF-20",
        objetivo = "Verificar que vertex_id, satellites y cn0_dbhz se envían solo cuando existen (compatibilidad).",
        entrada = "Lectura con esquina 2, 9 satélites y 36 dB-Hz; y lectura sin esos datos",
        esperado = "La primera incluye las 3 claves; la segunda no incluye ninguna",
    )
    fun jsonDeLecturaGps() {
        val con = MetrikJson.encodeToString(com.metrik.app.data.remote.GpsPointDto.serializer(),
            GpsSample(-8.13, -79.04, 3.0, 10.0, satellites = 9, cn0DbHz = 36.0, vertexId = 2).toDto())
        val sin = MetrikJson.encodeToString(com.metrik.app.data.remote.GpsPointDto.serializer(),
            GpsSample(-8.13, -79.04, 3.0, 10.0).toDto())
        ev.obtenido = "con datos: $con | sin datos: $sin"
        assertTrue("\"vertex_id\":2" in con && "\"satellites\":9" in con && "\"cn0_dbhz\":36.0" in con)
        assertFalse("vertex_id" in sin || "satellites" in sin || "cn0_dbhz" in sin)
    }

    companion object {
        private const val LAT0 = -8.13
        private const val LON0 = -79.04

        fun gauss(rng: Random): Double {
            // Box-Muller
            val u1 = rng.nextDouble().coerceAtLeast(1e-12)
            val u2 = rng.nextDouble()
            return sqrt(-2 * kotlin.math.ln(u1)) * cos(2 * Math.PI * u2)
        }

        fun at(x: Double, y: Double, t: Double, acc: Double, vertexId: Int? = null) = GpsSample(
            LAT0 + Math.toDegrees(y / 6_371_000.0),
            LON0 + Math.toDegrees(x / (6_371_000.0 * cos(Math.toRadians(LAT0)))),
            acc, t, vertexId = vertexId,
        )

        /** Terreno 100×60 m: quieto `dwell` s en cada esquina (σ=1,5 m, sesgo 1 m) y caminando entre ellas. */
        fun simulateStopAndGo(seed: Int, dwell: Int = 60, close: Boolean = true): List<GpsSample> {
            val rng = Random(seed)
            val poly = listOf(Point(0.0, 0.0), Point(100.0, 0.0), Point(100.0, 60.0), Point(0.0, 60.0))
            val out = mutableListOf<GpsSample>()
            var t = 0.0
            fun dwellAt(i: Int) {
                val bx = gauss(rng); val by = gauss(rng)
                repeat(dwell) {
                    out += at(poly[i].x + bx + gauss(rng) * 1.5, poly[i].y + by + gauss(rng) * 1.5, t, 2.27, vertexId = i)
                    t += 1.0
                }
            }
            for (i in 0..3) {
                dwellAt(i)
                val a = poly[i]; val b = poly[(i + 1) % 4]
                for (k in 1 until 30) {
                    val f = k / 30.0
                    out += at(a.x + (b.x - a.x) * f + gauss(rng) * 3, a.y + (b.y - a.y) * f + gauss(rng) * 3, t, 4.5)
                    t += 1.5
                }
            }
            if (close) dwellAt(0)
            return out
        }
    }
}
