package com.metrik.app.data.remote

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.HttpException
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import java.io.IOException
import java.util.concurrent.TimeUnit

val MetrikJson = Json { ignoreUnknownKeys = true; explicitNulls = false; encodeDefaults = true }

/**
 * Crea el cliente Retrofit. El token y la URL se leen en cada llamada, así un cambio en Ajustes
 * o un nuevo inicio de sesión se aplican sin reiniciar la app.
 */
class ApiClient(
    private val baseUrl: () -> String,
    private val token: () -> String?,
    private val logging: Boolean = false,
) {
    @Volatile private var cached: Pair<String, MetrikApi>? = null

    fun api(): MetrikApi {
        val url = baseUrl().let { if (it.endsWith("/")) it else "$it/" }
        cached?.let { (u, api) -> if (u == url) return api }
        val auth = Interceptor { chain ->
            val t = token()
            val req = if (t.isNullOrBlank()) chain.request()
            else chain.request().newBuilder().header("Authorization", "Bearer $t").build()
            chain.proceed(req)
        }
        val client = OkHttpClient.Builder()
            .addInterceptor(auth)
            .apply { if (logging) addInterceptor(HttpLoggingInterceptor().setLevel(HttpLoggingInterceptor.Level.BASIC)) }
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()
        val api = Retrofit.Builder()
            .baseUrl(url)
            .client(client)
            .addConverterFactory(MetrikJson.asConverterFactory("application/json".toMediaType()))
            .build()
            .create(MetrikApi::class.java)
        cached = url to api
        return api
    }
}

/** Convierte errores de red/HTTP en mensajes legibles para el usuario. */
fun Throwable.userMessage(): String = when (this) {
    is HttpException -> {
        val body = response()?.errorBody()?.string().orEmpty()
        val detail = runCatching {
            when (val d = MetrikJson.decodeFromString<ApiErrorDto>(body).detail) {
                is JsonPrimitive -> d.content
                is JsonArray -> (d.firstOrNull() as? JsonObject)?.get("msg")?.jsonPrimitive?.content
                else -> null
            }
        }.getOrNull()
        when (code()) {
            401 -> detail ?: "Sesión no válida: vuelve a iniciar sesión"
            404 -> "No encontrado"
            else -> detail ?: "Error del servidor (${code()})"
        }
    }
    is IOException -> "Sin conexión con el servidor. Revisa tu conexión a internet y que el backend esté encendido"
    else -> message ?: "Error inesperado"
}
