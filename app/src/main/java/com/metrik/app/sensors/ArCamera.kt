package com.metrik.app.sensors

import android.app.Activity
import android.content.Context
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.os.Handler
import android.os.Looper
import android.view.Surface
import android.view.WindowManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.google.ar.core.ArCoreApk
import com.google.ar.core.Config
import com.google.ar.core.Coordinates2d
import com.google.ar.core.Frame
import com.google.ar.core.Plane
import com.google.ar.core.Session
import com.google.ar.core.TrackingFailureReason
import com.google.ar.core.TrackingState
import com.metrik.app.core.geometry.Point
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

/** Disponibilidad de ARCore en este celular (RNF-05, EX-03). */
object ArSupport {
    fun isSupported(context: Context): Boolean = runCatching {
        ArCoreApk.getInstance().checkAvailability(context).isSupported
    }.getOrDefault(false)

    /**
     * Como [isSupported], pero espera la respuesta: la primera consulta suele devolver
     * UNKNOWN_CHECKING (transitorio) y antes se tomaba como "sin ARCore" → modo pasos sin cámara.
     */
    suspend fun awaitSupported(context: Context): Boolean {
        repeat(25) { // hasta ~5 s
            val availability = runCatching { ArCoreApk.getInstance().checkAvailability(context) }.getOrNull() ?: return false
            if (!availability.isTransient) return availability.isSupported
            kotlinx.coroutines.delay(200)
        }
        return false
    }

    /**
     * Crea la sesión de ARCore; si falta "Google Play Services for AR" lo solicita.
     * Devuelve null mientras se instala o si no es posible (con el motivo en [onError]).
     * [userRequestedInstall] debe ser false al reintentar después de volver de la instalación.
     */
    fun createSession(
        activity: Activity,
        userRequestedInstall: Boolean = true,
        onInstallRequested: () -> Unit = {},
        onError: (String) -> Unit,
    ): Session? = try {
        when (ArCoreApk.getInstance().requestInstall(activity, userRequestedInstall)) {
            ArCoreApk.InstallStatus.INSTALL_REQUESTED -> { onInstallRequested(); null }
            else -> {
                val session = Session(activity)
                try {
                    session.configure(
                        Config(session).apply {
                            // Piso y paredes: ARCore los usa para anclar mejor el mapa y la app muestra
                            // si ya los reconoció (antes estaba DISABLED y nunca se detectaban).
                            planeFindingMode = Config.PlaneFindingMode.HORIZONTAL_AND_VERTICAL
                            updateMode = Config.UpdateMode.LATEST_CAMERA_IMAGE
                            // Autoenfoque: con enfoque fijo el piso cercano (1–2 m) sale borroso y ARCore
                            // encuentra pocos puntos en él. La quietud ya la resuelve el odómetro (ZUPT).
                            focusMode = Config.FocusMode.AUTO
                            lightEstimationMode = Config.LightEstimationMode.DISABLED
                        },
                    )
                } catch (e: Exception) {
                    session.close() // si no, la sesión huérfana retiene la cámara y el reintento falla
                    throw e
                }
                session
            }
        }
    } catch (e: Exception) {
        onError("No se pudo iniciar ARCore: ${e.message ?: e.javaClass.simpleName}")
        null
    }
}

/** Dibuja la imagen de la cámara de fondo (textura OES externa de ARCore). */
private class CameraBackgroundRenderer {
    var textureId = -1; private set
    private var program = 0
    private var aPosition = 0
    private var aTexCoord = 0
    private val quad: FloatBuffer = floatBuffer(floatArrayOf(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f))
    private val texCoords: FloatBuffer = floatBuffer(FloatArray(8))

    fun create() {
        val tex = IntArray(1)
        GLES20.glGenTextures(1, tex, 0)
        textureId = tex[0]
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureId)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        program = GLES20.glCreateProgram().also { p ->
            GLES20.glAttachShader(p, shader(GLES20.GL_VERTEX_SHADER, VERTEX))
            GLES20.glAttachShader(p, shader(GLES20.GL_FRAGMENT_SHADER, FRAGMENT))
            GLES20.glLinkProgram(p)
        }
        aPosition = GLES20.glGetAttribLocation(program, "a_Position")
        aTexCoord = GLES20.glGetAttribLocation(program, "a_TexCoord")
    }

    fun draw(frame: Frame) {
        if (frame.hasDisplayGeometryChanged()) {
            quad.position(0); texCoords.position(0)
            frame.transformCoordinates2d(
                Coordinates2d.OPENGL_NORMALIZED_DEVICE_COORDINATES, quad,
                Coordinates2d.TEXTURE_NORMALIZED, texCoords,
            )
        }
        if (frame.timestamp == 0L) return
        quad.position(0); texCoords.position(0)
        GLES20.glDisable(GLES20.GL_DEPTH_TEST)
        GLES20.glDepthMask(false)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureId)
        GLES20.glUseProgram(program)
        GLES20.glVertexAttribPointer(aPosition, 2, GLES20.GL_FLOAT, false, 0, quad)
        GLES20.glVertexAttribPointer(aTexCoord, 2, GLES20.GL_FLOAT, false, 0, texCoords)
        GLES20.glEnableVertexAttribArray(aPosition)
        GLES20.glEnableVertexAttribArray(aTexCoord)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        GLES20.glDisableVertexAttribArray(aPosition)
        GLES20.glDisableVertexAttribArray(aTexCoord)
        GLES20.glDepthMask(true)
        GLES20.glEnable(GLES20.GL_DEPTH_TEST)
    }

    private fun shader(type: Int, code: String) = GLES20.glCreateShader(type).also {
        GLES20.glShaderSource(it, code)
        GLES20.glCompileShader(it)
    }

    companion object {
        private const val VERTEX = """
            attribute vec4 a_Position;
            attribute vec2 a_TexCoord;
            varying vec2 v_TexCoord;
            void main() { gl_Position = a_Position; v_TexCoord = a_TexCoord; }
        """
        private const val FRAGMENT = """
            #extension GL_OES_EGL_image_external : require
            precision mediump float;
            varying vec2 v_TexCoord;
            uniform samplerExternalOES sTexture;
            void main() { gl_FragColor = texture2D(sTexture, v_TexCoord); }
        """

        fun floatBuffer(data: FloatArray): FloatBuffer =
            ByteBuffer.allocateDirect(data.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer().apply {
                put(data); position(0)
            }
    }
}

/**
 * Renderizador (docs/03-algoritmos.md §1): en cada frame lanza un rayo desde la mira (centro de la
 * pantalla) hasta el piso; ese punto es la "rueda de medir". Apuntando al borde piso–pared, el trazo
 * es el perímetro real del ambiente (antes se medía el camino del celular, que va 30–50 cm
 * adentro de las paredes y achicaba el plano).
 * La posición del celular se envía aparte solo para detectar si la persona está quieta.
 */
private class FloorOdometryRenderer(
    private val session: Session,
    private val displayRotation: () -> Int,
    private val onSample: (OdometrySample) -> Unit,
    private val onStatus: (ArStatus) -> Unit,
) : GLSurfaceView.Renderer {
    private val background = CameraBackgroundRenderer()
    private var gate = TrackingGate()
    private var frameCount = 0L
    private val floorEstimator = FloorEstimator()
    private var walls = 0
    private var lastStatus: ArStatus? = null
    private var updateErrorReported = false
    private var messages = MessageDebouncer()
    private var smoothed: Point? = null
    private var floorSearchSinceNs: Long? = null
    private var trackingLabel = "iniciando"
    private var lastPointCount = 0
    private val camPos = DoubleArray(4) // x, y, z del celular y componente vertical de la mira (diagnóstico)

    /**
     * Lo pide el hilo principal al pausar: al volver hay que calibrar otra vez (si no, la compuerta
     * conservaba el tiempo anterior y dejaba pasar poses inestables) y volver a avisar el estado.
     */
    @Volatile var resetRequested = false

    private fun resetIfRequested() {
        if (!resetRequested) return
        resetRequested = false
        gate = TrackingGate()
        messages = MessageDebouncer()
        lastStatus = null
        updateErrorReported = false
        smoothed = null
        floorSearchSinceNs = null
    }

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        GLES20.glClearColor(0f, 0f, 0f, 1f)
        background.create()
        session.setCameraTextureName(background.textureId)
    }

    override fun onSurfaceChanged(gl: GL10?, w: Int, h: Int) {
        GLES20.glViewport(0, 0, w, h)
        session.setDisplayGeometry(displayRotation(), w, h)
    }

    override fun onDrawFrame(gl: GL10?) {
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT or GLES20.GL_DEPTH_BUFFER_BIT)
        resetIfRequested()
        val frame = try {
            session.update().also { updateErrorReported = false }
        } catch (e: Exception) {
            // Antes se ignoraba en silencio (pantalla negra sin explicación)
            if (!updateErrorReported) {
                updateErrorReported = true
                report(ArStatus(ready = false, message = "La cámara no responde (${e.javaClass.simpleName}). Cierra otras apps que usen la cámara y vuelve a entrar."))
            }
            return
        }
        background.draw(frame)
        val camera = frame.camera
        val now = System.nanoTime()
        val tracking = camera.trackingState == TrackingState.TRACKING
        trackingLabel = if (tracking) "OK" else camera.trackingFailureReason.name.lowercase()
        val pose = camera.pose
        camPos[0] = pose.tx().toDouble(); camPos[1] = pose.ty().toDouble(); camPos[2] = pose.tz().toDouble()
        camPos[3] = -pose.zAxis[1].toDouble() // componente vertical del rayo de la mira (<0 = hacia abajo)
        if (tracking && frameCount++ % 10 == 0L) updateFloor(frame, pose.ty().toDouble())
        val stable = gate.onFrame(tracking, now)
        if (!tracking) {
            smoothed = null
            emit(null, null, now, trackingMessage(camera.trackingFailureReason))
            return
        }
        if (!stable) {
            smoothed = null
            // ARCore necesita VER la escena desde varios puntos: con el celular quieto nunca termina
            // (antes el mensaje pedía quietud y la app se quedaba trabada aquí).
            emit(null, null, now, "Reconociendo el ambiente… desliza el celular despacio de lado a lado")
            return
        }
        // Piso sin textura: si en unos segundos no hay plano ni puntos, se estima por la altura del celular
        val searchingSince = floorSearchSinceNs ?: now.also { floorSearchSinceNs = it }
        if (floorEstimator.floorY == null && now - searchingSince > FLOOR_FALLBACK_NS) floorEstimator.assume(pose.ty().toDouble())
        // Solo se mide con la mira sobre el piso. Una vez conocida, la altura del piso no se pierde,
        // así que un recorrido nunca mezcla la mira con otra fuente.
        val floorY = floorEstimator.floorY ?: run {
            emit(null, null, now, "Buscando el piso… apunta al piso a 1–2 m y desliza el celular de lado a lado")
            return
        }
        // La cámara mira por su eje −Z: el rayo de la mira es −zAxis desde la posición del celular
        val z = pose.zAxis
        val raw = FloorAim.intersect(
            pose.tx().toDouble(), pose.ty().toDouble(), pose.tz().toDouble(),
            -z[0].toDouble(), -z[1].toDouble(), -z[2].toDouble(), floorY,
        ) ?: run {
            smoothed = null
            emit(null, null, now, "Apunta la mira al borde entre el piso y la pared (a menos de 4 m)")
            return
        }
        // Filtro paso bajo (~0,15 s a 30 fps): quita el temblor de la mano antes del odómetro
        val p = smoothed?.let { it + (raw - it) * POSE_SMOOTHING } ?: raw
        smoothed = p
        emit(p, Point(pose.tx().toDouble(), -pose.tz().toDouble()), now, null)
    }

    private fun emit(position: Point?, device: Point?, now: Long, rawMessage: String?) {
        val message = messages.onMessage(rawMessage, now)
        onSample(OdometrySample(position, now, message, device))
        if (ArDiagnostics.recording) {
            ArDiagnostics.record(
                now, trackingLabel, lastPointCount, floorEstimator.source, floorEstimator.floorY,
                camPos[0], camPos[1], camPos[2], camPos[3], position?.x, position?.y, rawMessage,
            )
        }
        report(
            ArStatus(
                ready = position != null, message = message, floorDetected = floorEstimator.floorY != null,
                walls = walls, aiming = position != null, floorSource = floorEstimator.source,
                diagnostics = "Seguimiento: ${trackingLabel} · puntos: $lastPointCount · piso: ${floorEstimator.source}",
            ),
        )
    }

    /** Solo avisa a la UI cuando el estado cambia (no 60 veces por segundo). */
    private fun report(status: ArStatus) {
        if (status == lastStatus) return
        lastStatus = status
        onStatus(status)
    }

    /** Planos de ARCore + nube de puntos → altura del piso (ver [FloorEstimator]). */
    private fun updateFloor(frame: Frame, cameraY: Double) {
        val horizontal = mutableListOf<Pair<Double, Double>>()
        var w = 0
        for (plane in session.getAllTrackables(Plane::class.java)) {
            if (plane.trackingState != TrackingState.TRACKING || plane.subsumedBy != null) continue
            when (plane.type) {
                Plane.Type.HORIZONTAL_UPWARD_FACING ->
                    horizontal += plane.centerPose.ty().toDouble() to (plane.extentX * plane.extentZ).toDouble()
                Plane.Type.VERTICAL -> w++
                else -> Unit
            }
        }
        floorEstimator.onPlanes(horizontal, cameraY)
        walls = maxOf(walls, w)
        // Puntos 3D (x, y, z, confianza) que ARCore sigue en la imagen: existen aunque no haya "plano"
        runCatching {
            frame.acquirePointCloud().use { cloud ->
                val pts = cloud.points
                lastPointCount = pts.remaining() / 4
                while (pts.remaining() >= 4) {
                    pts.get() // x
                    val y = pts.get()
                    pts.get() // z
                    val confidence = pts.get()
                    // Confianza baja aceptada: en pisos lisos casi todos los puntos son de baja confianza;
                    // el histograma ya descarta los sueltos.
                    if (confidence >= 0.1f) floorEstimator.onPoint(y.toDouble(), cameraY)
                }
            }
        }
    }

    private companion object {
        const val POSE_SMOOTHING = 0.2
        const val FLOOR_FALLBACK_NS = 5_000_000_000L // 5 s buscando el piso sin éxito → piso estimado
    }

    private fun trackingMessage(reason: TrackingFailureReason) = when (reason) {
        TrackingFailureReason.INSUFFICIENT_LIGHT -> "Poca luz: enciende las luces"
        TrackingFailureReason.EXCESSIVE_MOTION -> "Muy rápido: mueve el celular más despacio"
        TrackingFailureReason.INSUFFICIENT_FEATURES -> "Pocas referencias: incluye en la imagen el borde entre piso y pared, o algún mueble"
        TrackingFailureReason.CAMERA_UNAVAILABLE -> "Cámara no disponible"
        else -> "Iniciando seguimiento… apunta al piso y desliza el celular despacio"
    }
}

/**
 * Estado de ARCore para la UI: [ready] = la pose ya es confiable y se puede iniciar el recorrido;
 * [floorDetected] = se conoce la altura del piso; [aiming] = la mira está sobre el piso (modo preciso).
 */
data class ArStatus(
    val ready: Boolean = false,
    val message: String? = null,
    val floorDetected: Boolean = false,
    val walls: Int = 0,
    val aiming: Boolean = false,
    /** "plano" | "puntos" | "estimado" | "buscando" */
    val floorSource: String = "buscando",
    /** Línea técnica para diagnosticar en el celular (seguimiento, puntos vistos, origen del piso). */
    val diagnostics: String = "",
)

/** Vista de cámara con ARCore que alimenta el odómetro. */
@Composable
fun ArCameraView(
    session: Session,
    onSample: (OdometrySample) -> Unit,
    onStatus: (ArStatus) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val currentOnStatus by rememberUpdatedState(onStatus)
    val main = remember { Handler(Looper.getMainLooper()) }
    val renderer = remember(session) {
        FloorOdometryRenderer(
            session, { displayRotation(context) }, onSample,
            onStatus = { st -> main.post { currentOnStatus(st) } }, // el renderizador corre en el hilo GL
        )
    }
    val view = remember(session) {
        GLSurfaceView(context).apply {
            preserveEGLContextOnPause = true
            setEGLContextClientVersion(2)
            setEGLConfigChooser(8, 8, 8, 8, 16, 0)
            setRenderer(renderer)
            renderMode = GLSurfaceView.RENDERMODE_CONTINUOUSLY
        }
    }
    fun pause() {
        view.onPause()
        session.pause()
        renderer.resetRequested = true
        main.removeCallbacksAndMessages(null) // descarta estados pendientes del hilo GL
        currentOnStatus(ArStatus()) // con la cámara pausada no se puede iniciar
    }
    fun resume() {
        try {
            session.resume()
            view.onResume()
        } catch (e: Exception) {
            // Antes: runCatching silencioso → pantalla negra sin explicación
            currentOnStatus(ArStatus(message = "No se pudo abrir la cámara: ${e.message ?: e.javaClass.simpleName}"))
        }
    }
    DisposableEffect(lifecycleOwner, session) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> resume()
                Lifecycle.Event.ON_PAUSE -> pause()
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        if (lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) resume()
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            pause() // detiene el hilo GL antes de que MeasureScreen cierre la sesión
        }
    }
    AndroidView(factory = { view }, modifier = modifier)
}

@Suppress("DEPRECATION")
private fun displayRotation(context: Context): Int =
    (context.getSystemService(Context.WINDOW_SERVICE) as WindowManager).defaultDisplay?.rotation ?: Surface.ROTATION_0
