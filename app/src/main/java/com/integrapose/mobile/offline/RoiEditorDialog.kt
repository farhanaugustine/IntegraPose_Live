package com.integrapose.mobile.offline

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import android.media.MediaMetadataRetriever
import android.net.Uri
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.integrapose.mobile.analytics.BehaviorRoi
import com.integrapose.mobile.inference.RoiAnnotationPalette
import com.integrapose.mobile.ui.AdaptiveModal
import com.integrapose.mobile.ui.keepFocusedFieldVisible
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

@Composable
fun RoiEditorDialog(
    videoUri: Uri? = null,
    previewBitmap: Bitmap? = null,
    existingRois: List<BehaviorRoi>,
    onDismiss: () -> Unit,
    onUseRois: (List<BehaviorRoi>) -> Unit
) {
    val context = LocalContext.current
    var preview by remember(videoUri, previewBitmap) {
        mutableStateOf(previewBitmap)
    }
    var errorText by remember(videoUri, previewBitmap) {
        mutableStateOf<String?>(null)
    }
    var workingRois by remember(videoUri, previewBitmap) {
        mutableStateOf(existingRois)
    }
    var canvasSize by remember { mutableStateOf(IntSize.Zero) }
    var selectedRoiId by remember(videoUri, previewBitmap) {
        mutableStateOf(existingRois.firstOrNull()?.id)
    }
    var activeGesture by remember { mutableStateOf<RoiEditGesture?>(null) }
    var dragStart by remember { mutableStateOf<Offset?>(null) }
    var dragEnd by remember { mutableStateOf<Offset?>(null) }
    var zoomScale by remember(videoUri, previewBitmap) {
        mutableFloatStateOf(MIN_ROI_ZOOM)
    }
    var panOffset by remember(videoUri, previewBitmap) {
        mutableStateOf(Offset.Zero)
    }
    val imeVisible = WindowInsets.ime.getBottom(LocalDensity.current) > 0
    val setZoom: (Float) -> Unit = { requestedZoom ->
        val bitmap = preview
        val nextZoom = requestedZoom.coerceIn(MIN_ROI_ZOOM, MAX_ROI_ZOOM)
        zoomScale = nextZoom
        panOffset = if (bitmap == null) {
            Offset.Zero
        } else {
            clampRoiViewportPan(
                canvasSize,
                bitmap.width,
                bitmap.height,
                nextZoom,
                panOffset
            )
        }
    }

    LaunchedEffect(videoUri, previewBitmap) {
        if (videoUri != null) {
            runCatching { loadOrientedPreview(context, videoUri) }
                .onSuccess { preview = it }
                .onFailure {
                    errorText = it.message ?: "Could not load a video preview."
                }
        } else if (previewBitmap == null) {
            errorText = "No ROI reference frame is available."
        }
    }
    DisposableEffect(preview, videoUri) {
        val active = preview
        onDispose {
            if (videoUri != null && active !== previewBitmap) {
                active?.recycle()
            }
        }
    }

    AdaptiveModal(onDismiss = onDismiss) {
        Card(
            modifier = Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .padding(10.dp),
            colors = CardDefaults.cardColors(containerColor = Color(0xFF162231))
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(
                    "Define dwell-time regions",
                    style = MaterialTheme.typography.headlineSmall,
                    color = Color(0xFFE8EFF9)
                )
                if (!imeVisible) {
                    Text(
                        "One finger draws, moves, or resizes a region. Pinch with two fingers to zoom, and move both fingers to pan.",
                        color = Color(0xFFC8D6E8)
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Text(
                            "Zoom ${(zoomScale * 100f).roundToInt()}%",
                            modifier = Modifier.weight(1f),
                            color = Color(0xFFA8F0D3)
                        )
                        OutlinedButton(
                            onClick = { setZoom(zoomScale - ZOOM_STEP) },
                            enabled = zoomScale > MIN_ROI_ZOOM
                        ) {
                            Text("-")
                        }
                        OutlinedButton(
                            onClick = { setZoom(zoomScale + ZOOM_STEP) },
                            enabled = zoomScale < MAX_ROI_ZOOM
                        ) {
                            Text("+")
                        }
                        OutlinedButton(
                            onClick = {
                                panOffset = Offset.Zero
                                setZoom(MIN_ROI_ZOOM)
                            },
                            enabled = zoomScale > MIN_ROI_ZOOM ||
                                panOffset != Offset.Zero
                        ) {
                            Text("Reset")
                        }
                    }
                }

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .heightIn(min = if (imeVisible) 96.dp else 240.dp)
                        .onSizeChanged { newSize ->
                            canvasSize = newSize
                            preview?.let { bitmap ->
                                panOffset = clampRoiViewportPan(
                                    newSize,
                                    bitmap.width,
                                    bitmap.height,
                                    zoomScale,
                                    panOffset
                                )
                            }
                        }
                ) {
                    val bitmap = preview
                    if (bitmap == null && errorText == null) {
                        CircularProgressIndicator(Modifier.align(Alignment.Center))
                    } else if (bitmap != null) {
                        RoiDrawingSurface(
                            bitmap = bitmap,
                            canvasSize = canvasSize,
                            zoomScale = zoomScale,
                            panOffset = panOffset,
                            rois = workingRois,
                            selectedRoiId = selectedRoiId,
                            dragStart = dragStart,
                            dragEnd = dragEnd,
                            onTransform = { centroid, gesturePan, gestureZoom ->
                                val previousZoom = zoomScale
                                val safeGestureZoom = gestureZoom.takeIf {
                                    it.isFinite() && it > 0f
                                } ?: 1f
                                val nextZoom = (previousZoom * safeGestureZoom)
                                    .coerceIn(MIN_ROI_ZOOM, MAX_ROI_ZOOM)
                                val canvasCenter = Offset(
                                    canvasSize.width * 0.5f,
                                    canvasSize.height * 0.5f
                                )
                                val ratio = nextZoom / previousZoom
                                val focalAdjustment =
                                    (centroid - canvasCenter - panOffset) *
                                        (1f - ratio)
                                val proposedPan = panOffset + gesturePan +
                                    focalAdjustment
                                zoomScale = nextZoom
                                panOffset = clampRoiViewportPan(
                                    canvasSize,
                                    bitmap.width,
                                    bitmap.height,
                                    nextZoom,
                                    proposedPan
                                )
                            },
                            onDragStart = { point, toleranceX, toleranceY ->
                                val selected = workingRois.firstOrNull {
                                    it.id == selectedRoiId
                                }
                                val selectedCorner = selected?.cornerNear(
                                    point.x,
                                    point.y,
                                    toleranceX,
                                    toleranceY
                                )
                                if (selected != null && selectedCorner != null) {
                                    activeGesture = RoiEditGesture(
                                        kind = RoiEditKind.RESIZE,
                                        roiId = selected.id,
                                        start = point,
                                        original = selected,
                                        corner = selectedCorner
                                    )
                                } else {
                                    val hit = workingRois.asReversed().firstOrNull {
                                        it.containsNormalized(point.x, point.y)
                                    }
                                    if (hit != null) {
                                        selectedRoiId = hit.id
                                        activeGesture = RoiEditGesture(
                                            kind = RoiEditKind.MOVE,
                                            roiId = hit.id,
                                            start = point,
                                            original = hit
                                        )
                                    } else {
                                        selectedRoiId = null
                                        activeGesture = RoiEditGesture(
                                            kind = RoiEditKind.CREATE,
                                            start = point
                                        )
                                        dragStart = point
                                        dragEnd = point
                                    }
                                }
                            },
                            onDrag = { point ->
                                val gesture = activeGesture ?: return@RoiDrawingSurface
                                when (gesture.kind) {
                                    RoiEditKind.CREATE -> dragEnd = point
                                    RoiEditKind.MOVE -> {
                                        val original = gesture.original
                                            ?: return@RoiDrawingSurface
                                        workingRois = workingRois.map { roi ->
                                            if (roi.id == gesture.roiId) {
                                                original.movedBy(
                                                    point.x - gesture.start.x,
                                                    point.y - gesture.start.y
                                                )
                                            } else {
                                                roi
                                            }
                                        }
                                    }
                                    RoiEditKind.RESIZE -> {
                                        val original = gesture.original
                                            ?: return@RoiDrawingSurface
                                        val corner = gesture.corner
                                            ?: return@RoiDrawingSurface
                                        workingRois = workingRois.map { roi ->
                                            if (roi.id == gesture.roiId) {
                                                original.resizedFrom(
                                                    corner,
                                                    point.x,
                                                    point.y
                                                )
                                            } else {
                                                roi
                                            }
                                        }
                                    }
                                }
                            },
                            onDragCancelled = {
                                val gesture = activeGesture
                                val original = gesture?.original
                                if (
                                    original != null &&
                                    gesture.kind != RoiEditKind.CREATE
                                ) {
                                    workingRois = workingRois.map { roi ->
                                        if (roi.id == gesture.roiId) original else roi
                                    }
                                }
                                activeGesture = null
                                dragStart = null
                                dragEnd = null
                            },
                            onDragFinished = {
                                val gesture = activeGesture
                                val start = dragStart
                                val end = dragEnd
                                if (
                                    gesture?.kind == RoiEditKind.CREATE &&
                                    start != null &&
                                    end != null
                                ) {
                                    val left = minOf(start.x, end.x)
                                    val right = maxOf(start.x, end.x)
                                    val top = minOf(start.y, end.y)
                                    val bottom = maxOf(start.y, end.y)
                                    if (
                                        right - left >= MINIMUM_ROI_SIZE &&
                                        bottom - top >= MINIMUM_ROI_SIZE
                                    ) {
                                        val number = workingRois.size + 1
                                        val newRoi = BehaviorRoi(
                                            id = "roi_${System.nanoTime()}",
                                            name = "ROI $number",
                                            left = left,
                                            top = top,
                                            right = right,
                                            bottom = bottom
                                        )
                                        workingRois = workingRois + newRoi
                                        selectedRoiId = newRoi.id
                                    }
                                }
                                activeGesture = null
                                dragStart = null
                                dragEnd = null
                            }
                        )
                    }
                }

                errorText?.let { Text(it, color = Color(0xFFFFB2B2)) }
                if (workingRois.isEmpty()) {
                    Text(
                        "No regions yet. Drag on the frame to add one.",
                        color = Color(0xFFFFD2A6)
                    )
                } else {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = if (imeVisible) 170.dp else 190.dp)
                            .verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        workingRois.forEachIndexed { index, roi ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { selectedRoiId = roi.id },
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                OutlinedTextField(
                                    value = roi.name,
                                    onValueChange = { name ->
                                        workingRois = workingRois.toMutableList().also {
                                            it[index] = roi.copy(name = name)
                                        }
                                    },
                                    label = { Text("Region ${index + 1} name") },
                                    singleLine = true,
                                    modifier = Modifier
                                        .weight(1f)
                                        .keepFocusedFieldVisible()
                                )
                                IconButton(
                                    onClick = {
                                        workingRois = workingRois.filterIndexed {
                                                itemIndex, _ -> itemIndex != index
                                        }
                                        if (selectedRoiId == roi.id) {
                                            selectedRoiId =
                                                workingRois.firstOrNull()?.id
                                        }
                                    }
                                ) {
                                    Icon(
                                        Icons.Default.Delete,
                                        contentDescription = "Delete ${roi.name}"
                                    )
                                }
                            }
                        }
                    }
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedButton(
                        onClick = {
                            workingRois = emptyList()
                            selectedRoiId = null
                        },
                        enabled = workingRois.isNotEmpty(),
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("Clear")
                    }
                    OutlinedButton(
                        onClick = onDismiss,
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("Cancel")
                    }
                    Button(
                        onClick = {
                            onUseRois(workingRois.map(BehaviorRoi::sanitized))
                        },
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("Use ${workingRois.size}")
                    }
                }
            }
        }
    }
}

@Composable
private fun RoiDrawingSurface(
    bitmap: Bitmap,
    canvasSize: IntSize,
    zoomScale: Float,
    panOffset: Offset,
    rois: List<BehaviorRoi>,
    selectedRoiId: String?,
    dragStart: Offset?,
    dragEnd: Offset?,
    onTransform: (centroid: Offset, pan: Offset, zoom: Float) -> Unit,
    onDragStart: (Offset, Float, Float) -> Unit,
    onDrag: (Offset) -> Unit,
    onDragCancelled: () -> Unit,
    onDragFinished: () -> Unit
) {
    val currentZoomScale by rememberUpdatedState(zoomScale)
    val currentPanOffset by rememberUpdatedState(panOffset)
    val currentOnTransform by rememberUpdatedState(onTransform)
    val currentOnDragStart by rememberUpdatedState(onDragStart)
    val currentOnDrag by rememberUpdatedState(onDrag)
    val currentOnDragCancelled by rememberUpdatedState(onDragCancelled)
    val currentOnDragFinished by rememberUpdatedState(onDragFinished)
    Box(modifier = Modifier.fillMaxSize().clipToBounds()) {
        Image(
            bitmap = bitmap.asImageBitmap(),
            contentDescription = "First video frame for ROI selection",
            contentScale = ContentScale.Fit,
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    scaleX = zoomScale
                    scaleY = zoomScale
                    translationX = panOffset.x
                    translationY = panOffset.y
                }
        )
        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(bitmap, canvasSize) {
                    awaitEachGesture {
                        val firstDown = awaitFirstDown(requireUnconsumed = false)
                        val initialRect = roiImageRect(
                            canvasSize,
                            bitmap.width,
                            bitmap.height,
                            currentZoomScale,
                            currentPanOffset
                        )
                        val start = toRoiNormalized(
                            firstDown.position,
                            initialRect,
                            clamp = false
                        )
                        var editing = start != null
                        var transforming = false
                        if (start != null) {
                            val radiusPx = HANDLE_TOUCH_RADIUS_DP.dp.toPx()
                            currentOnDragStart(
                                start,
                                radiusPx / initialRect.width.coerceAtLeast(1f),
                                radiusPx / initialRect.height.coerceAtLeast(1f)
                            )
                        }

                        while (true) {
                            val event = awaitPointerEvent()
                            val pressed = event.changes.filter { it.pressed }
                            if (pressed.size >= 2) {
                                if (!transforming) {
                                    transforming = true
                                    if (editing) {
                                        currentOnDragCancelled()
                                        editing = false
                                    }
                                }
                                currentOnTransform(
                                    event.calculateCentroid(useCurrent = false),
                                    event.calculatePan(),
                                    event.calculateZoom()
                                )
                                event.changes.forEach { it.consume() }
                            } else if (!transforming && editing) {
                                val change = event.changes.firstOrNull {
                                    it.id == firstDown.id
                                }
                                if (change != null && change.pressed) {
                                    val currentRect = roiImageRect(
                                        canvasSize,
                                        bitmap.width,
                                        bitmap.height,
                                        currentZoomScale,
                                        currentPanOffset
                                    )
                                    toRoiNormalized(
                                        change.position,
                                        currentRect,
                                        clamp = true
                                    )?.let(currentOnDrag)
                                    change.consume()
                                }
                            }

                            if (event.changes.none { it.pressed }) {
                                if (editing) currentOnDragFinished()
                                break
                            }
                        }
                    }
                }
        ) {
            val rect = roiImageRect(
                IntSize(size.width.toInt(), size.height.toInt()),
                bitmap.width,
                bitmap.height,
                zoomScale,
                panOffset
            )
            rois.forEach { roi ->
                drawRoiRect(
                    roi = roi,
                    imageRect = rect,
                    color = Color(RoiAnnotationPalette.argbFor(roi.id)),
                    selected = roi.id == selectedRoiId
                )
            }
            val start = dragStart
            val end = dragEnd
            if (start != null && end != null) {
                drawRoiRect(
                    roi = BehaviorRoi(
                        id = "draft",
                        name = "draft",
                        left = minOf(start.x, end.x),
                        top = minOf(start.y, end.y),
                        right = maxOf(start.x, end.x),
                        bottom = maxOf(start.y, end.y)
                    ),
                    imageRect = rect,
                    color = Color.White,
                    selected = false
                )
            }
        }
    }
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawRoiRect(
    roi: BehaviorRoi,
    imageRect: Rect,
    color: Color,
    selected: Boolean
) {
    val left = imageRect.left + roi.left * imageRect.width
    val top = imageRect.top + roi.top * imageRect.height
    val right = imageRect.left + roi.right * imageRect.width
    val bottom = imageRect.top + roi.bottom * imageRect.height
    drawRect(
        color = color.copy(alpha = 0.18f),
        topLeft = Offset(left, top),
        size = androidx.compose.ui.geometry.Size(right - left, bottom - top)
    )
    drawRect(
        color = color,
        topLeft = Offset(left, top),
        size = androidx.compose.ui.geometry.Size(right - left, bottom - top),
        style = Stroke(width = if (selected) 3.dp.toPx() else 2.dp.toPx())
    )
    if (selected) {
        listOf(
            Offset(left, top),
            Offset(right, top),
            Offset(right, bottom),
            Offset(left, bottom)
        ).forEach { center ->
            drawCircle(Color(0xFF101820), radius = 10.dp.toPx(), center = center)
            drawCircle(Color.White, radius = 7.dp.toPx(), center = center)
            drawCircle(color, radius = 4.dp.toPx(), center = center)
        }
    }
}

private suspend fun loadOrientedPreview(context: Context, uri: Uri): Bitmap =
    withContext(Dispatchers.IO) {
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(context, uri)
            val raw = requireNotNull(
                retriever.getFrameAtTime(0L, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
            ) {
                "The selected video has no readable first frame."
            }
            val rotation = retriever
                .extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)
                ?.toFloatOrNull()
                ?: 0f
            val oriented = if (rotation % 360f == 0f) {
                raw
            } else {
                Bitmap.createBitmap(
                    raw,
                    0,
                    0,
                    raw.width,
                    raw.height,
                    Matrix().apply { postRotate(rotation) },
                    true
                ).also { if (it !== raw) raw.recycle() }
            }
            val largestEdge = maxOf(oriented.width, oriented.height)
            if (largestEdge <= MAX_PREVIEW_EDGE) {
                oriented
            } else {
                val scale = MAX_PREVIEW_EDGE.toFloat() / largestEdge
                Bitmap.createScaledBitmap(
                    oriented,
                    (oriented.width * scale).toInt().coerceAtLeast(2),
                    (oriented.height * scale).toInt().coerceAtLeast(2),
                    true
                ).also { if (it !== oriented) oriented.recycle() }
            }
        } finally {
            retriever.release()
        }
    }

private enum class RoiEditKind {
    CREATE,
    MOVE,
    RESIZE
}

private data class RoiEditGesture(
    val kind: RoiEditKind,
    val roiId: String? = null,
    val start: Offset,
    val original: BehaviorRoi? = null,
    val corner: RoiCorner? = null
)

private const val HANDLE_TOUCH_RADIUS_DP = 28
private const val ZOOM_STEP = 0.5f
private const val MAX_PREVIEW_EDGE = 1_280
