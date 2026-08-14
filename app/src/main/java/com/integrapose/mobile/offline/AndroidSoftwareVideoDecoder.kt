package com.integrapose.mobile.offline

import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.os.Build
import java.util.concurrent.ConcurrentHashMap

internal object AndroidSoftwareVideoDecoder {
    private val requiredDecoderByMime = ConcurrentHashMap<String, String>()

    fun rememberedFor(mimeType: String): String? = requiredDecoderByMime[mimeType]

    fun rememberFor(mimeType: String, decoderName: String) {
        requiredDecoderByMime[mimeType] = decoderName
    }

    fun findFor(mimeType: String): String? = runCatching {
        MediaCodecList(MediaCodecList.ALL_CODECS).codecInfos
            .asSequence()
            .filterNot(MediaCodecInfo::isEncoder)
            .filter { codec ->
                codec.supportedTypes.any { it.equals(mimeType, ignoreCase = true) }
            }
            .filter(::isSoftwareCodec)
            .sortedWith(
                compareBy<MediaCodecInfo> { softwarePreference(it.name) }
                    .thenBy { it.name }
            )
            .firstOrNull()
            ?.name
    }.getOrNull()

    fun shouldRetry(error: Throwable): Boolean {
        val pending = java.util.ArrayDeque<Throwable>()
        val visited = mutableSetOf<Throwable>()
        pending.add(error)
        while (pending.isNotEmpty()) {
            val cause = pending.removeFirst()
            if (!visited.add(cause)) continue
            val message = cause.message.orEmpty().lowercase()
            if (message.contains("inaccessible yuv plane") ||
                message.contains("could not read a yuv plane") ||
                message.contains("cannot lock image") ||
                message.contains("cpu-readable yuv")) {
                return true
            }
            cause.cause?.let(pending::add)
            cause.suppressed.forEach(pending::add)
        }
        return false
    }

    private fun isSoftwareCodec(codec: MediaCodecInfo): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            return codec.isSoftwareOnly
        }
        val name = codec.name.lowercase()
        return name.startsWith("c2.android.") ||
            name.startsWith("omx.google.") ||
            name.contains("software") ||
            name.contains("sw.decoder")
    }

    private fun softwarePreference(name: String): Int {
        val normalized = name.lowercase()
        return when {
            normalized.startsWith("c2.android.") -> 0
            normalized.startsWith("omx.google.") -> 1
            else -> 2
        }
    }
}
