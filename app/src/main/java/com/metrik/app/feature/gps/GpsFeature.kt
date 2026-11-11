package com.metrik.app.feature.gps

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.metrik.app.core.geometry.Geometry
import com.metrik.app.core.geometry.GeometryException
import com.metrik.app.core.geometry.Point
import com.metrik.app.core.geometry.PolygonAnalysis
import com.metrik.app.core.geometry.Scale
import com.metrik.app.data.repository.SurveyRepository
import com.metrik.app.domain.GpsSample
import com.metrik.app.domain.LocalSurvey
import com.metrik.app.domain.SurveyMode
import com.metrik.app.feature.plan.saveToDownloads
import com.metrik.app.gps.GpsMath
import com.metrik.app.gps.GpsPrecision
import com.metrik.app.gps.GpsTrackFilter
import com.metrik.app.gps.GpsTraverse
import com.metrik.app.gps.LocationSource
import com.metrik.app.gps.PdrGpsFusion
import com.metrik.app.gps.Transport
import com.metrik.app.ui.components.ErrorBanner
import com.metrik.app.ui.components.MetrikTopBar
import com.metrik.app.ui.components.PlanDrawing
import com.metrik.app.ui.components.StatRow
import com.metrik.app.ui.components.m
import com.metrik.app.ui.components.m2
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.shareIn
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.Locale

enum class GpsPhase { IDLE, CALIBRATING, READY, RECORDING, FINISHED }

data class GpsUiState(
    val phase: GpsPhase = GpsPhase.IDLE,
    val calibrationSamples: Int = 0,
    val calibration: GpsMath.StaticError? = null,
    val lastAccuracyM: Double? = null,
    val samples: List<GpsSample> = emptyList(),
    /** Recorrido en metros respecto de la partida; al finalizar, ya corregido tramo a tramo. */
    val track: List<Point> = emptyList(),
    val polygon: List<Point> = emptyList(),
    val analysis: PolygonAnalysis? = null,
    val savedLocalId: Long? = null,
    val error: String? = null,
    val transport: String = "walk",
    val discarded: Int = 0,
    /** Recorrido a pie con pasos + brújula (el GPS corrige). */
    val usingSteps: Boolean = false,
    val steps: Int = 0,
    val gpsCorrections: Int = 0,
    /** Esquinas marcadas al toque (la partida no se cuenta). */
    val cornersFixed: Int = 0,
    /** Dónde se marcó cada esquina (metros), para dibujarlas mientras se recorre. */
    val cornerPoints: List<Point> = emptyList(),
    /** Esquina en la que la persona sigue quieta: sus lecturas se están promediando. Null = caminando. */
    val atCorner: Int? = null,
    /** Lecturas quietas acumuladas en la esquina actual (o en la partida, al final). */
    val stillReadings: Int = 0,
    /** Incertidumbre al 95 % del área y del perímetro (RF-21); solo con esquinas marcadas. */
    val uncertainty: GpsPrecision.AreaUncertainty? = null,
    /** Error de cierre: distancia entre el final del recorrido y la partida antes de repartirla. */
    val closureM: Double? = null,
    /** false = no se volvió a la partida: la figura no se cerró. */
    val closureConsistent: Boolean? = null,
    /** Precisión de la última lectura descartada por señal de interior al medir la precisión; null = señal buena. */
    val weakSignalM: Double? = null,
)

/**
 * Medición exterior con GPS (CU-02): precisión al inicio, recorrido con esquinas al toque y área (RF-08, RF-09, RF-18 a RF-21).
 *
 * - La **partida** es el centro de las lecturas quietas de la medición de precisión (vértice 0).
 * - **Esquina** se marca al toque, sin esperar. Si la persona se queda quieta, las lecturas GPS de ese rato se
 *   promedian solas y anclan la esquina; al caminar de nuevo, se cierra el promedio.
 * - Al **finalizar** se asume que se terminó en la partida: las lecturas quietas del final miden el cierre y
 *   [GpsTraverse] reparte ese error entre las esquinas y corrige el recorrido tramo a tramo.
 */
class GpsViewModel(
    private val projectId: Int,
    private val location: LocationSource,
    private val surveys: SurveyRepository,
    private val calibrationTarget: Int = 15,
    /**
     * Pasos con brújula (rumbo absoluto en radianes por paso). A pie, el recorrido se arma con ellos
     * y el GPS solo corrige (ver [PdrGpsFusion]). Null = solo GPS.
     */
    private val compassSteps: Flow<Double>? = null,
    /** Largo de paso calibrado (Calibración → pasos). */
    private val stepLengthM: Double = 0.70,
    private val nanoClock: () -> Long = System::nanoTime,
    private val wallClockS: () -> Double = { System.currentTimeMillis() / 1000.0 },
) : ViewModel() {
    private var lastStepNs: Long? = null
    /**
     * La brújula se enciende una sola vez, al medir la precisión, y sigue encendida al grabar: así no empieza sin
     * asentar justo cuando la persona empieza a caminar. Los pasos detectados mientras mide la precisión se ignoran.
     */
    private val compassFeed: SharedFlow<Double>? by lazy { compassSteps?.shareIn(viewModelScope, SharingStarted.Eagerly) }
    /** Hora de la partida: una lectura anterior (guardada o atrasada) no entra al recorrido. */
    private var startTimestampS = Double.NEGATIVE_INFINITY
    /** Aplica el ajuste completo de pasos + GPS al trazo (en pantalla se acerca de a poco); null = solo GPS. */
    private var settleSteps: (() -> Unit)? = null
    /** Lecturas quietas etiquetadas con su vértice (0 = partida al final, 1.. = esquinas); se guardan aparte del trazo. */
    private val cornerSamples = mutableListOf<GpsSample>()
    /** Índice del punto del recorrido en el que se tocó cada esquina. */
    private val cornerIdx = mutableListOf<Int>()
    /** Esquina en la que la persona sigue quieta desde el toque; null en cuanto vuelve a caminar. */
    private var currentCorner: Int? = null
    /** Lecturas quietas desde el último movimiento (la pausa natural en una esquina o en la partida). */
    private val stillBuffer = mutableListOf<GpsSample>()
    /** Lecturas GPS en movimiento usadas para corregir los pasos, con el punto del recorrido al que corresponden. */
    private val gpsObs = mutableListOf<GpsTraverse.GpsObs>()
    private var maxAccuracyM = Transport.of("walk").maxAccuracyM
    private val _state = MutableStateFlow(GpsUiState())
    val state: StateFlow<GpsUiState> = _state
    private var job: Job? = null
    private val calibration = mutableListOf<GpsSample>()
    /** Precisión de todas las lecturas del recorrido (para saber si el terreno es más grande que el error). */
    private val recordAccuracies = mutableListOf<Double>()
    private val diag = StringBuilder()

    /** Lecturas con el celular quieto para estimar el margen de error inicial y fijar la partida. */
    fun calibrate() {
        job?.cancel()
        calibration.clear()
        diag.clear()
        diag.append(DIAG_HEADER).append('\n')
        _state.update { it.copy(phase = GpsPhase.CALIBRATING, calibrationSamples = 0, error = null) }
        // A pie: la brújula se enciende ya, para que se asiente mientras la persona está quieta
        if (Transport.of(_state.value.transport).key == "walk") compassFeed
        job = viewModelScope.launch {
            location.samples.collect { s ->
                // Bajo techo o junto a una pared el error es de 10–20 m: esas lecturas no sirven para el punto de partida
                if (s.accuracyM > CALIBRATION_MAX_ACCURACY_M) {
                    log("gps", s, note = "calibracion descartada")
                    _state.update { it.copy(weakSignalM = s.accuracyM, lastAccuracyM = s.accuracyM) }
                    return@collect
                }
                log("gps", s, note = "calibracion")
                calibration += s
                _state.update { it.copy(calibrationSamples = calibration.size, lastAccuracyM = s.accuracyM, weakSignalM = null) }
                if (calibration.size >= calibrationTarget) {
                    val err = GpsMath.staticError(calibration)
                    _state.update { it.copy(phase = GpsPhase.READY, calibration = err) }
                    job?.cancel()
                }
            }
        }
    }

    fun setTransport(key: String) = _state.update { it.copy(transport = Transport.of(key).key) }

    /**
     * Marca la esquina donde está la persona, al toque (RF-18). Las lecturas quietas desde que se detuvo y las que
     * sigan mientras no camine se promedian para anclarla. Un segundo toque sin haber caminado no cuenta.
     */
    fun fixCorner() {
        val s = _state.value
        if (s.phase != GpsPhase.RECORDING) return
        val idx = s.track.lastIndex
        if (idx <= 0) {
            _state.update { it.copy(error = "Todavía no hay recorrido: camina hasta la esquina y vuelve a tocar «Esquina»") }
            return
        }
        if (currentCorner != null || (cornerIdx.isNotEmpty() && idx <= cornerIdx.last())) return
        val vertex = s.cornersFixed + 1
        cornerIdx += idx
        currentCorner = vertex
        cornerSamples += stillBuffer.map { it.copy(vertexId = vertex) }
        log("esquina", null, x = s.track[idx].x, y = s.track[idx].y, note = "esquina $vertex, ${stillBuffer.size} lecturas quietas previas")
        _state.update {
            it.copy(
                cornersFixed = vertex, cornerPoints = it.cornerPoints + s.track[idx], atCorner = vertex,
                stillReadings = stillBuffer.size, error = null,
            )
        }
    }

    /** La persona volvió a moverse: termina el promedio de la esquina (si lo había) y empieza una pausa nueva. */
    private fun onMoved() {
        currentCorner = null
        stillBuffer.clear()
        if (_state.value.atCorner != null || _state.value.stillReadings != 0) _state.update { it.copy(atCorner = null, stillReadings = 0) }
    }

    /** Lectura con la persona quieta: se guarda para promediar la esquina actual o el cierre en la partida. */
    private fun onStillSample(s: GpsSample) {
        if (s.accuracyM > maxAccuracyM) {
            log("gps", s, note = "quieto, baja precision")
            _state.update { it.copy(discarded = it.discarded + 1, lastAccuracyM = s.accuracyM) }
            return
        }
        stillBuffer += s
        currentCorner?.let { cornerSamples += s.copy(vertexId = it) }
        log("gps", s, note = "quieto" + (currentCorner?.let { " en esquina $it" } ?: ""))
        _state.update { it.copy(stillReadings = stillBuffer.size, lastAccuracyM = s.accuracyM) }
    }

    fun startRecording() {
        job?.cancel()
        settleSteps = null
        cornerSamples.clear()
        cornerIdx.clear()
        stillBuffer.clear()
        gpsObs.clear()
        currentCorner = null
        recordAccuracies.clear()
        val profile = Transport.of(_state.value.transport)
        maxAccuracyM = profile.maxAccuracyM
        val filter = GpsTrackFilter(profile)
        // Punto de partida = centro de las lecturas de calibración (la persona estaba quieta en el inicio).
        val start = GpsMath.startPoint(calibration)?.takeIf { it.accuracyM <= profile.maxAccuracyM }
        start?.let(filter::seed)
        val initial = listOfNotNull(start)
        startTimestampS = (calibration.maxOfOrNull { it.timestampS } ?: Double.NEGATIVE_INFINITY)
        _state.update {
            it.copy(
                phase = GpsPhase.RECORDING, samples = initial,
                track = if (initial.isEmpty()) emptyList() else GpsMath.toEnu(initial), error = null, discarded = 0,
                cornersFixed = 0, cornerPoints = emptyList(), atCorner = null, stillReadings = 0,
                uncertainty = null, closureM = null, closureConsistent = null, polygon = emptyList(), analysis = null,
            )
        }
        lastStepNs = null
        log("inicio", start, note = "partida = centro de ${calibration.size} lecturas quietas")
        // A pie con brújula: pasos + brújula corregidos con GPS. En bici, moto o carro no hay pasos: solo GPS.
        val compass = compassFeed
        if (compass != null && profile.key == "walk") {
            recordWithSteps(profile, start, compass)
            return
        }
        job = viewModelScope.launch {
            location.samples.collect { s ->
                if (isStale(s)) return@collect
                recordAccuracies += s.accuracyM
                val accepted = filter.offer(s, moving = !GpsMath.isStill(s))
                if (accepted.isEmpty()) {
                    if (s.accuracyM <= profile.maxAccuracyM) onStillSample(s)
                    else { log("gps", s, note = "descartada"); _state.update { it.copy(discarded = it.discarded + 1, lastAccuracyM = s.accuracyM) } }
                    return@collect
                }
                onMoved()
                accepted.forEach { log("gps", it, note = "aceptada") }
                val samples = _state.value.samples + accepted
                _state.update {
                    it.copy(samples = samples, lastAccuracyM = s.accuracyM, track = GpsMath.toEnu(samples))
                }
            }
        }
    }

    /**
     * Recorrido a pie: cada paso agrega un punto (línea continua, sigue la caminata) y el GPS corrige
     * la deriva de los pasos. Quieto no hay pasos y el GPS tampoco mueve la posición: sus lecturas se
     * promedian para la esquina.
     */
    private fun recordWithSteps(profile: com.metrik.app.gps.TransportProfile, start: GpsSample?, compass: Flow<Double>) {
        var origin: GpsSample? = start
        var fusion: PdrGpsFusion? = start?.let { PdrGpsFusion(stepLengthM, it.accuracyM) }
        val times = mutableListOf<Double>().apply { start?.let { add(it.timestampS) } }
        // El ajuste con GPS corrige la figura entera: se rehace la lista de puntos completa
        fun publish(f: PdrGpsFusion, o: GpsSample, extra: (GpsUiState) -> GpsUiState) {
            val samples = f.track().mapIndexed { i, p ->
                if (i == 0) o else GpsMath.fromEnu(p, o.lat, o.lon, STEP_POINT_ACCURACY_M, times.getOrElse(i) { wallClockS() })
            }
            val track = GpsMath.toEnu(samples)
            // Las esquinas se dibujan sobre el trazo ya corregido (no donde estaba al marcarlas)
            _state.update { extra(it.copy(samples = samples, track = track, cornerPoints = cornerIdx.mapNotNull(track::getOrNull))) }
        }
        _state.update { it.copy(usingSteps = true, steps = 0, gpsCorrections = 0) }
        settleSteps = {
            val f = fusion
            val o = origin
            if (f != null && o != null) { f.settle(); publish(f, o) { it } }
        }
        job = viewModelScope.launch {
            launch {
                // Un paso suelto (mover el celular en la mano) no cuenta: solo los de una racha de caminata
                val gate = com.metrik.app.sensors.WalkingGate()
                compass.collect { headingRad ->
                    val f = fusion ?: return@collect // sin punto de partida todavía: se espera el primer GPS
                    val o = origin ?: return@collect
                    val confirmed = gate.onStep(nanoClock(), headingRad)
                    if (confirmed.isEmpty()) return@collect
                    lastStepNs = nanoClock()
                    onMoved()
                    confirmed.forEach { h ->
                        f.onStep(h); times += wallClockS()
                        log("paso", null, headingDeg = Math.toDegrees(h), x = f.position.x, y = f.position.y)
                    }
                    publish(f, o) { it.copy(steps = it.steps + confirmed.size) }
                }
            }
            location.samples.collect { s ->
                if (isStale(s)) return@collect
                recordAccuracies += s.accuracyM
                val o = origin
                val f = fusion
                if (o == null || f == null) {
                    if (s.accuracyM > profile.maxAccuracyM) { _state.update { it.copy(discarded = it.discarded + 1, lastAccuracyM = s.accuracyM) }; return@collect }
                    // Sin calibración válida: la primera lectura precisa es el punto de partida
                    origin = s
                    fusion = PdrGpsFusion(stepLengthM, s.accuracyM)
                    times.clear(); times += s.timestampS
                    log("inicio", s, note = "partida = primera lectura precisa")
                    _state.update { it.copy(samples = listOf(s), track = GpsMath.toEnu(listOf(s)), lastAccuracyM = s.accuracyM) }
                    return@collect
                }
                // Quieto (sin pasos recientes o Doppler ≈ 0): el GPS "pasea" y no debe mover la posición
                val walking = lastStepNs?.let { nanoClock() - it < STEP_WINDOW_NS } == true
                if (!walking || GpsMath.isStill(s)) { onStillSample(s); return@collect }
                if (s.accuracyM > profile.maxAccuracyM) {
                    log("gps", s, note = "descartada")
                    _state.update { it.copy(discarded = it.discarded + 1, lastAccuracyM = s.accuracyM) }
                    return@collect
                }
                val enu = GpsMath.toEnu(listOf(s), o.lat, o.lon).first()
                val used = f.onGps(enu, s.accuracyM)
                if (used) gpsObs += GpsTraverse.GpsObs(f.track().lastIndex, enu, GpsPrecision.sigmaFromAccuracy(s.accuracyM))
                log("gps", s, note = if (used) "correccion" else "rebote")
                if (used) publish(f, o) { it.copy(gpsCorrections = it.gpsCorrections + 1, lastAccuracyM = s.accuracyM) }
                else _state.update { it.copy(discarded = it.discarded + 1, lastAccuracyM = s.accuracyM) }
            }
        }
    }

    /** Lectura con hora anterior a la partida (ubicación guardada o atrasada): se descarta. */
    private fun isStale(s: GpsSample): Boolean {
        if (s.timestampS > startTimestampS) return false
        log("gps", s, note = "vieja (anterior a la partida)")
        _state.update { it.copy(discarded = it.discarded + 1) }
        return true
    }

    /** Termina el recorrido en la partida: cierra la poligonal, corrige el recorrido y calcula el área. */
    fun finish() {
        job?.cancel()
        // El plano final se calcula con el ajuste completo, no con el que se estaba mostrando de a poco
        settleSteps?.invoke()
        val s = _state.value
        // Si ya se caminó después de la última esquina, las lecturas quietas del final son el cierre en la partida
        val endSamples = if (currentCorner == null) stillBuffer.toList() else emptyList()
        cornerSamples += endSamples.map { it.copy(vertexId = 0) }
        currentCorner = null
        _state.update { it.copy(atCorner = null, stillReadings = 0) }
        tooSmallForGps(s.samples + cornerSamples)?.let { msg ->
            _state.update { it.copy(phase = GpsPhase.READY, polygon = emptyList(), analysis = null, error = msg) }
            return
        }
        if (cornerIdx.size >= 2 && finishWithTraverse(s, endSamples)) return
        runCatching { GpsMath.closedPolygon(s.samples, Transport.of(s.transport).maxAccuracyM) }
            .onSuccess { r ->
                log("fin", null, note = "sin esquinas: vertices=${r.vertices.size}, cierre=${"%.2f".format(Locale.US, r.closureM ?: 0.0)}")
                _state.update {
                    it.copy(phase = GpsPhase.FINISHED, polygon = r.vertices, analysis = r.analysis, closureM = r.closureM)
                }
                warnIfCrossed()
            }
            .onFailure { t ->
                _state.update {
                    it.copy(phase = GpsPhase.READY, error = (t as? GeometryException)?.message ?: "No se pudo calcular el área")
                }
            }
    }

    /**
     * Poligonal con las esquinas al toque (RF-18 a RF-21): ancla cada esquina con sus lecturas quietas, reparte el
     * cierre hasta la partida y corrige el recorrido tramo a tramo. Devuelve false si no se pudo (se usa el trazo).
     */
    private fun finishWithTraverse(s: GpsUiState, endSamples: List<GpsSample>): Boolean = runCatching {
        val origin = s.samples.first()
        fun anchor(readings: List<GpsSample>): GpsTraverse.Anchor? {
            val valid = readings.filter(GpsPrecision::acceptsSignal)
            if (valid.size < GpsPrecision.MIN_READINGS_PER_VERTEX) return null
            val fix = GpsPrecision.fixCorner(
                GpsMath.toEnu(valid, origin.lat, origin.lon), valid.map { GpsPrecision.sigmaFromAccuracy(it.accuracyM) },
                valid.last().timestampS - valid.first().timestampS + 1,
            )
            return GpsTraverse.Anchor(fix.point, fix.sigmaM)
        }
        val anchors = cornerIdx.indices.map { k -> anchor(cornerSamples.filter { it.vertexId == k + 1 }) }
        val startSigma = anchor(calibration)?.sigmaM ?: GpsPrecision.sigmaFromAccuracy(origin.accuracyM)
        // Solo GPS (sin pasos): el recorrido ya es GPS, cada tramo vale ±√2·σ de una lectura y no deriva con el largo
        val gpsOnly = !s.usingSteps
        val accs = s.samples.drop(1).map { it.accuracyM }.sorted()
        val legSigma0 = if (gpsOnly && accs.isNotEmpty()) Math.sqrt(2.0) * GpsPrecision.sigmaFromAccuracy(accs[accs.size / 2]) else GpsTraverse.TRACK_SIGMA0_M
        val r = GpsTraverse.adjust(
            s.track, cornerIdx, anchors, anchor(endSamples), startSigma,
            gps = if (gpsOnly) emptyList() else gpsObs.toList(),
            sigma0M = legSigma0, driftPerM = if (gpsOnly) 0.0 else GpsTraverse.TRACK_DRIFT_PER_M,
        )
        val k = if (r.closed) GpsPrecision.CLOSURE_COVERAGE_FACTOR else GpsPrecision.COVERAGE_FACTOR
        Triple(r, Geometry.analyze(r.vertices), GpsPrecision.uncertainty(r.vertices, r.sigmasM, k))
    }.onSuccess { (r, analysis, u) ->
        log("fin", null, note = "esquinas=${cornerIdx.size}, anclas=${r.sigmasM.map { "%.1f".format(Locale.US, it) }}, " +
            "cierre=${"%.2f".format(Locale.US, r.closureM)}, cerrado=${r.closed}")
        _state.update {
            it.copy(
                phase = GpsPhase.FINISHED, polygon = r.vertices, analysis = analysis, uncertainty = u, track = r.track,
                closureM = r.closureM, closureConsistent = r.closed,
                error = if (r.closed) null else "No terminaste en el punto de partida (quedaste a ${"%.1f".format(Locale.US, r.closureM)} m): " +
                    "la figura no se cerró. Vuelve al inicio antes de finalizar para que la medida sea precisa.",
            )
        }
        if (r.closed) warnIfCrossed()
    }.onFailure { t -> log("fin", null, note = "poligonal fallida: ${t.message}") }.isSuccess

    /**
     * Mensaje si lo recorrido no es claramente mayor que el error del GPS (p. ej. 4 m con ±8 m de error): el
     * polígono saldría de puro ruido. Null si el terreno se puede medir con GPS.
     */
    private fun tooSmallForGps(points: List<GpsSample>): String? {
        val accuracies = recordAccuracies.filter { it <= Transport.of(_state.value.transport).maxAccuracyM }.sorted()
        if (points.size < 2 || accuracies.isEmpty()) return null
        val typical = accuracies[accuracies.size / 2]
        val xy = GpsMath.toEnu(points)
        val extent = xy.maxOf { a -> xy.maxOf { b -> Geometry.euclidean(a, b) } }
        if (extent >= MIN_EXTENT_PER_ERROR * typical) return null
        return "Recorrido muy pequeño para GPS: abarca ${extent.toInt()} m y cada lectura tiene ±${typical.toInt()} m de error. " +
            "Para ambientes o espacios chicos usa «Medir un ambiente» (cámara o pasos); el GPS es para terrenos grandes."
    }

    /** Aviso si el terreno se cruza consigo mismo (por ejemplo, ida y vuelta por el mismo camino), como en interiores. */
    private fun warnIfCrossed() {
        if (_state.value.analysis?.isSimple == false) {
            _state.update {
                it.copy(error = "El terreno se cruza consigo mismo: recorre el borde sin volver por el mismo camino y vuelve al inicio (EX-06).")
            }
        }
    }

    fun save(name: String) {
        val s = _state.value
        val analysis = s.analysis ?: return
        if (name.isBlank()) { _state.update { it.copy(error = "Ponle un nombre al terreno") }; return }
        // Trazo + lecturas quietas de la partida (vértice 0) y de las esquinas, en orden de tiempo: el backend las agrupa por vertex_id
        val gps = (s.samples + calibration.map { it.copy(vertexId = 0) } + cornerSamples).distinct().sortedBy { it.timestampS }
        viewModelScope.launch {
            val id = surveys.saveLocal(
                LocalSurvey(
                    projectId = projectId, name = name.trim(), mode = SurveyMode.EXTERIOR, gps = gps,
                    // Vértices ajustados en el celular, en metros respecto de la primera lectura enviada (el origen del servidor)
                    corners = if (s.uncertainty != null) cornersFromFirstReading(analysis.vertices, s.samples.first(), gps.first()) else null,
                    capture = com.metrik.app.domain.CaptureInfo("gps", 0.0, 0.0, s.transport),
                    vertices = analysis.vertices, areaM2 = analysis.areaM2, perimeterM = analysis.perimeterM,
                    scaleDenominator = maxOf(500, Scale.suggestFor(analysis.vertices)),
                ),
            )
            _state.update { it.copy(savedLocalId = id) }
        }
    }

    /** Lleva los vértices (metros respecto de la partida [origin]) a metros respecto de la lectura [first]. */
    private fun cornersFromFirstReading(vertices: List<Point>, origin: GpsSample, first: GpsSample): List<Point> {
        val ll = vertices.map { GpsMath.fromEnu(it, origin.lat, origin.lon, origin.accuracyM, origin.timestampS) }
        return GpsMath.toEnu(ll, first.lat, first.lon)
    }

    /** Registro de la medición (CSV) para revisar qué vio el GPS y la brújula; se guarda en Descargas al finalizar. */
    fun diagnosticsCsv(): String = diag.toString()

    private fun log(kind: String, s: GpsSample?, headingDeg: Double? = null, x: Double? = null, y: Double? = null, note: String = "") {
        val origin = _state.value.samples.firstOrNull()
        val (px, py) = when {
            x != null && y != null -> x to y
            s != null && origin != null -> GpsMath.toEnu(listOf(s), origin.lat, origin.lon).first().let { it.x to it.y }
            else -> null to null
        }
        fun d(v: Double?) = v?.let { "%.6f".format(Locale.US, it) } ?: ""
        fun m(v: Double?) = v?.let { "%.2f".format(Locale.US, it) } ?: ""
        diag.append(m(s?.timestampS ?: wallClockS())).append(',').append(kind).append(',')
            .append(d(s?.lat)).append(',').append(d(s?.lon)).append(',').append(m(s?.accuracyM)).append(',')
            .append(m(s?.speedMps)).append(',').append(m(headingDeg)).append(',').append(m(px)).append(',').append(m(py)).append(',')
            .append(note.replace(',', ';')).append('\n')
    }

    override fun onCleared() { job?.cancel() }

    private companion object {
        const val STEP_WINDOW_NS = 3_000_000_000L // sin pasos en 3 s = quieto
        /** Peor precisión aceptada al medir la precisión inicial: más que esto es señal de interior. */
        const val CALIBRATION_MAX_ACCURACY_M = 6.0
        /** El terreno debe abarcar al menos este múltiplo del error típico de las lecturas para medirlo con GPS. */
        const val MIN_EXTENT_PER_ERROR = 2.5
        /**
         * Precisión que se guarda en los puntos por pasos: es la del trazo (un paso), no la absoluta.
         * El suavizado del área usa 2 × la mediana como tolerancia: con la del GPS (4–8 m) borraría
         * las esquinas de un terreno chico.
         */
        const val STEP_POINT_ACCURACY_M = 0.5
        const val DIAG_HEADER = "t_s,tipo,lat,lon,precision_m,velocidad_mps,rumbo_deg,x_m,y_m,nota"
    }
}

@Composable
fun GpsScreen(vm: GpsViewModel, onBack: () -> Unit, onSaved: (Long) -> Unit) {
    val s by vm.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var granted by remember {
        mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED)
    }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted = it }
    LaunchedEffect(granted) { if (!granted) permission.launch(Manifest.permission.ACCESS_FINE_LOCATION) }
    LaunchedEffect(s.savedLocalId) { s.savedLocalId?.let(onSaved) }
    // Registro de la medición → Descargas/MetriK (para revisar el GPS y la brújula si algo sale mal)
    var diagnosticsPath by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(s.phase) {
        if (s.phase == GpsPhase.FINISHED) {
            val csv = vm.diagnosticsCsv()
            if (csv.isNotBlank()) {
                val name = "MetriK_gps_" + java.text.SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(java.util.Date()) + ".csv"
                diagnosticsPath = runCatching { saveToDownloads(context, name, csv.toByteArray(), "text/csv") }.getOrNull()
            }
        } else if (s.phase == GpsPhase.RECORDING) diagnosticsPath = null
    }

    Scaffold(topBar = { MetrikTopBar("Medir terreno (GPS)", onBack = onBack) }) { padding ->
        Column(Modifier.padding(padding).fillMaxSize().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Card(Modifier.fillMaxWidth().weight(1f)) {
                // Recorrido (gris), esquinas marcadas y, al final, el terreno con los muros numerados y la partida en verde
                PlanDrawing(
                    track = s.track, polygon = s.polygon,
                    corners = if (s.phase == GpsPhase.FINISHED) s.polygon else s.cornerPoints,
                    current = if (s.phase == GpsPhase.RECORDING) s.track.lastOrNull() else null,
                    start = if (s.phase == GpsPhase.RECORDING) s.track.firstOrNull() else null,
                    numberWalls = s.phase == GpsPhase.FINISHED,
                )
            }
            ErrorBanner(if (!granted) "Se necesita permiso de ubicación" else s.error)
            s.lastAccuracyM?.let { StatRow("Precisión actual", it.m(1)) }
            when (s.phase) {
                GpsPhase.IDLE -> {
                    Text("¿Cómo vas a recorrer el terreno?", style = MaterialTheme.typography.titleSmall)
                    androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Transport.ALL.forEach { t ->
                            androidx.compose.material3.FilterChip(
                                selected = s.transport == t.key, onClick = { vm.setTransport(t.key) },
                                label = { Text(t.label) }, modifier = Modifier.testTag("transport_${t.key}"),
                            )
                        }
                    }
                    Text(Transport.of(s.transport).description + ". Las lecturas imposibles para ese transporte se descartan.",
                        style = MaterialTheme.typography.bodySmall)
                    Text(
                        "El GPS tiene un error de 3 a 15 m: úsalo para terrenos grandes, no para un aula. " +
                            "Párate EN UNA ESQUINA del terreno, a cielo abierto, y quédate quieta unos segundos: se mide el error y se fija la partida.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Button(onClick = vm::calibrate, enabled = granted, modifier = Modifier.fillMaxWidth().testTag("gps_calibrate")) {
                        Text("Medir precisión (quieto)")
                    }
                }
                GpsPhase.CALIBRATING -> {
                    Text("Quédate quieto… ${s.calibrationSamples} lecturas")
                    s.weakSignalM?.let { acc ->
                        Text(
                            "Señal débil (±${acc.toInt()} m): sal al aire libre, lejos de techos, paredes altas y árboles. " +
                                "Las lecturas no cuentan hasta que la señal mejore.",
                            color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag("gps_weak_signal"),
                        )
                    }
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                }
                GpsPhase.READY -> {
                    s.calibration?.let { c ->
                        StatRow("CEP (50 %)", c.cep50M.m(1))
                        StatRow("R95", c.r95M.m(1))
                        Text(
                            if (c.needsCalibration) "Error inicial alto: espera en un lugar abierto y vuelve a medir antes de recorrer"
                            else "Precisión suficiente: puedes recorrer el terreno",
                            color = if (c.needsCalibration) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                        )
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = vm::calibrate, modifier = Modifier.weight(1f)) { Text("Recalibrar") }
                        Button(onClick = vm::startRecording, modifier = Modifier.weight(1f).testTag("gps_start")) { Text("Recorrer") }
                    }
                }
                GpsPhase.RECORDING -> {
                    if (s.usingSteps) {
                        StatRow("Pasos (brújula)", s.steps.toString())
                        StatRow("Correcciones GPS", s.gpsCorrections.toString())
                    } else {
                        StatRow("Lecturas (${Transport.of(s.transport).label})", s.samples.size.toString())
                    }
                    if (s.discarded > 0) StatRow("GPS ignorado (rebotes o baja precisión)", s.discarded.toString())
                    StatRow("Esquinas marcadas", s.cornersFixed.toString())
                    val at = s.atCorner
                    if (at != null) {
                        Text(
                            "Quieta en la esquina $at: ${s.stillReadings} lecturas promediándose. Sigue caminando cuando quieras.",
                            color = MaterialTheme.colorScheme.primary, modifier = Modifier.testTag("gps_at_corner"),
                        )
                    } else if (s.stillReadings > 0 && s.cornersFixed >= 2) {
                        Text("Quieta: ${s.stillReadings} lecturas. Si estás en la partida, toca Finalizar.", style = MaterialTheme.typography.bodySmall)
                    }
                    Text(
                        "Camina por el borde con el celular apuntando hacia donde vas. Al llegar a cada esquina toca «Esquina» " +
                            "(si te quedas quieta un momento, la esquina sale más precisa). Termina EN EL PUNTO DE PARTIDA y toca Finalizar.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = vm::fixCorner, modifier = Modifier.weight(1f).testTag("gps_fix_corner")) {
                            Text("Esquina ${s.cornersFixed + 1}")
                        }
                        Button(onClick = vm::finish, modifier = Modifier.weight(1f).testTag("gps_finish")) { Text("Finalizar") }
                    }
                }
                GpsPhase.FINISHED -> {
                    s.analysis?.let { a ->
                        val u = s.uncertainty
                        StatRow("Área", if (u != null) "${a.areaM2.m2()} ± ${u.areaU95M2.m2()} (${"%.1f".format(u.areaU95Pct)} %)" else a.areaM2.m2())
                        StatRow("Perímetro", if (u != null) "${a.perimeterM.m()} ± ${u.perimeterU95M.m()}" else a.perimeterM.m())
                        StatRow("Vértices", a.vertices.size.toString())
                        s.closureM?.let { StatRow("Error de cierre", it.m(2)) }
                        if (s.closureConsistent != false && s.closureM != null) {
                            Text(
                                "Ese error se repartió entre las esquinas: la figura termina exactamente en el punto de partida.",
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                        if (u != null) {
                            Text("Margen al 95 % calculado con las esquinas marcadas y el cierre.", style = MaterialTheme.typography.bodySmall)
                        } else {
                            Text(
                                "Sin esquinas marcadas no se calcula el margen en el celular; el servidor lo calcula al sincronizar.",
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    }
                    diagnosticsPath?.let { Text("Registro de la medición guardado en $it", style = MaterialTheme.typography.bodySmall) }
                    var name by rememberSaveable { mutableStateOf("") }
                    OutlinedTextField(name, { name = it }, label = { Text("Nombre del terreno") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    Button(onClick = { vm.save(name) }, modifier = Modifier.fillMaxWidth().testTag("gps_save")) { Text("Guardar") }
                }
            }
        }
    }
}
