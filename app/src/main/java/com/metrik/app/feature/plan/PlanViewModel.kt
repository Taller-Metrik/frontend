package com.metrik.app.feature.plan

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.metrik.app.core.geometry.Geometry
import com.metrik.app.core.geometry.PolygonAnalysis
import com.metrik.app.core.geometry.Scale
import com.metrik.app.data.remote.MlAnalysisDto
import com.metrik.app.data.remote.userMessage
import com.metrik.app.data.repository.PlanRepository
import com.metrik.app.data.repository.SurveyRepository
import com.metrik.app.domain.LocalSurvey
import com.metrik.app.domain.SurveyMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.Normalizer

data class PlanUiState(
    val survey: LocalSurvey? = null,
    val analysis: PolygonAnalysis? = null,
    val scale: Int = 50,
    val busy: Boolean = false,
    val ml: MlAnalysisDto? = null,
    val pointA: Int = 0,
    val pointB: Int = 2,
    val message: String? = null,
    val error: String? = null,
)

/** Plano a escala, exportación DXF y análisis ML de un recorrido guardado (RF-04, RF-07, RF-10, RF-12, RF-15). */
class PlanViewModel(
    private val localId: Long,
    private val surveys: SurveyRepository,
    private val plans: PlanRepository,
    private val saveFile: suspend (fileName: String, bytes: ByteArray) -> String,
) : ViewModel() {
    private val _state = MutableStateFlow(PlanUiState())
    val state: StateFlow<PlanUiState> = _state

    init { load() }

    fun load() = viewModelScope.launch {
        var s = surveys.getLocal(localId) ?: run { _state.update { it.copy(error = "Recorrido no encontrado") }; return@launch }
        // Copias antiguas guardadas sin vértices: se reparan descargando el detalle del servidor.
        val rid = s.remoteId
        if (s.vertices.size < 3 && rid != null) {
            runCatching { surveys.downloadRemote(s.projectId, rid, s.id) }
                .onSuccess { s = surveys.getLocal(localId) ?: s }
                .onFailure { t -> _state.update { it.copy(error = "Este plano no tiene vértices y no se pudo descargar: ${t.userMessage()}") } }
        }
        val a = runCatching { Geometry.analyze(s.vertices) }.getOrNull()
        _state.update { it.copy(survey = s, analysis = a, scale = s.scaleDenominator, pointB = minOf(2, (a?.vertices?.size ?: 1) - 1)) }
    }

    fun setScale(d: Int) = _state.update { it.copy(scale = Scale.validate(d)) }

    fun setPoints(a: Int, b: Int) = _state.update { it.copy(pointA = a, pointB = b) }

    /** Medidas de cada muro en el papel (mm) según la escala elegida. */
    fun paperMm(): List<Double> {
        val s = _state.value
        return s.analysis?.wallLengthsM?.map { Scale.toPaperMm(it, s.scale) }.orEmpty()
    }

    fun sync() = runBusy { ensureSynced(); _state.update { it.copy(message = "Recorrido sincronizado con el servidor") } }

    fun exportDxf() = runBusy {
        val remoteId = ensureSynced()
        val s = _state.value
        val bytes = plans.exportDxf(remoteId, s.scale)
        val where = saveFile(dxfFileName(s.survey?.name ?: "plano", s.scale), bytes)
        _state.update { it.copy(message = "DXF guardado en $where") }
    }

    /** Informe técnico en PDF (CU-10): lo genera el servidor, así que primero se sincroniza el recorrido. */
    fun exportReport() = runBusy {
        val remoteId = ensureSynced()
        val bytes = plans.reportPdf(remoteId)
        val where = saveFile(reportFileName(_state.value.survey?.name ?: "plano"), bytes)
        _state.update { it.copy(message = "Informe técnico guardado en $where") }
    }

    fun analyzeMl() = runBusy {
        if (_state.value.survey?.mode != SurveyMode.INTERIOR) error("El análisis ML aplica a recorridos interiores")
        val remoteId = ensureSynced()
        _state.update { it.copy(ml = plans.ml(remoteId)) }
    }

    fun consumeMessage() = _state.update { it.copy(message = null) }

    fun rename(name: String) = runBusy {
        surveys.rename(localId, name)
        _state.update { it.copy(survey = surveys.getLocal(localId), message = "Nombre actualizado") }
    }

    private suspend fun ensureSynced(): Int {
        val current = _state.value.survey ?: error("Recorrido no cargado")
        val synced = if (current.synced) current else surveys.sync(current.id)
        _state.update { it.copy(survey = synced) }
        return requireNotNull(synced.remoteId)
    }

    private fun runBusy(block: suspend () -> Unit) {
        _state.update { it.copy(busy = true, error = null) }
        viewModelScope.launch {
            runCatching { block() }.onFailure { t -> _state.update { it.copy(error = t.userMessage()) } }
            _state.update { it.copy(busy = false) }
        }
    }

    companion object {
        fun dxfFileName(name: String, scale: Int) = "metrik_${slug(name)}_1-$scale.dxf"

        fun reportFileName(name: String) = "metrik_informe_${slug(name)}.pdf"

        /** Nombre seguro para archivo: sin tildes ni símbolos ("Aula Ñandú 2" → "aula_nandu_2"). */
        private fun slug(name: String): String {
            val ascii = Normalizer.normalize(name, Normalizer.Form.NFD).replace(Regex("\\p{M}"), "")
            return ascii.replace(Regex("[^A-Za-z0-9]+"), "_").trim('_').lowercase().ifBlank { "plano" }
        }
    }
}

/** Tipo MIME según la extensión del archivo que se guarda en Descargas. */
fun mimeTypeFor(fileName: String): String = when (fileName.substringAfterLast('.', "").lowercase()) {
    "pdf" -> "application/pdf"
    "csv" -> "text/csv"
    else -> "application/dxf"
}

/** Guarda un archivo (DXF o diagnóstico) en Descargas (Android 10+) o en la carpeta de la app (versiones anteriores). */
suspend fun saveToDownloads(
    context: Context,
    fileName: String,
    bytes: ByteArray,
    mimeType: String = "application/dxf",
): String = withContext(Dispatchers.IO) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, fileName)
            put(MediaStore.Downloads.MIME_TYPE, mimeType)
            put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/MetriK")
        }
        val uri = requireNotNull(context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values))
        context.contentResolver.openOutputStream(uri).use { requireNotNull(it).write(bytes) }
        "Descargas/MetriK/$fileName"
    } else {
        val dir = File(context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), "MetriK").apply { mkdirs() }
        File(dir, fileName).writeBytes(bytes)
        "${dir.absolutePath}/$fileName"
    }
}
