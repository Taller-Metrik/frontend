package com.metrik.app.feature.sensorvalidation

import com.metrik.app.domain.GpsSample
import com.metrik.app.gps.GpsMath
import com.metrik.app.sensors.TurnTracker
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import kotlin.math.cos
import kotlin.math.sqrt

/** Los valores de ejemplo (3 m, 4 m, 96/100, 90°/86°) viven solo aquí: la app nunca los registra como resultados. */
class SensorMetricsTest {
    @get:Rule val tmp = TemporaryFolder()

    private val refLat = -8.13
    private val refLon = -79.04
    private val startS = 1_000_000_000.0

    private fun metersNorth(m: Double) = refLat + Math.toDegrees(m / GpsMath.EARTH_RADIUS_M)
    private fun metersEast(m: Double) = refLon + Math.toDegrees(m / (GpsMath.EARTH_RADIUS_M * cos(Math.toRadians(refLat))))

    private fun sample(lat: Double, lon: Double, t: Double, acc: Double = 4.0, age: Double? = 0.5) =
        GpsSample(lat, lon, acc, t, ageS = age)

    // ── RMSE ──

    @Test
    fun rmse_de_3_y_4_metros_es_3_54() {
        assertEquals(sqrt(12.5), SensorMetrics.rmse(listOf(3.0, 4.0))!!, 1e-12)
        assertEquals(3.54, SensorMetrics.rmse(listOf(3.0, 4.0))!!, 0.01)
    }

    @Test
    fun rmse_sin_distancias_es_nulo() = assertNull(SensorMetrics.rmse(emptyList()))

    @Test
    fun rmse_con_una_lectura_es_su_distancia() = assertEquals(2.5, SensorMetrics.rmse(listOf(2.5))!!, 1e-12)

    @Test
    fun el_colector_calcula_la_distancia_con_las_coordenadas_y_no_con_accuracy() {
        val c = GpsTrialCollector(refLat, refLon, startS)
        // accuracy enorme a propósito: no debe influir en la distancia ni en el RMSE
        c.offer(sample(metersNorth(3.0), refLon, startS + 1, acc = 50.0))
        c.offer(sample(refLat, metersEast(4.0), startS + 2, acc = 1.0))
        assertEquals(2, c.readings.size)
        assertEquals(3.0, c.readings[0].distanceM, 0.01)
        assertEquals(4.0, c.readings[1].distanceM, 0.01)
        assertEquals(3.54, c.rmse()!!, 0.01)
        assertEquals(50.0, c.readings[0].accuracyM, 0.0) // se guarda como dato auxiliar
    }

    @Test
    fun el_colector_clasifica_lecturas_invalidas_repetidas_y_obsoletas() {
        val c = GpsTrialCollector(refLat, refLon, startS)
        val ok = c.offer(sample(refLat, refLon + 0.00001, startS + 10))
        assertEquals(GpsTrialCollector.Outcome.ACCEPTED, ok)
        // inválidas
        assertEquals(GpsTrialCollector.Outcome.INVALID, c.offer(sample(Double.NaN, refLon, startS + 11)))
        assertEquals(GpsTrialCollector.Outcome.INVALID, c.offer(sample(95.0, refLon, startS + 11)))
        assertEquals(GpsTrialCollector.Outcome.INVALID, c.offer(sample(refLat, 181.0, startS + 11)))
        assertEquals(GpsTrialCollector.Outcome.INVALID, c.offer(sample(0.0, 0.0, startS + 11)))
        assertEquals(GpsTrialCollector.Outcome.INVALID, c.offer(sample(refLat, refLon, startS + 11, acc = 0.0)))
        assertEquals(GpsTrialCollector.Outcome.INVALID, c.offer(sample(refLat, refLon, startS + 11, acc = Double.NaN)))
        // repetida: misma marca de tiempo que una ya aceptada
        assertEquals(GpsTrialCollector.Outcome.DUPLICATE, c.offer(sample(refLat, refLon + 0.00002, startS + 10)))
        // obsoletas: llegó vieja, se tomó antes de empezar o es anterior a la última aceptada
        assertEquals(GpsTrialCollector.Outcome.STALE, c.offer(sample(refLat, refLon, startS + 12, age = 3.0)))
        assertEquals(GpsTrialCollector.Outcome.STALE, c.offer(sample(refLat, refLon, startS - 60)))
        assertEquals(GpsTrialCollector.Outcome.STALE, c.offer(sample(refLat, refLon, startS + 9)))
        assertEquals(1, c.readings.size)
        assertEquals(6, c.invalid)
        assertEquals(1, c.duplicates)
        assertEquals(3, c.stale)
        assertEquals(10, c.discarded)
    }

    @Test
    fun el_colector_sin_lecturas_validas_no_tiene_rmse() {
        val c = GpsTrialCollector(refLat, refLon, startS)
        c.offer(sample(Double.NaN, 0.0, startS + 1))
        assertNull(c.rmse())
    }

    @Test
    fun la_referencia_acepta_signos_menos_unicode_y_espacios() {
        assertEquals(-8.121336, SensorMetrics.parseCoordinate("−8.121336", -90.0, 90.0)!!, 0.0)
        assertEquals(-79.016578, SensorMetrics.parseCoordinate("–79.016578 ", -180.0, 180.0)!!, 0.0)
        assertEquals(-8.121336, SensorMetrics.parseCoordinate("-8.121336‎", -90.0, 90.0)!!, 0.0)
    }

    @Test
    fun validacion_de_la_referencia_gps() {
        assertEquals(-8.13, SensorMetrics.parseCoordinate("-8,13", -90.0, 90.0)!!, 0.0)
        assertNull(SensorMetrics.parseCoordinate("90.5", -90.0, 90.0))
        assertNull(SensorMetrics.parseCoordinate("-180.1", -180.0, 180.0))
        assertNull(SensorMetrics.parseCoordinate("", -90.0, 90.0))
        assertNull(SensorMetrics.parseCoordinate("abc", -90.0, 90.0))
        assertNull(SensorMetrics.parseCoordinate("NaN", -90.0, 90.0))
        assertEquals(90.0, SensorMetrics.parseCoordinate("90", -90.0, 90.0)!!, 0.0)
    }

    // ── Pasos ──

    @Test
    fun referencia_100_con_96_detectados_da_4_por_ciento() =
        assertEquals(4.0, SensorMetrics.stepErrorPct(100, 96), 1e-9)

    @Test
    fun error_de_pasos_es_absoluto_y_con_cero_detectados_es_100() {
        assertEquals(4.0, SensorMetrics.stepErrorPct(100, 104), 1e-9)
        assertEquals(100.0, SensorMetrics.stepErrorPct(100, 0), 1e-9)
        assertEquals(20.0, SensorMetrics.stepErrorPct(50, 60), 1e-9)
    }

    @Test
    fun referencia_de_pasos_cero_no_se_acepta() {
        assertThrows(IllegalArgumentException::class.java) { SensorMetrics.stepErrorPct(0, 5) }
        assertNull(SensorMetrics.parseSteps("0"))
    }

    @Test
    fun los_pasos_de_referencia_son_solo_enteros_positivos() {
        assertEquals(100, SensorMetrics.parseSteps("100"))
        assertEquals(12, SensorMetrics.parseSteps(" 12 "))
        for (bad in listOf("", "  ", "-5", "3.5", "3,5", "abc", "+4", "1e2", "99999999999")) assertNull("«$bad»", SensorMetrics.parseSteps(bad))
    }

    // ── Giro ──

    @Test
    fun giro_de_90_con_estimado_86_da_error_de_4_grados() =
        assertEquals(4.0, SensorMetrics.turnErrorDeg(86.0, SensorMetrics.signedReference(90.0, TurnSense.RIGHT)), 1e-9)

    @Test
    fun el_sentido_cambia_el_signo_de_la_referencia() {
        assertEquals(90.0, SensorMetrics.signedReference(90.0, TurnSense.RIGHT), 0.0)
        assertEquals(-90.0, SensorMetrics.signedReference(90.0, TurnSense.LEFT), 0.0)
        // girar a la izquierda 86° cuando se pidió a la derecha 90°: no es un error de 4°
        assertEquals(176.0, SensorMetrics.turnErrorDeg(-86.0, 90.0), 1e-9)
    }

    /** Gira como lo hace la app: TurnTracker con el rumbo del sensor y el giro crudo de `corner()`. */
    private fun rawTurn(vararg headings: Double): Double {
        val t = TurnTracker()
        t.onHeading(headings.first())
        t.corner() // marca de inicio
        headings.drop(1).forEach(t::onHeading)
        return t.corner()!!.first
    }

    @Test
    fun giro_a_la_derecha_que_cruza_180_no_da_salto() {
        // 135° → 170° → 180° → −170° → −135°: giro horario de 90° (sin desenrollar daría −270°)
        val raw = rawTurn(135.0, 170.0, 180.0, -170.0, -135.0)
        assertEquals(90.0, raw, 1e-9)
        assertEquals(0.0, SensorMetrics.turnErrorDeg(raw, SensorMetrics.signedReference(90.0, TurnSense.RIGHT)), 1e-9)
    }

    @Test
    fun giro_a_la_izquierda_que_cruza_180_conserva_el_sentido() {
        val raw = rawTurn(-135.0, -170.0, 180.0, 170.0, 135.0)
        assertEquals(-90.0, raw, 1e-9)
        assertEquals(0.0, SensorMetrics.turnErrorDeg(raw, SensorMetrics.signedReference(90.0, TurnSense.LEFT)), 1e-9)
        assertEquals(-20.0, rawTurn(-170.0, 180.0, 170.0), 1e-9)
    }

    @Test
    fun giro_estimado_de_86_se_mide_desde_la_marca_de_inicio() {
        val raw = rawTurn(10.0, 40.0, 96.0)
        assertEquals(86.0, raw, 1e-9)
        assertEquals(4.0, SensorMetrics.turnErrorDeg(raw, 90.0), 1e-9)
    }

    @Test
    fun el_giro_se_mide_en_crudo_sin_ajustar_a_90() {
        // TurnTracker.corner() devuelve (crudo, ajustado): la validación usa el crudo
        val t = TurnTracker()
        t.onHeading(0.0); t.corner()
        t.onHeading(86.0)
        val (raw, snapped) = t.corner()!!
        assertEquals(86.0, raw, 1e-9)
        assertEquals(90.0, snapped, 1e-9)
    }

    @Test
    fun validacion_del_angulo_de_referencia() {
        assertEquals(90.0, SensorMetrics.parseAngle("90")!!, 0.0)
        assertEquals(45.5, SensorMetrics.parseAngle("45,5")!!, 0.0)
        assertEquals(360.0, SensorMetrics.parseAngle("360")!!, 0.0)
        for (bad in listOf("", "0", "-90", "361", "abc", "NaN")) assertNull("«$bad»", SensorMetrics.parseAngle(bad))
    }

    // ── CSV y almacenamiento ──

    private fun gpsTrial(id: String = "t1"): SensorTrial {
        val c = GpsTrialCollector(refLat, refLon, startS)
        c.offer(sample(metersNorth(3.0), refLon, startS + 1))
        c.offer(sample(refLat, metersEast(4.0), startS + 2))
        c.offer(sample(metersNorth(2.0), metersEast(2.0), startS + 3, acc = 3.0).copy(satellites = 12, cn0DbHz = 31.5))
        val rmse = c.rmse()!!
        return SensorTrial(
            id, 1_700_000_000_000L, "Marca, Modelo \"X\"", "0.1.0", TrialKind.GPS, 3.0, 0.0, rmse, rmse, "m",
            "FusedLocationProvider", "config, con coma", c.refLat, c.refLon, c.readings.toList(),
            c.invalid, c.duplicates, c.stale,
        )
    }

    private fun parseCsv(csv: String): List<List<String>> {
        val rows = mutableListOf<List<String>>()
        var row = mutableListOf<String>(); val cell = StringBuilder(); var quoted = false; var i = 0
        while (i < csv.length) {
            val ch = csv[i]
            when {
                quoted && ch == '"' && csv.getOrNull(i + 1) == '"' -> { cell.append('"'); i++ }
                ch == '"' -> quoted = !quoted
                !quoted && ch == ',' -> { row += cell.toString(); cell.clear() }
                !quoted && ch == '\r' -> Unit
                !quoted && ch == '\n' -> { row += cell.toString(); cell.clear(); rows += row; row = mutableListOf() }
                else -> cell.append(ch)
            }
            i++
        }
        return rows
    }

    @Test
    fun el_csv_permite_recalcular_el_rmse_de_gps() {
        val t = gpsTrial()
        val rows = parseCsv(SensorTrialCsv.build(listOf(t)))
        val header = rows.first()
        assertEquals(SensorTrialCsv.HEADER, header)
        rows.forEach { assertEquals(header.size, it.size) }
        val col = header.withIndex().associate { it.value to it.index }
        val lecturas = rows.drop(1).filter { it[col.getValue("fila")] == "LECTURA" }
        val resumen = rows.drop(1).single { it[col.getValue("fila")] == "RESUMEN" }
        assertEquals(3, lecturas.size)
        // la distancia de cada fila se recalcula con las coordenadas exportadas
        for (r in lecturas) {
            val d = GpsMath.haversine(
                r[col.getValue("lat_ref")].toDouble(), r[col.getValue("lon_ref")].toDouble(),
                r[col.getValue("lat_obs")].toDouble(), r[col.getValue("lon_obs")].toDouble(),
            )
            assertEquals(r[col.getValue("distancia_m")].toDouble(), d, 1e-9)
        }
        // y el RMSE del resumen, con las distancias exportadas
        val rmse = SensorMetrics.rmse(lecturas.map { it[col.getValue("distancia_m")].toDouble() })!!
        assertEquals(resumen[col.getValue("valor_observado")].toDouble(), rmse, 1e-12)
        assertEquals(t.observed, resumen[col.getValue("valor_observado")].toDouble(), 0.0)
        assertEquals("m", resumen[col.getValue("unidad")])
        assertEquals("3", resumen[col.getValue("lecturas_validas")])
        assertEquals("Marca, Modelo \"X\"", resumen[col.getValue("dispositivo")])
        assertEquals("config, con coma", resumen[col.getValue("configuracion")])
        assertEquals("2023-11-14T22:13:20Z", resumen[col.getValue("fecha_utc")])
        assertEquals("12", lecturas[2][col.getValue("satelites")])
    }

    @Test
    fun el_csv_de_pasos_y_giro_tiene_una_fila_resumen_con_referencia_observado_error_y_unidad() {
        val pasos = SensorTrial("p1", 0L, "Cel", "0.1.0", TrialKind.STEPS, 40.0, 100.0, 96.0, 4.0, "%", "StepDetector", "cfg")
        val giro = SensorTrial("g1", 0L, "Cel", "0.1.0", TrialKind.TURN, 5.0, -90.0, -86.0, 4.0, "°", "TurnTracker sobre X", "cfg", turnSense = "LEFT")
        val rows = parseCsv(SensorTrialCsv.build(listOf(pasos, giro)))
        val col = rows.first().withIndex().associate { it.value to it.index }
        assertEquals(3, rows.size) // cabecera + una fila por ensayo
        val (p, g) = rows[1] to rows[2]
        assertEquals(listOf("PASOS", "100", "96", "4", "%"), listOf("tipo", "valor_referencia", "valor_observado", "error", "unidad").map { p[col.getValue(it)] })
        assertEquals(listOf("GIRO", "-90", "-86", "4", "°", "LEFT"), listOf("tipo", "valor_referencia", "valor_observado", "error", "unidad", "sentido_giro").map { g[col.getValue(it)] })
        assertEquals("TurnTracker sobre X", g[col.getValue("fuente")])
    }

    @Test
    fun el_csv_no_redondea_los_numeros() {
        val v = 0.1 + 0.2
        assertEquals(v, SensorTrialCsv.num(v).toDouble(), 0.0)
        val tiny = 1.2345678901234567E-5
        assertEquals(tiny, SensorTrialCsv.num(tiny).toDouble(), 0.0)
        val lat = -8.123456789012345
        assertEquals(lat, SensorTrialCsv.num(lat).toDouble(), 0.0)
        assertTrue(SensorTrialCsv.num(lat).length >= 15) // sin redondeo a pocos decimales
        assertEquals("", SensorTrialCsv.num(null))
    }

    @Test
    fun los_ensayos_se_guardan_y_se_leen_del_archivo_local() = runBlocking {
        val file = File(tmp.root, "sub/trials.json")
        val store = JsonSensorTrialStore(file)
        assertTrue(store.all().isEmpty())
        val a = gpsTrial("a")
        val b = SensorTrial("b", 5L, "Cel", "0.1.0", TrialKind.STEPS, 1.0, 100.0, 96.0, 4.0, "%", "s", "c")
        store.add(a); store.add(b)
        assertEquals(listOf(a, b), JsonSensorTrialStore(file).all()) // otra instancia = sobrevive a reabrir la app
        store.delete("a")
        assertEquals(listOf(b), store.all())
    }

    @Test
    fun un_archivo_dañado_no_rompe_la_lectura() = runBlocking {
        val file = File(tmp.root, "bad.json").apply { writeText("{no es json") }
        val store = JsonSensorTrialStore(file)
        assertTrue(store.all().isEmpty())
        store.add(gpsTrial("z"))
        assertNotNull(store.all().singleOrNull())
    }
}
