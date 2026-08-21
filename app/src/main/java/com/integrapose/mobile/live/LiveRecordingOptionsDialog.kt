package com.integrapose.mobile.live

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.integrapose.mobile.analytics.BoutSettings
import com.integrapose.mobile.analytics.RoiAnalyticsSettings
import com.integrapose.mobile.analytics.RoiAnchorMode
import com.integrapose.mobile.model.ModelType
import com.integrapose.mobile.ui.AdaptiveModal
import com.integrapose.mobile.ui.keepFocusedFieldVisible

data class LiveRecordingOptions(
    val plannedDurationMinutes: Int = 0,
    val rawVideo: Boolean = true,
    val annotatedVideo: Boolean = true,
    val drawRoisOnAnnotatedVideo: Boolean = false,
    val detectionCsv: Boolean = true,
    val classBouts: Boolean = false,
    val roiVisits: Boolean = false,
    val boutSettings: BoutSettings = BoutSettings(),
    val roiSettings: RoiAnalyticsSettings = RoiAnalyticsSettings()
) {
    val hasOutput: Boolean
        get() = rawVideo || annotatedVideo || detectionCsv ||
            classBouts || roiVisits
}

internal fun LiveRecordingOptions.requiresRawMaster(
    postprocessAnnotatedVideo: Boolean =
        com.integrapose.mobile.BuildConfig.POSTPROCESS_LIVE_ANNOTATED_VIDEO
): Boolean =
    rawVideo || (
        postprocessAnnotatedVideo &&
            annotatedVideo
        )

internal fun shouldFinalizeMetricsConcurrently(
    postprocessAnnotatedVideo: Boolean
): Boolean = postprocessAnnotatedVideo

@Composable
fun LiveRecordingOptionsDialog(
    modelType: ModelType,
    options: LiveRecordingOptions,
    roiCount: Int,
    onDefineRois: () -> Unit,
    onDismiss: () -> Unit,
    onUseOptions: (LiveRecordingOptions) -> Unit
) {
    var rawVideo by remember(options) { mutableStateOf(options.rawVideo) }
    var plannedDurationMinutes by remember(options) {
        mutableStateOf(options.plannedDurationMinutes.toString())
    }
    var annotatedVideo by remember(options) {
        mutableStateOf(options.annotatedVideo)
    }
    var drawRoisOnAnnotatedVideo by remember(options, roiCount) {
        mutableStateOf(options.drawRoisOnAnnotatedVideo && roiCount > 0)
    }
    var detectionCsv by remember(options) {
        mutableStateOf(options.detectionCsv)
    }
    var classBouts by remember(options) { mutableStateOf(options.classBouts) }
    var roiVisits by remember(options, roiCount) {
        mutableStateOf(options.roiVisits && roiCount > 0)
    }
    var anchorMode by remember(options, modelType) {
        mutableStateOf(
            if (modelType == ModelType.POSE) {
                options.roiSettings.anchorMode
            } else {
                RoiAnchorMode.BOUNDING_BOX_CENTER
            }
        )
    }
    var minBoutFrames by remember(options) {
        mutableStateOf(options.boutSettings.minBoutFrames.toString())
    }
    var maxBoutGap by remember(options) {
        mutableStateOf(options.boutSettings.maxGapFrames.toString())
    }
    var keypointIndex by remember(options) {
        mutableStateOf(options.roiSettings.keypointIndex.toString())
    }
    var entryThreshold by remember(options) {
        mutableStateOf(options.roiSettings.entryThreshold.toString())
    }
    var exitThreshold by remember(options) {
        mutableStateOf(options.roiSettings.exitThreshold.toString())
    }
    var maxRoiGap by remember(options) {
        mutableStateOf(options.roiSettings.maxGapFrames.toString())
    }
    var minRoiDwell by remember(options) {
        mutableStateOf(options.roiSettings.minDwellFrames.toString())
    }
    val analyticsSelected = classBouts || roiVisits
    val hasOutput = rawVideo || annotatedVideo || detectionCsv ||
        classBouts || roiVisits

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
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(
                    "Live recording & metrics",
                    style = MaterialTheme.typography.headlineSmall,
                    color = Color(0xFFE8EFF9)
                )
                Text(
                    "Video streams directly to app-owned disk storage; it is not held in RAM. " +
                        "Selected analytics are built after recording stops.",
                    color = Color(0xFFC8D6E8)
                )
                LiveNumberField(
                    "Planned duration (minutes)",
                    plannedDurationMinutes,
                    { plannedDurationMinutes = digits(it) },
                    "0 keeps manual Stop. A positive value checks disk space before Start " +
                        "and stops the session automatically. Maximum 1440 minutes."
                )
                Text(
                    "Video",
                    fontWeight = FontWeight.SemiBold,
                    color = Color(0xFFE8EFF9)
                )
                LiveOptionToggle(
                    title = "Raw source MP4",
                    subtitle = "Recommended audit record: camera video without predictions.",
                    checked = rawVideo,
                    onCheckedChange = { rawVideo = it }
                )
                LiveOptionToggle(
                    title = "Annotated MP4",
                    subtitle = "30 FPS maximum; bbox, keypoints, classes, and track IDs are drawn.",
                    checked = annotatedVideo,
                    onCheckedChange = { annotatedVideo = it }
                )
                LiveOptionToggle(
                    title = "ROI outlines in annotated MP4",
                    subtitle = if (roiCount > 0) {
                        "Draw the ${roiCount} named region(s) on every saved annotated frame."
                    } else {
                        "Define at least one region to make ROI outlines available."
                    },
                    checked = drawRoisOnAnnotatedVideo,
                    enabled = annotatedVideo && roiCount > 0,
                    onCheckedChange = { drawRoisOnAnnotatedVideo = it }
                )
                if (rawVideo && annotatedVideo) {
                    Text(
                        "Saving both videos can reduce inference throughput on some devices.",
                        style = MaterialTheme.typography.bodySmall,
                        color = Color(0xFFFFD2A6)
                    )
                }
                Text(
                    "Data and behavior metrics",
                    fontWeight = FontWeight.SemiBold,
                    color = Color(0xFFE8EFF9)
                )
                LiveOptionToggle(
                    title = "Detection + pose CSV",
                    subtitle = if (analyticsSelected) {
                        "Required as the post-recording inference record for behavior analytics."
                    } else {
                        "Per-frame bbox, class, track ID, and kpt1_x/kpt1_y/kpt1_conf columns."
                    },
                    checked = detectionCsv || analyticsSelected,
                    enabled = !analyticsSelected,
                    onCheckedChange = { detectionCsv = it }
                )
                LiveOptionToggle(
                    title = "Detailed behavior bouts CSV",
                    subtitle = "Mutually exclusive bouts with observed and gap-filled frame support.",
                    checked = classBouts,
                    onCheckedChange = {
                        classBouts = it
                        if (it) detectionCsv = true
                    }
                )
                if (classBouts) {
                    LiveNumberField(
                        "Minimum bout duration (frames)",
                        minBoutFrames,
                        { minBoutFrames = digits(it) },
                        "Shorter bouts are omitted."
                    )
                    LiveNumberField(
                        "Maximum frame gap (frames)",
                        maxBoutGap,
                        { maxBoutGap = digits(it) },
                        "Filled only when flanked by the same behavior."
                    )
                }
                LiveOptionToggle(
                    title = "ROI entry / exit / dwell CSV",
                    subtitle = if (roiCount > 0) {
                        "Use the ${roiCount} defined region(s)."
                    } else {
                        "Define at least one region first."
                    },
                    checked = roiVisits,
                    enabled = roiCount > 0,
                    onCheckedChange = {
                        roiVisits = it
                        if (it) detectionCsv = true
                    }
                )
                OutlinedButton(
                    onClick = onDefineRois,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        if (roiCount == 0) {
                            "Capture frame and define regions"
                        } else {
                            "Edit ${roiCount} live region(s)"
                        }
                    )
                }
                if (roiVisits) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Text(
                            "Entry/exit anchor",
                            modifier = Modifier.weight(1f),
                            color = Color(0xFFD5E1F0)
                        )
                        OutlinedButton(
                            onClick = {
                                anchorMode = if (
                                    anchorMode == RoiAnchorMode.BOUNDING_BOX_CENTER
                                ) {
                                    RoiAnchorMode.KEYPOINT
                                } else {
                                    RoiAnchorMode.BOUNDING_BOX_CENTER
                                }
                            },
                            enabled = modelType == ModelType.POSE
                        ) {
                            Text(anchorMode.displayName)
                        }
                    }
                    if (anchorMode == RoiAnchorMode.KEYPOINT) {
                        LiveNumberField(
                            "ROI entry keypoint index",
                            keypointIndex,
                            { keypointIndex = digits(it) },
                            "Zero-based; index 0 corresponds to kpt1 in the CSV."
                        )
                    }
                    LiveNumberField(
                        "ROI entry threshold",
                        entryThreshold,
                        { entryThreshold = decimal(it) },
                        "Bounding-box overlap required to enter; default 0.75."
                    )
                    LiveNumberField(
                        "ROI exit threshold",
                        exitThreshold,
                        { exitThreshold = decimal(it) },
                        "Stay inside while overlap exceeds this value; default 0.25."
                    )
                    LiveNumberField(
                        "Maximum ROI gap (frames)",
                        maxRoiGap,
                        { maxRoiGap = digits(it) },
                        "Gaps at or below this value are bridged; default 5."
                    )
                    LiveNumberField(
                        "Minimum ROI dwell (frames)",
                        minRoiDwell,
                        { minRoiDwell = digits(it) },
                        "Shorter visits are omitted; default 3."
                    )
                }
                if (!hasOutput) {
                    Text(
                        "Select at least one video or data output.",
                        color = Color(0xFFFFB2B2)
                    )
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedButton(
                        onClick = onDismiss,
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("Cancel")
                    }
                    Button(
                        onClick = {
                            onUseOptions(
                                LiveRecordingOptions(
                                    plannedDurationMinutes =
                                        (plannedDurationMinutes.toIntOrNull() ?: 0)
                                            .coerceIn(
                                                0,
                                                LiveRecordingStorage.MAX_PLANNED_MINUTES
                                            ),
                                    rawVideo = rawVideo,
                                    annotatedVideo = annotatedVideo,
                                    drawRoisOnAnnotatedVideo =
                                        annotatedVideo &&
                                            drawRoisOnAnnotatedVideo &&
                                            roiCount > 0,
                                    detectionCsv = detectionCsv || analyticsSelected,
                                    classBouts = classBouts,
                                    roiVisits = roiVisits && roiCount > 0,
                                    boutSettings = BoutSettings(
                                        minBoutFrames =
                                            minBoutFrames.toIntOrNull() ?: 3,
                                        maxGapFrames =
                                            maxBoutGap.toIntOrNull() ?: 5
                                    ).sanitized(),
                                    roiSettings = RoiAnalyticsSettings(
                                        anchorMode = anchorMode,
                                        keypointIndex =
                                            keypointIndex.toIntOrNull() ?: 0,
                                        entryThreshold =
                                            entryThreshold.toFloatOrNull() ?: 0.75f,
                                        exitThreshold =
                                            exitThreshold.toFloatOrNull() ?: 0.25f,
                                        maxGapFrames =
                                            maxRoiGap.toIntOrNull() ?: 5,
                                        minDwellFrames =
                                            minRoiDwell.toIntOrNull() ?: 3
                                    ).sanitized()
                                )
                            )
                        },
                        enabled = hasOutput,
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("Use settings")
                    }
                }
            }
        }
    }
}

@Composable
private fun LiveOptionToggle(
    title: String,
    subtitle: String,
    checked: Boolean,
    enabled: Boolean = true,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, color = Color(0xFFD5E1F0))
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = Color(0xFFAEBFD4)
            )
        }
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            enabled = enabled
        )
    }
}

@Composable
private fun LiveNumberField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    support: String
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        supportingText = { Text(support) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
        modifier = Modifier
            .fillMaxWidth()
            .keepFocusedFieldVisible()
    )
}

private fun digits(value: String): String =
    value.filter(Char::isDigit).take(6)

private fun decimal(value: String): String {
    var dotSeen = false
    return value.filter { character ->
        when {
            character.isDigit() -> true
            character == '.' && !dotSeen -> {
                dotSeen = true
                true
            }
            else -> false
        }
    }.take(8)
}
