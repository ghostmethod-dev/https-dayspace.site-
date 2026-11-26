package site.dayspace.phoneagent

import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

class ApiException(val status: Int, message: String) : Exception(message)

/**
 * Minimal client for the Claude Messages API (POST /v1/messages).
 *
 * Conversation history is kept as raw JSON and sent back byte-for-byte, so
 * thinking blocks are always returned unchanged, as the API requires.
 */
class ClaudeClient(private val apiKey: String, private val model: String) {

    // Optional features; each one is switched off if the API rejects it.
    private var useContextEditing = true
    private var useFallbacks = true
    private var useCaching = true

    fun send(system: String, tools: JSONArray, messages: JSONArray): JSONObject {
        var attempt = 0
        while (true) {
            val betas = ArrayList<String>()
            val body = JSONObject()
                .put("model", model)
                .put("max_tokens", 16000)
                .put("system", system)
                .put("tools", tools)
                .put("messages", messages)
                .put("thinking", JSONObject().put("type", "adaptive"))
                .put("output_config", JSONObject().put("effort", "medium"))
            if (useCaching) {
                body.put("cache_control", JSONObject().put("type", "ephemeral"))
            }
            if (useFallbacks) {
                // If the model declines a request, the API retries it on a fallback model.
                body.put("fallbacks", "default")
                betas.add("server-side-fallback-2026-07-01")
            }
            if (useContextEditing) {
                // Server-side clearing of old screenshots / tool results, so the
                // history we send stays append-only and the cost stays bounded.
                body.put(
                    "context_management", JSONObject().put(
                        "edits", JSONArray().put(
                            JSONObject()
                                .put("type", "clear_tool_uses_20250919")
                                .put("trigger", JSONObject().put("type", "input_tokens").put("value", 40000))
                                .put("keep", JSONObject().put("type", "tool_uses").put("value", 4))
                        )
                    )
                )
                betas.add("context-management-2025-06-27")
            }

            val (code, text) = try {
                post(body, betas)
            } catch (e: IOException) {
                if (attempt < 3) {
                    attempt++
                    Thread.sleep(2000L * attempt)
                    continue
                }
                throw ApiException(0, "שגיאת רשת: ${e.message}")
            }

            if (code == 200) return JSONObject(text)

            if (code == 400) {
                val lower = text.lowercase()
                when {
                    useContextEditing && lower.contains("context_management") -> { useContextEditing = false; continue }
                    useFallbacks && lower.contains("fallback") -> { useFallbacks = false; continue }
                    useCaching && lower.contains("cache_control") -> { useCaching = false; continue }
                }
            }
            if ((code == 429 || code == 529 || code >= 500) && attempt < 4) {
                attempt++
                Thread.sleep(3000L * attempt)
                continue
            }
            throw ApiException(code, describeError(code, text))
        }
    }

    private fun post(body: JSONObject, betas: List<String>): Pair<Int, String> {
        val conn = URL("https://api.anthropic.com/v1/messages").openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "POST"
            conn.connectTimeout = 30_000
            conn.readTimeout = 300_000
            conn.doOutput = true
            conn.setRequestProperty("content-type", "application/json")
            conn.setRequestProperty("x-api-key", apiKey)
            conn.setRequestProperty("anthropic-version", "2023-06-01")
            if (betas.isNotEmpty()) conn.setRequestProperty("anthropic-beta", betas.joinToString(","))
            conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""
            return Pair(code, text)
        } finally {
            conn.disconnect()
        }
    }

    private fun describeError(code: Int, text: String): String {
        val msg = try {
            JSONObject(text).getJSONObject("error").optString("message", text)
        } catch (_: Exception) {
            text.take(300)
        }
        return when (code) {
            401 -> "מפתח ה-API לא תקין (401). בדקו את המפתח בהגדרות."
            403 -> "אין הרשאה (403): $msg"
            404 -> "המודל לא נמצא (404). בדקו את שם המודל: $msg"
            429 -> "חריגה ממגבלת קצב או מיתרת קרדיט (429): $msg"
            else -> "שגיאת API $code: $msg"
        }
    }
}
