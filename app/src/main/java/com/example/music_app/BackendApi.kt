package com.example.music_app

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

data class AuthSession(
    val token: String,
    val user: User,
)

data class VocalRemovalJob(
    val id: String,
    val songId: Long?,
    val status: String,
    val resultUrl: String,
    val error: String,
)

class BackendApi(
    private val baseUrl: String = "http://10.0.2.2:3000",
) {
    fun register(name: String, email: String, phone: String, password: String): User {
        val json = post(
            path = "/auth/register",
            body = JSONObject()
                .put("name", name)
                .put("email", email)
                .put("phone", phone)
                .put("password", password)
        )
        return json.getJSONObject("user").toUser()
    }

    fun login(email: String, password: String): User {
        val json = post(
            path = "/auth/login",
            body = JSONObject()
                .put("email", email)
                .put("password", password)
        )
        return json.getJSONObject("user").toUser()
    }

    fun sendCode(email: String) {
        post("/auth/send-code", JSONObject().put("email", email))
    }

    fun verifyCode(email: String, code: String): AuthSession {
        val json = post(
            path = "/auth/verify-code",
            body = JSONObject()
                .put("email", email)
                .put("code", code)
        )
        return AuthSession(
            token = json.getString("token"),
            user = json.getJSONObject("user").toUser()
        )
    }

    fun me(token: String): User {
        return get("/me", token).getJSONObject("user").toUser()
    }

    fun songs(token: String): List<Song> {
        val data = get("/library/songs", token).getJSONArray("songs")
        return data.toSongList()
    }

    fun addSong(token: String, song: Song): Song {
        val json = post(
            path = "/library/songs",
            token = token,
            body = JSONObject()
                .put("title", song.title)
                .put("artist", song.artist)
                .put("album", song.album)
                .put("durationSeconds", song.durationSeconds)
                .put("genre", song.genre)
                .put("coverUrl", song.coverUrl)
                .put("previewUrl", song.previewUrl)
                .put("localPath", song.localPath)
                .put("favorite", song.favorite)
                .put("downloaded", song.downloaded)
        )
        return json.getJSONObject("song").toSong(inLibrary = true)
    }

    fun updateSong(token: String, song: Song): Song {
        val json = patch(
            path = "/library/songs/${song.id}",
            token = token,
            body = JSONObject()
                .put("title", song.title)
                .put("artist", song.artist)
                .put("album", song.album)
                .put("durationSeconds", song.durationSeconds)
                .put("genre", song.genre)
                .put("coverUrl", song.coverUrl)
                .put("previewUrl", song.previewUrl)
                .put("localPath", song.localPath)
                .put("favorite", song.favorite)
                .put("downloaded", song.downloaded)
        )
        return json.getJSONObject("song").toSong(inLibrary = true)
    }

    fun deleteSong(token: String, songId: Long) {
        delete("/library/songs/$songId", token)
    }

    fun playlists(token: String): List<Playlist> {
        val data = get("/playlists", token).getJSONArray("playlists")
        return buildList {
            for (index in 0 until data.length()) {
                val item = data.getJSONObject(index)
                add(
                    Playlist(
                        id = item.getLong("id"),
                        userId = 0,
                        name = item.getString("name"),
                        songCount = item.optInt("songCount", 0),
                        coverUrl = item.optString("coverUrl")
                    )
                )
            }
        }
    }

    fun playlistSongs(token: String, playlistId: Long): List<Song> {
        return get("/playlists/$playlistId/songs", token).getJSONArray("songs").toSongList()
    }

    fun createPlaylist(token: String, name: String, coverUrl: String): Playlist {
        val json = post(
            path = "/playlists",
            token = token,
            body = JSONObject().put("name", name).put("coverUrl", coverUrl)
        )
        val item = json.getJSONObject("playlist")
        return Playlist(item.getLong("id"), 0, item.getString("name"), 0, item.optString("coverUrl"))
    }

    fun updatePlaylist(token: String, playlist: Playlist): Playlist {
        val json = patch(
            path = "/playlists/${playlist.id}",
            token = token,
            body = JSONObject().put("name", playlist.name).put("coverUrl", playlist.coverUrl)
        )
        val item = json.getJSONObject("playlist")
        return Playlist(item.getLong("id"), 0, item.getString("name"), playlist.songCount, item.optString("coverUrl"))
    }

    fun deletePlaylist(token: String, playlistId: Long) {
        delete("/playlists/$playlistId", token)
    }

    fun addSongToPlaylist(token: String, playlistId: Long, songId: Long) {
        post("/playlists/$playlistId/songs", JSONObject().put("songId", songId), token)
    }

    fun removeSongFromPlaylist(token: String, playlistId: Long, songId: Long) {
        delete("/playlists/$playlistId/songs/$songId", token)
    }

    fun movePlaylistSong(token: String, playlistId: Long, songId: Long, sortOrder: Int) {
        post("/playlists/$playlistId/songs/$songId/move", JSONObject().put("sortOrder", sortOrder), token)
    }

    fun settings(token: String, userId: Long): UserSettings {
        return get("/settings", token).getJSONObject("settings").toSettings(userId)
    }

    fun saveSettings(token: String, settings: UserSettings): UserSettings {
        val json = patch(
            path = "/settings",
            token = token,
            body = JSONObject()
                .put("volume", settings.defaultVolume)
                .put("speed", settings.defaultSpeed)
                .put("bpm", settings.metronomeBpm)
                .put("vocalCut", settings.vocalsRemoved)
                .put("voiceCount", settings.voiceCountEnabled)
                .put("beatOverlay", settings.metronomeOverlayEnabled)
                .put("repeatMode", settings.repeatMode)
                .put("shuffle", settings.shuffleEnabled)
                .put("language", settings.language)
        )
        return json.getJSONObject("settings").toSettings(settings.userId)
    }

    fun createVocalRemovalJob(token: String, song: Song): VocalRemovalJob {
        val json = post(
            path = "/audio/vocal-removal/jobs",
            token = token,
            body = JSONObject()
                .put("songId", if (song.id > 0) song.id else JSONObject.NULL)
                .put("sourceUrl", song.previewUrl.ifBlank { song.localPath })
        )
        return json.getJSONObject("job").toVocalRemovalJob()
    }

    fun vocalRemovalJob(token: String, jobId: String): VocalRemovalJob {
        return get("/audio/vocal-removal/jobs/$jobId", token).getJSONObject("job").toVocalRemovalJob()
    }

    private fun get(path: String, token: String? = null): JSONObject {
        return request("GET", path, null, token)
    }

    private fun post(path: String, body: JSONObject, token: String? = null): JSONObject {
        return request("POST", path, body, token)
    }

    private fun patch(path: String, body: JSONObject, token: String? = null): JSONObject {
        return request("PATCH", path, body, token)
    }

    private fun delete(path: String, token: String? = null): JSONObject {
        return request("DELETE", path, null, token)
    }

    private fun request(method: String, path: String, body: JSONObject?, token: String?): JSONObject {
        val connection = URL("$baseUrl$path").openConnection() as HttpURLConnection
        connection.requestMethod = method
        connection.connectTimeout = 10_000
        connection.readTimeout = 10_000
        connection.setRequestProperty("Accept", "application/json")
        if (token != null) connection.setRequestProperty("Authorization", "Bearer $token")
        if (body != null) {
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            connection.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
        }
        val status = connection.responseCode
        val stream = if (status in 200..299) connection.inputStream else connection.errorStream
        val raw = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
        val json = if (raw.isBlank()) JSONObject() else JSONObject(raw)
        if (status !in 200..299) {
            throw IOException(json.optString("error", "Backend request failed: $status"))
        }
        return json
    }

    private fun JSONObject.toUser(): User {
        return User(
            id = getLong("id"),
            name = getString("name"),
            email = getString("email"),
            phone = optString("phone")
        )
    }

    private fun JSONArray.toSongList(): List<Song> {
        return buildList {
            for (index in 0 until length()) add(getJSONObject(index).toSong(inLibrary = true))
        }
    }

    private fun JSONObject.toSong(inLibrary: Boolean): Song {
        return Song(
            id = getLong("id"),
            title = getString("title"),
            artist = getString("artist"),
            album = optString("album"),
            durationSeconds = optInt("durationSeconds", 30).coerceAtLeast(1),
            genre = optString("genre"),
            favorite = optBoolean("favorite"),
            inLibrary = inLibrary,
            downloaded = optBoolean("downloaded"),
            coverUrl = optString("coverUrl"),
            previewUrl = optString("previewUrl"),
            localPath = optString("localPath")
        )
    }

    private fun JSONObject.toSettings(userId: Long): UserSettings {
        return UserSettings(
            userId = userId,
            language = optString("language", "ru"),
            theme = "dark",
            defaultVolume = optDouble("volume", 0.75).toFloat(),
            defaultSpeed = optDouble("speed", 1.0).toFloat(),
            metronomeBpm = optInt("bpm", 120),
            shuffleEnabled = optBoolean("shuffle"),
            repeatMode = optString("repeatMode", "off"),
            voiceCountEnabled = optBoolean("voiceCount"),
            measureCountEnabled = false,
            metronomeOverlayEnabled = optBoolean("beatOverlay"),
            vocalsRemoved = optBoolean("vocalCut"),
            eq60 = 0,
            eq230 = 0,
            eq910 = 0,
            eq3600 = 0,
            eq14000 = 0
        )
    }

    private fun JSONObject.toVocalRemovalJob(): VocalRemovalJob {
        val rawResult = optString("resultUrl")
        val absoluteResult = if (rawResult.startsWith("/")) "$baseUrl$rawResult" else rawResult
        return VocalRemovalJob(
            id = getString("id"),
            songId = if (isNull("songId")) null else optLong("songId"),
            status = getString("status"),
            resultUrl = absoluteResult,
            error = optString("error")
        )
    }
}

class BackendSessionStore(context: Context) {
    private val prefs = context.getSharedPreferences("backend_session", Context.MODE_PRIVATE)

    fun token(): String? = prefs.getString("token", null)

    fun save(token: String) {
        prefs.edit().putString("token", token).apply()
    }

    fun clear() {
        prefs.edit().remove("token").apply()
    }
}
