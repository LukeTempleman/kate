package tech.gonxt.kate.brain

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import tech.gonxt.kate.core.ChatMessage
import tech.gonxt.kate.core.Role
import java.net.HttpURLConnection
import java.net.URL

const val GROQ_MODEL = "llama-3.3-70b-versatile"

private val json = Json { ignoreUnknownKeys = true }

/** One SSE line from an OpenAI-compatible stream → delta text (null = nothing/done). */
fun parseGroqChunk(line: String): String? {
    if (!line.startsWith("data: ")) return null
    val payload = line.removePrefix("data: ").trim()
    if (payload.isEmpty() || payload == "[DONE]") return null
    return runCatching {
        json.parseToJsonElement(payload).jsonObject["choices"]?.jsonArray?.firstOrNull()
            ?.jsonObject?.get("delta")?.jsonObject?.get("content")?.jsonPrimitive?.content
    }.getOrNull()?.takeIf { it.isNotEmpty() }
}

fun buildGroqBody(history: List<ChatMessage>, persona: String, model: String = GROQ_MODEL): String =
    buildJsonObject {
        put("model", model)
        put("stream", true)
        put("temperature", 0.6)
        put("max_tokens", 512)
        put(
            "messages",
            buildJsonArray {
                add(buildJsonObject { put("role", "system"); put("content", persona) })
                for (m in history.takeLast(20)) {
                    add(
                        buildJsonObject {
                            put("role", if (m.role == Role.USER) "user" else "assistant")
                            put("content", m.text)
                        },
                    )
                }
            },
        )
    }.toString()

fun isOnline(context: Context): Boolean {
    val cm = context.getSystemService(ConnectivityManager::class.java) ?: return false
    val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
    return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
        caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
}

/** Online brain (spec M1.4): Groq llama-3.3-70b, token streaming over SSE. */
class GroqBrain(
    private val context: Context,
    private val apiKey: () -> String,
) : Brain {

    override val id = "groq"

    override suspend fun isAvailable(): Boolean =
        apiKey().isNotBlank() && isOnline(context)

    override fun reply(history: List<ChatMessage>): Flow<String> = flow {
        val conn = URL("https://api.groq.com/openai/v1/chat/completions")
            .openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "POST"
            conn.doOutput = true
            conn.connectTimeout = 10_000
            conn.readTimeout = 60_000
            conn.setRequestProperty("Authorization", "Bearer ${apiKey()}")
            conn.setRequestProperty("Content-Type", "application/json")
            conn.setRequestProperty("Accept", "text/event-stream")
            conn.outputStream.use { it.write(buildGroqBody(history, KATE_PERSONA).toByteArray()) }

            if (conn.responseCode !in 200..299) {
                val err = conn.errorStream?.bufferedReader()?.readText()?.take(300)
                error("groq http ${conn.responseCode}: $err")
            }
            conn.inputStream.bufferedReader().useLines { lines ->
                for (line in lines) {
                    if (line.startsWith("data: ") && line.contains("[DONE]")) break
                    parseGroqChunk(line)?.let { emit(it) }
                }
            }
        } finally {
            conn.disconnect()
        }
    }.flowOn(Dispatchers.IO)
}
