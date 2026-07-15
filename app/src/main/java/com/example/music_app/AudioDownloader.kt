package com.example.music_app

import android.content.Context
import android.os.Environment
import java.io.File
import java.net.URL

class AudioDownloader(private val context: Context) {
    fun download(song: Song): String {
        require(song.previewUrl.isNotBlank()) { context.getString(R.string.snackbar_no_download_link) }

        val musicDir = File(context.getExternalFilesDir(Environment.DIRECTORY_MUSIC), "DanceDeckMusic")
        if (!musicDir.exists()) musicDir.mkdirs()

        val fileName = safeFileName("${song.artist} - ${song.title}") + ".mp3"
        val output = uniqueFile(musicDir, fileName)

        URL(song.previewUrl).openStream().use { input ->
            output.outputStream().use { fileOut ->
                input.copyTo(fileOut)
            }
        }
        return output.absolutePath
    }

    private fun uniqueFile(dir: File, fileName: String): File {
        val base = fileName.substringBeforeLast(".")
        val ext = fileName.substringAfterLast(".", "mp3")
        var candidate = File(dir, fileName)
        var index = 1
        while (candidate.exists()) {
            candidate = File(dir, "$base-$index.$ext")
            index += 1
        }
        return candidate
    }

    private fun safeFileName(value: String): String {
        return value.replace(Regex("[\\\\/:*?\"<>|]+"), "_").take(80).ifBlank { "track" }
    }
}
