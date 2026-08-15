package eu.kanade.tachiyomi.extension.all.theyiffgallery

import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.asJsoup
import eu.kanade.tachiyomi.source.HttpSource
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import okhttp3.Headers
import okhttp3.Request
import okhttp3.Response
import java.net.URLEncoder

class ComicsSiteExample : HttpSource() {

    override val name = "TheyYiffGallery"
    override val baseUrl = "https://theyiffgallery.com"
    override val lang = "en"
    override val supportsLatest = false

    override fun headersBuilder(): Headers.Builder = super.headersBuilder()
        .add("Referer", "$baseUrl/")

    // Root of the actual Comics section.
    private val comicsCategoryId = 1661

    // Known year categories from the site.
    // Add the remaining years here as they are collected.
    private val yearCategories = linkedMapOf(
        2026 to 9708,
        2025 to 9268,
    )

    // ---------------------------------------------------------------
    // BROWSE
    // ---------------------------------------------------------------

    override fun popularMangaRequest(page: Int): Request {
        val year = yearCategories.keys.elementAtOrNull(page - 1)
            ?: yearCategories.keys.last()
        val categoryId = yearCategories[year]!!

        return GET("$baseUrl/index?/category/$categoryId", headers)
    }

    override fun popularMangaParse(response: Response): MangasPage {
        val document = response.asJsoup()

        val mangas = document
            .select("a[href*=index?\\/category\\/] img.category.thumbnail")
            .mapNotNull { image ->
                val link = image.parent() ?: return@mapNotNull null
                val href = link.absUrl("href")
                if (href.isBlank()) return@mapNotNull null

                SManga.create().apply {
                    setUrlWithoutDomain(href)
                    title = image.attr("alt").ifBlank { image.attr("title") }
                    thumbnail_url = image.absUrl("src")
                }
            }

        // One year per Mihon page.
        val hasNextPage = yearCategories.keys.elementAtOrNull(
            yearCategories.keys.indexOfFirst { year ->
                response.request.url.toString().contains("${yearCategories[year]}")
            } + 1,
        ) != null

        return MangasPage(mangas, hasNextPage)
    }

    // ---------------------------------------------------------------
    // SEARCH
    // ---------------------------------------------------------------

    override fun searchMangaRequest(
        page: Int,
        query: String,
        filters: FilterList,
    ): Request {
        return GET(
            "$baseUrl/qsearch.php?q=${URLEncoder.encode(query, "UTF-8")}",
            headers,
        )
    }

    override fun searchMangaParse(response: Response): MangasPage {
        val document = response.asJsoup()

        val mangas = document
            .select("a[href*=index?\\/category\\/]")
            .mapNotNull { link ->
                val href = link.absUrl("href")
                val title = link.text().trim()

                if (href.isBlank() || title.isBlank()) {
                    return@mapNotNull null
                }

                SManga.create().apply {
                    setUrlWithoutDomain(href)
                    this.title = title
                }
            }
            .distinctBy { it.url }

        return MangasPage(mangas, false)
    }

    override fun getFilterList(): FilterList = FilterList()

    // ---------------------------------------------------------------
    // MANGA DETAILS
    // ---------------------------------------------------------------

    override fun mangaDetailsParse(response: Response): SManga {
        val document = response.asJsoup()
        val categoryImage = document.selectFirst("img.category.thumbnail")

        return SManga.create().apply {
            title = categoryImage?.attr("alt")
                ?.ifBlank { categoryImage.attr("title") }
                ?: document.selectFirst("title")?.text()
                ?: "Unknown"

            thumbnail_url = categoryImage?.absUrl("src")
            status = SManga.UNKNOWN
        }
    }

    // ---------------------------------------------------------------
    // CHAPTERS
    // ---------------------------------------------------------------

    override fun chapterListParse(response: Response): List<SChapter> {
        val document = response.asJsoup()
        val chapters = mutableListOf<SChapter>()

        // Images directly inside the comic become one virtual chapter.
        val hasDirectImages = document
            .select("a[href*=picture?] img.thumbnail:not(.category)")
            .isNotEmpty()

        if (hasDirectImages) {
            chapters += SChapter.create().apply {
                setUrlWithoutDomain(response.request.url.toString())
                name = "Principal"
                chapter_number = 0f
            }
        }

        // Nested categories become their own chapters.
        document
            .select("a[href*=index?\\/category\\/] img.category.thumbnail")
            .forEach { image ->
                val link = image.parent() ?: return@forEach
                val href = link.absUrl("href")
                if (href.isBlank()) return@forEach

                val name = image.attr("alt")
                    .ifBlank { image.attr("title") }
                    .ifBlank { "Subcategory" }

                chapters += SChapter.create().apply {
                    setUrlWithoutDomain(href)
                    this.name = name
                    chapter_number = chapters.size.toFloat() + 1f
                }
            }

        return chapters.reversed()
    }

    // ---------------------------------------------------------------
    // PAGES
    // ---------------------------------------------------------------

    override fun pageListParse(response: Response): List<Page> {
        val document = response.asJsoup()

        // A chapter URL points to a category. Each image thumbnail links to
        // a picture page, which contains the actual image as #theMainImage.
        val pictureUrls = document
            .select("a[href*=picture?]")
            .mapNotNull { it.absUrl("href").takeIf(String::isNotBlank) }
            .distinct()

        return pictureUrls.mapIndexedNotNull { index, pictureUrl ->
            val pictureResponse = try {
                client.newCall(GET(pictureUrl, headers)).execute()
            } catch (_: Exception) {
                return@mapIndexedNotNull null
            }

            pictureResponse.use { result ->
                val pictureDocument = result.asJsoup()
                val image = pictureDocument.selectFirst("#theMainImage")
                    ?: return@mapIndexedNotNull null

                val imageUrl = image.absUrl("src")
                if (imageUrl.isBlank()) return@mapIndexedNotNull null

                Page(index, imageUrl = imageUrl)
            }
        }
    }

    override fun imageUrlParse(response: Response): String =
        throw UnsupportedOperationException()
}
