package com.example.ui.hologram

import android.content.Context
import com.example.physiology.BodyRegion
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.nio.ShortBuffer
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

data class GlMesh(
    val vertexBuffer: FloatBuffer,
    val normalBuffer: FloatBuffer,
    val indexBuffer: ShortBuffer? = null,
    val vertexCount: Int,
    val indexCount: Int = 0,
    val centroid: FloatArray = floatArrayOf(0f, 0f, 0f),
)

data class VesselSpline3D(
    val points: List<FloatArray>, // list of [x, y, z]
    val isArterial: Boolean,
    val radius: Float,
)

object HologramMeshBuilder {

    val DEFAULT_PIVOT = floatArrayOf(0f, -0.04f, 0f)

    // Exact normalized BodyParts3D anatomical anchor coordinates (Y spans -1.03 feet to +0.96 crown)
    val regionAnchors3D: Map<BodyRegion, FloatArray> = mapOf(
        BodyRegion.BRAIN to floatArrayOf(0.00f, 0.87f, -0.02f),
        BodyRegion.LUNGS to floatArrayOf(-0.078f, 0.51f, 0.00f),
        BodyRegion.HEART to floatArrayOf(0.026f, 0.468f, 0.022f),
        BodyRegion.LIVER_PANCREAS to floatArrayOf(-0.052f, 0.32f, 0.025f),
        BodyRegion.DIGESTIVE_GI to floatArrayOf(0.020f, 0.145f, 0.045f),
        BodyRegion.KIDNEYS to floatArrayOf(0.062f, 0.245f, -0.048f),
        BodyRegion.VESSELS to floatArrayOf(-0.075f, -0.02f, 0.015f),
    )

    fun samplePoints(mesh: GlMesh, maxSamples: Int): List<FloatArray> {
        val count = mesh.vertexCount
        if (count <= 0) return emptyList()
        val buf = mesh.vertexBuffer.duplicate()
        buf.position(0)
        val arr = FloatArray(count * 3)
        buf.get(arr)
        val step = (count / maxSamples.coerceAtLeast(1)).coerceAtLeast(1)
        val out = ArrayList<FloatArray>(minOf(count, maxSamples) + 1)
        var i = 0
        while (i < count) {
            out.add(floatArrayOf(arr[i * 3], arr[i * 3 + 1], arr[i * 3 + 2]))
            i += step
        }
        return out
    }

    fun loadBmeshAsset(context: Context, name: String): GlMesh {
        val bytes = context.assets.open("anatomy/$name.bmesh").use { it.readBytes() }
        val bb = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        val magic = bb.int
        require(magic == 0x424D5348) { "Invalid .bmesh magic for $name" }
        val vertexCount = bb.int
        val indexCount = bb.int

        val posBytes = vertexCount * 12
        val normBytes = vertexCount * 12
        val idxBytes = indexCount * 2

        val vb = ByteBuffer.allocateDirect(posBytes).order(ByteOrder.nativeOrder())
        vb.put(bytes, 12, posBytes)
        vb.position(0)
        val vertexBuffer = vb.asFloatBuffer()

        val nb = ByteBuffer.allocateDirect(normBytes).order(ByteOrder.nativeOrder())
        nb.put(bytes, 12 + posBytes, normBytes)
        nb.position(0)
        val normalBuffer = nb.asFloatBuffer()

        val ib = ByteBuffer.allocateDirect(idxBytes).order(ByteOrder.nativeOrder())
        ib.put(bytes, 12 + posBytes + normBytes, idxBytes)
        ib.position(0)
        val indexBuffer = ib.asShortBuffer()

        var cx = 0f
        var cy = 0f
        var cz = 0f
        if (vertexCount > 0) {
            val posArr = FloatArray(vertexCount * 3)
            vertexBuffer.get(posArr)
            vertexBuffer.position(0)
            for (i in 0 until vertexCount) {
                cx += posArr[i * 3]
                cy += posArr[i * 3 + 1]
                cz += posArr[i * 3 + 2]
            }
            cx /= vertexCount
            cy /= vertexCount
            cz /= vertexCount
        }

        return GlMesh(
            vertexBuffer = vertexBuffer,
            normalBuffer = normalBuffer,
            indexBuffer = indexBuffer,
            vertexCount = vertexCount,
            indexCount = indexCount,
            centroid = floatArrayOf(cx, cy, cz),
        )
    }

    // Blood corpuscle stream paths aligned directly with BodyParts3D arteries & veins
    fun buildVesselSplines(): List<VesselSpline3D> {
        return listOf(
            // Ascending Aorta -> Carotids -> Brain
            VesselSpline3D(
                points = listOf(
                    floatArrayOf(0.015f, 0.47f, 0.020f),
                    floatArrayOf(0.005f, 0.56f, 0.010f),
                    floatArrayOf(0.022f, 0.69f, -0.005f),
                    floatArrayOf(0.024f, 0.85f, -0.010f),
                ),
                isArterial = true,
                radius = 0.0062f,
            ),
            VesselSpline3D(
                points = listOf(
                    floatArrayOf(0.005f, 0.56f, 0.010f),
                    floatArrayOf(-0.022f, 0.69f, -0.005f),
                    floatArrayOf(-0.024f, 0.85f, -0.010f),
                ),
                isArterial = true,
                radius = 0.0058f,
            ),
            // Descending Aorta -> Iliacs -> Right/Left Femoral/Tibial
            VesselSpline3D(
                points = listOf(
                    floatArrayOf(0.008f, 0.52f, -0.020f),
                    floatArrayOf(0.004f, 0.35f, -0.028f),
                    floatArrayOf(0.000f, 0.16f, -0.024f),
                    floatArrayOf(-0.058f, 0.03f, 0.005f),
                    floatArrayOf(-0.088f, -0.25f, 0.012f),
                    floatArrayOf(-0.084f, -0.65f, -0.010f),
                    floatArrayOf(-0.082f, -0.94f, 0.010f),
                ),
                isArterial = true,
                radius = 0.0065f,
            ),
            VesselSpline3D(
                points = listOf(
                    floatArrayOf(0.000f, 0.16f, -0.024f),
                    floatArrayOf(0.058f, 0.03f, 0.005f),
                    floatArrayOf(0.088f, -0.25f, 0.012f),
                    floatArrayOf(0.084f, -0.65f, -0.010f),
                    floatArrayOf(0.082f, -0.94f, 0.010f),
                ),
                isArterial = true,
                radius = 0.0062f,
            ),
            // Left & Right Arm Subclavian -> Brachial -> Radial Arteries
            VesselSpline3D(
                points = listOf(
                    floatArrayOf(0.005f, 0.56f, 0.010f),
                    floatArrayOf(-0.175f, 0.54f, -0.020f),
                    floatArrayOf(-0.245f, 0.34f, -0.035f),
                    floatArrayOf(-0.285f, 0.11f, -0.010f),
                    floatArrayOf(-0.315f, -0.06f, 0.015f),
                ),
                isArterial = true,
                radius = 0.0050f,
            ),
            VesselSpline3D(
                points = listOf(
                    floatArrayOf(0.005f, 0.56f, 0.010f),
                    floatArrayOf(0.175f, 0.54f, -0.020f),
                    floatArrayOf(0.245f, 0.34f, -0.035f),
                    floatArrayOf(0.285f, 0.11f, -0.010f),
                    floatArrayOf(0.315f, -0.06f, 0.015f),
                ),
                isArterial = true,
                radius = 0.0050f,
            ),
            // Venous Return: Femoral -> IVC -> Right Atrium
            VesselSpline3D(
                points = listOf(
                    floatArrayOf(-0.095f, -0.93f, 0.002f),
                    floatArrayOf(-0.095f, -0.52f, -0.015f),
                    floatArrayOf(-0.065f, 0.02f, -0.005f),
                    floatArrayOf(-0.022f, 0.22f, -0.018f),
                    floatArrayOf(-0.015f, 0.45f, 0.005f),
                ),
                isArterial = false,
                radius = 0.0058f,
            ),
            VesselSpline3D(
                points = listOf(
                    floatArrayOf(0.095f, -0.93f, 0.002f),
                    floatArrayOf(0.095f, -0.52f, -0.015f),
                    floatArrayOf(0.065f, 0.02f, -0.005f),
                    floatArrayOf(-0.022f, 0.22f, -0.018f),
                ),
                isArterial = false,
                radius = 0.0055f,
            ),
            // Brain -> Jugular -> SVC -> Heart
            VesselSpline3D(
                points = listOf(
                    floatArrayOf(-0.030f, 0.84f, -0.015f),
                    floatArrayOf(-0.028f, 0.67f, -0.012f),
                    floatArrayOf(-0.018f, 0.48f, 0.005f),
                ),
                isArterial = false,
                radius = 0.0050f,
            ),
        )
    }

    fun buildUnitSphere(stacks: Int = 6, slices: Int = 8): GlMesh {
        val verts = mutableListOf<Float>()
        val norms = mutableListOf<Float>()
        addEllipsoid(verts, norms, 0f, 0f, 0f, 1f, 1f, 1f, stacks, slices)
        return toGlMesh(verts, norms)
    }

    fun buildPedestalRingMesh(): GlMesh {
        val verts = mutableListOf<Float>()
        val norms = mutableListOf<Float>()
        val radii = listOf(0.28f to 0.0040f, 0.42f to 0.0028f)
        val segments = 48
        radii.forEach { (r, tubeR) ->
            val pts = (0..segments).map { i ->
                val a = (i.toFloat() / segments) * 2f * PI.toFloat()
                floatArrayOf(cos(a) * r, -1.045f, sin(a) * r, tubeR)
            }
            addTubePath(verts, norms, pts, slices = 6, closeEnds = false)
        }
        // Anterior orientation tick (+Z front marker)
        addTubePath(
            verts, norms,
            listOf(
                floatArrayOf(0f, -1.045f, 0.28f, 0.005f),
                floatArrayOf(0f, -1.045f, 0.48f, 0.005f),
            ),
            slices = 6,
            closeEnds = true,
        )
        return toGlMesh(verts, norms)
    }

    private fun addTubePath(
        verts: MutableList<Float>,
        norms: MutableList<Float>,
        pts: List<FloatArray>,
        slices: Int,
        closeEnds: Boolean,
    ) {
        if (pts.size < 2) return
        val rings = Array(pts.size) { Array(slices) { FloatArray(3) } }
        val ringNormals = Array(pts.size) { Array(slices) { FloatArray(3) } }

        for (i in pts.indices) {
            val prev = pts[(i - 1).coerceAtLeast(0)]
            val next = pts[(i + 1).coerceAtMost(pts.lastIndex)]
            val tan = normalize(floatArrayOf(next[0] - prev[0], next[1] - prev[1], next[2] - prev[2]))
            val ref = if (kotlin.math.abs(tan[1]) < 0.9f) floatArrayOf(0f, 1f, 0f) else floatArrayOf(1f, 0f, 0f)
            val right = normalize(cross(tan, ref))
            val up = normalize(cross(right, tan))
            val r = pts[i][3]

            for (s in 0 until slices) {
                val theta = (s.toFloat() / slices) * 2f * PI.toFloat()
                val c = cos(theta); val sn = sin(theta)
                val nx = right[0] * c + up[0] * sn
                val ny = right[1] * c + up[1] * sn
                val nz = right[2] * c + up[2] * sn
                ringNormals[i][s] = floatArrayOf(nx, ny, nz)
                rings[i][s] = floatArrayOf(pts[i][0] + nx * r, pts[i][1] + ny * r, pts[i][2] + nz * r)
            }
        }

        for (i in 0 until pts.size - 1) {
            for (s in 0 until slices) {
                val sNext = (s + 1) % slices
                pushTri(
                    verts, norms,
                    rings[i][s], rings[i][sNext], rings[i + 1][sNext],
                    ringNormals[i][s], ringNormals[i][sNext], ringNormals[i + 1][sNext],
                )
                pushTri(
                    verts, norms,
                    rings[i][s], rings[i + 1][sNext], rings[i + 1][s],
                    ringNormals[i][s], ringNormals[i + 1][sNext], ringNormals[i + 1][s],
                )
            }
        }
        if (closeEnds && pts.size >= 2) {
            val endCap = pts.last()
            val cPt = floatArrayOf(endCap[0], endCap[1], endCap[2])
            val nUp = floatArrayOf(0f, -1f, 0f)
            for (s in 0 until slices) {
                val sNext = (s + 1) % slices
                pushTri(verts, norms, cPt, rings.last()[s], rings.last()[sNext], nUp, ringNormals.last()[s], ringNormals.last()[sNext])
            }
        }
    }

    private fun addEllipsoid(
        verts: MutableList<Float>,
        norms: MutableList<Float>,
        cx: Float, cy: Float, cz: Float,
        rx: Float, ry: Float, rz: Float,
        stacks: Int, slices: Int,
    ) {
        for (i in 0 until stacks) {
            val phi1 = (i.toFloat() / stacks) * PI.toFloat() - PI.toFloat() / 2f
            val phi2 = ((i + 1).toFloat() / stacks) * PI.toFloat() - PI.toFloat() / 2f
            for (j in 0 until slices) {
                val th1 = (j.toFloat() / slices) * 2f * PI.toFloat()
                val th2 = ((j + 1).toFloat() / slices) * 2f * PI.toFloat()

                fun point(phi: Float, th: Float): Pair<FloatArray, FloatArray> {
                    val nx = cos(phi) * cos(th)
                    val ny = sin(phi)
                    val nz = cos(phi) * sin(th)
                    return floatArrayOf(cx + nx * rx, cy + ny * ry, cz + nz * rz) to normalize(floatArrayOf(nx, ny, nz))
                }

                val (p1, n1) = point(phi1, th1)
                val (p2, n2) = point(phi1, th2)
                val (p3, n3) = point(phi2, th2)
                val (p4, n4) = point(phi2, th1)

                pushTri(verts, norms, p1, p2, p3, n1, n2, n3)
                pushTri(verts, norms, p1, p3, p4, n1, n3, n4)
            }
        }
    }

    private fun pushTri(
        verts: MutableList<Float>,
        norms: MutableList<Float>,
        p1: FloatArray, p2: FloatArray, p3: FloatArray,
        n1: FloatArray, n2: FloatArray, n3: FloatArray,
    ) {
        verts.add(p1[0]); verts.add(p1[1]); verts.add(p1[2])
        verts.add(p2[0]); verts.add(p2[1]); verts.add(p2[2])
        verts.add(p3[0]); verts.add(p3[1]); verts.add(p3[2])

        norms.add(n1[0]); norms.add(n1[1]); norms.add(n1[2])
        norms.add(n2[0]); norms.add(n2[1]); norms.add(n2[2])
        norms.add(n3[0]); norms.add(n3[1]); norms.add(n3[2])
    }

    private fun cross(a: FloatArray, b: FloatArray): FloatArray = floatArrayOf(
        a[1] * b[2] - a[2] * b[1],
        a[2] * b[0] - a[0] * b[2],
        a[0] * b[1] - a[1] * b[0],
    )

    private fun normalize(v: FloatArray): FloatArray {
        val len = sqrt(v[0] * v[0] + v[1] * v[1] + v[2] * v[2]).coerceAtLeast(1e-5f)
        return floatArrayOf(v[0] / len, v[1] / len, v[2] / len)
    }

    private fun toGlMesh(verts: List<Float>, norms: List<Float>): GlMesh {
        val vb = ByteBuffer.allocateDirect(verts.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer().apply {
            put(verts.toFloatArray())
            position(0)
        }
        val nb = ByteBuffer.allocateDirect(norms.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer().apply {
            put(norms.toFloatArray())
            position(0)
        }
        return GlMesh(vb, nb, indexBuffer = null, vertexCount = verts.size / 3)
    }
}
