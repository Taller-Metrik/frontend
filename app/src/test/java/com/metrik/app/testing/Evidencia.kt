package com.metrik.app.testing

import org.junit.rules.TestWatcher
import org.junit.runner.Description

/** Metadatos de evidencia de una prueba (ver docs/04-plan-de-pruebas.md §3). */
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
 * Regla JUnit: al terminar cada prueba imprime su resumen (aparece en la salida de Gradle y en el
 * XML de resultados) y una línea `EVIDENCIA_JSON` que usa scripts/generar_evidencias_android.py.
 */
class EvidenceRule : TestWatcher() {
    var obtenido: String = "-"
    private var meta: Evidencia? = null
    private var estado = "APROBADO"

    override fun starting(description: Description) {
        meta = description.getAnnotation(Evidencia::class.java)
        obtenido = "-"
        estado = "APROBADO"
    }

    override fun failed(e: Throwable, description: Description) {
        estado = "FALLIDO"
    }

    override fun finished(description: Description) {
        val m = meta ?: return
        println("[${m.id}] ${m.nombre}")
        println("  Requisito : ${m.requisito}")
        println("  Entrada   : ${m.entrada}")
        println("  Esperado  : ${m.esperado}")
        println("  Obtenido  : $obtenido")
        val campos = mapOf(
            "id" to m.id, "nombre" to m.nombre, "requisito" to m.requisito, "objetivo" to m.objetivo,
            "precondiciones" to m.precondiciones, "entrada" to m.entrada, "esperado" to m.esperado,
            "obtenido" to obtenido, "estado" to estado,
            "clase" to description.className, "metodo" to description.methodName,
        )
        println("EVIDENCIA_JSON " + campos.entries.joinToString(",", "{", "}") { (k, v) -> "\"$k\":\"${esc(v)}\"" })
    }

    private fun esc(s: String) = s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n")
}
