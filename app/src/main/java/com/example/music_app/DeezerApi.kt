package com.example.music_app

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

class DeezerApi {
    fun chart(): List<Song> {
        return fetch("https://api.deezer.com/chart/0/tracks?limit=30", "deezer")
    }

    fun search(query: String, genre: String): List<Song> {
        val term = when {
            query.isNotBlank() -> query.trim()
            genre != "all" -> genre
            else -> "top hits"
        }
        return fetch("https://api.deezer.com/search?q=${urlEncode(term)}&limit=30", genre)
    }

    private fun fetch(url: String, genre: String): List<Song> {
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.requestMethod = "GET"
        connection.setRequestProperty("Accept", "application/json")
        connection.connectTimeout = 10_000
        connection.readTimeout = 10_000

        return connection.inputStream.bufferedReader().use { reader ->
            val root = JSONObject(reader.readText())
            val data = root.getJSONArray("data")
            buildList {
                for (index in 0 until data.length()) {
                    val track = data.getJSONObject(index)
                    val artist = track.getJSONObject("artist")
                    val album = track.getJSONObject("album")
                    add(
                        Song(
                            id = -track.getLong("id"),
                            title = track.getString("title"),
                            artist = artist.getString("name"),
                            album = album.optString("title"),
                            durationSeconds = track.optInt("duration", 30).coerceAtLeast(1),
                            genre = genre,
                            favorite = false,
                            inLibrary = false,
                            downloaded = false,
                            coverUrl = album.optString("cover_medium"),
                            previewUrl = track.optString("preview"),
                            localPath = "",
                        )
                    )
                }
            }
        }
    }

    private fun urlEncode(value: String): String {
        return java.net.URLEncoder.encode(value, Charsets.UTF_8.name())
    }
}
