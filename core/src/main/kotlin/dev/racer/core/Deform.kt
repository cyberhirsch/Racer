package dev.racer.core

import kotlin.math.max
import kotlin.math.sqrt

/**
 * Mesh arithmetic shared by everything that bends bodywork.
 *
 * The deformation itself lives in [SoftCage] — this is the two pieces of it
 * that are about triangles rather than about physics: making a folded panel
 * catch the light like a folded panel, and saying how far one has been pushed
 * out of shape.
 */
object Deform {

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
    fun recomputeNormals(v: FloatArray, indices: IntArray) {
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

    /**
     * How far the shape has been pushed off its original, at its worst point.
     *
     * The average movement is taken out first, because a piece whose lattice
     * has been shoved bodily sideways has not been damaged at all — every
     * vertex has moved by the same amount and the shape is identical. Without
     * that subtraction a small part that takes a blow reaching right across it
     * reports a huge deformation for having been pushed, and the panel that
     * was genuinely crumpled next to it looks undamaged by comparison.
     */
    fun worstDisplacement(base: FloatArray, deformed: FloatArray): Float {
        val stride = Mesh.FLOATS_PER_VERTEX
        if (base.isEmpty()) return 0f
        var mx = 0f; var my = 0f; var mz = 0f
        var n = 0
        var i = 0
        while (i < base.size) {
            mx += deformed[i] - base[i]
            my += deformed[i + 1] - base[i + 1]
            mz += deformed[i + 2] - base[i + 2]
            n++
            i += stride
        }
        mx /= n; my /= n; mz /= n

        var worst = 0f
        i = 0
        while (i < base.size) {
            val dx = deformed[i] - base[i] - mx
            val dy = deformed[i + 1] - base[i + 1] - my
            val dz = deformed[i + 2] - base[i + 2] - mz
            worst = max(worst, sqrt(dx * dx + dy * dy + dz * dz))
            i += stride
        }
        return worst
    }
}
