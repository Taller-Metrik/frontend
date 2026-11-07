package com.metrik.app.sensors

import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger

/**
 * Registro de diagnóstico de ARCore durante un recorrido: una línea CSV por frame. Se guarda en
 * Descargas/MetriK al finalizar para reproducir en pruebas lo que vio la cámara en el celular real.
 * Lo escribe el hilo GL y lo lee el hilo principal (colas concurrentes).
 */
object ArDiagnostics {
    const val HEADER =
        "t_ms,seguimiento,puntos,piso_origen,piso_y,cel_x,cel_y,cel_z,mira_dir_y,mira_x,mira_y,mensaje"
    private const val MAX_LINES = 30_000 // ~16 min a 30 fps

    @Volatile var recording = false
        private set
    private val lines = ConcurrentLinkedQueue<String>()
    private val count = AtomicInteger()
    @Volatile private var startNs = 0L

    fun start(nowNs: Long = System.nanoTime()) {
        lines.clear(); count.set(0); startNs = nowNs; recording = true
    }

    fun record(
        nowNs: Long, tracking: String, points: Int, floorSource: String, floorY: Double?,
        camX: Double, camY: Double, camZ: Double, dirY: Double, aimX: Double?, aimY: Double?, message: String?,
    ) {
        if (!recording || count.incrementAndGet() > MAX_LINES) return
        fun f(v: Double?) = v?.let { "%.4f".format(java.util.Locale.US, it) } ?: ""
        val msg = message?.replace(',', ';')?.replace('\n', ' ') ?: ""
        lines += "${(nowNs - startNs) / 1_000_000},$tracking,$points,$floorSource,${f(floorY)}," +
            "${f(camX)},${f(camY)},${f(camZ)},${f(dirY)},${f(aimX)},${f(aimY)},$msg"
    }

    /** Detiene el registro y devuelve el CSV completo (null si no se grabó nada). */
    fun stop(): String? {
        recording = false
        if (lines.isEmpty()) return null
        return HEADER + "\n" + lines.joinToString("\n")
    }
}
