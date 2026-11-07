package com.metrik.app.feature.validation

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.metrik.app.data.remote.GpsReferenceDto
import com.metrik.app.data.remote.userMessage
import com.metrik.app.data.repository.SurveyRepository
import com.metrik.app.data.repository.ValidationRepository
import com.metrik.app.domain.LocalSurvey
import com.metrik.app.ui.components.ErrorBanner
import com.metrik.app.ui.components.LoadingButton
import com.metrik.app.ui.components.MetrikTopBar
import com.metrik.app.ui.components.StatRow
import com.metrik.app.ui.components.m
import com.metrik.app.ui.components.m2
import com.metrik.app.ui.components.pct
import com.metrik.app.ui.theme.MetrikColors
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Referencias para terrenos (sin wincha). La imagen satelital se agregará cuando exista el mapa para marcar las esquinas. */
val GPS_REFERENCES = listOf(
    "cancha" to "Cancha oficial",
    "catastro" to "Catastro / SUNARP",
    "rtk" to "GPS profesional",
)

data class GpsReferenceUiState(
    val survey: LocalSurvey? = null,
    val type: String = "cancha",
    val areaText: String = "",
    val perimeterText: String = "",
    val loading: Boolean = false,
    val report: GpsReferenceDto? = null,
    val error: String? = null,
)

class GpsReferenceViewModel(
    private val localId: Long,
    private val surveys: SurveyRepository,
    private val repo: ValidationRepository,
) : ViewModel() {
    private val _state = MutableStateFlow(GpsReferenceUiState())
    val state: StateFlow<GpsReferenceUiState> = _state

    init { viewModelScope.launch { _state.update { it.copy(survey = surveys.getLocal(localId)) } } }

    fun setType(t: String) = _state.update { it.copy(type = t) }
    fun onArea(t: String) = _state.update { it.copy(areaText = t, error = null) }
    fun onPerimeter(t: String) = _state.update { it.copy(perimeterText = t, error = null) }

    fun submit() {
        val s = _state.value
        val survey = s.survey ?: return
        val area = s.areaText.replace(',', '.').toDoubleOrNull()
        val per = s.perimeterText.replace(',', '.').toDoubleOrNull()
        if ((area == null || area <= 0) && (per == null || per <= 0)) {
            _state.update { it.copy(error = "Ingresa el área real o el perímetro real de la referencia") }
            return
        }
        _state.update { it.copy(loading = true, error = null) }
        viewModelScope.launch {
            runCatching {
                val synced = if (survey.synced) survey else surveys.sync(survey.id)
                repo.submitGpsReference(requireNotNull(synced.remoteId), s.type, area?.takeIf { it > 0 }, per?.takeIf { it > 0 })
            }.onSuccess { r -> _state.update { it.copy(loading = false, report = r) } }
                .onFailure { t -> _state.update { it.copy(loading = false, error = t.userMessage()) } }
        }
    }
}

@Composable
fun GpsReferenceScreen(vm: GpsReferenceViewModel, onBack: () -> Unit) {
    val s by vm.state.collectAsStateWithLifecycle()
    Scaffold(topBar = { MetrikTopBar("Validar terreno (GPS)", onBack = onBack) }) { padding ->
        Column(
            Modifier.padding(padding).fillMaxSize().imePadding().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("Un terreno no se mide con wincha: compáralo con una referencia confiable.", style = MaterialTheme.typography.bodyMedium)
            Text("¿Con qué lo comparas?", style = MaterialTheme.typography.titleSmall)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                GPS_REFERENCES.forEach { (key, label) ->
                    FilterChip(selected = s.type == key, onClick = { vm.setType(key) }, label = { Text(label) }, modifier = Modifier.testTag("ref_$key"))
                }
            }
            OutlinedTextField(s.areaText, vm::onArea, label = { Text("Área real (m²)") }, singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), modifier = Modifier.fillMaxWidth().testTag("ref_area"))
            OutlinedTextField(s.perimeterText, vm::onPerimeter, label = { Text("Perímetro real (m)") }, singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), modifier = Modifier.fillMaxWidth().testTag("ref_perimeter"))
            ErrorBanner(s.error)
            LoadingButton("Calcular margen de error", s.loading, vm::submit, Modifier.fillMaxWidth().testTag("ref_submit"))
            s.report?.let { r ->
                Card(Modifier.fillMaxWidth().testTag("ref_report")) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("Resultado · ${r.referenceLabel}", fontWeight = FontWeight.SemiBold)
                        StatRow("Área GPS vs real", "${r.measuredAreaM2.m2()} vs ${r.realAreaM2?.m2() ?: "—"}")
                        r.areaErrorPct?.let { StatRow("Error de área", it.pct()) }
                        StatRow("Perímetro GPS vs real", "${r.measuredPerimeterM.m(1)} vs ${r.realPerimeterM?.m(1) ?: "—"}")
                        r.perimeterErrorPct?.let { StatRow("Error de perímetro", it.pct()) }
                        r.vertexMaeM?.let { StatRow("Error medio por esquina", it.m(1)) }
                        r.meetsTarget?.let {
                            Text(if (it) "✔ Cumple el objetivo (≤ ${r.targetPct.toInt()} %)" else "✘ No cumple el objetivo (≤ ${r.targetPct.toInt()} %)",
                                color = if (it) MetrikColors.Ok else MetrikColors.Bad, fontWeight = FontWeight.SemiBold)
                        }
                    }
                }
            }
        }
    }
}
