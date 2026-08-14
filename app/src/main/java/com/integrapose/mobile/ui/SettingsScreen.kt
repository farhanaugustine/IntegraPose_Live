package com.integrapose.mobile.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.integrapose.mobile.inference.AnnotationColorPreset
import com.integrapose.mobile.inference.AnnotationStyle
import com.integrapose.mobile.inference.RoiLabelSize
import com.integrapose.mobile.model.KeypointConnection
import com.integrapose.mobile.model.ModelType
import com.integrapose.mobile.model.InferenceModelConfig
import com.integrapose.mobile.model.formatKeypointConnections
import com.integrapose.mobile.model.parseKeypointConnections
import com.integrapose.mobile.tracking.IoUTrackerConfig

@Composable
fun SettingsScreen(
    style: AnnotationStyle,
    selectedModel: InferenceModelConfig?,
    trackerConfig: IoUTrackerConfig,
    onBoundingBoxColorChange: (AnnotationColorPreset) -> Unit,
    onKeypointColorChange: (AnnotationColorPreset) -> Unit,
    onRoiLabelSizeChange: (RoiLabelSize) -> Unit,
    onSkeletonConnectionsChange: (String, List<KeypointConnection>) -> Unit,
    onTrackerConfigChange: (IoUTrackerConfig) -> Unit
) {
    var showLegalAcknowledgements by remember { mutableStateOf(false) }
    if (showLegalAcknowledgements) {
        AboutScreen(onBack = { showLegalAcknowledgements = false })
        return
    }

    val poseModel = selectedModel?.takeIf { it.type == ModelType.POSE }
    var skeletonText by remember(poseModel?.id, poseModel?.skeletonConnections) {
        mutableStateOf(formatKeypointConnections(poseModel?.skeletonConnections.orEmpty()))
    }
    var skeletonError by remember(poseModel?.id) { mutableStateOf<String?>(null) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .imePadding()
            .navigationBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(
            "Settings",
            style = MaterialTheme.typography.headlineSmall,
            color = Color(0xFFE5ECF8)
        )
        Text(
            "Annotation settings apply to Live, Image, Offline, and exported annotated media.",
            color = Color(0xFFBDD0E7)
        )

        Card(colors = CardDefaults.cardColors(containerColor = Color(0x55304455))) {
            Column(
                modifier = Modifier.padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    "Annotation appearance",
                    style = MaterialTheme.typography.titleMedium,
                    color = Color(0xFFE8EFF9)
                )
                ColorPresetSelector(
                    label = "Bounding boxes",
                    selected = style.boundingBoxColor,
                    onSelected = onBoundingBoxColorChange
                )
                ColorPresetSelector(
                    label = "Keypoints and configured skeleton lines",
                    selected = style.keypointColor,
                    onSelected = onKeypointColorChange
                )
                RoiLabelSizeSelector(
                    selected = style.roiLabelSize,
                    onSelected = onRoiLabelSizeChange
                )
                Text(
                    "ROI outlines use stable automatic colors. Off hides ROI names but keeps the outlines and analytics.",
                    style = MaterialTheme.typography.bodySmall,
                    color = Color(0xFFBDD0E7)
                )
                OutlinedButton(
                    onClick = {
                        onBoundingBoxColorChange(AnnotationStyle.Default.boundingBoxColor)
                        onKeypointColorChange(AnnotationStyle.Default.keypointColor)
                        onRoiLabelSizeChange(AnnotationStyle.Default.roiLabelSize)
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Restore annotation defaults")
                }
            }
        }

        Card(colors = CardDefaults.cardColors(containerColor = Color(0x55304455))) {
            Column(
                modifier = Modifier.padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(
                    "Pose skeleton",
                    style = MaterialTheme.typography.titleMedium,
                    color = Color(0xFFE8EFF9)
                )
                if (poseModel == null) {
                    Text(
                        "Select a compatible pose model to define its skeleton. Detection models draw boxes only.",
                        color = Color(0xFFBDD0E7)
                    )
                } else {
                    Text(
                        "Selected model: ${poseModel.name}",
                        fontWeight = FontWeight.SemiBold,
                        color = Color(0xFFDDE8F6)
                    )
                    Text(
                        "Connections are model-specific and use the zero-based keypoint order from the training dataset. IntegraPose Live does not guess that order.",
                        style = MaterialTheme.typography.bodySmall,
                        color = Color(0xFFBDD0E7)
                    )
                    Text(
                        "Example: if Nose=0, CenterSpine=1, and TailBase=2, enter 0-1, 1-2. Leave this empty to draw keypoints without lines.",
                        style = MaterialTheme.typography.bodySmall,
                        color = Color(0xFFA8F0D3)
                    )
                    OutlinedTextField(
                        value = skeletonText,
                        onValueChange = {
                            skeletonText = it
                            skeletonError = null
                        },
                        label = { Text("Connections") },
                        placeholder = { Text("0-1, 1-2") },
                        supportingText = {
                            Text("Separate connections with commas or put one on each line.")
                        },
                        minLines = 3,
                        maxLines = 6,
                        isError = skeletonError != null,
                        modifier = Modifier
                            .fillMaxWidth()
                            .keepFocusedFieldVisible()
                    )
                    skeletonError?.let {
                        Text(it, color = Color(0xFFFFB2B2), style = MaterialTheme.typography.bodySmall)
                    }
                    Text(
                        if (poseModel.skeletonConnections.isEmpty()) {
                            "Current output: keypoints only"
                        } else {
                            "Current skeleton: ${poseModel.skeletonConnections.size} connection(s)"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = Color(0xFFFFD8A8)
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Button(
                            onClick = {
                                runCatching { parseKeypointConnections(skeletonText) }
                                    .onSuccess { connections ->
                                        skeletonError = null
                                        onSkeletonConnectionsChange(poseModel.id, connections)
                                    }
                                    .onFailure { error ->
                                        skeletonError = error.message ?: "Invalid skeleton connections."
                                    }
                            },
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("Save skeleton")
                        }
                        OutlinedButton(
                            onClick = {
                                skeletonText = ""
                                skeletonError = null
                                onSkeletonConnectionsChange(poseModel.id, emptyList())
                            },
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("Keypoints only")
                        }
                    }
                }
            }
        }

        GeometryTrackerSettingsCard(
            config = trackerConfig,
            onConfigChange = onTrackerConfigChange
        )

        Button(
            onClick = { showLegalAcknowledgements = true },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Legal & acknowledgements")
        }
    }
}

@Composable
private fun GeometryTrackerSettingsCard(
    config: IoUTrackerConfig,
    onConfigChange: (IoUTrackerConfig) -> Unit
) {
    var minimumConfidenceText by remember(config) {
        mutableStateOf(config.minimumConfidence.toString())
    }
    var newTrackConfidenceText by remember(config) {
        mutableStateOf(config.newTrackConfidence.toString())
    }
    var matchIoUText by remember(config) {
        mutableStateOf(config.matchIoU.toString())
    }
    var maxMissingFramesText by remember(config) {
        mutableStateOf(config.maxMissingFrames.toString())
    }
    var errorText by remember(config) { mutableStateOf<String?>(null) }

    Card(colors = CardDefaults.cardColors(containerColor = Color(0x55304455))) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(
                "Geometry tracker",
                style = MaterialTheme.typography.titleMedium,
                color = Color(0xFFE8EFF9)
            )
            Text(
                "Assigns IDs by bounding-box overlap. It does not use appearance ReID or motion prediction, so crossings and long occlusions can still cause ID switches.",
                style = MaterialTheme.typography.bodySmall,
                color = Color(0xFFFFD8A8)
            )
            OutlinedTextField(
                value = maxMissingFramesText,
                onValueChange = {
                    maxMissingFramesText = it
                    errorText = null
                },
                label = { Text("Lost-track buffer (frames)") },
                supportingText = {
                    Text(
                        "Keeps an unmatched ID available for 0-${IoUTrackerConfig.MAX_MISSING_FRAMES} frames. After this buffer expires, a later detection receives a new ID."
                    )
                },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                isError = errorText != null,
                modifier = Modifier
                    .fillMaxWidth()
                    .keepFocusedFieldVisible()
            )
            OutlinedTextField(
                value = matchIoUText,
                onValueChange = {
                    matchIoUText = it
                    errorText = null
                },
                label = { Text("Match overlap (IoU)") },
                supportingText = {
                    Text(
                        "0-1. Higher values require closer box overlap to keep the same ID."
                    )
                },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                isError = errorText != null,
                modifier = Modifier
                    .fillMaxWidth()
                    .keepFocusedFieldVisible()
            )
            OutlinedTextField(
                value = minimumConfidenceText,
                onValueChange = {
                    minimumConfidenceText = it
                    errorText = null
                },
                label = { Text("Minimum tracking confidence") },
                supportingText = {
                    Text("0-1. Detections below this value cannot match an existing ID.")
                },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                isError = errorText != null,
                modifier = Modifier
                    .fillMaxWidth()
                    .keepFocusedFieldVisible()
            )
            OutlinedTextField(
                value = newTrackConfidenceText,
                onValueChange = {
                    newTrackConfidenceText = it
                    errorText = null
                },
                label = { Text("New ID confidence") },
                supportingText = {
                    Text(
                        "0-1 and not lower than the minimum tracking confidence."
                    )
                },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                isError = errorText != null,
                modifier = Modifier
                    .fillMaxWidth()
                    .keepFocusedFieldVisible()
            )
            errorText?.let {
                Text(
                    it,
                    color = Color(0xFFFFB2B2),
                    style = MaterialTheme.typography.bodySmall
                )
            }
            Button(
                onClick = {
                    runCatching {
                        parseGeometryTrackerConfig(
                            minimumConfidenceText = minimumConfidenceText,
                            newTrackConfidenceText = newTrackConfidenceText,
                            matchIoUText = matchIoUText,
                            maxMissingFramesText = maxMissingFramesText
                        )
                    }.onSuccess { parsed ->
                        errorText = null
                        onConfigChange(parsed)
                    }.onFailure { error ->
                        errorText = error.message
                            ?: "Check the geometry tracker values."
                    }
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Save tracker settings")
            }
            OutlinedButton(
                onClick = {
                    val defaults = IoUTrackerConfig()
                    minimumConfidenceText = defaults.minimumConfidence.toString()
                    newTrackConfidenceText = defaults.newTrackConfidence.toString()
                    matchIoUText = defaults.matchIoU.toString()
                    maxMissingFramesText = defaults.maxMissingFrames.toString()
                    errorText = null
                    onConfigChange(defaults)
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Restore tracker defaults")
            }
        }
    }
}

internal fun parseGeometryTrackerConfig(
    minimumConfidenceText: String,
    newTrackConfidenceText: String,
    matchIoUText: String,
    maxMissingFramesText: String
): IoUTrackerConfig {
    val minimumConfidence = minimumConfidenceText.toFloatOrNull()
        ?: throw IllegalArgumentException(
            "Minimum tracking confidence must be a number from 0 to 1."
        )
    val newTrackConfidence = newTrackConfidenceText.toFloatOrNull()
        ?: throw IllegalArgumentException(
            "New ID confidence must be a number from 0 to 1."
        )
    val matchIoU = matchIoUText.toFloatOrNull()
        ?: throw IllegalArgumentException("Match overlap must be a number from 0 to 1.")
    val maxMissingFrames = maxMissingFramesText.toIntOrNull()
        ?: throw IllegalArgumentException("Lost-track buffer must be a whole number.")
    require(minimumConfidence.isFinite() && minimumConfidence in 0f..1f) {
        "Minimum tracking confidence must be from 0 to 1."
    }
    require(newTrackConfidence.isFinite() && newTrackConfidence in 0f..1f) {
        "New ID confidence must be from 0 to 1."
    }
    require(newTrackConfidence >= minimumConfidence) {
        "New ID confidence cannot be lower than minimum tracking confidence."
    }
    require(matchIoU.isFinite() && matchIoU in 0f..1f) {
        "Match overlap must be from 0 to 1."
    }
    require(maxMissingFrames in 0..IoUTrackerConfig.MAX_MISSING_FRAMES) {
        "Lost-track buffer must be from 0 to ${IoUTrackerConfig.MAX_MISSING_FRAMES} frames."
    }
    return IoUTrackerConfig(
        minimumConfidence = minimumConfidence,
        newTrackConfidence = newTrackConfidence,
        matchIoU = matchIoU,
        maxMissingFrames = maxMissingFrames
    )
}

@Composable
private fun RoiLabelSizeSelector(
    selected: RoiLabelSize,
    onSelected: (RoiLabelSize) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("ROI name labels", color = Color(0xFFD5E1F0))
        Box(modifier = Modifier.fillMaxWidth()) {
            OutlinedButton(
                onClick = { expanded = true },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(selected.displayName)
            }
            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                RoiLabelSize.entries.forEach { size ->
                    DropdownMenuItem(
                        text = { Text(size.displayName) },
                        onClick = {
                            expanded = false
                            onSelected(size)
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun ColorPresetSelector(
    label: String,
    selected: AnnotationColorPreset,
    onSelected: (AnnotationColorPreset) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(label, color = Color(0xFFD5E1F0))
        Box(modifier = Modifier.fillMaxWidth()) {
            OutlinedButton(
                onClick = { expanded = true },
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(20.dp)
                            .background(Color(selected.argb))
                    )
                    Text(selected.displayName)
                }
            }
            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                AnnotationColorPreset.entries.forEach { preset ->
                    DropdownMenuItem(
                        text = { Text(preset.displayName) },
                        leadingIcon = {
                            Box(
                                modifier = Modifier
                                    .size(20.dp)
                                    .background(Color(preset.argb))
                            )
                        },
                        onClick = {
                            expanded = false
                            onSelected(preset)
                        }
                    )
                }
            }
        }
    }
}
