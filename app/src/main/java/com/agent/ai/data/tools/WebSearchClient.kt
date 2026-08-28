package com.agent.ai.data.tools

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

/** Free web search via DuckDuckGo + Wikipedia (no API key). */
object WebSearchClient {

    private val http = OkHttpClient.Builder()
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(18, TimeUnit.SECONDS)
        .build()

    private const val USER_AGENT =
        "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"

    suspend fun search(query: String, maxSnippets: Int = 3): String = withContext(Dispatchers.IO) {
        val q = query.trim()
        if (q.isEmpty()) return@withContext "Search query was empty"

        val sections = mutableListOf<String>()
        fetchDdgInstant(q)?.let { sections += it }
        fetchWikipediaSummary(q)?.let { sections += it }
        val lite = fetchDdgLiteSnippets(q, maxSnippets)
        if (lite.isNotEmpty()) sections += lite

        sections
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .distinct()
            .joinToString("\n\n")
            .take(1200)
            .ifBlank { "No web results found for \"$q\". Try a more specific query." }
    }

    private fun fetchDdgInstant(query: String): String? {
        return try {
            val url = "https://api.duckduckgo.com/?q=${encode(query)}&format=json&no_html=1&skip_disambig=1"
            val body = get(url) ?: return null
            val json = JSONObject(body)
            val parts = mutableListOf<String>()

            json.optString("Heading").takeIf { it.isNotBlank() }?.let { parts += "Topic: $it" }
            json.optString("AbstractText").takeIf { it.isNotBlank() }?.let { abstract ->
                val src = json.optString("AbstractSource").takeIf { it.isNotBlank() }
                parts += if (src != null) "$abstract (source: $src)" else abstract
            }

            json.optJSONArray("RelatedTopics")?.let { arr ->
                for (i in 0 until minOf(arr.length(), 4)) {
                    val item = arr.optJSONObject(i) ?: continue
                    item.optString("Text").takeIf { it.isNotBlank() }?.let { parts += it }
                }
            }
            parts.joinToString("\n").takeIf { it.isNotBlank() }
        } catch (_: Exception) {
            null
        }
    }

    private fun fetchWikipediaSummary(query: String): String? {
        return try {
            val searchUrl =
                "https://en.wikipedia.org/w/api.php?action=query&list=search&srsearch=${encode(query)}&format=json&srlimit=1"
            val searchBody = get(searchUrl) ?: return null
            val searchJson = JSONObject(searchBody)
            val items = searchJson.optJSONObject("query")?.optJSONArray("search") ?: return null
            if (items.length() == 0) return null
            val title = items.getJSONObject(0).getString("title")

            val summaryUrl = "https://en.wikipedia.org/api/rest_v1/page/summary/${encode(title)}"
            val summaryBody = get(summaryUrl) ?: return null
            val summaryJson = JSONObject(summaryBody)
            val extract = summaryJson.optString("extract").takeIf { it.isNotBlank() } ?: return null
            "Wikipedia (${summaryJson.optString("title", title)}): $extract"
        } catch (_: Exception) {
            null
        }
    }

    private fun fetchDdgLiteSnippets(query: String, max: Int): List<String> {
        return try {
            val request = Request.Builder()
                .url("https://lite.duckduckgo.com/lite/")
                .post(FormBody.Builder().add("q", query).build())
                .header("User-Agent", USER_AGENT)
                .build()
            val html = http.newCall(request).execute().use { resp ->
                if (!resp.isSuccessful) return emptyList()
                resp.body?.string().orEmpty()
            }
            val snippets = mutableListOf<String>()
            val snippetRegex = Regex("""class="result-snippet"[^>]*>([^<]+)""", RegexOption.IGNORE_CASE)
            snippetRegex.findAll(html).forEach { match ->
                val text = decodeHtml(match.groupValues[1].trim())
                if (text.length > 20) snippets += text
            }
            if (snippets.isEmpty()) {
                Regex("""<td class='result-snippet'[^>]*>([\s\S]*?)</td>""").findAll(html).forEach { match ->
                    val text = decodeHtml(match.groupValues[1].replace(Regex("<[^>]+>"), " ").trim())
                    if (text.length > 20) snippets += text
                }
            }
            snippets.distinct().take(max)
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun get(url: String): String? = try {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", USER_AGENT)
            .build()
        http.newCall(request).execute().use { resp ->
            if (!resp.isSuccessful) null else resp.body?.string()
        }
    } catch (_: Exception) {
        null
    }

    private fun encode(value: String): String = URLEncoder.encode(value, Charsets.UTF_8.name())

    private fun decodeHtml(raw: String): String = raw
        .replace("&amp;", "&")
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&quot;", "\"")
        .replace("&#39;", "'")
        .replace(Regex("\\s+"), " ")
        .trim()
}
