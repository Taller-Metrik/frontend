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
import com.metrik.app.gps.GpsMath
import com.metrik.app.gps.LocationSource
import com.metrik.app.gps.Transport
import com.metrik.app.ui.components.ErrorBanner
import com.metrik.app.ui.components.MetrikTopBar
import com.metrik.app.ui.components.PlanDrawing
import com.metrik.app.ui.components.StatRow
import com.metrik.app.ui.components.m
import com.metrik.app.ui.components.m2
import kotlinx.coroutines.Job
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
)

/** Medición exterior con GPS (CU-02): calibración condicional, recorrido y área (RF-08, RF-09). */
class GpsViewModel(
    private val projectId: Int,
    private val location: LocationSource,
    private val surveys: SurveyRepository,
    private val calibrationTarget: Int = 15,
) : ViewModel() {
    private val _state = MutableStateFlow(GpsUiState())
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

    fun startRecording() {
        job?.cancel()
        _state.update { it.copy(phase = GpsPhase.RECORDING, samples = emptyList(), track = emptyList(), error = null, discarded = 0) }
        val profile = Transport.of(_state.value.transport)
        job = viewModelScope.launch {
            location.samples.collect { s ->
                if (!Transport.accept(profile, _state.value.samples.lastOrNull(), s)) {
                    _state.update { it.copy(discarded = it.discarded + 1, lastAccuracyM = s.accuracyM) }
                    return@collect
                }
                val samples = _state.value.samples + s
                _state.update {
                    it.copy(samples = samples, lastAccuracyM = s.accuracyM, track = GpsMath.toEnu(samples))
                }
            }
        }
    }

    fun finish() {
        job?.cancel()
        val samples = _state.value.samples
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

    fun save(name: String) {
        val s = _state.value
        val analysis = s.analysis ?: return
        if (name.isBlank()) { _state.update { it.copy(error = "Ponle un nombre al terreno") }; return }
        viewModelScope.launch {
            val id = surveys.saveLocal(
                LocalSurvey(
                    projectId = projectId, name = name.trim(), mode = SurveyMode.EXTERIOR, gps = s.samples,
                    capture = com.metrik.app.domain.CaptureInfo("gps", 0.0, 0.0, s.transport),
                    vertices = analysis.vertices, areaM2 = analysis.areaM2, perimeterM = analysis.perimeterM,
                    scaleDenominator = maxOf(500, Scale.suggestFor(analysis.vertices)),
                ),
            )
            _state.update { it.copy(savedLocalId = id) }
        }
    }

    override fun onCleared() { job?.cancel() }
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
            Card(Modifier.fillMaxWidth().weight(1f)) { PlanDrawing(track = s.track, polygon = s.polygon) }
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
                            "Primero quédate quieto unos segundos para medir el error.",
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
                    StatRow("Lecturas (${Transport.of(s.transport).label})", s.samples.size.toString())
                    if (s.discarded > 0) StatRow("Descartadas (saltos o baja precisión)", s.discarded.toString())
                    Text("Camina por el borde del terreno y vuelve al punto de inicio.")
                    Button(onClick = vm::finish, modifier = Modifier.fillMaxWidth().testTag("gps_finish")) { Text("Finalizar") }
                }
                GpsPhase.FINISHED -> {
                    s.analysis?.let { a ->
                        StatRow("Área", a.areaM2.m2())
                        StatRow("Perímetro", a.perimeterM.m())
                        StatRow("Vértices", a.vertices.size.toString())
                    }
                    var name by rememberSaveable { mutableStateOf("") }
                    OutlinedTextField(name, { name = it }, label = { Text("Nombre del terreno") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    Button(onClick = { vm.save(name) }, modifier = Modifier.fillMaxWidth().testTag("gps_save")) { Text("Guardar") }
                }
            }
        }
    }
}
