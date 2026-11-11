package com.metrik.app.feature.measure

import com.metrik.app.domain.DoorMark
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.metrik.app.core.geometry.Geometry
import com.metrik.app.core.geometry.GeometryException
import com.metrik.app.core.geometry.Point
import com.metrik.app.core.geometry.PolygonAnalysis
import com.metrik.app.core.geometry.Scale
import com.metrik.app.core.geometry.Smoothing
import com.metrik.app.data.repository.SurveyRepository
import com.metrik.app.domain.CaptureInfo
import com.metrik.app.domain.LocalSurvey
import com.metrik.app.domain.SurveyMode
import com.metrik.app.sensors.OdometerAccumulator
import com.metrik.app.sensors.OdometrySource
import com.metrik.app.sensors.TurnTracker
import com.metrik.app.sensors.Wall
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class MeasurePhase { IDLE, MEASURING, FINISHED }

data class MeasureUiState(
    val phase: MeasurePhase = MeasurePhase.IDLE,
    val sourceKind: String = "",
    val message: String? = null,
    val track: List<Point> = emptyList(),
    val current: Point? = null,
    val corners: List<Point> = emptyList(),
    val walls: List<Wall> = emptyList(),
    val segmentM: Double = 0.0,
    val totalM: Double = 0.0,
    val trackingLossS: Double = 0.0,
    val distanceToStartM: Double? = null,
    val nearStart: Boolean = false,
    val polygon: List<Point> = emptyList(),
    val analysis: PolygonAnalysis? = null,
    val closed: Boolean = true,
    val closureErrorM: Double = 0.0,
    val scaleDenominator: Int = 50,
    val savedLocalId: Long? = null,
    val error: String? = null,
    /** Puertas marcadas en este recorrido («Puerta aquí»). */
    val doors: List<DoorMark> = emptyList(),
    /** Puerta del proyecto en la que empieza este ambiente (para unirlo solo). */
    val startDoor: String? = null,
    /** Puertas ya marcadas en otros ambientes del proyecto. */
    val availableDoors: List<String> = emptyList(),
)

/**
 * Recorrido interior (CU-01): el odómetro acumula la distancia, el giroscopio mide los giros
 * en cada esquina y al finalizar se suaviza el trazo y se calcula el plano (RF-01 a RF-06).
 */
class MeasureViewModel(
    private val projectId: Int,
    private val surveys: SurveyRepository,
    private val nanoClock: () -> Long = System::nanoTime,
) : ViewModel() {
    private val _state = MutableStateFlow(MeasureUiState())
    val state: StateFlow<MeasureUiState> = _state

    // Con detección de quietud: el balanceo del cuerpo estando quieto ya no suma distancia
    private val odometer = OdometerAccumulator.walking()
    private val turns = TurnTracker()
    private var jobs = listOf<Job>()
    private var startedNs = 0L
    private var captured: LocalSurvey? = null

    init {
        viewModelScope.launch {
            surveys.observeLocal(projectId).collect { list ->
                val names = list.flatMap { it.capture?.doors.orEmpty() }.map { it.name }.distinct()
                _state.update { it.copy(availableDoors = names) }
            }
        }
    }

    /** Antes de iniciar: el ambiente empieza en esta puerta (null = en una esquina cualquiera). */
    fun setStartDoor(name: String?) {
        if (_state.value.phase != MeasurePhase.IDLE) return
        _state.update { it.copy(startDoor = name) }
    }

    /** «Puerta aquí»: guarda la posición actual como la puerta que lleva al ambiente [name]. */
    fun markDoor(name: String) {
        val s = _state.value
        if (s.phase != MeasurePhase.MEASURING) return
        val clean = name.trim()
        if (clean.isEmpty()) { _state.update { it.copy(error = "Ponle a la puerta el nombre del ambiente al que lleva") }; return }
        if (s.doors.any { it.name.equals(clean, ignoreCase = true) } || s.availableDoors.any { it.equals(clean, ignoreCase = true) }) {
            _state.update { it.copy(error = "Ya hay una puerta «$clean» en el proyecto") }
            return
        }
        val p = odometer.track.lastOrNull() ?: run { _state.update { it.copy(error = "Todavía no hay posición") }; return }
        _state.update { it.copy(doors = it.doors + DoorMark(clean, p.x, p.y), error = null) }
    }

    fun start(source: OdometrySource, heading: Flow<Double>? = null) {
        if (_state.value.phase == MeasurePhase.MEASURING) return
        val startDoor = _state.value.startDoor
        reset()
        startedNs = nanoClock()
        _state.update { it.copy(phase = MeasurePhase.MEASURING, sourceKind = source.kind, startDoor = startDoor) }
        val sampling = viewModelScope.launch {
            var n = 0
            var lost = 0
            source.samples.collect { sample ->
                val added = odometer.onSample(sample)
                // Sin seguimiento llegan ~60 muestras/s: solo se publica si cambia el mensaje (o cada 15),
                // para no copiar el trazo completo en cada frame.
                // Si cambia el mensaje (p. ej. se recuperó el seguimiento estando quieto) se publica igual,
                // para no dejar un aviso viejo en pantalla.
                val messageChanged = sample.message != _state.value.message
                val publishLost = sample.position == null && ++lost % 15 == 0
                if (messageChanged || publishLost || added && (++n % 3 == 0)) publish(sample.message, odometer.corrected(sample.position))
            }
        }
        val headingJob = heading?.let { flow -> viewModelScope.launch { flow.collect { turns.onHeading(it) } } }
        jobs = listOfNotNull(sampling, headingJob)
    }

    private fun publish(message: String?, current: Point?) = _state.update {
        it.copy(
            message = message,
            track = odometer.track.toList(),
            current = current ?: it.current,
            segmentM = odometer.segmentM,
            totalM = odometer.totalM,
            trackingLossS = odometer.trackingLossS,
            distanceToStartM = startDistance(current ?: it.current),
            nearStart = (startDistance(current ?: it.current) ?: Double.MAX_VALUE) <= Smoothing.closureTolerance(odometer.totalM) &&
                odometer.totalM >= 4.0,
        )
    }

    /** Distancia al punto de inicio (solo después de 4 m, para no avisar al empezar). */
    private fun startDistance(p: Point?): Double? {
        val start = odometer.track.firstOrNull() ?: return null
        return if (p == null || odometer.totalM < 4.0) null else com.metrik.app.core.geometry.Geometry.euclidean(start, p)
    }

    /** Marca la esquina: cierra el muro actual y registra el giro del giroscopio (RF-02, RF-03). */
    fun markCorner() {
        if (_state.value.phase != MeasurePhase.MEASURING) return
        val position = odometer.track.lastOrNull() ?: run {
            _state.update { it.copy(error = "Todavía no hay posición. ${it.message ?: "Mueve el celular despacio para que la cámara reconozca el ambiente"}") }
            return
        }
        val length = odometer.closeSegment()
        val turn = turns.corner()
        _state.update {
            it.copy(
                corners = it.corners + position,
                walls = it.walls + Wall(length, turn?.first, turn?.second),
                segmentM = 0.0,
                error = null,
            )
        }
    }

    /** Termina el recorrido: suaviza, analiza y propone la escala (RF-04, RF-05, RF-06, RF-10). */
    fun finish() {
        if (_state.value.phase != MeasurePhase.MEASURING) return
        jobs.forEach { it.cancel() }
        val durationS = (nanoClock() - startedNs) / 1e9
        publish(null, null)
        val track = odometer.track.toList()
        // El recorrido empieza en una esquina: el punto inicial también es esquina
        val corners = listOfNotNull(track.firstOrNull()) + _state.value.corners
        // Por pasos el trazo es disperso (un punto por paso): se usan las esquinas marcadas,
        // que con mediciones reales dieron el mejor resultado (docs/05 §8). ARCore usa el trazo.
        val useCorners = _state.value.sourceKind == "steps" && corners.size >= 4
        val result = runCatching {
            if (useCorners) Smoothing.smoothTrack(emptyList(), corners = corners) else Smoothing.smoothTrack(track)
        }.recoverCatching { if (corners.size >= 4) Smoothing.smoothTrack(emptyList(), corners = corners) else throw it }
        result.onSuccess { r ->
            val analysis = Geometry.analyze(r.vertices)
            val scale = Scale.suggestFor(analysis.vertices)
            captured = LocalSurvey(
                projectId = projectId,
                name = "",
                mode = SurveyMode.INTERIOR,
                points = track,
                corners = if (useCorners) corners else null,
                capture = CaptureInfo(
                    _state.value.sourceKind, odometer.trackingLossS, durationS,
                    doors = _state.value.doors, startDoor = _state.value.startDoor,
                ),
                vertices = analysis.vertices,
                areaM2 = analysis.areaM2,
                perimeterM = analysis.perimeterM,
                scaleDenominator = scale,
                closed = r.closed,
            )
            _state.update {
                it.copy(
                    phase = MeasurePhase.FINISHED, polygon = analysis.vertices, analysis = analysis,
                    closed = r.closed, closureErrorM = r.closureErrorM, scaleDenominator = scale, message = null,
                    error = if (!analysis.isSimple) "El plano se cruza consigo mismo: repite el tramo (EX-06)" else null,
                )
            }
        }.onFailure { t ->
            val msg = (t as? GeometryException)?.message ?: "No se pudo calcular el plano"
            _state.update { it.copy(phase = MeasurePhase.FINISHED, error = "$msg. Recorre al menos 3 paredes.") }
        }
    }

    fun save(name: String) {
        val survey = captured ?: return
        if (name.isBlank()) { _state.update { it.copy(error = "Ponle un nombre al ambiente") }; return }
        viewModelScope.launch {
            val id = surveys.saveLocal(survey.copy(name = name.trim()))
            _state.update { it.copy(savedLocalId = id, error = null) }
        }
    }

    fun reset() {
        jobs.forEach { it.cancel() }
        jobs = emptyList()
        odometer.reset()
        turns.reset()
        captured = null
        _state.value = MeasureUiState(availableDoors = _state.value.availableDoors)
    }

    override fun onCleared() { jobs.forEach { it.cancel() } }
}
