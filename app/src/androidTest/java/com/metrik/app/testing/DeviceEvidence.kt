package com.metrik.app.testing

import android.graphics.Bitmap
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.rules.TestWatcher
import org.junit.runner.Description
import java.io.File
import java.io.FileOutputStream

/** Metadatos de evidencia de una prueba instrumentada (docs/04-plan-de-pruebas.md §3). */
@Target(AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.RUNTIME)
annotation class Evidencia(
    val id: String,
    val nombre: String,
    val requisito: String = "",
    val objetivo: String = "",
    val precondiciones: String = "",
    val entrada: String = "",
    val esperado: String = "",
)

/**
 * Al terminar cada prueba guarda en el celular, en
 * /sdcard/Android/data/com.metrik.app/files/evidencias/:
 *  - <ID>.png  captura real de la pantalla (si [screenshot] es true)
 *  - <ID>.json metadatos, resultado obtenido y estado
 * scripts/generar_evidencias_android.py los descarga con adb.
 */
class DeviceEvidenceRule(private val screenshot: Boolean = true) : TestWatcher() {
    var obtenido: String = "-"
    private var meta: Evidencia? = null
    private var estado = "APROBADO"

    private val dir: File by lazy {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        File(ctx.getExternalFilesDir(null), "evidencias").apply { mkdirs() }
    }

    override fun starting(description: Description) {
        meta = description.getAnnotation(Evidencia::class.java)
        obtenido = "-"
        estado = "APROBADO"
    }

    override fun failed(e: Throwable, description: Description) {
        estado = "FALLIDO"
        obtenido = "$obtenido | ${e.javaClass.simpleName}: ${e.message?.take(300)}"
    }

    /** Captura la pantalla en este momento (se llama al final de las pruebas de UI). */
    fun capture() {
        val m = meta ?: return
        if (!screenshot) return
        val bmp: Bitmap = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot() ?: return
        FileOutputStream(File(dir, "${m.id}.png")).use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    override fun finished(description: Description) {
        val m = meta ?: return
        if (screenshot && !File(dir, "${m.id}.png").exists()) runCatching { capture() }
        val fields = mapOf(
            "id" to m.id, "nombre" to m.nombre, "requisito" to m.requisito, "objetivo" to m.objetivo,
            "precondiciones" to m.precondiciones, "entrada" to m.entrada, "esperado" to m.esperado,
            "obtenido" to obtenido, "estado" to estado, "clase" to description.className, "metodo" to description.methodName,
            "captura" to if (screenshot) "${m.id}.png" else "",
        )
        File(dir, "${m.id}.json").writeText(fields.entries.joinToString(",", "{", "}") { (k, v) -> "\"$k\":\"${esc(v)}\"" })
        println("[${m.id}] ${m.nombre} → $estado | Obtenido: $obtenido")
    }

    private fun esc(s: String) = s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n")
}
