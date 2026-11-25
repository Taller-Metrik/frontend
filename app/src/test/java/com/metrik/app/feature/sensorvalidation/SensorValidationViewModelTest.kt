package com.metrik.app.feature.sensorvalidation

import com.metrik.app.domain.GpsSample
import com.metrik.app.gps.GpsMath
import com.metrik.app.gps.LocationSource
import com.metrik.app.testing.MainDispatcherRule
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import kotlin.math.cos

class SensorValidationViewModelTest {
    @get:Rule val main = MainDispatcherRule()

    private class MemoryStore : SensorTrialStore {
        val trials = mutableListOf<SensorTrial>()
        override suspend fun all() = trials.toList()
        override suspend fun add(trial: SensorTrial) { trials += trial }
        override suspend fun delete(id: String) { trials.removeAll { it.id == id } }
    }

    private val gps = MutableSharedFlow<GpsSample>(extraBufferCapacity = 64)
    private val steps = MutableSharedFlow<Unit>(extraBufferCapacity = 256)
    private val heading = MutableSharedFlow<Double>(extraBufferCapacity = 64)
    private val store = MemoryStore()
    private var nowMs = 1_000_000_000_000L
    private var ids = 0
    private val exported = mutableListOf<Pair<String, String>>()

    private val refLat = -8.13
    private val refLon = -79.04
    private fun metersNorth(m: Double) = refLat + Math.toDegrees(m / GpsMath.EARTH_RADIUS_M)
    private fun metersEast(m: Double) = refLon + Math.toDegrees(m / (GpsMath.EARTH_RADIUS_M * cos(Math.toRadians(refLat))))
    private fun fix(lat: Double, lon: Double, offsetS: Int) = GpsSample(lat, lon, 4.0, nowMs / 1000.0 + offsetS, ageS = 0.4)

    private fun vm(
        headingFlow: kotlinx.coroutines.flow.Flow<Double>? = heading,
        stepsAvailable: Boolean = true,
    ) = SensorValidationViewModel(
        location = object : LocationSource { override val samples = gps },
        steps = steps, heading = headingFlow, store = store,
        export = { name, bytes -> exported += name to bytes.toString(Charsets.UTF_8); "Descargas/MetriK/$name" },
        device = "Cel de prueba", appVersion = "9.9.9", headingSource = "TYPE_GAME_ROTATION_VECTOR",
        stepsAvailable = stepsAvailable, nowMs = { nowMs }, newId = { "id${++ids}" },
    )

    private fun SensorValidationViewModel.setGpsRef() { onRefLat("$refLat"); onRefLon("$refLon") }

    // ── GPS ──

    @Test
    fun gps_con_distancias_de_3_y_4_m_da_rmse_de_3_54_y_se_guarda() {
        val vm = vm(); vm.setGpsRef()
        vm.startGps()
        assertTrue(vm.state.value.gpsRunning)
        gps.tryEmit(fix(metersNorth(3.0), refLon, 1))
        gps.tryEmit(fix(refLat, metersEast(4.0), 2))
        gps.tryEmit(fix(refLat, metersEast(4.0), 2)) // repetida
        assertEquals(2, vm.state.value.gpsValid)
        assertEquals(1, vm.state.value.gpsDiscarded)
        nowMs += 5_000
        vm.finishGps()
        val s = vm.state.value
        assertFalse(s.gpsRunning)
        val t = s.gpsResult!!
        assertEquals(3.54, t.observed, 0.01)
        assertEquals(t.observed, t.error, 0.0)
        assertEquals(5.0, t.durationS, 1e-9)
        assertEquals(2, t.readings.size)
        assertEquals(1, t.discardedDuplicate)
        assertEquals("Cel de prueba", t.device); assertEquals("9.9.9", t.appVersion); assertEquals("id1", t.id)
        assertTrue(t.config.contains("FusedLocationProvider"))
        assertEquals(listOf(t), store.trials)
        // al finalizar se detiene la captura: nuevas lecturas ya no cuentan
        gps.tryEmit(fix(refLat, refLon, 3))
        assertEquals(2, vm.state.value.trials.single().readings.size)
    }

    @Test
    fun gps_sin_lecturas_validas_muestra_sin_datos_y_no_guarda() {
        val vm = vm(); vm.setGpsRef()
        vm.startGps()
        gps.tryEmit(GpsSample(Double.NaN, refLon, 4.0, nowMs / 1000.0 + 1))
        vm.finishGps()
        assertEquals("Sin datos para calcular el RMSE", vm.state.value.gpsNotice)
        assertNull(vm.state.value.gpsResult)
        assertTrue(store.trials.isEmpty())
    }

    @Test
    fun gps_cancelado_no_guarda_y_detiene_la_captura() {
        val vm = vm(); vm.setGpsRef()
        vm.startGps()
        gps.tryEmit(fix(metersNorth(3.0), refLon, 1))
        vm.cancelGps()
        assertFalse(vm.state.value.gpsRunning)
        assertNotNull(vm.state.value.gpsNotice)
        gps.tryEmit(fix(metersNorth(3.0), refLon, 2))
        assertEquals(1, vm.state.value.gpsValid)
        assertTrue(store.trials.isEmpty())
    }

    @Test
    fun gps_con_referencia_invalida_no_empieza() {
        val vm = vm()
        vm.onRefLat("95"); vm.onRefLon("-79")
        vm.startGps()
        assertFalse(vm.state.value.gpsRunning)
        assertTrue(vm.state.value.error!!.contains("Latitud"))
        vm.onRefLat("-8"); vm.onRefLon("")
        vm.startGps()
        assertFalse(vm.state.value.gpsRunning)
        assertTrue(vm.state.value.error!!.contains("Longitud"))
    }

    @Test
    fun permiso_de_ubicacion_denegado_muestra_el_aviso() {
        val vm = vm(); vm.setGpsRef()
        vm.onLocationDenied()
        assertTrue(vm.state.value.error!!.contains("Permiso de ubicación"))
        assertFalse(vm.state.value.gpsRunning)
    }

    // ── Pasos ──

    @Test
    fun pasos_100_de_referencia_con_96_detectados_da_4_por_ciento() {
        val vm = vm()
        vm.startSteps()
        repeat(96) { steps.tryEmit(Unit) }
        assertEquals(96, vm.state.value.stepsDetected)
        vm.finishSteps()
        val t = vm.state.value.stepsResult!!
        assertEquals(100.0, t.reference, 0.0); assertEquals(96.0, t.observed, 0.0); assertEquals(4.0, t.error, 1e-9); assertEquals("%", t.unit)
        assertEquals(listOf(t), store.trials)
        steps.tryEmit(Unit)
        assertEquals(96, vm.state.value.stepsDetected) // detenido
    }

    @Test
    fun iniciar_reinicia_el_contador_del_ensayo() {
        val vm = vm()
        vm.startSteps(); repeat(10) { steps.tryEmit(Unit) }; vm.cancelSteps()
        vm.startSteps()
        assertEquals(0, vm.state.value.stepsDetected)
    }

    @Test
    fun finalizar_con_cero_pasos_es_un_ensayo_valido_con_error_100() {
        val vm = vm()
        vm.startSteps(); vm.finishSteps()
        val t = vm.state.value.stepsResult!!
        assertEquals(100.0, t.error, 1e-9)
        assertNull(vm.state.value.stepsNotice)
        assertEquals(1, store.trials.size)
    }

    @Test
    fun pasos_cancelado_no_se_confunde_con_cero_pasos() {
        val vm = vm()
        vm.startSteps(); vm.cancelSteps()
        assertNull(vm.state.value.stepsResult)
        assertTrue(vm.state.value.stepsNotice!!.contains("cancelado"))
        assertTrue(store.trials.isEmpty())
    }

    @Test
    fun pasos_rechaza_referencias_que_no_son_enteros_positivos() {
        val vm = vm()
        for (bad in listOf("0", "-3", "2.5", "abc", "")) {
            vm.onRefSteps(bad); vm.startSteps()
            assertFalse("«$bad»", vm.state.value.stepsRunning)
            assertNotNull(vm.state.value.error)
        }
    }

    @Test
    fun pasos_sin_acelerometro_avisa() {
        val vm = vm(stepsAvailable = false)
        vm.startSteps()
        assertFalse(vm.state.value.stepsRunning)
        assertTrue(vm.state.value.error!!.contains("acelerómetro"))
    }

    // ── Giro ──

    @Test
    fun giro_90_de_referencia_con_86_estimados_da_error_de_4() {
        val vm = vm()
        vm.markTurnStart()
        assertTrue(vm.state.value.turnWaiting)
        heading.tryEmit(10.0)
        assertTrue(vm.state.value.turnMarked)
        heading.tryEmit(50.0); heading.tryEmit(96.0)
        vm.markTurnEnd()
        val t = vm.state.value.turnResult!!
        assertEquals(90.0, t.reference, 0.0); assertEquals(86.0, t.observed, 1e-9); assertEquals(4.0, t.error, 1e-9); assertEquals("°", t.unit)
        assertEquals("RIGHT", t.turnSense)
        assertTrue(t.source.contains("TurnTracker") && t.source.contains("TYPE_GAME_ROTATION_VECTOR"))
        assertEquals(listOf(t), store.trials)
        heading.tryEmit(120.0)
        assertFalse(vm.state.value.turnMarked) // lectura detenida
    }

    @Test
    fun giro_que_cruza_180_se_mide_con_el_sentido_correcto() {
        val vm = vm()
        vm.markTurnStart()
        listOf(135.0, 170.0, 180.0, -170.0, -135.0).forEach { heading.tryEmit(it) }
        vm.markTurnEnd()
        val t = vm.state.value.turnResult!!
        assertEquals(90.0, t.observed, 1e-9)
        assertEquals(0.0, t.error, 1e-9)
    }

    @Test
    fun giro_a_la_izquierda_usa_referencia_negativa() {
        val vm = vm()
        vm.onTurnSense(TurnSense.LEFT)
        vm.markTurnStart()
        listOf(-135.0, -170.0, 180.0, 170.0, 136.0).forEach { heading.tryEmit(it) }
        vm.markTurnEnd()
        val t = vm.state.value.turnResult!!
        assertEquals(-90.0, t.reference, 0.0)
        assertEquals(-89.0, t.observed, 1e-9)
        assertEquals(1.0, t.error, 1e-9)
        assertEquals("LEFT", t.turnSense)
    }

    @Test
    fun giro_cancelado_no_guarda_y_sin_sensor_avisa() {
        val vm = vm()
        vm.markTurnStart(); heading.tryEmit(0.0); heading.tryEmit(30.0)
        vm.cancelTurn()
        assertNull(vm.state.value.turnResult)
        assertTrue(vm.state.value.turnNotice!!.contains("cancelado"))
        assertTrue(store.trials.isEmpty())

        val sinSensor = vm(headingFlow = null)
        sinSensor.markTurnStart()
        assertFalse(sinSensor.state.value.turnWaiting)
        assertTrue(sinSensor.state.value.error!!.contains("orientación"))
    }

    @Test
    fun giro_sin_lecturas_nuevas_entre_marcas_no_inventa_un_resultado() {
        val vm = vm()
        vm.markTurnStart(); heading.tryEmit(0.0)
        vm.markTurnEnd()
        assertNull(vm.state.value.turnResult)
        assertNotNull(vm.state.value.error)
        assertTrue(store.trials.isEmpty())
    }

    @Test
    fun giro_rechaza_angulos_de_referencia_invalidos() {
        val vm = vm()
        for (bad in listOf("0", "-10", "400", "x", "")) {
            vm.onRefTurn(bad); vm.markTurnStart()
            assertFalse("«$bad»", vm.state.value.turnWaiting)
            assertNotNull(vm.state.value.error)
        }
    }

    // ── Historial y exportación ──

    @Test
    fun exportar_genera_el_csv_y_eliminar_quita_el_ensayo() = runBlocking {
        val vm = vm()
        vm.startSteps(); repeat(96) { steps.tryEmit(Unit) }; vm.finishSteps()
        vm.exportCsv(null)
        val (name, csv) = exported.single()
        assertTrue(name.startsWith("MetriK_validacion_sensores_") && name.endsWith(".csv"))
        assertTrue(csv.startsWith(SensorTrialCsv.HEADER.joinToString(",")))
        assertTrue(csv.contains("PASOS,ERROR_RELATIVO_ABSOLUTO_PASOS,RESUMEN"))
        assertTrue(vm.state.value.message!!.contains("Descargas/MetriK"))
        vm.deleteTrial("id1")
        assertTrue(vm.state.value.trials.isEmpty())
        assertTrue(store.trials.isEmpty())
        vm.exportCsv(null)
        assertEquals(1, exported.size)
        assertEquals("No hay ensayos para exportar", vm.state.value.error)
    }
}
