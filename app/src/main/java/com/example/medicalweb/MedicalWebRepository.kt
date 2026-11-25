package com.example.medicalweb

import android.util.Xml
import com.example.physiology.ManipulableParam
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.withContext
import org.xmlpull.v1.XmlPullParser
import java.io.StringReader

class MedicalWebRepository {

    suspend fun searchMedicalSummaries(query: String): Result<Pair<List<MedicalSummaryItem>, List<MedicalSummaryItem>>> =
        withContext(Dispatchers.IO) {
            val cleanQuery = query.trim()
            if (cleanQuery.isEmpty()) {
                return@withContext Result.failure(IllegalArgumentException("Enter a medical topic or biomarker to search."))
            }
            try {
                supervisorScope {
                    val medlineDeferred = async {
                        runCatching {
                            val xml = MedicalApiFactory.fetchMedlinePlusXml(cleanQuery)
                            parseMedlinePlusXml(xml, cleanQuery)
                        }.getOrElse { emptyList() }
                    }
                    val pubmedDeferred = async {
                        runCatching {
                            val searchResp = MedicalApiFactory.pubMedService.searchIds(term = cleanQuery, retmax = 4)
                            val ids = searchResp.esearchResult?.idlist.orEmpty()
                            if (ids.isEmpty()) emptyList()
                            else {
                                val xml = MedicalApiFactory.pubMedService.fetchAbstractsXml(ids = ids.joinToString(",")).string()
                                parsePubMedXml(xml)
                            }
                        }.getOrElse { emptyList() }
                    }

                    val medline = medlineDeferred.await()
                    val pubmed = pubmedDeferred.await()
                    if (medline.isEmpty() && pubmed.isEmpty()) {
                        Result.failure(IllegalStateException("No structured NIH MedlinePlus or PubMed summaries matched \"$cleanQuery\"."))
                    } else {
                        Result.success(medline to pubmed)
                    }
                }
            } catch (e: Exception) {
                Result.failure(e)
            }
        }

    private fun parseMedlinePlusXml(xml: String, query: String): List<MedicalSummaryItem> {
        if (xml.isBlank()) return emptyList()
        val items = mutableListOf<MedicalSummaryItem>()
        val parser = Xml.newPullParser()
        parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
        parser.setInput(StringReader(xml))

        var event = parser.eventType
        var inDocument = false
        var currentUrl = ""
        var currentContentName = ""
        var title = ""
        var fullSummary = ""
        var snippet = ""
        val meshes = mutableListOf<String>()

        while (event != XmlPullParser.END_DOCUMENT) {
            when (event) {
                XmlPullParser.START_TAG -> {
                    when (parser.name) {
                        "document" -> {
                            inDocument = true
                            currentUrl = parser.getAttributeValue(null, "url").orEmpty()
                            title = ""
                            fullSummary = ""
                            snippet = ""
                            meshes.clear()
                        }
                        "content" -> if (inDocument) {
                            currentContentName = parser.getAttributeValue(null, "name").orEmpty()
                            val text = readInnerText(parser)
                            when (currentContentName) {
                                "title" -> if (title.isBlank()) title = stripHtml(text)
                                "FullSummary" -> if (fullSummary.isBlank()) fullSummary = stripHtml(text)
                                "snippet" -> if (snippet.isBlank()) snippet = stripHtml(text)
                                "mesh" -> meshes += stripHtml(text)
                            }
                        }
                    }
                }
                XmlPullParser.END_TAG -> {
                    if (parser.name == "document" && inDocument) {
                        val rawBody = fullSummary.ifBlank { snippet }
                        val bullets = extractConciseBullets(rawBody)
                        val extractedNumbers = extractNumericParameters("$title $rawBody $query")
                        if (title.isNotBlank() && bullets.isNotEmpty()) {
                            items += MedicalSummaryItem(
                                id = "mlp_${items.size}_${title.hashCode()}",
                                source = MedicalSource.MEDLINE_PLUS,
                                title = title,
                                subtitle = if (meshes.isNotEmpty()) "NIH MedlinePlus • ${meshes.take(2).joinToString(", ")}" else "NIH MedlinePlus Health Topic",
                                summaryBullets = bullets,
                                extractedParameters = extractedNumbers,
                                referenceUrl = currentUrl,
                                linkedParam = inferLinkedParam("$title $rawBody $query"),
                            )
                        }
                        inDocument = false
                    }
                }
            }
            event = parser.next()
        }
        return items
    }

    private fun parsePubMedXml(xml: String): List<MedicalSummaryItem> {
        if (xml.isBlank()) return emptyList()
        val items = mutableListOf<MedicalSummaryItem>()
        val parser = Xml.newPullParser()
        parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
        parser.setInput(StringReader(xml))

        var event = parser.eventType
        var inArticle = false
        var pmid = ""
        var title = ""
        var journal = ""
        var year = ""
        val abstractParts = mutableListOf<String>()

        while (event != XmlPullParser.END_DOCUMENT) {
            when (event) {
                XmlPullParser.START_TAG -> {
                    when (parser.name) {
                        "PubmedArticle" -> {
                            inArticle = true
                            pmid = ""
                            title = ""
                            journal = ""
                            year = ""
                            abstractParts.clear()
                        }
                        "PMID" -> if (inArticle && pmid.isBlank()) {
                            pmid = readInnerText(parser).trim()
                        }
                        "ArticleTitle" -> if (inArticle && title.isBlank()) {
                            title = stripHtml(readInnerText(parser)).trim()
                        }
                        "ISOAbbreviation", "Title" -> if (inArticle && journal.isBlank()) {
                            journal = readInnerText(parser).trim()
                        }
                        "Year" -> if (inArticle && year.isBlank()) {
                            year = readInnerText(parser).trim()
                        }
                        "AbstractText" -> if (inArticle) {
                            val label = parser.getAttributeValue(null, "Label").orEmpty()
                            val body = stripHtml(readInnerText(parser)).trim()
                            if (body.isNotBlank()) {
                                abstractParts += if (label.isNotBlank()) "$label: $body" else body
                            }
                        }
                    }
                }
                XmlPullParser.END_TAG -> {
                    if (parser.name == "PubmedArticle" && inArticle) {
                        val combinedAbstract = abstractParts.joinToString(" ")
                        val bullets = extractConciseBullets(combinedAbstract.ifBlank { "Indexed clinical abstract available under PMID $pmid." })
                        val params = extractNumericParameters("$title $combinedAbstract")
                        if (title.isNotBlank()) {
                            items += MedicalSummaryItem(
                                id = "pmid_$pmid",
                                source = MedicalSource.PUBMED,
                                title = title,
                                subtitle = "PubMed PMID $pmid • ${journal.ifBlank { "NIH/NLM" }} ${year.ifBlank { "" }}".trim(),
                                summaryBullets = bullets,
                                extractedParameters = params,
                                referenceUrl = "https://pubmed.ncbi.nlm.nih.gov/$pmid/",
                                linkedParam = inferLinkedParam("$title $combinedAbstract"),
                            )
                        }
                        inArticle = false
                    }
                }
            }
            event = parser.next()
        }
        return items
    }

    private fun readInnerText(parser: XmlPullParser): String {
        val sb = StringBuilder()
        var depth = 1
        while (depth > 0) {
            when (parser.next()) {
                XmlPullParser.TEXT, XmlPullParser.CDSECT -> sb.append(parser.text.orEmpty())
                XmlPullParser.START_TAG -> depth++
                XmlPullParser.END_TAG -> depth--
                XmlPullParser.END_DOCUMENT -> break
            }
        }
        return sb.toString()
    }

    private fun stripHtml(raw: String): String =
        raw.replace(Regex("<[^>]*>"), " ")
            .replace("&amp;", "&")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&quot;", "\"")
            .replace("&#39;", "'")
            .replace(Regex("\\s+"), " ")
            .trim()

    private fun extractConciseBullets(text: String): List<String> {
        val sentences = text.split(Regex("(?<=[.!?])\\s+"))
            .map { it.trim() }
            .filter { it.length > 24 }
        return sentences.take(3).ifEmpty { listOf(text.take(220)) }
    }

    private fun extractNumericParameters(text: String): List<String> {
        val regex = Regex("\\b\\d+(?:\\.\\d+)?(?:\\s*[-–to]+\\s*\\d+(?:\\.\\d+)?)?\\s*(?:mg/dL|mEq/L|mmol/L|mmHg|mL/min|bpm|%|pH|μIU/mL|U/L)\\b", RegexOption.IGNORE_CASE)
        return regex.findAll(text)
            .map { it.value.trim() }
            .distinct()
            .take(5)
            .toList()
    }

    private fun inferLinkedParam(text: String): ManipulableParam? {
        val lower = text.lowercase()
        return when {
            "potassium" in lower || "hyperkalemia" in lower || "hypokalemia" in lower -> ManipulableParam.POTASSIUM
            "sodium" in lower || "hyponatremia" in lower || "hypernatremia" in lower -> ManipulableParam.SODIUM
            "insulin" in lower -> ManipulableParam.INSULIN
            "glucose" in lower || "diabetes" in lower || "glycem" in lower || "ketoacidosis" in lower -> ManipulableParam.GLUCOSE
            "oxygen" in lower || "hypoxia" in lower || "spo2" in lower || "respirat" in lower -> ManipulableParam.OXYGEN_INFLOW
            "blood pressure" in lower || "hypertension" in lower || "heart rate" in lower -> ManipulableParam.SYMPATHETIC
            "hydration" in lower || "dehydration" in lower || "volume" in lower || "renal" in lower -> ManipulableParam.HYDRATION
            else -> null
        }
    }
}
