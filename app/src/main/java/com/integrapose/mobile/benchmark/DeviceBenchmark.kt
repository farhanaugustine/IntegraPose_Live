package com.integrapose.mobile.benchmark

import android.app.ActivityManager
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.os.Build
import android.os.Debug
import android.os.PowerManager
import android.system.Os
import android.system.OsConstants
import com.integrapose.mobile.inference.NcnnNative
import com.integrapose.mobile.inference.NcnnRuntimeTuning
import com.integrapose.mobile.inference.ModelInferenceRunner
import com.integrapose.mobile.model.InferenceModelConfig
import kotlin.math.ceil

data class DeviceProfile(
    val deviceName: String,
    val androidVersion: String,
    val soc: String?,
    val abis: List<String>,
    val cpuCores: Int,
    val totalMemoryMb: Long,
    val availableMemoryMb: Long,
    val appMemoryClassMb: Int,
    val processPssMb: Long,
    val nativeHeapMb: Long,
    val lowRamDevice: Boolean,
    val pageSizeBytes: Long,
    val thermalStatus: Int,
    val systemReportsVulkan: Boolean,
    val ncnnVulkanDeviceCount: Int
) {
    val vulkanAvailable: Boolean
        get() = ncnnVulkanDeviceCount > 0

    val meetsFourGbRamTarget: Boolean
        get() = totalMemoryMb >= 3_500L

    val thermalStatusLabel: String
        get() = when (thermalStatus) {
            PowerManager.THERMAL_STATUS_NONE -> "None"
            PowerManager.THERMAL_STATUS_LIGHT -> "Light"
            PowerManager.THERMAL_STATUS_MODERATE -> "Moderate"
            PowerManager.THERMAL_STATUS_SEVERE -> "Severe"
            PowerManager.THERMAL_STATUS_CRITICAL -> "Critical"
            PowerManager.THERMAL_STATUS_EMERGENCY -> "Emergency"
            PowerManager.THERMAL_STATUS_SHUTDOWN -> "Shutdown"
            else -> "Unavailable"
        }

    val summary: String
        get() = buildString {
            append(deviceName)
            append(" | Android ").append(androidVersion)
            soc?.takeIf { it.isNotBlank() }?.let { append(" | ").append(it) }
            append(" | ").append(cpuCores).append(" CPU cores")
            append(" | ").append(totalMemoryMb).append(" MB RAM")
            append(" | ").append(abis.firstOrNull() ?: "unknown ABI")
            if (pageSizeBytes > 0L) {
                append(" | ").append(pageSizeBytes / 1024L).append(" KB pages")
            }
            if (lowRamDevice) append(" | low-RAM profile")
            if (vulkanAvailable) append(" | NCNN Vulkan")
        }

    companion object {
        fun collect(context: Context): DeviceProfile {
            val activityManager = context.getSystemService(ActivityManager::class.java)
            val memoryInfo = ActivityManager.MemoryInfo().also(activityManager::getMemoryInfo)
            val soc = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) Build.SOC_MODEL else null
            val powerManager = context.getSystemService(PowerManager::class.java)
            val thermalStatus = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                powerManager.currentThermalStatus
            } else {
                -1
            }
            val pageSizeBytes = runCatching {
                Os.sysconf(OsConstants._SC_PAGESIZE)
            }.getOrDefault(0L)
            val systemReportsVulkan = context.packageManager.hasSystemFeature(
                PackageManager.FEATURE_VULKAN_HARDWARE_LEVEL
            )
            val ncnnVulkanDeviceCount = runCatching {
                NcnnNative.gpuCount()
            }.getOrDefault(0)
            return DeviceProfile(
                deviceName = "${Build.MANUFACTURER} ${Build.MODEL}".trim(),
                androidVersion = "${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})",
                soc = soc,
                abis = Build.SUPPORTED_ABIS.toList(),
                cpuCores = Runtime.getRuntime().availableProcessors().coerceAtLeast(1),
                totalMemoryMb = memoryInfo.totalMem / (1024L * 1024L),
                availableMemoryMb = memoryInfo.availMem / (1024L * 1024L),
                appMemoryClassMb = activityManager.memoryClass,
                processPssMb = Debug.getPss().toLong() / 1024L,
                nativeHeapMb = Debug.getNativeHeapAllocatedSize() / (1024L * 1024L),
                lowRamDevice = activityManager.isLowRamDevice,
                pageSizeBytes = pageSizeBytes,
                thermalStatus = thermalStatus,
                systemReportsVulkan = systemReportsVulkan,
                ncnnVulkanDeviceCount = ncnnVulkanDeviceCount
            )
        }
    }
}

data class BenchmarkResult(
    val iterations: Int,
    val medianMs: Long,
    val p95Ms: Long,
    val estimatedFps: Double,
    val inputWidth: Int,
    val inputHeight: Int,
    val backend: String
)

suspend fun benchmarkModel(
    runner: ModelInferenceRunner,
    bitmap: Bitmap,
    model: InferenceModelConfig,
    warmupIterations: Int = 2,
    measuredIterations: Int = 6,
    ncnnTuning: NcnnRuntimeTuning? = null
): BenchmarkResult {
    repeat(warmupIterations.coerceAtLeast(0)) {
        runner.run(
            bitmap,
            model,
            sourceTimestampUs = 0L,
            ncnnTuning = ncnnTuning
        )
    }
    val results = List(measuredIterations.coerceIn(1, 30)) {
        runner.run(
            bitmap,
            model,
            sourceTimestampUs = 0L,
            ncnnTuning = ncnnTuning
        )
    }
    val sorted = results.map { it.inferenceMs }.sorted()
    val median = sorted[sorted.size / 2]
    val p95Index = (ceil(sorted.size * 0.95).toInt() - 1).coerceIn(sorted.indices)
    val representative = results.last()
    return BenchmarkResult(
        iterations = results.size,
        medianMs = median,
        p95Ms = sorted[p95Index],
        estimatedFps = if (median > 0L) 1_000.0 / median else Double.POSITIVE_INFINITY,
        inputWidth = representative.modelInputWidth,
        inputHeight = representative.modelInputHeight,
        backend = representative.backend
    )
}
