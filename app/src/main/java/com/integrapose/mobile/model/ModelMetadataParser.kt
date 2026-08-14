package com.integrapose.mobile.model

object ModelMetadataParser {
    private val quotedMapEntry = Regex(
        "[\"']?(\\d+)[\"']?\\s*:\\s*([\"'])(.*?)\\2",
        setOf(RegexOption.DOT_MATCHES_ALL)
    )
    private val quotedListEntry = Regex(
        "([\"'])(.*?)\\1",
        setOf(RegexOption.DOT_MATCHES_ALL)
    )
    private val editableEntry = Regex("^\\s*(\\d+)\\s*(?:=|:)\\s*(.+?)\\s*$")

    fun parseClassNames(rawNames: String?): List<String> {
        val raw = rawNames?.trim().orEmpty()
        if (raw.isEmpty()) return emptyList()

        if (':' in raw) {
            val mapping = linkedMapOf<Int, String>()
            quotedMapEntry.findAll(raw).forEach { match ->
                val id = match.groupValues[1].toIntOrNull() ?: return@forEach
                mapping[id] = unescape(match.groupValues[3])
            }
            if (mapping.isEmpty()) {
                raw.trim('{', '}').split(',').forEach { token ->
                    val separator = token.indexOf(':')
                    if (separator <= 0) return@forEach
                    val id = token.substring(0, separator).trim().trim(39.toChar(), 34.toChar()).toIntOrNull()
                        ?: return@forEach
                    val name = token.substring(separator + 1).trim().trim(39.toChar(), 34.toChar())
                    if (name.isNotBlank()) mapping[id] = name
                }
            }
            if (mapping.isNotEmpty()) return mappingToList(mapping)
        }

        val values = quotedListEntry.findAll(raw)
            .map { unescape(it.groupValues[2]) }
            .filter { it.isNotBlank() }
            .toList()
        if (values.isNotEmpty()) return values

        return raw.trim('[', ']').split(',')
            .map { it.trim().trim(39.toChar(), 34.toChar()) }
            .filter { it.isNotBlank() }
    }

    fun inferModelType(task: String?): ModelType? = when (task?.trim()?.lowercase()) {
        "pose" -> ModelType.POSE
        "detect", "detection" -> ModelType.DETECTION
        else -> null
    }

    fun parseSquareInputSize(rawSize: String?): Int? {
        val values = Regex("\\d+").findAll(rawSize.orEmpty())
            .mapNotNull { it.value.toIntOrNull() }
            .filter { it in 32..2_048 }
            .toList()
        return when {
            values.isEmpty() -> null
            values.size == 1 -> values.first()
            values[0] == values[1] -> values[0]
            else -> null
        }
    }

    fun parseBoolean(rawValue: String?): Boolean? = when (
        rawValue?.trim()?.trim(34.toChar(), 39.toChar())?.lowercase()
    ) {
        "true", "1", "yes" -> true
        "false", "0", "no" -> false
        else -> null
    }

    fun parsePositiveInt(rawValue: String?): Int? = rawValue
        ?.trim()
        ?.trim(34.toChar(), 39.toChar())
        ?.toIntOrNull()
        ?.takeIf { it > 0 }

    fun parseBooleanArgument(arguments: String?, name: String): Boolean? =
        parseBoolean(parseArgumentValue(arguments, name))

    fun parsePositiveIntArgument(arguments: String?, name: String): Int? =
        parsePositiveInt(parseArgumentValue(arguments, name))

    fun formatEditableMapping(classNames: List<String>): String =
        classNames.mapIndexed { id, name -> "$id=$name" }.joinToString("\n")

    fun parseEditableMapping(text: String): List<String> {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return emptyList()
        if ('=' !in trimmed && ':' !in trimmed && '\n' !in trimmed) {
            return trimmed.split(',').map { it.trim() }.filter { it.isNotBlank() }
        }

        val mapping = linkedMapOf<Int, String>()
        trimmed.lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .forEach { line ->
                val match = editableEntry.matchEntire(line)
                    ?: throw IllegalArgumentException("Use one class per line in the form ID=name.")
                val id = match.groupValues[1].toInt()
                require(id in 0..10_000) { "Class IDs must be between 0 and 10000." }
                require(id !in mapping) { "Class ID $id is listed more than once." }
                val name = match.groupValues[2].trim().trim(39.toChar(), 34.toChar())
                require(name.isNotBlank()) { "Class ID $id needs a name." }
                mapping[id] = name
            }
        return mappingToList(mapping)
    }

    private fun mappingToList(mapping: Map<Int, String>): List<String> {
        val maximumId = mapping.keys.maxOrNull() ?: return emptyList()
        require(maximumId <= 10_000) { "Class ID is too large." }
        return List(maximumId + 1) { id -> mapping[id] ?: "class_$id" }
    }

    private fun parseArgumentValue(arguments: String?, name: String): String? {
        val normalized = arguments.orEmpty()
            .replace(34.toChar().toString(), "")
            .replace(39.toChar().toString(), "")
        val pattern = Regex(
            "\\b" + Regex.escape(name) + "\\b\\s*(?:=|:)\\s*([^,}\\s]+)",
            RegexOption.IGNORE_CASE
        )
        return pattern.find(normalized)?.groupValues?.getOrNull(1)
    }

    private fun unescape(value: String): String = value
        .replace("\\'", "'")
        .replace("\\\"", "\"")
        .replace("\\\\", "\\")
}
