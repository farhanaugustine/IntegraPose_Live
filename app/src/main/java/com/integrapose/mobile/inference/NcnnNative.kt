package com.integrapose.mobile.inference

import java.nio.ByteBuffer

data class NcnnTensorOutput(
    val data: FloatArray,
    val shape: LongArray
)

internal object NcnnNative {
    private val loadFailure: Throwable? = runCatching {
        System.loadLibrary("integrapose_ncnn")
    }.exceptionOrNull()

    fun create(
        paramPath: String,
        weightsPath: String,
        threads: Int,
        useVulkan: Boolean
    ): Long {
        requireAvailable()
        return nativeCreate(paramPath, weightsPath, threads, useVulkan).also { handle ->
            check(handle != 0L) { "NCNN returned an invalid model handle." }
        }
    }

    fun run(
        handle: Long,
        input: FloatArray,
        width: Int,
        height: Int
    ): NcnnTensorOutput {
        requireAvailable()
        return nativeRun(handle, input, width, height)
    }

    fun runDirect(
        handle: Long,
        input: ByteBuffer,
        width: Int,
        height: Int
    ): NcnnTensorOutput {
        requireAvailable()
        require(input.isDirect) { "NCNN input must use a direct byte buffer." }
        return nativeRunDirect(handle, input, width, height)
    }

    fun destroy(handle: Long) {
        if (handle != 0L && loadFailure == null) nativeDestroy(handle)
    }

    fun gpuCount(): Int {
        requireAvailable()
        return nativeGpuCount()
    }

    private fun requireAvailable() {
        val failure = loadFailure ?: return
        throw IllegalStateException(
            "The NCNN native runtime could not be loaded: " +
                (failure.message ?: failure::class.java.simpleName),
            failure
        )
    }

    @JvmStatic
    private external fun nativeCreate(
        paramPath: String,
        weightsPath: String,
        threads: Int,
        useVulkan: Boolean
    ): Long

    @JvmStatic
    private external fun nativeRun(
        handle: Long,
        input: FloatArray,
        width: Int,
        height: Int
    ): NcnnTensorOutput

    @JvmStatic
    private external fun nativeRunDirect(
        handle: Long,
        input: ByteBuffer,
        width: Int,
        height: Int
    ): NcnnTensorOutput

    @JvmStatic
    private external fun nativeDestroy(handle: Long)

    @JvmStatic
    private external fun nativeGpuCount(): Int
}
