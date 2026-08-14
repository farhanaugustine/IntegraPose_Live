package com.integrapose.mobile.ui

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Camera
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material.icons.filled.Speed
import androidx.compose.ui.graphics.vector.ImageVector

enum class AppTab(val title: String, val icon: ImageVector) {
    LIVE("Live", Icons.Default.Camera),
    IMAGE("Image", Icons.Default.Image),
    OFFLINE("Offline", Icons.Default.Folder),
    BENCHMARK("Bench", Icons.Default.Speed),
    MODELS("Models", Icons.Default.SmartToy),
    SETTINGS("Settings", Icons.Default.Settings)
}
