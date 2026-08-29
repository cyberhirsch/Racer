package dev.racer.core

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * A deformable panel: a lattice of point masses that the bodywork is skinned to.
 *
 * This is a simulation, not a displacement field. Every panel carries a coarse
 * cage of nodes spread through its bounding box, joined by distance
 * constraints along the cage's edges, its face diagonals and its body
 * diagonals. A blow pushes nodes; the constraints then argue it out among
 * themselves over several iterations, which is what propagates a hit on the
 * nose back through the whole panel instead of leaving a local crater. The
 * mesh's own vertices never take part — they are carried along by trilinear
 * interpolation of whichever cell they sit in, so a panel of six hundred
 * vertices costs the same to simulate as one of sixty.
 *
 * Two kinds of constraint, and the difference between them is the whole point:
 *
 *  - **Structural** constraints hold neighbouring nodes apart. Past a yield
 *    strain their rest length is permanently rewritten toward what it has
 *    actually been stretched or squashed to. That is plasticity: carbon does
 *    not spring back, and once a fold has formed it stays folded even though
 *    the constraint solving that formed it is entirely elastic.
 *  - **Shape** constraints pull every node softly toward where it started.
 *    Without them a panel that takes a hard hit keeps wobbling and eventually
 *    collapses into a bag; with them it behaves like a stiff shell that
 *    dents. Their rest positions never move, so they are also the memory of
 *    how far the piece is from its factory shape.
 *
 * Solved with position-based dynamics — project the positions, then read the
 * velocities back off the movement. It is unconditionally stable at any time
 * step, which matters here because a crash runs in slow motion and then jumps
 * back up to full speed, and a force-based spring lattice tuned to look stiff
 * at one of those rates explodes at the other.
 */
class SoftCage private constructor(
    private val nx: Int,
    private val ny: Int,
    private val nz: Int,
    /** The corner of the rest box, in the piece's own mesh coordinates. */
    private val origin: Vec3,
    /** The size of the rest box. Never zero on any axis. */
    private val size: Vec3,
    private val base: FloatArray,
    private val indices: IntArray
) {

    private val count = nx * ny * nz

    /** Where each node started, and where the shape constraints pull it back to. */
    private val rest = FloatArray(count * 3)
    private val position = FloatArray(count * 3)
    private val previous = FloatArray(count * 3)
    private val velocity = FloatArray(count * 3)

    /**
     * One distance constraint. [restLength] is a var because yielding rewrites
     * it — that single mutable float is where permanent damage lives.
     */
    private class Link(val a: Int, val b: Int, val originalLength: Float) {
        var restLength = originalLength
    }

    private val links = ArrayList<Link>()

    /**
     * How each mesh vertex is carried.
     *
     * Precomputed once: the cell it falls in and its position within that
     * cell. A vertex never changes cells, because the cage is indexed on the
     * *rest* shape — which is what stops a badly folded panel from tearing
     * itself apart as vertices hop between cells.
     */
    private val cellOf = IntArray(base.size / Mesh.FLOATS_PER_VERTEX)
    private val weightOf = FloatArray(cellOf.size * 3)

    private val skinned: FloatArray = base.copyOf()
    private var stale = false

    /**
     * The current deformed shape.
     *
     * Skinned on demand rather than at the end of every sub-step. A crash runs
     * a hundred and twenty steps a second and is looked at sixty times, and
     * re-interpolating several hundred vertices and rebuilding their normals
     * is far and away the most expensive thing in here — so it happens when
     * somebody actually wants to see the panel, once, however many steps have
     * gone by since.
     */
    val vertices: FloatArray
        get() {
            if (stale) { skin(); stale = false }
            return skinned
        }

    /**
     * Bumped whenever the lattice has moved.
     *
     * Counted in [step] rather than in the skinning, so a renderer can ask
     * whether the shape has changed without forcing the work of building it.
     */
    var shapeVersion = 0
        private set

    /** True while any node is still moving enough to be worth solving. */
    var awake = false
        private set

    init {
        var n = 0
        for (k in 0 until nz) for (j in 0 until ny) for (i in 0 until nx) {
            rest[n] = origin.x + size.x * i / (nx - 1f)
            rest[n + 1] = origin.y + size.y * j / (ny - 1f)
            rest[n + 2] = origin.z + size.z * k / (nz - 1f)
            n += 3
        }
        rest.copyInto(position)
        rest.copyInto(previous)

        // Every node against every node it can see one cell away: edges hold
        // the lattice's length, face diagonals its shear, body diagonals its
        // twist. Leaving the diagonals out gives a lattice that folds flat
        // under its own solving like an unbraced bookcase.
        for (k in 0 until nz) for (j in 0 until ny) for (i in 0 until nx) {
            val a = index(i, j, k)
            for (dk in 0..1) for (dj in -1..1) for (di in -1..1) {
                if (dk == 0 && (dj < 0 || (dj == 0 && di <= 0))) continue
                val i2 = i + di; val j2 = j + dj; val k2 = k + dk
                if (i2 !in 0 until nx || j2 !in 0 until ny || k2 !in 0 until nz) continue
                val b = index(i2, j2, k2)
                links.add(Link(a, b, distance(rest, a, rest, b)))
            }
        }

        bindVertices()
    }

    private fun index(i: Int, j: Int, k: Int) = (k * ny + j) * nx + i

    /** Work out, once, which cell each mesh vertex rides in and where. */
    private fun bindVertices() {
        val stride = Mesh.FLOATS_PER_VERTEX
        var v = 0
        var slot = 0
        while (v < base.size) {
            val u = ((base[v] - origin.x) / size.x).coerceIn(0f, 1f) * (nx - 1)
            val s = ((base[v + 1] - origin.y) / size.y).coerceIn(0f, 1f) * (ny - 1)
            val t = ((base[v + 2] - origin.z) / size.z).coerceIn(0f, 1f) * (nz - 1)
            val i = min(nx - 2, u.toInt())
            val j = min(ny - 2, s.toInt())
            val k = min(nz - 2, t.toInt())
            cellOf[slot] = index(i, j, k)
            weightOf[slot * 3] = u - i
            weightOf[slot * 3 + 1] = s - j
            weightOf[slot * 3 + 2] = t - k
            slot++
            v += stride
        }
    }

    /**
     * Push the panel.
     *
     * [at] and [direction] are in the piece's own mesh coordinates; [impulse]
     * is metres per second given to a node at the centre of the blow, falling
     * off to nothing at [reach]. Nodes are given velocity rather than being
     * moved, so the fold develops through the solver over the following
     * frames — which is why a heavy hit visibly crumples rather than snapping
     * to its final shape on the frame it lands.
     */
    fun strike(at: Vec3, direction: Vec3, impulse: Float, reach: Float) {
        if (impulse <= 0f || reach <= 1e-4f) return
        val d = direction.normalized()
        var n = 0
        var touched = false
        while (n < position.size) {
            val dx = position[n] - at.x
            val dy = position[n + 1] - at.y
            val dz = position[n + 2] - at.z
            val distance = sqrt(dx * dx + dy * dy + dz * dz)
            if (distance < reach) {
                val f = 1f - distance / reach
                val share = f * f * (3f - 2f * f) * impulse
                velocity[n] += d.x * share
                velocity[n + 1] += d.y * share
                velocity[n + 2] += d.z * share
                touched = true
            }
            n += 3
        }
        if (touched) awake = true
    }

    /**
     * Advance the lattice.
     *
     * Predict, project the constraints a few times, read the velocities back
     * off how far everything actually moved, and re-skin. Panels are stepped
     * on the wreck's own clock, so a crash in slow motion crumples in slow
     * motion too.
     */
    fun step(dt: Float) {
        if (!awake || dt <= 0f) return

        position.copyInto(previous)
        val damp = 1f - min(0.9f, DAMPING * dt)
        var n = 0
        while (n < position.size) {
            velocity[n] *= damp; velocity[n + 1] *= damp; velocity[n + 2] *= damp
            position[n] += velocity[n] * dt
            position[n + 1] += velocity[n + 1] * dt
            position[n + 2] += velocity[n + 2] * dt
            n += 3
        }

        repeat(ITERATIONS) { solve() }

        var moving = false
        val inv = 1f / dt
        n = 0
        while (n < position.size) {
            val vx = (position[n] - previous[n]) * inv
            val vy = (position[n + 1] - previous[n + 1]) * inv
            val vz = (position[n + 2] - previous[n + 2]) * inv
            velocity[n] = vx; velocity[n + 1] = vy; velocity[n + 2] = vz
            if (vx * vx + vy * vy + vz * vz > SLEEP * SLEEP) moving = true
            n += 3
        }

        stale = true
        shapeVersion++
        if (!moving) {
            // Stop cleanly rather than trickling: a panel that keeps solving
            // at a millimetre a second costs the same as one being smashed.
            java.util.Arrays.fill(velocity, 0f)
            awake = false
        }
    }

    private fun solve() {
        for (l in links) {
            val a = l.a * 3
            val b = l.b * 3
            var dx = position[b] - position[a]
            var dy = position[b + 1] - position[a + 1]
            var dz = position[b + 2] - position[a + 2]
            val length = sqrt(dx * dx + dy * dy + dz * dz)
            if (length < 1e-6f) continue
            val strain = (length - l.restLength) / l.restLength

            // Plasticity. Past the yield strain the constraint gives up part
            // of the difference for good, so the fold survives the solver
            // pulling everything straight again afterwards.
            if (abs(strain) > YIELD) {
                val over = strain - YIELD * (if (strain > 0f) 1f else -1f)
                l.restLength = max(1e-3f, l.restLength * (1f + over * PLASTIC))
            }

            val correction = (length - l.restLength) / length * 0.5f * STIFFNESS
            dx *= correction; dy *= correction; dz *= correction
            position[a] += dx; position[a + 1] += dy; position[a + 2] += dz
            position[b] -= dx; position[b + 1] -= dy; position[b + 2] -= dz
        }

        // The shell. Soft, and pulling toward the shape the panel remembers,
        // so it resists being deformed at all and a light knock barely marks
        // it.
        //
        // That memory yields too, and it has to: a shell anchored to the
        // factory shape for ever would quietly undo every fold the structural
        // constraints had just made permanent, and the panel would spring
        // back over the following second however hard it was hit.
        var n = 0
        while (n < position.size) {
            for (axis in 0 until 3) {
                val o = n + axis
                position[o] += (rest[o] - position[o]) * SHELL
            }
            val dx = position[n] - rest[n]
            val dy = position[n + 1] - rest[n + 1]
            val dz = position[n + 2] - rest[n + 2]
            if (dx * dx + dy * dy + dz * dz > SHELL_YIELD * SHELL_YIELD) {
                rest[n] += dx * SHELL_PLASTIC
                rest[n + 1] += dy * SHELL_PLASTIC
                rest[n + 2] += dz * SHELL_PLASTIC
            }
            n += 3
        }
    }

    /** Carry the mesh's vertices along with the cage. */
    private fun skin() {
        val stride = Mesh.FLOATS_PER_VERTEX
        var v = 0
        var slot = 0
        while (v < skinned.size) {
            val cell = cellOf[slot]
            val u = weightOf[slot * 3]
            val s = weightOf[slot * 3 + 1]
            val t = weightOf[slot * 3 + 2]

            var x = 0f; var y = 0f; var z = 0f
            for (c in 0 until 8) {
                val di = c and 1
                val dj = (c shr 1) and 1
                val dk = (c shr 2) and 1
                val w = (if (di == 1) u else 1f - u) *
                    (if (dj == 1) s else 1f - s) *
                    (if (dk == 1) t else 1f - t)
                if (w == 0f) continue
                val node = (cell + di + dj * nx + dk * nx * ny) * 3
                x += position[node] * w
                y += position[node + 1] * w
                z += position[node + 2] * w
            }
            skinned[v] = x; skinned[v + 1] = y; skinned[v + 2] = z
            slot++
            v += stride
        }
        Deform.recomputeNormals(skinned, indices)
    }

    /** How far the shape has been pushed off its original, at its worst point. */
    val damage: Float get() = Deform.worstDisplacement(base, vertices)

    /**
     * How much of the lattice has actually yielded.
     *
     * Counted from the constraints rather than measured off the mesh, so it
     * reports permanent damage and is not fooled by a panel caught mid-wobble.
     */
    val yielded: Int
        get() {
            var n = 0
            for (i in links.indices) {
                val l = links[i]
                if (abs(l.restLength - l.originalLength) > 1e-3f) n++
            }
            return n
        }

    private fun distance(a: FloatArray, ai: Int, b: FloatArray, bi: Int): Float {
        val dx = b[bi * 3] - a[ai * 3]
        val dy = b[bi * 3 + 1] - a[ai * 3 + 1]
        val dz = b[bi * 3 + 2] - a[ai * 3 + 2]
        return sqrt(dx * dx + dy * dy + dz * dz)
    }

    companion object {
        /**
         * Build a cage sized to the piece.
         *
         * The resolution follows the shape: a front wing is two metres wide
         * and three centimetres thick, and a cage with as many nodes through
         * its thickness as across its span would spend nearly all of them
         * where nothing can be seen. Every axis still gets three, though: with
         * only two there is no node between the faces for the material to
         * fold *around*, and a blow through a thin panel pushes both faces
         * equally and strains nothing.
         */
        fun around(mesh: Mesh, bounds: Mesh.Bounds): SoftCage {
            val size = Vec3(
                max(MIN_EXTENT, bounds.half.x * 2f),
                max(MIN_EXTENT, bounds.half.y * 2f),
                max(MIN_EXTENT, bounds.half.z * 2f)
            )
            val longest = max(size.x, max(size.y, size.z))
            fun nodes(extent: Float) =
                (2 + (RESOLUTION * extent / longest).toInt()).coerceIn(MIN_NODES, RESOLUTION + 1)
            return SoftCage(
                nodes(size.x), nodes(size.y), nodes(size.z),
                origin = bounds.centre - size * 0.5f,
                size = size,
                base = mesh.vertices,
                indices = mesh.indices
            )
        }

        /** Nodes along the piece's longest axis. Everything else scales down. */
        private const val RESOLUTION = 4

        /** Fewest nodes on any axis, however flat the piece is. */
        private const val MIN_NODES = 3

        /** A panel thinner than this still gets a cage a centimetre deep. */
        private const val MIN_EXTENT = 0.01f

        private const val ITERATIONS = 4

        /** How hard a distance constraint pulls, per iteration. */
        private const val STIFFNESS = 0.75f

        /** How hard the panel is pulled back toward its factory shape. */
        private const val SHELL = 0.018f

        /** Strain past which carbon stops springing back. */
        private const val YIELD = 0.02f

        /** How much of the excess strain is kept, per solve. */
        private const val PLASTIC = 0.85f

        /** How far a node can be pushed before the panel forgets where it was, m. */
        private const val SHELL_YIELD = 0.002f

        /** How much of that the shell's memory gives up, per solve. */
        private const val SHELL_PLASTIC = 0.05f

        private const val DAMPING = 1.2f

        /** Node speed below which the lattice is done moving, m/s. */
        private const val SLEEP = 0.02f
    }
}
