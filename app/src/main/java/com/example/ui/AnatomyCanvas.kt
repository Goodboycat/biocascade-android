package com.example.ui

import android.opengl.GLSurfaceView
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.example.physiology.BodyRegion
import com.example.physiology.PhysiologyState
import com.example.physiology.RegionTelemetry
import com.example.physiology.SeverityLevel
import com.example.ui.hologram.HologramGlRenderer
import com.example.ui.hologram.HologramMeshBuilder
import com.example.ui.hologram.ProjectedAnchor
import com.example.ui.theme.BioSkinPalette
import com.example.ui.theme.LocalBioSkin
import com.example.ui.theme.TelemetryNumericStyle
import kotlin.math.hypot
import kotlin.math.roundToInt

@Composable
fun InteractiveAnatomyStage(
    state: PhysiologyState,
    selectedRegion: BodyRegion?,
    onSelectRegion: (BodyRegion?) -> Unit,
    modifier: Modifier = Modifier,
) {
    val skin = LocalBioSkin.current
    val context = LocalContext.current
    val density = LocalDensity.current
    val lifecycleOwner = LocalLifecycleOwner.current

    var zoomScale by remember { mutableFloatStateOf(1.0f) }
    var pivotX by remember { mutableFloatStateOf(HologramMeshBuilder.DEFAULT_PIVOT[0]) }
    var pivotY by remember { mutableFloatStateOf(HologramMeshBuilder.DEFAULT_PIVOT[1]) }
    var pivotZ by remember { mutableFloatStateOf(HologramMeshBuilder.DEFAULT_PIVOT[2]) }
    var yawDegrees by remember { mutableFloatStateOf(0f) }
    var pitchDegrees by remember { mutableFloatStateOf(5f) }
    var projectedAnchors by remember { mutableStateOf<Map<BodyRegion, ProjectedAnchor>>(emptyMap()) }

    val renderer = remember {
        HologramGlRenderer(
            context = context,
            onAnchorsProjected = { anchors -> projectedAnchors = anchors },
        )
    }

    // Push live physiology telemetry & pivot-orbit camera parameters safely into the GL renderer
    SideEffect {
        renderer.physiologyState = state
        renderer.skinPalette = skin
        renderer.selectedRegion = selectedRegion
        renderer.yawDegrees = yawDegrees
        renderer.pitchDegrees = pitchDegrees
        renderer.zoomScale = zoomScale
        renderer.pivotX = pivotX
        renderer.pivotY = pivotY
        renderer.pivotZ = pivotZ
    }

    var glViewRef by remember { mutableStateOf<GLSurfaceView?>(null) }

    DisposableEffect(lifecycleOwner, glViewRef) {
        val view = glViewRef
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> view?.onResume()
                Lifecycle.Event.ON_PAUSE -> view?.onPause()
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            view?.onPause()
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    // Containers automatically appear when zoomed in (>= 1.32x) or when an organ is tapped
    val showAllContainers = zoomScale >= 1.32f

    BoxWithConstraints(
        modifier = modifier
            .fillMaxSize()
            .background(skin.background)
            // CRITICAL: Initial pass intercepts 2-finger pinch-to-zoom/pan AND 1-finger 360° pivot orbit
            // BEFORE any child container, pinning rotation to the inspected anatomical spot so
            // users never drift off into black void.
            .pointerInput(Unit) {
                val touchSlop = viewConfiguration.touchSlop
                var singleDragAccum = 0f
                var pinchTarget: FloatArray? = null
                awaitPointerEventScope {
                    while (true) {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                        val pressed = event.changes.filter { it.pressed }
                        if (pressed.isEmpty()) {
                            singleDragAccum = 0f
                            pinchTarget = null
                        } else if (pressed.size >= 2) {
                            singleDragAccum = 0f
                            if (pinchTarget == null) {
                                val centroid = event.calculateCentroid(useCurrent = true)
                                if (centroid != Offset.Unspecified) {
                                    pinchTarget = renderer.pickAnatomyPointAtScreen(centroid.x, centroid.y)
                                }
                            }
                            val zoomChange = event.calculateZoom()
                            val panChange = event.calculatePan()
                            if (zoomChange != 1f || panChange != Offset.Zero) {
                                val oldZoom = zoomScale
                                val newScale = (oldZoom * zoomChange).coerceIn(0.55f, 6.0f)

                                var nx = pivotX
                                var ny = pivotY
                                var nz = pivotZ

                                // 1. Rotate 2-finger screen pan into current body-local space
                                if (panChange != Offset.Zero) {
                                    val deltaModel = renderer.screenDeltaToModelDelta(
                                        dxPx = panChange.x,
                                        dyPx = panChange.y,
                                        currentZoom = newScale,
                                    )
                                    nx += deltaModel[0]
                                    ny += deltaModel[1]
                                    nz += deltaModel[2]
                                }

                                // 2. Automatic anatomical target pinning while zooming in toward pinch centroid
                                val target = pinchTarget
                                if (zoomChange > 1.003f && target != null) {
                                    val pull = ((zoomChange - 1f) * 2.4f).coerceIn(0f, 0.38f)
                                    nx += (target[0] - nx) * pull
                                    ny += (target[1] - ny) * pull
                                    nz += (target[2] - nz) * pull
                                } else if (zoomChange < 0.997f && newScale < 1.50f) {
                                    // Smoothly ease back to full-body center when zooming back out
                                    val def = HologramMeshBuilder.DEFAULT_PIVOT
                                    if (newScale <= 1.02f) {
                                        nx = def[0]; ny = def[1]; nz = def[2]
                                    } else {
                                        val recenter = ((1.50f - newScale) / 0.50f).coerceIn(0f, 0.45f)
                                        nx += (def[0] - nx) * recenter
                                        ny += (def[1] - ny) * recenter
                                        nz += (def[2] - nz) * recenter
                                    }
                                }

                                // 3. Clamp pivot strictly inside the real human anatomical volume
                                val clamped = renderer.clampPivotToAnatomy(nx, ny, nz)
                                zoomScale = newScale
                                pivotX = clamped[0]
                                pivotY = clamped[1]
                                pivotZ = clamped[2]

                                renderer.zoomScale = newScale
                                renderer.pivotX = clamped[0]
                                renderer.pivotY = clamped[1]
                                renderer.pivotZ = clamped[2]

                                event.changes.forEach { it.consume() }
                            }
                        } else if (pressed.size == 1) {
                            pinchTarget = null
                            val change = pressed.first()
                            val delta = change.position - change.previousPosition
                            val stepDist = hypot(delta.x, delta.y)
                            singleDragAccum += stepDist
                            if (singleDragAccum > touchSlop * 0.65f && stepDist > 0.25f) {
                                // Orbit strictly around the currently pinned anatomical pivot (never drifts into black)
                                yawDegrees = ((yawDegrees + delta.x * 0.45f) % 360f + 360f) % 360f
                                pitchDegrees = (pitchDegrees + delta.y * 0.35f).coerceIn(-82f, 82f)
                                renderer.yawDegrees = yawDegrees
                                renderer.pitchDegrees = pitchDegrees
                                change.consume()
                            }
                        }
                    }
                }
            }
            .pointerInput(zoomScale) {
                detectTapGestures { tapOffset ->
                    val hitRadius = with(density) { 48.dp.toPx() } * zoomScale.coerceAtMost(1.8f)
                    val hit = projectedAnchors.entries
                        .mapNotNull { (region, anchor) ->
                            val dist = hypot(anchor.screenOffset.x - tapOffset.x, anchor.screenOffset.y - tapOffset.y)
                            if (dist <= hitRadius) Triple(region, dist, anchor.ndcDepth) else null
                        }
                        .minWithOrNull(compareBy<Triple<BodyRegion, Float, Float>> { (it.second / 18f).roundToInt() }.thenBy { it.third })
                        ?.first
                    if (hit != null) {
                        onSelectRegion(if (selectedRegion == hit) null else hit)
                    } else {
                        onSelectRegion(null)
                    }
                }
            }
            .testTag("anatomy_stage"),
    ) {
        val stageW = constraints.maxWidth.toFloat()
        val stageH = constraints.maxHeight.toFloat()

        // True 3D OpenGL ES 2.0 Hologram Surface
        AndroidView(
            factory = { ctx ->
                GLSurfaceView(ctx).apply {
                    setEGLContextClientVersion(2)
                    setEGLConfigChooser(8, 8, 8, 8, 16, 0)
                    preserveEGLContextOnPause = true
                    setRenderer(renderer)
                    renderMode = GLSurfaceView.RENDERMODE_CONTINUOUSLY
                    glViewRef = this
                }
            },
            modifier = Modifier.fillMaxSize(),
        )

        // Holographic HUD callout leader lines & organ anchor markers
        Canvas(modifier = Modifier.fillMaxSize()) {
            val cardWidthPx = with(density) { 166.dp.toPx() }
            val padPx = with(density) { 12.dp.toPx() }
            projectedAnchors.forEach { (region, anchor) ->
                val telemetry = state.regionTelemetry[region] ?: return@forEach
                val sevColor = severityColor(telemetry.severity, skin)
                val isSelected = selectedRegion == region
                val showContainer = showAllContainers || isSelected

                if (isSelected || showAllContainers) {
                    drawCircle(
                        color = sevColor.copy(alpha = if (isSelected) 0.45f else 0.25f),
                        radius = if (isSelected) 16f else 10f,
                        center = anchor.screenOffset,
                    )
                    drawCircle(
                        color = if (isSelected) Color.White else sevColor,
                        radius = if (isSelected) 5.5f else 3.5f,
                        center = anchor.screenOffset,
                    )
                }

                if (showContainer) {
                    val pos = computeContainerPosition(
                        anchor = anchor.screenOffset,
                        stageW = stageW,
                        stageH = stageH,
                        cardWidthPx = cardWidthPx,
                        padPx = padPx,
                    )
                    val targetEdgeX = if (pos.placedRight) pos.x else pos.x + cardWidthPx
                    val targetEdgeY = pos.y + with(density) { 28.dp.toPx() }
                    drawLine(
                        color = sevColor.copy(alpha = if (isSelected) 0.85f else 0.45f),
                        start = anchor.screenOffset,
                        end = Offset(targetEdgeX, targetEdgeY),
                        strokeWidth = if (isSelected) 2.5f else 1.5f,
                        pathEffect = PathEffect.dashPathEffect(floatArrayOf(8f, 6f), 0f),
                    )
                }
            }
        }

        // Zoom-adaptive floating symptom & numerical info containers anchored to 3D projections
        val cardWidthPx = with(density) { 166.dp.toPx() }
        val padPx = with(density) { 12.dp.toPx() }
        BodyRegion.entries.forEach { region ->
            val telemetry = state.regionTelemetry[region]
            val anchor = projectedAnchors[region]
            val visible = telemetry != null && anchor != null && (showAllContainers || selectedRegion == region)
            val pos = if (anchor != null) {
                computeContainerPosition(anchor.screenOffset, stageW, stageH, cardWidthPx, padPx)
            } else {
                ContainerPos(0f, 0f, true)
            }

            AnimatedVisibility(
                visible = visible,
                enter = fadeIn() + scaleIn(initialScale = 0.88f),
                exit = fadeOut() + scaleOut(targetScale = 0.88f),
                modifier = Modifier.offset { IntOffset(pos.x.roundToInt(), pos.y.roundToInt()) },
            ) {
                if (telemetry != null) {
                    RegionFloatingContainer(
                        telemetry = telemetry,
                        isSelected = selectedRegion == region,
                        skin = skin,
                        onTap = { onSelectRegion(if (selectedRegion == region) null else region) },
                    )
                }
            }
        }

        // Subtle automatic gesture hint when zoomed out with no organ selected (zero buttons)
        AnimatedVisibility(
            visible = !showAllContainers && selectedRegion == null,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(bottom = 12.dp, end = 12.dp),
        ) {
            Surface(
                color = skin.surface.copy(alpha = 0.78f),
                shape = RoundedCornerShape(10.dp),
                modifier = Modifier.border(1.dp, skin.borderSubtle, RoundedCornerShape(10.dp)),
            ) {
                Text(
                    text = "Pinch to zoom & pin • Drag to orbit 360°",
                    style = MaterialTheme.typography.labelSmall,
                    color = skin.textSecondary,
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                )
            }
        }
    }
}

private data class ContainerPos(val x: Float, val y: Float, val placedRight: Boolean)

private fun computeContainerPosition(
    anchor: Offset,
    stageW: Float,
    stageH: Float,
    cardWidthPx: Float,
    padPx: Float,
): ContainerPos {
    val placeRight = anchor.x <= stageW * 0.52f
    val rawX = if (placeRight) {
        anchor.x + padPx * 1.4f
    } else {
        anchor.x - cardWidthPx - padPx * 1.4f
    }
    val x = rawX.coerceIn(10f, (stageW - cardWidthPx - 10f).coerceAtLeast(10f))
    val y = (anchor.y - padPx * 2.5f).coerceIn(14f, (stageH - 210f).coerceAtLeast(24f))
    return ContainerPos(x, y, placeRight)
}

private fun severityColor(severity: SeverityLevel, skin: BioSkinPalette): Color = when (severity) {
    SeverityLevel.NORMAL -> skin.normalColor
    SeverityLevel.CAUTION -> skin.cautionColor
    SeverityLevel.CRITICAL -> skin.criticalColor
}

@Composable
private fun RegionFloatingContainer(
    telemetry: RegionTelemetry,
    isSelected: Boolean,
    skin: BioSkinPalette,
    onTap: () -> Unit,
) {
    val sevColor = severityColor(telemetry.severity, skin)
    Surface(
        color = skin.surface.copy(alpha = 0.88f),
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier
            .width(166.dp)
            .border(
                width = if (isSelected) 1.5.dp else 1.dp,
                color = if (isSelected) sevColor else sevColor.copy(alpha = 0.55f),
                shape = RoundedCornerShape(12.dp),
            )
            .clickable(onClick = onTap)
            .testTag("container_${telemetry.region.name.lowercase()}"),
    ) {
        Column(modifier = Modifier.padding(8.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    text = telemetry.region.displayName,
                    style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                    color = skin.textPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .clip(CircleShape)
                        .background(sevColor),
                )
            }
            Text(
                text = "${telemetry.primaryNumber} • ${telemetry.secondaryNumber}",
                style = TelemetryNumericStyle.copy(
                    fontSize = MaterialTheme.typography.labelMedium.fontSize,
                    fontWeight = FontWeight.Bold,
                ),
                color = sevColor,
            )
            Text(
                text = "${telemetry.rateText} | ${telemetry.etaText}",
                style = MaterialTheme.typography.labelSmall,
                color = skin.textSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            telemetry.symptoms.take(2).forEach { sym ->
                Text(
                    text = "• $sym",
                    style = MaterialTheme.typography.bodySmall,
                    color = skin.textPrimary.copy(alpha = 0.9f),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}
