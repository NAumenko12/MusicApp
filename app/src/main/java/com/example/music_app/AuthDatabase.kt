package com.example.music_app

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import java.security.MessageDigest
import kotlin.random.Random

class AuthDatabase(context: Context) : SQLiteOpenHelper(context, DATABASE_NAME, null, DATABASE_VERSION) {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE users (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                name TEXT NOT NULL,
                email TEXT NOT NULL UNIQUE,
                phone TEXT NOT NULL,
                password_hash TEXT NOT NULL,
                created_at INTEGER NOT NULL
            )
            """.trimIndent()
        )
        db.execSQL(
            """
            CREATE TABLE login_codes (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                email TEXT NOT NULL,
                code TEXT NOT NULL,
                expires_at INTEGER NOT NULL,
                used INTEGER NOT NULL DEFAULT 0,
                created_at INTEGER NOT NULL
            )
            """.trimIndent()
        )
        db.execSQL(
            """
            CREATE TABLE songs (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                user_id INTEGER NOT NULL,
                title TEXT NOT NULL,
                artist TEXT NOT NULL,
                album TEXT NOT NULL DEFAULT '',
                duration_seconds INTEGER NOT NULL,
                genre TEXT NOT NULL,
                cover_url TEXT NOT NULL DEFAULT '',
                preview_url TEXT NOT NULL DEFAULT '',
                local_path TEXT NOT NULL DEFAULT '',
                favorite INTEGER NOT NULL DEFAULT 0,
                in_library INTEGER NOT NULL DEFAULT 1,
                downloaded INTEGER NOT NULL DEFAULT 0,
                created_at INTEGER NOT NULL,
                FOREIGN KEY(user_id) REFERENCES users(id) ON DELETE CASCADE
            )
            """.trimIndent()
        )
        db.execSQL(
            """
            CREATE TABLE playlists (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                user_id INTEGER NOT NULL,
                name TEXT NOT NULL,
                cover_url TEXT NOT NULL DEFAULT '',
                created_at INTEGER NOT NULL,
                FOREIGN KEY(user_id) REFERENCES users(id) ON DELETE CASCADE
            )
            """.trimIndent()
        )
        db.execSQL(
            """
            CREATE TABLE playlist_songs (
                playlist_id INTEGER NOT NULL,
                song_id INTEGER NOT NULL,
                sort_order INTEGER NOT NULL DEFAULT 0,
                created_at INTEGER NOT NULL,
                PRIMARY KEY(playlist_id, song_id),
                FOREIGN KEY(playlist_id) REFERENCES playlists(id) ON DELETE CASCADE,
                FOREIGN KEY(song_id) REFERENCES songs(id) ON DELETE CASCADE
            )
            """.trimIndent()
        )
        createPlaybackHistoryIfMissing(db)
        db.execSQL(
            """
            CREATE TABLE user_settings (
                user_id INTEGER PRIMARY KEY,
                language TEXT NOT NULL DEFAULT 'ru',
                theme TEXT NOT NULL DEFAULT 'dark',
                default_volume REAL NOT NULL DEFAULT 0.75,
                default_speed REAL NOT NULL DEFAULT 1.0,
                metronome_bpm INTEGER NOT NULL DEFAULT 120,
                shuffle_enabled INTEGER NOT NULL DEFAULT 0,
                repeat_mode TEXT NOT NULL DEFAULT 'off',
                voice_count_enabled INTEGER NOT NULL DEFAULT 0,
                measure_count_enabled INTEGER NOT NULL DEFAULT 0,
                metronome_overlay_enabled INTEGER NOT NULL DEFAULT 0,
                vocals_removed INTEGER NOT NULL DEFAULT 0,
                eq60 INTEGER NOT NULL DEFAULT 0,
                eq230 INTEGER NOT NULL DEFAULT 0,
                eq910 INTEGER NOT NULL DEFAULT 0,
                eq3600 INTEGER NOT NULL DEFAULT 0,
                eq14000 INTEGER NOT NULL DEFAULT 0,
                FOREIGN KEY(user_id) REFERENCES users(id) ON DELETE CASCADE
            )
            """.trimIndent()
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 5) {
            addColumnIfMissing(db, "user_settings", "shuffle_enabled INTEGER NOT NULL DEFAULT 0")
            addColumnIfMissing(db, "user_settings", "repeat_mode TEXT NOT NULL DEFAULT 'off'")
            addColumnIfMissing(db, "user_settings", "voice_count_enabled INTEGER NOT NULL DEFAULT 0")
            addColumnIfMissing(db, "user_settings", "measure_count_enabled INTEGER NOT NULL DEFAULT 0")
            addColumnIfMissing(db, "user_settings", "metronome_overlay_enabled INTEGER NOT NULL DEFAULT 0")
            addColumnIfMissing(db, "user_settings", "vocals_removed INTEGER NOT NULL DEFAULT 0")
        }
        if (oldVersion < 6) {
            addColumnIfMissing(db, "playlist_songs", "sort_order INTEGER NOT NULL DEFAULT 0")
            normalizePlaylistOrders(db)
        }
        if (oldVersion < 7) {
            createPlaybackHistoryIfMissing(db)
        }
        if (oldVersion < 8) {
            addColumnIfMissing(db, "playlists", "cover_url TEXT NOT NULL DEFAULT ''")
            return
        }
        destructiveRecreate(db)
    }

    private fun addColumnIfMissing(db: SQLiteDatabase, table: String, definition: String) {
        runCatching { db.execSQL("ALTER TABLE $table ADD COLUMN $definition") }
    }

    private fun destructiveRecreate(db: SQLiteDatabase) {
        db.execSQL("DROP TABLE IF EXISTS playback_history")
        db.execSQL("DROP TABLE IF EXISTS playlist_songs")
        db.execSQL("DROP TABLE IF EXISTS playlists")
        db.execSQL("DROP TABLE IF EXISTS user_settings")
        db.execSQL("DROP TABLE IF EXISTS songs")
        db.execSQL("DROP TABLE IF EXISTS login_codes")
        db.execSQL("DROP TABLE IF EXISTS users")
        onCreate(db)
    }

    private fun normalizePlaylistOrders(db: SQLiteDatabase = writableDatabase) {
        db.rawQuery("SELECT id FROM playlists", emptyArray()).use { playlists ->
            while (playlists.moveToNext()) {
                val playlistId = playlists.getLong(0)
                val ids = db.rawQuery(
                    """
                    SELECT song_id
                    FROM playlist_songs
                    WHERE playlist_id = ?
                    ORDER BY sort_order ASC, created_at ASC
                    """.trimIndent(),
                    arrayOf(playlistId.toString())
                ).use { songs ->
                    buildList {
                        while (songs.moveToNext()) add(songs.getLong(0))
                    }
                }
                ids.forEachIndexed { index, songId ->
                    val values = ContentValues().apply { put("sort_order", index) }
                    db.update(
                        "playlist_songs",
                        values,
                        "playlist_id = ? AND song_id = ?",
                        arrayOf(playlistId.toString(), songId.toString())
                    )
                }
            }
        }
    }

    private fun createPlaybackHistoryIfMissing(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS playback_history (
                user_id INTEGER NOT NULL,
                song_id INTEGER NOT NULL,
                played_at INTEGER NOT NULL,
                PRIMARY KEY(user_id, song_id),
                FOREIGN KEY(user_id) REFERENCES users(id) ON DELETE CASCADE,
                FOREIGN KEY(song_id) REFERENCES songs(id) ON DELETE CASCADE
            )
            """.trimIndent()
        )
    }

    fun registerUser(name: String, email: String, phone: String, password: String): Result<User> {
        val normalizedEmail = email.trim().lowercase()
        if (findUserByEmail(normalizedEmail) != null) {
            return Result.failure(IllegalArgumentException("User already exists"))
        }

        val values = ContentValues().apply {
            put("name", name.trim())
            put("email", normalizedEmail)
            put("phone", phone.trim())
            put("password_hash", hashPassword(password))
            put("created_at", System.currentTimeMillis())
        }

        val id = writableDatabase.insert("users", null, values)
        if (id == -1L) {
            return Result.failure(IllegalStateException("Could not create user"))
        }
        createDefaultSettings(id)
        seedSongs(id)
        return Result.success(User(id, name.trim(), normalizedEmail, phone.trim()))
    }

    fun authenticate(email: String, password: String): User? {
        val normalizedEmail = email.trim().lowercase()
        val cursor = readableDatabase.query(
            "users",
            arrayOf("id", "name", "email", "phone"),
            "email = ? AND password_hash = ?",
            arrayOf(normalizedEmail, hashPassword(password)),
            null,
            null,
            null,
            "1"
        )
        cursor.use {
            if (!it.moveToFirst()) return null
            return User(
                id = it.getLong(0),
                name = it.getString(1),
                email = it.getString(2),
                phone = it.getString(3),
            )
        }
    }

    fun socialUser(email: String, provider: String): Result<User> {
        val normalizedEmail = email.trim().lowercase()
        findUserByEmail(normalizedEmail)?.let { return Result.success(it) }

        val values = ContentValues().apply {
            put("name", provider)
            put("email", normalizedEmail)
            put("phone", "")
            put("password_hash", hashPassword("$provider:${System.currentTimeMillis()}"))
            put("created_at", System.currentTimeMillis())
        }

        val id = writableDatabase.insert("users", null, values)
        if (id == -1L) {
            return Result.failure(IllegalStateException("Could not create social profile"))
        }
        createDefaultSettings(id)
        seedSongs(id)
        return Result.success(User(id, provider, normalizedEmail, ""))
    }


    fun findUserByEmail(email: String): User? {
        val normalizedEmail = email.trim().lowercase()
        val cursor = readableDatabase.query(
            "users",
            arrayOf("id", "name", "email", "phone"),
            "email = ?",
            arrayOf(normalizedEmail),
            null,
            null,
            null,
            "1"
        )
        cursor.use {
            if (!it.moveToFirst()) return null
            return User(
                id = it.getLong(0),
                name = it.getString(1),
                email = it.getString(2),
                phone = it.getString(3),
            )
        }
    }

    fun upsertBackendUser(user: User) {
        val values = ContentValues().apply {
            put("id", user.id)
            put("name", user.name.trim())
            put("email", user.email.trim().lowercase())
            put("phone", user.phone.trim())
            put("password_hash", "backend")
            put("created_at", System.currentTimeMillis())
        }
        writableDatabase.insertWithOnConflict("users", null, values, SQLiteDatabase.CONFLICT_REPLACE)
        createDefaultSettings(user.id)
    }

    fun createLoginCode(email: String): String {
        val code = Random.nextInt(100000, 999999).toString()
        val now = System.currentTimeMillis()
        val values = ContentValues().apply {
            put("email", email.trim().lowercase())
            put("code", code)
            put("expires_at", now + CODE_LIFETIME_MS)
            put("used", 0)
            put("created_at", now)
        }
        writableDatabase.insert("login_codes", null, values)
        return code
    }

    fun verifyLoginCode(email: String, code: String): Boolean {
        val normalizedEmail = email.trim().lowercase()
        val now = System.currentTimeMillis()
        val cursor = readableDatabase.query(
            "login_codes",
            arrayOf("id"),
            "email = ? AND code = ? AND used = 0 AND expires_at >= ?",
            arrayOf(normalizedEmail, code.trim(), now.toString()),
            null,
            null,
            "created_at DESC",
            "1"
        )
        cursor.use {
            if (!it.moveToFirst()) return false
            val id = it.getLong(0)
            val values = ContentValues().apply { put("used", 1) }
            writableDatabase.update("login_codes", values, "id = ?", arrayOf(id.toString()))
            return true
        }
    }

    fun songs(userId: Long, query: String = "", genre: String = "all"): List<Song> {
        val selection = buildString {
            append("user_id = ? AND in_library = 1")
            if (query.isNotBlank()) append(" AND (title LIKE ? OR artist LIKE ?)")
            if (genre != "all") append(" AND genre = ?")
        }
        val args = mutableListOf(userId.toString())
        if (query.isNotBlank()) {
            args += "%${query.trim()}%"
            args += "%${query.trim()}%"
        }
        if (genre != "all") args += genre
        val cursor = readableDatabase.query(
            "songs",
            SONG_COLUMNS,
            selection,
            args.toTypedArray(),
            null,
            null,
            "created_at DESC"
        )
        cursor.use {
            val items = mutableListOf<Song>()
            while (it.moveToNext()) items += it.toSong()
            return items.mergeDuplicateSongs()
        }
    }

    fun favoriteSongs(userId: Long): List<Song> = filteredSongs(userId, "favorite = 1")

    fun downloadedSongs(userId: Long): List<Song> = filteredSongs(userId, "downloaded = 1")

    fun recentSongs(userId: Long): List<Song> {
        val cursor = readableDatabase.rawQuery(
            """
            SELECT s.id, s.title, s.artist, s.album, s.duration_seconds, s.genre, s.favorite, s.in_library, s.downloaded, s.cover_url, s.preview_url, s.local_path
            FROM playback_history h
            INNER JOIN songs s ON s.id = h.song_id
            WHERE h.user_id = ? AND s.in_library = 1
            ORDER BY h.played_at DESC
            """.trimIndent(),
            arrayOf(userId.toString())
        )
        cursor.use {
            val items = mutableListOf<Song>()
            while (it.moveToNext()) items += it.toSong()
            return items.mergeDuplicateSongs()
        }
    }

    fun markSongPlayed(userId: Long, songId: Long) {
        if (songId <= 0) return
        val values = ContentValues().apply {
            put("user_id", userId)
            put("song_id", songId)
            put("played_at", System.currentTimeMillis())
        }
        writableDatabase.insertWithOnConflict("playback_history", null, values, SQLiteDatabase.CONFLICT_REPLACE)
    }

    fun addSong(
        userId: Long,
        title: String,
        artist: String,
        album: String,
        durationSeconds: Int,
        genre: String,
        coverUrl: String = "",
        previewUrl: String = "",
        downloaded: Boolean = false,
        localPath: String = "",
    ): Long {
        findSongByIdentity(userId, title, artist, previewUrl)?.let { existing ->
            val values = ContentValues().apply {
                put("in_library", 1)
                if (downloaded) {
                    put("downloaded", 1)
                    put("local_path", localPath)
                }
                if (previewUrl.isNotBlank() && existing.previewUrl.isBlank()) put("preview_url", previewUrl)
                if (coverUrl.isNotBlank() && existing.coverUrl.isBlank()) put("cover_url", coverUrl)
            }
            writableDatabase.update("songs", values, "id = ?", arrayOf(existing.id.toString()))
            return existing.id
        }
        val values = ContentValues().apply {
            put("user_id", userId)
            put("title", title.trim())
            put("artist", artist.trim())
            put("album", album.trim())
            put("duration_seconds", durationSeconds.coerceAtLeast(1))
            put("genre", genre)
            put("cover_url", coverUrl)
            put("preview_url", previewUrl)
            put("local_path", localPath)
            put("favorite", 0)
            put("in_library", 1)
            put("downloaded", if (downloaded) 1 else 0)
            put("created_at", System.currentTimeMillis())
        }
        return writableDatabase.insert("songs", null, values)
    }

    fun upsertBackendSong(userId: Long, song: Song): Long {
        val values = ContentValues().apply {
            put("id", song.id)
            put("user_id", userId)
            put("title", song.title.trim())
            put("artist", song.artist.trim())
            put("album", song.album.trim())
            put("duration_seconds", song.durationSeconds.coerceAtLeast(1))
            put("genre", song.genre)
            put("cover_url", song.coverUrl)
            put("preview_url", song.previewUrl)
            put("local_path", song.localPath)
            put("favorite", if (song.favorite) 1 else 0)
            put("in_library", if (song.inLibrary) 1 else 0)
            put("downloaded", if (song.downloaded) 1 else 0)
            put("created_at", System.currentTimeMillis())
        }
        writableDatabase.insertWithOnConflict("songs", null, values, SQLiteDatabase.CONFLICT_REPLACE)
        return song.id
    }

    fun updateSongMedia(songId: Long, previewUrl: String, coverUrl: String, durationSeconds: Int) {
        val values = ContentValues().apply {
            if (previewUrl.isNotBlank()) put("preview_url", previewUrl)
            if (coverUrl.isNotBlank()) put("cover_url", coverUrl)
            if (durationSeconds > 1) put("duration_seconds", durationSeconds)
        }
        if (values.size() > 0) {
            writableDatabase.update("songs", values, "id = ?", arrayOf(songId.toString()))
        }
    }

    fun findSongByPreview(userId: Long, previewUrl: String): Song? {
        if (previewUrl.isBlank()) return null
        val cursor = readableDatabase.query(
            "songs",
            SONG_COLUMNS,
            "user_id = ? AND preview_url = ?",
            arrayOf(userId.toString(), previewUrl),
            null,
            null,
            null,
            "1"
        )
        cursor.use {
            if (!it.moveToFirst()) return null
            return it.toSong()
        }
    }

    fun findSongByIdentity(userId: Long, title: String, artist: String, previewUrl: String = ""): Song? {
        findSongByPreview(userId, previewUrl)?.let { return it }
        if (title.isBlank() || artist.isBlank()) return null
        val cursor = readableDatabase.query(
            "songs",
            SONG_COLUMNS,
            "user_id = ? AND lower(title) = lower(?) AND lower(artist) = lower(?)",
            arrayOf(userId.toString(), title.trim(), artist.trim()),
            null,
            null,
            null,
            "1"
        )
        cursor.use {
            if (!it.moveToFirst()) return null
            return it.toSong()
        }
    }

    fun song(songId: Long): Song? {
        val cursor = readableDatabase.query(
            "songs",
            SONG_COLUMNS,
            "id = ?",
            arrayOf(songId.toString()),
            null,
            null,
            null,
            "1"
        )
        cursor.use {
            if (!it.moveToFirst()) return null
            return it.toSong()
        }
    }

    fun updateSongFlag(songId: Long, column: String, enabled: Boolean) {
        require(column in setOf("favorite", "downloaded", "in_library"))
        val values = ContentValues().apply { put(column, if (enabled) 1 else 0) }
        val identityCursor = readableDatabase.query(
            "songs",
            arrayOf("user_id", "title", "artist"),
            "id = ?",
            arrayOf(songId.toString()),
            null,
            null,
            null,
            "1"
        )
        identityCursor.use {
            if (it.moveToFirst()) {
                writableDatabase.update(
                    "songs",
                    values,
                    "user_id = ? AND lower(title) = lower(?) AND lower(artist) = lower(?)",
                    arrayOf(it.getLong(0).toString(), it.getString(1), it.getString(2))
                )
                return
            }
        }
        writableDatabase.update("songs", values, "id = ?", arrayOf(songId.toString()))
    }

    fun deleteSong(songId: Long) {
        readableDatabase.query(
            "songs",
            arrayOf("user_id", "title", "artist"),
            "id = ?",
            arrayOf(songId.toString()),
            null,
            null,
            null,
            "1"
        ).use { cursor ->
            if (cursor.moveToFirst()) {
                val matchingIds = readableDatabase.query(
                    "songs",
                    arrayOf("id"),
                    "user_id = ? AND lower(title) = lower(?) AND lower(artist) = lower(?)",
                    arrayOf(cursor.getLong(0).toString(), cursor.getString(1), cursor.getString(2)),
                    null,
                    null,
                    null
                ).use { ids ->
                    buildList {
                        while (ids.moveToNext()) add(ids.getLong(0))
                    }
                }
                matchingIds.forEach { id ->
                    writableDatabase.delete("playback_history", "song_id = ?", arrayOf(id.toString()))
                    writableDatabase.delete("playlist_songs", "song_id = ?", arrayOf(id.toString()))
                    writableDatabase.delete("songs", "id = ?", arrayOf(id.toString()))
                }
                return
            }
        }
        writableDatabase.delete("playlist_songs", "song_id = ?", arrayOf(songId.toString()))
        writableDatabase.delete("playback_history", "song_id = ?", arrayOf(songId.toString()))
        writableDatabase.delete("songs", "id = ?", arrayOf(songId.toString()))
    }

    fun markSongDownloaded(songId: Long, localPath: String) {
        val values = ContentValues().apply {
            put("downloaded", 1)
            put("local_path", localPath)
        }
        writableDatabase.update("songs", values, "id = ?", arrayOf(songId.toString()))
    }

    fun cleanupDuplicateSongs(userId: Long) {
        val duplicateGroups = readableDatabase.query(
            "songs",
            SONG_COLUMNS,
            "user_id = ? AND in_library = 1",
            arrayOf(userId.toString()),
            null,
            null,
            "created_at DESC"
        ).use { cursor ->
            val raw = mutableListOf<Song>()
            while (cursor.moveToNext()) raw += cursor.toSong()
            raw.groupBy { "${it.title.trim().lowercase()}|${it.artist.trim().lowercase()}" }
                .values
                .filter { it.size > 1 }
        }
        duplicateGroups.forEach { group ->
            val merged = group.mergeDuplicateSongs().first()
            val keep = group.firstOrNull { it.favorite } ?: group.firstOrNull { it.downloaded } ?: group.first()
            val values = ContentValues().apply {
                put("favorite", if (merged.favorite) 1 else 0)
                put("downloaded", if (merged.downloaded) 1 else 0)
                put("in_library", 1)
                put("preview_url", merged.previewUrl)
                put("cover_url", merged.coverUrl)
                put("local_path", merged.localPath)
                put("duration_seconds", merged.durationSeconds)
                put("genre", merged.genre)
            }
            writableDatabase.update("songs", values, "id = ?", arrayOf(keep.id.toString()))
            group.filter { it.id != keep.id }.forEach { duplicate ->
                readableDatabase.query(
                    "playlist_songs",
                    arrayOf("playlist_id"),
                    "song_id = ?",
                    arrayOf(duplicate.id.toString()),
                    null,
                    null,
                    null
                ).use { cursor ->
                    while (cursor.moveToNext()) {
                        val playlistValues = ContentValues().apply {
                            put("playlist_id", cursor.getLong(0))
                            put("song_id", keep.id)
                            put("sort_order", nextPlaylistSortOrder(cursor.getLong(0)))
                            put("created_at", System.currentTimeMillis())
                        }
                        writableDatabase.insertWithOnConflict("playlist_songs", null, playlistValues, SQLiteDatabase.CONFLICT_IGNORE)
                    }
                }
                writableDatabase.delete("playlist_songs", "song_id = ?", arrayOf(duplicate.id.toString()))
                writableDatabase.delete("songs", "id = ?", arrayOf(duplicate.id.toString()))
            }
        }
    }

    fun playlists(userId: Long): List<Playlist> {
        val cursor = readableDatabase.rawQuery(
            """
            SELECT p.id, p.user_id, p.name, COUNT(ps.song_id) AS song_count, p.cover_url
            FROM playlists p
            LEFT JOIN playlist_songs ps ON ps.playlist_id = p.id
            WHERE p.user_id = ?
            GROUP BY p.id
            ORDER BY p.created_at DESC
            """.trimIndent(),
            arrayOf(userId.toString())
        )
        cursor.use {
            val items = mutableListOf<Playlist>()
            while (it.moveToNext()) {
                items += Playlist(
                    id = it.getLong(0),
                    userId = it.getLong(1),
                    name = it.getString(2),
                    songCount = it.getInt(3),
                    coverUrl = it.getString(4),
                )
            }
            return items
        }
    }

    fun createPlaylist(userId: Long, name: String, coverUrl: String = ""): Long {
        val values = ContentValues().apply {
            put("user_id", userId)
            put("name", name.trim())
            put("cover_url", coverUrl.trim())
            put("created_at", System.currentTimeMillis())
        }
        return writableDatabase.insert("playlists", null, values)
    }

    fun upsertBackendPlaylist(userId: Long, playlist: Playlist): Long {
        val values = ContentValues().apply {
            put("id", playlist.id)
            put("user_id", userId)
            put("name", playlist.name.trim())
            put("cover_url", playlist.coverUrl.trim())
            put("created_at", System.currentTimeMillis())
        }
        writableDatabase.insertWithOnConflict("playlists", null, values, SQLiteDatabase.CONFLICT_REPLACE)
        return playlist.id
    }

    fun deletePlaylist(playlistId: Long) {
        writableDatabase.delete("playlists", "id = ?", arrayOf(playlistId.toString()))
    }

    fun renamePlaylist(playlistId: Long, name: String) {
        val values = ContentValues().apply { put("name", name.trim()) }
        writableDatabase.update("playlists", values, "id = ?", arrayOf(playlistId.toString()))
    }

    fun updatePlaylistDetails(playlistId: Long, name: String, coverUrl: String) {
        val values = ContentValues().apply {
            put("name", name.trim())
            put("cover_url", coverUrl.trim())
        }
        writableDatabase.update("playlists", values, "id = ?", arrayOf(playlistId.toString()))
    }

    fun addSongToPlaylist(playlistId: Long, songId: Long) {
        val values = ContentValues().apply {
            put("playlist_id", playlistId)
            put("song_id", songId)
            put("sort_order", nextPlaylistSortOrder(playlistId))
            put("created_at", System.currentTimeMillis())
        }
        writableDatabase.insertWithOnConflict("playlist_songs", null, values, SQLiteDatabase.CONFLICT_IGNORE)
    }

    fun replacePlaylistSongs(playlistId: Long, songs: List<Song>) {
        writableDatabase.delete("playlist_songs", "playlist_id = ?", arrayOf(playlistId.toString()))
        songs.forEachIndexed { index, song ->
            val values = ContentValues().apply {
                put("playlist_id", playlistId)
                put("song_id", song.id)
                put("sort_order", index)
                put("created_at", System.currentTimeMillis())
            }
            writableDatabase.insertWithOnConflict("playlist_songs", null, values, SQLiteDatabase.CONFLICT_REPLACE)
        }
    }

    fun removeSongFromPlaylist(playlistId: Long, songId: Long) {
        writableDatabase.delete(
            "playlist_songs",
            "playlist_id = ? AND song_id = ?",
            arrayOf(playlistId.toString(), songId.toString())
        )
    }

    fun moveSongInPlaylist(playlistId: Long, songId: Long, direction: Int) {
        val songs = songsInPlaylist(playlistId).toMutableList()
        val fromIndex = songs.indexOfFirst { it.id == songId }
        if (fromIndex == -1) return
        val toIndex = (fromIndex + direction).coerceIn(0, songs.lastIndex)
        if (fromIndex == toIndex) return
        val item = songs.removeAt(fromIndex)
        songs.add(toIndex, item)
        songs.forEachIndexed { index, song ->
            val values = ContentValues().apply { put("sort_order", index) }
            writableDatabase.update(
                "playlist_songs",
                values,
                "playlist_id = ? AND song_id = ?",
                arrayOf(playlistId.toString(), song.id.toString())
            )
        }
    }

    fun songsInPlaylist(playlistId: Long): List<Song> {
        val cursor = readableDatabase.rawQuery(
            """
            SELECT s.id, s.title, s.artist, s.album, s.duration_seconds, s.genre, s.favorite, s.in_library, s.downloaded, s.cover_url, s.preview_url, s.local_path
            FROM songs s
            INNER JOIN playlist_songs ps ON ps.song_id = s.id
            WHERE ps.playlist_id = ?
            ORDER BY ps.sort_order ASC, ps.created_at ASC
            """.trimIndent(),
            arrayOf(playlistId.toString())
        )
        cursor.use {
            val items = mutableListOf<Song>()
            while (it.moveToNext()) items += it.toSong()
            return items.mergeDuplicateSongs()
        }
    }

    private fun nextPlaylistSortOrder(playlistId: Long): Int {
        return readableDatabase.rawQuery(
            "SELECT COALESCE(MAX(sort_order), -1) + 1 FROM playlist_songs WHERE playlist_id = ?",
            arrayOf(playlistId.toString())
        ).use { cursor ->
            if (cursor.moveToFirst()) cursor.getInt(0) else 0
        }
    }

    fun settings(userId: Long): UserSettings {
        createDefaultSettings(userId)
        val cursor = readableDatabase.query(
            "user_settings",
            arrayOf(
                "user_id",
                "language",
                "theme",
                "default_volume",
                "default_speed",
                "metronome_bpm",
                "shuffle_enabled",
                "repeat_mode",
                "voice_count_enabled",
                "measure_count_enabled",
                "metronome_overlay_enabled",
                "vocals_removed",
                "eq60",
                "eq230",
                "eq910",
                "eq3600",
                "eq14000"
            ),
            "user_id = ?",
            arrayOf(userId.toString()),
            null,
            null,
            null,
            "1"
        )
        cursor.use {
            it.moveToFirst()
            return UserSettings(
                userId = it.getLong(0),
                language = it.getString(1),
                theme = it.getString(2),
                defaultVolume = it.getFloat(3),
                defaultSpeed = it.getFloat(4),
                metronomeBpm = it.getInt(5),
                shuffleEnabled = it.getInt(6) == 1,
                repeatMode = it.getString(7),
                voiceCountEnabled = it.getInt(8) == 1,
                measureCountEnabled = it.getInt(9) == 1,
                metronomeOverlayEnabled = it.getInt(10) == 1,
                vocalsRemoved = it.getInt(11) == 1,
                eq60 = it.getInt(12),
                eq230 = it.getInt(13),
                eq910 = it.getInt(14),
                eq3600 = it.getInt(15),
                eq14000 = it.getInt(16),
            )
        }
    }

    fun saveSettings(settings: UserSettings) {
        val values = ContentValues().apply {
            put("language", settings.language)
            put("theme", settings.theme)
            put("default_volume", settings.defaultVolume)
            put("default_speed", settings.defaultSpeed)
            put("metronome_bpm", settings.metronomeBpm)
            put("shuffle_enabled", if (settings.shuffleEnabled) 1 else 0)
            put("repeat_mode", settings.repeatMode)
            put("voice_count_enabled", if (settings.voiceCountEnabled) 1 else 0)
            put("measure_count_enabled", if (settings.measureCountEnabled) 1 else 0)
            put("metronome_overlay_enabled", if (settings.metronomeOverlayEnabled) 1 else 0)
            put("vocals_removed", if (settings.vocalsRemoved) 1 else 0)
            put("eq60", settings.eq60)
            put("eq230", settings.eq230)
            put("eq910", settings.eq910)
            put("eq3600", settings.eq3600)
            put("eq14000", settings.eq14000)
        }
        writableDatabase.update("user_settings", values, "user_id = ?", arrayOf(settings.userId.toString()))
    }

    private fun filteredSongs(userId: Long, extraWhere: String): List<Song> {
        val cursor = readableDatabase.query(
            "songs",
            SONG_COLUMNS,
            "user_id = ? AND in_library = 1 AND $extraWhere",
            arrayOf(userId.toString()),
            null,
            null,
            "created_at DESC"
        )
        cursor.use {
            val items = mutableListOf<Song>()
            while (it.moveToNext()) items += it.toSong()
            return items.mergeDuplicateSongs()
        }
    }

    private fun List<Song>.mergeDuplicateSongs(): List<Song> {
        return groupBy { "${it.title.trim().lowercase()}|${it.artist.trim().lowercase()}" }
            .values
            .map { group ->
                val primary = group.firstOrNull { it.favorite } ?: group.firstOrNull { it.downloaded } ?: group.first()
                primary.copy(
                    favorite = group.any { it.favorite },
                    downloaded = group.any { it.downloaded },
                    inLibrary = group.any { it.inLibrary },
                    localPath = group.firstOrNull { it.localPath.isNotBlank() }?.localPath ?: primary.localPath,
                    previewUrl = group.firstOrNull { it.previewUrl.isNotBlank() }?.previewUrl ?: primary.previewUrl,
                    coverUrl = group.firstOrNull { it.coverUrl.isNotBlank() }?.coverUrl ?: primary.coverUrl,
                )
            }
    }

    private fun createDefaultSettings(userId: Long) {
        val values = ContentValues().apply {
            put("user_id", userId)
            put("language", "ru")
            put("theme", "dark")
            put("default_volume", 0.75f)
            put("default_speed", 1.0f)
            put("metronome_bpm", 120)
            put("shuffle_enabled", 0)
            put("repeat_mode", "off")
            put("voice_count_enabled", 0)
            put("measure_count_enabled", 0)
            put("metronome_overlay_enabled", 0)
            put("vocals_removed", 0)
        }
        writableDatabase.insertWithOnConflict("user_settings", null, values, SQLiteDatabase.CONFLICT_IGNORE)
    }

    private fun seedSongs(userId: Long) {
        if (songs(userId).isNotEmpty()) return
        val demoSongs = listOf(
            arrayOf("Neon Floor", "DanceDeck", "Starter Pack", "198", "electronic"),
            arrayOf("Warm Up Groove", "DDM Session", "Practice", "164", "funk"),
            arrayOf("Late Night Pop", "Kai Beats", "City Lights", "212", "pop"),
            arrayOf("Rock Step", "Deck Band", "Rehearsal", "186", "rock"),
            arrayOf("Jazz Motion", "Green Room", "Improvisation", "241", "jazz"),
            arrayOf("Classic Pulse", "Studio Strings", "Training", "220", "classical"),
            arrayOf("Hip Hop Count", "Metro Crew", "Bars", "175", "hip-hop"),
        )
        demoSongs.forEach { item ->
            addSong(userId, item[0], item[1], item[2], item[3].toInt(), item[4])
        }
        createPlaylist(userId, "Workout")
    }

    private fun android.database.Cursor.toSong(): Song {
        return Song(
            id = getLong(0),
            title = getString(1),
            artist = getString(2),
            album = getString(3),
            durationSeconds = getInt(4),
            genre = getString(5),
            favorite = getInt(6) == 1,
            inLibrary = getInt(7) == 1,
            downloaded = getInt(8) == 1,
            coverUrl = getString(9),
            previewUrl = getString(10),
            localPath = getString(11),
        )
    }

    private fun hashPassword(password: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(password.toByteArray())
        return digest.joinToString("") { "%02x".format(it) }
    }

    companion object {
        private const val DATABASE_NAME = "dance_deck_music.db"
        private const val DATABASE_VERSION = 8
        private const val CODE_LIFETIME_MS = 10 * 60 * 1000L
        private val SONG_COLUMNS = arrayOf(
            "id",
            "title",
            "artist",
            "album",
            "duration_seconds",
            "genre",
            "favorite",
            "in_library",
            "downloaded",
            "cover_url",
            "preview_url",
            "local_path",
        )
    }
}
