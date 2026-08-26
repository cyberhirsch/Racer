package dev.racer.core

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.PI

/**
 * Turns a [Track] into renderable geometry.
 *
 * Everything static — road, lines, kerbs, gravel, the start gantry —
 * goes into a single mesh, and therefore a single draw call. Built as separate
 * objects, a long circuit's kerbs and gates alone are well over a thousand
 * draws per frame, which is enough to halve the frame rate on a phone.
 *
 * The checkpoint gates are returned separately because they are drawn
 * translucent and disappear as they are passed.
 */
object TrackMesh {

    private val ROAD = Material.rgb(0x2B2E34, specular = 0.10f)
    private val LINE = Material.rgb(0xE8E8E8, specular = 0.15f)
    private val KERB_RED = Material.rgb(0xCC2222, specular = 0.2f)
    private val KERB_WHITE = Material.rgb(0xEDEDED, specular = 0.2f)
    private val GRAVEL = Material.rgb(0x6B5A3E, specular = 0.02f)
    private val GRASS = Material.rgb(0x2F4A2B, specular = 0.02f)
    private val DARK = Material.rgb(0x22262C, specular = 0.4f)
    private val BARK = Material.rgb(0x5A4632, specular = 0.08f)
    private val LEAF = Material.rgb(0x3F7A34, specular = 0.05f)
    private val STONE = Material.rgb(0x8A8880, specular = 0.18f)
    private val CHECK_DARK = Material.rgb(0x111111, specular = 0.1f)
    private val CHECK_LIGHT = Material.rgb(0xF5F5F5, specular = 0.1f)

    class Gate(val mesh: Mesh, val frameIndex: Int, val finish: Boolean)

    class Built(val ground: Mesh, val gates: List<Gate>)

    fun build(track: Track): Built {
        val b = MeshBuilder()
        val hw = track.halfWidth.toFloat()

        // Grass apron, gravel traps, road, then the painted lines on top. Each
        // is a ribbon between two lateral offsets, at a slightly different
        // height so they do not z-fight.
        // The grass runs a long way out, because there is nothing to stop you
        // going there: no barriers anywhere on the circuit. Driving off is
        // allowed, and it should not end at the edge of a green rug.
        ribbon(b, track, -(track.runoff + Track.GRASS_APRON), track.runoff + Track.GRASS_APRON, -0.14f, GRASS)
        ribbon(b, track, -track.runoff, track.runoff, -0.02f, GRAVEL)
        ribbon(b, track, -track.halfWidth, track.halfWidth, 0.02f, ROAD)
        ribbon(b, track, -track.halfWidth + 0.15, -track.halfWidth + 0.45, 0.035f, LINE)
        ribbon(b, track, track.halfWidth - 0.45, track.halfWidth - 0.15, 0.035f, LINE)

        kerbs(b, track)
        scenery(b, track)
        startGantry(b, track, hw)

        val gates = track.checkpoints.mapIndexed { i, frameIndex ->
            Gate(gateMesh(track, frameIndex, i == track.checkpoints.size - 1), frameIndex,
                i == track.checkpoints.size - 1)
        }
        return Built(b.build(), gates)
    }

    /** A flat ribbon between two lateral offsets, running the whole circuit. */
    private fun ribbon(
        b: MeshBuilder, track: Track, fromOffset: Double, toOffset: Double, y: Float, mat: Material
    ) {
        val up = Vec3(0f, 1f, 0f)
        val base = b.vertexCount
        for (f in track.frames) {
            b.vertex(Vec3(f.pos.x + f.right.x * fromOffset, y.toDouble(), f.pos.z + f.right.z * fromOffset), up, mat)
            b.vertex(Vec3(f.pos.x + f.right.x * toOffset, y.toDouble(), f.pos.z + f.right.z * toOffset), up, mat)
        }
        for (i in 0 until track.frames.size - 1) {
            val a = base + i * 2
            b.quad(a, a + 2, a + 3, a + 1)
        }
    }

    /** Red and white kerbs, only where the track actually bends. */
    private fun kerbs(b: MeshBuilder, track: Track) {
        var stripe = 0
        var i = 0
        while (i < track.frames.size - 2) {
            val k = track.curvature[i]
            if (abs(k) < 0.004) { i += 2; continue }
            val side = if (k > 0) 1.0 else -1.0
            val f = track.frames[i]
            val f2 = track.frames[i + 2]
            val seg = f.pos.distanceTo(f2.pos).coerceAtLeast(1.5)
            val off = side * (track.halfWidth + 0.55)
            b.addBox(
                Vec3(1.1f, 0.10f, seg.toFloat()),
                Mat4.compose(
                    Vec3(f.pos.x + f.right.x * off, 0.05, f.pos.z + f.right.z * off),
                    Vec3(0f, atan2(f.tangent.x, f.tangent.z).toFloat(), 0f)
                ),
                if (stripe++ % 2 == 0) KERB_RED else KERB_WHITE
            )
            i += 2
        }
    }

    /**
     * The trees and rocks standing on the grass.
     *
     * Built into the same mesh as the road, because they never move and a
     * separate draw call for each of two hundred trees would cost more than
     * the whole circuit does. Their shapes are deliberately plain — a trunk
     * and two cones, or a couple of leaning blocks — since at the distance
     * they are ever seen from, silhouette is all that reads.
     */
    private fun scenery(b: MeshBuilder, track: Track) {
        for ((i, o) in track.obstacles.withIndex()) {
            val at = Vec3(o.x.toFloat(), 0f, o.z.toFloat())
            // Deterministic variety without another random source: the index
            // is as good a jumble as any, and it stays put between runs.
            val wobble = ((i * 37) % 100) / 100f
            if (o.tree) {
                val height = 4.5f + wobble * 3.5f
                val trunk = o.radius.toFloat() * 0.35f
                b.addCylinder(
                    trunk * 0.8f, trunk, height * 0.42f, 6,
                    Mat4.translation(at.x, height * 0.21f, at.z), BARK, caps = false
                )
                b.addCylinder(
                    0f, o.radius.toFloat() * 1.5f, height * 0.5f, 7,
                    Mat4.translation(at.x, height * 0.55f, at.z), LEAF, caps = false
                )
                b.addCylinder(
                    0f, o.radius.toFloat() * 1.1f, height * 0.4f, 7,
                    Mat4.translation(at.x, height * 0.8f, at.z), LEAF, caps = false
                )
            } else {
                val r = o.radius.toFloat()
                b.addSphere(
                    r, 6,
                    Mat4.compose(Vec3(at.x, r * 0.35f, at.z), Vec3(wobble * 0.5f, wobble * 6f, 0f)) *
                        Mat4.scale(1f, 0.7f, 1.15f),
                    STONE
                )
                b.addSphere(
                    r * 0.55f, 5,
                    Mat4.compose(
                        Vec3(at.x + r * 0.7f, r * 0.2f, at.z - r * 0.4f),
                        Vec3(0f, wobble * 4f, 0f)
                    ),
                    STONE
                )
            }
        }
    }

    private fun startGantry(b: MeshBuilder, track: Track, hw: Float) {
        val f = track.frames[0]
        val yaw = atan2(f.tangent.x, f.tangent.z).toFloat()
        val squares = 16
        val cell = track.cfg.width / squares

        for (i in 0 until squares) for (j in 0 until 2) {
            val lat = -track.halfWidth + (i + 0.5) * cell
            val along = (j - 0.5) * 0.6
            b.addBox(
                Vec3(cell.toFloat(), 0.03f, 0.6f),
                Mat4.compose(
                    Vec3(
                        f.pos.x + f.right.x * lat + f.tangent.x * along, 0.045,
                        f.pos.z + f.right.z * lat + f.tangent.z * along
                    ),
                    Vec3(0f, yaw, 0f)
                ),
                if ((i + j) % 2 == 0) CHECK_DARK else CHECK_LIGHT
            )
        }

        for (side in listOf(-1.0, 1.0)) {
            b.addBox(Vec3(0.4f, 7f, 0.4f), Mat4.compose(Vec3(
                f.pos.x + f.right.x * side * (track.halfWidth + 1.5), 3.5,
                f.pos.z + f.right.z * side * (track.halfWidth + 1.5)
            )), DARK)
        }
        b.addBox(Vec3(track.cfg.width.toFloat() + 3.6f, 1.0f, 0.6f),
            Mat4.compose(Vec3(f.pos.x, 7.0, f.pos.z), Vec3(0f, yaw, 0f)), DARK)
    }

    /** A checkpoint gate: a translucent pane between two lit poles. */
    private fun gateMesh(track: Track, frameIndex: Int, finish: Boolean): Mesh {
        val b = MeshBuilder()
        val f = track.frames[frameIndex]
        val mat = if (finish) Material.rgb(0x00FF88, specular = 0f) else Material.rgb(0x33AAFF, specular = 0f)
        val hw = track.halfWidth

        val a = Vec3(f.pos.x - f.right.x * hw, 0.1, f.pos.z - f.right.z * hw)
        val bb = Vec3(f.pos.x + f.right.x * hw, 0.1, f.pos.z + f.right.z * hw)
        val c = Vec3(f.pos.x + f.right.x * hw, 5.1, f.pos.z + f.right.z * hw)
        val d = Vec3(f.pos.x - f.right.x * hw, 5.1, f.pos.z - f.right.z * hw)
        b.addQuad(a, bb, c, d, mat)
        b.addQuad(bb, a, d, c, mat)      // visible from behind too

        for (side in listOf(-1.0, 1.0)) {
            b.addCylinder(0.16f, 0.16f, 5.2f, 8, Mat4.compose(Vec3(
                f.pos.x + f.right.x * side * hw, 2.6, f.pos.z + f.right.z * side * hw
            )), mat)
        }
        return b.build()
    }
}
