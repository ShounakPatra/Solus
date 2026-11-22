package com.shounak.localmeshai.ai.tools

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.util.concurrent.TimeUnit
import java.util.regex.Pattern

data class SearchItem(
    val title: String,
    val snippet: String,
    val url: String
)

data class SearchResult(
    val query: String,
    val provider: String,
    val items: List<SearchItem>,
    val note: String = ""
) {
    fun toJsonString(): String {
        val root = JSONObject()
        root.put("query", query)
        root.put("provider", provider)
        if (note.isNotBlank()) root.put("note", note)
        val array = JSONArray()
        items.forEach { item ->
            array.put(
                JSONObject()
                    .put("title", item.title)
                    .put("snippet", item.snippet)
                    .put("url", item.url)
            )
        }
        root.put("results", array)
        return root.toString()
    }

    fun toSummaryString(): String {
        if (items.isEmpty()) return "No web search results found for \"$query\"."
        val sb = StringBuilder()
        sb.append("Web Search Results for \"$query\" ($provider):\n\n")
        items.forEachIndexed { i, item ->
            sb.append("${i + 1}. **${item.title}**\n")
            if (item.snippet.isNotBlank()) sb.append("   ${item.snippet}\n")
            if (item.url.isNotBlank()) sb.append("   ${item.url}\n\n")
        }
        if (note.isNotBlank()) {
            sb.append("Note: $note\n")
        }
        return sb.toString().trimEnd()
    }
}

object DuckDuckGoSearchEngine {
    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(6, TimeUnit.SECONDS)
            .callTimeout(10, TimeUnit.SECONDS)
            .followRedirects(true)
            .build()
    }

    private val LINK_PATTERN = Pattern.compile(
        """<a[^>]+href=['"]([^'"]+)['"][^>]*class=['"]result-link['"][^>]*>(.*?)</a>|<a[^>]+class=['"]result-link['"][^>]*href=['"]([^'"]+)['"][^>]*>(.*?)</a>|<a rel=['"]nofollow['"][^>]*href=['"]([^'"]+)['"][^>]*>(.*?)</a>""",
        Pattern.DOTALL or Pattern.CASE_INSENSITIVE
    )

    private val SNIPPET_PATTERN = Pattern.compile(
        """<td[^>]+class=['"]result-snippet['"][^>]*>(.*?)</td>""",
        Pattern.DOTALL or Pattern.CASE_INSENSITIVE
    )

    private val HTML_TAG_PATTERN = Pattern.compile("<[^>]+>")
    private val entities = mapOf("amp" to "&", "lt" to "<", "gt" to ">", "quot" to "\"", "apos" to "'", "nbsp" to " ")

    private fun plainText(html: String): String = Regex("&(#x[0-9a-fA-F]+|#[0-9]+|[a-zA-Z]+);")
        .replace(html.replace(Regex("<[^>]+>"), "")) { match ->
            val value = match.groupValues[1]
            val number = when {
                value.startsWith("#x") -> value.drop(2).toIntOrNull(16)
                value.startsWith("#") -> value.drop(1).toIntOrNull()
                else -> null
            }
            entities[value] ?: number?.takeIf { it > 0 && Character.isValidCodePoint(it) && it !in 0xD800..0xDFFF }
                ?.let { String(Character.toChars(it)) } ?: match.value
        }

    private fun stripHtml(input: String): String {
        val noTags = HTML_TAG_PATTERN.matcher(input).replaceAll("")
        return plainText(noTags).trim()
    }

    suspend fun searchWithProvider(
        query: String,
        provider: String = "Auto",
        braveApiKey: String = "",
        tavilyApiKey: String = "",
        maxResults: Int = 4
    ): SearchResult = withContext(Dispatchers.IO) {
        val trimmed = query.trim().take(300)
        if (trimmed.isBlank()) {
            return@withContext SearchResult(trimmed, provider, emptyList(), "Empty search query")
        }

        when (provider) {
            "Brave" -> {
                if (braveApiKey.isNotBlank()) {
                    val brave = trySearchBrave(trimmed, braveApiKey, maxResults)
                    if (brave != null && brave.items.isNotEmpty()) return@withContext brave
                }
                search(trimmed, maxResults)
            }
            "Tavily" -> {
                if (tavilyApiKey.isNotBlank()) {
                    val tavily = trySearchTavily(trimmed, tavilyApiKey, maxResults)
                    if (tavily != null && tavily.items.isNotEmpty()) return@withContext tavily
                }
                search(trimmed, maxResults)
            }
            "Auto" -> {
                if (tavilyApiKey.isNotBlank()) {
                    val tavily = trySearchTavily(trimmed, tavilyApiKey, maxResults)
                    if (tavily != null && tavily.items.isNotEmpty()) return@withContext tavily
                }
                if (braveApiKey.isNotBlank()) {
                    val brave = trySearchBrave(trimmed, braveApiKey, maxResults)
                    if (brave != null && brave.items.isNotEmpty()) return@withContext brave
                }
                search(trimmed, maxResults)
            }
            else -> {
                search(trimmed, maxResults)
            }
        }
    }

    suspend fun search(query: String, maxResults: Int = 4): SearchResult = withContext(Dispatchers.IO) {
        val trimmed = query.trim().take(300)
        if (trimmed.isBlank()) {
            return@withContext SearchResult(trimmed, "DuckDuckGo", emptyList(), "Empty search query")
        }

        // 1. Try DuckDuckGo Lite HTML search
        val liteResult = trySearchLite(trimmed, maxResults)
        if (liteResult != null && liteResult.items.isNotEmpty()) {
            return@withContext liteResult
        }

        // 2. Fallback to DuckDuckGo Instant Answer API
        val instantResult = trySearchInstantAnswer(trimmed, maxResults)
        if (instantResult != null && instantResult.items.isNotEmpty()) {
            return@withContext instantResult
        }

        // 3. Fallback to Wikipedia Lookup (matches "DuckDuckGo sometimes refuses automated searches, then Wikipedia answers instead.")
        val wikiResult = trySearchWikipedia(trimmed, maxResults)
        if (wikiResult != null && wikiResult.items.isNotEmpty()) {
            return@withContext wikiResult
        }

        SearchResult(
            query = trimmed,
            provider = "DuckDuckGo",
            items = emptyList(),
            note = "DuckDuckGo search did not return results. Please try a different query."
        )
    }

    private fun trySearchLite(query: String, maxResults: Int): SearchResult? {
        return try {
            val formBody = FormBody.Builder()
                .add("q", query)
                .build()

            val request = Request.Builder()
                .url("https://lite.duckduckgo.com/lite/")
                .post(formBody)
                .header(
                    "User-Agent",
                    "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"
                )
                .header("Content-Type", "application/x-www-form-urlencoded")
                .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                .build()

            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return null
                val html = response.body.string()

                val linkMatcher = LINK_PATTERN.matcher(html)
                val snippetMatcher = SNIPPET_PATTERN.matcher(html)

                val links = mutableListOf<Pair<String, String>>()
                while (linkMatcher.find()) {
                    val rawUrl = linkMatcher.group(1) ?: linkMatcher.group(3) ?: linkMatcher.group(5) ?: ""
                    val rawTitle = linkMatcher.group(2) ?: linkMatcher.group(4) ?: linkMatcher.group(6) ?: ""
                    if (rawUrl.isNotBlank() && !rawUrl.startsWith("/")) {
                        links.add(rawUrl to stripHtml(rawTitle))
                    }
                }

                val snippets = mutableListOf<String>()
                while (snippetMatcher.find()) {
                    snippets.add(stripHtml(snippetMatcher.group(1) ?: ""))
                }

                val items = mutableListOf<SearchItem>()
                val count = minOf(links.size, maxResults)
                for (i in 0 until count) {
                    val (url, title) = links[i]
                    val snippet = if (i < snippets.size) snippets[i] else ""
                    if (title.isNotBlank() && url.isNotBlank()) {
                        items.add(SearchItem(title = title, snippet = snippet, url = url))
                    }
                }

                if (items.isNotEmpty()) {
                    SearchResult(query, "DuckDuckGo", items)
                } else null
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun trySearchInstantAnswer(query: String, maxResults: Int): SearchResult? {
        return try {
            val url = "https://api.duckduckgo.com/".toHttpUrlOrNull()?.newBuilder()
                ?.addQueryParameter("q", query)
                ?.addQueryParameter("format", "json")
                ?.addQueryParameter("no_html", "1")
                ?.addQueryParameter("skip_disambig", "1")
                ?.build() ?: return null

            val request = Request.Builder()
                .url(url)
                .header("User-Agent", "SolusAndroid/1.0 (https://github.com/ShounakPatra/Solus)")
                .build()

            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return null
                val bodyStr = response.body.string()
                val json = JSONObject(bodyStr)

                val items = mutableListOf<SearchItem>()
                val abstractText = json.optString("AbstractText")
                val heading = json.optString("Heading")
                val abstractUrl = json.optString("AbstractURL")

                if (abstractText.isNotBlank()) {
                    items.add(
                        SearchItem(
                            title = if (heading.isNotBlank()) heading else query,
                            snippet = abstractText,
                            url = abstractUrl
                        )
                    )
                }

                val related = json.optJSONArray("RelatedTopics") ?: JSONArray()
                for (i in 0 until minOf(related.length(), maxResults - items.size)) {
                    val topic = related.optJSONObject(i) ?: continue
                    val text = topic.optString("Text")
                    val firstUrl = topic.optString("FirstURL")
                    if (text.isNotBlank()) {
                        val titlePart = text.substringBefore(" - ", query)
                        val snipPart = text.substringAfter(" - ", text)
                        items.add(SearchItem(title = titlePart, snippet = snipPart, url = firstUrl))
                    }
                }

                if (items.isNotEmpty()) {
                    SearchResult(query, "DuckDuckGo Instant Answer", items)
                } else null
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun trySearchWikipedia(query: String, maxResults: Int): SearchResult? {
        return try {
            val wikiJson = WikipediaLookup.lookup(query, client)
            val obj = JSONObject(wikiJson)
            val results = obj.optJSONArray("results") ?: return null
            val items = mutableListOf<SearchItem>()
            for (i in 0 until minOf(results.length(), maxResults)) {
                val r = results.getJSONObject(i)
                items.add(
                    SearchItem(
                        title = r.optString("title"),
                        snippet = r.optString("excerpt").ifBlank { r.optString("description") },
                        url = r.optString("url")
                    )
                )
            }
            if (items.isNotEmpty()) {
                SearchResult(
                    query = query,
                    provider = "Wikipedia (DuckDuckGo fallback)",
                    items = items,
                    note = "DuckDuckGo automated search was limited; Wikipedia search answers instead."
                )
            } else null
        } catch (_: Exception) {
            null
        }
    }

    private fun trySearchBrave(query: String, apiKey: String, maxResults: Int): SearchResult? {
        return try {
            val url = "https://api.search.brave.com/res/v1/web/search".toHttpUrlOrNull()?.newBuilder()
                ?.addQueryParameter("q", query)
                ?.addQueryParameter("count", maxResults.coerceIn(1, 10).toString())
                ?.build() ?: return null

            val request = Request.Builder()
                .url(url)
                .header("Accept", "application/json")
                .header("X-Subscription-Token", apiKey.trim())
                .build()

            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return null
                val body = response.body?.string() ?: return null
                val json = JSONObject(body)
                val web = json.optJSONObject("web") ?: return null
                val results = web.optJSONArray("results") ?: return null
                val items = mutableListOf<SearchItem>()
                for (i in 0 until minOf(results.length(), maxResults)) {
                    val obj = results.optJSONObject(i) ?: continue
                    val title = obj.optString("title").trim()
                    val description = obj.optString("description").trim()
                    val pageUrl = obj.optString("url").trim()
                    if (title.isNotBlank() && pageUrl.isNotBlank()) {
                        items.add(SearchItem(title, description, pageUrl))
                    }
                }
                if (items.isNotEmpty()) SearchResult(query, "Brave", items) else null
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun trySearchTavily(query: String, apiKey: String, maxResults: Int): SearchResult? {
        return try {
            val jsonBody = JSONObject().apply {
                put("api_key", apiKey.trim())
                put("query", query)
                put("max_results", maxResults.coerceIn(1, 10))
            }
            val requestBody = jsonBody.toString().toRequestBody("application/json; charset=utf-8".toMediaTypeOrNull())
            val request = Request.Builder()
                .url("https://api.tavily.com/search")
                .post(requestBody)
                .header("Content-Type", "application/json")
                .build()

            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return null
                val body = response.body?.string() ?: return null
                val json = JSONObject(body)
                val results = json.optJSONArray("results") ?: return null
                val items = mutableListOf<SearchItem>()
                for (i in 0 until minOf(results.length(), maxResults)) {
                    val obj = results.optJSONObject(i) ?: continue
                    val title = obj.optString("title").trim()
                    val content = obj.optString("content").trim()
                    val pageUrl = obj.optString("url").trim()
                    if (title.isNotBlank() && pageUrl.isNotBlank()) {
                        items.add(SearchItem(title, content, pageUrl))
                    }
                }
                if (items.isNotEmpty()) SearchResult(query, "Tavily", items) else null
            }
        } catch (_: Exception) {
            null
        }
    }
}
