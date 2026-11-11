package com.metrik.app.feature.house

import com.metrik.app.core.geometry.Point
import androidx.compose.foundation.Canvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.runtime.setValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.foundation.gestures.detectDragGestures
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

/**
 * Unión tal como la arma el usuario: ambiente A (id local) muro i ↔ ambiente B muro j (0 = del vértice 1 al 2).
 * Si viene de una puerta ([door]), lleva los puntos de la puerta en cada ambiente y el backend elige los muros.
 */
data class JoinInput(
    val a: Long,
    val aWall: Int = 0,
    val b: Long,
    val bWall: Int = 0,
    val thickness: String = "0.15",
    val aPoint: Point? = null,
    val bPoint: Point? = null,
    val door: String? = null,
)

/** Contorno de la casa frente a la suma de los ambientes: la diferencia es el área de los muros y el error. */
data class ContourComparison(
    val contourAreaM2: Double,
    val contourPerimeterM: Double,
    val roomsAreaM2: Double,
    val differenceM2: Double,
    val differencePct: Double,
)

data class HouseUiState(
    /** Ambientes interiores del proyecto guardados en el celular (sin el contorno de la casa). */
    val rooms: List<LocalSurvey> = emptyList(),
    /** Contorno de toda la casa (medido caminando su borde), para comparar con la suma de los ambientes. */
    val contour: LocalSurvey? = null,
    /** Ambientes elegidos, en orden: el primero es la base. */
    val selected: List<Long> = emptyList(),
    val joins: List<JoinInput> = emptyList(),
    /** Posición de cada ambiente elegido en el panel interactivo. */
    val poses: Map<Long, RoomPose> = emptyMap(),
    /** Ambiente tocado por última vez (el que giran los botones). */
    val active: Long? = null,
    /** Espesor de los muros compartidos (m) para las uniones con imán. */
    val thickness: String = "0.15",
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
                val interior = list.filter { it.mode == SurveyMode.INTERIOR && it.vertices.size >= 3 }.sortedBy { it.createdAt }
                // El contorno se reconoce por su nombre («Contorno de la casa»); si hay varios, el más reciente
                val contour = interior.lastOrNull { it.name.trim().lowercase().startsWith(CONTOUR_PREFIX) }
                _state.update { s ->
                    s.copy(
                        rooms = interior.filter { it.id != contour?.id },
                        contour = contour,
                        selected = s.selected.filter { it != contour?.id },
                    )
                }
            }
        }
    }

    /**
     * Compara el contorno con la suma de los ambientes elegidos (o de todos, si no se eligió ninguno).
     * Null si todavía no se midió el contorno.
     */
    fun comparison(): ContourComparison? {
        val s = _state.value
        val contour = s.contour ?: return null
        val rooms = if (s.selected.isEmpty()) s.rooms else s.selected.mapNotNull { s.room(it) }
        val sum = rooms.sumOf { it.areaM2 }
        val diff = contour.areaM2 - sum
        return ContourComparison(
            contour.areaM2, contour.perimeterM, sum, diff, if (contour.areaM2 > 0) 100 * diff / contour.areaM2 else 0.0,
        )
    }

    // Cambiar ambientes o uniones borra la planta anterior: ya no corresponde
    private fun edit(change: (HouseUiState) -> HouseUiState) = _state.update { change(it).copy(result = null, message = null) }

    /**
     * Elegir o quitar un ambiente. Al elegirlo aparece en el panel a la derecha de los demás (sin encimarse);
     * al quitarlo se borran sus uniones.
     */
    fun toggleRoom(id: Long) = edit { s ->
        if (id in s.selected) {
            s.copy(
                selected = s.selected - id, poses = s.poses - id, active = s.active.takeIf { it != id },
                joins = s.joins.filter { it.a != id && it.b != id },
            )
        } else {
            val room = s.room(id) ?: return@edit s
            val right = s.selected.mapNotNull { other ->
                val r = s.room(other) ?: return@mapNotNull null
                HouseLayout.world(r.vertices, s.poses[other] ?: RoomPose()).maxOf { it.x }
            }.maxOrNull()
            val start = HouseLayout.initialPoses(listOf(room.vertices)).single()
            val pose = if (right == null) start else start.copy(dx = start.dx + right + 1.0)
            s.copy(selected = s.selected + id, poses = s.poses + (id to pose), active = id)
        }
    }

    /** Arrastrar un ambiente en el panel ([dxM], [dyM] en metros). */
    fun moveRoom(id: Long, dxM: Double, dyM: Double) = edit { s ->
        val p = s.poses[id] ?: return@edit s
        s.copy(poses = s.poses + (id to p.copy(dx = p.dx + dxM, dy = p.dy + dyM)), active = id)
    }

    /** Girar un ambiente alrededor de su centro. */
    fun rotateRoom(id: Long, degrees: Double) {
        rotateOnly(id, degrees)
        pruneJoins() // girado, ya no toca al ambiente al que estaba pegado
    }

    private fun rotateOnly(id: Long, degrees: Double) = edit { s ->
        val p = s.poses[id] ?: return@edit s
        s.copy(poses = s.poses + (id to p.copy(rotRad = p.rotRad + Math.toRadians(degrees))), active = id)
    }

    /** Deja el ambiente derecho (su muro más largo horizontal o vertical). */
    fun straightenRoom(id: Long) {
        straightenOnly(id)
        pruneJoins()
    }

    private fun straightenOnly(id: Long) = edit { s ->
        val p = s.poses[id] ?: return@edit s
        val room = s.room(id) ?: return@edit s
        s.copy(poses = s.poses + (id to HouseLayout.straighten(room.vertices, p)), active = id)
    }

    fun setThickness(text: String) = edit { it.copy(thickness = text) }

    /**
     * Soltar un ambiente: si quedó cerca del muro de otro, el imán lo pega (paralelo, al espesor del muro) y se
     * guarda la unión en ese punto, reemplazando la que hubiera entre los mismos dos ambientes.
     */
    fun dropRoom(id: Long) {
        val s = _state.value
        val ids = s.selected.filter { s.poses[it] != null && s.room(it) != null }
        val moving = ids.indexOf(id)
        if (moving < 0) return
        val t = number(s.thickness)?.takeIf { it > 0 && it <= MAX_THICKNESS_M } ?: 0.15
        val snap = HouseLayout.snap(moving, ids.map { s.room(it)!!.vertices }, ids.map { s.poses.getValue(it) }, t)
        if (snap == null) {
            // Soltado lejos: las uniones que ya no se tocan se borran
            pruneJoins()
            return
        }
        val other = ids[snap.aIndex]
        val join = JoinInput(
            a = other, b = id, thickness = s.thickness, aPoint = snap.aPointLocal, bPoint = snap.bPointLocal,
            aWall = snap.aWall, bWall = snap.bWall,
        )
        edit {
            it.copy(
                poses = it.poses + (id to snap.pose),
                joins = it.joins.filterNot { j -> setOf(j.a, j.b) == setOf(other, id) } + join,
                active = id,
            ).copy(message = null)
        }
        val removed = pruneJoins()
        _state.update {
            it.copy(message = "«${s.room(id)?.name}» unido a «${s.room(other)?.name}»" + if (removed.isEmpty()) "" else ". " + removedText(removed))
        }
    }

    /**
     * Borra las uniones hechas con imán cuyos ambientes ya no se tocan (se separaron o se giraron): así la planta
     * nunca se arma con uniones viejas. Devuelve los pares borrados.
     */
    private fun pruneJoins(): List<Pair<String, String>> {
        val s = _state.value
        val stale = s.joins.filter { j ->
            val a = s.room(j.a)
            val b = s.room(j.b)
            val pa = s.poses[j.a]
            val pb = s.poses[j.b]
            val ap = j.aPoint
            val bp = j.bPoint
            if (a == null || b == null || pa == null || pb == null || ap == null || bp == null || j.door != null) return@filter false
            val t = number(j.thickness) ?: return@filter false
            val gap = com.metrik.app.core.geometry.Geometry.euclidean(
                HouseLayout.toWorld(ap, a.vertices, pa), HouseLayout.toWorld(bp, b.vertices, pb),
            )
            kotlin.math.abs(gap - t) > JOIN_TOLERANCE_M
        }
        if (stale.isEmpty()) return emptyList()
        _state.update { it.copy(joins = it.joins - stale.toSet(), result = null) }
        val removed = stale.map { (s.room(it.a)?.name ?: "?") to (s.room(it.b)?.name ?: "?") }
        _state.update { it.copy(message = removedText(removed)) }
        return removed
    }

    private fun removedText(removed: List<Pair<String, String>>) =
        "Se quitó la unión " + removed.joinToString { "«${it.first}» ↔ «${it.second}»" } + " porque ya no se tocan"

    /**
     * La planta del servidor viene en la orientación original del ambiente base (el primero elegido): se gira y
     * ubica como ese ambiente en el panel, para que se vea igual que lo armado (enderezado), no en diagonal.
     */
    fun alignedResult(): List<PlacedRoomDto>? {
        val s = _state.value
        val r = s.result ?: return null
        val base = s.selected.firstOrNull() ?: return r.rooms
        val local = s.room(base)?.vertices ?: return r.rooms
        val pose = s.poses[base] ?: return r.rooms
        return r.rooms.map { room ->
            room.copy(vertices = room.vertices.map { v ->
                val w = HouseLayout.toWorld(Point(v[0], v[1]), local, pose)
                listOf(w.x, w.y)
            })
        }
    }

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

    /** true si hay ambientes que empezaron en una puerta marcada (se pueden unir solos). */
    val canAutoJoin: Boolean get() = _state.value.rooms.any { it.capture?.startDoor != null }

    /**
     * Une los ambientes por las puertas: cada ambiente que empezó en la puerta «X» se une al ambiente donde
     * se marcó «X» (el pasillo). La base es el ambiente con más puertas usadas.
     */
    fun autoJoin() {
        val s = _state.value
        // El nombre de la puerta se escribe a mano: se compara sin mayúsculas ni espacios de más
        val owners = s.rooms.flatMap { r -> r.capture?.doors.orEmpty().map { d -> d.name.trim().lowercase() to (r to d) } }
            .distinctBy { it.first }.toMap()
        val joins = s.rooms.mapNotNull { r ->
            val name = r.capture?.startDoor ?: return@mapNotNull null
            val (owner, door) = owners[name.trim().lowercase()] ?: return@mapNotNull null
            if (owner.id == r.id) return@mapNotNull null
            val start = r.points.firstOrNull() ?: r.vertices.first()
            JoinInput(a = owner.id, b = r.id, aPoint = Point(door.x, door.y), bPoint = start, door = door.name)
        }
        if (joins.isEmpty()) {
            _state.update {
                it.copy(error = "Ningún ambiente empezó en una puerta marcada: marca las puertas en el pasillo y empieza cada ambiente en la suya")
            }
            return
        }
        val base = joins.groupingBy { it.a }.eachCount().maxByOrNull { it.value }!!.key
        val order = (listOf(base) + joins.flatMap { listOf(it.a, it.b) }).distinct()
        edit { it.copy(selected = order, joins = joins, error = null) }
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
            FloorJoinDto(
                remote.getValue(j.a), j.aWall.takeIf { j.aPoint == null }, remote.getValue(j.b), j.bWall.takeIf { j.bPoint == null },
                requireNotNull(number(j.thickness)), j.aPoint?.let { listOf(it.x, it.y) }, j.bPoint?.let { listOf(it.x, it.y) },
            )
        },
    )

    companion object {
        const val MAX_THICKNESS_M = 0.50
        /** Una unión con imán sigue vigente si sus dos puntos siguen al espesor del muro (± 10 cm). */
        const val JOIN_TOLERANCE_M = 0.10

        fun dxfFileName(projectId: Int) = "metrik_vivienda_$projectId.dxf"

        /** Nombre con el que se guarda el contorno de la casa (la pantalla de medición lo trae escrito). */
        const val CONTOUR_NAME = "Contorno de la casa"
        private const val CONTOUR_PREFIX = "contorno"

        /** Acepta coma o punto decimal ("0,15" o "0.15"). */
        private fun number(text: String): Double? = text.trim().replace(',', '.').toDoubleOrNull()?.takeIf { it.isFinite() }
    }
}

@Composable
fun HouseScreen(vm: HouseViewModel, onBack: () -> Unit, onMeasureContour: () -> Unit = {}) {
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
            Text("Toca los ambientes que forman la vivienda: aparecen en el panel de abajo.", style = MaterialTheme.typography.bodySmall)
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

            ContourSection(s, vm.comparison(), onMeasureContour)

            Text("Arma la vivienda", style = MaterialTheme.typography.titleSmall)
            Text(
                "Arrastra cada ambiente con el dedo hasta pegarlo al otro: cuando un muro queda cerca, se pega solo (imán). " +
                    "Toca un ambiente y usa los botones para girarlo.",
                style = MaterialTheme.typography.bodySmall,
            )
            if (s.selected.isNotEmpty()) InteractivePanel(s, vm)
            if (vm.canAutoJoin) {
                OutlinedButton(onClick = vm::autoJoin, enabled = !s.busy, modifier = Modifier.fillMaxWidth().testTag("house_auto")) {
                    Text("Unir automáticamente (por las puertas)")
                }
            }

            Button(
                onClick = vm::calculate, enabled = !s.busy && s.selected.isNotEmpty(),
                modifier = Modifier.fillMaxWidth().testTag("house_calculate"),
            ) { Text("Armar la planta") }
            // Orientada como el panel (el servidor la devuelve en la orientación original de la medición)
            s.result?.let { HouseResult(it.copy(rooms = vm.alignedResult() ?: it.rooms)) }
            OutlinedButton(
                onClick = vm::exportDxf, enabled = !s.busy && s.selected.isNotEmpty(),
                modifier = Modifier.fillMaxWidth().testTag("house_dxf"),
            ) { Text("Descargar DXF de la vivienda") }
        }
    }
}

/** Contorno de toda la casa: se mide caminando su borde y se compara con la suma de los ambientes. */
@Composable
private fun ContourSection(s: HouseUiState, c: ContourComparison?, onMeasure: () -> Unit) {
    Text("Contorno de la casa", style = MaterialTheme.typography.titleSmall)
    if (c == null) {
        Text(
            "Camina el borde de toda la casa para comparar su área con la suma de los ambientes medidos.",
            style = MaterialTheme.typography.bodySmall,
        )
    } else {
        val chosen = if (s.selected.isEmpty()) "todos los ambientes" else "los ambientes elegidos"
        StatRow("Contorno", String.format(Locale.US, "%.2f m² · %.2f m", c.contourAreaM2, c.contourPerimeterM))
        StatRow("Suma de $chosen", String.format(Locale.US, "%.2f m²", c.roomsAreaM2))
        StatRow(
            "Diferencia", String.format(Locale.US, "%.2f m² (%.1f %%)", c.differenceM2, c.differencePct),
            Modifier.testTag("house_contour_diff"),
        )
        Text(
            "La diferencia corresponde a los muros y al error de medición. Si es negativa o muy grande, revisa las medidas.",
            style = MaterialTheme.typography.bodySmall,
        )
    }
    OutlinedButton(onClick = onMeasure, modifier = Modifier.fillMaxWidth().testTag("house_measure_contour")) {
        Text(if (c == null) "Medir contorno de la casa" else "Volver a medir el contorno")
    }
}

/** Vista del panel: qué rectángulo del plano (en metros) se muestra. Se congela mientras se arrastra. */
private data class PanelView(val minX: Double, val minY: Double, val spanM: Double)

private fun panelView(s: HouseUiState): PanelView {
    val pts = s.selected.flatMap { id -> s.room(id)?.let { HouseLayout.world(it.vertices, s.poses[id] ?: RoomPose()) }.orEmpty() }
    if (pts.isEmpty()) return PanelView(0.0, 0.0, 10.0)
    val minX = pts.minOf { it.x }
    val maxX = pts.maxOf { it.x }
    val minY = pts.minOf { it.y }
    val maxY = pts.maxOf { it.y }
    val span = maxOf(maxX - minX, maxY - minY, 4.0) * 1.35 // margen para poder mover los ambientes
    return PanelView((minX + maxX) / 2 - span / 2, (minY + maxY) / 2 - span / 2, span)
}

/**
 * Panel interactivo (CU-08): se arrastran los ambientes con el dedo y, al soltarlos cerca del muro de otro, se
 * pegan con imán. Los botones giran el ambiente tocado; la lista muestra las uniones hechas.
 */
@Composable
private fun InteractivePanel(s: HouseUiState, vm: HouseViewModel) {
    val state by rememberUpdatedState(s)
    var frozen by remember { mutableStateOf<PanelView?>(null) }
    var dragging by remember { mutableStateOf<Long?>(null) }
    val view = frozen ?: panelView(s)
    val measurer = rememberTextMeasurer()
    val labelColor = MaterialTheme.colorScheme.onSurface
    Card(Modifier.fillMaxWidth()) {
        Canvas(
            Modifier.fillMaxWidth().height(340.dp).testTag("house_panel").pointerInput(Unit) {
                detectDragGestures(
                    onDragStart = { at ->
                        val v = panelView(state)
                        frozen = v
                        val k = size.width / v.spanM
                        val p = Point(v.minX + at.x / k, v.minY + (size.height - at.y) / k)
                        // El ambiente bajo el dedo (el último dibujado queda encima)
                        dragging = state.selected.lastOrNull { id ->
                            state.room(id)?.let { HouseLayout.contains(HouseLayout.world(it.vertices, state.poses[id] ?: RoomPose()), p) } == true
                        }
                    },
                    onDrag = { change, amount ->
                        val id = dragging ?: return@detectDragGestures
                        change.consume()
                        val k = size.width / (frozen?.spanM ?: 10.0)
                        vm.moveRoom(id, amount.x / k, -amount.y / k)
                    },
                    onDragEnd = {
                        dragging?.let(vm::dropRoom)
                        dragging = null
                        frozen = null
                    },
                    onDragCancel = { dragging = null; frozen = null },
                )
            },
        ) {
            val k = (size.width / view.spanM).toFloat()
            fun map(p: Point) = Offset(((p.x - view.minX) * k).toFloat(), size.height - ((p.y - view.minY) * k).toFloat())
            s.selected.forEach { id ->
                val room = s.room(id) ?: return@forEach
                val w = HouseLayout.world(room.vertices, s.poses[id] ?: RoomPose())
                val path = Path().apply {
                    w.forEachIndexed { i, p -> val o = map(p); if (i == 0) moveTo(o.x, o.y) else lineTo(o.x, o.y) }
                    close()
                }
                val active = id == s.active
                drawPath(path, if (active) MetrikColors.Amber.copy(alpha = 0.25f) else MetrikColors.Petrol.copy(alpha = 0.10f))
                drawPath(path, if (active) MetrikColors.Amber else MetrikColors.Petrol, style = Stroke(if (active) 5f else 3f))
                val c = HouseLayout.center(w)
                val label = measurer.measure(room.name, TextStyle(fontSize = 12.sp, color = labelColor))
                drawText(label, topLeft = map(c) - Offset(label.size.width / 2f, label.size.height / 2f))
            }
            // Uniones hechas: punto verde donde se pegaron
            s.joins.forEach { j ->
                val a = s.room(j.a) ?: return@forEach
                val pa = j.aPoint ?: return@forEach
                drawCircle(MetrikColors.Ok, radius = 9f, center = map(HouseLayout.toWorld(pa, a.vertices, s.poses[j.a] ?: RoomPose())))
            }
        }
    }
    val active = s.active?.takeIf { it in s.selected }
    Text(
        active?.let { "Girar «${s.room(it)?.name}»:" } ?: "Toca un ambiente para girarlo",
        style = MaterialTheme.typography.bodySmall,
    )
    // Los botones pasan a la fila siguiente si no caben (pantallas angostas)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        listOf(90.0 to "↺ 90°", -90.0 to "↻ 90°", 15.0 to "↺ 15°", -15.0 to "↻ 15°").forEach { (deg, label) ->
            OutlinedButton(onClick = { active?.let { vm.rotateRoom(it, deg) } }, enabled = active != null) {
                Text(label, maxLines = 1)
            }
        }
        OutlinedButton(
            onClick = { active?.let(vm::straightenRoom) }, enabled = active != null,
            modifier = Modifier.testTag("house_straighten"),
        ) { Text("Enderezar", maxLines = 1) }
    }
    OutlinedTextField(
        s.thickness, vm::setThickness, Modifier.fillMaxWidth(), label = { Text("Espesor del muro entre ambientes (m)") },
        supportingText = { Text("0,15 tabique · 0,25 portante") }, singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
    )
    if (s.joins.isEmpty()) {
        Text("Todavía no hay ambientes unidos.", style = MaterialTheme.typography.bodySmall)
    } else {
        Text("Unidos:", style = MaterialTheme.typography.bodySmall)
        s.joins.forEachIndexed { i, j ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("${s.room(j.a)?.name} ↔ ${s.room(j.b)?.name}", Modifier.weight(1f).padding(top = 12.dp))
                TextButton(onClick = { vm.removeJoin(i) }) { Text("Quitar") }
            }
        }
    }
}

@Composable
private fun JoinCard(i: Int, j: JoinInput, s: HouseUiState, vm: HouseViewModel) {
    val chosen = s.selected.mapNotNull { s.room(it) }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            val porPuerta = j.door != null && j.aPoint != null && j.bPoint != null
            Text(
                if (porPuerta) "Unión ${i + 1}: por la puerta «${j.door}»" else "Unión ${i + 1}",
                style = MaterialTheme.typography.bodyMedium,
            )
            if (porPuerta) {
                Text(
                    "${s.room(j.a)?.name} ↔ ${s.room(j.b)?.name}: los muros se eligen solos. Toca un muro abajo para cambiarlo a mano.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                WallPicker("Ambiente A", j.a, if (j.aPoint != null) -1 else j.aWall, chosen, Modifier.weight(1f)) { id, wall ->
                    vm.updateJoin(i, j.copy(a = id, aWall = wall, aPoint = null, door = null))
                }
                WallPicker("Ambiente B", j.b, if (j.bPoint != null) -1 else j.bWall, chosen, Modifier.weight(1f)) { id, wall ->
                    vm.updateJoin(i, j.copy(b = id, bWall = wall, bPoint = null, door = null))
                }
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
