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
import com.metrik.app.core.geometry.GeometryException
import com.metrik.app.core.geometry.Point
import com.metrik.app.core.geometry.PolygonAnalysis
import com.metrik.app.core.geometry.Scale
import com.metrik.app.data.repository.SurveyRepository
import com.metrik.app.domain.GpsSample
import com.metrik.app.domain.LocalSurvey
import com.metrik.app.domain.SurveyMode
import com.metrik.app.core.geometry.Geometry
import com.metrik.app.gps.GpsMath
import com.metrik.app.gps.GpsPrecision
import com.metrik.app.gps.GpsTrackFilter
import com.metrik.app.gps.PdrGpsFusion
import com.metrik.app.gps.LocationSource
import com.metrik.app.gps.Transport
import com.metrik.app.ui.components.ErrorBanner
import com.metrik.app.ui.components.MetrikTopBar
import com.metrik.app.ui.components.PlanDrawing
import com.metrik.app.ui.components.StatRow
import com.metrik.app.ui.components.m
import com.metrik.app.ui.components.m2
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class GpsPhase { IDLE, CALIBRATING, READY, RECORDING, FINISHED }

data class GpsUiState(
    val phase: GpsPhase = GpsPhase.IDLE,
    val calibrationSamples: Int = 0,
    val calibration: GpsMath.StaticError? = null,
    val lastAccuracyM: Double? = null,
    val samples: List<GpsSample> = emptyList(),
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
    /** Esquina que se está fijando (quieto sobre ella); null = caminando (RF-18). */
    val fixingVertex: Int? = null,
    /** Lecturas tomadas en la esquina actual. */
    val dwellCount: Int = 0,
    val cornersFixed: Int = 0,
    /** Se volvió a fijar la primera esquina al final (RF-19). */
    val closureFixed: Boolean = false,
    /** Incertidumbre al 95 % del área y del perímetro (RF-21); solo con esquinas fijadas. */
    val uncertainty: GpsPrecision.AreaUncertainty? = null,
    val closureM: Double? = null,
    /** false = el cierre es mayor de lo esperable: conviene volver a medir. */
    val closureConsistent: Boolean? = null,
)

/** Medición exterior con GPS (CU-02): calibración condicional, recorrido y área (RF-08, RF-09). */
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
    /** Lecturas por esquina fijada: a 1 lectura por segundo, 30 ≈ 30 s quieto (RF-18). */
    private val cornerDwellSamples: Int = 30,
) : ViewModel() {
    private var lastStepNs: Long? = null
    /** Lecturas tomadas quieto sobre las esquinas, etiquetadas con su vertexId (se guardan aparte del trazo). */
    private val cornerSamples = mutableListOf<GpsSample>()
    private var closingDwell = false
    /** Índice de [cornerSamples] donde empezó la esquina en curso (para descartarla si no se completa). */
    private var dwellStartIndex = 0
    /** Precisión máxima aceptada al fijar una esquina (la del transporte elegido). */
    private var dwellMaxAccuracyM = Transport.of("walk").maxAccuracyM
    private val _state = MutableStateFlow(GpsUiState())

    init {
        require(cornerDwellSamples > 0) { "cornerDwellSamples debe ser mayor que 0" }
    }
    val state: StateFlow<GpsUiState> = _state
    private var job: Job? = null
    private val calibration = mutableListOf<GpsSample>()

    /** Lecturas con el celular quieto para estimar el margen de error inicial. */
    fun calibrate() {
        job?.cancel()
        calibration.clear()
        _state.update { it.copy(phase = GpsPhase.CALIBRATING, calibrationSamples = 0, error = null) }
        job = viewModelScope.launch {
            location.samples.collect { s ->
                calibration += s
                _state.update { it.copy(calibrationSamples = calibration.size, lastAccuracyM = s.accuracyM) }
                if (calibration.size >= calibrationTarget) {
                    val err = GpsMath.staticError(calibration)
                    _state.update { it.copy(phase = GpsPhase.READY, calibration = err) }
                    job?.cancel()
                }
            }
        }
    }

    fun setTransport(key: String) = _state.update { it.copy(transport = Transport.of(key).key) }

    /** Lecturas que hacen falta para fijar una esquina (la pantalla muestra el avance). */
    val cornerDwellTarget: Int get() = cornerDwellSamples

    /** El usuario está quieto sobre la siguiente esquina: las próximas lecturas la fijan (RF-18). */
    fun fixCorner() {
        val s = _state.value
        if (s.phase != GpsPhase.RECORDING || s.fixingVertex != null || s.closureFixed) return
        closingDwell = false
        dwellStartIndex = cornerSamples.size
        _state.update { it.copy(fixingVertex = s.cornersFixed, dwellCount = 0, error = null) }
    }

    /** Cancela la esquina en curso (por ejemplo, si se perdió la señal): sus lecturas se descartan. */
    fun cancelCorner() {
        if (_state.value.fixingVertex == null) return
        discardIncompleteDwell()
        _state.update { it.copy(fixingVertex = null, dwellCount = 0) }
    }

    private fun discardIncompleteDwell() {
        if (dwellStartIndex < cornerSamples.size) cornerSamples.subList(dwellStartIndex, cornerSamples.size).clear()
    }

    /** De vuelta en el punto de partida: se fija otra vez la primera esquina para medir el cierre (RF-19). */
    fun fixStartAgain() {
        val s = _state.value
        if (s.phase != GpsPhase.RECORDING || s.fixingVertex != null || s.closureFixed) return
        if (s.cornersFixed < 3) {
            _state.update { it.copy(error = "Fija al menos 3 esquinas antes de volver al inicio") }
            return
        }
        closingDwell = true
        dwellStartIndex = cornerSamples.size
        _state.update { it.copy(fixingVertex = 0, dwellCount = 0, error = null) }
    }

    /**
     * Si se está fijando una esquina, guarda la lectura etiquetada y devuelve true (no va al trazo).
     * Las lecturas muy imprecisas para el transporte no cuentan para la esquina.
     */
    private fun consumeDwell(s: GpsSample): Boolean {
        val vertex = _state.value.fixingVertex ?: return false
        if (s.accuracyM > dwellMaxAccuracyM) {
            _state.update { it.copy(discarded = it.discarded + 1, lastAccuracyM = s.accuracyM) }
            return true
        }
        cornerSamples += s.copy(vertexId = vertex)
        val count = _state.value.dwellCount + 1
        if (count < cornerDwellSamples) {
            _state.update { it.copy(dwellCount = count, lastAccuracyM = s.accuracyM) }
        } else {
            _state.update {
                it.copy(
                    fixingVertex = null, dwellCount = 0, lastAccuracyM = s.accuracyM,
                    cornersFixed = if (closingDwell) it.cornersFixed else it.cornersFixed + 1,
                    closureFixed = closingDwell,
                )
            }
        }
        return true
    }

    fun startRecording() {
        job?.cancel()
        cornerSamples.clear()
        closingDwell = false
        dwellStartIndex = 0
        val profile = Transport.of(_state.value.transport)
        dwellMaxAccuracyM = profile.maxAccuracyM
        val filter = GpsTrackFilter(profile)
        // Punto de partida = centro de las lecturas de calibración (la persona estaba quieta en el inicio).
        // Antes no se usaban: el punto de partida no aparecía hasta la primera lectura precisa en marcha.
        val start = GpsMath.startPoint(calibration)?.takeIf { it.accuracyM <= profile.maxAccuracyM }
        start?.let(filter::seed)
        val initial = listOfNotNull(start)
        _state.update {
            it.copy(
                phase = GpsPhase.RECORDING, samples = initial,
                track = if (initial.isEmpty()) emptyList() else GpsMath.toEnu(initial), error = null, discarded = 0,
                fixingVertex = null, dwellCount = 0, cornersFixed = 0, closureFixed = false,
                uncertainty = null, closureM = null, closureConsistent = null,
            )
        }
        lastStepNs = null
        // A pie con brújula: pasos + brújula corregidos con GPS. En bici, moto o carro no hay pasos: solo GPS.
        val compass = compassSteps
        if (compass != null && profile.key == "walk") {
            recordWithSteps(profile, start, compass)
            return
        }
        job = viewModelScope.launch {
            location.samples.collect { s ->
                if (consumeDwell(s)) return@collect
                val accepted = filter.offer(s, moving = !GpsMath.isStill(s))
                if (accepted.isEmpty()) {
                    _state.update { it.copy(discarded = it.discarded + 1, lastAccuracyM = s.accuracyM) }
                    return@collect
                }
                val samples = _state.value.samples + accepted
                _state.update {
                    it.copy(samples = samples, lastAccuracyM = s.accuracyM, track = GpsMath.toEnu(samples))
                }
            }
        }
    }

    /**
     * Recorrido a pie: cada paso agrega un punto (línea continua, sigue la caminata) y el GPS corrige
     * la deriva de los pasos. Quieto no hay pasos y el GPS tampoco mueve la posición.
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
            _state.update { extra(it.copy(samples = samples, track = GpsMath.toEnu(samples))) }
        }
        _state.update { it.copy(usingSteps = true, steps = 0, gpsCorrections = 0) }
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
                    confirmed.forEach { h -> f.onStep(h); times += wallClockS() }
                    publish(f, o) { it.copy(steps = it.steps + confirmed.size) }
                }
            }
            location.samples.collect { s ->
                if (consumeDwell(s)) return@collect
                if (s.accuracyM > profile.maxAccuracyM) {
                    _state.update { it.copy(discarded = it.discarded + 1, lastAccuracyM = s.accuracyM) }
                    return@collect
                }
                val o = origin
                val f = fusion
                if (o == null || f == null) {
                    // Sin calibración válida: la primera lectura precisa es el punto de partida
                    origin = s
                    fusion = PdrGpsFusion(stepLengthM, s.accuracyM)
                    times.clear(); times += s.timestampS
                    _state.update { it.copy(samples = listOf(s), track = GpsMath.toEnu(listOf(s)), lastAccuracyM = s.accuracyM) }
                    return@collect
                }
                // Quieto (sin pasos recientes): el GPS "pasea" y no debe mover la posición
                val walking = lastStepNs?.let { nanoClock() - it < STEP_WINDOW_NS } == true
                if (!walking || GpsMath.isStill(s)) { _state.update { it.copy(lastAccuracyM = s.accuracyM) }; return@collect }
                val used = f.onGps(GpsMath.toEnu(listOf(s), o.lat, o.lon).first(), s.accuracyM)
                if (used) publish(f, o) { it.copy(gpsCorrections = it.gpsCorrections + 1, lastAccuracyM = s.accuracyM) }
                else _state.update { it.copy(discarded = it.discarded + 1, lastAccuracyM = s.accuracyM) }
            }
        }
    }

    fun finish() {
        job?.cancel()
        val samples = _state.value.samples
        // Una esquina a medio fijar no cuenta: sus pocas lecturas inflarían σ o falsearían el cierre
        if (_state.value.fixingVertex != null) discardIncompleteDwell()
        _state.update { it.copy(fixingVertex = null, dwellCount = 0) }
        if (GpsPrecision.fixedCorners(cornerSamples) >= 3 && finishWithCorners(samples.firstOrNull())) return
        runCatching { GpsMath.areaPolygon(samples, Transport.of(_state.value.transport).maxAccuracyM) }
            .onSuccess { (vertices, analysis) ->
                _state.update { it.copy(phase = GpsPhase.FINISHED, polygon = vertices, analysis = analysis) }
            }
            .onFailure { t ->
                _state.update {
                    it.copy(phase = GpsPhase.READY, error = (t as? GeometryException)?.message ?: "No se pudo calcular el área")
                }
            }
    }

    /**
     * Terreno a partir de las esquinas fijadas, con su incertidumbre (RF-18 a RF-21). Devuelve false si las
     * esquinas no alcanzan (por ejemplo, señal insuficiente): entonces se usa el trazo continuo.
     */
    private fun finishWithCorners(origin: GpsSample?): Boolean = runCatching {
        val poly = GpsPrecision.cornerPolygon(cornerSamples, origin)
        Triple(poly, Geometry.analyze(poly.vertices), GpsPrecision.uncertainty(poly.vertices, poly.sigmasM, poly.k))
    }.onSuccess { (poly, analysis, u) ->
        _state.update {
            it.copy(
                phase = GpsPhase.FINISHED, polygon = poly.vertices, analysis = analysis, uncertainty = u,
                closureM = poly.closureM, closureConsistent = poly.closureConsistent,
            )
        }
    }.isSuccess

    fun save(name: String) {
        val s = _state.value
        val analysis = s.analysis ?: return
        if (name.isBlank()) { _state.update { it.copy(error = "Ponle un nombre al terreno") }; return }
        // Trazo + lecturas de las esquinas, en orden de tiempo: el backend las agrupa por vertex_id
        val gps = (s.samples + cornerSamples).sortedBy { it.timestampS }
        viewModelScope.launch {
            val id = surveys.saveLocal(
                LocalSurvey(
                    projectId = projectId, name = name.trim(), mode = SurveyMode.EXTERIOR, gps = gps,
                    capture = com.metrik.app.domain.CaptureInfo("gps", 0.0, 0.0, s.transport),
                    vertices = analysis.vertices, areaM2 = analysis.areaM2, perimeterM = analysis.perimeterM,
                    scaleDenominator = maxOf(500, Scale.suggestFor(analysis.vertices)),
                ),
            )
            _state.update { it.copy(savedLocalId = id) }
        }
    }

    override fun onCleared() { job?.cancel() }

    private companion object {
        const val STEP_WINDOW_NS = 3_000_000_000L // sin pasos en 3 s = quieto
        /**
         * Precisión que se guarda en los puntos por pasos: es la del trazo (un paso), no la absoluta.
         * El suavizado del área usa 2 × la mediana como tolerancia: con la del GPS (4–8 m) borraría
         * las esquinas de un terreno chico.
         */
        const val STEP_POINT_ACCURACY_M = 0.5
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

    Scaffold(topBar = { MetrikTopBar("Medir terreno (GPS)", onBack = onBack) }) { padding ->
        Column(Modifier.padding(padding).fillMaxSize().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Card(Modifier.fillMaxWidth().weight(1f)) {
                // Punto actual visible desde el inicio (el de partida sale de la calibración)
                PlanDrawing(
                    track = s.track, polygon = s.polygon,
                    current = if (s.phase == GpsPhase.RECORDING) s.track.lastOrNull() else null,
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
                            "Primero quédate quieto EN EL PUNTO DE PARTIDA unos segundos: mide el error y fija el inicio del recorrido.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Button(onClick = vm::calibrate, enabled = granted, modifier = Modifier.fillMaxWidth().testTag("gps_calibrate")) {
                        Text("Medir precisión (quieto)")
                    }
                }
                GpsPhase.CALIBRATING -> {
                    Text("Quédate quieto… ${s.calibrationSamples} lecturas")
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
                        if (s.discarded > 0) StatRow("GPS ignorado (rebotes o baja precisión)", s.discarded.toString())
                        Text("Camina por el borde del terreno con el celular en la mano, apuntando hacia donde caminas, y vuelve al punto de inicio.")
                    } else {
                        StatRow("Lecturas (${Transport.of(s.transport).label})", s.samples.size.toString())
                        if (s.discarded > 0) StatRow("Descartadas (saltos o baja precisión)", s.discarded.toString())
                        Text("Recorre el borde del terreno y vuelve al punto de inicio.")
                    }
                    CornerControls(
                        s, vm.cornerDwellTarget,
                        onFixCorner = vm::fixCorner, onFixStart = vm::fixStartAgain, onCancel = vm::cancelCorner,
                    )
                    Button(
                        onClick = vm::finish, enabled = s.fixingVertex == null,
                        modifier = Modifier.fillMaxWidth().testTag("gps_finish"),
                    ) { Text("Finalizar") }
                }
                GpsPhase.FINISHED -> {
                    s.analysis?.let { a ->
                        val u = s.uncertainty
                        StatRow("Área", if (u != null) "${a.areaM2.m2()} ± ${u.areaU95M2.m2()} (${"%.1f".format(u.areaU95Pct)} %)" else a.areaM2.m2())
                        StatRow("Perímetro", if (u != null) "${a.perimeterM.m()} ± ${u.perimeterU95M.m()}" else a.perimeterM.m())
                        StatRow("Vértices", a.vertices.size.toString())
                        if (u != null) {
                            Text("Margen al 95 % calculado con las esquinas fijadas.", style = MaterialTheme.typography.bodySmall)
                        } else {
                            Text(
                                "Sin esquinas fijadas no se calcula el margen en el celular; el servidor lo calcula al sincronizar.",
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                        s.closureM?.let { StatRow("Error de cierre", it.m(2)) }
                        if (s.closureConsistent == false) {
                            Text(
                                "El cierre es mayor de lo esperable para la precisión del GPS: conviene volver a medir con más tiempo en cada esquina.",
                                color = MaterialTheme.colorScheme.error,
                            )
                        }
                    }
                    var name by rememberSaveable { mutableStateOf("") }
                    OutlinedTextField(name, { name = it }, label = { Text("Nombre del terreno") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    Button(onClick = { vm.save(name) }, modifier = Modifier.fillMaxWidth().testTag("gps_save")) { Text("Guardar") }
                }
            }
        }
    }
}

/**
 * Esquinas fijadas (RF-18, RF-19): al llegar a cada esquina el usuario se queda quieto mientras se
 * toman las lecturas; al final vuelve al punto de partida y lo fija otra vez para medir el cierre.
 */
@Composable
private fun CornerControls(
    s: GpsUiState, target: Int, onFixCorner: () -> Unit, onFixStart: () -> Unit, onCancel: () -> Unit,
) {
    val fixing = s.fixingVertex
    if (fixing != null) {
        val what = if (s.cornersFixed >= 3 && fixing == 0) "el punto de partida" else "la esquina ${fixing + 1}"
        Text("Quédate quieto sobre $what… ${s.dwellCount}/$target lecturas", style = MaterialTheme.typography.titleSmall)
        LinearProgressIndicator(progress = { s.dwellCount / target.toFloat() }, modifier = Modifier.fillMaxWidth())
        OutlinedButton(onClick = onCancel, modifier = Modifier.fillMaxWidth().testTag("gps_cancel_corner")) {
            Text("Cancelar esquina")
        }
        return
    }
    StatRow("Esquinas fijadas", if (s.closureFixed) "${s.cornersFixed} + cierre" else s.cornersFixed.toString())
    Text(
        "Para más precisión: al llegar a cada esquina toca «Fijar esquina» y quédate quieto ~$target s. " +
            "Al terminar, vuelve al punto de partida y fíjalo otra vez.",
        style = MaterialTheme.typography.bodySmall,
    )
    if (!s.closureFixed) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = onFixCorner, modifier = Modifier.weight(1f).testTag("gps_fix_corner")) {
                Text("Fijar esquina ${s.cornersFixed + 1}")
            }
            OutlinedButton(
                onClick = onFixStart, enabled = s.cornersFixed >= 3,
                modifier = Modifier.weight(1f).testTag("gps_fix_start"),
            ) { Text("Volver a fijar el inicio") }
        }
    }
}
