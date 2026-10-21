package com.novelassist.learning

import com.novelassist.data.BibleCategory
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive

/** What the AI reported after reading a chapter. */
data class LearnedChapter(
    val summary: String = "",
    val beat: String = "",
    val entities: List<LearnedEntity> = emptyList(),
    val styleNotes: String = "",
)

data class LearnedEntity(val category: BibleCategory, val name: String, val description: String)

/** Tolerant parsing: small on-device models don't always produce perfect JSON. */
object AiJson {
    private val json = Json { isLenient = true; ignoreUnknownKeys = true }

    fun parseLearn(reply: String): LearnedChapter {
        val obj = extractObject(reply) ?: return regexFallback(reply)
        fun str(key: String) = (obj[key] as? JsonPrimitive)?.contentOrNullSafe().orEmpty().trim()
        val entities = (obj["entities"] as? JsonArray).orEmpty().mapNotNull { el ->
            val o = el as? JsonObject ?: return@mapNotNull null
            val name = (o["name"] as? JsonPrimitive)?.contentOrNullSafe()?.trim().orEmpty()
            if (name.isBlank() || name.length > 80) return@mapNotNull null
            LearnedEntity(
                category = BibleCategory.fromLoose((o["category"] as? JsonPrimitive)?.contentOrNullSafe()),
                name = name,
                description = (o["description"] as? JsonPrimitive)?.contentOrNullSafe()?.trim().orEmpty(),
            )
        }
        return LearnedChapter(str("summary"), str("beat"), entities, str("style_notes"))
    }

    private fun extractObject(reply: String): JsonObject? {
        val cleaned = reply.replace(Regex("```(?:json)?"), "").trim()
        val start = cleaned.indexOf('{')
        val end = cleaned.lastIndexOf('}')
        if (start < 0 || end <= start) return null
        val body = cleaned.substring(start, end + 1)
            .replace(Regex(",\\s*([}\\]])"), "$1") // trailing commas
        return runCatching { json.parseToJsonElement(body) as? JsonObject }.getOrNull()
    }

    private fun regexFallback(reply: String): LearnedChapter {
        fun field(name: String) = Regex("\"$name\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"").find(reply)?.groupValues?.get(1)
            ?.replace("\\\"", "\"")?.trim().orEmpty()
        return LearnedChapter(summary = field("summary"), beat = field("beat"), styleNotes = field("style_notes"))
    }

    private fun JsonPrimitive.contentOrNullSafe(): String? = runCatching { jsonPrimitive.content }.getOrNull()
}
