package com.example.weiterb2

import android.content.Context
import org.json.JSONObject

object Catalog {
    fun load(context: Context): List<Track> {
        val root = JSONObject(context.assets.open("catalog.json").bufferedReader().use { it.readText() })
        require(root.getInt("schemaVersion") == 1) { "Unsupported catalog" }
        val entries = root.getJSONArray("tracks")
        val tracks = (0 until entries.length()).map { index ->
            val item = entries.getJSONObject(index)
            val refs = item.getJSONArray("references")
            Track(item.getString("id"), item.getInt("number"), item.getString("title"), item.getString("asset"), item.getLong("durationMs"), (0 until refs.length()).map { refIndex ->
                val ref = refs.getJSONObject(refIndex)
                BookReference(ref.getString("section"), if (ref.has("chapter")) ref.getInt("chapter") else null,
                    if (ref.has("chapterTitle")) ref.getString("chapterTitle") else null,
                    if (ref.has("page")) ref.getInt("page") else null,
                    if (ref.has("exercise")) ref.getString("exercise") else null,
                    ref.getString("evidence"), ref.getString("status"),
                    if (ref.has("timestampMs")) ref.getLong("timestampMs") else null)
            })
        }
        require(tracks.map { it.number } == (1..63).toList()) { "Incomplete audio catalog" }
        require(tracks.map { it.id }.distinct().size == 63)
        return tracks
    }
}
