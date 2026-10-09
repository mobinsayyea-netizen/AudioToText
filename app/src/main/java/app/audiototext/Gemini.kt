package app.audiototext

import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

class GeminiException(message: String) : Exception(message)

class GeminiResult(val text: String, val finish: String)

object Gemini {
    private const val API = "https://generativelanguage.googleapis.com/v1beta"

    fun buildPrompt(lang: String, part: Int, total: Int): String {
        val unclear = if (lang == "hi") "[अस्पष्ट]" else "[unclear]"
        val langLine = when (lang) {
            "hi" -> "Language: spoken Hindi/Urdu (Hindustani), exactly as an everyday speaker talks. " +
                "Write it in Devanagari script. Write the exact words the speaker said, in the same casual spoken style. " +
                "Do NOT replace Urdu, Persian or English words with formal or Sanskritized Hindi words, " +
                "and do not make the language more formal, literary or correct than it was spoken. " +
                "Keep English words in English (Roman) letters, exactly as spoken, like WhatsApp, click, extension."
            "hiroman" -> "Language: spoken Hindi/Urdu (Hindustani), exactly as an everyday speaker talks. " +
                "Write it in English (Roman) letters the way people type Hindi in chat. " +
                "Keep the exact spoken words and casual style; do not make it formal."
            "en" -> "Language: English."
            else -> "Detect the language yourself; write each language in its own script."
        }
        return listOf(
            "Transcribe this audio exactly as spoken, word for word.",
            langLine,
            "Do NOT summarize, shorten, explain, translate, correct or add anything. Do not invent words you did not hear.",
            "If a word is unclear, write $unclear instead of guessing.",
            "Put each sentence or natural pause on a new line. If someone spells out letters or symbols, write them as spoken.",
            if (total > 1) "This is piece $part of $total of one longer recording; it may begin or end in the middle of a sentence, write it as heard." else "",
            "Output only the transcript text. No title, no notes."
        ).filter { it.isNotEmpty() }.joinToString("\n")
    }

    /** Sleeps for [sec] seconds, but wakes up early if the user pressed Stop. */
    fun pause(sec: Int, stop: () -> Boolean) {
        var i = 0
        while (i < sec) {
            if (stop()) return
            Thread.sleep(1000)
            i++
        }
    }

    /** One request to Gemini. Retries when the server is busy or the limit is reached. */
    fun transcribe(
        key: String,
        model: String,
        bytes: ByteArray,
        mime: String,
        prompt: String,
        onWait: (Int, Int, String) -> Unit,
        stop: () -> Boolean
    ): GeminiResult {
        val url = "$API/models/${URLEncoder.encode(model, "UTF-8")}:generateContent"
        val b64 = Base64.encodeToString(bytes, Base64.NO_WRAP)
        val parts = JSONArray()
            .put(JSONObject().put("inline_data", JSONObject().put("mime_type", mime).put("data", b64)))
            .put(JSONObject().put("text", prompt))
        val body = JSONObject()
            .put("contents", JSONArray().put(JSONObject().put("role", "user").put("parts", parts)))
            .put("generationConfig", JSONObject().put("temperature", 0).put("maxOutputTokens", 30000))
            .toString().toByteArray(Charsets.UTF_8)

        var lastErr = ""
        for (attempt in 1..6) {
            if (stop()) throw GeminiException("Stopped")
            var code = -1
            var resp = ""
            var conn: HttpURLConnection? = null
            try {
                conn = URL(url).openConnection() as HttpURLConnection
                conn.requestMethod = "POST"
                conn.connectTimeout = 30000
                conn.readTimeout = 300000
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", "application/json")
                conn.setRequestProperty("x-goog-api-key", key)
                conn.setFixedLengthStreamingMode(body.size)
                conn.outputStream.use { it.write(body) }
                code = conn.responseCode
                val stream = if (code in 200..299) conn.inputStream else conn.errorStream
                resp = stream?.bufferedReader()?.readText() ?: ""
            } catch (e: IOException) {
                lastErr = "No network connection"
                onWait(attempt, 15, lastErr)
                pause(15, stop)
                continue
            } finally {
                conn?.disconnect()
            }

            var data: JSONObject? = null
            try {
                data = JSONObject(resp)
            } catch (e: Exception) {
                // not JSON
            }

            if (code in 200..299) {
                val cand = data?.optJSONArray("candidates")?.optJSONObject(0)
                val ps = cand?.optJSONObject("content")?.optJSONArray("parts")
                val sb = StringBuilder()
                if (ps != null) {
                    for (i in 0 until ps.length()) sb.append(ps.optJSONObject(i)?.optString("text", "") ?: "")
                }
                val finish = cand?.optString("finishReason", "") ?: ""
                val block = data?.optJSONObject("promptFeedback")?.optString("blockReason", "") ?: ""
                if (sb.isEmpty() && finish.isEmpty() && block.isNotEmpty()) {
                    throw GeminiException("Gemini blocked this audio: $block")
                }
                return GeminiResult(sb.toString().trim(), finish)
            }

            val errMsg = data?.optJSONObject("error")?.optString("message", "")
            val msg = if (errMsg.isNullOrEmpty()) "HTTP $code" else errMsg
            lastErr = msg
            if (code == 400 || code == 401 || code == 403 || code == 404) {
                val hint = if (code == 404) " | Change the model name: use the button that shows the models available for your key." else ""
                throw GeminiException("Request cannot run ($code): $msg$hint")
            }
            if (code == 429 && Regex("per day|daily|PerDay", RegexOption.IGNORE_CASE).containsMatchIn(msg)) {
                throw GeminiException("Today's free quota is finished: $msg")
            }
            val wait = minOf(90, 15 * attempt)
            onWait(attempt, wait, if (code == 429) "limit reached" else "server busy")
            pause(wait, stop)
        }
        throw GeminiException("Failed again and again: $lastErr")
    }

    fun listModels(key: String): List<String> {
        val conn = URL("$API/models?pageSize=200").openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = 30000
            conn.readTimeout = 60000
            conn.setRequestProperty("x-goog-api-key", key)
            val code = conn.responseCode
            val txt = (if (code in 200..299) conn.inputStream else conn.errorStream)?.bufferedReader()?.readText() ?: ""
            if (code !in 200..299) throw IOException("HTTP $code")
            val arr = JSONObject(txt).optJSONArray("models") ?: return emptyList()
            val out = ArrayList<String>()
            for (i in 0 until arr.length()) {
                val m = arr.getJSONObject(i)
                val methods = m.optJSONArray("supportedGenerationMethods") ?: continue
                var ok = false
                for (j in 0 until methods.length()) {
                    if (methods.optString(j) == "generateContent") ok = true
                }
                if (ok) out.add(m.optString("name").removePrefix("models/"))
            }
            return out
        } finally {
            conn.disconnect()
        }
    }
}
