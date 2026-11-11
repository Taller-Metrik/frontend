package com.metrik.app.feature.house

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.metrik.app.data.remote.FloorJoinDto
import com.metrik.app.data.remote.FloorPlanInDto
import com.metrik.app.data.remote.FloorPlanOutDto
import com.metrik.app.data.remote.PlacedRoomDto
import com.metrik.app.data.remote.userMessage
import com.metrik.app.data.repository.FloorPlanRepository
import com.metrik.app.data.repository.SurveyRepository
import com.metrik.app.domain.LocalSurvey
import com.metrik.app.domain.SurveyMode
import com.metrik.app.ui.components.ErrorBanner
import com.metrik.app.ui.components.MetrikTopBar
import com.metrik.app.ui.components.PlanDrawing
import com.metrik.app.ui.components.StatRow
import com.metrik.app.ui.theme.MetrikColors
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.Locale
import kotlin.math.min

/** Unión tal como la arma el usuario: ambiente A (id local) muro i ↔ ambiente B muro j (0 = del vértice 1 al 2). */
data class JoinInput(val a: Long, val aWall: Int = 0, val b: Long, val bWall: Int = 0, val thickness: String = "0.15")

data class HouseUiState(
    /** Ambientes interiores del proyecto guardados en el celular. */
    val rooms: List<LocalSurvey> = emptyList(),
    /** Ambientes elegidos, en orden: el primero es la base. */
    val selected: List<Long> = emptyList(),
    val joins: List<JoinInput> = emptyList(),
    val result: FloorPlanOutDto? = null,
    val busy: Boolean = false,
    val error: String? = null,
    val message: String? = null,
) {
    fun room(id: Long): LocalSurvey? = rooms.firstOrNull { it.id == id }
}

/** Plano de vivienda (CU-08): el backend une los ambientes por sus muros; la app elige, dibuja y descarga el DXF. */
class HouseViewModel(
    private val projectId: Int,
    private val surveys: SurveyRepository,
    private val plans: FloorPlanRepository,
    private val saveFile: suspend (String, ByteArray) -> String,
) : ViewModel() {
    private val _state = MutableStateFlow(HouseUiState())
    val state: StateFlow<HouseUiState> = _state

    init {
        viewModelScope.launch {
            surveys.observeLocal(projectId).collect { list ->
                val rooms = list.filter { it.mode == SurveyMode.INTERIOR && it.vertices.size >= 3 }.sortedBy { it.createdAt }
                _state.update { it.copy(rooms = rooms) }
            }
        }
    }

    // Cambiar ambientes o uniones borra la planta anterior: ya no corresponde
    private fun edit(change: (HouseUiState) -> HouseUiState) = _state.update { change(it).copy(result = null, message = null) }

    fun toggleRoom(id: Long) = edit { s -> s.copy(selected = if (id in s.selected) s.selected - id else s.selected + id) }

    /** Nueva unión: por defecto la base con el primer ambiente elegido que todavía no está unido. */
    fun addJoin() {
        val s = _state.value
        if (s.selected.size < 2) {
            _state.update { it.copy(error = "Elige al menos dos ambientes para unirlos") }
            return
        }
        val a = s.selected.first()
        val b = s.selected.drop(1).firstOrNull { id -> s.joins.none { it.a == id || it.b == id } } ?: s.selected[1]
        edit { it.copy(joins = it.joins + JoinInput(a = a, b = b), error = null) }
    }

    fun updateJoin(i: Int, join: JoinInput) = edit { s -> s.copy(joins = s.joins.mapIndexed { k, j -> if (k == i) join else j }) }
    fun removeJoin(i: Int) = edit { s -> s.copy(joins = s.joins.filterIndexed { k, _ -> k != i }) }

    fun calculate() = run { body ->
        val out = plans.plan(projectId, body)
        _state.update { it.copy(result = out) }
    }

    fun exportDxf() = run { body ->
        val where = saveFile(dxfFileName(projectId), plans.dxf(projectId, body))
        _state.update { it.copy(message = "DXF guardado en $where") }
    }

    private fun run(block: suspend (FloorPlanInDto) -> Unit) {
        val s = _state.value
        runCatching { validate(s) }.onFailure { t ->
            _state.update { it.copy(error = t.message) }
            return
        }
        _state.update { it.copy(busy = true, error = null, message = null) }
        viewModelScope.launch {
            runCatching {
                // El backend solo conoce los ambientes sincronizados: se suben los que están solo en el celular
                val remote = s.selected.associateWith { id ->
                    val room = s.room(id) ?: error("Ambiente no encontrado")
                    room.remoteId ?: requireNotNull(surveys.sync(id).remoteId)
                }
                block(request(s, remote))
            }.onFailure { t -> _state.update { it.copy(error = t.userMessage()) } }
            _state.update { it.copy(busy = false) }
        }
    }

    private fun validate(s: HouseUiState) {
        require(s.selected.isNotEmpty()) { "Elige los ambientes de la vivienda" }
        s.joins.forEachIndexed { i, j ->
            val t = number(j.thickness) ?: error("El espesor de la unión ${i + 1} debe ser un número")
            require(t > 0 && t <= MAX_THICKNESS_M) { "El espesor de la unión ${i + 1} debe estar entre 0 y 0,50 m" }
            require(j.a != j.b) { "La unión ${i + 1} une un ambiente consigo mismo" }
            require(j.a in s.selected && j.b in s.selected) { "La unión ${i + 1} usa un ambiente que no elegiste" }
        }
    }

    private fun request(s: HouseUiState, remote: Map<Long, Int>) = FloorPlanInDto(
        rooms = s.selected.map { remote.getValue(it) },
        joins = s.joins.map { j ->
            FloorJoinDto(remote.getValue(j.a), j.aWall, remote.getValue(j.b), j.bWall, requireNotNull(number(j.thickness)))
        },
    )

    companion object {
        const val MAX_THICKNESS_M = 0.50

        fun dxfFileName(projectId: Int) = "metrik_vivienda_$projectId.dxf"

        /** Acepta coma o punto decimal ("0,15" o "0.15"). */
        private fun number(text: String): Double? = text.trim().replace(',', '.').toDoubleOrNull()?.takeIf { it.isFinite() }
    }
}

@Composable
fun HouseScreen(vm: HouseViewModel, onBack: () -> Unit) {
    val s by vm.state.collectAsStateWithLifecycle()
    Scaffold(topBar = { MetrikTopBar("Plano de vivienda", onBack = onBack) }) { padding ->
        Column(
            Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (s.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            ErrorBanner(s.error)
            s.message?.let { Text(it, color = MetrikColors.Ok, modifier = Modifier.testTag("house_message")) }

            Text("Ambientes", style = MaterialTheme.typography.titleSmall)
            if (s.rooms.isEmpty()) {
                Text(
                    "No hay ambientes interiores en este celular. Mide cada ambiente o descárgalo desde el proyecto.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            Text("El primero que elijas es la base; los demás se ubican pegados a él.", style = MaterialTheme.typography.bodySmall)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                s.rooms.forEach { r ->
                    val order = s.selected.indexOf(r.id)
                    FilterChip(
                        order >= 0, { vm.toggleRoom(r.id) },
                        { Text(if (order >= 0) "${order + 1}. ${r.name}" else r.name) },
                        Modifier.testTag("house_room_${r.name}"),
                    )
                }
            }

            Text("Muros compartidos", style = MaterialTheme.typography.titleSmall)
            s.joins.forEachIndexed { i, j -> JoinCard(i, j, s, vm) }
            OutlinedButton(onClick = vm::addJoin, enabled = s.selected.size >= 2, modifier = Modifier.testTag("house_add_join")) {
                Text("Unir dos ambientes")
            }

            Button(
                onClick = vm::calculate, enabled = !s.busy && s.selected.isNotEmpty(),
                modifier = Modifier.fillMaxWidth().testTag("house_calculate"),
            ) { Text("Armar la planta") }
            s.result?.let { HouseResult(it) }
            OutlinedButton(
                onClick = vm::exportDxf, enabled = !s.busy && s.selected.isNotEmpty(),
                modifier = Modifier.fillMaxWidth().testTag("house_dxf"),
            ) { Text("Descargar DXF de la vivienda") }
        }
    }
}

@Composable
private fun JoinCard(i: Int, j: JoinInput, s: HouseUiState, vm: HouseViewModel) {
    val chosen = s.selected.mapNotNull { s.room(it) }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("Unión ${i + 1}", style = MaterialTheme.typography.bodyMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                WallPicker("Ambiente A", j.a, j.aWall, chosen, Modifier.weight(1f)) { id, wall -> vm.updateJoin(i, j.copy(a = id, aWall = wall)) }
                WallPicker("Ambiente B", j.b, j.bWall, chosen, Modifier.weight(1f)) { id, wall -> vm.updateJoin(i, j.copy(b = id, bWall = wall)) }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    j.thickness, { vm.updateJoin(i, j.copy(thickness = it)) }, Modifier.weight(1f),
                    label = { Text("Espesor del muro (m)") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    supportingText = { Text("0,15 tabique · 0,25 portante") },
                )
                TextButton(onClick = { vm.removeJoin(i) }) { Text("Quitar") }
            }
        }
    }
}

/** Elige el ambiente y uno de sus muros; el dibujo numera los muros (M1 va del vértice 1 al 2). */
@Composable
private fun WallPicker(
    title: String, roomId: Long, wall: Int, rooms: List<LocalSurvey>, modifier: Modifier, onChange: (Long, Int) -> Unit,
) {
    val room = rooms.firstOrNull { it.id == roomId }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(title, style = MaterialTheme.typography.bodySmall)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            rooms.forEach { r -> FilterChip(r.id == roomId, { onChange(r.id, 0) }, { Text(r.name) }) }
        }
        if (room != null) {
            Box(Modifier.fillMaxWidth().height(140.dp)) { PlanDrawing(polygon = room.vertices, numberWalls = true) }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                repeat(room.vertices.size) { w -> FilterChip(w == wall, { onChange(room.id, w) }, { Text("M${w + 1}") }) }
            }
        }
    }
}

@Composable
private fun HouseResult(r: FloorPlanOutDto) {
    HorizontalDivider()
    Box(Modifier.fillMaxWidth().height(320.dp)) { FloorPlanDrawing(r.rooms) }
    Text("Cuadro de áreas", style = MaterialTheme.typography.titleSmall)
    r.rooms.forEach { StatRow(it.name, String.format(Locale.US, "%.2f m²", it.areaM2)) }
    StatRow("Área total", String.format(Locale.US, "%.2f m²", r.totalAreaM2), Modifier.testTag("house_total"))
    if (r.warnings.isNotEmpty()) {
        Text("Advertencias", style = MaterialTheme.typography.titleSmall)
        r.warnings.forEach { Text("• $it", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
    }
}

/** Planta de la vivienda: cada ambiente con su nombre y área. El eje Y se invierte (norte hacia arriba). */
@Composable
private fun FloorPlanDrawing(rooms: List<PlacedRoomDto>) {
    val measurer = rememberTextMeasurer()
    val labelColor = MaterialTheme.colorScheme.onSurface
    Canvas(Modifier.fillMaxSize().testTag("house_drawing")) {
        val all = rooms.flatMap { it.vertices }.filter { it.size >= 2 }
        if (all.isEmpty()) return@Canvas
        val pad = 24f
        val minX = all.minOf { it[0] }
        val maxX = all.maxOf { it[0] }
        val minY = all.minOf { it[1] }
        val maxY = all.maxOf { it[1] }
        val spanX = (maxX - minX).coerceAtLeast(1.0)
        val spanY = (maxY - minY).coerceAtLeast(1.0)
        val k = min((size.width - 2 * pad) / spanX, (size.height - 2 * pad) / spanY).toFloat()
        val offX = (size.width - spanX.toFloat() * k) / 2
        val offY = (size.height - spanY.toFloat() * k) / 2
        fun map(p: List<Double>) = Offset(offX + ((p[0] - minX) * k).toFloat(), size.height - offY - ((p[1] - minY) * k).toFloat())

        rooms.forEach { room ->
            val pts = room.vertices.filter { it.size >= 2 }.map(::map)
            if (pts.size < 3) return@forEach
            val path = Path().apply { moveTo(pts[0].x, pts[0].y); pts.drop(1).forEach { lineTo(it.x, it.y) }; close() }
            drawPath(path, MetrikColors.Wall.copy(alpha = 0.08f))
            drawPath(path, MetrikColors.Wall, style = Stroke(width = 5f))
            val c = Offset(pts.map { it.x }.average().toFloat(), pts.map { it.y }.average().toFloat())
            val label = measurer.measure(
                "${room.name}\n${String.format(Locale.US, "%.2f m²", room.areaM2)}",
                TextStyle(fontSize = 11.sp, color = labelColor, background = MetrikColors.Amber.copy(alpha = 0.85f)),
            )
            drawText(label, topLeft = Offset(c.x - label.size.width / 2, c.y - label.size.height / 2))
        }
    }
}
