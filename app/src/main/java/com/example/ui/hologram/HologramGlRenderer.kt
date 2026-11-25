package com.example.ui.hologram

import android.content.Context
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.opengl.Matrix
import android.os.Handler
import android.os.Looper
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import com.example.physiology.BodyRegion
import com.example.physiology.PhysiologyState
import com.example.physiology.SeverityLevel
import com.example.ui.theme.BioSkinPalette
import com.example.ui.theme.DashboardSkin
import com.example.ui.theme.paletteForSkin
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.math.tan

data class ProjectedAnchor(
    val screenOffset: Offset,
    val ndcDepth: Float,
)

class HologramGlRenderer(
    context: Context,
    private val onAnchorsProjected: (Map<BodyRegion, ProjectedAnchor>) -> Unit,
    private val onAutoYawUpdated: ((Float) -> Unit)? = null,
) : GLSurfaceView.Renderer {

    private val appContext = context.applicationContext

    @Volatile var physiologyState: PhysiologyState = PhysiologyState()
    @Volatile var skinPalette: BioSkinPalette = paletteForSkin(DashboardSkin.CLINICAL_OBSIDIAN)
    @Volatile var selectedRegion: BodyRegion? = null
    @Volatile var yawDegrees: Float = 0f
    @Volatile var pitchDegrees: Float = 5f
    @Volatile var zoomScale: Float = 1.0f
    @Volatile var pivotX: Float = HologramMeshBuilder.DEFAULT_PIVOT[0]
    @Volatile var pivotY: Float = HologramMeshBuilder.DEFAULT_PIVOT[1]
    @Volatile var pivotZ: Float = HologramMeshBuilder.DEFAULT_PIVOT[2]
    @Volatile var autoSpin: Boolean = false

    @Volatile private var anatomyPoints: List<FloatArray> = emptyList()

    private var program = 0
    private var uMvpHandle = 0
    private var uModelHandle = 0
    private var uColorHandle = 0
    private var uTimeHandle = 0
    private var uModeHandle = 0
    private var uAlphaHandle = 0
    private var uEyePosHandle = 0
    private var aPosHandle = 0
    private var aNormHandle = 0

    private lateinit var skinShell: GlMesh
    private lateinit var skeletonMesh: GlMesh
    private lateinit var pedestalMesh: GlMesh
    private lateinit var diaphragmMesh: GlMesh
    private lateinit var organMeshes: Map<BodyRegion, GlMesh>
    private lateinit var arterialMesh: GlMesh
    private lateinit var venousMesh: GlMesh
    private lateinit var vesselSplines: List<VesselSpline3D>
    private lateinit var sphereMesh: GlMesh

    private val mainHandler = Handler(Looper.getMainLooper())
    private var lastDispatchedAnchors: Map<BodyRegion, ProjectedAnchor> = emptyMap()
    private var lastAutoYawDispatchMs = 0L

    private val viewMatrix = FloatArray(16)
    private val projMatrix = FloatArray(16)
    private val modelMatrix = FloatArray(16)
    private val mvMatrix = FloatArray(16)
    private val mvpMatrix = FloatArray(16)
    private val scratchVec = FloatArray(4)
    private val scratchOut = FloatArray(4)

    private var viewportW = 1
    private var viewportH = 1
    private var startTimeMs = System.currentTimeMillis()
    private var lastFrameMs = startTimeMs

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        GLES20.glEnable(GLES20.GL_DEPTH_TEST)
        GLES20.glEnable(GLES20.GL_BLEND)
        GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA)

        skinShell = HologramMeshBuilder.loadBmeshAsset(appContext, "skin_shell")
        skeletonMesh = HologramMeshBuilder.loadBmeshAsset(appContext, "skeleton")
        pedestalMesh = HologramMeshBuilder.buildPedestalRingMesh()
        diaphragmMesh = HologramMeshBuilder.loadBmeshAsset(appContext, "diaphragm")
        arterialMesh = HologramMeshBuilder.loadBmeshAsset(appContext, "arteries")
        venousMesh = HologramMeshBuilder.loadBmeshAsset(appContext, "veins")

        organMeshes = mapOf(
            BodyRegion.BRAIN to HologramMeshBuilder.loadBmeshAsset(appContext, "brain_nervous"),
            BodyRegion.LUNGS to HologramMeshBuilder.loadBmeshAsset(appContext, "lungs_airway"),
            BodyRegion.HEART to HologramMeshBuilder.loadBmeshAsset(appContext, "heart"),
            BodyRegion.LIVER_PANCREAS to HologramMeshBuilder.loadBmeshAsset(appContext, "liver_pancreas"),
            BodyRegion.DIGESTIVE_GI to HologramMeshBuilder.loadBmeshAsset(appContext, "digestive_gi"),
            BodyRegion.KIDNEYS to HologramMeshBuilder.loadBmeshAsset(appContext, "kidneys_urinary"),
        )

        vesselSplines = HologramMeshBuilder.buildVesselSplines()
        sphereMesh = HologramMeshBuilder.buildUnitSphere(stacks = 6, slices = 8)

        // Build fast 3D anatomical surface + organ point cloud for ray picking and pivot clamping
        val pts = ArrayList<FloatArray>(950)
        pts.addAll(HologramMeshBuilder.samplePoints(skinShell, 460))
        organMeshes.values.forEach { pts.addAll(HologramMeshBuilder.samplePoints(it, 65)) }
        pts.addAll(HologramMeshBuilder.samplePoints(diaphragmMesh, 40))
        pts.addAll(HologramMeshBuilder.regionAnchors3D.values)
        anatomyPoints = pts

        program = compileProgram(VERTEX_SHADER, FRAGMENT_SHADER)
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        viewportW = width.coerceAtLeast(1)
        viewportH = height.coerceAtLeast(1)
        GLES20.glViewport(0, 0, viewportW, viewportH)
        val aspect = viewportW.toFloat() / viewportH.toFloat()
        Matrix.perspectiveM(projMatrix, 0, FOV_Y_DEG, aspect, 0.12f, 22f)
    }

    override fun onDrawFrame(gl: GL10?) {
        val now = System.currentTimeMillis()
        val dt = ((now - lastFrameMs) / 1000f).coerceIn(0f, 0.05f)
        lastFrameMs = now
        val elapsedSec = (now - startTimeMs) / 1000f

        if (autoSpin) {
            val nextYaw = (yawDegrees + dt * 26f) % 360f
            yawDegrees = nextYaw
            if (now - lastAutoYawDispatchMs > 60L) {
                lastAutoYawDispatchMs = now
                onAutoYawUpdated?.let { cb -> mainHandler.post { cb(nextYaw) } }
            }
        }

        val skin = skinPalette
        val bg = skin.background
        GLES20.glClearColor(bg.red, bg.green, bg.blue, 1f)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT or GLES20.GL_DEPTH_BUFFER_BIT)

        // Pivot-orbit camera: always rotates around (pivotX, pivotY, pivotZ) pinned at screen center
        val camDist = BASE_CAM_DIST / zoomScale.coerceIn(0.55f, 6.0f)
        Matrix.setLookAtM(
            viewMatrix, 0,
            0f, 0f, camDist,
            0f, 0f, 0f,
            0f, 1f, 0f,
        )

        Matrix.setIdentityM(modelMatrix, 0)
        Matrix.rotateM(modelMatrix, 0, pitchDegrees.coerceIn(-82f, 82f), 1f, 0f, 0f)
        Matrix.rotateM(modelMatrix, 0, yawDegrees, 0f, 1f, 0f)
        Matrix.translateM(modelMatrix, 0, -pivotX, -pivotY, -pivotZ)

        Matrix.multiplyMM(mvMatrix, 0, viewMatrix, 0, modelMatrix, 0)
        Matrix.multiplyMM(mvpMatrix, 0, projMatrix, 0, mvMatrix, 0)

        GLES20.glUseProgram(program)
        GLES20.glUniform1f(uTimeHandle, elapsedSec)
        GLES20.glUniform3f(uEyePosHandle, 0f, 0f, camDist)

        val state = physiologyState

        // 1. Solid Anatomical Inner Core (Vessels, Organs, Diaphragm) with depth write ON
        GLES20.glDepthMask(true)

        // Holographic Base Pedestal Ring & Anterior Indicator
        drawMesh(
            mesh = pedestalMesh,
            color = skin.primaryAccent,
            alpha = 0.46f,
            mode = 2,
            localModel = modelMatrix,
        )

        // Real BodyParts3D Arterial & Venous Vessel Trees
        val vesselsSelected = selectedRegion == BodyRegion.VESSELS
        val arteryColor = when {
            vesselsSelected -> Color.White
            state.spO2Pct < 90.0 -> skin.cautionColor
            else -> skin.arterialRed
        }
        drawMesh(
            mesh = arterialMesh,
            color = arteryColor,
            alpha = if (vesselsSelected) 0.98f else 0.90f,
            mode = 1,
            localModel = modelMatrix,
        )
        drawMesh(
            mesh = venousMesh,
            color = if (vesselsSelected) Color.White else skin.venousBlue,
            alpha = if (vesselsSelected) 0.95f else 0.82f,
            mode = 1,
            localModel = modelMatrix,
        )

        // Real BodyParts3D Organs with physiological pulse (Heart beats at HR, Lungs & Diaphragm breathe at RR)
        val hrFreq = (state.heartRateBpm / 60.0).toFloat() * 2f * PI.toFloat()
        val systole = sin(elapsedSec * hrFreq).coerceAtLeast(0f)
        val heartScale = 1f + 0.14f * systole * systole

        val respFreq = (state.respiratoryRateBpm / 60.0).toFloat() * 2f * PI.toFloat()
        val lungScale = 1f + 0.048f * sin(elapsedSec * respFreq)

        organMeshes.forEach { (region, mesh) ->
            val sev = state.regionTelemetry[region]?.severity ?: SeverityLevel.NORMAL
            val baseColor = when (sev) {
                SeverityLevel.NORMAL -> when (region) {
                    BodyRegion.HEART -> skin.arterialRed
                    BodyRegion.LUNGS -> skin.primaryAccent
                    BodyRegion.BRAIN -> skin.secondaryAccent
                    BodyRegion.LIVER_PANCREAS -> Color(0xFFD97757)
                    BodyRegion.DIGESTIVE_GI -> Color(0xFFE09F5A)
                    BodyRegion.KIDNEYS -> Color(0xFFC06C84)
                    BodyRegion.VESSELS -> skin.arterialRed
                }
                SeverityLevel.CAUTION -> skin.cautionColor
                SeverityLevel.CRITICAL -> skin.criticalColor
            }
            val isSel = selectedRegion == region
            val orgModel = FloatArray(16)
            when (region) {
                BodyRegion.HEART -> scaledAroundPoint(modelMatrix, mesh.centroid, heartScale, orgModel)
                BodyRegion.LUNGS -> scaledAroundPoint(modelMatrix, mesh.centroid, lungScale, orgModel)
                else -> System.arraycopy(modelMatrix, 0, orgModel, 0, 16)
            }
            drawMesh(
                mesh = mesh,
                color = if (isSel) Color.White else baseColor,
                alpha = if (isSel) 0.98f else 0.92f,
                mode = 1,
                localModel = orgModel,
            )
        }

        // Diaphragm breathing synchronously beneath lungs
        val diaphragmModel = FloatArray(16)
        scaledAroundPoint(modelMatrix, diaphragmMesh.centroid, 1f + 0.03f * sin(elapsedSec * respFreq), diaphragmModel)
        drawMesh(
            mesh = diaphragmMesh,
            color = skin.secondaryAccent,
            alpha = 0.65f,
            mode = 1,
            localModel = diaphragmModel,
        )

        // Animated 3D Running Blood Corpuscles streaming through arteries & veins
        val bloodSpeed = (state.cardiacOutputLMin / 4.9).toFloat().coerceIn(0.45f, 3.0f)
        val particleModel = FloatArray(16)
        vesselSplines.forEachIndexed { sIdx, spline ->
            val pts = spline.points
            if (pts.size < 2) return@forEachIndexed
            val segCount = pts.size - 1
            val numParticles = if (segCount >= 4) 6 else 4
            val pColor = if (spline.isArterial) Color.White else skin.primaryAccent
            val pRadius = spline.radius * 1.45f
            for (p in 0 until numParticles) {
                val u = ((elapsedSec * 0.36f * bloodSpeed + p.toFloat() / numParticles + sIdx * 0.17f) % 1f) * segCount
                val seg = u.toInt().coerceIn(0, segCount - 1)
                val t = (u - seg).coerceIn(0f, 1f)
                val a = pts[seg]
                val b = pts[seg + 1]
                val wx = a[0] + (b[0] - a[0]) * t
                val wy = a[1] + (b[1] - a[1]) * t
                val wz = a[2] + (b[2] - a[2]) * t

                Matrix.translateM(particleModel, 0, modelMatrix, 0, wx, wy, wz)
                Matrix.scaleM(particleModel, 0, pRadius, pRadius, pRadius)
                drawMesh(
                    mesh = sphereMesh,
                    color = pColor,
                    alpha = 0.96f,
                    mode = 3,
                    localModel = particleModel,
                )
            }
        }

        // Animated 3D Cause-and-Effect Cascade Signal Orbs between organs
        state.causalChain.forEachIndexed { idx, node ->
            val a = HologramMeshBuilder.regionAnchors3D[node.sourceRegion] ?: return@forEachIndexed
            val b = HologramMeshBuilder.regionAnchors3D[node.targetRegion] ?: return@forEachIndexed
            val t = (elapsedSec * 0.52f + idx * 0.21f) % 1f
            val inv = 1f - t
            val midX = (a[0] + b[0]) * 0.5f + if (idx % 2 == 0) 0.06f else -0.06f
            val midY = (a[1] + b[1]) * 0.5f
            val midZ = (a[2] + b[2]) * 0.5f + 0.06f
            val px = inv * inv * a[0] + 2f * inv * t * midX + t * t * b[0]
            val py = inv * inv * a[1] + 2f * inv * t * midY + t * t * b[1]
            val pz = inv * inv * a[2] + 2f * inv * t * midZ + t * t * b[2]

            val arcColor = when (node.severity) {
                SeverityLevel.NORMAL -> skin.primaryAccent
                SeverityLevel.CAUTION -> skin.cautionColor
                SeverityLevel.CRITICAL -> skin.criticalColor
            }
            Matrix.translateM(particleModel, 0, modelMatrix, 0, px, py, pz)
            Matrix.scaleM(particleModel, 0, 0.015f, 0.015f, 0.015f)
            drawMesh(sphereMesh, arcColor, alpha = 0.96f, mode = 3, localModel = particleModel)
        }

        // 2. Translucent Hologram Skeleton (77 Bones: Skull, 24 Ribs, Spine, Pelvis, Limbs) & Outer Body Shell
        // Drawn with depthMask(false) so inner organs stay vivid and never get occluded!
        GLES20.glDepthMask(false)
        drawMesh(
            mesh = skeletonMesh,
            color = skin.primaryAccent.copy(alpha = 1f),
            alpha = 0.28f,
            mode = 2,
            localModel = modelMatrix,
        )

        GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE) // Additive holographic glow
        drawMesh(
            mesh = skinShell,
            color = skin.primaryAccent,
            alpha = 0.56f,
            mode = 0,
            localModel = modelMatrix,
        )
        GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA)
        GLES20.glDepthMask(true)

        // 3. Project all 3D organ anchors into 2D Compose viewport coordinates
        val projected = mutableMapOf<BodyRegion, ProjectedAnchor>()
        HologramMeshBuilder.regionAnchors3D.forEach { (region, pos3d) ->
            scratchVec[0] = pos3d[0]
            scratchVec[1] = pos3d[1]
            scratchVec[2] = pos3d[2]
            scratchVec[3] = 1f
            Matrix.multiplyMV(scratchOut, 0, mvpMatrix, 0, scratchVec, 0)
            val w = scratchOut[3]
            if (w > 0.01f) {
                val ndcX = scratchOut[0] / w
                val ndcY = scratchOut[1] / w
                val ndcZ = scratchOut[2] / w
                val sx = (ndcX * 0.5f + 0.5f) * viewportW
                val sy = (1f - (ndcY * 0.5f + 0.5f)) * viewportH
                projected[region] = ProjectedAnchor(Offset(sx, sy), ndcZ)
            }
        }
        val movedEnough = lastDispatchedAnchors.size != projected.size || projected.any { (r, a) ->
            val prev = lastDispatchedAnchors[r] ?: return@any true
            abs(prev.screenOffset.x - a.screenOffset.x) > 1.2f ||
                abs(prev.screenOffset.y - a.screenOffset.y) > 1.2f
        }
        if (movedEnough) {
            lastDispatchedAnchors = projected
            val snapshot = projected.toMap()
            mainHandler.post { onAnchorsProjected(snapshot) }
        }
    }

    fun pickAnatomyPointAtScreen(screenX: Float, screenY: Float): FloatArray? {
        val pts = anatomyPoints
        if (pts.isEmpty() || viewportW <= 1 || viewportH <= 1) return null

        val camDist = BASE_CAM_DIST / zoomScale.coerceIn(0.55f, 6.0f)
        val vm = FloatArray(16)
        val mm = FloatArray(16)
        val pm = FloatArray(16)
        val mvm = FloatArray(16)
        val mvpm = FloatArray(16)
        Matrix.perspectiveM(pm, 0, FOV_Y_DEG, viewportW.toFloat() / viewportH.toFloat(), 0.12f, 22f)
        Matrix.setLookAtM(vm, 0, 0f, 0f, camDist, 0f, 0f, 0f, 0f, 1f, 0f)
        Matrix.setIdentityM(mm, 0)
        Matrix.rotateM(mm, 0, pitchDegrees.coerceIn(-82f, 82f), 1f, 0f, 0f)
        Matrix.rotateM(mm, 0, yawDegrees, 0f, 1f, 0f)
        Matrix.translateM(mm, 0, -pivotX, -pivotY, -pivotZ)
        Matrix.multiplyMM(mvm, 0, vm, 0, mm, 0)
        Matrix.multiplyMM(mvpm, 0, pm, 0, mvm, 0)

        val vIn = FloatArray(4)
        val vOut = FloatArray(4)
        val hitRadiusPx = 64f
        var bestWithinRadius: FloatArray? = null
        var bestWithinZ = Float.POSITIVE_INFINITY
        var closestAny: FloatArray? = null
        var closestAnyDist = Float.POSITIVE_INFINITY

        for (p in pts) {
            vIn[0] = p[0]; vIn[1] = p[1]; vIn[2] = p[2]; vIn[3] = 1f
            Matrix.multiplyMV(vOut, 0, mvpm, 0, vIn, 0)
            val w = vOut[3]
            if (w <= 0.01f) continue
            val ndcX = vOut[0] / w
            val ndcY = vOut[1] / w
            val ndcZ = vOut[2] / w
            val sx = (ndcX * 0.5f + 0.5f) * viewportW
            val sy = (1f - (ndcY * 0.5f + 0.5f)) * viewportH
            val d = hypot(sx - screenX, sy - screenY)
            if (d <= hitRadiusPx && ndcZ < bestWithinZ) {
                bestWithinZ = ndcZ
                bestWithinRadius = p
            }
            if (d < closestAnyDist) {
                closestAnyDist = d
                closestAny = p
            }
        }
        return (bestWithinRadius ?: closestAny)?.copyOf()
    }

    fun screenDeltaToModelDelta(dxPx: Float, dyPx: Float, currentZoom: Float): FloatArray {
        val h = viewportH.coerceAtLeast(1).toFloat()
        val camDist = BASE_CAM_DIST / currentZoom.coerceIn(0.55f, 6.0f)
        val tanHalfFov = tan(Math.toRadians(FOV_Y_DEG / 2.0)).toFloat()
        val worldPerPixel = (2f * camDist * tanHalfFov) / h

        // Drag right -> body moves right -> pivot shifts left (-X in view)
        // Drag down -> body moves down -> pivot shifts up (+Y in view)
        val vx = -dxPx * worldPerPixel
        val vy = dyPx * worldPerPixel

        val invR = FloatArray(16)
        Matrix.setIdentityM(invR, 0)
        Matrix.rotateM(invR, 0, -yawDegrees, 0f, 1f, 0f)
        Matrix.rotateM(invR, 0, -pitchDegrees.coerceIn(-82f, 82f), 1f, 0f, 0f)

        val out = FloatArray(4)
        Matrix.multiplyMV(out, 0, invR, 0, floatArrayOf(vx, vy, 0f, 0f), 0)
        return floatArrayOf(out[0], out[1], out[2])
    }

    fun clampPivotToAnatomy(px: Float, py: Float, pz: Float): FloatArray {
        val cy = py.coerceIn(-0.96f, 0.93f)
        // Interior anatomical torso / head / neck volume allows smooth panning across internal organs
        val insideHead = cy in 0.72f..0.94f &&
            (px * px) / (0.068f * 0.068f) + ((pz + 0.025f) * (pz + 0.025f)) / (0.092f * 0.092f) <= 1f
        val insideTorso = cy in -0.05f..0.60f &&
            (px * px) / (0.145f * 0.145f) + ((pz + 0.015f) * (pz + 0.015f)) / (0.095f * 0.095f) <= 1f
        if (insideHead || insideTorso) {
            return floatArrayOf(px, cy, pz)
        }

        val pts = anatomyPoints
        if (pts.isEmpty()) {
            return floatArrayOf(px.coerceIn(-0.14f, 0.14f), cy, pz.coerceIn(-0.09f, 0.09f))
        }
        var nearest = pts[0]
        var bestDistSq = Float.POSITIVE_INFINITY
        for (p in pts) {
            val dx = p[0] - px
            val dy = p[1] - cy
            val dz = p[2] - pz
            val d2 = dx * dx + dy * dy + dz * dz
            if (d2 < bestDistSq) {
                bestDistSq = d2
                nearest = p
            }
        }
        // Within skin thickness of any real anatomical surface (arms, legs, shoulders, skull, pelvis)
        return if (bestDistSq <= 0.028f * 0.028f) {
            floatArrayOf(px, cy, pz)
        } else {
            floatArrayOf(nearest[0], nearest[1], nearest[2])
        }
    }

    private fun scaledAroundPoint(base: FloatArray, anchor: FloatArray, scale: Float, out: FloatArray) {
        val temp = FloatArray(16)
        Matrix.translateM(temp, 0, base, 0, anchor[0], anchor[1], anchor[2])
        Matrix.scaleM(out, 0, temp, 0, scale, scale, scale)
        Matrix.translateM(out, 0, -anchor[0], -anchor[1], -anchor[2])
    }

    private fun drawMesh(
        mesh: GlMesh,
        color: Color,
        alpha: Float,
        mode: Int,
        localModel: FloatArray,
    ) {
        val localMv = FloatArray(16)
        val localMvp = FloatArray(16)
        Matrix.multiplyMM(localMv, 0, viewMatrix, 0, localModel, 0)
        Matrix.multiplyMM(localMvp, 0, projMatrix, 0, localMv, 0)

        GLES20.glUniformMatrix4fv(uMvpHandle, 1, false, localMvp, 0)
        GLES20.glUniformMatrix4fv(uModelHandle, 1, false, localModel, 0)
        GLES20.glUniform3f(uColorHandle, color.red, color.green, color.blue)
        GLES20.glUniform1f(uAlphaHandle, alpha)
        GLES20.glUniform1i(uModeHandle, mode)

        GLES20.glEnableVertexAttribArray(aPosHandle)
        GLES20.glVertexAttribPointer(aPosHandle, 3, GLES20.GL_FLOAT, false, 0, mesh.vertexBuffer)

        GLES20.glEnableVertexAttribArray(aNormHandle)
        GLES20.glVertexAttribPointer(aNormHandle, 3, GLES20.GL_FLOAT, false, 0, mesh.normalBuffer)

        val ib = mesh.indexBuffer
        if (ib != null && mesh.indexCount > 0) {
            ib.position(0)
            GLES20.glDrawElements(GLES20.GL_TRIANGLES, mesh.indexCount, GLES20.GL_UNSIGNED_SHORT, ib)
        } else {
            GLES20.glDrawArrays(GLES20.GL_TRIANGLES, 0, mesh.vertexCount)
        }

        GLES20.glDisableVertexAttribArray(aPosHandle)
        GLES20.glDisableVertexAttribArray(aNormHandle)
    }

    private fun compileProgram(vSrc: String, fSrc: String): Int {
        val vs = compileShader(GLES20.GL_VERTEX_SHADER, vSrc)
        val fs = compileShader(GLES20.GL_FRAGMENT_SHADER, fSrc)
        val prog = GLES20.glCreateProgram()
        GLES20.glAttachShader(prog, vs)
        GLES20.glAttachShader(prog, fs)
        GLES20.glLinkProgram(prog)

        uMvpHandle = GLES20.glGetUniformLocation(prog, "uMVPMatrix")
        uModelHandle = GLES20.glGetUniformLocation(prog, "uModelMatrix")
        uColorHandle = GLES20.glGetUniformLocation(prog, "uColor")
        uTimeHandle = GLES20.glGetUniformLocation(prog, "uTime")
        uModeHandle = GLES20.glGetUniformLocation(prog, "uMode")
        uAlphaHandle = GLES20.glGetUniformLocation(prog, "uAlpha")
        uEyePosHandle = GLES20.glGetUniformLocation(prog, "uEyePos")
        aPosHandle = GLES20.glGetAttribLocation(prog, "aPosition")
        aNormHandle = GLES20.glGetAttribLocation(prog, "aNormal")
        return prog
    }

    private fun compileShader(type: Int, src: String): Int {
        val shader = GLES20.glCreateShader(type)
        GLES20.glShaderSource(shader, src)
        GLES20.glCompileShader(shader)
        return shader
    }

    private companion object {
        const val FOV_Y_DEG = 42f
        const val BASE_CAM_DIST = 2.60f

        const val VERTEX_SHADER = """
            attribute vec3 aPosition;
            attribute vec3 aNormal;
            uniform mat4 uMVPMatrix;
            uniform mat4 uModelMatrix;
            varying vec3 vWorldPos;
            varying vec3 vWorldNormal;
            varying float vLocalY;

            void main() {
                vec4 wp = uModelMatrix * vec4(aPosition, 1.0);
                vWorldPos = wp.xyz;
                vWorldNormal = normalize((uModelMatrix * vec4(aNormal, 0.0)).xyz);
                vLocalY = aPosition.y;
                gl_Position = uMVPMatrix * vec4(aPosition, 1.0);
            }
        """

        const val FRAGMENT_SHADER = """
            precision mediump float;
            varying vec3 vWorldPos;
            varying vec3 vWorldNormal;
            varying float vLocalY;
            uniform vec3 uColor;
            uniform float uTime;
            uniform float uAlpha;
            uniform int uMode;
            uniform vec3 uEyePos;

            void main() {
                vec3 N = normalize(vWorldNormal);
                vec3 V = normalize(uEyePos - vWorldPos);
                float ndotv = abs(dot(N, V));
                float fresnel = pow(1.0 - clamp(ndotv, 0.0, 1.0), 2.1);

                if (uMode == 0) {
                    // Hologram outer silhouette: Fresnel rim + horizontal scanlines + grid
                    float scan = 0.72 + 0.28 * sin(vLocalY * 120.0 - uTime * 4.5);
                    float sweep = smoothstep(0.0, 0.14, abs(fract(vLocalY * 0.60 - uTime * 0.22) - 0.5));
                    float grid = step(0.92, fract(vLocalY * 20.0)) * 0.16;
                    float alpha = clamp((fresnel * 0.65 + 0.03 + grid) * scan * uAlpha, 0.02, 0.72);
                    vec3 col = uColor * (0.65 + fresnel * 1.15 + (1.0 - sweep) * 0.45);
                    gl_FragColor = vec4(col, alpha);
                } else if (uMode == 1) {
                    // 3D Organs & Vessels: volumetric shaded 3D surface + rim highlight
                    vec3 L = normalize(vec3(0.4, 0.7, 1.0));
                    float diff = max(dot(N, L), 0.28);
                    vec3 col = uColor * (0.48 + 0.72 * diff) + vec3(0.24) * fresnel;
                    gl_FragColor = vec4(col, uAlpha);
                } else if (uMode == 2) {
                    // Holographic skeleton (cranium, spine, ribs, pelvis, limbs)
                    vec3 L = normalize(vec3(0.3, 0.6, 1.0));
                    float diff = max(dot(N, L), 0.35);
                    float scan = 0.84 + 0.16 * sin(vLocalY * 80.0 - uTime * 2.5);
                    gl_FragColor = vec4(uColor * (0.75 * diff + fresnel * 0.55), uAlpha * scan);
                } else {
                    // Glowing blood corpuscles & causal signal packets
                    gl_FragColor = vec4(uColor * 1.25, uAlpha);
                }
            }
        """
    }
}
