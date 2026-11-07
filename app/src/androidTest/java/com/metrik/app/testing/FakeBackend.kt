package com.metrik.app.testing

import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.RecordedRequest
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicInteger

/**
 * Backend simulado dentro del celular para las pruebas E2E: responde como la API MetriK
 * (/api/v1) y guarda proyectos y recorridos en memoria.
 */
class FakeBackend : Dispatcher() {
    private val projects = mutableListOf<JSONObject>()
    private val surveys = mutableListOf<JSONObject>()
    private val ids = AtomicInteger(1)
    val requests = mutableListOf<String>()

    private fun json(code: Int, body: String) =
        MockResponse.Builder().code(code).addHeader("Content-Type", "application/json").body(body).build()

    override fun dispatch(request: RecordedRequest): MockResponse {
        val path = request.url.encodedPath.removePrefix("/api/v1")
        val method = request.method
        requests += "$method $path"
        val body = request.body?.utf8().orEmpty()
        return when {
            method == "POST" && path == "/auth/login" ->
                if ("password=clave-segura-123" in body) json(200, """{"access_token":"tok-e2e","token_type":"bearer"}""")
                else json(401, """{"detail":"Correo o contraseña incorrectos"}""")
            method == "GET" && path == "/auth/me" ->
                json(200, """{"id":1,"email":"ana@metrik.pe","full_name":"Ana Medidora","is_active":true,"created_at":"2026-01-01T00:00:00Z"}""")
            method == "GET" && path == "/projects" -> json(200, JSONArray(projects).toString())
            method == "POST" && path == "/projects" -> {
                val p = JSONObject(body).put("id", ids.getAndIncrement()).put("created_at", "2026-01-01T00:00:00Z")
                projects += p
                json(201, p.toString())
            }
            method == "GET" && Regex("/projects/\\d+/surveys").matches(path) -> json(200, "[]")
            method == "POST" && Regex("/projects/\\d+/surveys").matches(path) -> {
                val req = JSONObject(body)
                val projectId = path.split("/")[2].toInt()
                val s = JSONObject()
                    .put("id", ids.getAndIncrement()).put("project_id", projectId).put("name", req.getString("name"))
                    .put("mode", req.getString("mode")).put("area_m2", 20.0).put("perimeter_m", 18.0)
                    .put("scale_denominator", req.optInt("scale_denominator", 50)).put("created_at", "2026-01-01T00:00:00Z")
                surveys += s
                json(201, s.toString())
            }
            method == "GET" && Regex("/surveys/\\d+/export/dxf").matches(path) ->
                MockResponse.Builder().code(200).addHeader("Content-Type", "application/dxf")
                    .body("0\nSECTION\n2\nENTITIES\n0\nLWPOLYLINE\n8\nMUROS\n0\nENDSEC\n0\nEOF\n").build()
            method == "GET" && Regex("/surveys/\\d+/ml").matches(path) ->
                json(200, """{"survey_id":1,"reliability_probability":0.91,"reliable":true,"recommendation":"Recorrido confiable: puede usar las medidas","walls":[{"wall_index":0,"measured_m":4.98,"corrected_m":5.01,"correction_m":0.03}],"corrected_perimeter_m":18.05,"corrector_model":"Regresión lineal","classifier_model":"Regresión logística","version":"e2e"}""")
            method == "PUT" && Regex("/surveys/\\d+/references").matches(path) ->
                json(200, """{"survey_id":1,"survey_name":"Aula","report":{"n":4,"mae_m":0.021,"rmse_m":0.025,"bias_m":-0.012,"std_m":0.02,"mean_rel_error_pct":0.47,"max_abs_error_m":0.03,"max_rel_error_pct":0.7,"area_error_pct":0.9,"meets_target":true,"target_pct":3.0,"walls":[{"wall_index":0,"measured_m":4.98,"real_m":5.0,"error_m":-0.02,"abs_error_m":0.02,"rel_error_pct":0.4,"readings_m":[5.0,5.0],"needs_extra_reading":false}]},"warnings":[]}""")
            else -> json(404, """{"detail":"No encontrado"}""")
        }
    }
}
