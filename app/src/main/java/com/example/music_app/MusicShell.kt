package com.example.music_app

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

@Composable
fun MusicShell(
    user: User?,
    authDatabase: AuthDatabase,
    backendApi: BackendApi,
    authToken: String,
    showMessage: (String) -> Unit,
    onSettingsChanged: (UserSettings) -> Unit,
    onLogout: () -> Unit,
) {
    val activeUser = user ?: return
    val context = LocalContext.current
    val deezerApi = remember { DeezerApi() }
    val downloader = remember { AudioDownloader(context) }
    val player = remember { AudioPlayerController(context) }
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    var tab by rememberSaveable { mutableStateOf(AppTab.Home) }
    var refresh by remember { mutableIntStateOf(0) }
    var vocalsRemoved by rememberSaveable { mutableStateOf(false) }
    var vocalJobStatus by rememberSaveable { mutableStateOf("idle") }
    var menuOpen by rememberSaveable { mutableStateOf(false) }
    val repository = remember(activeUser.id, authToken) {
        MusicRepository(activeUser.id, authDatabase, backendApi, authToken)
    }

    fun reload() {
        refresh += 1
    }

    LaunchedEffect(activeUser.id) {
        val settings = withContext(Dispatchers.IO) {
            runCatching { repository.syncFromBackend() }.getOrElse {
                repository.cleanupDuplicateSongs()
                repository.settings()
            }
        }
        onSettingsChanged(settings)
        player.applySettings(settings)
        vocalsRemoved = settings.vocalsRemoved
        reload()
    }

    LaunchedEffect(
        player.volume,
        player.speed,
        player.bpm,
        player.shuffleEnabled,
        player.repeatMode,
        player.voiceCountEnabled,
        player.measureCountEnabled,
        player.metronomeOverlayEnabled,
        vocalsRemoved
    ) {
        delay(250)
        val snapshot = player.settingsSnapshot(
            userId = activeUser.id,
            base = repository.settings(),
            vocalsRemoved = vocalsRemoved
        )
        withContext(Dispatchers.IO) {
            runCatching { repository.saveSettings(snapshot) }
        }
    }

    LaunchedEffect(player.isPlaying) {
        while (player.isPlaying) {
            delay(500)
            player.tick()
        }
    }

    player.errorMessage?.let { message ->
        LaunchedEffect(message) {
            showMessage(message)
        }
    }

    DisposableEffect(Unit) {
        onDispose { player.release() }
    }

    fun Song.withPlayableFieldsFrom(candidates: List<Song>): Song {
        val freshMatch = candidates.firstOrNull {
            it.title.trim().equals(title.trim(), ignoreCase = true) &&
                it.artist.trim().equals(artist.trim(), ignoreCase = true) &&
                (it.id <= 0 || it.previewUrl.isNotBlank())
        }
        val anyMatch = candidates.firstOrNull {
            it.title.trim().equals(title.trim(), ignoreCase = true) &&
                it.artist.trim().equals(artist.trim(), ignoreCase = true)
        }
        val match = freshMatch ?: anyMatch
        return copy(
            previewUrl = match?.previewUrl?.takeIf { it.isNotBlank() } ?: previewUrl,
            coverUrl = coverUrl.ifBlank { match?.coverUrl.orEmpty() },
            localPath = localPath.ifBlank { match?.localPath.orEmpty() },
            durationSeconds = if (durationSeconds > 1) durationSeconds else (match?.durationSeconds ?: durationSeconds)
        )
    }

    fun play(song: Song, queue: List<Song> = emptyList()) {
        val libraryQueue = repository.songs()
        val playableSong = song.withPlayableFieldsFrom(queue + libraryQueue)
        val mergedQueue = (queue + libraryQueue + playableSong)
            .distinctBy { "${it.title.trim().lowercase()}|${it.artist.trim().lowercase()}" }
            .map { it.withPlayableFieldsFrom(queue + libraryQueue + playableSong) }
        if (playableSong.id > 0 && playableSong.previewUrl.isNotBlank()) {
            scope.launch(Dispatchers.IO) {
                runCatching { repository.updateSongMedia(playableSong) }
            }
        }
        player.play(playableSong, mergedQueue)
        if (playableSong.id > 0) repository.markSongPlayed(playableSong.id)
        reload()
        tab = AppTab.Player
    }

    fun toggleFavorite(song: Song) {
        scope.launch {
            val updated = withContext(Dispatchers.IO) {
                val localSong = repository.addSong(song)
                repository.updateSongFlag(localSong, "favorite", !localSong.favorite)
            }
            if (player.currentSong?.id == updated.id || player.currentSong?.previewUrl == updated.previewUrl) {
                player.updateCurrentSong(updated)
            }
            reload()
            showMessage(
                context.getString(
                    if (updated.favorite) R.string.snackbar_favorite_added else R.string.snackbar_favorite_removed
                )
            )
        }
    }

    fun downloadSong(song: Song) {
        scope.launch {
            val localSong = withContext(Dispatchers.IO) { repository.addSong(song) }
            if (localSong.downloaded && localSong.localPath.isNotBlank() && File(localSong.localPath).exists()) {
                showMessage(context.getString(R.string.snackbar_already_downloaded))
                return@launch
            }
            if (localSong.previewUrl.isBlank()) {
                showMessage(context.getString(R.string.snackbar_no_download_link))
                return@launch
            }
            showMessage(context.getString(R.string.snackbar_downloading, localSong.title))
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val path = downloader.download(localSong)
                    repository.markSongDownloaded(localSong, path)
                }
            }
            result
                .onSuccess {
                    player.updateCurrentSong(it)
                    reload()
                    showMessage(context.getString(R.string.snackbar_downloaded_device))
                }
                .onFailure { showMessage(it.message ?: context.getString(R.string.snackbar_download_failed)) }
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        Box(modifier = Modifier.fillMaxSize()) {
            when (tab) {
                AppTab.Home -> HomeScreen(
                    user = activeUser,
                    repository = repository,
                    deezerApi = deezerApi,
                    refresh = refresh,
                    onPlay = ::play,
                    onFavorite = ::toggleFavorite,
                    onDownload = ::downloadSong,
                    onChanged = {
                        reload()
                        showMessage(it)
                    }
                )

                AppTab.Player -> PlayerScreen(
                    player = player,
                    vocalsRemoved = vocalsRemoved,
                    vocalJobStatus = vocalJobStatus,
                    onVocals = {
                        val enabled = it
                        val song = player.currentSong
                        if (!enabled) {
                            vocalsRemoved = false
                            vocalJobStatus = "idle"
                            player.setVocalReduction(false)
                        } else if (song == null || (song.previewUrl.isBlank() && song.localPath.isBlank())) {
                            showMessage(context.getString(R.string.snackbar_no_download_link))
                        } else {
                            scope.launch {
                                vocalsRemoved = true
                                player.setVocalReduction(true)
                                vocalJobStatus = "queued"
                                showMessage(context.getString(R.string.vocal_backend_started))
                                val result = withContext(Dispatchers.IO) {
                                    runCatching {
                                        val job = repository.createVocalRemovalJob(song)
                                        var current = job
                                        repeat(60) {
                                            if (current.status == "done" || current.status == "failed") return@repeat
                                            delay(1000)
                                            current = repository.vocalRemovalJob(current.id)
                                            withContext(Dispatchers.Main) {
                                                vocalJobStatus = current.status
                                            }
                                        }
                                        current
                                    }
                                }
                                result
                                    .onSuccess { job ->
                                        vocalJobStatus = job.status
                                        if (job.status == "done" && job.resultUrl.isNotBlank()) {
                                            val processed = song.copy(previewUrl = job.resultUrl, localPath = "", downloaded = false)
                                            player.play(processed, player.queueItems.map {
                                                if (it.id == song.id || it.previewUrl == song.previewUrl) processed else it
                                            })
                                            showMessage(context.getString(R.string.vocal_backend_ready))
                                        } else {
                                            vocalsRemoved = false
                                            player.setVocalReduction(false)
                                            showMessage(job.error.ifBlank { context.getString(R.string.vocal_backend_failed) })
                                        }
                                    }
                                    .onFailure {
                                        vocalJobStatus = "failed"
                                        vocalsRemoved = false
                                        player.setVocalReduction(false)
                                        showMessage(context.getString(R.string.vocal_backend_failed))
                                    }
                            }
                        }
                    },
                    onOpenEqualizer = { tab = AppTab.Equalizer },
                    onFavorite = {
                        player.currentSong?.let {
                            toggleFavorite(it)
                        }
                    },
                    onDownloaded = {
                        player.currentSong?.let {
                            if (it.id > 0 && it.downloaded) {
                                scope.launch {
                                    val updated = withContext(Dispatchers.IO) {
                                        repository.updateSongFlag(it, "downloaded", !it.downloaded)
                                    }
                                    player.updateCurrentSong(updated)
                                    reload()
                                    showMessage(context.getString(R.string.snackbar_removed_downloads))
                                }
                            } else {
                                downloadSong(it)
                            }
                        }
                    }
                )

                AppTab.Equalizer -> EqualizerScreen(
                    settings = repository.settings(),
                    song = player.currentSong,
                    isPlaying = player.isPlaying,
                    onSave = {
                        scope.launch(Dispatchers.IO) { runCatching { repository.saveSettings(it) } }
                        player.applyEqualizerSettings(it)
                        showMessage(context.getString(R.string.snackbar_eq_saved))
                    }
                )

                AppTab.Metronome -> MetronomeScreen(
                    initialBpm = repository.settings().metronomeBpm,
                    onSave = { bpm ->
                        val settings = repository.settings().copy(metronomeBpm = bpm)
                        scope.launch(Dispatchers.IO) { runCatching { repository.saveSettings(settings) } }
                        showMessage(context.getString(R.string.snackbar_bpm_saved))
                    }
                )

                AppTab.Library -> LibraryScreen(
                    repository = repository,
                    user = activeUser,
                    refresh = refresh,
                    onPlay = ::play,
                    onDownload = ::downloadSong,
                    onChanged = {
                        reload()
                        showMessage(it)
                    }
                )

                AppTab.Playlists -> PlaylistsScreen(
                    repository = repository,
                    user = activeUser,
                    refresh = refresh,
                    onPlay = ::play,
                    onChanged = {
                        reload()
                        showMessage(it)
                    }
                )

                AppTab.Profile -> ProfileScreen(
                    user = activeUser,
                    songs = repository.songs(),
                    playlists = repository.playlists(),
                    settings = repository.settings(),
                    onSaveSettings = {
                        scope.launch(Dispatchers.IO) { runCatching { repository.saveSettings(it) } }
                        onSettingsChanged(it)
                        reload()
                        showMessage(context.getString(R.string.snackbar_settings_saved))
                    },
                    onLogout = onLogout
                )
            }
        }
        TopBar(title = stringResource(tab.labelRes), onMenuClick = { menuOpen = true })
        if (menuOpen) {
            SideNavigation(
                activeTab = tab,
                user = activeUser,
                onTabChange = {
                    tab = it
                    menuOpen = false
                },
                onClose = { menuOpen = false }
            )
        }
    }
}
