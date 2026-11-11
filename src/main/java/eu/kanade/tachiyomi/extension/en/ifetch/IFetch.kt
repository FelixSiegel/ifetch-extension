package eu.kanade.tachiyomi.extension.en.ifetch

import android.app.Application
import android.content.SharedPreferences
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import androidx.preference.EditTextPreference
import androidx.preference.ListPreference
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.asObservable
import eu.kanade.tachiyomi.source.ConfigurableSource
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.HttpSource
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Headers
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import rx.Observable
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.util.Collections

@Suppress("unused")
class IFetch : HttpSource(), ConfigurableSource {

    override val name = "iFetch API"

    override val baseUrl: String
        get() = preferences.getString(PREF_URL_KEY, PREF_URL_DEFAULT)!!

    override val lang = "en"
    override val supportsLatest = true

    private val preferences: SharedPreferences by lazy {
        Injekt.get<Application>().getSharedPreferences("source_$id", 0x0000)
    }

    private val json = Json { ignoreUnknownKeys = true }

    // Track manga IDs that have already been opened during this application session.
    private val seenMangaUrls = Collections.synchronizedSet(mutableSetOf<String>())

    override fun headersBuilder(): Headers.Builder = Headers.Builder()
        .add("User-Agent", "Mihon/iFetch")

    private fun parseMangaList(response: Response, hasNextPage: Boolean? = null): MangasPage {
        val body = response.body?.string() ?: return MangasPage(emptyList(), false)
        val array = json.parseToJsonElement(body).jsonArray

        val mangas = array.map { element ->
            val obj = element.jsonObject
            SManga.create().apply {
                title = obj["title"]?.jsonPrimitive?.content ?: ""
                url = obj["id"]?.jsonPrimitive?.content ?: ""
                thumbnail_url = obj["cover_url"]?.jsonPrimitive?.content ?: ""
            }
        }

        return MangasPage(mangas, hasNextPage ?: mangas.isNotEmpty())
    }

    // Search Manga
    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request {
        return GET("$baseUrl/api/search?q=$query", headers)
    }

    override fun searchMangaParse(response: Response): MangasPage = parseMangaList(response, hasNextPage = false)

    // Popular Manga
    override fun popularMangaRequest(page: Int): Request {
        return GET("$baseUrl/api/popular?page=$page", headers)
    }

    override fun popularMangaParse(response: Response): MangasPage = parseMangaList(response, hasNextPage = false)

    // Latest / Recent Manga
    override fun latestUpdatesRequest(page: Int): Request {
        val route = preferences.getString(PREF_LATEST_ROUTE_KEY, PREF_LATEST_ROUTE_DEFAULT)!!
        return GET("$baseUrl$route?page=$page", headers)
    }

    override fun latestUpdatesParse(response: Response): MangasPage = parseMangaList(response)

    // Manga Details
    override fun mangaDetailsRequest(manga: SManga): Request {
        return GET("$baseUrl/api/manga/${manga.url}", headers)
    }

    override fun mangaDetailsParse(response: Response): SManga {
        val body = response.body?.string() ?: return SManga.create()
        val obj = json.parseToJsonElement(body).jsonObject

        return SManga.create().apply {
            title = obj["title"]?.jsonPrimitive?.content ?: ""
            url = obj["id"]?.jsonPrimitive?.content ?: ""
            thumbnail_url = obj["cover_url"]?.jsonPrimitive?.content ?: ""
            description = obj["description"]?.jsonPrimitive?.content ?: ""
            author = obj["authors"]?.jsonArray?.joinToString(", ") { it.jsonPrimitive.content } ?: ""
            genre = obj["genres"]?.jsonArray?.joinToString(", ") { it.jsonPrimitive.content } ?: ""

            val statusStr = obj["status"]?.jsonPrimitive?.content?.lowercase() ?: ""
            status = when {
                statusStr.contains("ongoing") -> SManga.ONGOING
                statusStr.contains("completed") -> SManga.COMPLETED
                else -> SManga.UNKNOWN
            }
        }
    }

    // Chapters
    override fun chapterListRequest(manga: SManga): Request {
        val requestHeaders = headersBuilder()
            .add(HEADER_MANGA_INITIALIZED, manga.initialized.toString())
            .build()
        return GET("$baseUrl/api/manga/${manga.url}/chapters", requestHeaders)
    }

    override fun chapterListParse(response: Response): List<SChapter> {
        val body = response.body?.string() ?: return emptyList()
        val array = json.parseToJsonElement(body).jsonArray

        // Extract manga id from the request URL (e.g. /api/manga/{id}/chapters)
        val mangaId = response.request.url.pathSegments.let { it[it.size - 2] }
        val isInitialized = response.request.header(HEADER_MANGA_INITIALIZED)?.toBoolean() ?: false
        val isRefresh = isInitialized || seenMangaUrls.contains(mangaId)

        var hasMissing = false
        var hasDownloaded = false

        val chapters = array.map { element ->
            val obj = element.jsonObject
            val number = obj["number"]?.jsonPrimitive?.content ?: "0"
            val label = obj["label"]?.jsonPrimitive?.content
                ?: obj["title"]?.jsonPrimitive?.content
                ?: "Chapter $number"
            val downloaded = obj["downloaded"]?.jsonPrimitive?.booleanOrNull
                ?: obj["downloaded"]?.jsonPrimitive?.content?.toBooleanStrictOrNull()
                ?: false

            if (downloaded) {
                hasDownloaded = true
            } else {
                hasMissing = true
            }

            SChapter.create().apply {
                name = label
                chapter_number = number.toFloatOrNull() ?: 0f
                url = "$mangaId::$number"
            }
        }.reversed() // Mihon wants descending order

        val behavior = preferences.getString(PREF_DOWNLOAD_BEHAVIOR_KEY, PREF_DOWNLOAD_BEHAVIOR_DEFAULT)

        if (behavior == PREF_DOWNLOAD_BEHAVIOR_ALWAYS) {
            // Option 1: Chain download route on every fetch
            if (hasMissing) {
                triggerDownload(mangaId)
                if (!hasDownloaded && chapters.isNotEmpty()) {
                    throw Exception("Download started! Check back in a few minutes.")
                }
            }
            return chapters
        } else {
            // Option 2: Download only on refresh
            if (!isRefresh) {
                // First time opening the manga: only display info and chapter list
                seenMangaUrls.add(mangaId)
                if (hasMissing) {
                    showToast("Refresh again to download missing chapters")
                }
            } else {
                // Refresh detected: queue missing chapters
                if (hasMissing) {
                    when (val result = triggerDownload(mangaId)) {
                        is DownloadResult.Success -> {
                            if (result.queuedCount > 0) {
                                showToast("Download started for ${result.queuedCount} chapters! Check back in a few minutes.")
                            } else {
                                showToast("Download already in progress. Check back in a few minutes.")
                            }
                        }
                        is DownloadResult.Error -> {
                            showToast("Failed to queue download: ${result.message}")
                        }
                    }
                }
            }
            return chapters
        }
    }

    private fun triggerDownload(mangaId: String): DownloadResult {
        return try {
            val emptyBody = "{}".toRequestBody("application/json; charset=utf-8".toMediaTypeOrNull())
            val request = Request.Builder()
                .url("$baseUrl/api/manga/$mangaId/download")
                .post(emptyBody)
                .headers(headers)
                .build()

            client.newCall(request).execute().use { response ->
                if (response.isSuccessful || response.code == 202) {
                    val bodyStr = response.body?.string() ?: ""
                    val queuedCount = try {
                        json.parseToJsonElement(bodyStr)
                            .jsonObject["queued_count"]
                            ?.jsonPrimitive
                            ?.content
                            ?.toIntOrNull() ?: 0
                    } catch (_: Exception) {
                        0
                    }
                    DownloadResult.Success(queuedCount)
                } else {
                    DownloadResult.Error("HTTP ${response.code}")
                }
            }
        } catch (e: Exception) {
            DownloadResult.Error(e.message ?: "Network error")
        }
    }

    private fun showToast(message: String) {
        try {
            Handler(Looper.getMainLooper()).post {
                try {
                    Toast.makeText(Injekt.get<Application>(), message, Toast.LENGTH_LONG).show()
                } catch (_: Throwable) {
                }
            }
        } catch (_: Throwable) {
        }
    }

    // Pages
    override fun fetchPageList(chapter: SChapter): Observable<List<Page>> {
        return client.newCall(pageListRequest(chapter))
            .asObservable()
            .map { response ->
                if (response.code == 404) {
                    throw Exception("Chapter not downloaded yet. Refresh the manga to start download.")
                }
                if (!response.isSuccessful) {
                    throw Exception("HTTP error ${response.code}")
                }
                pageListParse(response)
            }
    }

    override fun pageListRequest(chapter: SChapter): Request {
        return GET("$baseUrl/api/chapter/${chapter.url}/pages", headers)
    }

    override fun pageListParse(response: Response): List<Page> {
        val body = response.body?.string() ?: return emptyList()
        val array = json.parseToJsonElement(body).jsonArray

        return array.mapIndexed { i, element ->
            val imgPath = element.jsonPrimitive.content
            Page(i, "", "$baseUrl$imgPath")
        }
    }

    override fun imageUrlParse(response: Response): String = ""

    // Settings
    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        val urlPref = EditTextPreference(screen.context).apply {
            key = PREF_URL_KEY
            title = "API Base URL"
            summary = "URL of your ifetch server (e.g. http://192.168.1.10:8080)"
            setDefaultValue(PREF_URL_DEFAULT)
            dialogTitle = "API Base URL"
        }
        screen.addPreference(urlPref)

        val latestRoutePref = ListPreference(screen.context).apply {
            key = PREF_LATEST_ROUTE_KEY
            title = "Recent / Latest tab route"
            entries = arrayOf("Latest updates (/api/latest)", "Newly added (/api/new)")
            entryValues = arrayOf("/api/latest", "/api/new")
            setDefaultValue(PREF_LATEST_ROUTE_DEFAULT)
            summary = "%s"
        }
        screen.addPreference(latestRoutePref)

        val downloadBehaviorPref = ListPreference(screen.context).apply {
            key = PREF_DOWNLOAD_BEHAVIOR_KEY
            title = "Download behavior"
            entries = arrayOf(
                "Download on refresh (View first, refresh to download)",
                "Always download (Auto-download when opening manga)"
            )
            entryValues = arrayOf(
                PREF_DOWNLOAD_BEHAVIOR_REFRESH,
                PREF_DOWNLOAD_BEHAVIOR_ALWAYS
            )
            setDefaultValue(PREF_DOWNLOAD_BEHAVIOR_DEFAULT)
            summary = "%s"
        }
        screen.addPreference(downloadBehaviorPref)
    }

    private sealed class DownloadResult {
        data class Success(val queuedCount: Int) : DownloadResult()
        data class Error(val message: String) : DownloadResult()
    }

    companion object {
        private const val HEADER_MANGA_INITIALIZED = "X-Manga-Initialized"

        private const val PREF_URL_KEY = "pref_api_url"
        private const val PREF_URL_DEFAULT = "http://10.0.2.2:8080" // Android emulator loopback

        private const val PREF_LATEST_ROUTE_KEY = "pref_latest_route"
        private const val PREF_LATEST_ROUTE_DEFAULT = "/api/latest"

        private const val PREF_DOWNLOAD_BEHAVIOR_KEY = "pref_download_behavior"
        private const val PREF_DOWNLOAD_BEHAVIOR_REFRESH = "refresh"
        private const val PREF_DOWNLOAD_BEHAVIOR_ALWAYS = "always"
        private const val PREF_DOWNLOAD_BEHAVIOR_DEFAULT = PREF_DOWNLOAD_BEHAVIOR_REFRESH
    }
}
