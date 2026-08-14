package com.integrapose.mobile.model

import kotlinx.serialization.Serializable

@Serializable
enum class ModelRuntime(val displayName: String) {
    ONNX_CPU("ONNX CPU"),
    NCNN_CPU("NCNN CPU"),
    NCNN_VULKAN("NCNN Vulkan")
}
