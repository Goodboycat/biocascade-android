package com.example.medicalweb

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.ResponseBody
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory
import retrofit2.http.GET
import retrofit2.http.Query
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

@JsonClass(generateAdapter = true)
data class PubMedESearchResponse(
    @Json(name = "esearchresult") val esearchResult: PubMedESearchResult? = null,
)

@JsonClass(generateAdapter = true)
data class PubMedESearchResult(
    val count: String? = null,
    val idlist: List<String> = emptyList(),
)

interface PubMedApiService {
    @GET("esearch.fcgi")
    suspend fun searchIds(
        @Query("db") db: String = "pubmed",
        @Query("term") term: String,
        @Query("retmax") retmax: Int = 5,
        @Query("retmode") retmode: String = "json",
        @Query("sort") sort: String = "relevance",
        @Query("tool") tool: String = "BioCascade",
        @Query("email") email: String = "support@biocascade.app",
    ): PubMedESearchResponse

    @GET("efetch.fcgi")
    suspend fun fetchAbstractsXml(
        @Query("db") db: String = "pubmed",
        @Query("id") ids: String,
        @Query("retmode") retmode: String = "xml",
        @Query("tool") tool: String = "BioCascade",
        @Query("email") email: String = "support@biocascade.app",
    ): ResponseBody
}

object MedicalApiFactory {
    val okHttpClient: OkHttpClient by lazy {
        val logging = HttpLoggingInterceptor().apply {
            level = HttpLoggingInterceptor.Level.BASIC
        }
        OkHttpClient.Builder()
            .connectTimeout(12, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .addInterceptor(logging)
            .build()
    }

    val pubMedService: PubMedApiService by lazy {
        Retrofit.Builder()
            .baseUrl("https://eutils.ncbi.nlm.nih.gov/entrez/eutils/")
            .client(okHttpClient)
            .addConverterFactory(MoshiConverterFactory.create())
            .build()
            .create(PubMedApiService::class.java)
    }

    fun fetchMedlinePlusXml(query: String): String {
        val encoded = URLEncoder.encode(query.trim(), "UTF-8")
        val url = "https://wsearch.nlm.nih.gov/ws/query?db=healthTopics&term=$encoded&retmax=5&tool=BioCascade&email=support@biocascade.app"
        val req = Request.Builder().url(url).get().build()
        okHttpClient.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) error("MedlinePlus HTTP ${resp.code}")
            return resp.body?.string().orEmpty()
        }
    }
}
