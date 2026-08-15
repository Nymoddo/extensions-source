package eu.kanade.tachiyomi.extension.en.theyiffgallery

import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.model.SMangaUpdate
import eu.kanade.tachiyomi.util.asJsoup
import keiyoushi.annotation.Source
import keiyoushi.network.get
import keiyoushi.source.KeiSource
import kotlinx.serialization.json.JsonElement
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl

@Source
abstract class TheYiffGallery : KeiSource() {

    private val comicsCategoryId = 1661

    override val supportsLatest = false

    override suspend fun getLatestUpdates(page: Int): MangasPage =
        MangasPage(emptyList(), false)

    // ---------------------------------------------------------------
    // BROWSE
    //
    // /index?/category/1661 contains the year categories.
    // We discover the year IDs from the root page instead of hardcoding
    // them, so adding a new year does not require a source update.
    // ---------------------------------------------------------------

    override suspend fun getPopularManga(page: Int): MangasPage {
        val years = client.get(comicsRootUrl()).use { response ->
            response.asJsoup()
                .select("""a[href*="index?/category/"] img.category.thumbnail""")
                .mapNotNull { image ->
                    val year = image.attr("alt").trim().toIntOrNull()
                        ?: image.attr("title").trim().toIntOrNull()
                        ?: return@mapNotNull null

                    if (year !in 2010..2100) return@mapNotNull null

                    val href = image.parent()?.absUrl("href").orEmpty()
                    if (href.isBlank()) return@mapNotNull null

                    year to href
                }
                .distinctBy { it.first }
                .sortedByDescending { it.first }
        }

        val yearEntry = years.getOrNull(page - 1)
            ?: return MangasPage(emptyList(), false)

        val mangas = client.get(yearEntry.second).use { response ->
            parseCategoryChildren(response.asJsoup())
        }

        return MangasPage(
            mangas = mangas,
            hasNextPage = page < years.size,
        )
    }

    private fun comicsRootUrl(): String = "$baseUrl/index?/category/$comicsCategoryId"

    private fun parseCategoryChildren(
        document: org.jsoup.nodes.Document,
    ): List<SManga> = document
        .select("""a[href*="index?/category/"] img.category.thumbnail""")
        .mapNotNull { image ->
            val href = image.parent()?.absUrl("href").orEmpty()
            val title = image.attr("alt")
                .ifBlank { image.attr("title") }
                .trim()

            if (href.isBlank() || title.isBlank()) return@mapNotNull null

            SManga.create().apply {
                setUrlWithoutDomain(href)
                this.title = title
                thumbnail_url = image.absUrl("src")
            }
        }
        .distinctBy { it.url }

    // ---------------------------------------------------------------
    // SEARCH
    //
    // The site's quick-search endpoint is qsearch.php?q=...
    // Its autocomplete results point directly to /index?/category/ID.
    // ---------------------------------------------------------------

    override suspend fun getSearchMangaList(
        page: Int,
        query: String,
        filters: FilterList,
    ): MangasPage {
        if (query.isBlank()) return MangasPage(emptyList(), false)

        val mangas = client.get(
            "$baseUrl/qsearch.php?q=${query.encodeUrlParameter()}",
        ).use { response ->
            response.asJsoup()
                .select("""a[href*="index?/category/"]""")
                .mapNotNull { link ->
                    val href = link.absUrl("href")
                    val title = link.text().trim()

                    if (href.isBlank() || title.isBlank()) return@mapNotNull null

                    SManga.create().apply {
                        setUrlWithoutDomain(href)
                        this.title = title
                    }
                }
                .distinctBy { it.url }
        }

        return MangasPage(mangas, false)
    }

    override fun getFilterList(data: JsonElement?): FilterList = FilterList()

    // ---------------------------------------------------------------
    // URL SEARCH / DEEPLINKS
    // ---------------------------------------------------------------

    override suspend fun getMangaByUrl(url: HttpUrl): SManga? {
        if (url.host != baseUrl.toHttpUrl().host) return null
        if (url.encodedPath != "/index") return null

        val query = url.encodedQuery ?: return null
        if (!query.startsWith("/category/")) return null

        return SManga.create().apply {
            this.url = "/index?$query"
            title = "Unknown"
        }
    }

    // ---------------------------------------------------------------
    // DETAILS + CHAPTERS
    //
    // A comic itself is a category.
    // Direct images = virtual "Principal" chapter.
    // Nested categories = separate chapters.
    // ---------------------------------------------------------------

    override suspend fun fetchMangaUpdate(
        manga: SManga,
        chapters: List<SChapter>,
        fetchDetails: Boolean,
        fetchChapters: Boolean,
    ): SMangaUpdate {
        client.get(getMangaUrl(manga)).use { response ->
            val document = response.asJsoup()

            // Keep title/thumbnail from the browse/search result.
            // Child category thumbnails are not necessarily the comic cover.
            if (fetchDetails && manga.title.isBlank()) {
                manga.title = document.selectFirst("title")
                    ?.text()
                    ?.substringBefore(" - ")
                    ?.trim()
                    .orEmpty()
            }

            manga.status = SManga.UNKNOWN

            val updatedChapters = if (fetchChapters || fetchDetails) {
                parseChapters(document, manga.url)
            } else {
                chapters
            }

            return SMangaUpdate(manga, updatedChapters)
        }
    }

    private fun parseChapters(
        document: org.jsoup.nodes.Document,
        mangaUrl: String,
    ): List<SChapter> {
        val result = mutableListOf<SChapter>()

        if (
            document.select(
                """a[href*="picture?"] img.thumbnail:not(.category)""",
            ).isNotEmpty()
        ) {
            result += SChapter.create().apply {
                url = mangaUrl
                name = "Principal"
                chapter_number = 0f
            }
        }

        document
            .select("""a[href*="index?/category/"] img.category.thumbnail""")
            .forEach { image ->
                val href = image.parent()?.absUrl("href").orEmpty()
                if (href.isBlank()) return@forEach

                val name = image.attr("alt")
                    .ifBlank { image.attr("title") }
                    .trim()
                    .ifBlank { "Subcategory" }

                result += SChapter.create().apply {
                    setUrlWithoutDomain(href)
                    this.name = name
                    chapter_number = result.size.toFloat()
                }
            }

        return result.reversed()
    }

    // ---------------------------------------------------------------
    // PAGES
    //
    // A chapter URL is a category. Its thumbnails point to picture?
    // pages. Each picture? page contains #theMainImage.
    // ---------------------------------------------------------------

    override suspend fun getPageList(chapter: SChapter): List<Page> {
        val chapterUrl = getChapterUrl(chapter)

        return client.get(chapterUrl).use { response ->
            val document = response.asJsoup()

            val pictureUrls = document
                .select("""a[href*="picture?"]""")
                .mapNotNull { it.absUrl("href").takeIf(String::isNotBlank) }
                .distinct()

            pictureUrls.mapIndexedNotNull { index, pictureUrl ->
                runCatching {
                    client.get(pictureUrl).use { pictureResponse ->
                        val pictureDocument = pictureResponse.asJsoup()
                        val image = pictureDocument.selectFirst("#theMainImage")
                            ?: return@use null

                        val imageUrl = image.absUrl("src")
                        if (imageUrl.isBlank()) return@use null

                        Page(index, imageUrl = imageUrl)
                    }
                }.getOrNull()
            }
        }
    }

    private fun String.encodeUrlParameter(): String = java.net.URLEncoder.encode(this, Charsets.UTF_8.name())
}
