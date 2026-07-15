package com.example.music_app

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.ToneGenerator
import android.media.audiofx.Equalizer
import android.net.Uri
import java.io.File
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlin.random.Random

enum class RepeatMode(val storageValue: String) {
    Off("off"),
    All("all"),
    One("one");

    companion object {
        fun from(value: String): RepeatMode = entries.firstOrNull { it.storageValue == value } ?: Off
    }
}

class AudioPlayerController(private val context: Context) {
    private var mediaPlayer: MediaPlayer? = null
    private var toneGenerator: ToneGenerator? = null
    private var lastMetronomeBeatIndex = -1
    private var queue: List<Song> = emptyList()
    private var queueIndex = -1
    private var equalizer: Equalizer? = null
    private var eqValues = listOf(0, 0, 0, 0, 0)

    var currentSong by mutableStateOf<Song?>(null)
        private set
    var isPlaying by mutableStateOf(false)
        private set
    var isLoading by mutableStateOf(false)
        private set
    var progressSeconds by mutableIntStateOf(0)
        private set
    var durationSeconds by mutableIntStateOf(30)
        private set
    var volume by mutableFloatStateOf(0.75f)
        private set
    var speed by mutableFloatStateOf(1f)
        private set
    var errorMessage by mutableStateOf<String?>(null)
        private set
    var shuffleEnabled by mutableStateOf(false)
        private set
    var repeatMode by mutableStateOf(RepeatMode.Off)
        private set
    var voiceCountEnabled by mutableStateOf(false)
        private set
    var measureCountEnabled by mutableStateOf(false)
        private set
    var bpm by mutableIntStateOf(120)
        private set
    var currentBeat by mutableIntStateOf(1)
        private set
    var currentMeasure by mutableIntStateOf(1)
        private set
    var metronomeOverlayEnabled by mutableStateOf(false)
        private set
    var vocalReductionEnabled by mutableStateOf(false)
        private set
    var equalizerReady by mutableStateOf(false)
        private set
    val queueSize: Int
        get() = queue.size
    val queuePosition: Int
        get() = if (queueIndex >= 0) queueIndex + 1 else 0
    val queueItems: List<Song>
        get() = queue

    fun applySettings(settings: UserSettings) {
        updateVolume(settings.defaultVolume)
        updateSpeed(settings.defaultSpeed)
        bpm = settings.metronomeBpm.coerceIn(40, 240)
        shuffleEnabled = settings.shuffleEnabled
        repeatMode = RepeatMode.from(settings.repeatMode)
        voiceCountEnabled = settings.voiceCountEnabled
        measureCountEnabled = settings.measureCountEnabled
        metronomeOverlayEnabled = settings.metronomeOverlayEnabled
        vocalReductionEnabled = settings.vocalsRemoved
        eqValues = settings.eqValues()
        applyEqualizerBands()
        updateBeatCounters()
    }

    fun settingsSnapshot(userId: Long, base: UserSettings, vocalsRemoved: Boolean): UserSettings {
        return base.copy(
            userId = userId,
            defaultVolume = volume,
            defaultSpeed = speed,
            metronomeBpm = bpm,
            shuffleEnabled = shuffleEnabled,
            repeatMode = repeatMode.storageValue,
            voiceCountEnabled = voiceCountEnabled,
            measureCountEnabled = measureCountEnabled,
            metronomeOverlayEnabled = metronomeOverlayEnabled,
            vocalsRemoved = vocalsRemoved
        )
    }

    fun play(song: Song, queueSongs: List<Song> = emptyList()) {
        if (queueSongs.isNotEmpty()) {
            queue = queueSongs
            queueIndex = queue.indexOfFirst { it.id == song.id || sameIdentity(it, song) }.takeIf { it >= 0 } ?: 0
        } else if (queue.isEmpty()) {
            queue = listOf(song)
            queueIndex = 0
        } else {
            queueIndex = queue.indexOfFirst { it.id == song.id || sameIdentity(it, song) }.takeIf { it >= 0 } ?: queueIndex
        }
        startSong(song)
    }

    private fun startSong(song: Song) {
        currentSong = song
        progressSeconds = 0
        durationSeconds = song.durationSeconds.coerceAtLeast(1)
        errorMessage = null
        lastMetronomeBeatIndex = -1

        val source = when {
            song.localPath.isNotBlank() && File(song.localPath).exists() -> song.localPath
            song.previewUrl.isNotBlank() -> song.previewUrl
            else -> ""
        }

        if (source.isBlank()) {
            isPlaying = false
            errorMessage = context.getString(R.string.player_error_no_preview)
            return
        }

        releasePlayer()
        isLoading = true
        mediaPlayer = MediaPlayer().apply {
            runCatching {
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .build()
                )
                if (source.startsWith("http", ignoreCase = true)) {
                    setDataSource(context, Uri.parse(source))
                } else {
                    setDataSource(source)
                }
                setVolume(volume, volume)
                setOnPreparedListener { player ->
                    isLoading = false
                    durationSeconds = (player.duration / 1000).coerceAtLeast(1)
                    attachEqualizer(player.audioSessionId)
                    if (speed != 1f) setPlaybackSpeed(speed)
                    player.start()
                    this@AudioPlayerController.isPlaying = true
                }
                setOnCompletionListener {
                    if (repeatMode == RepeatMode.One) {
                        seek(0)
                        it.start()
                        if (speed != 1f) setPlaybackSpeed(speed)
                        this@AudioPlayerController.isPlaying = true
                    } else if (playNext(auto = true)) {
                        Unit
                    } else {
                        this@AudioPlayerController.isPlaying = false
                        progressSeconds = 0
                        updateBeatCounters()
                    }
                }
                setOnErrorListener { _, what, extra ->
                    isLoading = false
                    this@AudioPlayerController.isPlaying = false
                    errorMessage = context.getString(R.string.player_error_stream_failed, what, extra)
                    true
                }
                prepareAsync()
            }.onFailure {
                isLoading = false
                this@AudioPlayerController.isPlaying = false
                errorMessage = context.getString(
                    R.string.player_error_open_stream,
                    it.message ?: context.getString(R.string.player_error_source)
                )
                releasePlayer()
            }
        }
    }

    fun toggle() {
        val player = mediaPlayer
        if (player == null) {
            currentSong?.let { play(it) }
            return
        }
        if (player.isPlaying) {
            player.pause()
            isPlaying = false
        } else {
            player.start()
            if (speed != 1f) setPlaybackSpeed(speed)
            isPlaying = true
        }
    }

    fun seek(seconds: Int) {
        val safe = seconds.coerceIn(0, durationSeconds)
        progressSeconds = safe
        mediaPlayer?.seekTo(safe * 1000)
        updateBeatCounters()
    }

    fun skipBy(seconds: Int) {
        seek(progressSeconds + seconds)
    }

    fun updateVolume(value: Float) {
        volume = value.coerceIn(0f, 1f)
        mediaPlayer?.setVolume(volume, volume)
    }

    fun updateSpeed(value: Float) {
        speed = value.coerceIn(0.5f, 2f)
        setPlaybackSpeed(speed)
    }

    fun tick() {
        val player = mediaPlayer ?: return
        if (isPlaying) {
            progressSeconds = (player.currentPosition / 1000).coerceAtMost(durationSeconds)
            updateBeatCounters()
            maybePlayMetronomeClick()
        }
    }

    fun toggleShuffle() {
        shuffleEnabled = !shuffleEnabled
    }

    fun cycleRepeatMode() {
        repeatMode = when (repeatMode) {
            RepeatMode.Off -> RepeatMode.All
            RepeatMode.All -> RepeatMode.One
            RepeatMode.One -> RepeatMode.Off
        }
    }

    fun toggleVoiceCount() {
        voiceCountEnabled = !voiceCountEnabled
    }

    fun toggleMeasureCount() {
        measureCountEnabled = !measureCountEnabled
    }

    fun updateBpm(value: Int) {
        bpm = value.coerceIn(40, 240)
        updateBeatCounters()
    }

    fun toggleMetronomeOverlay() {
        metronomeOverlayEnabled = !metronomeOverlayEnabled
        lastMetronomeBeatIndex = -1
    }

    fun applyEqualizerSettings(settings: UserSettings) {
        eqValues = settings.eqValues()
        applyEqualizerBands()
    }

    fun setVocalReduction(enabled: Boolean) {
        vocalReductionEnabled = enabled
        applyEqualizerBands()
    }

    fun playNext(auto: Boolean = false): Boolean {
        if (queue.isEmpty()) return false
        val nextIndex = when {
            shuffleEnabled && queue.size > 1 -> {
                var candidate: Int
                do {
                    candidate = Random.nextInt(queue.size)
                } while (candidate == queueIndex)
                candidate
            }
            queueIndex < queue.lastIndex -> queueIndex + 1
            repeatMode == RepeatMode.All || !auto -> 0
            else -> return false
        }
        queueIndex = nextIndex
        startSong(queue[queueIndex])
        return true
    }

    fun playPrevious(): Boolean {
        if (progressSeconds > 3) {
            seek(0)
            return true
        }
        if (queue.isEmpty()) return false
        queueIndex = if (queueIndex > 0) queueIndex - 1 else queue.lastIndex
        startSong(queue[queueIndex])
        return true
    }

    fun playQueueIndex(index: Int): Boolean {
        if (index !in queue.indices) return false
        queueIndex = index
        startSong(queue[queueIndex])
        return true
    }

    fun removeQueueIndex(index: Int) {
        if (index !in queue.indices) return
        val wasCurrent = index == queueIndex
        queue = queue.filterIndexed { itemIndex, _ -> itemIndex != index }
        queueIndex = when {
            queue.isEmpty() -> -1
            index < queueIndex -> queueIndex - 1
            queueIndex >= queue.size -> queue.lastIndex
            else -> queueIndex
        }
        if (wasCurrent && queue.isNotEmpty()) {
            startSong(queue[queueIndex])
        } else if (wasCurrent) {
            releasePlayer()
            currentSong = null
            isPlaying = false
            progressSeconds = 0
        }
    }

    fun updateCurrentSong(song: Song) {
        currentSong = song
    }

    fun release() {
        releasePlayer()
        releaseEqualizer()
        toneGenerator?.release()
        toneGenerator = null
        currentSong = null
        isPlaying = false
        isLoading = false
        progressSeconds = 0
    }

    private fun setPlaybackSpeed(value: Float) {
        val player = mediaPlayer ?: return
        runCatching {
            player.playbackParams = player.playbackParams
                .setSpeed(value)
                .setPitch(1f)
        }.onFailure {
            errorMessage = context.getString(R.string.player_error_speed)
        }
    }

    private fun attachEqualizer(audioSessionId: Int) {
        releaseEqualizer()
        runCatching {
            equalizer = Equalizer(0, audioSessionId).apply { enabled = true }
            equalizerReady = true
            applyEqualizerBands()
        }.onFailure {
            equalizerReady = false
            errorMessage = context.getString(R.string.player_error_equalizer)
        }
    }

    private fun applyEqualizerBands() {
        val eq = equalizer ?: return
        runCatching {
            val range = eq.bandLevelRange
            val min = range[0].toInt()
            val max = range[1].toInt()
            val adjusted = if (vocalReductionEnabled) {
                eqValues.mapIndexed { index, value ->
                    when (index) {
                        1 -> value - 3
                        2 -> value - 10
                        3 -> value - 7
                        else -> value
                    }
                }
            } else {
                eqValues
            }
            val bands = eq.numberOfBands.toInt().coerceAtLeast(1)
            for (band in 0 until bands) {
                val sourceIndex = ((band.toFloat() / bands.toFloat()) * adjusted.size).toInt().coerceIn(adjusted.indices)
                val millibels = (adjusted[sourceIndex] * 100).coerceIn(min, max).toShort()
                eq.setBandLevel(band.toShort(), millibels)
            }
            equalizerReady = true
        }.onFailure {
            equalizerReady = false
        }
    }

    private fun releaseEqualizer() {
        equalizer?.runCatching {
            enabled = false
            release()
        }
        equalizer = null
        equalizerReady = false
    }

    private fun updateBeatCounters() {
        val beatLengthSeconds = 60f / bpm.coerceIn(40, 240)
        val beatIndex = (progressSeconds / beatLengthSeconds).toInt().coerceAtLeast(0)
        currentBeat = (beatIndex % 4) + 1
        currentMeasure = (beatIndex / 4) + 1
    }

    private fun maybePlayMetronomeClick() {
        if (!metronomeOverlayEnabled) return
        val beatLengthSeconds = 60f / bpm.coerceIn(40, 240)
        val beatIndex = (progressSeconds / beatLengthSeconds).toInt().coerceAtLeast(0)
        if (beatIndex == lastMetronomeBeatIndex) return
        lastMetronomeBeatIndex = beatIndex
        val tone = if (currentBeat == 1) ToneGenerator.TONE_PROP_BEEP2 else ToneGenerator.TONE_PROP_BEEP
        val generator = toneGenerator ?: ToneGenerator(AudioManager.STREAM_MUSIC, 70).also { toneGenerator = it }
        generator.startTone(tone, 70)
    }

    private fun sameIdentity(a: Song, b: Song): Boolean {
        return a.title.trim().equals(b.title.trim(), ignoreCase = true) &&
            a.artist.trim().equals(b.artist.trim(), ignoreCase = true)
    }

    private fun releasePlayer() {
        mediaPlayer?.runCatching {
            stop()
            release()
        }
        mediaPlayer = null
        releaseEqualizer()
    }
}

private fun UserSettings.eqValues(): List<Int> {
    return listOf(eq60, eq230, eq910, eq3600, eq14000)
}
