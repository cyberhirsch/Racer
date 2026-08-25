package dev.racer.app

import android.opengl.GLES30
import android.opengl.GLSurfaceView
import dev.racer.core.CarMesh
import dev.racer.core.Game
import dev.racer.core.Mat4
import dev.racer.core.Mesh
import dev.racer.core.Track
import dev.racer.core.TrackMesh
import dev.racer.core.Vec3
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicReference
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlin.math.PI

/**
 * OpenGL ES 3.0 renderer.
 *
 * All geometry comes from :core as plain float arrays, so this class only
 * uploads buffers and issues draws. The scene is a handful of draw calls: the
 * whole track is one, the car body one, plus one per wheel and per unpassed
 * checkpoint gate.
 */
class GlRenderer(private val game: Game) : GLSurfaceView.Renderer {

    /** Set from the UI thread when a new level is loaded. */
    private val pendingTrack = AtomicReference<Track?>(null)

    private var program = 0
    private var uMvp = 0
    private var uModel = 0
    private var uLightDir = 0
    private var uCameraPos = 0
    private var uFogColor = 0
    private var uFogRange = 0
    private var uAlpha = 0
    private var uTint = 0

    private var trackBuffers: GpuMesh? = null
    private var gateBuffers: List<GpuMesh> = emptyList()
    private var carBody: GpuMesh? = null
    private var wheels: List<Pair<GpuMesh, CarMesh.Wheel>> = emptyList()

    private var width = 1
    private var height = 1
    private var lastFrameNanos = 0L

    /**
     * Called on the GL thread at the start of every frame with the real elapsed
     * time, so the simulation advances exactly once per drawn frame and the
     * camera is computed from the same delta the physics just used.
     */
    var onFrame: ((Double) -> Unit)? = null

    fun setTrack(track: Track) { pendingTrack.set(track) }

    private class GpuMesh(val vao: Int, val vbo: Int, val ibo: Int, val indexCount: Int) {
        fun release() {
            GLES30.glDeleteVertexArrays(1, intArrayOf(vao), 0)
            GLES30.glDeleteBuffers(2, intArrayOf(vbo, ibo), 0)
        }
    }

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        GLES30.glClearColor(SKY[0], SKY[1], SKY[2], 1f)
        GLES30.glEnable(GLES30.GL_DEPTH_TEST)
        GLES30.glEnable(GLES30.GL_CULL_FACE)
        GLES30.glCullFace(GLES30.GL_BACK)

        program = buildProgram(VERTEX_SHADER, FRAGMENT_SHADER)
        uMvp = GLES30.glGetUniformLocation(program, "uMvp")
        uModel = GLES30.glGetUniformLocation(program, "uModel")
        uLightDir = GLES30.glGetUniformLocation(program, "uLightDir")
        uCameraPos = GLES30.glGetUniformLocation(program, "uCameraPos")
        uFogColor = GLES30.glGetUniformLocation(program, "uFogColor")
        uFogRange = GLES30.glGetUniformLocation(program, "uFogRange")
        uAlpha = GLES30.glGetUniformLocation(program, "uAlpha")
        uTint = GLES30.glGetUniformLocation(program, "uTint")

        // The car never changes, so build it once.
        val car = CarMesh.build()
        carBody = upload(car.body)
        wheels = car.wheels.map { upload(it.mesh) to it }

        // A surface can be recreated (app resumed, context lost); rebuild the
        // track next frame if we already have one.
        game.track?.let { pendingTrack.set(it) }
        lastFrameNanos = 0L
    }

    override fun onSurfaceChanged(gl: GL10?, w: Int, h: Int) {
        width = w.coerceAtLeast(1)
        height = h.coerceAtLeast(1)
        GLES30.glViewport(0, 0, width, height)
    }

    override fun onDrawFrame(gl: GL10?) {
        val now = System.nanoTime()
        val elapsed = if (lastFrameNanos == 0L) 1.0 / 60.0 else (now - lastFrameNanos) / 1e9
        lastFrameNanos = now
        val frameDelta = elapsed.coerceIn(1.0 / 240.0, 0.05)

        pendingTrack.getAndSet(null)?.let { rebuildTrack(it) }
        onFrame?.invoke(frameDelta)

        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT or GLES30.GL_DEPTH_BUFFER_BIT)

        val aspect = width.toFloat() / height
        val cam = game.camera(frameDelta, aspect)
        val projection = Mat4.perspective(cam.fovDegrees * PI.toFloat() / 180f, aspect, 0.4f, 1200f)
        // Roll the camera to cancel the phone's rotation, so the horizon stays
        // level for the person holding it. Rotate the up vector about the view
        // axis: the image turns the opposite way to the camera, which is
        // exactly the compensation wanted.
        val forward = (cam.target - cam.eye).normalized()
        val right = forward.cross(Vec3(0f, 1f, 0f)).normalized()
        val up = right.cross(forward).normalized()
        val rolled = up * kotlin.math.cos(cam.rollRadians) + right * kotlin.math.sin(cam.rollRadians)
        val view = Mat4.lookAt(cam.eye, cam.target, rolled)
        val viewProjection = projection * view

        GLES30.glUseProgram(program)
        GLES30.glUniform3f(uLightDir, LIGHT[0], LIGHT[1], LIGHT[2])
        GLES30.glUniform3f(uCameraPos, cam.eye.x, cam.eye.y, cam.eye.z)
        GLES30.glUniform3f(uFogColor, SKY[0], SKY[1], SKY[2])
        GLES30.glUniform2f(uFogRange, 140f, 620f)
        GLES30.glUniform1f(uAlpha, 1f)
        GLES30.glUniform3f(uTint, 1f, 1f, 1f)

        GLES30.glDisable(GLES30.GL_BLEND)
        trackBuffers?.let { draw(it, Mat4.identity(), viewProjection) }

        drawCar(viewProjection)

        // Gates last, blended, and only the ones still to be passed.
        GLES30.glEnable(GLES30.GL_BLEND)
        GLES30.glBlendFunc(GLES30.GL_SRC_ALPHA, GLES30.GL_ONE_MINUS_SRC_ALPHA)
        GLES30.glDepthMask(false)
        GLES30.glDisable(GLES30.GL_CULL_FACE)
        GLES30.glUniform1f(uAlpha, 0.32f)
        gateBuffers.forEachIndexed { i, mesh ->
            if (game.gateVisible(i)) draw(mesh, Mat4.identity(), viewProjection)
        }
        GLES30.glUniform1f(uAlpha, 1f)
        GLES30.glEnable(GLES30.GL_CULL_FACE)
        GLES30.glDepthMask(true)
        GLES30.glDisable(GLES30.GL_BLEND)
    }

    private fun drawCar(viewProjection: Mat4) {
        val v = game.vehicle

        // Cosmetic body roll and pitch, driven by the physics state.
        val roll = -(v.yawRate * v.vx / 90.0).coerceIn(-0.06, 0.06)
        val pitch = (-v.lastAx / 260.0).coerceIn(-0.035, 0.035)
        val carModel = Mat4.compose(
            Vec3(v.x, 0.0, v.z),
            Vec3(pitch.toFloat(), v.yaw.toFloat(), roll.toFloat())
        )

        carBody?.let { draw(it, carModel, viewProjection) }

        for ((mesh, wheel) in wheels) {
            // Front wheels steer; all four spin with the road speed.
            val steer = if (wheel.front) v.steer.toFloat() else 0f
            val local = Mat4.compose(
                Vec3(wheel.x, if (wheel.front) CarMesh.FRONT_RADIUS else CarMesh.REAR_RADIUS, wheel.z),
                Vec3(0f, steer, 0f)
            ) * Mat4.rotationX(-v.wheelSpin.toFloat())
            draw(mesh, carModel * local, viewProjection)
        }
    }

    private fun draw(mesh: GpuMesh, model: Mat4, viewProjection: Mat4) {
        GLES30.glUniformMatrix4fv(uModel, 1, false, model.m, 0)
        GLES30.glUniformMatrix4fv(uMvp, 1, false, (viewProjection * model).m, 0)
        GLES30.glBindVertexArray(mesh.vao)
        GLES30.glDrawElements(GLES30.GL_TRIANGLES, mesh.indexCount, GLES30.GL_UNSIGNED_INT, 0)
        GLES30.glBindVertexArray(0)
    }

    private fun rebuildTrack(track: Track) {
        trackBuffers?.release()
        gateBuffers.forEach { it.release() }
        val built = TrackMesh.build(track)
        trackBuffers = upload(built.ground)
        gateBuffers = built.gates.map { upload(it.mesh) }
    }

    private fun upload(mesh: Mesh): GpuMesh {
        val vao = IntArray(1); GLES30.glGenVertexArrays(1, vao, 0)
        val buffers = IntArray(2); GLES30.glGenBuffers(2, buffers, 0)
        GLES30.glBindVertexArray(vao[0])

        // Writing through the float view leaves the ByteBuffer's own position
        // at zero, so there is nothing to rewind. (Calling Buffer.position()
        // here is the classic way to crash on older Android runtimes, because
        // the covariant overload compiled against a modern JDK is missing.)
        val vertexBytes = ByteBuffer.allocateDirect(mesh.vertices.size * 4)
            .order(ByteOrder.nativeOrder())
        vertexBytes.asFloatBuffer().put(mesh.vertices)
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, buffers[0])
        GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER, mesh.vertices.size * 4, vertexBytes, GLES30.GL_STATIC_DRAW)

        val indexBytes = ByteBuffer.allocateDirect(mesh.indices.size * 4)
            .order(ByteOrder.nativeOrder())
        indexBytes.asIntBuffer().put(mesh.indices)
        GLES30.glBindBuffer(GLES30.GL_ELEMENT_ARRAY_BUFFER, buffers[1])
        GLES30.glBufferData(GLES30.GL_ELEMENT_ARRAY_BUFFER, mesh.indices.size * 4, indexBytes, GLES30.GL_STATIC_DRAW)

        val stride = Mesh.STRIDE_BYTES
        GLES30.glEnableVertexAttribArray(0)
        GLES30.glVertexAttribPointer(0, 3, GLES30.GL_FLOAT, false, stride, 0)
        GLES30.glEnableVertexAttribArray(1)
        GLES30.glVertexAttribPointer(1, 3, GLES30.GL_FLOAT, false, stride, 3 * 4)
        GLES30.glEnableVertexAttribArray(2)
        GLES30.glVertexAttribPointer(2, 4, GLES30.GL_FLOAT, false, stride, 6 * 4)

        GLES30.glBindVertexArray(0)
        return GpuMesh(vao[0], buffers[0], buffers[1], mesh.indexCount)
    }

    private fun buildProgram(vertexSource: String, fragmentSource: String): Int {
        fun compile(type: Int, source: String): Int {
            val shader = GLES30.glCreateShader(type)
            GLES30.glShaderSource(shader, source)
            GLES30.glCompileShader(shader)
            val ok = IntArray(1)
            GLES30.glGetShaderiv(shader, GLES30.GL_COMPILE_STATUS, ok, 0)
            check(ok[0] != 0) { "Shader compile failed: " + GLES30.glGetShaderInfoLog(shader) }
            return shader
        }
        val program = GLES30.glCreateProgram()
        GLES30.glAttachShader(program, compile(GLES30.GL_VERTEX_SHADER, vertexSource))
        GLES30.glAttachShader(program, compile(GLES30.GL_FRAGMENT_SHADER, fragmentSource))
        GLES30.glLinkProgram(program)
        val ok = IntArray(1)
        GLES30.glGetProgramiv(program, GLES30.GL_LINK_STATUS, ok, 0)
        check(ok[0] != 0) { "Program link failed: " + GLES30.glGetProgramInfoLog(program) }
        return program
    }

    private companion object {
        val SKY = floatArrayOf(0.56f, 0.71f, 0.87f)
        val LIGHT = floatArrayOf(0.42f, 0.82f, 0.38f)

        const val VERTEX_SHADER = """#version 300 es
            layout(location = 0) in vec3 aPosition;
            layout(location = 1) in vec3 aNormal;
            layout(location = 2) in vec4 aColor;

            uniform mat4 uMvp;
            uniform mat4 uModel;

            out vec3 vNormal;
            out vec3 vWorld;
            out vec4 vColor;

            void main() {
                vWorld = (uModel * vec4(aPosition, 1.0)).xyz;
                vNormal = mat3(uModel) * aNormal;
                vColor = aColor;
                gl_Position = uMvp * vec4(aPosition, 1.0);
            }
        """

        /**
         * One directional sun with a Blinn-Phong highlight, sky/ground
         * hemispheric ambient so upward faces pick up the sky, and distance fog
         * that fades into the horizon.
         */
        const val FRAGMENT_SHADER = """#version 300 es
            precision mediump float;

            in vec3 vNormal;
            in vec3 vWorld;
            in vec4 vColor;

            uniform vec3 uLightDir;
            uniform vec3 uCameraPos;
            uniform vec3 uFogColor;
            uniform vec2 uFogRange;
            uniform float uAlpha;
            uniform vec3 uTint;

            out vec4 fragColor;

            void main() {
                vec3 n = normalize(vNormal);
                vec3 l = normalize(uLightDir);
                vec3 v = normalize(uCameraPos - vWorld);

                float diffuse = max(dot(n, l), 0.0);
                vec3 sky = vec3(0.62, 0.72, 0.88);
                vec3 ground = vec3(0.22, 0.24, 0.20);
                vec3 ambient = mix(ground, sky, n.y * 0.5 + 0.5);

                vec3 base = vColor.rgb * uTint;
                vec3 lit = base * (ambient * 0.55 + vec3(1.0, 0.97, 0.92) * diffuse * 0.95);

                float specular = pow(max(dot(n, normalize(l + v)), 0.0), 48.0) * vColor.a;
                lit += vec3(1.0, 0.98, 0.94) * specular * 0.8;

                float fog = smoothstep(uFogRange.x, uFogRange.y, length(uCameraPos - vWorld));
                fragColor = vec4(mix(lit, uFogColor, fog), uAlpha);
            }
        """
    }
}
