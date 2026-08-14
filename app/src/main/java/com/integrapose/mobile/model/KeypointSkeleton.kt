package com.integrapose.mobile.model

import kotlinx.serialization.Serializable

@Serializable
data class KeypointConnection(
    val startIndex: Int,
    val endIndex: Int
)

fun parseKeypointConnections(text: String): List<KeypointConnection> {
    val tokens = text
        .split(Regex("[,;\\n\\r]+"))
        .map(String::trim)
        .filter(String::isNotEmpty)
    require(tokens.size <= 512) { "A skeleton can contain at most 512 connections." }

    val seen = mutableSetOf<Pair<Int, Int>>()
    return tokens.map { token ->
        val match = CONNECTION_PATTERN.matchEntire(token)
            ?: throw IllegalArgumentException(
                "Use connections such as 0-1, 1-2, with commas or one connection per line."
            )
        val start = match.groupValues[1].toInt()
        val end = match.groupValues[2].toInt()
        require(start in 0..10_000 && end in 0..10_000) {
            "Keypoint indices must be between 0 and 10000."
        }
        require(start != end) { "A keypoint cannot connect to itself ($start-$end)." }
        val normalized = minOf(start, end) to maxOf(start, end)
        require(seen.add(normalized)) { "Connection $start-$end is listed more than once." }
        KeypointConnection(start, end)
    }
}

fun formatKeypointConnections(connections: List<KeypointConnection>): String =
    connections.joinToString(", ") { "${it.startIndex}-${it.endIndex}" }

private val CONNECTION_PATTERN = Regex("^(\\d+)\\s*(?:->|-|:)\\s*(\\d+)$")
