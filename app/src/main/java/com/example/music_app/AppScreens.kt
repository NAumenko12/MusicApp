package com.example.music_app

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.example.music_app.ui.theme.MutedText
import com.example.music_app.ui.theme.NeonGreen
import com.example.music_app.ui.theme.Panel
import com.example.music_app.ui.theme.PanelSoft
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun HomeScreen(
    user: User,
    repository: MusicRepository,
    deezerApi: DeezerApi,
    refresh: Int,
    onPlay: (Song, List<Song>) -> Unit,
    onFavorite: (Song) -> Unit,
    onDownload: (Song) -> Unit,
    onChanged: (String) -> Unit,
) {
    val scope = rememberCoroutineScope()
    var search by rememberSaveable { mutableStateOf("") }
    var genre by rememberSaveable { mutableStateOf("all") }
    var remoteSongs by remember { mutableStateOf<List<Song>>(emptyList()) }
    var isLoading by remember { mutableStateOf(false) }
    var playlistTarget by remember { mutableStateOf<Song?>(null) }
    val localSongs = remember(refresh, search, genre) { repository.songs(search, genre) }
    val playlists = remember(refresh) { repository.playlists() }
    val deezerUnavailableMessage = stringResource(R.string.snackbar_deezer_unavailable)
    val addedPlaylistMessage = stringResource(R.string.snackbar_added_playlist)
    val addTrackFailedMessage = stringResource(R.string.snackbar_add_track_failed)
    val alreadyLibraryMessage = stringResource(R.string.snackbar_already_library)
    val addedLibraryMessage = stringResource(R.string.snackbar_added_library)

    fun loadRemote() {
        scope.launch {
            isLoading = true
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    if (search.isBlank() && genre == "all") deezerApi.chart() else deezerApi.search(search, genre)
                }
            }
            remoteSongs = result.getOrDefault(emptyList())
            isLoading = false
            result.exceptionOrNull()?.let { onChanged(deezerUnavailableMessage) }
        }
    }

    LaunchedEffect(search, genre) {
        loadRemote()
    }

    PlaylistPickerDialog(
        song = playlistTarget,
        playlists = playlists,
        onDismiss = { playlistTarget = null },
        onAdd = { playlist, song ->
            scope.launch {
                val result = withContext(Dispatchers.IO) {
                    runCatching { repository.addSongToPlaylist(playlist.id, song) }
                }
                if (result.isSuccess) {
                    onChanged(addedPlaylistMessage.format(playlist.name))
                } else {
                    onChanged(addTrackFailedMessage)
                }
                playlistTarget = null
            }
        }
    )

    ScreenLayout(title = stringResource(R.string.home_title), subtitle = stringResource(R.string.home_subtitle)) {
        AuthTextField(search, { search = it }, stringResource(R.string.home_search))
        GenreRow(active = genre, onChange = { genre = it })
        if (isLoading) Text(stringResource(R.string.loading_deezer), color = MutedText)
        SectionTitle(stringResource(R.string.trend_title))
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            remoteSongs.take(7).forEachIndexed { index, song ->
                TrendCard(index = index + 1, song = song, onPlay = { onPlay(song, remoteSongs) })
            }
        }
        SectionTitle(stringResource(R.string.for_you_title))
        val feed = remoteSongs.ifEmpty { localSongs }
        feed.take(12).forEach { song ->
            val showingRemote = remoteSongs.isNotEmpty()
            val savedSong = remember(refresh, song.previewUrl, song.title, song.artist) {
                repository.findSongByIdentity(song)
            }
            val visibleSong = savedSong?.copy(
                coverUrl = savedSong.coverUrl.ifBlank { song.coverUrl },
                previewUrl = savedSong.previewUrl.ifBlank { song.previewUrl },
                durationSeconds = if (savedSong.durationSeconds > 1) savedSong.durationSeconds else song.durationSeconds,
                inLibrary = true
            ) ?: if (showingRemote) song.copy(id = 0, inLibrary = false) else song
            HomeAddRow(
                song = visibleSong,
                onPlay = { onPlay(visibleSong, feed) },
                onAddToPlaylist = { playlistTarget = visibleSong.copy(
                    title = song.title,
                    artist = song.artist,
                    album = song.album,
                    durationSeconds = song.durationSeconds,
                    genre = song.genre,
                    coverUrl = song.coverUrl,
                    previewUrl = song.previewUrl
                ) },
                onAdd = {
                    if (savedSong != null || visibleSong.inLibrary) {
                        onChanged(alreadyLibraryMessage)
                    } else {
                        scope.launch {
                            val result = withContext(Dispatchers.IO) {
                                runCatching { repository.addSong(song) }
                            }
                            onChanged(if (result.isSuccess) addedLibraryMessage else addTrackFailedMessage)
                        }
                    }
                }
            )
        }
        if (feed.isEmpty()) EmptyState(stringResource(R.string.empty_search_title), stringResource(R.string.empty_search_body))
    }
}

@Composable
private fun TrendCard(index: Int, song: Song, onPlay: () -> Unit) {
    Card(
        onClick = onPlay,
        colors = CardDefaults.cardColors(containerColor = Panel),
        shape = RoundedCornerShape(8.dp),
        modifier = Modifier.width(118.dp)
    ) {
        Column(modifier = Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            AlbumTile(size = 96, index = index, coverUrl = song.coverUrl)
            Text(song.title, color = Color.White, maxLines = 1, style = MaterialTheme.typography.titleLarge)
            Text(song.artist, color = MutedText, maxLines = 1)
        }
    }
}

@Composable
private fun HomeAddRow(song: Song, onPlay: () -> Unit, onAdd: () -> Unit, onAddToPlaylist: () -> Unit) {
    var menuOpen by remember { mutableStateOf(false) }
    SectionCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            AlbumTile(size = 62, index = null, coverUrl = song.coverUrl)
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(start = 14.dp)
            ) {
                Text(song.title, style = MaterialTheme.typography.titleLarge, color = Color.White, maxLines = 1)
                Text(song.artist, color = MutedText, maxLines = 1)
                Text("↗ ${formatTime(song.durationSeconds)}", color = NeonGreen, style = MaterialTheme.typography.labelLarge)
            }
            Button(
                onClick = onPlay,
                colors = ButtonDefaults.buttonColors(containerColor = NeonGreen, contentColor = Color.Black),
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier.size(width = 82.dp, height = 56.dp)
            ) {
                Text("▶")
            }
            Box {
                TextButton(onClick = { menuOpen = true }) {
                    Text("⋯", color = Color.White, style = MaterialTheme.typography.headlineSmall)
                }
                DropdownMenu(
                    expanded = menuOpen,
                    onDismissRequest = { menuOpen = false },
                    containerColor = PanelSoft
                ) {
                    DropdownMenuItem(
                        text = { Text("+ ${stringResource(R.string.add_to_playlist)}", color = Color.White) },
                        onClick = {
                            menuOpen = false
                            onAddToPlaylist()
                        }
                    )
                }
            }
        }
        Button(
            onClick = onAdd,
            colors = ButtonDefaults.buttonColors(
                containerColor = if (song.inLibrary || song.id > 0) PanelSoft else NeonGreen,
                contentColor = if (song.inLibrary || song.id > 0) NeonGreen else Color.Black
            ),
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier
                .fillMaxWidth()
                .height(52.dp)
        ) {
            Text(
                if (song.inLibrary || song.id > 0) "✓ ${stringResource(R.string.added)}" else "+ ${stringResource(R.string.add)}",
                style = MaterialTheme.typography.titleLarge
            )
        }
    }
}

@Composable
private fun String.labelForVocalStatus(): String {
    return when (this) {
        "queued" -> stringResource(R.string.vocal_status_queued)
        "processing" -> stringResource(R.string.vocal_status_processing)
        "done" -> stringResource(R.string.vocal_status_done)
        "failed" -> stringResource(R.string.vocal_status_failed)
        else -> stringResource(R.string.vocal_status_idle)
    }
}

@Composable
fun PlayerScreen(
    player: AudioPlayerController,
    vocalsRemoved: Boolean,
    vocalJobStatus: String,
    onVocals: (Boolean) -> Unit,
    onOpenEqualizer: () -> Unit,
    onFavorite: () -> Unit,
    onDownloaded: () -> Unit,
) {
    val song = player.currentSong
    val duration = player.durationSeconds.coerceAtLeast(1)
    var showQueue by rememberSaveable { mutableStateOf(false) }
    val vocalBusy = vocalJobStatus == "queued" || vocalJobStatus == "processing"
    ScreenLayout(title = "", subtitle = "") {
        Box(
            modifier = Modifier
                .size(230.dp)
                .align(Alignment.CenterHorizontally)
                .clip(CircleShape)
                .border(8.dp, PanelSoft, CircleShape)
                .background(Brush.linearGradient(listOf(Color(0xFF24533F), PanelSoft))),
            contentAlignment = Alignment.Center
        ) {
            if (song?.coverUrl?.isNotBlank() == true) {
                RemoteCoverImage(song.coverUrl)
            } else {
                Text(if (player.isLoading) "..." else "♫", color = NeonGreen.copy(alpha = 0.8f), style = MaterialTheme.typography.displayLarge)
            }
        }
        Text(
            song?.title ?: stringResource(R.string.choose_song),
            style = MaterialTheme.typography.headlineMedium,
            color = Color.White,
            modifier = Modifier.align(Alignment.CenterHorizontally)
        )
        Text(
            song?.artist ?: stringResource(R.string.from_home_page),
            style = MaterialTheme.typography.titleLarge,
            color = MutedText,
            modifier = Modifier.align(Alignment.CenterHorizontally)
        )
        if (song?.previewUrl?.isNotBlank() == true) {
            Text(
                stringResource(R.string.queue_preview, player.queuePosition, player.queueSize),
                color = MutedText.copy(alpha = 0.65f),
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.align(Alignment.CenterHorizontally)
            )
        }
        Column(verticalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(stringResource(R.string.slow_down), color = MutedText, style = MaterialTheme.typography.labelLarge)
                Text("${(player.speed * 100).toInt()}%", color = NeonGreen, style = MaterialTheme.typography.titleLarge)
                Text(stringResource(R.string.speed_up), color = MutedText, style = MaterialTheme.typography.labelLarge)
            }
            Slider(
                value = player.speed,
                onValueChange = player::updateSpeed,
                valueRange = 0.5f..1.5f
            )
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                Button(
                    onClick = { player.updateSpeed(1f) },
                    shape = RoundedCornerShape(18.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = NeonGreen, contentColor = Color.Black)
                ) {
                    Text(stringResource(R.string.reset_speed))
                }
            }
        }
        player.errorMessage?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        Slider(
            value = player.progressSeconds.toFloat(),
            onValueChange = { player.seek(it.toInt()) },
            valueRange = 0f..duration.toFloat()
        )
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(formatTime(player.progressSeconds), color = MutedText)
            Text(formatTime(duration), color = MutedText)
        }
        Row(
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth()
        ) {
            PlayerIconButton(if (player.shuffleEnabled) "⤨" else "⇄", active = player.shuffleEnabled) { player.toggleShuffle() }
            PlayerIconButton("⏮") { player.playPrevious() }
            Button(
                onClick = { player.toggle() },
                modifier = Modifier
                    .weight(1.8f)
                    .height(68.dp),
                shape = CircleShape,
                colors = ButtonDefaults.buttonColors(containerColor = NeonGreen, contentColor = Color.Black),
                enabled = song != null && !player.isLoading
            ) {
                Text(if (player.isPlaying) "Ⅱ" else "▶")
            }
            PlayerIconButton("⏭") { player.playNext() }
            PlayerIconButton(player.repeatMode.label(), active = player.repeatMode != RepeatMode.Off) { player.cycleRepeatMode() }
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
            Text("🔊", color = Color.White, style = MaterialTheme.typography.titleLarge)
            Slider(
                value = player.volume,
                onValueChange = player::updateVolume,
                valueRange = 0f..1f,
                modifier = Modifier.weight(1f)
            )
        }
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                PlayerOptionCard(
                    "🗣 ${stringResource(R.string.voice)}",
                    when {
                        vocalBusy -> "..."
                        vocalsRemoved -> "ON"
                        else -> "OFF"
                    },
                    modifier = Modifier.weight(1f),
                    onClick = { if (!vocalBusy) onVocals(!vocalsRemoved) }
                ) {
                    Text(vocalJobStatus.labelForVocalStatus(), color = Color.White, style = MaterialTheme.typography.labelLarge)
                    if (vocalBusy) {
                        LinearProgressIndicator(
                            modifier = Modifier.fillMaxWidth(),
                            color = NeonGreen,
                            trackColor = PanelSoft
                        )
                    }
                    Text(
                        if (vocalsRemoved) stringResource(R.string.vocal_cut_active) else stringResource(R.string.vocal_cut_hint),
                        color = MutedText,
                        style = MaterialTheme.typography.labelLarge
                    )
                }
                PlayerOptionCard(
                    "⏱ ${stringResource(R.string.metronome_overlay)}",
                    if (player.metronomeOverlayEnabled) "ON" else "OFF",
                    modifier = Modifier.weight(1f),
                    onClick = { player.toggleMetronomeOverlay() }
                ) {
                    Text(stringResource(R.string.click_over_music), color = MutedText, style = MaterialTheme.typography.labelLarge)
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                PlayerOptionCard(
                    stringResource(R.string.voice_count),
                    if (player.voiceCountEnabled) "${player.currentBeat}" else "OFF",
                    modifier = Modifier.weight(1f),
                    onClick = { player.toggleVoiceCount() }
                ) {
                    Text(if (player.voiceCountEnabled) stringResource(R.string.beat_count_value, player.currentBeat) else stringResource(R.string.tap_to_enable), color = MutedText, style = MaterialTheme.typography.labelLarge)
                }
                PlayerOptionCard(
                    stringResource(R.string.measure_count),
                    if (player.measureCountEnabled) "${player.currentMeasure}" else "OFF",
                    modifier = Modifier.weight(1f),
                    onClick = { player.toggleMeasureCount() }
                ) {
                    Text(if (player.measureCountEnabled) stringResource(R.string.measure_value, player.currentMeasure) else stringResource(R.string.tap_to_enable), color = MutedText, style = MaterialTheme.typography.labelLarge)
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                PlayerOptionCard("BPM", "${player.bpm}", modifier = Modifier.weight(1f)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        MiniButton("-") { player.updateBpm(player.bpm - 1) }
                        MiniButton("+") { player.updateBpm(player.bpm + 1) }
                    }
                }
                PlayerOptionCard("🎚 ${stringResource(R.string.eq_title)}", stringResource(R.string.equalizer_open), modifier = Modifier.weight(1f), onClick = onOpenEqualizer) {
                    Text(if (player.equalizerReady) stringResource(R.string.equalizer_applied) else stringResource(R.string.equalizer_waiting), color = MutedText, style = MaterialTheme.typography.labelLarge)
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            DarkButton(text = if (song?.favorite == true) "♥ ${stringResource(R.string.favorite_on)}" else "♡ ${stringResource(R.string.favorite_add)}", modifier = Modifier.weight(1f), onClick = onFavorite)
            DarkButton(text = if (song?.downloaded == true) "✓ ${stringResource(R.string.downloaded)}" else "↓ ${stringResource(R.string.download)}", modifier = Modifier.weight(1f), onClick = onDownloaded)
        }
        DarkButton(text = if (showQueue) stringResource(R.string.hide_queue) else "☰ ${stringResource(R.string.queue_button, player.queueSize)}", modifier = Modifier.fillMaxWidth()) {
            showQueue = !showQueue
        }
        if (showQueue) {
            SectionTitle(stringResource(R.string.queue_title))
            if (player.queueItems.isEmpty()) {
                EmptyState(stringResource(R.string.queue_empty_title), stringResource(R.string.queue_empty_body))
            } else {
                player.queueItems.forEachIndexed { index, queuedSong ->
                    val current = player.queuePosition == index + 1
                    SectionCard {
                        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            AlbumTile(size = 48, index = index + 1, coverUrl = queuedSong.coverUrl)
                            Column(modifier = Modifier.weight(1f).padding(start = 12.dp)) {
                                Text(
                                    queuedSong.title,
                                    color = if (current) NeonGreen else Color.White,
                                    style = MaterialTheme.typography.titleLarge,
                                    maxLines = 1
                                )
                                Text(queuedSong.artist, color = MutedText, maxLines = 1)
                            }
                            TextButton(onClick = { player.playQueueIndex(index) }) {
                                Text(if (current) stringResource(R.string.now_playing) else "▶")
                            }
                            TextButton(onClick = { player.removeQueueIndex(index) }) {
                                Text(stringResource(R.string.remove))
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun RepeatMode.label(): String {
    return when (this) {
        RepeatMode.Off -> "↻"
        RepeatMode.All -> "↻A"
        RepeatMode.One -> "↻1"
    }
}

@Composable
private fun RowScope.PlayerIconButton(text: String, active: Boolean = false, onClick: () -> Unit) {
    OutlinedButton(
        onClick = onClick,
        modifier = Modifier
            .weight(1f)
            .height(56.dp),
        shape = RoundedCornerShape(14.dp),
        colors = ButtonDefaults.outlinedButtonColors(
            containerColor = if (active) NeonGreen.copy(alpha = 0.18f) else Color.Transparent,
            contentColor = if (active) NeonGreen else Color.White
        )
    ) {
        Text(text)
    }
}

@Composable
private fun SpeedPill(text: String, active: Boolean, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        shape = RoundedCornerShape(10.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = if (active) NeonGreen else Panel,
            contentColor = if (active) Color.Black else Color.White
        ),
        contentPadding = ButtonDefaults.ContentPadding,
        modifier = Modifier
            .width(70.dp)
            .height(28.dp)
    ) {
        Text(text, style = MaterialTheme.typography.labelLarge, maxLines = 1)
    }
}

@Composable
private fun MiniButton(text: String, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        shape = RoundedCornerShape(10.dp),
        colors = ButtonDefaults.buttonColors(containerColor = Panel, contentColor = Color.White),
        contentPadding = ButtonDefaults.ContentPadding,
        modifier = Modifier.size(width = 42.dp, height = 30.dp)
    ) {
        Text(text, style = MaterialTheme.typography.labelLarge)
    }
}

@Composable
private fun PlayerOptionCard(
    title: String,
    value: String,
    modifier: Modifier = Modifier,
    onClick: () -> Unit = {},
    content: @Composable ColumnScope.() -> Unit
) {
    Button(
        onClick = onClick,
        shape = RoundedCornerShape(24.dp),
        colors = ButtonDefaults.buttonColors(containerColor = PanelSoft, contentColor = Color.White),
        contentPadding = ButtonDefaults.ContentPadding,
        modifier = modifier
            .height(124.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(14.dp),
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(title, color = MutedText, style = MaterialTheme.typography.titleLarge)
                if (value.isNotBlank()) {
                    Text(value, color = if (title == "BPM") NeonGreen else Color.White, style = MaterialTheme.typography.titleLarge)
                }
            }
            content()
        }
    }
}

@Composable
private fun PlayerOptionCardOld(title: String, value: String, modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier = modifier
            .height(124.dp)
            .background(PanelSoft, RoundedCornerShape(24.dp))
            .padding(14.dp),
        verticalArrangement = Arrangement.SpaceBetween
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, color = MutedText, style = MaterialTheme.typography.titleLarge)
            if (value.isNotBlank()) {
                Text(value, color = if (title == "BPM") NeonGreen else Color.White, style = MaterialTheme.typography.titleLarge)
            }
        }
        content()
    }
}

@Composable
fun EqualizerScreen(settings: UserSettings, song: Song?, isPlaying: Boolean, onSave: (UserSettings) -> Unit) {
    var eq60 by rememberSaveable(settings.userId) { mutableIntStateOf(settings.eq60) }
    var eq230 by rememberSaveable(settings.userId) { mutableIntStateOf(settings.eq230) }
    var eq910 by rememberSaveable(settings.userId) { mutableIntStateOf(settings.eq910) }
    var eq3600 by rememberSaveable(settings.userId) { mutableIntStateOf(settings.eq3600) }
    var eq14000 by rememberSaveable(settings.userId) { mutableIntStateOf(settings.eq14000) }

    fun preset(values: List<Int>) {
        eq60 = values[0]
        eq230 = values[1]
        eq910 = values[2]
        eq3600 = values[3]
        eq14000 = values[4]
    }

    ScreenLayout(title = stringResource(R.string.eq_title), subtitle = song?.title ?: stringResource(R.string.eq_subtitle_default)) {
        Visualizer(active = isPlaying, values = listOf(eq60, eq230, eq910, eq3600, eq14000))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SmallChip("Flat", false) { preset(listOf(0, 0, 0, 0, 0)) }
            SmallChip("Rock", false) { preset(listOf(4, 2, -1, 3, 5)) }
            SmallChip("Pop", false) { preset(listOf(-1, 2, 4, 3, 0)) }
            SmallChip("Bass", false) { preset(listOf(6, 4, 0, -2, -3)) }
        }
        EqSlider("60Hz", eq60) { eq60 = it }
        EqSlider("230Hz", eq230) { eq230 = it }
        EqSlider("910Hz", eq910) { eq910 = it }
        EqSlider("3.6kHz", eq3600) { eq3600 = it }
        EqSlider("14kHz", eq14000) { eq14000 = it }
        PrimaryAction(stringResource(R.string.save_eq)) {
            onSave(settings.copy(eq60 = eq60, eq230 = eq230, eq910 = eq910, eq3600 = eq3600, eq14000 = eq14000))
        }
    }
}

@Composable
fun EqSlider(label: String, value: Int, onValue: (Int) -> Unit) {
    SectionCard {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(label, style = MaterialTheme.typography.titleLarge, color = Color.White)
            Text("${if (value > 0) "+" else ""}${value}dB", color = NeonGreen)
        }
        Slider(value = value.toFloat(), onValueChange = { onValue(it.toInt()) }, valueRange = -12f..12f)
    }
}

@Composable
fun MetronomeScreen(initialBpm: Int, onSave: (Int) -> Unit) {
    var bpm by rememberSaveable { mutableIntStateOf(initialBpm) }
    var running by rememberSaveable { mutableStateOf(false) }
    var beat by rememberSaveable { mutableIntStateOf(0) }

    LaunchedEffect(running, bpm) {
        while (running) {
            kotlinx.coroutines.delay((60000 / bpm.coerceIn(40, 240)).toLong())
            beat = (beat + 1) % 4
        }
    }

    ScreenLayout(title = stringResource(R.string.metronome_title), subtitle = "") {
        Box(
            modifier = Modifier
                .size(240.dp)
                .align(Alignment.CenterHorizontally)
                .clip(CircleShape)
                .border(9.dp, if (running && beat == 0) NeonGreen else PanelSoft, CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("$bpm", style = MaterialTheme.typography.displayLarge, color = Color.White)
                Text("BPM", color = MutedText, style = MaterialTheme.typography.titleLarge)
            }
        }
        Slider(value = bpm.toFloat(), onValueChange = { bpm = it.toInt() }, valueRange = 40f..240f)
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            DarkButton("-", modifier = Modifier.weight(1f)) { bpm = (bpm - 1).coerceAtLeast(40) }
            Button(
                onClick = { running = !running },
                modifier = Modifier.weight(2f),
                shape = CircleShape,
                colors = ButtonDefaults.buttonColors(containerColor = NeonGreen, contentColor = Color.Black)
            ) { Text(if (running) stringResource(R.string.stop) else "▶") }
            DarkButton("+", modifier = Modifier.weight(1f)) { bpm = (bpm + 1).coerceAtMost(240) }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
            SectionCard {
                Text(stringResource(R.string.time_signature), color = MutedText)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf("2/4", "3/4", "4/4", "5/4", "6/8", "7/8").forEach { SmallChip(it, it == "4/4") {} }
                }
            }
        }
        PrimaryAction(stringResource(R.string.save_bpm)) { onSave(bpm) }
    }
}

@Composable
fun LibraryScreen(
    repository: MusicRepository,
    user: User,
    refresh: Int,
    onPlay: (Song, List<Song>) -> Unit,
    onDownload: (Song) -> Unit,
    onChanged: (String) -> Unit,
) {
    val scope = rememberCoroutineScope()
    var section by rememberSaveable { mutableStateOf("all") }
    var librarySearch by rememberSaveable { mutableStateOf("") }
    var sortMode by rememberSaveable { mutableStateOf("recent") }
    var genreFilter by rememberSaveable { mutableStateOf("all") }
    var selectMode by rememberSaveable { mutableStateOf(false) }
    var selectedIds by rememberSaveable { mutableStateOf(setOf<Long>()) }
    var playlistTarget by remember { mutableStateOf<Song?>(null) }
    var multiPlaylistTarget by remember { mutableStateOf<List<Song>>(emptyList()) }
    var deleteTarget by remember { mutableStateOf<Song?>(null) }
    var bulkDeleteOpen by remember { mutableStateOf(false) }
    val playlists = remember(refresh) { repository.playlists() }
    val addedPlaylistMessage = stringResource(R.string.snackbar_added_playlist)
    val addedPlaylistCountMessage = stringResource(R.string.snackbar_added_playlist_count)
    val trackDeletedMessage = stringResource(R.string.snackbar_track_deleted)
    val tracksDeletedMessage = stringResource(R.string.snackbar_tracks_deleted)
    val selectTracksMessage = stringResource(R.string.snackbar_select_tracks)
    val favoritesUpdatedMessage = stringResource(R.string.snackbar_favorites_updated)
    val songs = remember(refresh, section, librarySearch, sortMode, genreFilter) {
        val base = when (section) {
            "fav" -> repository.favoriteSongs()
            "down" -> repository.downloadedSongs()
            "recent" -> repository.recentSongs()
            else -> repository.songs()
        }
        val genreFiltered = if (genreFilter == "all") {
            base
        } else {
            base.filter { it.genre == genreFilter }
        }
        val filtered = if (librarySearch.isBlank()) {
            genreFiltered
        } else {
            genreFiltered.filter {
                it.title.contains(librarySearch, ignoreCase = true) ||
                    it.artist.contains(librarySearch, ignoreCase = true) ||
                    it.album.contains(librarySearch, ignoreCase = true)
            }
        }
        when (sortMode) {
            "title" -> filtered.sortedBy { it.title.lowercase() }
            "artist" -> filtered.sortedBy { it.artist.lowercase() }
            "duration" -> filtered.sortedByDescending { it.durationSeconds }
            else -> filtered
        }
    }
    val selectedSongs = songs.filter { it.id in selectedIds }

    PlaylistPickerDialog(
        song = playlistTarget,
        playlists = playlists,
        onDismiss = { playlistTarget = null },
        onAdd = { playlist, song ->
            scope.launch {
                withContext(Dispatchers.IO) { runCatching { repository.addSongToPlaylist(playlist.id, song) } }
                playlistTarget = null
                onChanged(addedPlaylistMessage.format(playlist.name))
            }
        }
    )

    MultiPlaylistPickerDialog(
        songs = multiPlaylistTarget,
        playlists = playlists,
        onDismiss = { multiPlaylistTarget = emptyList() },
        onAdd = { playlist, tracks ->
            scope.launch {
                withContext(Dispatchers.IO) {
                    tracks.forEach { repository.addSongToPlaylist(playlist.id, it) }
                }
                selectedIds = emptySet()
                selectMode = false
                multiPlaylistTarget = emptyList()
                onChanged(addedPlaylistCountMessage.format(playlist.name, tracks.size))
            }
        }
    )

    ConfirmDeleteSongDialog(
        song = deleteTarget,
        onDismiss = { deleteTarget = null },
        onConfirm = { song ->
            scope.launch {
                withContext(Dispatchers.IO) { repository.deleteSong(song.id) }
                deleteTarget = null
                onChanged(trackDeletedMessage)
            }
        }
    )

    ConfirmBulkDeleteDialog(
        open = bulkDeleteOpen,
        count = selectedIds.size,
        onDismiss = { bulkDeleteOpen = false },
        onConfirm = {
            scope.launch {
                withContext(Dispatchers.IO) {
                    selectedIds.forEach { repository.deleteSong(it) }
                }
                selectedIds = emptySet()
                selectMode = false
                bulkDeleteOpen = false
                onChanged(tracksDeletedMessage)
            }
        }
    )

    ScreenLayout(title = stringResource(R.string.library_title), subtitle = "") {
        AuthTextField(librarySearch, { librarySearch = it }, stringResource(R.string.library_search))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SmallChip("♡ ${stringResource(R.string.filter_favorites)}", section == "fav") { section = "fav" }
            SmallChip("↓ ${stringResource(R.string.filter_downloaded)}", section == "down") { section = "down" }
            SmallChip("↺ ${stringResource(R.string.filter_recent)}", section == "recent") { section = "recent" }
            SmallChip("♫ ${stringResource(R.string.filter_all_songs)}", section == "all") { section = "all" }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.horizontalScroll(rememberScrollState())) {
            SmallChip(stringResource(R.string.sort_date), sortMode == "recent") { sortMode = "recent" }
            SmallChip(stringResource(R.string.sort_title), sortMode == "title") { sortMode = "title" }
            SmallChip(stringResource(R.string.sort_artist), sortMode == "artist") { sortMode = "artist" }
            SmallChip(stringResource(R.string.sort_duration), sortMode == "duration") { sortMode = "duration" }
        }
        GenreRow(active = genreFilter, onChange = { genreFilter = it })
        SectionCard {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                DarkButton(
                    text = if (selectMode) stringResource(R.string.cancel) else stringResource(R.string.select),
                    modifier = Modifier.weight(1f),
                    onClick = {
                        selectMode = !selectMode
                        selectedIds = emptySet()
                    }
                )
                if (selectMode) {
                    DarkButton(
                        text = stringResource(R.string.add_selected_to_playlist),
                        modifier = Modifier.weight(1f),
                        onClick = {
                            if (selectedSongs.isNotEmpty()) multiPlaylistTarget = selectedSongs else onChanged(selectTracksMessage)
                        }
                    )
                    OutlinedButton(
                        onClick = { if (selectedIds.isNotEmpty()) bulkDeleteOpen = true else onChanged(selectTracksMessage) },
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Text(stringResource(R.string.delete_selected), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
        SectionTitle(stringResource(R.string.library_tracks))
        songs.forEach { song ->
            SongRow(
                song = song,
                onPlay = { onPlay(song, songs) },
                onFavorite = {
                    scope.launch {
                        withContext(Dispatchers.IO) {
                            repository.updateSongFlag(song, "favorite", !song.favorite)
                        }
                        onChanged(favoritesUpdatedMessage)
                    }
                },
                onDownload = {
                    onDownload(song)
                },
                selectable = selectMode,
                selected = song.id in selectedIds,
                onSelectedChange = { selected ->
                    selectedIds = if (selected) selectedIds + song.id else selectedIds - song.id
                },
                onAddToPlaylist = { playlistTarget = song },
                onDelete = { deleteTarget = song }
            )
        }
        if (songs.isEmpty()) EmptyState(stringResource(R.string.library_empty_title), stringResource(R.string.library_empty_body))
    }
}

@Composable
fun PlaylistsScreen(
    repository: MusicRepository,
    user: User,
    refresh: Int,
    onPlay: (Song, List<Song>) -> Unit,
    onChanged: (String) -> Unit,
) {
    val scope = rememberCoroutineScope()
    var newName by rememberSaveable { mutableStateOf("") }
    var newCoverUrl by rememberSaveable { mutableStateOf("") }
    var activePlaylistId by rememberSaveable { mutableStateOf<Long?>(null) }
    var renameText by rememberSaveable { mutableStateOf("") }
    var coverText by rememberSaveable { mutableStateOf("") }
    var playlistMenuId by remember { mutableStateOf<Long?>(null) }
    var deletePlaylistTarget by remember { mutableStateOf<Playlist?>(null) }
    var dragAccumulator by remember { mutableStateOf(0f) }
    val playlists = remember(refresh) { repository.playlists() }
    val librarySongs = remember(refresh) { repository.songs() }
    val activePlaylist = playlists.firstOrNull { it.id == activePlaylistId }
    val playlistSongs = remember(refresh, activePlaylistId) {
        activePlaylistId?.let { repository.songsInPlaylist(it) }.orEmpty()
    }
    val playlistSongIds = playlistSongs.map { it.id }.toSet()
    val playlistDeletedMessage = stringResource(R.string.snackbar_playlist_deleted)
    val enterPlaylistNameMessage = stringResource(R.string.snackbar_enter_playlist_name)
    val playlistCreatedMessage = stringResource(R.string.snackbar_playlist_created)
    val playlistEmptyMessage = stringResource(R.string.snackbar_playlist_empty)
    val playlistRenamedMessage = stringResource(R.string.snackbar_playlist_renamed)
    val orderUpdatedMessage = stringResource(R.string.snackbar_order_updated)
    val removedFromPlaylistMessage = stringResource(R.string.snackbar_removed_from_playlist)
    val alreadyPlaylistMessage = stringResource(R.string.snackbar_already_playlist)
    val addedToPlaylistMessage = stringResource(R.string.snackbar_added_to_playlist)

    ConfirmDeletePlaylistDialog(
        playlist = deletePlaylistTarget,
        onDismiss = { deletePlaylistTarget = null },
        onConfirm = { playlist ->
            scope.launch {
                withContext(Dispatchers.IO) { repository.deletePlaylist(playlist.id) }
                if (activePlaylistId == playlist.id) activePlaylistId = null
                deletePlaylistTarget = null
                onChanged(playlistDeletedMessage)
            }
        }
    )

    ScreenLayout(title = stringResource(R.string.playlists_title), subtitle = stringResource(R.string.playlists_subtitle)) {
        if (activePlaylist == null) {
            SectionCard {
                Text(stringResource(R.string.playlist_new), color = Color.White, style = MaterialTheme.typography.titleLarge)
                AuthTextField(newName, { newName = it }, stringResource(R.string.playlist_name))
                AuthTextField(newCoverUrl, { newCoverUrl = it }, stringResource(R.string.playlist_cover))
                PrimaryAction(stringResource(R.string.playlist_create)) {
                    if (newName.isBlank()) {
                        onChanged(enterPlaylistNameMessage)
                    } else {
                        scope.launch {
                            withContext(Dispatchers.IO) { repository.createPlaylist(newName, newCoverUrl) }
                            newName = ""
                            newCoverUrl = ""
                            onChanged(playlistCreatedMessage)
                        }
                    }
                }
            }
            playlists.forEach { playlist ->
                SectionCard {
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        AlbumTile(size = 58, index = null, coverUrl = playlist.coverUrl)
                        Column(modifier = Modifier.weight(1f).padding(start = 12.dp)) {
                            Text(playlist.name, color = Color.White, style = MaterialTheme.typography.titleLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(stringResource(R.string.track_count, playlist.songCount), color = MutedText)
                        }
                        Button(
                            onClick = {
                                activePlaylistId = playlist.id
                                renameText = playlist.name
                                coverText = playlist.coverUrl
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = NeonGreen, contentColor = Color.Black),
                            shape = RoundedCornerShape(14.dp)
                        ) {
                            Text(stringResource(R.string.playlist_open))
                        }
                        Box {
                            TextButton(onClick = { playlistMenuId = playlist.id }) {
                                Text("⋯", color = Color.White, style = MaterialTheme.typography.headlineSmall)
                            }
                            DropdownMenu(
                                expanded = playlistMenuId == playlist.id,
                                onDismissRequest = { playlistMenuId = null },
                                containerColor = PanelSoft
                            ) {
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.playlist_open), color = Color.White) },
                                    onClick = {
                                        playlistMenuId = null
                                        activePlaylistId = playlist.id
                                        renameText = playlist.name
                                        coverText = playlist.coverUrl
                                    }
                                )
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.playlist_delete), color = Color(0xFFFF5A66)) },
                                    onClick = {
                                        playlistMenuId = null
                                        deletePlaylistTarget = playlist
                                    }
                                )
                            }
                        }
                    }
                }
            }
            if (playlists.isEmpty()) EmptyState(stringResource(R.string.playlist_empty_title), stringResource(R.string.playlist_empty_body))
        } else {
            SectionCard {
                Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    AlbumTile(size = 86, index = null, coverUrl = activePlaylist.coverUrl.ifBlank { playlistSongs.firstOrNull { it.coverUrl.isNotBlank() }?.coverUrl.orEmpty() })
                    Column(modifier = Modifier.weight(1f).padding(start = 16.dp)) {
                        Text(activePlaylist.name, color = Color.White, style = MaterialTheme.typography.headlineMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(stringResource(R.string.playlist_summary, playlistSongs.size, formatTime(playlistSongs.sumOf { it.durationSeconds })), color = MutedText)
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    Button(
                        onClick = {
                            playlistSongs.firstOrNull()?.let { onPlay(it, playlistSongs) } ?: onChanged(playlistEmptyMessage)
                        },
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.buttonColors(containerColor = NeonGreen, contentColor = Color.Black),
                        shape = RoundedCornerShape(14.dp)
                    ) {
                        Text("▶ ${stringResource(R.string.playlist_play_all)}")
                    }
                    OutlinedButton(
                        onClick = { activePlaylistId = null },
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(14.dp)
                    ) {
                        Text(stringResource(R.string.playlist_back))
                    }
                }
            }
            SectionCard {
                Text(stringResource(R.string.playlist_edit), color = Color.White, style = MaterialTheme.typography.titleLarge)
                AuthTextField(renameText, { renameText = it }, stringResource(R.string.playlist_name))
                AuthTextField(coverText, { coverText = it }, stringResource(R.string.playlist_cover))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    Button(
                        onClick = {
                            if (renameText.isNotBlank()) {
                                scope.launch {
                                    withContext(Dispatchers.IO) {
                                        repository.updatePlaylistDetails(activePlaylist.id, renameText, coverText)
                                    }
                                    onChanged(playlistRenamedMessage)
                                }
                            }
                        },
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.buttonColors(containerColor = NeonGreen, contentColor = Color.Black),
                        shape = RoundedCornerShape(14.dp)
                    ) {
                        Text(stringResource(R.string.playlist_save))
                    }
                    OutlinedButton(
                        onClick = { deletePlaylistTarget = activePlaylist },
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(14.dp)
                    ) {
                        Text(stringResource(R.string.playlist_delete))
                    }
                }
            }
            SectionTitle(stringResource(R.string.playlist_tracks))
            playlistSongs.forEachIndexed { index, song ->
                SectionCard {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .pointerInput(activePlaylist.id, song.id, index) {
                                detectDragGesturesAfterLongPress(
                                    onDragStart = { dragAccumulator = 0f },
                                    onDragEnd = { dragAccumulator = 0f },
                                    onDragCancel = { dragAccumulator = 0f },
                                    onDrag = { change, dragAmount ->
                                        change.consume()
                                        dragAccumulator += dragAmount.y
                                        if (dragAccumulator > 54f) {
                                            scope.launch(Dispatchers.IO) {
                                                repository.moveSongInPlaylist(activePlaylist.id, song.id, 1)
                                            }
                                            dragAccumulator = 0f
                                            onChanged(orderUpdatedMessage)
                                        } else if (dragAccumulator < -54f) {
                                            scope.launch(Dispatchers.IO) {
                                                repository.moveSongInPlaylist(activePlaylist.id, song.id, -1)
                                            }
                                            dragAccumulator = 0f
                                            onChanged(orderUpdatedMessage)
                                        }
                                    }
                                )
                            },
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        AlbumTile(size = 52, index = index + 1, coverUrl = song.coverUrl)
                        Column(modifier = Modifier.weight(1f).padding(start = 12.dp)) {
                            Text(song.title, color = Color.White, style = MaterialTheme.typography.titleLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(song.artist, color = MutedText, maxLines = 1)
                            Text(stringResource(R.string.drag_hint), color = MutedText.copy(alpha = 0.7f), style = MaterialTheme.typography.labelLarge)
                        }
                        Button(
                            onClick = { onPlay(song, playlistSongs) },
                            colors = ButtonDefaults.buttonColors(containerColor = NeonGreen, contentColor = Color.Black),
                            shape = RoundedCornerShape(12.dp)
                        ) { Text("▶") }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                        OutlinedButton(
                            onClick = {
                                scope.launch {
                                    withContext(Dispatchers.IO) {
                                        repository.removeSongFromPlaylist(activePlaylist.id, song.id)
                                    }
                                    onChanged(removedFromPlaylistMessage)
                                }
                            },
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Text(stringResource(R.string.remove))
                        }
                    }
                }
            }
            if (playlistSongs.isEmpty()) Text(stringResource(R.string.playlist_empty), color = MutedText)
            SectionTitle(stringResource(R.string.playlist_add_from_library))
            librarySongs.forEach { song ->
                val added = song.id in playlistSongIds
                SectionCard {
                    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        AlbumTile(size = 52, index = null, coverUrl = song.coverUrl)
                        Column(modifier = Modifier.weight(1f).padding(start = 12.dp)) {
                            Text(song.title, color = Color.White, style = MaterialTheme.typography.titleLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(song.artist, color = MutedText, maxLines = 1)
                        }
                        Button(
                            onClick = {
                                if (added) {
                                    onChanged(alreadyPlaylistMessage)
                                } else {
                                    scope.launch {
                                        withContext(Dispatchers.IO) {
                                            repository.addSongToPlaylist(activePlaylist.id, song)
                                        }
                                        onChanged(addedToPlaylistMessage)
                                    }
                                }
                            },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = if (added) PanelSoft else NeonGreen,
                                contentColor = if (added) NeonGreen else Color.Black
                            ),
                            shape = RoundedCornerShape(14.dp)
                        ) {
                            Text(if (added) "✓" else "+")
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PlaylistPickerDialog(
    song: Song?,
    playlists: List<Playlist>,
    onDismiss: () -> Unit,
    onAdd: (Playlist, Song) -> Unit,
) {
    if (song == null) return
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Panel,
        title = { Text(stringResource(R.string.add_to_playlist), color = Color.White) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(song.title, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (playlists.isEmpty()) {
                    Text(stringResource(R.string.playlist_empty_body), color = MutedText)
                } else {
                    playlists.forEach { playlist ->
                        Button(
                            onClick = { onAdd(playlist, song) },
                            colors = ButtonDefaults.buttonColors(containerColor = PanelSoft, contentColor = Color.White),
                            shape = RoundedCornerShape(14.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text(playlist.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text("${playlist.songCount}")
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.cancel), color = NeonGreen)
            }
        }
    )
}

@Composable
private fun ConfirmDeleteSongDialog(
    song: Song?,
    onDismiss: () -> Unit,
    onConfirm: (Song) -> Unit,
) {
    if (song == null) return
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Panel,
        title = { Text(stringResource(R.string.confirm_delete_tracks_title), color = Color.White) },
        text = {
            Text(
                stringResource(R.string.delete_song_body, song.title),
                color = MutedText
            )
        },
        confirmButton = {
            Button(
                onClick = { onConfirm(song) },
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFF3B4A), contentColor = Color.White),
                shape = RoundedCornerShape(14.dp)
            ) {
                Text(stringResource(R.string.playlist_delete))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.cancel), color = NeonGreen)
            }
        }
    )
}

@Composable
private fun ConfirmBulkDeleteDialog(
    open: Boolean,
    count: Int,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    if (!open) return
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Panel,
        title = { Text(stringResource(R.string.confirm_delete_tracks_title), color = Color.White) },
        text = { Text(stringResource(R.string.selected_tracks_count, count), color = MutedText) },
        confirmButton = {
            Button(
                onClick = onConfirm,
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFF3B4A), contentColor = Color.White),
                shape = RoundedCornerShape(14.dp)
            ) {
                Text(stringResource(R.string.playlist_delete))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.cancel), color = NeonGreen)
            }
        }
    )
}

@Composable
private fun ConfirmDeletePlaylistDialog(
    playlist: Playlist?,
    onDismiss: () -> Unit,
    onConfirm: (Playlist) -> Unit,
) {
    if (playlist == null) return
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Panel,
        title = { Text(stringResource(R.string.confirm_delete_playlist_title), color = Color.White) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(playlist.name, color = Color.White, style = MaterialTheme.typography.titleLarge)
                Text(stringResource(R.string.confirm_delete_playlist_body), color = MutedText)
            }
        },
        confirmButton = {
            Button(
                onClick = { onConfirm(playlist) },
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFF3B4A), contentColor = Color.White),
                shape = RoundedCornerShape(14.dp)
            ) {
                Text(stringResource(R.string.playlist_delete))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.cancel), color = NeonGreen)
            }
        }
    )
}

@Composable
private fun MultiPlaylistPickerDialog(
    songs: List<Song>,
    playlists: List<Playlist>,
    onDismiss: () -> Unit,
    onAdd: (Playlist, List<Song>) -> Unit,
) {
    if (songs.isEmpty()) return
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Panel,
        title = { Text(stringResource(R.string.add_selected_to_playlist), color = Color.White) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(stringResource(R.string.selected_tracks_count, songs.size), color = MutedText)
                if (playlists.isEmpty()) {
                    Text(stringResource(R.string.playlist_empty_body), color = MutedText)
                } else {
                    playlists.forEach { playlist ->
                        Button(
                            onClick = { onAdd(playlist, songs) },
                            colors = ButtonDefaults.buttonColors(containerColor = PanelSoft, contentColor = Color.White),
                            shape = RoundedCornerShape(14.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text(playlist.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text("${playlist.songCount}")
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.cancel), color = NeonGreen)
            }
        }
    )
}

@Composable
fun SettingsScreen(settings: UserSettings, volume: Float, speed: Float, onVolume: (Float) -> Unit, onSpeed: (Float) -> Unit, onSave: (UserSettings) -> Unit) {
    var language by rememberSaveable(settings.userId) { mutableStateOf(settings.language) }
    var theme by rememberSaveable(settings.userId) { mutableStateOf(settings.theme) }

    ScreenLayout(title = stringResource(R.string.settings_title), subtitle = stringResource(R.string.settings_subtitle)) {
        SectionCard {
            Text(stringResource(R.string.language), style = MaterialTheme.typography.titleLarge)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SmallChip("RU", language == "ru") { language = "ru" }
                SmallChip("EN", language == "en") { language = "en" }
            }
            Text(stringResource(R.string.theme), style = MaterialTheme.typography.titleLarge)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SmallChip(stringResource(R.string.theme_light), theme == "light") { theme = "light" }
                SmallChip(stringResource(R.string.theme_dark), theme == "dark") { theme = "dark" }
                SmallChip(stringResource(R.string.theme_system), theme == "system") { theme = "system" }
            }
            Text(stringResource(R.string.default_volume, volume.percent()), style = MaterialTheme.typography.titleLarge)
            Slider(value = volume, onValueChange = onVolume, valueRange = 0f..1f)
            Text(stringResource(R.string.default_speed, speed.toString()), style = MaterialTheme.typography.titleLarge)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 2f).forEach { item ->
                    SmallChip("${item}x", speed == item) { onSpeed(item) }
                }
            }
        }
        PrimaryAction(stringResource(R.string.save_settings)) { onSave(settings.copy(language = language, theme = theme)) }
    }
}

@Composable
fun ProfileScreen(
    user: User,
    songs: List<Song>,
    playlists: List<Playlist>,
    settings: UserSettings,
    onSaveSettings: (UserSettings) -> Unit,
    onLogout: () -> Unit,
) {
    var language by rememberSaveable(settings.userId) { mutableStateOf(settings.language) }
    var theme by rememberSaveable(settings.userId) { mutableStateOf(settings.theme) }
    var defaultVolume by rememberSaveable(settings.userId) { mutableStateOf(settings.defaultVolume) }
    var defaultSpeed by rememberSaveable(settings.userId) { mutableStateOf(settings.defaultSpeed) }

    ScreenLayout(title = "", subtitle = "") {
        SectionCard {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(76.dp)
                        .background(PanelSoft, CircleShape),
                    contentAlignment = Alignment.Center
                ) { Text("♙", color = MutedText, style = MaterialTheme.typography.displayLarge) }
                Column(modifier = Modifier.padding(start = 16.dp)) {
                    Text(user.name.ifBlank { "Alex" }, style = MaterialTheme.typography.headlineMedium, color = Color.White)
                    Text(user.email, color = MutedText)
                    DarkButton(stringResource(R.string.edit_profile)) {}
                }
            }
        }
        SectionCard {
            Text(stringResource(R.string.quests_completed), color = MutedText)
            Text("8", color = Color.White, style = MaterialTheme.typography.displayLarge)
            Text(stringResource(R.string.quest_rate), color = MutedText)
            Text("= 80₽", color = NeonGreen, style = MaterialTheme.typography.headlineMedium)
            DarkButton("🎁 ${stringResource(R.string.complete_quests)}") {}
        }
        SectionCard {
            Text("♕ ${stringResource(R.string.subscription)}", color = Color.White, style = MaterialTheme.typography.headlineMedium)
            Text(stringResource(R.string.inactive), color = MutedText)
            Text(stringResource(R.string.premium_features), color = MutedText)
            Text("↓ ${stringResource(R.string.offline_downloads)}", color = MutedText)
            Text("♫ ${stringResource(R.string.beat_overlay)}", color = MutedText)
            Text("♕ ${stringResource(R.string.no_ads)}", color = MutedText)
            PrimaryAction(stringResource(R.string.renew)) {}
        }
        SectionCard {
            Text(stringResource(R.string.social_media), color = Color.White, style = MaterialTheme.typography.headlineMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                SmallChip("◎", false) {}
                SmallChip("♬", false) {}
                SmallChip("♪", false) {}
            }
        }
        SectionCard {
            Text("⚙ ${stringResource(R.string.settings_title)}", color = Color.White, style = MaterialTheme.typography.headlineMedium)
            Text(stringResource(R.string.language), color = MutedText)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SmallChip("RU", language == "ru") { language = "ru" }
                SmallChip("EN", language == "en") { language = "en" }
            }
            Text(stringResource(R.string.theme), color = MutedText)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.horizontalScroll(rememberScrollState())) {
                SmallChip(stringResource(R.string.theme_light), theme == "light") { theme = "light" }
                SmallChip(stringResource(R.string.theme_dark), theme == "dark") { theme = "dark" }
                SmallChip(stringResource(R.string.theme_system), theme == "system") { theme = "system" }
            }
            Text(stringResource(R.string.default_volume, defaultVolume.percent()), color = Color.White, style = MaterialTheme.typography.titleLarge)
            Slider(value = defaultVolume, onValueChange = { defaultVolume = it }, valueRange = 0f..1f)
            Text(stringResource(R.string.default_speed, defaultSpeed.toString()), color = Color.White, style = MaterialTheme.typography.titleLarge)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.horizontalScroll(rememberScrollState())) {
                listOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 2f).forEach { speed ->
                    SmallChip("${speed}x", defaultSpeed == speed) { defaultSpeed = speed }
                }
            }
            PrimaryAction(stringResource(R.string.save_settings)) {
                onSaveSettings(
                    settings.copy(
                        language = language,
                        theme = theme,
                        defaultVolume = defaultVolume,
                        defaultSpeed = defaultSpeed
                    )
                )
            }
        }
        OutlinedButton(onClick = onLogout, shape = RoundedCornerShape(8.dp), modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.logout))
        }
    }
}
