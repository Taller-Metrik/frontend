package com.metrik.app.feature

import com.metrik.app.core.geometry.Geometry
import com.metrik.app.core.geometry.Point
import com.metrik.app.domain.GpsSample
import com.metrik.app.feature.gps.GpsPhase
import com.metrik.app.feature.gps.GpsViewModel
import com.metrik.app.gps.GpsMath
import com.metrik.app.gps.GpsPrecisionTest
import com.metrik.app.gps.LocationSource
import com.metrik.app.testing.EvidenceRule
import com.metrik.app.testing.Evidencia
import com.metrik.app.testing.FakeSurveyRepository
import com.metrik.app.testing.MainDispatcherRule
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.util.Random

/** Esquinas marcadas al toque (sin espera) en la pantalla GPS, a pie con pasos + brújula (RF-18, RF-19, RF-21). */
class GpsCornersViewModelTest {
    @get:Rule val ev = EvidenceRule()
    @get:Rule val main = MainDispatcherRule()

    private val truth = listOf(Point(0.0, 0.0), Point(100.0, 0.0), Point(100.0, 60.0), Point(0.0, 60.0))

    private class Sim(seed: Int) {
        val gps = MutableSharedFlow<GpsSample>(extraBufferCapacity = 10_000)
        val compass = MutableSharedFlow<Double>(extraBufferCapacity = 10_000)
        val rnd = Random(seed.toLong())
        var nowNs = 0L
        var t = 0.0
        val repo = FakeSurveyRepository()
        val vm = GpsViewModel(1, object : LocationSource { override val samples = gps }, repo, startGate = { com.metrik.app.gps.GpsStartGate.off() },
            calibrationTarget = 3, compassSteps = compass, stepLengthM = 0.70, nanoClock = { nowNs })

        suspend fun calibrateAndStart() {
            vm.calibrate()
            // Calibración con su dispersión: la partida (centro) no coincide con la primera lectura
            for ((x, y) in listOf(1.0 to 0.0, -1.0 to 0.6, 0.3 to -0.9)) gps.emit(GpsPrecisionTest.at(x, y, t++, 2.5))
            vm.startRecording()
        }

        /** Camina de a a b: un paso cada 0,5 s con rumbo de brújula (0 = norte, π/2 = este) con ruido de 6°, GPS cada 2 s (σ 3 m). */
        suspend fun walk(a: Point, b: Point, headingBiasDeg: Double = 0.0, transientSteps: Int = 0, transientDeg: Double = 0.0) {
            val len = Geometry.euclidean(a, b)
            val heading = Math.atan2(b.x - a.x, b.y - a.y) + Math.toRadians(headingBiasDeg)
            val steps = (len / 0.7).toInt()
            for (k in 1..steps) {
                nowNs += 500_000_000L; t += 0.5
                val extra = if (k <= transientSteps) transientDeg else 0.0
                compass.emit(heading + Math.toRadians(extra + rnd.nextGaussian() * 6))
                if (k % 4 == 0) {
                    val f = k.toDouble() / steps
                    gps.emit(
                        GpsPrecisionTest.at(a.x + (b.x - a.x) * f + rnd.nextGaussian() * 3, a.y + (b.y - a.y) * f + rnd.nextGaussian() * 3, t, 3.0)
                            .copy(speedMps = 1.3),
                    )
                }
            }
        }

        /** Quieto en p durante [seconds] s: lecturas GPS con Doppler 0 (σ 1,5 m). */
        suspend fun still(p: Point, seconds: Int) {
            repeat(seconds) {
                nowNs += 1_000_000_000L; t += 1.0
                gps.emit(GpsPrecisionTest.at(p.x + rnd.nextGaussian() * 1.5, p.y + rnd.nextGaussian() * 1.5, t, 2.5).copy(speedMps = 0.0))
            }
        }
    }

    @Test
    @Evidencia(
        id = "FU-48", nombre = "Terreno a pie con esquinas al toque y cierre en la partida", requisito = "RF-18 / RF-19 / RF-21",
        objetivo = "Verificar el flujo nuevo: medir precisión al inicio, caminar con pasos + brújula, tocar «Esquina» al "
            + "llegar a cada una (sin esperar; si se queda quieto, las lecturas se promedian solas), terminar en la partida y "
            + "finalizar. El polígono queda en las esquinas, cierra en la partida y se guardan las lecturas de esquina.",
        entrada = "Rectángulo 100×60 m; brújula con 6° de ruido y el lado 3 desviado 12°; 6 s quieto en cada esquina y en la partida",
        esperado = "3 esquinas tocadas + la partida = 4 vértices a < 3 m de los reales, área 6000 m² ± 3 %, cierre informado, "
            + "margen U95 calculado, recorrido guardado con lecturas vertex_id 0..3 y los vértices del celular como `corners`",
    )
    fun esquinasAlToque() = runBlocking {
        val sim = Sim(seed = 11)
        sim.calibrateAndStart()
        for (side in 0 until 4) {
            val a = truth[side]
            val b = truth[(side + 1) % 4]
            sim.walk(a, b, headingBiasDeg = if (side == 2) 12.0 else 0.0)
            sim.nowNs += 4_000_000_000L
            if (side < 3) sim.vm.fixCorner()
            sim.still(b, 6)
        }
        val recording = sim.vm.state.value
        sim.vm.finish()
        val done = sim.vm.state.value
        sim.vm.save("Terreno al toque")
        withTimeout(5_000) { sim.vm.state.first { it.savedLocalId != null } }
        val saved = sim.repo.saved.value.values.single()
        val errors = truth.map { tr -> done.polygon.minOf { Geometry.euclidean(it, tr) } }
        ev.obtenido = "esquinas=${recording.cornersFixed}, pasos=${recording.steps}, fase=${done.phase}, " +
            "errores=${errors.map { "%.1f".format(it) }}, área=${"%.1f".format(done.analysis?.areaM2)} ± ${"%.1f".format(done.uncertainty?.areaU95M2)}, " +
            "cierre=${"%.2f".format(done.closureM)} m, error=${done.error}, vertex_ids=${saved.gps!!.mapNotNull { it.vertexId }.toSet()}, " +
            "corners guardados=${saved.corners?.size}"
        assertEquals(3, recording.cornersFixed)
        // Las esquinas dibujadas mientras se recorre caen sobre el trazo (aunque el GPS lo haya corregido después)
        assertTrue(recording.cornerPoints.all { c -> recording.track.any { Geometry.euclidean(it, c) < 1e-6 } })
        assertEquals(GpsPhase.FINISHED, done.phase)
        assertEquals(4, done.polygon.size)
        assertTrue(errors.all { it < 3.0 })
        assertEquals(6000.0, done.analysis!!.areaM2, 180.0)
        assertNotNull(done.uncertainty)
        assertNotNull(done.closureM)
        assertNull(done.error)
        assertTrue(done.track.size > 300)
        assertEquals(setOf(0, 1, 2, 3), saved.gps.mapNotNull { it.vertexId }.toSet())
        assertEquals(4, saved.corners?.size)
        // Las esquinas van en metros respecto de la primera lectura enviada (así las usa el servidor)
        val g0 = saved.gps.first()
        val o = done.samples.first()
        val back = saved.corners!!.map { GpsMath.toEnu(listOf(GpsMath.fromEnu(it, g0.lat, g0.lon, 1.0, 0.0)), o.lat, o.lon).first() }
        back.zip(done.polygon).forEach { (b, p) -> assertTrue(Geometry.euclidean(b, p) < 0.01) }
        assertTrue(saved.corners!!.zip(done.polygon).any { (c, p) -> Geometry.euclidean(c, p) > 0.01 })
    }

    @Test
    @Evidencia(
        id = "FU-49", nombre = "Un toque doble en la misma esquina no la duplica", requisito = "RF-18",
        objetivo = "Verificar que tocar «Esquina» dos veces sin haber caminado solo cuenta una esquina.",
        entrada = "Caminar un lado, tocar «Esquina» dos veces seguidas",
        esperado = "1 esquina marcada",
    )
    fun toqueDoble() = runBlocking {
        val sim = Sim(seed = 3)
        sim.calibrateAndStart()
        sim.walk(truth[0], truth[1])
        sim.vm.fixCorner()
        sim.vm.fixCorner()
        val s = sim.vm.state.value
        ev.obtenido = "esquinas=${s.cornersFixed}, puntos de esquina=${s.cornerPoints.size}"
        assertEquals(1, s.cornersFixed)
        assertEquals(1, s.cornerPoints.size)
    }

    @Test
    @Evidencia(
        id = "FU-50", nombre = "Sin esquinas marcadas se usa el recorrido continuo", requisito = "RF-08",
        objetivo = "Verificar que, si el usuario no marca esquinas, el terreno sale igual del recorrido (cerrado en la partida).",
        entrada = "Mismo rectángulo sin tocar «Esquina»; termina en la partida",
        esperado = "Fase FINISHED, área 6000 m² ± 5 %, sin margen U95 (no hubo esquinas)",
    )
    fun sinEsquinas() = runBlocking {
        val sim = Sim(seed = 5)
        sim.calibrateAndStart()
        for (side in 0 until 4) sim.walk(truth[side], truth[(side + 1) % 4])
        sim.nowNs += 4_000_000_000L
        sim.still(truth[0], 4)
        sim.vm.finish()
        val done = sim.vm.state.value
        ev.obtenido = "fase=${done.phase}, área=${"%.1f".format(done.analysis?.areaM2)}, vértices=${done.polygon.size}, error=${done.error}"
        assertEquals(GpsPhase.FINISHED, done.phase)
        assertEquals(6000.0, done.analysis!!.areaM2, 300.0)
        assertNull(done.uncertainty)
    }

    @Test
    @Evidencia(
        id = "FU-79", nombre = "Esquinas al toque solo con GPS (bici, moto, carro)", requisito = "RF-18 / RF-19",
        objetivo = "Verificar que sin pasos (solo GPS) las esquinas al toque también anclan el polígono y cierran en la partida.",
        entrada = "Rectángulo 100×60 m con lecturas GPS en movimiento (σ 3 m) cada 2,5 m; esquinas al toque con 5 s quieto; vuelta a la partida",
        esperado = "4 vértices a < 3 m de los reales, área 6000 m² ± 3 %",
    )
    fun soloGps() = runBlocking {
        val gps = MutableSharedFlow<GpsSample>(extraBufferCapacity = 10_000)
        val rnd = Random(9)
        val vm = GpsViewModel(1, object : LocationSource { override val samples = gps }, FakeSurveyRepository(), startGate = { com.metrik.app.gps.GpsStartGate.off() }, calibrationTarget = 3)
        vm.setTransport("bike")
        vm.calibrate()
        var t = 0.0
        repeat(3) { gps.emit(GpsPrecisionTest.at(0.0, 0.0, t++, 2.5)) }
        vm.startRecording()
        for (side in 0 until 4) {
            val a = truth[side]
            val b = truth[(side + 1) % 4]
            val n = (Geometry.euclidean(a, b) / 2.5).toInt()
            for (k in 1..n) {
                val f = k.toDouble() / n
                t += 1.0
                gps.emit(
                    GpsPrecisionTest.at(a.x + (b.x - a.x) * f + rnd.nextGaussian() * 3, a.y + (b.y - a.y) * f + rnd.nextGaussian() * 3, t, 3.0)
                        .copy(speedMps = 2.5),
                )
            }
            if (side < 3) vm.fixCorner()
            repeat(5) { t += 1.0; gps.emit(GpsPrecisionTest.at(b.x + rnd.nextGaussian() * 1.5, b.y + rnd.nextGaussian() * 1.5, t, 2.5).copy(speedMps = 0.0)) }
        }
        vm.finish()
        val done = vm.state.value
        val errors = truth.map { tr -> done.polygon.minOf { Geometry.euclidean(it, tr) } }
        ev.obtenido = "esquinas=${done.cornersFixed}, errores=${errors.map { "%.1f".format(it) }}, área=${"%.1f".format(done.analysis?.areaM2)}, error=${done.error}"
        assertEquals(4, done.polygon.size)
        assertTrue(errors.all { it < 3.0 })
        assertEquals(6000.0, done.analysis!!.areaM2, 180.0)
    }
    @Test
    @Evidencia(
        id = "FU-86", nombre = "Brújula torcida al empezar: el terreno final sale bien", requisito = "RF-08 / RF-18 / RF-19",
        objetivo = "Verificar que, con la partida fija, un desvío fuerte de la brújula en los primeros pasos no estropea "
            + "el terreno guardado: al finalizar, cada tramo se corrige con su propio GPS y la figura cierra en la partida.",
        entrada = "Rectángulo 100×60 m como FU-48, con la brújula desviada 40° en los primeros 12 pasos",
        esperado = "4 vértices a < 3 m de los reales, área 6000 m² ± 3 %, figura cerrada en la partida",
    )
    fun brujulaTorcidaAlEmpezar() = runBlocking {
        val sim = Sim(seed = 11)
        sim.calibrateAndStart()
        for (side in 0 until 4) {
            val a = truth[side]
            val b = truth[(side + 1) % 4]
            sim.walk(a, b, transientSteps = if (side == 0) 12 else 0, transientDeg = 40.0)
            sim.nowNs += 4_000_000_000L
            if (side < 3) sim.vm.fixCorner()
            sim.still(b, 6)
        }
        sim.vm.finish()
        val done = sim.vm.state.value
        val errors = truth.map { tr -> done.polygon.minOf { Geometry.euclidean(it, tr) } }
        ev.obtenido = "errores=${errors.map { "%.1f".format(it) }}, área=${"%.1f".format(done.analysis?.areaM2)}, " +
            "cerrada=${done.closureConsistent}, error=${done.error}"
        assertEquals(4, done.polygon.size)
        assertTrue(errors.all { it < 3.0 })
        assertEquals(6000.0, done.analysis!!.areaM2, 180.0)
        assertEquals(true, done.closureConsistent)
    }
}
