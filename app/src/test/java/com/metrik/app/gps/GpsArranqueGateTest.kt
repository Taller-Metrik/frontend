package com.metrik.app.gps

import com.metrik.app.core.geometry.Geometry
import com.metrik.app.core.geometry.Point
import com.metrik.app.domain.GpsSample
import com.metrik.app.testing.EvidenceRule
import com.metrik.app.testing.Evidencia
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.util.Random
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.sin

/** Arranque del GPS: la primera medida tras encenderlo no debe correr la partida (ni el dibujo al caminar). */
class GpsArranqueGateTest {
    @get:Rule val ev = EvidenceRule()
    private companion object {
        const val LAT0 = -8.13 // igual que GpsPrecisionTest.at
        const val LON0 = -79.04
    }

    private fun at(x: Double, y: Double, t: Double, acc: Double = 3.5, sats: Int? = 9, cn0: Double? = 35.0) =
        GpsPrecisionTest.at(x, y, t, acc).copy(satellites = sats, cn0DbHz = cn0)

    /**
     * Celular quieto en (0, 0) recién encendido: la posición arranca corrida [offsetM] al noreste y se acerca sola
     * con constante de tiempo [tauS] (convergencia del receptor), más ruido σ = 1 m. Precisión informada 3,5 m todo el rato.
     */
    private fun coldStart(seed: Long, offsetM: Double = 12.0, tauS: Double = 8.0, seconds: Int = 90): List<GpsSample> {
        val rnd = Random(seed)
        return (0 until seconds).map { t ->
            val d = offsetM * exp(-t / tauS) / Math.sqrt(2.0)
            at(d + rnd.nextGaussian(), d + rnd.nextGaussian(), t.toDouble())
        }
    }

    /** Partida con el detector: lecturas ofrecidas hasta juntar [target] que cuenten. */
    private fun gatedStart(samples: List<GpsSample>, gate: GpsStartGate = GpsStartGate(), target: Int = 15): Pair<Point, Double> {
        val counted = mutableListOf<GpsSample>()
        var tEnd = 0.0
        for (s in samples) {
            counted += gate.offer(s)
            tEnd = s.timestampS
            if (counted.size >= target) break
        }
        val p = robustStart(counted)!!.sample
        return GpsMath.toEnu(listOf(p), LAT0, LON0).first() to tEnd
    }

    @Test
    @Evidencia(
        id = "FU-95", nombre = "La partida no se corre con el arranque del GPS", requisito = "RF-08, RF-09",
        objetivo = "Verificar que las primeras lecturas tras encender el GPS (corridas aunque informen buena precisión) "
            + "no se usan para la partida: se espera a que la posición se estabilice.",
        entrada = "Celular quieto; el GPS arranca corrido 12 m y se acerca solo (τ = 8 s), ruido σ 1 m, precisión 3,5 m; 20 semillas",
        esperado = "Antes (promedio de las 15 primeras): error de la partida > 4 m; ahora < 2,5 m en promedio y < 4 m en el peor caso",
    )
    fun partidaSinArranque() {
        val runs = (1L..20L).map { seed ->
            val samples = coldStart(seed)
            val old = GpsMath.toEnu(listOf(GpsMath.startPoint(samples.take(15))!!), LAT0, LON0).first()
            val (now, tEnd) = gatedStart(samples)
            Triple(Geometry.euclidean(old, Point(0.0, 0.0)), Geometry.euclidean(now, Point(0.0, 0.0)), tEnd)
        }
        val oldMean = runs.map { it.first }.average()
        val newMean = runs.map { it.second }.average()
        val newWorst = runs.maxOf { it.second }
        ev.obtenido = "antes: error medio ${"%.1f".format(oldMean)} m; ahora: medio ${"%.1f".format(newMean)} m, " +
            "peor ${"%.1f".format(newWorst)} m, tarda en promedio ${"%.0f".format(runs.map { it.third }.average())} s"
        assertTrue("antes debía fallar: $oldMean", oldMean > 4.0)
        assertTrue("error medio $newMean", newMean < 2.5)
        assertTrue("peor caso $newWorst", newWorst < 4.0)
    }

    @Test
    @Evidencia(
        id = "FU-96", nombre = "Sin lecturas de Wi-Fi o red celular ni de calentamiento en la partida", requisito = "RF-08, RF-20",
        objetivo = "Verificar que las lecturas de los primeros 5 s y las que no traen datos de satélites (Wi-Fi o red "
            + "celular) no cuentan para la partida, salvo que se agote el tope de 60 s.",
        entrada = "A) lecturas buenas en t = 0..4 s; B) lecturas sin satélites en t = 5..30 s; C) las mismas hasta t = 61 s",
        esperado = "A) 0 cuentan (calentando); B) 0 cuentan (estabilizando); C) al llegar a 60 s cuentan y queda «sin estabilizar»",
    )
    fun calentamientoYSinSatelites() {
        val gate = GpsStartGate()
        val a = (0..4).sumOf { gate.offer(at(0.0, 0.0, it.toDouble())).size }
        val statusA = gate.status
        val b = (5..30).sumOf { gate.offer(at(0.0, 0.0, it.toDouble(), sats = null, cn0 = null)).size }
        val c = (31..61).sumOf { gate.offer(at(0.0, 0.0, it.toDouble(), sats = null, cn0 = null)).size }
        ev.obtenido = "A) $a ($statusA); B) $b; C) $c (${gate.status})"
        assertEquals(0, a)
        assertEquals(GpsStartGate.Status.WARMING_UP, statusA)
        assertEquals(0, b)
        assertTrue(c > 0)
        assertEquals(GpsStartGate.Status.UNSTABLE, gate.status)
    }

    @Test
    @Evidencia(
        id = "FU-97", nombre = "Un rebote en la medición de precisión no mueve la partida", requisito = "RF-08",
        objetivo = "Verificar que la partida es un centro robusto: un rebote de 15 m entre lecturas buenas se descarta.",
        entrada = "15 lecturas en (0, 0) con ruido ±0,7 m y precisión 3 m, una de ellas a 15 m con precisión 2 m",
        esperado = "Antes (promedio 1/precisión²): la partida se corre > 1,5 m; ahora < 0,5 m",
    )
    fun reboteNoMueveLaPartida() {
        val rnd = Random(7)
        val good = (0 until 14).map { at(rnd.nextGaussian() * 0.7, rnd.nextGaussian() * 0.7, it.toDouble(), acc = 3.0) }
        val samples = good.take(7) + at(15.0, 0.0, 7.5, acc = 2.0) + good.drop(7)
        fun err(p: GpsSample) = Geometry.euclidean(GpsMath.toEnu(listOf(p), LAT0, LON0).first(), Point(0.0, 0.0))
        val old = err(GpsMath.startPoint(samples)!!)
        val now = err(robustStart(samples)!!.sample)
        ev.obtenido = "antes ${"%.2f".format(old)} m, ahora ${"%.2f".format(now)} m"
        assertTrue("antes $old", old > 1.5)
        assertTrue("ahora $now", now < 0.5)
    }

    /** Rectángulo 40 × 30 m a pie con brújula desviada 15° y GPS que llega [lagS] tarde; devuelve (salto máx., error medio). */
    private fun walk(seed: Long, lagS: Double, useTime: Boolean): Pair<Double, Double> {
        val rnd = Random(seed)
        val corners = listOf(Point(0.0, 0.0), Point(0.0, 40.0), Point(30.0, 40.0), Point(30.0, 0.0), Point(0.0, 0.0))
        val f = PdrGpsFusion(0.70, 2.0)
        val truthAt = mutableListOf(0.0 to Point(0.0, 0.0))
        var truth = Point(0.0, 0.0)
        var t = 0.0
        var steps = 0
        var maxJump = 0.0
        var sumErr = 0.0
        var nErr = 0
        for ((a, b) in corners.zipWithNext()) {
            val heading = Math.atan2(b.x - a.x, b.y - a.y)
            repeat((Geometry.euclidean(a, b) / 0.77).toInt()) {
                t += 0.55
                truth = Point(truth.x + 0.77 * sin(heading), truth.y + 0.77 * cos(heading))
                truthAt += t to truth
                f.onStep(heading + Math.toRadians(15.0), t)
                if (++steps % 2 == 0) {
                    // El fix es de hace lagS segundos: dónde estaba la persona entonces
                    val fixT = t - lagS
                    val fixTruth = truthAt.last { it.first <= fixT }.second
                    val before = f.position
                    f.onGps(Point(fixTruth.x + rnd.nextGaussian() * 1.5, fixTruth.y + rnd.nextGaussian() * 1.5), 3.0, if (useTime) fixT else null)
                    maxJump = maxOf(maxJump, Geometry.euclidean(before, f.position))
                    sumErr += Geometry.euclidean(f.position, truth); nErr++
                }
            }
        }
        return maxJump to sumErr / nErr
    }

    @Test
    @Evidencia(
        id = "FU-98", nombre = "El dibujo sigue la caminata aunque el GPS llegue atrasado", requisito = "RF-07, RF-08",
        objetivo = "Verificar que cada lectura GPS se compara con donde estaban los pasos cuando se tomó (no con el último "
            + "paso) y que el giro y la escala se corrigen sin saltos desde la primera lectura.",
        entrada = "Rectángulo 40 × 30 m; brújula desviada 15°; paso real 0,77 m (supuesto 0,70 m); GPS σ 1,5 m cada 2 pasos con 1 s de atraso; 10 semillas",
        esperado = "Con la hora del fix el error medio de la posición dibujada es menor que sin ella; salto por lectura < 1,5 m",
    )
    fun dibujoConGpsAtrasado() {
        val with = (1L..10L).map { walk(it, lagS = 1.0, useTime = true) }
        val without = (1L..10L).map { walk(it, lagS = 1.0, useTime = false) }
        val errWith = with.map { it.second }.average()
        val errWithout = without.map { it.second }.average()
        val jump = with.maxOf { it.first }
        ev.obtenido = "error medio dibujado: con hora ${"%.2f".format(errWith)} m, sin hora ${"%.2f".format(errWithout)} m; " +
            "salto máx. ${"%.2f".format(jump)} m"
        assertTrue("con hora $errWith vs sin hora $errWithout", errWith < errWithout)
        assertTrue("salto $jump", jump < 1.5)
    }
}
