package dev.racer.app

import android.opengl.GLES30
import android.opengl.GLSurfaceView
import dev.racer.core.CarMesh
import dev.racer.core.Debris
import dev.racer.core.Game
import dev.racer.core.Mat4
import dev.racer.core.Mesh
import dev.racer.core.Track
import dev.racer.core.TrackMesh
import dev.racer.core.Vec3
import dev.racer.core.Wreck
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
    private var uUnlit = 0
    private var uAlpha = 0
    private var uTint = 0

    private var trackBuffers: GpuMesh? = null
    private var gateBuffers: List<GpuMesh> = emptyList()
    private var carShadow: GpuMesh? = null
    private var carParts: List<Pair<CarMesh.Part, GpuMesh>> = emptyList()
    private var wheels: List<Pair<GpuMesh, CarMesh.Wheel>> = emptyList()

    /**
     * Every piece of car, found by the mesh it was built from.
     *
     * A wreck hands back bodies that carry the same [Mesh] instances the car
     * was built with, so this is how a body finds the buffers it belongs to
     * without the two sides having to agree on an ordering.
     */
    private var carGpu: MutableMap<Mesh, GpuMesh> = HashMap()

    /**
     * One buffer for every shard of carbon and blade of grass a crash throws.
     *
     * Built world-space and drawn with an identity model matrix, so the whole
     * spray — a couple of hundred quads — costs one upload and one draw call.
     * Allocated on the first crash and kept, because its size never changes.
     */
    private var debrisBuffers: GpuMesh? = null

    /**
     * Which crash the debris buffer currently holds.
     *
     * A fresh wreck starts its shape version at zero, so without this a second
     * crash would find the version it left the first one at and never upload.
     */
    private var debrisOwner: Debris? = null

    private var width = 1
    private var height = 1
    private var lastFrameNanos = 0L
    private var framesThisSecond = 0
    private var secondsOfFrames = 0.0
    private var frameCounter = 0
    private var lastLoggedRoll = 0.0

    /**
     * Called on the GL thread at the start of every frame with the real elapsed
     * time, so the simulation advances exactly once per drawn frame and the
     * camera is computed from the same delta the physics just used.
     */
    var onFrame: ((Double) -> Unit)? = null

    fun setTrack(track: Track) { pendingTrack.set(track) }

    private class GpuMesh(val vao: Int, val vbo: Int, val ibo: Int, val indexCount: Int) {
        /**
         * Which version of a deformable piece's shape is currently on the GPU.
         *
         * A wreck rebuilds a piece's vertices only when something actually
         * hits it, which is a handful of times over the whole crash — so the
         * buffer is re-uploaded on that same handful of frames rather than
         * every frame, and the rest of the time this costs one integer
         * comparison.
         */
        var uploadedShape = -1

        fun replaceVertices(v: FloatArray) {
            val bytes = ByteBuffer.allocateDirect(v.size * 4).order(ByteOrder.nativeOrder())
            bytes.asFloatBuffer().put(v)
            GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, vbo)
            GLES30.glBufferSubData(GLES30.GL_ARRAY_BUFFER, 0, v.size * 4, bytes)
            GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, 0)
        }

        fun release() {
            GLES30.glDeleteVertexArrays(1, intArrayOf(vao), 0)
            GLES30.glDeleteBuffers(2, intArrayOf(vbo, ibo), 0)
        }
    }

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        // The empty sky has to match what the shader paints on geometry that
        // has faded entirely into the haze, or there is a visible seam along
        // the horizon where the two meet. Since the shader now tone maps, the
        // clear colour has to go through the same curve rather than being the
        // fog colour itself.
        val sky = skyAsDisplayed()
        GLES30.glClearColor(sky[0], sky[1], sky[2], 1f)
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
        uUnlit = GLES30.glGetUniformLocation(program, "uUnlit")
        uAlpha = GLES30.glGetUniformLocation(program, "uAlpha")
        uTint = GLES30.glGetUniformLocation(program, "uTint")

        // The same car the game hands to a wreck, so the pieces a crash takes
        // apart are the pieces being drawn.
        val car = game.car
        carGpu = HashMap()
        carParts = car.partList.map { (part, mesh) ->
            val gpu = upload(mesh, dynamic = true)
            carGpu[mesh] = gpu
            part to gpu
        }
        carShadow = upload(CarMesh.shadow())
        wheels = car.wheels.map { w ->
            val gpu = upload(w.mesh, dynamic = true)
            carGpu[w.mesh] = gpu
            gpu to w
        }

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
        // level for the person holding it. The maths lives in :core, where
        // CameraRollTest measures the angle the horizon actually comes out at;
        // a roll sign is far too easy to get backwards to keep it here.
        val view = Mat4.lookAtRolled(cam.eye, cam.target, Vec3(0f, 1f, 0f), cam.rollRadians)

        // Report what the renderer is actually drawing with, whenever it
        // changes materially. The app can read the right roll from the sensor
        // and still draw a level frame; only this tells the two apart, and it
        // is what the emulator test asserts on.
        // How fast is this actually drawing? The game's own heartbeat runs on
        // simulated time, so when the frame rate collapses the log goes quiet
        // rather than saying so — which is exactly how an over-expensive
        // shader looked like a hung app for two rounds.
        framesThisSecond++
        secondsOfFrames += elapsed
        if (secondsOfFrames >= 2.0) {
            android.util.Log.i("Racer", "render %.1f fps".format(framesThisSecond / secondsOfFrames))
            framesThisSecond = 0
            secondsOfFrames = 0.0
        }

        val rollDegrees = Math.toDegrees(cam.rollRadians.toDouble())
        if (kotlin.math.abs(rollDegrees - lastLoggedRoll) > 3.0 || ++frameCounter % 120 == 0) {
            lastLoggedRoll = rollDegrees
            android.util.Log.i("Racer", "draw roll=%.1f deg".format(rollDegrees))
        }
        val viewProjection = projection * view

        GLES30.glUseProgram(program)
        GLES30.glUniform3f(uLightDir, LIGHT[0], LIGHT[1], LIGHT[2])
        GLES30.glUniform3f(uCameraPos, cam.eye.x, cam.eye.y, cam.eye.z)
        GLES30.glUniform3f(uFogColor, SKY[0], SKY[1], SKY[2])
        GLES30.glUniform2f(uFogRange, 140f, 620f)
        GLES30.glUniform1f(uAlpha, 1f)
        GLES30.glUniform1f(uUnlit, 0f)
        GLES30.glUniform3f(uTint, 1f, 1f, 1f)

        GLES30.glDisable(GLES30.GL_BLEND)
        trackBuffers?.let { draw(it, Mat4.identity(), viewProjection) }

        drawShadow(viewProjection)
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

    /**
     * The patch of shade under the car.
     *
     * Multiplied over the ground rather than drawn on top of it, so the road
     * markings and the grain of the tarmac still show through — a flat grey
     * blob painted over them would look like a sticker. Culling is off because
     * the winding of a fan is easy to get backwards and there is nothing to
     * gain by insisting on it, and depth writes are off so the car is not
     * fighting its own shadow for the same millimetre of ground.
     */
    private fun drawShadow(viewProjection: Mat4) {
        val shadow = carShadow ?: return
        // One patch of shade under one car. Once it is in pieces there is no
        // single place to put it, and leaving it behind at the impact point
        // draws a car-shaped shadow with no car above it.
        if (game.wreck != null) return
        val v = game.vehicle
        GLES30.glEnable(GLES30.GL_BLEND)
        GLES30.glBlendFunc(GLES30.GL_ZERO, GLES30.GL_SRC_COLOR)
        GLES30.glDepthMask(false)
        GLES30.glDisable(GLES30.GL_CULL_FACE)
        GLES30.glUniform1f(uUnlit, 1f)

        draw(shadow, Mat4.compose(Vec3(v.x, 0.0, v.z), Vec3(0f, v.yaw.toFloat(), 0f)), viewProjection)

        GLES30.glUniform1f(uUnlit, 0f)
        GLES30.glEnable(GLES30.GL_CULL_FACE)
        GLES30.glDepthMask(true)
        GLES30.glDisable(GLES30.GL_BLEND)
    }

    private fun drawCar(viewProjection: Mat4) {
        game.wreck?.let { drawWreck(it, viewProjection); return }

        // A wreck leaves its damage in the GPU's copy of the bodywork, and
        // nothing else ever writes those buffers — so without this the next
        // race starts with the last one's crumpled nose still on the car.
        restoreUndamagedBodywork()

        val v = game.vehicle

        // Cosmetic body roll and pitch, driven by the physics state.
        val roll = -(v.yawRate * v.vx / 90.0).coerceIn(-0.06, 0.06)
        val pitch = (-v.lastAx / 260.0).coerceIn(-0.035, 0.035)
        val carModel = Mat4.compose(
            Vec3(v.x, 0.0, v.z),
            Vec3(pitch.toFloat(), v.yaw.toFloat(), roll.toFloat())
        )

        // In one piece the car is still one rigid object: every part gets the
        // same matrix. It is drawn a part at a time only so that the moment it
        // stops being one object, nothing about the drawing has to change.
        for ((_, gpu) in carParts) draw(gpu, carModel, viewProjection)

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

    /** Put the factory shape back on the GPU, if a crash replaced it. */
    private fun restoreUndamagedBodywork() {
        for ((mesh, gpu) in carGpu) {
            if (gpu.uploadedShape == -1) continue
            gpu.replaceVertices(mesh.vertices)
            gpu.uploadedShape = -1
        }
    }

    /**
     * The car once it has stopped being one.
     *
     * Every piece carries its own matrix from the simulation, so this does not
     * need to know what is still bolted on and what is not — a piece that is
     * still attached simply reports the tub's pose. The only extra work is
     * handing the GPU a piece's new shape on the frames it has been bent.
     */
    private fun drawWreck(wreck: Wreck, viewProjection: Mat4) {
        drawDebris(wreck.debris, viewProjection)
        for (body in wreck.bodies) {
            val gpu = carGpu[body.base] ?: continue
            if (gpu.uploadedShape != body.shapeVersion) {
                gpu.replaceVertices(body.vertices)
                gpu.uploadedShape = body.shapeVersion
            }
            draw(gpu, body.modelMatrix(), viewProjection)
        }
    }

    /**
     * The spray.
     *
     * Both faces, because a shard is a flat quad that tumbles and would
     * otherwise wink out of existence for half of every rotation. Culling is
     * restored immediately afterwards rather than being left off for the
     * wreck, which is closed geometry and wants it.
     */
    private fun drawDebris(debris: Debris, viewProjection: Mat4) {
        val gpu = debrisBuffers ?: upload(Mesh(debris.vertices, debris.indices), dynamic = true)
            .also { debrisBuffers = it }
        if (debrisOwner !== debris) {
            debrisOwner = debris
            gpu.uploadedShape = -1
        }
        if (gpu.uploadedShape != debris.shapeVersion) {
            gpu.replaceVertices(debris.vertices)
            gpu.uploadedShape = debris.shapeVersion
        }
        GLES30.glDisable(GLES30.GL_CULL_FACE)
        draw(gpu, Mat4.identity(), viewProjection)
        GLES30.glEnable(GLES30.GL_CULL_FACE)
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

    private fun upload(mesh: Mesh, dynamic: Boolean = false): GpuMesh {
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
        // A piece that can be crumpled has its vertices replaced as it takes
        // damage, so the driver is told to expect that up front.
        GLES30.glBufferData(
            GLES30.GL_ARRAY_BUFFER, mesh.vertices.size * 4, vertexBytes,
            if (dynamic) GLES30.GL_DYNAMIC_DRAW else GLES30.GL_STATIC_DRAW
        )

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

        /**
         * [SKY] put through the same decode, tone map and encode the fragment
         * shader applies, so the cleared background and fully fogged geometry
         * end up the same colour.
         */
        fun skyAsDisplayed(): FloatArray = FloatArray(3) { i ->
            val linear = SKY[i] * SKY[i]
            val mapped = ((linear * (2.51f * linear + 0.03f)) /
                (linear * (2.43f * linear + 0.59f) + 0.14f)).coerceIn(0f, 1f)
            kotlin.math.sqrt(mapped)
        }
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
         * A directional sun, hemispheric ambient, and distance haze — but all
         * of it worked out in linear light and tone mapped at the end.
         *
         * The old shader added its lighting straight to colours that were
         * already display-encoded, which is what gave everything that flat,
         * plastic look: highlights clipped to white instead of rolling off,
         * shadowed faces went muddy rather than dark, and no amount of tuning
         * the numbers fixed it. Colours are decoded to linear, lit, run
         * through a filmic curve and encoded back. Gamma two is used for both
         * conversions rather than the exact 2.2 — a multiply and a square root
         * against a pow, for a difference nobody can see on a phone.
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
            uniform float uUnlit;

            out vec4 fragColor;

            // Cheap value noise, used to break up surfaces that are otherwise
            // one flat colour across hundreds of square metres.
            float hash21(vec2 p) {
                p = fract(p * vec2(123.34, 456.21));
                p += dot(p, p + 45.32);
                return fract(p.x * p.y);
            }

            // One sample per 30cm cell, no smoothing between them. A proper
            // interpolated noise costs four hashes a pixel, and this runs on a
            // software rasteriser in CI where that is not free — at the size
            // the cells appear on screen, and with the distance fade below,
            // the difference is invisible and the cost is a quarter.
            float grain(vec2 p) {
                return hash21(floor(p));
            }

            // Narkowicz's fit of the ACES curve: highlights roll off instead
            // of clipping, which is most of the difference between a render
            // that looks photographed and one that looks drawn.
            vec3 tonemap(vec3 x) {
                return clamp((x * (2.51 * x + 0.03)) / (x * (2.43 * x + 0.59) + 0.14), 0.0, 1.0);
            }

            void main() {
                // The shadow patch is not a surface and must not be lit: it is
                // multiplied over the ground exactly as it is.
                if (uUnlit > 0.5) {
                    fragColor = vec4(vColor.rgb, 1.0);
                    return;
                }

                vec3 n = normalize(vNormal);
                vec3 l = normalize(uLightDir);
                vec3 v = normalize(uCameraPos - vWorld);
                float toCamera = length(uCameraPos - vWorld);

                vec3 albedo = vColor.rgb * uTint;
                albedo *= albedo;                       // display -> linear

                // Tarmac and grass are single flat colours over huge areas,
                // which reads as plastic sheeting. A little noise in the
                // albedo, on near-horizontal surfaces only and faded out with
                // distance so it never sparkles, reads as texture.
                float detail = smoothstep(0.75, 0.95, abs(n.y)) *
                    (1.0 - smoothstep(15.0, 70.0, toCamera));
                if (detail > 0.01) {
                    albedo *= 1.0 + (grain(vWorld.xz * 3.2) - 0.5) * 0.24 * detail;
                }

                float ndl = max(dot(n, l), 0.0);
                vec3 sun = vec3(1.0, 0.95, 0.86) * 2.6;
                vec3 skyLight = vec3(0.20, 0.30, 0.52);
                vec3 bounce = vec3(0.13, 0.12, 0.09);
                vec3 ambient = mix(bounce, skyLight, n.y * 0.5 + 0.5);

                vec3 lit = albedo * (ambient + sun * ndl);

                // Gloss comes from the material's specular channel: matte
                // rubber and painted carbon want very different highlights,
                // and one fixed exponent gave them the same one.
                // Two fixed exponents, reached by squaring, and mixed by the
                // material's gloss. A pow with a computed exponent cannot be
                // folded away by the compiler and has to be evaluated per
                // pixel; on the software rasteriser CI runs on that alone was
                // enough to drop the frame rate to a crawl.
                float gloss = vColor.a;
                vec3 h = normalize(l + v);
                float nh = max(dot(n, h), 0.0);
                float nh2 = nh * nh;
                float nh4 = nh2 * nh2;
                float nh8 = nh4 * nh4;
                float nh16 = nh8 * nh8;
                float spec = mix(nh8, nh16 * nh16, gloss) * ndl;

                // Grazing angles reflect more, whatever the material. This is
                // what puts a bright edge along the top of the bodywork and
                // makes it read as a hard, shiny surface.
                float grazing = 1.0 - max(dot(n, v), 0.0);
                float g2 = grazing * grazing;
                float fresnel = g2 * g2 * grazing;
                lit += sun * spec * (0.04 + 0.96 * gloss) * (0.3 + 2.0 * fresnel);

                // Distance haze, with the sun burning through it when looked
                // towards — the horizon is never one flat colour in daylight.
                vec3 fogLinear = uFogColor * uFogColor;
                float towardsSun = max(dot(normalize(vWorld - uCameraPos), l), 0.0);
                float s2 = towardsSun * towardsSun;
                float s8 = s2 * s2; s8 = s8 * s8;
                fogLinear = mix(fogLinear, vec3(1.0, 0.86, 0.66), s8 * 0.55);

                float fog = smoothstep(uFogRange.x, uFogRange.y, toCamera);
                vec3 colour = mix(lit, fogLinear, fog);

                fragColor = vec4(sqrt(tonemap(colour)), uAlpha);   // linear -> display
            }
        """
    }
}
