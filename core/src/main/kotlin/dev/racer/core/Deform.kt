package dev.racer.core

import kotlin.math.max
import kotlin.math.sqrt

/**
 * Permanent damage to a panel.
 *
 * A dent is a single blow: where it landed, which way it went, and how hard.
 * Panels keep the list of every dent they have taken, and their shape is that
 * list applied to the shape they left the factory with. Deformation is
 * therefore *plastic* — carbon fibre does not spring back, and neither does
 * this. Hit the same corner twice and it folds twice as far.
 *
 * Held in the piece's own local coordinates, so a piece that has broken off
 * and is tumbling still dents correctly wherever it lands.
 */
data class Dent(
    /** Where the blow landed, in the piece's local frame. */
    val at: Vec3,
    /** Which way the material was pushed. Unit length. */
    val direction: Vec3,
    /** How far the material at the centre of the blow moved, metres. */
    val depth: Float,
    /** How far out from the centre the damage reaches, metres. */
    val reach: Float
)

/**
 * Applies dents to a mesh.
 *
 * The alternative — a mass-spring lattice relaxed every frame — was tried and
 * is not worth it here. It costs a solver iteration per spring per frame for
 * something the player sees for about four seconds, it needs tuning to stay
 * stable at large time steps, and once the springs have yielded and settled it
 * arrives at very nearly this shape anyway. What matters visually is that the
 * panel folds *inwards, locally, and stays folded*, which is what a field of
 * blows applied to the original shape gives you, for one pass over the
 * vertices each time something is actually hit.
 */
object Deform {

    /**
     * The undamaged mesh plus every dent it has taken.
     *
     * Returns a fresh vertex array; [base] is never modified, because it is
     * the only record of the original shape and every dent is measured from
     * it.
     */
    fun apply(base: FloatArray, indices: IntArray, dents: List<Dent>): FloatArray {
        val out = base.copyOf()
        if (dents.isEmpty()) return out

        val stride = Mesh.FLOATS_PER_VERTEX
        var i = 0
        while (i < out.size) {
            var px = out[i]; var py = out[i + 1]; var pz = out[i + 2]
            for (d in dents) {
                val dx = px - d.at.x; val dy = py - d.at.y; val dz = pz - d.at.z
                val distance = sqrt(dx * dx + dy * dy + dz * dz)
                if (distance >= d.reach) continue

                // Smooth falloff, so the fold blends into the panel around it
                // rather than punching a cone through it.
                val t = 1f - distance / d.reach
                val w = t * t * (3f - 2f * t)

                px += d.direction.x * d.depth * w
                py += d.direction.y * d.depth * w
                pz += d.direction.z * d.depth * w

                // Material has to go somewhere. Pulling the surface in toward
                // the axis of the blow as well as along it is what makes this
                // read as crumpling rather than as a dent stamped by a press.
                val along = dx * d.direction.x + dy * d.direction.y + dz * d.direction.z
                val ox = dx - d.direction.x * along
                val oy = dy - d.direction.y * along
                val oz = dz - d.direction.z * along
                val pucker = w * 0.35f
                px -= ox * pucker; py -= oy * pucker; pz -= oz * pucker
            }
            out[i] = px; out[i + 1] = py; out[i + 2] = pz
            i += stride
        }

        recomputeNormals(out, indices)
        return out
    }

    /**
     * Rebuild the normals from the deformed positions.
     *
     * Without this a crumpled panel keeps the shading of the smooth one and
     * the damage is nearly invisible — the silhouette changes but nothing
     * catches the light differently, which is most of what tells the eye a
     * surface is bent. Area-weighted face normals, accumulated per vertex, so
     * flat-shaded and smooth-shaded parts of the same mesh both come out
     * right.
     */
    private fun recomputeNormals(v: FloatArray, indices: IntArray) {
        val stride = Mesh.FLOATS_PER_VERTEX
        var i = 0
        while (i < v.size) { v[i + 3] = 0f; v[i + 4] = 0f; v[i + 5] = 0f; i += stride }

        var t = 0
        while (t + 2 < indices.size) {
            val a = indices[t] * stride
            val b = indices[t + 1] * stride
            val c = indices[t + 2] * stride

            val ux = v[b] - v[a]; val uy = v[b + 1] - v[a + 1]; val uz = v[b + 2] - v[a + 2]
            val wx = v[c] - v[a]; val wy = v[c + 1] - v[a + 1]; val wz = v[c + 2] - v[a + 2]
            // Not normalised: the cross product's length is twice the
            // triangle's area, which is exactly the weighting we want.
            val nx = uy * wz - uz * wy
            val ny = uz * wx - ux * wz
            val nz = ux * wy - uy * wx

            for (o in intArrayOf(a, b, c)) {
                v[o + 3] += nx; v[o + 4] += ny; v[o + 5] += nz
            }
            t += 3
        }

        i = 0
        while (i < v.size) {
            val nx = v[i + 3]; val ny = v[i + 4]; val nz = v[i + 5]
            val l = sqrt(nx * nx + ny * ny + nz * nz)
            if (l > 1e-9f) {
                v[i + 3] = nx / l; v[i + 4] = ny / l; v[i + 5] = nz / l
            } else {
                // A degenerate triangle, or a vertex no triangle uses. Point it
                // up rather than leaving a zero normal, which the shader would
                // turn into a black facet.
                v[i + 3] = 0f; v[i + 4] = 1f; v[i + 5] = 0f
            }
            i += stride
        }
    }

    /** How far the shape has been pushed off its original, at its worst point. */
    fun worstDisplacement(base: FloatArray, deformed: FloatArray): Float {
        val stride = Mesh.FLOATS_PER_VERTEX
        var worst = 0f
        var i = 0
        while (i < base.size) {
            val dx = deformed[i] - base[i]
            val dy = deformed[i + 1] - base[i + 1]
            val dz = deformed[i + 2] - base[i + 2]
            worst = max(worst, sqrt(dx * dx + dy * dy + dz * dz))
            i += stride
        }
        return worst
    }
}
