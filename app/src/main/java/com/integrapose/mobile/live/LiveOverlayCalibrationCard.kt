package com.integrapose.mobile.live

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.integrapose.mobile.inference.OverlayCalibration
import com.integrapose.mobile.ui.AdaptiveModal
import java.util.Locale

@Composable
internal fun LiveOverlayCalibrationDialog(
    cameraName: String,
    automaticMappingActive: Boolean,
    fillPreview: Boolean,
    calibration: OverlayCalibration,
    onFillPreviewChange: (Boolean) -> Unit,
    onCalibrationChange: (OverlayCalibration) -> Unit,
    onSave: () -> Unit,
    onReset: () -> Unit,
    onDismiss: () -> Unit
) {
    var menuExpanded by remember { mutableStateOf(false) }
    AdaptiveModal(onDismiss = onDismiss) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.92f)
                .windowInsetsPadding(WindowInsets.safeDrawing),
            colors = CardDefaults.cardColors(containerColor = Color(0xFF1C2C3D))
        ) {
            Column(modifier = Modifier.padding(14.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        "Preview alignment",
                        fontWeight = FontWeight.SemiBold,
                        color = Color(0xFFE8EFF9)
                    )
                    TextButton(onClick = onDismiss) { Text("Close") }
                }

                Column(
                    modifier = Modifier
                        .weight(1f)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        "Saved independently for the $cameraName. Automatic CameraX mapping remains active; use these controls only for visible preview correction.",
                        style = MaterialTheme.typography.bodySmall,
                        color = Color(0xFFC5D2E4)
                    )
                    Text(
                        if (automaticMappingActive) {
                            "Automatic crop, rotation, and preview mapping is ready."
                        } else {
                            "CameraX mapping is not ready yet; fit-to-screen fallback is active."
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = if (automaticMappingActive) {
                            Color(0xFFA8F0D3)
                        } else {
                            Color(0xFFFFD8A8)
                        }
                    )

                    CalibrationSwitch(
                        label = "Fill preview (crop edges)",
                        checked = fillPreview,
                        onCheckedChange = onFillPreviewChange
                    )
                    Text(
                        if (fillPreview) {
                            "The live camera fills its panel. The left and right edges may be cropped, but recorded videos keep the complete source frame."
                        } else {
                            "The complete camera frame is visible. Letterboxing may appear when the camera and screen use different aspect ratios."
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = Color(0xFFFFD8A8)
                    )

                    CalibrationSwitch(
                        label = "Flip left / right",
                        checked = calibration.flipHorizontal,
                        onCheckedChange = {
                            onCalibrationChange(calibration.copy(flipHorizontal = it))
                        }
                    )
                    CalibrationSwitch(
                        label = "Flip top / bottom",
                        checked = calibration.flipVertical,
                        onCheckedChange = {
                            onCalibrationChange(calibration.copy(flipVertical = it))
                        }
                    )

                    Box(modifier = Modifier.fillMaxWidth()) {
                        OutlinedButton(
                            onClick = { menuExpanded = true },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("Quick rotation: ${degrees(calibration.rotationDegrees)}")
                        }
                        DropdownMenu(
                            expanded = menuExpanded,
                            onDismissRequest = { menuExpanded = false }
                        ) {
                            QUICK_ROTATIONS.forEach { preset ->
                                DropdownMenuItem(
                                    text = { Text(preset.label) },
                                    onClick = {
                                        onCalibrationChange(
                                            calibration.copy(rotationDegrees = preset.degrees)
                                        )
                                        menuExpanded = false
                                    }
                                )
                            }
                        }
                    }

                    CalibrationSlider(
                        label = "Custom rotation",
                        displayValue = degrees(calibration.rotationDegrees),
                        value = calibration.rotationDegrees,
                        valueRange = OverlayCalibration.MIN_ROTATION..OverlayCalibration.MAX_ROTATION,
                        onValueChange = {
                            onCalibrationChange(calibration.copy(rotationDegrees = it))
                        }
                    )
                    CalibrationSlider(
                        label = "Horizontal position",
                        displayValue = signedPercent(calibration.offsetXFraction),
                        value = calibration.offsetXFraction,
                        valueRange = OverlayCalibration.MIN_OFFSET..OverlayCalibration.MAX_OFFSET,
                        onValueChange = {
                            onCalibrationChange(calibration.copy(offsetXFraction = it))
                        }
                    )
                    CalibrationSlider(
                        label = "Vertical position",
                        displayValue = signedPercent(calibration.offsetYFraction),
                        value = calibration.offsetYFraction,
                        valueRange = OverlayCalibration.MIN_OFFSET..OverlayCalibration.MAX_OFFSET,
                        onValueChange = {
                            onCalibrationChange(calibration.copy(offsetYFraction = it))
                        }
                    )
                    CalibrationSlider(
                        label = "Horizontal scale",
                        displayValue = percent(calibration.scaleX),
                        value = calibration.scaleX,
                        valueRange = OverlayCalibration.MIN_SCALE..OverlayCalibration.MAX_SCALE,
                        onValueChange = { onCalibrationChange(calibration.copy(scaleX = it)) }
                    )
                    CalibrationSlider(
                        label = "Vertical scale",
                        displayValue = percent(calibration.scaleY),
                        value = calibration.scaleY,
                        valueRange = OverlayCalibration.MIN_SCALE..OverlayCalibration.MAX_SCALE,
                        onValueChange = { onCalibrationChange(calibration.copy(scaleY = it)) }
                    )
                    Text(
                        "Preview calibration changes only on-screen annotation placement. Model detections and exported CSV coordinates stay in source-frame pixels.",
                        style = MaterialTheme.typography.bodySmall,
                        color = Color(0xFFFFD8A8)
                    )
                }

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 10.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Button(
                        onClick = {
                            onSave()
                            onDismiss()
                        },
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("Save")
                    }
                    OutlinedButton(onClick = onReset, modifier = Modifier.weight(1f)) {
                        Text("Reset")
                    }
                }
            }
        }
    }
}

@Composable
private fun CalibrationSwitch(
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(label, modifier = Modifier.weight(1f), color = Color(0xFFD9E4F3))
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Composable
private fun CalibrationSlider(
    label: String,
    displayValue: String,
    value: Float,
    valueRange: ClosedFloatingPointRange<Float>,
    onValueChange: (Float) -> Unit
) {
    Row(modifier = Modifier.fillMaxWidth()) {
        Text(label, modifier = Modifier.weight(1f), color = Color(0xFFD9E4F3))
        Text(displayValue, color = Color(0xFF9ED9FF))
    }
    Slider(
        value = value,
        onValueChange = onValueChange,
        valueRange = valueRange,
        modifier = Modifier.fillMaxWidth()
    )
}

private fun signedPercent(value: Float): String =
    String.format(Locale.US, "%+.0f%%", value * 100f)

private fun percent(value: Float): String =
    String.format(Locale.US, "%.0f%%", value * 100f)

private fun degrees(value: Float): String =
    String.format(Locale.US, "%.1f deg", value)

private data class QuickRotation(val label: String, val degrees: Float)

private val QUICK_ROTATIONS = listOf(
    QuickRotation("No rotation", 0f),
    QuickRotation("90 deg clockwise", 90f),
    QuickRotation("90 deg counter-clockwise", -90f),
    QuickRotation("180 deg", 180f)
)
