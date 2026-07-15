package com.example.music_app

data class User(
    val id: Long,
    val name: String,
    val email: String,
    val phone: String,
)

data class Song(
    val id: Long,
    val title: String,
    val artist: String,
    val album: String,
    val durationSeconds: Int,
    val genre: String,
    val favorite: Boolean,
    val inLibrary: Boolean,
    val downloaded: Boolean,
    val coverUrl: String = "",
    val previewUrl: String = "",
    val localPath: String = "",
)

data class Playlist(
    val id: Long,
    val userId: Long,
    val name: String,
    val songCount: Int,
    val coverUrl: String = "",
)

data class UserSettings(
    val userId: Long,
    val language: String,
    val theme: String,
    val defaultVolume: Float,
    val defaultSpeed: Float,
    val metronomeBpm: Int,
    val shuffleEnabled: Boolean,
    val repeatMode: String,
    val voiceCountEnabled: Boolean,
    val measureCountEnabled: Boolean,
    val metronomeOverlayEnabled: Boolean,
    val vocalsRemoved: Boolean,
    val eq60: Int,
    val eq230: Int,
    val eq910: Int,
    val eq3600: Int,
    val eq14000: Int,
)
