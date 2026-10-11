package com.example.weiterb2

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

object Catalog {
    fun load(context: Context): List<Track> = loadLibrary(context).tracks

    fun loadLibrary(context: Context): LibraryCatalog {
        val root = JSONObject(context.assets.open("catalog.json").bufferedReader().use { it.readText() })
        require(root.getInt("schemaVersion") == 2) { "Unsupported catalog" }
        val books = root.getJSONArray("books").objects().map { book ->
            val bookId = book.getString("id")
            BookVolume(bookId, book.getString("familyId"), book.getString("familyTitle"), book.getString("title"), book.optInt("position"),
                book.getJSONArray("series").objects().map { AudioSeries(it.getString("id"), it.getString("title"), it.optInt("position")) },
                book.getJSONArray("packages").objects().map {
                    SourcePackage(it.getString("id"), bookId, it.getString("title"), it.getString("url"), it.getLong("bytes"), it.getString("sha256"), it.optInt("position"))
                },
                book.getJSONArray("tracks").objects().map { item ->
                    Track(item.getString("id"), item.getInt("number"), item.getString("title"), item.optString("asset"), item.getLong("durationMs"), references(item),
                        bookId, item.getString("seriesId"), item.getString("packageId"), item.getString("sourceMember"), item.getString("sha256"), item.getLong("bytes"))
                },
                book.getJSONArray("documents").objects().map {
                    BookDocument(it.getString("id"), bookId, it.getString("title"), it.getString("language"), it.getString("kind"), it.getString("url"),
                        it.getLong("bytes"), it.getString("sha256"), it.getInt("pageCount"), it.optInt("position"), references(it))
                }, book.optString("level"), book.optString("edition"), book.optString("isbn"))
        }
        return LibraryCatalog(books).also { library ->
            require(library.books.isNotEmpty()) { "Empty book catalog" }
            library.books.forEach { book ->
                val seriesIds = book.series.map { it.id }.toSet()
                val packageIds = book.packages.map { it.id }.toSet()
                require(book.tracks.all { it.seriesId in seriesIds && it.packageId in packageIds && it.durationMs > 0 }) { "Incomplete book catalog" }
                require(book.documents.all { it.language in setOf("de", "en") && it.pageCount > 0 }) { "Invalid document catalog" }
            }
        }
    }
    private fun references(item: JSONObject): List<BookReference> = item.optJSONArray("references")?.objects().orEmpty().map { ref ->
        BookReference(ref.getString("section"), ref.optionalInt("chapter"), ref.optionalString("chapterTitle"), ref.optionalInt("page"),
            ref.optionalString("exercise"), ref.getString("evidence"), ref.getString("status"),
            if (ref.has("timestampMs")) ref.getLong("timestampMs") else null)
    }
    private fun JSONObject.optionalInt(key: String): Int? = if (has(key)) getInt(key) else null
    private fun JSONObject.optionalString(key: String): String? = if (has(key)) getString(key) else null
    private fun JSONArray.objects(): List<JSONObject> = (0 until length()).map { getJSONObject(it) }
}
