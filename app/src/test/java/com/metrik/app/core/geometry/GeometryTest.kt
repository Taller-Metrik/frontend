package com.metrik.app.core.geometry

import com.metrik.app.testing.AMBIENTE_L
import com.metrik.app.testing.AMBIENTE_U
import com.metrik.app.testing.EvidenceRule
import com.metrik.app.testing.Evidencia
import com.metrik.app.testing.RECTANGULO_5x4
import com.metrik.app.testing.simulateWalk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import kotlin.math.roundToInt

/** Pruebas unitarias del núcleo geométrico de la app (funciona sin internet). */
class GeometryTest {
    @get:Rule val ev = EvidenceRule()

    @Test
    @Evidencia(
        id = "FU-01", nombre = "Área por Shoelace en la app (rectángulo y L)", requisito = "RF-06",
        objetivo = "Verificar que el cálculo offline de la app coincide con el del backend.",
        entrada = "Rectángulo 5×4 m y ambiente en L de 24 m²", esperado = "20.0 m² y 24.0 m²",
    )
    fun areaShoelace() {
        val r = Geometry.area(RECTANGULO_5x4)
        val l = Geometry.area(AMBIENTE_L)
        ev.obtenido = "rectángulo=$r m², L=$l m²"
        assertEquals(20.0, r, 1e-9)
        assertEquals(24.0, l, 1e-9)
    }

    @Test
    @Evidencia(
        id = "FU-02", nombre = "Ángulos interiores y vértice cóncavo", requisito = "RF-06",
        objetivo = "Verificar la detección del vértice cóncavo en ambos sentidos de recorrido.",
        entrada = "Ambiente en L antihorario y horario", esperado = "Cinco de 90°, uno de 270°, suma 720°",
    )
    fun angulosConcavo() {
        val ccw = Geometry.interiorAngles(AMBIENTE_L).map { it.roundToInt() }
        val cw = Geometry.interiorAngles(AMBIENTE_L.reversed()).map { it.roundToInt() }
        ev.obtenido = "antihorario=$ccw, horario=$cw"
        assertEquals(listOf(90, 90, 90, 90, 90, 270), ccw.sorted())
        assertEquals(listOf(90, 90, 90, 90, 90, 270), cw.sorted())
    }

    @Test
    @Evidencia(
        id = "FU-03", nombre = "Distancias Euclidiana y Manhattan", requisito = "RF-07",
        entrada = "A=(0,0), B=(3,4)", esperado = "5.0 m y 7.0 m",
    )
    fun distancias() {
        val e = Geometry.euclidean(Point(0.0, 0.0), Point(3.0, 4.0))
        val m = Geometry.manhattan(Point(0.0, 0.0), Point(3.0, 4.0))
        ev.obtenido = "Euclidiana=$e, Manhattan=$m"
        assertEquals(5.0, e, 1e-12)
        assertEquals(7.0, m, 1e-12)
    }

    @Test
    @Evidencia(
        id = "FU-04", nombre = "Polígono autointersectado y menos de 3 vértices", requisito = "EX-05, EX-06",
        entrada = "Moño (0,0),(4,4),(4,0),(0,4) y dos vértices", esperado = "isSimple=false y GeometryException",
    )
    fun casosInvalidos() {
        val mono = Geometry.isSimple(listOf(Point(0.0, 0.0), Point(4.0, 4.0), Point(4.0, 0.0), Point(0.0, 4.0)))
        val ex = assertThrows(GeometryException::class.java) { Geometry.area(listOf(Point(0.0, 0.0), Point(1.0, 0.0))) }
        ev.obtenido = "moño simple=$mono; ${ex.message}"
        assertFalse(mono)
    }

    @Test
    @Evidencia(
        id = "FU-05", nombre = "Suavizado de un recorrido rectangular con ruido", requisito = "RF-05",
        objetivo = "Verificar que el trazo ruidoso de un aula queda como rectángulo con ángulos de 90°.",
        entrada = "Trazo simulado 5×4 m, paso 5 cm, ruido σ=3 cm, rotado 20°",
        esperado = "4 vértices, ángulos de 90°, área 20 m² ± 2 %",
    )
    fun suavizadoRectangulo() {
        val r = Smoothing.smoothTrack(simulateWalk(RECTANGULO_5x4))
        val a = Geometry.analyze(r.vertices)
        ev.obtenido = "${r.vertices.size} vértices, área=${"%.3f".format(a.areaM2)} m², " +
            "ángulos=${a.interiorAnglesDeg.map { it.roundToInt() }}"
        assertEquals(4, r.vertices.size)
        a.interiorAnglesDeg.forEach { assertEquals(90.0, it, 0.01) }
        assertEquals(20.0, a.areaM2, 0.4)
    }

    @Test
    @Evidencia(
        id = "FU-06", nombre = "Suavizado de ambientes cóncavos (L y U)", requisito = "RF-05",
        entrada = "Trazos simulados de L y U (24 m² cada uno), ruido σ=3 cm",
        esperado = "L: 6 vértices (1 cóncavo); U: 8 vértices (2 cóncavos); área 24 m² ± 2 %",
    )
    fun suavizadoConcavos() {
        val l = Geometry.analyze(Smoothing.smoothTrack(simulateWalk(AMBIENTE_L, seed = 2)).vertices)
        val u = Geometry.analyze(Smoothing.smoothTrack(simulateWalk(AMBIENTE_U, seed = 3)).vertices)
        ev.obtenido = "L: ${l.vertices.size} v, cóncavos=${l.concaveVertices.size}, área=${"%.3f".format(l.areaM2)} | " +
            "U: ${u.vertices.size} v, cóncavos=${u.concaveVertices.size}, área=${"%.3f".format(u.areaM2)}"
        assertEquals(6, l.vertices.size); assertEquals(1, l.concaveVertices.size)
        assertEquals(8, u.vertices.size); assertEquals(2, u.concaveVertices.size)
        assertEquals(24.0, l.areaM2, 0.48); assertEquals(24.0, u.areaM2, 0.48)
    }

    @Test
    @Evidencia(
        id = "FU-07", nombre = "Recorrido que no se cierra", requisito = "EX-01",
        entrada = "Esquinas (0,0),(5,0),(5,4),(0,4),(0,2) — termina a 2 m del inicio",
        esperado = "closed=false y polígono de 4 vértices con 20 m²",
    )
    fun recorridoNoCerrado() {
        val pts = listOf(Point(0.0, 0.0), Point(5.0, 0.0), Point(5.0, 4.0), Point(0.0, 4.0), Point(0.0, 2.0))
        val r = Smoothing.smoothTrack(emptyList(), corners = pts)
        ev.obtenido = "closed=${r.closed}, vértices=${r.vertices.size}, área=${Geometry.area(r.vertices)}"
        assertFalse(r.closed)
        assertEquals(4, r.vertices.size)
        assertEquals(20.0, Geometry.area(r.vertices), 1e-6)
    }

    @Test
    @Evidencia(
        id = "FU-08", nombre = "Ajuste de giro del giroscopio a 90°", requisito = "RF-02",
        entrada = "87°, 93.5°, -88°, 178°, 60° con tolerancia 15°", esperado = "90°, 90°, -90°, 180°, 60°",
    )
    fun ajusteGiro() {
        val out = listOf(87.0, 93.5, -88.0, 178.0, 60.0).map { Smoothing.snapTurnAngle(it) }
        ev.obtenido = out.toString()
        assertEquals(listOf(90.0, 90.0, -90.0, 180.0, 60.0), out)
    }

    @Test
    @Evidencia(
        id = "FU-09", nombre = "Escalas y sugerencia para A3", requisito = "RF-10",
        entrada = "5 m a 1:50 y 1:500; aula 6×5 m; terreno 120×80 m",
        esperado = "100 mm y 10 mm; aula 1:25; terreno 1:500",
    )
    fun escalas() {
        val a = Scale.toPaperMm(5.0, 50)
        val b = Scale.toPaperMm(5.0, 500)
        val aula = Scale.suggest(6.0, 5.0)
        val terreno = Scale.suggest(120.0, 80.0)
        ev.obtenido = "1:50→$a mm, 1:500→$b mm, aula 1:$aula, terreno 1:$terreno"
        assertEquals(100.0, a, 1e-9); assertEquals(10.0, b, 1e-9)
        assertEquals(25, aula); assertEquals(500, terreno)
        assertTrue(runCatching { Scale.toPaperMm(1.0, 33) }.isFailure)
    }

    private fun recorrido(esquinas: List<Point>, extra: List<Point> = emptyList()): List<Point> {
        val pts = mutableListOf<Point>()
        for (i in 0 until esquinas.lastIndex) {
            val a = esquinas[i]; val b = esquinas[i + 1]
            val n = maxOf(1, (Geometry.euclidean(a, b) / 0.1).roundToInt())
            for (k in 0 until n) pts += Point(a.x + (b.x - a.x) * k / n, a.y + (b.y - a.y) * k / n)
        }
        return pts + esquinas.last() + extra
    }

    @Test
    @Evidencia(
        id = "FU-25", nombre = "Cierre del recorrido con tolerancia adaptativa", requisito = "RF-05",
        objetivo = "Verificar que la app reconoce la vuelta al inicio con deriva o pasándose del inicio, igual que el backend.",
        entrada = "Cuadrado 15×15 m terminando a 2 m; rectángulo 5×4 m pasándose 1,5 m; cuadrado con hueco de 6 m",
        esperado = "Los dos primeros cerrados; el del hueco de 6 m abierto",
    )
    fun cierreAdaptativo() {
        val deriva = Smoothing.smoothTrack(recorrido(listOf(Point(0.0, 0.0), Point(15.0, 0.0), Point(15.0, 15.0), Point(0.0, 15.0), Point(0.0, 2.0))))
        val sobrepaso = Smoothing.smoothTrack(
            recorrido(listOf(Point(0.0, 0.0), Point(5.0, 0.0), Point(5.0, 4.0), Point(0.0, 4.0), Point(0.0, 0.0)), (1..15).map { Point(0.1 * it, 0.0) }),
        )
        val hueco = Smoothing.smoothTrack(recorrido(listOf(Point(0.0, 0.0), Point(15.0, 0.0), Point(15.0, 15.0), Point(0.0, 15.0), Point(0.0, 6.0))))
        val areaSobrepaso = Geometry.area(sobrepaso.vertices)
        ev.obtenido = "deriva: cerrado=${deriva.closed}, lados=${deriva.vertices.size}; sobrepaso: cerrado=${sobrepaso.closed}, " +
            "área=${"%.1f".format(areaSobrepaso)} m²; hueco 6 m: cerrado=${hueco.closed}"
        assertTrue(deriva.closed); assertEquals(4, deriva.vertices.size)
        assertTrue(sobrepaso.closed); assertEquals(20.0, areaSobrepaso, 0.5)
        assertFalse(hueco.closed)
    }

    @Test
    @Evidencia(
        id = "FU-26", nombre = "Recorrido que empieza a mitad de una pared", requisito = "RF-05",
        objetivo = "Verificar que el plano de la app no se deforma si se empieza a caminar a mitad de una pared.",
        entrada = "Cuadro de 6×4 m recorrido desde la mitad del lado de 6 m",
        esperado = "Muros 6, 4, 6, 4 y área ≈ 24 m² (Muro 1 = pared donde se empezó)",
    )
    fun inicioAMitadDePared() {
        val r = Smoothing.smoothTrack(
            recorrido(listOf(Point(3.0, 0.0), Point(0.0, 0.0), Point(0.0, 4.0), Point(6.0, 4.0), Point(6.0, 0.0), Point(3.0, 0.0))),
        )
        val muros = Geometry.wallLengths(r.vertices).map { it.roundToInt() }
        val area = Geometry.area(r.vertices)
        ev.obtenido = "muros=${Geometry.wallLengths(r.vertices).map { "%.2f".format(it) }}, área=${"%.2f".format(area)}"
        assertEquals(listOf(6, 4, 6, 4), muros)
        assertEquals(24.0, area, 0.3)
    }
}
