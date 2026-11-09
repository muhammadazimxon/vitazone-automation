package com.example.vitazonebussinessautomation.domain.service

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class OrgInfoLookupService {
    private val cache = mutableMapOf<String, String>()

    private val client by lazy {
        HttpClient(CIO) {
            expectSuccess = false
        }
    }

    suspend fun lookupCompanyByInn(innInput: String): String? = withContext(Dispatchers.Default) {
        val inn = innInput.replace(Regex("\\D"), "")
        if (inn.length < 9) return@withContext null

        cache[inn]?.let { return@withContext it }

        try {
            val url = "https://orginfo.uz/ru/search/all/?q=$inn"
            val response = client.get(url) {
                header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
                header("Accept-Language", "ru-RU,ru;q=0.9")
            }

            if (response.status.value == 200) {
                val html = response.bodyAsText()
                val extracted = extractNameFromHtml(html, inn)
                if (!extracted.isNullOrBlank()) {
                    cache[inn] = extracted
                    return@withContext extracted
                }
            }
        } catch (e: Exception) {
            println("OrgInfoLookupService error for INN $inn: ${e.message}")
        }

        return@withContext null
    }

    private fun extractNameFromHtml(html: String, innStr: String): String? {
        var fromIndex = 0
        for (tries in 0 until 20) {
            val idx = html.indexOf(innStr, fromIndex)
            if (idx == -1) break
            fromIndex = idx + innStr.length

            val start = html.lastIndexOf("<a", idx)
            val end = html.indexOf("</a>", idx)
            if (start == -1 || end == -1 || start >= end) continue

            val anchor = html.substring(start, end)
            if (!anchor.contains("/organization/")) continue

            val cleanText = decodeHtmlEntities(anchor.replace(Regex("<[^>]+>"), " "))
                .replace(Regex("\\s+"), " ")
                .trim()

            if (!cleanText.contains(innStr)) continue

            val quotedRegex = Regex("[*\"«“'`]\\s*([^\\*\"»”'`]+?)\\s*[*\"»”'`]")
            val match = quotedRegex.find(cleanText) ?: Regex("\"([^\"]{2,}?)\"").find(cleanText)
            if (match != null && match.groupValues.size > 1) {
                val candidate = match.groupValues[1].trim()
                if (candidate.isNotEmpty()) return candidate
            }
        }
        return null
    }

    private fun decodeHtmlEntities(input: String): String {
        return input
            .replace("&quot;", "\"")
            .replace("&#34;", "\"")
            .replace("&ldquo;", "\"")
            .replace("&rdquo;", "\"")
            .replace("&amp;", "&")
            .replace("&#39;", "'")
            .replace("&#x27;", "'")
            .replace("&apos;", "'")
            .replace("&nbsp;", " ")
            .replace(Regex("&#(\\d+);")) { match ->
                val code = match.groupValues[1].toIntOrNull()
                if (code != null) code.toChar().toString() else match.value
            }
    }
}
