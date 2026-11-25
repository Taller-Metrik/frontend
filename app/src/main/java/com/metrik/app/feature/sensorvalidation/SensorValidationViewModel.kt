package com.metrik.app.feature.sensorvalidation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.metrik.app.gps.FusedLocationSource
import com.metrik.app.gps.LocationSource
import com.metrik.app.sensors.TurnTracker
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.UUID

enum class SensorTab(val label: String) { GPS("GPS"), STEPS("Pasos"), TURN("Giro") }

data class SensorValidationState(
    val tab: SensorTab = SensorTab.GPS,
    val trials: List<SensorTrial> = emptyList(),
    val error: String? = null,
    val message: String? = null,

    // GPS
    val refLatText: String = "",
    val refLonText: String = "",
    val gpsRunning: Boolean = false,
    val gpsValid: Int = 0,
    val gpsDiscarded: Int = 0,
    val gpsResult: SensorTrial? = null,
    val gpsNotice: String? = null,

    // Pasos
    val refStepsText: String = "100",
    val stepsRunning: Boolean = false,
    val stepsDetected: Int = 0,
    val stepsResult: SensorTrial? = null,
    val stepsNotice: String? = null,

    // Giro
    val refTurnText: String = "90",
    val turnSense: TurnSense = TurnSense.RIGHT,
    /** Se pidió marcar el inicio y se espera la primera lectura de orientación. */
    val turnWaiting: Boolean = false,
    val turnMarked: Boolean = false,
    val turnResult: SensorTrial? = null,
    val turnNotice: String? = null,
)

/**
 * Ensayos de validación de sensores (GPS, pasos, giro). Reutiliza las fuentes de la app: el proveedor GPS que usa el
 * recorrido ([location], antes de `GpsTrackFilter`), el detector de pasos del modo interior ([steps]) y el rumbo con
 * `TurnTracker` ([heading], el mismo flujo que recibe `MeasureViewModel`). Los ensayos se guardan solo en el celular.
 */
class SensorValidationViewModel(
    private val location: LocationSource,
    private val steps: Flow<Unit>,
    private val heading: Flow<Double>?,
    private val store: SensorTrialStore,
    private val export: suspend (fileName: String, bytes: ByteArray) -> String,
    private val device: String,
    private val appVersion: String,
    private val headingSource: String = "no disponible",
    private val stepsAvailable: Boolean = true,
    private val gpsConfig: String = FusedLocationSource.CONFIG_DESCRIPTION,
    private val stepsConfig: String = STEPS_CONFIG,
    private val nowMs: () -> Long = System::currentTimeMillis,
    private val newId: () -> String = { UUID.randomUUID().toString().take(8) },
) : ViewModel() {
    private val _state = MutableStateFlow(SensorValidationState())
    val state: StateFlow<SensorValidationState> = _state

    private var gpsJob: Job? = null
    private var gpsCollector: GpsTrialCollector? = null
    private var gpsStartedMs = 0L

    private var stepsJob: Job? = null
    private var stepsStartedMs = 0L

    private val turns = TurnTracker()
    private var turnJob: Job? = null
    private var turnStartedMs = 0L
    private var headingsSinceMark = 0

    init {
        viewModelScope.launch { _state.update { it.copy(trials = store.all()) } }
    }

    fun onTab(tab: SensorTab) = _state.update { it.copy(tab = tab, error = null, message = null) }
    fun onRefLat(text: String) = _state.update { it.copy(refLatText = text) }
    fun onRefLon(text: String) = _state.update { it.copy(refLonText = text) }
    fun onRefSteps(text: String) = _state.update { it.copy(refStepsText = text) }
    fun onRefTurn(text: String) = _state.update { it.copy(refTurnText = text) }
    fun onTurnSense(sense: TurnSense) = _state.update { it.copy(turnSense = sense) }

    // ───────────────────────────── GPS ─────────────────────────────

    /** Valida la referencia (latitud/longitud) antes de pedir el permiso o empezar. */
    fun gpsInputsValid(): Boolean {
        val s = _state.value
        val lat = SensorMetrics.parseCoordinate(s.refLatText, -90.0, 90.0)
        val lon = SensorMetrics.parseCoordinate(s.refLonText, -180.0, 180.0)
        val msg = when {
            lat == null -> "Latitud de referencia no válida (entre −90 y 90)"
            lon == null -> "Longitud de referencia no válida (entre −180 y 180)"
            else -> null
        }
        _state.update { it.copy(error = msg) }
        return msg == null
    }

    fun onLocationDenied() = _state.update {
        it.copy(error = "Permiso de ubicación denegado: actívalo en los ajustes del celular para medir el GPS", gpsRunning = false)
    }

    fun startGps() {
        if (_state.value.gpsRunning || !gpsInputsValid()) return
        val lat = SensorMetrics.parseCoordinate(_state.value.refLatText, -90.0, 90.0)!!
        val lon = SensorMetrics.parseCoordinate(_state.value.refLonText, -180.0, 180.0)!!
        gpsStartedMs = nowMs()
        val collector = GpsTrialCollector(lat, lon, startedAtEpochS = gpsStartedMs / 1000.0)
        gpsCollector = collector
        _state.update { it.copy(gpsRunning = true, gpsValid = 0, gpsDiscarded = 0, gpsResult = null, gpsNotice = null, error = null, message = null) }
        gpsJob = viewModelScope.launch {
            try {
                location.samples.collect { sample ->
                    collector.offer(sample)
                    _state.update { it.copy(gpsValid = collector.readings.size, gpsDiscarded = collector.discarded) }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(gpsRunning = false, error = "No se pudo leer el GPS: ${e.message ?: e::class.simpleName}") }
            }
        }
    }

    fun finishGps() {
        val collector = gpsCollector ?: return
        if (!_state.value.gpsRunning) return
        stopGps()
        val rmse = collector.rmse()
        if (rmse == null) {
            _state.update { it.copy(gpsNotice = "Sin datos para calcular el RMSE", gpsResult = null) }
            return
        }
        val trial = newTrial(
            kind = TrialKind.GPS, durationS = (nowMs() - gpsStartedMs) / 1000.0,
            reference = 0.0, observed = rmse, error = rmse,
            source = "FusedLocationProvider (LocationSource de MetriK)", config = gpsConfig,
        ).copy(
            refLat = collector.refLat, refLon = collector.refLon, readings = collector.readings.toList(),
            discardedInvalid = collector.invalid, discardedDuplicate = collector.duplicates, discardedStale = collector.stale,
        )
        save(trial) { it.copy(gpsResult = trial) }
    }

    fun cancelGps() {
        if (!_state.value.gpsRunning) return
        stopGps()
        _state.update { it.copy(gpsNotice = "Ensayo cancelado: no se guardó", gpsResult = null) }
    }

    private fun stopGps() {
        gpsJob?.cancel(); gpsJob = null
        _state.update { it.copy(gpsRunning = false) }
    }

    // ───────────────────────────── Pasos ─────────────────────────────

    fun startSteps() {
        if (_state.value.stepsRunning) return
        if (SensorMetrics.parseSteps(_state.value.refStepsText) == null) {
            _state.update { it.copy(error = "Los pasos de referencia deben ser un entero positivo") }
            return
        }
        if (!stepsAvailable) {
            _state.update { it.copy(error = "Este celular no tiene acelerómetro: no se pueden contar pasos") }
            return
        }
        stepsStartedMs = nowMs()
        _state.update { it.copy(stepsRunning = true, stepsDetected = 0, stepsResult = null, stepsNotice = null, error = null, message = null) }
        stepsJob = viewModelScope.launch {
            try {
                steps.collect { _state.update { s -> s.copy(stepsDetected = s.stepsDetected + 1) } }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(stepsRunning = false, error = "No se pudo leer el acelerómetro: ${e.message ?: e::class.simpleName}") }
            }
        }
    }

    /** Un ensayo finalizado con 0 pasos detectados es válido (error 100 %); uno cancelado no se guarda. */
    fun finishSteps() {
        if (!_state.value.stepsRunning) return
        val reference = SensorMetrics.parseSteps(_state.value.refStepsText) ?: return
        stopSteps()
        val detected = _state.value.stepsDetected
        val error = SensorMetrics.stepErrorPct(reference, detected)
        val trial = newTrial(
            kind = TrialKind.STEPS, durationS = (nowMs() - stepsStartedMs) / 1000.0,
            reference = reference.toDouble(), observed = detected.toDouble(), error = error,
            source = "StepDetector (el mismo del modo interior por pasos)", config = stepsConfig,
        )
        save(trial) { it.copy(stepsResult = trial) }
    }

    fun cancelSteps() {
        if (!_state.value.stepsRunning) return
        stopSteps()
        _state.update { it.copy(stepsNotice = "Ensayo cancelado: no se guardó", stepsResult = null) }
    }

    private fun stopSteps() {
        stepsJob?.cancel(); stepsJob = null
        _state.update { it.copy(stepsRunning = false) }
    }

    // ───────────────────────────── Giro ─────────────────────────────

    /**
     * Empieza a leer la orientación; al llegar la primera lectura queda marcado el inicio (el giro estimado se mide
     * desde ahí con `TurnTracker`, que desenrolla el cruce de ±180°).
     */
    fun markTurnStart() {
        val s = _state.value
        if (s.turnMarked || s.turnWaiting) return
        if (SensorMetrics.parseAngle(s.refTurnText) == null) {
            _state.update { it.copy(error = "El ángulo de referencia debe ser mayor que 0 y hasta 360°") }
            return
        }
        val flow = heading
        if (flow == null) {
            _state.update { it.copy(error = "Este celular no tiene sensor de orientación: no se puede medir el giro") }
            return
        }
        turns.reset()
        headingsSinceMark = 0
        turnStartedMs = nowMs()
        _state.update { it.copy(turnWaiting = true, turnMarked = false, turnResult = null, turnNotice = null, error = null, message = null) }
        turnJob = viewModelScope.launch {
            try {
                flow.collect { deg ->
                    turns.onHeading(deg)
                    if (_state.value.turnWaiting) {
                        turns.corner() // fija la referencia de «inicio» (el giro crudo se mide desde aquí)
                        headingsSinceMark = 0
                        turnStartedMs = nowMs()
                        _state.update { it.copy(turnWaiting = false, turnMarked = true) }
                    } else headingsSinceMark++
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                stopTurn()
                _state.update { it.copy(error = "No se pudo leer la orientación: ${e.message ?: e::class.simpleName}") }
            }
        }
    }

    fun markTurnEnd() {
        val s = _state.value
        if (!s.turnMarked) return
        val angle = SensorMetrics.parseAngle(s.refTurnText) ?: return
        if (headingsSinceMark == 0) {
            _state.update { it.copy(error = "Aún no hay lecturas de orientación nuevas: gira el celular y vuelve a marcar el fin") }
            return
        }
        val estimated = turns.corner()?.first // giro crudo (antes del ajuste a 90°), con signo: + horario
        stopTurn()
        if (estimated == null) {
            _state.update { it.copy(error = "Sin lecturas de orientación") }
            return
        }
        val reference = SensorMetrics.signedReference(angle, s.turnSense)
        val trial = newTrial(
            kind = TrialKind.TURN, durationS = (nowMs() - turnStartedMs) / 1000.0,
            reference = reference, observed = estimated, error = SensorMetrics.turnErrorDeg(estimated, reference),
            source = "TurnTracker sobre $headingSource",
            config = "Giro crudo (turnRawDeg, sin ajuste a 90°); convención: positivo = derecha (horario), negativo = izquierda",
        ).copy(turnSense = s.turnSense.name)
        save(trial) { it.copy(turnResult = trial) }
    }

    fun cancelTurn() {
        val s = _state.value
        if (!s.turnMarked && !s.turnWaiting) return
        stopTurn()
        _state.update { it.copy(turnNotice = "Ensayo cancelado: no se guardó", turnResult = null) }
    }

    private fun stopTurn() {
        turnJob?.cancel(); turnJob = null
        _state.update { it.copy(turnWaiting = false, turnMarked = false) }
    }

    // ───────────────────────────── Historial y exportación ─────────────────────────────

    fun deleteTrial(id: String) {
        viewModelScope.launch {
            store.delete(id)
            _state.update { s -> s.copy(trials = s.trials.filterNot { it.id == id }) }
        }
    }

    /** Exporta un ensayo (o todos si [id] es null) a Descargas/MetriK. */
    fun exportCsv(id: String? = null) {
        val trials = _state.value.trials.filter { id == null || it.id == id }
        if (trials.isEmpty()) {
            _state.update { it.copy(error = "No hay ensayos para exportar") }
            return
        }
        viewModelScope.launch {
            val name = "MetriK_validacion_sensores_" + (id ?: "todos") + "_" + nowMs() + ".csv"
            runCatching { export(name, SensorTrialCsv.build(trials).toByteArray(Charsets.UTF_8)) }
                .onSuccess { path -> _state.update { it.copy(message = "CSV guardado en $path", error = null) } }
                .onFailure { t -> _state.update { it.copy(error = "No se pudo exportar: ${t.message ?: t::class.simpleName}") } }
        }
    }

    // ───────────────────────────── Utilidades ─────────────────────────────

    private fun newTrial(
        kind: TrialKind, durationS: Double, reference: Double, observed: Double, error: Double, source: String, config: String,
    ) = SensorTrial(
        id = newId(), createdAtMs = nowMs(), device = device, appVersion = appVersion, kind = kind, durationS = durationS,
        reference = reference, observed = observed, error = error, unit = kind.unit, source = source, config = config,
    )

    private fun save(trial: SensorTrial, update: (SensorValidationState) -> SensorValidationState) {
        _state.update { update(it).copy(trials = it.trials + trial) }
        viewModelScope.launch {
            runCatching { store.add(trial) }
                .onFailure { t -> _state.update { it.copy(error = "El resultado se muestra, pero no se pudo guardar: ${t.message ?: t::class.simpleName}") } }
        }
    }

    companion object {
        const val STEPS_CONFIG = "StepDetector con los parametros por defecto del modo interior por pasos; sin WalkingGate"
    }
}
