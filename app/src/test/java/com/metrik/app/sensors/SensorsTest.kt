package com.metrik.app.sensors

import com.metrik.app.core.geometry.Point
import com.metrik.app.domain.GpsSample
import com.metrik.app.gps.GpsMath
import com.metrik.app.testing.EvidenceRule
import com.metrik.app.testing.Evidencia
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/** Pruebas unitarias de la lógica de sensores (odómetro, giroscopio, acelerómetro, GPS). */
class SensorsTest {
    @get:Rule val ev = EvidenceRule()
    private fun s(x: Double?, y: Double = 0.0, tMs: Long) = OdometrySample(x?.let { Point(it, y) }, tMs * 1_000_000)

    @Test
    @Evidencia(
        id = "FU-10", nombre = "Odómetro acumula distancia y descarta temblor", requisito = "RF-01",
        objetivo = "Verificar que el odómetro suma la distancia del tramo como una rueda e ignora el temblor < 5 mm.",
        entrada = "Puntos a 0, 1 mm, 1 m, 2 m y 3 m en línea recta", esperado = "Total 3.0 m, tramo 3.0 m, 4 puntos en el trazo",
    )
    fun odometroAcumula() {
        val o = OdometerAccumulator()
        listOf(0.0, 0.001, 1.0, 2.0, 3.0).forEachIndexed { i, x -> o.onSample(s(x, tMs = i * 1000L)) }
        ev.obtenido = "total=${o.totalM}, tramo=${o.segmentM}, puntos=${o.track.size}"
        assertEquals(3.0, o.totalM, 1e-9)
        assertEquals(4, o.track.size)
        assertEquals(3.0, o.closeSegment(), 1e-9)
        assertEquals(0.0, o.segmentM, 0.0)
    }

    @Test
    @Evidencia(
        id = "FU-11", nombre = "Tiempo sin seguimiento y relocalización", requisito = "EX-02",
        objetivo = "Verificar que se mide el tiempo sin seguimiento de ARCore y que un salto > 1 m no suma distancia.",
        entrada = "Seguimiento perdido 1.5 s y luego salto de 3 m", esperado = "trackingLoss=1.5 s, saltos=1, total=0",
    )
    fun perdidaDeSeguimiento() {
        val o = OdometerAccumulator()
        o.onSample(s(0.0, tMs = 0)); o.onSample(s(null, tMs = 500)); o.onSample(s(null, tMs = 1500)); o.onSample(s(3.0, tMs = 2000))
        ev.obtenido = "pérdida=${o.trackingLossS} s, saltos=${o.jumps}, total=${o.totalM}"
        assertEquals(1.5, o.trackingLossS, 1e-9)
        assertEquals(1, o.jumps)
        assertEquals(0.0, o.totalM, 0.0)
    }

    @Test
    @Evidencia(
        id = "FU-12", nombre = "Giro entre esquinas con cruce de ±180°", requisito = "RF-02",
        objetivo = "Verificar que el rumbo del giroscopio se desenrolla y el giro se ajusta a 90°.",
        entrada = "Rumbo 170° → 179° → -175° → -98° (giro real ≈ 92°)", esperado = "Giro crudo 92°, ajustado 90°",
    )
    fun giroConCruce() {
        val t = TurnTracker()
        listOf(170.0, 179.0, -175.0, -98.0).forEach(t::onHeading)
        val (raw, snapped) = t.corner()!!
        ev.obtenido = "crudo=$raw°, ajustado=$snapped°"
        assertEquals(92.0, raw, 1e-9)
        assertEquals(90.0, snapped, 0.0)
    }

    @Test
    @Evidencia(
        id = "FU-13", nombre = "Detector de pasos del acelerómetro", requisito = "EX-03",
        objetivo = "Verificar el conteo de pasos con una señal de caminata simulada de 2 pasos/s durante 10 s.",
        entrada = "Señal 9.81 + 3·sen(2π·2t) m/s² muestreada a 50 Hz + vibración de 0.2 m/s²", esperado = "20 pasos (± 1)",
    )
    fun detectorDePasos() {
        val d = StepDetector()
        var steps = 0
        for (i in 0 until 500) {
            val t = i / 50.0
            val z = StepDetector.GRAVITY + 3.0 * sin(2 * PI * 2 * t) + 0.2 * sin(2 * PI * 23 * t)
            if (d.onAcceleration(0.0, 0.0, z, (t * 1e9).toLong())) steps++
        }
        ev.obtenido = "$steps pasos"
        assertTrue(steps in 19..21)
    }

    @Test
    @Evidencia(
        id = "FU-14", nombre = "GPS: Haversine, proyección y margen de error", requisito = "RF-08, RF-09",
        objetivo = "Verificar los cálculos GPS del celular: distancia geodésica, CEP con lecturas quietas y decisión de calibrar.",
        entrada = "1° de latitud; 40 lecturas en círculo de 2 m y de 12 m alrededor de Trujillo",
        esperado = "≈111 195 m; CEP 2 m sin calibrar; CEP 12 m pide calibrar",
    )
    fun gpsMath() {
        val d = GpsMath.haversine(0.0, 0.0, 1.0, 0.0)
        fun ring(r: Double) = (0 until 40).map {
            val a = 2 * PI * it / 40
            GpsSample(
                -8.13 + Math.toDegrees(r * sin(a) / 6_371_000.0),
                -79.04 + Math.toDegrees(r * cos(a) / (6_371_000.0 * cos(Math.toRadians(-8.13)))),
                r, it.toDouble(),
            )
        }
        val bueno = GpsMath.staticError(ring(2.0))
        val malo = GpsMath.staticError(ring(12.0))
        ev.obtenido = "haversine=${"%.1f".format(d)} m; CEP=${"%.2f".format(bueno.cep50M)} calibrar=${bueno.needsCalibration}; " +
            "CEP=${"%.2f".format(malo.cep50M)} calibrar=${malo.needsCalibration}"
        assertEquals(111_195.0, d, 15.0)
        assertEquals(2.0, bueno.cep50M, 0.01)
        assertTrue(!bueno.needsCalibration)
        assertTrue(malo.needsCalibration)
    }

    @Test
    @Evidencia(
        id = "FU-15", nombre = "Área de un terreno con lecturas GPS", requisito = "RF-08",
        objetivo = "Verificar el área en el celular a partir de un recorrido GPS de 100×60 m, descartando lecturas imprecisas.",
        entrada = "Recorrido cada 2 m (precisión 4 m) + 5 lecturas con precisión 30 m", esperado = "Área 6000 m² ± 3 %",
    )
    fun gpsArea() {
        val lat0 = -8.13
        val lon0 = -79.04
        val pts = mutableListOf<GpsSample>()
        val poly = listOf(0.0 to 0.0, 100.0 to 0.0, 100.0 to 60.0, 0.0 to 60.0)
        var t = 0.0
        for (i in poly.indices) {
            val (x1, y1) = poly[i]
            val (x2, y2) = poly[(i + 1) % 4]
            val n = (kotlin.math.hypot(x2 - x1, y2 - y1) / 2).toInt()
            for (k in 0 until n) {
                val x = x1 + (x2 - x1) * k / n
                val y = y1 + (y2 - y1) * k / n
                pts += GpsSample(lat0 + Math.toDegrees(y / 6_371_000.0), lon0 + Math.toDegrees(x / (6_371_000.0 * cos(Math.toRadians(lat0)))), 4.0, t++)
            }
        }
        repeat(5) { pts.add(it * 30, GpsSample(lat0 + 0.001, lon0, 30.0, -1.0)) }
        val (_, a) = GpsMath.areaPolygon(pts)
        ev.obtenido = "área=${"%.1f".format(a.areaM2)} m², perímetro=${"%.1f".format(a.perimeterM)} m"
        assertEquals(6000.0, a.areaM2, 180.0)
    }

    @Test
    @Evidencia(
        id = "FU-27", nombre = "Saltos del seguimiento por velocidad imposible", requisito = "EX-02",
        objetivo = "Verificar que un salto de posición imposible al caminar (piso liso, ARCore se relocaliza) no suma distancia.",
        entrada = "Camina 1 m en 1 s; luego salta 0,8 m en 0,1 s (8 m/s); luego sigue 1 m en 1 s",
        esperado = "Total 2,0 m, 1 salto y trazo continuo 0 → 1 → 2 m (sin línea cruzada)",
    )
    fun saltoPorVelocidad() {
        val o = OdometerAccumulator()
        o.onSample(s(0.0, tMs = 0)); o.onSample(s(1.0, tMs = 1000)); o.onSample(s(1.8, tMs = 1100)); o.onSample(s(2.8, tMs = 2100))
        ev.obtenido = "total=${o.totalM}, saltos=${o.jumps}, trazo=${o.track.map { it.x }}"
        assertEquals(2.0, o.totalM, 1e-9)
        assertEquals(1, o.jumps)
        // El trazo se reengancha: sigue desde el último punto bueno (sin línea que cruce)
        assertEquals(listOf(0.0, 1.0, 2.0), o.track.map { Math.round(it.x * 1000) / 1000.0 })
    }

    @Test
    @Evidencia(
        id = "FU-28", nombre = "Deriva al recuperar el seguimiento no suma distancia", requisito = "EX-02",
        objetivo = "Verificar que, quieto, la pose corrida de ARCore al recuperar el seguimiento se reengancha sin sumar metros.",
        entrada = "Quieto en 0; seguimiento perdido 2 s; vuelve en 0,9 m (deriva lenta, 0,45 m/s); luego camina 1 m",
        esperado = "Total 1,0 m (solo lo caminado), 1 salto, trazo 0 → 1 m",
    )
    fun derivaAlRecuperar() {
        val o = OdometerAccumulator(jitterM = 0.02)
        o.onSample(s(0.0, tMs = 0)); o.onSample(s(null, tMs = 100)); o.onSample(s(0.9, tMs = 2100)); o.onSample(s(1.9, tMs = 3100))
        ev.obtenido = "total=${o.totalM}, saltos=${o.jumps}, trazo=${o.track.map { it.x }}"
        assertEquals(1.0, o.totalM, 1e-9)
        assertEquals(1, o.jumps)
        assertEquals(listOf(0.0, 1.0), o.track.map { Math.round(it.x * 1000) / 1000.0 })
    }

    @Test
    @Evidencia(
        id = "FU-29", nombre = "Calibración de ARCore antes de medir", requisito = "EX-02",
        objetivo = "Verificar que la pose solo se usa tras 1,5 s de seguimiento continuo y que una pérdida reinicia la espera.",
        entrada = "Seguimiento en 0, 1.0 y 1.6 s; pérdida en 2 s; seguimiento en 2.5 y 4.1 s",
        esperado = "false, false, true, false, false, true",
    )
    fun compuertaDeCalibracion() {
        val g = TrackingGate()
        val ms = 1_000_000L
        val r = listOf(
            g.onFrame(true, 0), g.onFrame(true, 1000 * ms), g.onFrame(true, 1600 * ms),
            g.onFrame(false, 2000 * ms), g.onFrame(true, 2500 * ms), g.onFrame(true, 4100 * ms),
        )
        ev.obtenido = r.toString()
        assertEquals(listOf(false, false, true, false, false, true), r)
    }

    @Test
    @Evidencia(
        id = "FU-31", nombre = "Quieto con balanceo no suma distancia", requisito = "RF-01",
        objetivo = "Verificar que el balanceo natural del cuerpo estando quieto (±4 cm) no suma metros y que caminar sí se mide completo.",
        entrada = "10 s quieto con balanceo de ±4 cm a 30 Hz; luego 3 m en línea recta a 1 m/s con el mismo balanceo lateral",
        esperado = "0 m quieto; 3 m (± 10 cm) al caminar",
    )
    fun quietoConBalanceo() {
        val o = OdometerAccumulator.walking()
        var t = 0L
        for (i in 0 until 300) { o.onSample(OdometrySample(Point(0.04 * sin(i * 0.3), 0.04 * cos(i * 0.21)), t)); t += 33_000_000L }
        val quieto = o.totalM
        for (i in 0..90) { o.onSample(OdometrySample(Point(i / 30.0, 0.04 * sin(i * 0.3)), t)); t += 33_000_000L }
        ev.obtenido = "quieto=${quieto} m, caminando=${o.totalM} m"
        assertEquals(0.0, quieto, 1e-9)
        assertEquals(3.0, o.totalM, 0.10)
    }

    @Test
    @Evidencia(
        id = "FU-32", nombre = "Altura del piso sin plano de ARCore", requisito = "RF-01",
        objetivo = "Verificar que la altura del piso se estima con la nube de puntos aunque ARCore no declare un plano, ignorando una mesa y puntos sueltos, y que un plano real tiene prioridad.",
        entrada = "Celular a y=0; 200 puntos del piso en y≈−1,40 (±1 cm); 60 de una mesa en y=−0,65; 30 sueltos entre −2,0 y −0,5; luego un plano en y=−1,38",
        esperado = "Piso ≈ −1,40 m (± 2 cm) por puntos; −1,38 m con el plano",
    )
    fun alturaDelPiso() {
        val f = FloorEstimator()
        val rng = java.util.Random(3)
        assertEquals(null, f.floorY)
        repeat(200) { f.onPoint(-1.40 + rng.nextGaussian() * 0.01, 0.0) }
        repeat(60) { f.onPoint(-0.65, 0.0) }
        repeat(30) { f.onPoint(-0.5 - rng.nextDouble() * 1.5, 0.0) }
        val porPuntos = f.floorY!!
        f.onPlanes(listOf(-0.65 to 0.5, -1.38 to 3.0), 0.0)
        ev.obtenido = "por puntos=${"%.3f".format(porPuntos)} m, con plano=${f.floorY} m"
        assertEquals(-1.40, porPuntos, 0.02)
        assertEquals(-1.38, f.floorY!!, 1e-9)
    }

    @Test
    @Evidencia(
        id = "FU-33", nombre = "Mira sobre el piso", requisito = "RF-01",
        objetivo = "Verificar dónde toca el piso el rayo de la mira y que se descarta si apunta al horizonte o muy lejos.",
        entrada = "Celular a 1,40 m del piso mirando 45° hacia abajo hacia −Z; luego horizontal; luego 12° (punto a 6,6 m)",
        esperado = "Punto a 1,40 m adelante: (0; 1,40); null; null",
    )
    fun miraSobreElPiso() {
        val s2 = Math.sqrt(0.5)
        val p = FloorAim.intersect(0.0, 0.0, 0.0, 0.0, -s2, -s2, floorY = -1.40)!!
        val horizontal = FloorAim.intersect(0.0, 0.0, 0.0, 0.0, 0.0, -1.0, floorY = -1.40)
        val lejos = FloorAim.intersect(0.0, 0.0, 0.0, 0.0, -0.21, -0.98, floorY = -1.40)
        ev.obtenido = "punto=(${"%.3f".format(p.x)}; ${"%.3f".format(p.y)}), horizontal=$horizontal, lejos=$lejos"
        assertEquals(0.0, p.x, 1e-9)
        assertEquals(1.40, p.y, 1e-9)
        assertEquals(null, horizontal)
        assertEquals(null, lejos)
    }

    @Test
    @Evidencia(
        id = "FU-34", nombre = "Mira: temblor quieto no suma, barrido deliberado sí", requisito = "RF-01",
        objetivo = "Verificar que, con la persona quieta, el temblor de la mira (±5 cm) no suma y que llevar la mira 1 m a lo largo de la pared sí suma, como una rueda empujada.",
        entrada = "Celular fijo; 5 s de mira temblando ±5 cm; la mira avanza 1 m en 2 s; luego camina 1 m más",
        esperado = "0 m con temblor; ~0,9 m tras el barrido (de a 30 cm); 2,0 m (± 6 cm) al final",
    )
    fun miraQuietaYBarrido() {
        val o = OdometerAccumulator.walking()
        val device = Point(0.0, 0.0)
        var t = 0L
        val frame = 33_000_000L
        for (i in 0 until 150) { o.onSample(OdometrySample(Point(0.05 * sin(i * 0.4), 1.0 + 0.05 * cos(i * 0.3)), t, device = device)); t += frame }
        val temblor = o.totalM
        for (i in 0..60) { o.onSample(OdometrySample(Point(i / 60.0, 1.0), t, device = device)); t += frame }
        val barrido = o.totalM
        // Luego camina 1 m más con la mira por el borde (la persona se mueve a 1 m/s)
        for (i in 1..30) { o.onSample(OdometrySample(Point(1.0 + i / 30.0, 1.0), t, device = Point(i / 30.0, 0.0))); t += frame }
        ev.obtenido = "temblor=$temblor m, barrido=$barrido m, total tras caminar=${o.totalM} m"
        assertEquals(0.0, temblor, 1e-9)
        assertEquals(0.9, barrido, 0.1) // estando quieto, cuenta de a ≥ 30 cm
        assertEquals(2.0, o.totalM, 0.06)
    }

    @Test
    @Evidencia(
        id = "FU-35", nombre = "Cortes breves del seguimiento no traban la calibración", requisito = "EX-02",
        objetivo = "Verificar que cortes del seguimiento de un instante (< 0,3 s) no reinician la espera de calibración.",
        entrada = "Seguimiento desde 0 s con cortes de 0,1 s cada 0,5 s; consulta a 1,6 s",
        esperado = "Calibración lista a 1,6 s",
    )
    fun cortesBrevesNoTraban() {
        val g = TrackingGate()
        val ms = 1_000_000L
        var listo = false
        for (t in 0..1600 step 33) {
            val corte = (t % 500) in 400..499 // 0,1 s sin seguimiento cada 0,5 s
            listo = g.onFrame(!corte, t * ms)
        }
        ev.obtenido = "listo=$listo"
        assertTrue(listo)
    }

    @Test
    @Evidencia(
        id = "FU-36", nombre = "Piso estimado en superficies sin textura", requisito = "RF-01",
        objetivo = "Verificar que sin plano ni puntos se puede estimar el piso por la altura del celular y que los datos reales lo reemplazan.",
        entrada = "Celular en y=0, sin datos → estimar; luego 100 puntos reales en y=−1,45",
        esperado = "Estimado −1,30 m (origen 'estimado'); luego −1,45 m (origen 'puntos')",
    )
    fun pisoEstimado() {
        val f = FloorEstimator()
        f.assume(0.0)
        val estimado = f.floorY!!
        val origen1 = f.source
        repeat(100) { f.onPoint(-1.45, 0.0) }
        ev.obtenido = "estimado=$estimado ($origen1), real=${"%.3f".format(f.floorY)} (${f.source})"
        assertEquals(-1.30, estimado, 1e-9)
        assertEquals("estimado", origen1)
        assertEquals(-1.45, f.floorY!!, 0.02)
        assertEquals("puntos", f.source)
    }

    @Test
    @Evidencia(
        id = "FU-37", nombre = "Tras un salto de la mira se sigue midiendo", requisito = "RF-01",
        objetivo = "Verificar que, caminando, un salto brusco de la mira (inclinar el celular rápido) se descarta pero la medición continúa al instante.",
        entrada = "Camina 1 m a 1 m/s con la mira; la mira salta 1,5 m en un frame; sigue caminando 1 m",
        esperado = "Total 2,0 m (± 6 cm) y 1 salto",
    )
    fun saltoDeMiraNoFrena() {
        val o = OdometerAccumulator.walking()
        var t = 0L
        val frame = 33_000_000L
        for (i in 0..30) { val x = i / 30.0; o.onSample(OdometrySample(Point(x, 1.0), t, device = Point(x, 0.0))); t += frame }
        for (i in 31..60) { val x = i / 30.0; o.onSample(OdometrySample(Point(x + 1.5, 1.0), t, device = Point(x, 0.0))); t += frame }
        ev.obtenido = "total=${o.totalM} m, saltos=${o.jumps}"
        assertEquals(2.0, o.totalM, 0.06)
        assertEquals(1, o.jumps)
    }

    @Test
    @Evidencia(
        id = "FU-30", nombre = "Avisos sin parpadeo", requisito = "EX-02",
        objetivo = "Verificar que un aviso de ARCore solo se muestra si se mantiene 0,7 s y que los cambios de un frame se ignoran.",
        entrada = "\"A\" un frame (0 s) y nada a 0,03 s; luego \"B\" desde 1 s hasta 1,8 s",
        esperado = "null, null, null, null, B",
    )
    fun avisosSinParpadeo() {
        val d = MessageDebouncer()
        val ms = 1_000_000L
        val r = listOf(
            d.onMessage("A", 0), d.onMessage(null, 30 * ms),
            d.onMessage("B", 1000 * ms), d.onMessage("B", 1500 * ms), d.onMessage("B", 1800 * ms),
        )
        ev.obtenido = r.toString()
        assertEquals(listOf(null, null, null, null, "B"), r)
    }
}
