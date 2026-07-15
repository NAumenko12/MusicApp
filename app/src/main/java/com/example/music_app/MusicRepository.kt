package com.example.music_app

class MusicRepository(
    private val userId: Long,
    private val authDatabase: AuthDatabase,
    private val backendApi: BackendApi,
    private val authToken: String,
) {
    fun settings(): UserSettings = authDatabase.settings(userId)

    fun songs(query: String = "", genre: String = "all"): List<Song> = authDatabase.songs(userId, query, genre)

    fun favoriteSongs(): List<Song> = authDatabase.favoriteSongs(userId)

    fun downloadedSongs(): List<Song> = authDatabase.downloadedSongs(userId)

    fun recentSongs(): List<Song> = authDatabase.recentSongs(userId)

    fun song(songId: Long): Song? = authDatabase.song(songId)

    fun findSongByIdentity(song: Song): Song? {
        return authDatabase.findSongByIdentity(userId, song.title, song.artist, song.previewUrl)
    }

    fun playlists(): List<Playlist> = authDatabase.playlists(userId)

    fun songsInPlaylist(playlistId: Long): List<Song> = authDatabase.songsInPlaylist(playlistId)

    fun markSongPlayed(songId: Long) {
        authDatabase.markSongPlayed(userId, songId)
    }

    suspend fun syncFromBackend(): UserSettings {
        if (authToken.isBlank()) return settings()
        val remoteSettings = backendApi.settings(authToken, userId)
        authDatabase.saveSettings(remoteSettings)
        backendApi.songs(authToken).forEach { song ->
            authDatabase.upsertBackendSong(userId, song)
        }
        backendApi.playlists(authToken).forEach { playlist ->
            authDatabase.upsertBackendPlaylist(userId, playlist.copy(userId = userId))
            val playlistSongs = backendApi.playlistSongs(authToken, playlist.id)
            playlistSongs.forEach { song -> authDatabase.upsertBackendSong(userId, song) }
            authDatabase.replacePlaylistSongs(playlist.id, playlistSongs)
        }
        return remoteSettings
    }

    suspend fun addSong(song: Song): Song {
        findSongByIdentity(song)?.let { existing ->
            if (authToken.isNotBlank()) {
                runCatching {
                    val remote = backendApi.updateSong(authToken, existing.copy(inLibrary = true))
                    authDatabase.upsertBackendSong(userId, remote)
                    return remote
                }.recoverCatching {
                    val remote = backendApi.addSong(authToken, existing.copy(inLibrary = true))
                    authDatabase.upsertBackendSong(userId, remote)
                    return remote
                }
            }
            return existing
        }
        if (authToken.isNotBlank()) {
            runCatching {
                val remote = backendApi.addSong(authToken, song.copy(inLibrary = true))
                authDatabase.upsertBackendSong(userId, remote)
                return remote
            }.onFailure {
                runCatching { syncFromBackend() }
                findSongByIdentity(song)?.let { existing -> return existing }
            }
        }
        val id = authDatabase.addSong(
            userId = userId,
            title = song.title,
            artist = song.artist,
            album = song.album,
            durationSeconds = song.durationSeconds,
            genre = song.genre,
            coverUrl = song.coverUrl,
            previewUrl = song.previewUrl,
            downloaded = song.downloaded,
            localPath = song.localPath
        )
        return authDatabase.song(id) ?: song.copy(id = id, inLibrary = true)
    }

    suspend fun updateSong(song: Song): Song {
        if (song.id <= 0) return addSong(song)
        authDatabase.upsertBackendSong(userId, song.copy(inLibrary = true))
        if (authToken.isNotBlank()) {
            runCatching {
                val remote = backendApi.updateSong(authToken, song.copy(inLibrary = true))
                authDatabase.upsertBackendSong(userId, remote)
                return remote
            }
        }
        return song.copy(inLibrary = true)
    }

    suspend fun updateSongMedia(song: Song): Song {
        if (song.id <= 0) return song
        authDatabase.updateSongMedia(song.id, song.previewUrl, song.coverUrl, song.durationSeconds)
        return updateSong(authDatabase.song(song.id) ?: song)
    }

    suspend fun updateSongFlag(song: Song, column: String, enabled: Boolean): Song {
        authDatabase.updateSongFlag(song.id, column, enabled)
        val updated = authDatabase.song(song.id) ?: when (column) {
            "favorite" -> song.copy(favorite = enabled)
            "downloaded" -> song.copy(downloaded = enabled)
            else -> song.copy(inLibrary = enabled)
        }
        return updateSong(updated)
    }

    suspend fun markSongDownloaded(song: Song, localPath: String): Song {
        authDatabase.markSongDownloaded(song.id, localPath)
        val updated = authDatabase.song(song.id) ?: song.copy(downloaded = true, localPath = localPath)
        return updateSong(updated)
    }

    suspend fun deleteSong(songId: Long) {
        if (authToken.isNotBlank()) runCatching { backendApi.deleteSong(authToken, songId) }
        authDatabase.deleteSong(songId)
    }

    suspend fun createPlaylist(name: String, coverUrl: String): Playlist {
        if (authToken.isNotBlank()) {
            runCatching {
                val remote = backendApi.createPlaylist(authToken, name, coverUrl)
                authDatabase.upsertBackendPlaylist(userId, remote.copy(userId = userId))
                return remote.copy(userId = userId)
            }
        }
        val id = authDatabase.createPlaylist(userId, name, coverUrl)
        return authDatabase.playlists(userId).first { it.id == id }
    }

    suspend fun updatePlaylistDetails(playlistId: Long, name: String, coverUrl: String): Playlist? {
        val current = authDatabase.playlists(userId).firstOrNull { it.id == playlistId }
        val updated = (current ?: Playlist(playlistId, userId, name, 0, coverUrl)).copy(name = name, coverUrl = coverUrl)
        if (authToken.isNotBlank()) {
            runCatching {
                val remote = backendApi.updatePlaylist(authToken, updated)
                authDatabase.upsertBackendPlaylist(userId, remote.copy(userId = userId))
                return remote.copy(userId = userId)
            }
        }
        authDatabase.updatePlaylistDetails(playlistId, name, coverUrl)
        return authDatabase.playlists(userId).firstOrNull { it.id == playlistId }
    }

    suspend fun deletePlaylist(playlistId: Long) {
        if (authToken.isNotBlank()) runCatching { backendApi.deletePlaylist(authToken, playlistId) }
        authDatabase.deletePlaylist(playlistId)
    }

    suspend fun addSongToPlaylist(playlistId: Long, song: Song): Song {
        val savedSong = addSong(song)
        if (authToken.isNotBlank()) runCatching { backendApi.addSongToPlaylist(authToken, playlistId, savedSong.id) }
        authDatabase.addSongToPlaylist(playlistId, savedSong.id)
        return savedSong
    }

    suspend fun removeSongFromPlaylist(playlistId: Long, songId: Long) {
        if (authToken.isNotBlank()) runCatching { backendApi.removeSongFromPlaylist(authToken, playlistId, songId) }
        authDatabase.removeSongFromPlaylist(playlistId, songId)
    }

    suspend fun moveSongInPlaylist(playlistId: Long, songId: Long, direction: Int) {
        authDatabase.moveSongInPlaylist(playlistId, songId, direction)
        if (authToken.isNotBlank()) {
            authDatabase.songsInPlaylist(playlistId).forEachIndexed { index, song ->
                runCatching { backendApi.movePlaylistSong(authToken, playlistId, song.id, index) }
            }
        }
    }

    suspend fun saveSettings(settings: UserSettings): UserSettings {
        authDatabase.saveSettings(settings)
        if (authToken.isNotBlank()) {
            runCatching {
                val remote = backendApi.saveSettings(authToken, settings)
                authDatabase.saveSettings(remote)
                return remote
            }
        }
        return settings
    }

    suspend fun createVocalRemovalJob(song: Song): VocalRemovalJob {
        if (authToken.isBlank()) error("Backend token is missing")
        val savedSong = addSong(song)
        return backendApi.createVocalRemovalJob(authToken, savedSong)
    }

    suspend fun vocalRemovalJob(jobId: String): VocalRemovalJob {
        if (authToken.isBlank()) error("Backend token is missing")
        return backendApi.vocalRemovalJob(authToken, jobId)
    }

    fun cleanupDuplicateSongs() {
        authDatabase.cleanupDuplicateSongs(userId)
    }
}
