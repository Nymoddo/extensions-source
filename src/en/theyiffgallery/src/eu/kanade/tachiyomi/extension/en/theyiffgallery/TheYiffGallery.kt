package eu.kanade.tachiyomi.extension.en.theyiffgallery

import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.model.SMangaUpdate
import keiyoushi.annotation.Source
import keiyoushi.network.get
import keiyoushi.source.KeiSource
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl

@Source
abstract class TheYiffGallery : KeiSource() {

    override val supportsLatest = false

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

    private val yearCategoryIds = yearCategories.map { it.second }.toSet()

    override suspend fun getLatestUpdates(page: Int): MangasPage = MangasPage(emptyList(), false)

    // ============================== Browse ==============================

    override suspend fun getPopularManga(page: Int): MangasPage {
        val year = yearCategories.getOrNull(page - 1)
            ?: return MangasPage(emptyList(), false)

        val mangas = getCategories(year.second)
            .filter { it.id != year.second && it.parentId == year.second }
            .map { it.toSManga() }

        return MangasPage(
            mangas = mangas,
            hasNextPage = page < yearCategories.size,
        )
    }

    // ============================== Search ==============================

    override suspend fun getSearchMangaList(
        page: Int,
        query: String,
        filters: FilterList,
    ): MangasPage {
        if (page != 1 || query.isBlank()) return MangasPage(emptyList(), false)

        val normalizedQuery = query.trim()

        val mangas = yearCategories
            .flatMap { (_, categoryId) ->
                getCategories(categoryId)
                    .filter { it.id != categoryId && it.parentId == categoryId }
            }
            .distinctBy { it.id }
            .filter { it.name.contains(normalizedQuery, ignoreCase = true) }
            .map { it.toSManga() }

        return MangasPage(mangas, false)
    }

    override fun getFilterList(data: JsonElement?): FilterList = FilterList()

    // ============================== URLs ==============================

    override fun getMangaUrl(manga: SManga): String {
        val categoryId = manga.url.categoryId()
        return "$baseUrl/index?/category/$categoryId"
    }

    override fun getChapterUrl(chapter: SChapter): String {
        val categoryId = chapter.url.categoryId()
        return "$baseUrl/index?/category/$categoryId"
    }

    override suspend fun getMangaByUrl(url: HttpUrl): SManga? {
        if (url.host != baseUrl.toHttpUrl().host) return null

        val categoryId = url.toString().categoryIdOrNull()
            ?: return null

        val category = getCategory(categoryId)
            ?: return null

        if (category.parentId !in yearCategoryIds) return null

        return category.toSManga()
    }

    // ============================== Details ==============================

    override suspend fun fetchMangaUpdate(
        manga: SManga,
        chapters: List<SChapter>,
        fetchDetails: Boolean,
        fetchChapters: Boolean,
    ): SMangaUpdate {
        val mangaCategoryId = manga.url.categoryId()

        val category = getCategory(mangaCategoryId)
        if (fetchDetails && category != null) {
            manga.title = category.name
            manga.thumbnail_url = category.thumbnailUrl
            manga.status = SManga.UNKNOWN
        }

        val updatedChapters = if (fetchChapters || chapters.isEmpty()) {
            getChapterList(mangaCategoryId)
        } else {
            chapters
        }

        return SMangaUpdate(manga, updatedChapters)
    }

    private suspend fun getChapterList(mangaCategoryId: Int): List<SChapter> {
        val result = mutableListOf<SChapter>()

        val directImages = getCategoryImageUrls(mangaCategoryId)
        if (directImages.isNotEmpty()) {
            result += SChapter.create().apply {
                url = "/index?/category/$mangaCategoryId"
                name = "Principal"
                chapter_number = 0f
            }
        }

        val subcategories = getCategories(mangaCategoryId)
            .filter { it.id != mangaCategoryId }
            .filter { it.nbImages == null || it.nbImages > 0 }

        subcategories.forEach { category ->
            result += SChapter.create().apply {
                url = "/index?/category/${category.id}"
                name = category.name
                chapter_number = result.size.toFloat()
            }
        }

        return result.reversed()
    }

    // ============================== Pages ==============================

    override suspend fun getPageList(chapter: SChapter): List<Page> {
        val categoryId = chapter.url.categoryId()

        return getCategoryImageUrls(categoryId).mapIndexed { index, imageUrl ->
            Page(index, imageUrl = imageUrl)
        }
    }

    private suspend fun getCategoryImageUrls(categoryId: Int): List<String> {
        val url = apiUrl(
            method = "pwg.categories.getImages",
            extra = "&cat_id=$categoryId&per_page=500&page=0",
        )

        return client.get(url).use { response ->
            val root = Json.parseToJsonElement(response.body.string()).jsonObject
            val result = root["result"]?.jsonObject ?: return@use emptyList()
            val images = result["images"]?.jsonArray ?: return@use emptyList()

            images.mapNotNull { imageElement ->
                val image = imageElement.jsonObject

                image["element_url"]
                    ?.jsonPrimitive
                    ?.contentOrNull
                    ?.takeIf { it.isNotBlank() }
                    ?: image["derivatives"]
                        ?.jsonObject
                        ?.get("xxlarge")
                        ?.jsonObject
                        ?.get("url")
                        ?.jsonPrimitive
                        ?.contentOrNull
                        ?.takeIf { it.isNotBlank() }
            }
        }
    }

    // ============================== Categories ==============================

    private suspend fun getCategory(categoryId: Int): PiwigoCategory? =
        getCategories(categoryId).firstOrNull { it.id == categoryId }

    private suspend fun getCategories(categoryId: Int): List<PiwigoCategory> {
        val url = apiUrl(
            method = "pwg.categories.getList",
            extra = "&cat_id=$categoryId&recursive=true&thumbnail_size=medium",
        )

        return client.get(url).use { response ->
            val root = Json.parseToJsonElement(response.body.string()).jsonObject
            val result = root["result"] ?: return@use emptyList()
            val categories = result.jsonObject["categories"]?.jsonArray
                ?: return@use emptyList()

            categories.mapNotNull { element ->
                val category = element.jsonObject
                val id = category["id"]
                    ?.jsonPrimitive
                    ?.contentOrNull
                    ?.toIntOrNull()
                    ?: return@mapNotNull null

                val name = category["name"]
                    ?.jsonPrimitive
                    ?.contentOrNull
                    ?.trim()
                    .orEmpty()

                if (name.isBlank()) return@mapNotNull null

                val uppercats = category["uppercats"]
                    ?.jsonPrimitive
                    ?.contentOrNull
                    ?.split(',')
                    ?.mapNotNull { it.trim().toIntOrNull() }
                    .orEmpty()

                val explicitParent = category["id_uppercat"]
                    ?.jsonPrimitive
                    ?.contentOrNull
                    ?.toIntOrNull()

                val parentId = explicitParent
                    ?: uppercats
                        .takeIf { it.size >= 2 }
                        ?.get(uppercats.lastIndex - 1)

                val thumbnailUrl = category["tn_url"]
                    ?.jsonPrimitive
                    ?.contentOrNull
                    ?.takeIf { it.isNotBlank() }

                val nbImages = category["nb_images"]
                    ?.jsonPrimitive
                    ?.contentOrNull
                    ?.toIntOrNull()

                PiwigoCategory(
                    id = id,
                    name = name,
                    parentId = parentId,
                    thumbnailUrl = thumbnailUrl,
                    nbImages = nbImages,
                )
            }
        }
    }

    private fun PiwigoCategory.toSManga(): SManga = SManga.create().apply {
        url = "/index?/category/${this@toSManga.id}"
        title = this@toSManga.name
        thumbnail_url = this@toSManga.thumbnailUrl
    }

    private fun apiUrl(method: String, extra: String = ""): String =
        "$baseUrl/ws.php?format=json&method=$method$extra"

    private fun String.categoryId(): Int =
        categoryIdOrNull() ?: throw IllegalArgumentException("Missing category ID in URL: $this")

    private fun String.categoryIdOrNull(): Int? {
        val decoded = runCatching {
            java.net.URLDecoder.decode(this, Charsets.UTF_8.name())
        }.getOrDefault(this)

        return CATEGORY_ID_REGEX.find(decoded)
            ?.groupValues
            ?.getOrNull(1)
            ?.toIntOrNull()
    }
}

private data class PiwigoCategory(
    val id: Int,
    val name: String,
    val parentId: Int?,
    val thumbnailUrl: String?,
    val nbImages: Int?,
)

private val CATEGORY_ID_REGEX = Regex("""category/(\d+)""")
