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

    override suspend fun getLatestUpdates(page: Int): MangasPage = MangasPage(emptyList(), false)

    // ---------------------------------------------------------------
    // BROWSE
    //
    // The website groups comics by year. One Mihon page maps to one
    // year category, newest first.
    // ---------------------------------------------------------------

    private val yearCategories = listOf(
        2026 to 9708,
        2025 to 9268,
        2024 to 8771,
        2023 to 8131,
        2022 to 7556,
        2021 to 7093,
        2020 to 6433,
        2019 to 5810,
        2018 to 5136,
        2017 to 4378,
        2016 to 3618,
        2015 to 2415,
        2014 to 2579,
        2013 to 2291,
        2012 to 1662,
        2011 to 1780,
        2010 to 2119,
    )

    override suspend fun getPopularManga(page: Int): MangasPage {
        if (page != 1) return MangasPage(emptyList(), false)

        val manga = SManga.create().apply {
            url = "/index?/category/9980"
            title = "The Recital [V11 URL PROBE]"
        }

        return MangasPage(listOf(manga), false)
    }

    private fun parseCategoryChildren(
        document: org.jsoup.nodes.Document,
    ): List<SManga> = document
        .select("img.category.thumbnail")
        .mapNotNull { image ->
            val link = image.parent()
            if (link?.tagName() != "a") return@mapNotNull null

            val href = link.attr("href").trim()
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
                .select("""ul.ui-autocomplete a, a[href*="/category/"]""")
                .mapNotNull { link ->
                    val href = link.attr("href").trim()
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

    override fun getMangaUrl(manga: SManga): String = absoluteSiteUrl(manga.url)

    override fun getChapterUrl(chapter: SChapter): String = absoluteSiteUrl(chapter.url)

    private fun absoluteSiteUrl(url: String): String = if (url.startsWith("http")) {
        url
    } else {
        "$baseUrl/${url.trimStart('/')}"
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
        val document = fetchCategoryDocument(9980)
        val pictureLinks = document.select("""a[href*="picture?"]""").size
        val thumbnails = document.select("img.thumbnail:not(.category)").size
        val pageTitle = document.title().ifBlank { "NO TITLE" }

        manga.status = SManga.UNKNOWN
        manga.description = buildString {
            append("V11 diagnostic\n")
            append("Title: $pageTitle\n")
            append("Picture links: $pictureLinks\n")
            append("Image thumbnails: $thumbnails")
        }

        val diagnosticChapter = SChapter.create().apply {
            url = "/index?/category/9980#v11"
            name = "Principal [V11] pics=$pictureLinks thumbs=$thumbnails"
            chapter_number = 0f
        }

        return SMangaUpdate(manga, listOf(diagnosticChapter))
    }

    private fun parseChapters(
        document: org.jsoup.nodes.Document,
        mangaUrl: String,
    ): List<SChapter> {
        val result = mutableListOf<SChapter>()

        if (document.select("img.thumbnail:not(.category)").isNotEmpty()) {
            result += SChapter.create().apply {
                url = mangaUrl
                name = "Principal"
                chapter_number = 0f
            }
        }

        document
            .select("img.category.thumbnail")
            .forEach { image ->
                val link = image.parent()
                if (link?.tagName() != "a") return@forEach

                val href = link.attr("href").trim()
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
        val document = fetchCategoryDocument(9980)

        return document
            .select("img.thumbnail:not(.category)")
            .mapIndexedNotNull { index, image ->
                val thumbnailUrl = image.absUrl("src")
                if (thumbnailUrl.isBlank()) return@mapIndexedNotNull null

                val imageUrl = thumbnailUrl.replace(
                    Regex("""-cu_[^.]+(?=\.[^.]+$)"""),
                    "-xx",
                )

                Page(index, imageUrl = imageUrl)
            }
    }

    private suspend fun fetchCategoryDocument(categoryId: Int): org.jsoup.nodes.Document {
        val urls = listOf(
            "$baseUrl/index?/category/$categoryId",
            "$baseUrl/index?%2Fcategory%2F$categoryId=",
            "$baseUrl/index.php?/category/$categoryId",
        )

        return urls
            .mapNotNull { url ->
                runCatching {
                    client.get(url).use { response ->
                        response.asJsoup()
                    }
                }.getOrNull()
            }
            .maxByOrNull { document ->
                document.select("""a[href*="picture?"]""").size * 10 +
                    document.select("img.thumbnail:not(.category)").size
            }
            ?: org.jsoup.nodes.Document(baseUrl)
    }

    private fun String.encodeUrlParameter(): String = java.net.URLEncoder.encode(this, Charsets.UTF_8.name())
}
