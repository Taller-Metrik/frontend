package com.metrik.app.feature.fence

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.metrik.app.data.remote.FenceInDto
import com.metrik.app.data.remote.FenceOutDto
import com.metrik.app.data.remote.GateDto
import com.metrik.app.data.remote.TakeoffItemDto
import com.metrik.app.data.remote.missingLabels
import com.metrik.app.data.remote.userMessage
import com.metrik.app.data.repository.FenceRepository
import com.metrik.app.data.repository.SurveyRepository
import com.metrik.app.domain.LocalSurvey
import com.metrik.app.ui.components.ErrorBanner
import com.metrik.app.ui.components.MetrikTopBar
import com.metrik.app.ui.components.StatRow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.Locale

/** Portón tal como lo escribe el usuario: lado (0 = del vértice 1 al 2) y ancho en texto. */
data class GateInput(val side: Int = 0, val width: String = "4")

data class FenceUiState(
    val survey: LocalSurvey? = null,
    val kind: String = "wire",
    val gates: List<GateInput> = emptyList(),
    val strands: String = "4",
    val postSpacing: String = "3",
    val wallHeight: String = "2.40",
    val columnSpacing: String = "3",
    /** Precio escrito por el usuario para cada partida presupuestable. */
    val prices: Map<String, String> = emptyMap(),
    val result: FenceOutDto? = null,
    val busy: Boolean = false,
    val error: String? = null,
) {
    val sides: Int get() = survey?.vertices?.size ?: 0
}

/** Cerco perimétrico (CU-09): metrado con rango de compra y presupuesto, calculados en el servidor. */
class FenceViewModel(
    private val localId: Long,
    private val surveys: SurveyRepository,
    private val fences: FenceRepository,
) : ViewModel() {
    private val _state = MutableStateFlow(FenceUiState())
    val state: StateFlow<FenceUiState> = _state

    init {
        viewModelScope.launch {
            val s = surveys.getLocal(localId)
            _state.update { if (s == null) it.copy(error = "Recorrido no encontrado") else it.copy(survey = s) }
        }
    }

    // Cualquier cambio de los datos borra el metrado anterior: ya no corresponde (los precios escritos se conservan)
    private fun edit(change: (FenceUiState) -> FenceUiState) = _state.update { change(it).copy(result = null) }

    fun setKind(kind: String) = edit { it.copy(kind = kind) }
    fun setStrands(v: String) = edit { it.copy(strands = v) }
    fun setPostSpacing(v: String) = edit { it.copy(postSpacing = v) }
    fun setWallHeight(v: String) = edit { it.copy(wallHeight = v) }
    fun setColumnSpacing(v: String) = edit { it.copy(columnSpacing = v) }
    fun addGate() = edit { it.copy(gates = it.gates + GateInput()) }
    fun removeGate(i: Int) = edit { it.copy(gates = it.gates.filterIndexed { k, _ -> k != i }) }
    fun updateGate(i: Int, side: Int, width: String) =
        edit { it.copy(gates = it.gates.mapIndexed { k, g -> if (k == i) GateInput(side, width) else g }) }
    fun setPrice(key: String, text: String) = _state.update { it.copy(prices = it.prices + (key to text)) }

    /** Metrado sin presupuesto. */
    fun calculate() = run(withPrices = false)

    /** Metrado con presupuesto: solo se envían precios válidos de partidas presupuestables. */
    fun calculateBudget() = run(withPrices = true)

    private fun run(withPrices: Boolean) {
        val s = _state.value
        val body = runCatching { request(s, withPrices) }.getOrElse { t ->
            _state.update { it.copy(error = t.message) }
            return
        }
        _state.update { it.copy(busy = true, error = null) }
        viewModelScope.launch {
            runCatching {
                val survey = s.survey ?: error("Recorrido no cargado")
                val synced = if (survey.synced) survey else surveys.sync(survey.id)
                _state.update { it.copy(survey = synced) }
                fences.calculate(requireNotNull(synced.remoteId), body)
            }.onSuccess { out -> _state.update { it.copy(result = out) } }
                .onFailure { t -> _state.update { it.copy(error = t.userMessage()) } }
            _state.update { it.copy(busy = false) }
        }
    }

    private fun request(s: FenceUiState, withPrices: Boolean): FenceInDto {
        val gates = s.gates.mapIndexed { i, g ->
            val w = number(g.width) ?: error("El ancho del portón ${i + 1} debe ser un número mayor que 0")
            require(w > 0) { "El ancho del portón ${i + 1} debe ser mayor que 0" }
            GateDto(g.side, w)
        }
        val priceable = s.result?.items?.filter { it.priceable }?.map { it.key }?.toSet().orEmpty()
        val prices = if (withPrices) {
            s.prices.filterKeys { it in priceable }
                .mapNotNull { (k, v) -> number(v)?.takeIf { it >= 0 }?.let { k to it } }
                .toMap()
        } else {
            null
        }
        return if (s.kind == "wire") {
            FenceInDto(
                kind = "wire", gates = gates, prices = prices,
                strands = s.strands.trim().toIntOrNull()?.takeIf { it in 1..10 } ?: error("Los hilos deben ser de 1 a 10"),
                postSpacingM = number(s.postSpacing)?.takeIf { it in 1.5..5.0 }
                    ?: error("La separación de postes debe estar entre 1,5 y 5 m"),
            )
        } else {
            FenceInDto(
                kind = "brick", gates = gates, prices = prices,
                wallHeightM = number(s.wallHeight)?.takeIf { it in 1.0..4.0 } ?: error("La altura del muro debe estar entre 1 y 4 m"),
                columnSpacingM = number(s.columnSpacing)?.takeIf { it in 2.0..4.5 }
                    ?: error("La separación de columnas debe estar entre 2 y 4,5 m"),
            )
        }
    }

    private companion object {
        /** Acepta coma o punto decimal ("2,40" o "2.40"). */
        fun number(text: String): Double? = text.trim().replace(',', '.').toDoubleOrNull()?.takeIf { it.isFinite() }
    }
}

private fun Double.qty(unit: String): String =
    if (unit == "und" || unit == "rollo") String.format(Locale.US, "%.0f %s", this, unit)
    else String.format(Locale.US, "%.2f %s", this, unit)

private fun Double.soles(): String = String.format(Locale.US, "S/ %,.2f", this)

@Composable
fun FenceScreen(vm: FenceViewModel, onBack: () -> Unit) {
    val s by vm.state.collectAsStateWithLifecycle()
    Scaffold(topBar = { MetrikTopBar("Cerco perimétrico", onBack = onBack) }) { padding ->
        Column(
            Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (s.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            ErrorBanner(s.error)
            Text("Tipo de cerco", style = MaterialTheme.typography.titleSmall)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(s.kind == "wire", { vm.setKind("wire") }, { Text("Postes y alambre de púas") }, Modifier.testTag("fence_wire"))
                FilterChip(s.kind == "brick", { vm.setKind("brick") }, { Text("Muro de ladrillo") }, Modifier.testTag("fence_brick"))
            }
            val decimal = KeyboardOptions(keyboardType = KeyboardType.Decimal)
            if (s.kind == "wire") {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(s.postSpacing, vm::setPostSpacing, Modifier.weight(1f),
                        label = { Text("Postes cada (m)") }, singleLine = true, keyboardOptions = decimal)
                    OutlinedTextField(s.strands, vm::setStrands, Modifier.weight(1f), label = { Text("Hilos") }, singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                }
            } else {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(s.wallHeight, vm::setWallHeight, Modifier.weight(1f),
                        label = { Text("Altura (m)") }, singleLine = true, keyboardOptions = decimal)
                    OutlinedTextField(s.columnSpacing, vm::setColumnSpacing, Modifier.weight(1f),
                        label = { Text("Columnas cada (m)") }, singleLine = true, keyboardOptions = decimal)
                }
                Text("Altura medida desde el terreno natural hasta lo alto de la viga solera.", style = MaterialTheme.typography.bodySmall)
            }

            Text("Portones", style = MaterialTheme.typography.titleSmall)
            s.gates.forEachIndexed { i, g -> GateCard(i, g, s.sides, vm) }
            OutlinedButton(onClick = vm::addGate, enabled = s.sides >= 3, modifier = Modifier.testTag("fence_add_gate")) {
                Text("Agregar portón")
            }
            Text("El lado 1 va del vértice 1 al 2 del plano, el lado 2 del 2 al 3, y así.", style = MaterialTheme.typography.bodySmall)

            Button(
                onClick = vm::calculate, enabled = !s.busy && s.survey != null,
                modifier = Modifier.fillMaxWidth().testTag("fence_calculate"),
            ) { Text("Calcular metrado") }
            s.result?.let { r -> FenceResult(r, s, vm) }
        }
    }
}

@Composable
private fun GateCard(i: Int, g: GateInput, sides: Int, vm: FenceViewModel) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(8.dp)) {
            Text("Lado del portón ${i + 1}", style = MaterialTheme.typography.bodySmall)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                repeat(sides) { side -> FilterChip(g.side == side, { vm.updateGate(i, side, g.width) }, { Text("${side + 1}") }) }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    g.width, { vm.updateGate(i, g.side, it) }, Modifier.weight(1f), label = { Text("Ancho (m)") },
                    singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                )
                TextButton(onClick = { vm.removeGate(i) }) { Text("Quitar") }
            }
        }
    }
}

@Composable
private fun FenceResult(r: FenceOutDto, s: FenceUiState, vm: FenceViewModel) {
    HorizontalDivider()
    StatRow(
        "Perímetro",
        r.perimeterU95M?.let { String.format(Locale.US, "%.2f ± %.2f m", r.perimeterM, it) }
            ?: String.format(Locale.US, "%.2f m", r.perimeterM),
    )
    StatRow("Longitud a cercar (sin portones)", String.format(Locale.US, "%.2f m", r.netLengthM))
    Text("Metrado", style = MaterialTheme.typography.titleSmall)
    r.items.forEach { item -> ItemRow(item, s, vm) }
    r.notes.forEach { Text("• $it", style = MaterialTheme.typography.bodySmall) }
    Text("Supuestos", style = MaterialTheme.typography.titleSmall)
    r.assumptions.forEach { Text("• $it", style = MaterialTheme.typography.bodySmall) }
    r.budget?.let { b ->
        HorizontalDivider()
        Text("Presupuesto", style = MaterialTheme.typography.titleSmall)
        b.lines.forEach { StatRow("${it.description} (${it.quantity.qty(it.unit)} × ${it.unitPrice.soles()})", it.subtotal.soles()) }
        StatRow("Costo directo", b.directCost.soles())
        StatRow("Gastos generales (${b.overheadPct.toInt()} %)", b.overhead.soles())
        StatRow("Utilidad (${b.profitPct.toInt()} %)", b.profit.soles())
        StatRow("Subtotal", b.subtotal.soles())
        StatRow("IGV (${b.igvPct.toInt()} %)", b.igv.soles())
        StatRow("Total", b.total.soles(), Modifier.testTag("fence_total"))
        if (b.missingPrices.isNotEmpty()) {
            Text("Sin precio (no incluidas): ${b.missingLabels(r.items).joinToString()}", style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun ItemRow(item: TakeoffItemDto, s: FenceUiState, vm: FenceViewModel) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(8.dp)) {
            Text(item.description + if (item.priceable) "" else " (sin precio)", style = MaterialTheme.typography.bodyMedium)
            Text(
                "${item.quantity.qty(item.unit)} · rango ${item.min.qty(item.unit)} – ${item.max.qty(item.unit)} · " +
                    "comprar ${item.purchase.qty(item.unit)}",
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}
