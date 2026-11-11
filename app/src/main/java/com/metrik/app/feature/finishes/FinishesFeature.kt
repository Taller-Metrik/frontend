package com.metrik.app.feature.finishes

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
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.metrik.app.data.remote.FinishesInDto
import com.metrik.app.data.remote.FinishesOutDto
import com.metrik.app.data.remote.OpeningDto
import com.metrik.app.data.remote.PriceDto
import com.metrik.app.data.remote.missingLabels
import com.metrik.app.data.remote.userMessage
import com.metrik.app.data.repository.FinishesRepository
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

/** Vano tal como lo escribe el usuario: muro (0 = del vértice 1 al 2), tipo y medidas en texto. */
data class OpeningInput(val wall: Int = 0, val kind: String = "door", val width: String = "0.90", val height: String = "2.10")

/** Partidas de mano de obra: el usuario pone su precio por m² o por metro. */
val LABOR = listOf(
    "floor_m2" to "Piso (S/ por m²)",
    "baseboard_m" to "Contrazócalo (S/ por m)",
    "plaster_m2" to "Tarrajeo (S/ por m²)",
    "wall_paint_m2" to "Pintura de muros (S/ por m²)",
    "ceiling_paint_m2" to "Pintura de cielo raso (S/ por m²)",
)

data class FinishesUiState(
    val survey: LocalSurvey? = null,
    val height: String = "2.50",
    val openings: List<OpeningInput> = emptyList(),
    val labor: Map<String, String> = emptyMap(),
    val prices: List<PriceDto> = emptyList(),
    val result: FinishesOutDto? = null,
    val busy: Boolean = false,
    val error: String? = null,
) {
    val walls: Int get() = survey?.vertices?.size ?: 0
}

/** Metrado de acabados (CU-07): partidas, insumos y presupuesto calculados en el servidor con el catálogo. */
class FinishesViewModel(
    private val localId: Long,
    private val surveys: SurveyRepository,
    private val repo: FinishesRepository,
) : ViewModel() {
    private val _state = MutableStateFlow(FinishesUiState())
    val state: StateFlow<FinishesUiState> = _state

    init {
        viewModelScope.launch {
            val s = surveys.getLocal(localId)
            _state.update { if (s == null) it.copy(error = "Recorrido no encontrado") else it.copy(survey = s) }
        }
        loadPrices()
    }

    // Cualquier cambio de los datos o de los precios borra el metrado y el presupuesto anteriores: ya no corresponden
    private fun edit(change: (FinishesUiState) -> FinishesUiState) = _state.update { change(it).copy(result = null) }

    fun setHeight(v: String) = edit { it.copy(height = v) }
    fun addOpening(kind: String) = edit {
        val new = if (kind == "door") OpeningInput() else OpeningInput(kind = "window", width = "1.20", height = "1.00")
        it.copy(openings = it.openings + new)
    }
    fun removeOpening(i: Int) = edit { it.copy(openings = it.openings.filterIndexed { k, _ -> k != i }) }
    fun updateOpening(i: Int, wall: Int, kind: String, width: String, height: String) = edit {
        it.copy(openings = it.openings.mapIndexed { k, o -> if (k == i) OpeningInput(wall, kind, width, height) else o })
    }
    fun setLabor(key: String, text: String) = edit { it.copy(labor = it.labor + (key to text)) }

    fun loadPrices() = busy { _state.update { it.copy(prices = repo.prices()) } }

    fun setOwnPrice(key: String, text: String) {
        val price = number(text)?.takeIf { it >= 0 }
        if (price == null) {
            _state.update { it.copy(error = "El precio debe ser un número mayor o igual que 0") }
            return
        }
        busy { repo.setPrice(key, price); val prices = repo.prices(); edit { it.copy(prices = prices) } }
    }

    fun resetPrice(key: String) = busy { repo.resetPrice(key); val prices = repo.prices(); edit { it.copy(prices = prices) } }

    fun calculate() {
        val s = _state.value
        val body = runCatching { request(s) }.getOrElse { t ->
            _state.update { it.copy(error = t.message) }
            return
        }
        busy {
            val survey = s.survey ?: error("Recorrido no cargado")
            val synced = if (survey.synced) survey else surveys.sync(survey.id)
            _state.update { it.copy(survey = synced) }
            val out = repo.finishes(requireNotNull(synced.remoteId), body)
            _state.update { it.copy(result = out) }
        }
    }

    private fun request(s: FinishesUiState): FinishesInDto {
        val height = number(s.height)?.takeIf { it in 1.8..6.0 } ?: error("La altura debe estar entre 1,8 y 6 m")
        val openings = s.openings.mapIndexed { i, o ->
            val w = number(o.width)?.takeIf { it > 0 } ?: error("El ancho del vano ${i + 1} debe ser un número mayor que 0")
            val h = number(o.height)?.takeIf { it > 0 } ?: error("El alto del vano ${i + 1} debe ser un número mayor que 0")
            OpeningDto(o.wall, o.kind, w, h)
        }
        val labor = s.labor.mapNotNull { (k, v) -> number(v)?.takeIf { it >= 0 }?.let { k to it } }.toMap()
        return FinishesInDto(heightM = height, openings = openings, laborPrices = labor.ifEmpty { null })
    }

    private fun busy(block: suspend () -> Unit) {
        _state.update { it.copy(busy = true, error = null) }
        viewModelScope.launch {
            runCatching { block() }.onFailure { t -> _state.update { it.copy(error = t.userMessage()) } }
            _state.update { it.copy(busy = false) }
        }
    }

    private companion object {
        /** Acepta coma o punto decimal ("2,60" o "2.60"). */
        fun number(text: String): Double? = text.trim().replace(',', '.').toDoubleOrNull()?.takeIf { it.isFinite() }
    }
}

private fun Double.soles(): String = String.format(Locale.US, "S/ %,.2f", this)

private fun Double.qty(unit: String): String =
    if (unit in setOf("caja", "bolsa", "saco", "galón", "balde", "kg", "und")) String.format(Locale.US, "%.0f %s", this, unit)
    else String.format(Locale.US, "%.2f %s", this, unit)

@Composable
fun FinishesScreen(vm: FinishesViewModel, onBack: () -> Unit) {
    val s by vm.state.collectAsStateWithLifecycle()
    Scaffold(topBar = { MetrikTopBar("Metrado de acabados", onBack = onBack) }) { padding ->
        Column(
            Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (s.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            ErrorBanner(s.error)
            val decimal = KeyboardOptions(keyboardType = KeyboardType.Decimal)
            OutlinedTextField(
                s.height, vm::setHeight, Modifier.fillMaxWidth().testTag("finishes_height"),
                label = { Text("Altura del piso al techo (m)") }, singleLine = true, keyboardOptions = decimal,
            )

            Text("Puertas y ventanas", style = MaterialTheme.typography.titleSmall)
            s.openings.forEachIndexed { i, o -> OpeningCard(i, o, s.walls, vm) }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { vm.addOpening("door") }, enabled = s.walls >= 3) { Text("Agregar puerta") }
                OutlinedButton(onClick = { vm.addOpening("window") }, enabled = s.walls >= 3) { Text("Agregar ventana") }
            }

            Text("Mano de obra (opcional)", style = MaterialTheme.typography.titleSmall)
            LABOR.forEach { (key, label) ->
                OutlinedTextField(
                    s.labor[key].orEmpty(), { vm.setLabor(key, it) }, Modifier.fillMaxWidth(),
                    label = { Text(label) }, singleLine = true, keyboardOptions = decimal,
                )
            }
            Button(
                onClick = vm::calculate, enabled = !s.busy && s.survey != null,
                modifier = Modifier.fillMaxWidth().testTag("finishes_calculate"),
            ) { Text("Calcular metrado y presupuesto") }

            s.result?.let { FinishesResult(it) }
            HorizontalDivider()
            PriceCatalog(s.prices, vm)
        }
    }
}

@Composable
private fun OpeningCard(i: Int, o: OpeningInput, walls: Int, vm: FinishesViewModel) {
    val decimal = KeyboardOptions(keyboardType = KeyboardType.Decimal)
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(8.dp)) {
            Text(if (o.kind == "door") "Puerta ${i + 1}: muro" else "Ventana ${i + 1}: muro", style = MaterialTheme.typography.bodySmall)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                repeat(walls) { w -> FilterChip(o.wall == w, { vm.updateOpening(i, w, o.kind, o.width, o.height) }, { Text("${w + 1}") }) }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    o.width, { vm.updateOpening(i, o.wall, o.kind, it, o.height) }, Modifier.weight(1f),
                    label = { Text("Ancho (m)") }, singleLine = true, keyboardOptions = decimal,
                )
                OutlinedTextField(
                    o.height, { vm.updateOpening(i, o.wall, o.kind, o.width, it) }, Modifier.weight(1f),
                    label = { Text("Alto (m)") }, singleLine = true, keyboardOptions = decimal,
                )
                TextButton(onClick = { vm.removeOpening(i) }) { Text("Quitar") }
            }
        }
    }
}

@Composable
private fun FinishesResult(r: FinishesOutDto) {
    HorizontalDivider()
    Text("Metrado", style = MaterialTheme.typography.titleSmall)
    r.items.forEach { StatRow(it.description, it.quantity.qty(it.unit)) }
    r.notes.forEach { Text("• $it", style = MaterialTheme.typography.bodySmall) }
    r.budget?.let { b ->
        Text("Presupuesto", style = MaterialTheme.typography.titleSmall)
        b.lines.forEach { StatRow("${it.description} (${it.quantity.qty(it.unit)} × ${it.unitPrice.soles()})", it.subtotal.soles()) }
        StatRow("Costo directo", b.directCost.soles())
        StatRow("Gastos generales (${b.overheadPct.toInt()} %)", b.overhead.soles())
        StatRow("Utilidad (${b.profitPct.toInt()} %)", b.profit.soles())
        StatRow("Subtotal", b.subtotal.soles())
        StatRow("IGV (${b.igvPct.toInt()} %)", b.igv.soles())
        StatRow("Total", b.total.soles(), Modifier.testTag("finishes_total"))
        if (b.missingPrices.isNotEmpty()) {
            Text(
                "Sin precio (no incluidas en el total): ${b.missingLabels(r.items).joinToString()}. " +
                    "Ponles tu precio en «Precios de materiales» o en la mano de obra.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error,
            )
        }
    }
    Text("Supuestos", style = MaterialTheme.typography.titleSmall)
    r.assumptions.forEach { Text("• $it", style = MaterialTheme.typography.bodySmall) }
}

@Composable
private fun PriceCatalog(prices: List<PriceDto>, vm: FinishesViewModel) {
    Text("Precios de materiales", style = MaterialTheme.typography.titleSmall)
    Text("Precio de la tienda (con su fecha) o el tuyo, que tiene prioridad.", style = MaterialTheme.typography.bodySmall)
    val drafts = remember { mutableStateMapOf<String, String>() }
    prices.forEach { p ->
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(8.dp)) {
                Text("${p.description} (${p.unit})", style = MaterialTheme.typography.bodyMedium)
                val origin = when (p.source) {
                    "user" -> "tu precio"
                    "store" -> "${p.store} · ${p.fetchedAt?.take(10).orEmpty()}"
                    else -> "sin precio"
                }
                Text("${p.price?.soles() ?: "—"} · $origin", style = MaterialTheme.typography.bodySmall)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        drafts[p.key].orEmpty(), { drafts[p.key] = it }, Modifier.weight(1f),
                        label = { Text("Mi precio (S/)") }, singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    )
                    TextButton(onClick = { vm.setOwnPrice(p.key, drafts[p.key].orEmpty()) }) { Text("Guardar") }
                    if (p.source == "user") TextButton(onClick = { vm.resetPrice(p.key) }) { Text("Usar tienda") }
                }
            }
        }
    }
}
