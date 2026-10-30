package com.matura.app.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.speech.tts.TextToSpeech
import kotlinx.serialization.json.Json
import java.util.Locale

/**
 * Spoken pronunciation for vocabulary entries.
 *
 * Two sources, in order:
 *  1. A bundled clip, looked up in assets/audio/index.json. Generated ahead of time,
 *     so playback works with no network and no INTERNET permission — the app still
 *     cannot open a socket.
 *  2. The system TextToSpeech engine, as a fallback. This is what covers the words a
 *     player pastes in from their own lesson, which by definition cannot be bundled.
 *
 * Every call is wrapped in runCatching: audio is a nice-to-have, and a missing codec
 * or a dead TTS engine must never take down a round.
 */
class Pronunciation(private val context: Context) {

    private var player: MediaPlayer? = null
    private var tts: TextToSpeech? = null
    private var ttsReady = false

    /** word (lowercased) -> asset filename. Empty when the audio pack is absent. */
    private val index: Map<String, String> by lazy {
        runCatching {
            val raw = context.assets.open(INDEX_PATH).bufferedReader().use { it.readText() }
            Json.decodeFromString<Map<String, String>>(raw).mapKeys { it.key.lowercase(Locale.ROOT) }
        }.getOrDefault(emptyMap())
    }

    /** True when a bundled clip exists — lets the UI hide the speaker icon rather than lie. */
    fun hasClip(word: String): Boolean = index.containsKey(word.trim().lowercase(Locale.ROOT))

    fun play(word: String, enabled: Boolean) {
        if (!enabled) return
        val key = word.trim().lowercase(Locale.ROOT)
        if (key.isEmpty()) return
        val asset = index[key]
        if (asset != null) playAsset(asset) else speak(word)
    }

    private fun playAsset(name: String) {
        runCatching {
            stop()
            val fd = context.assets.openFd("$ASSET_DIR/$name")
            player = MediaPlayer().apply {
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
                setDataSource(fd.fileDescriptor, fd.startOffset, fd.length)
                setOnCompletionListener { runCatching { it.release() }; if (player === this) player = null }
                prepare()
                start()
            }
            fd.close()
        }.onFailure { stop() }
    }

    /** Fallback for words with no bundled clip — chiefly the player's own pasted lists. */
    private fun speak(word: String) {
        runCatching {
            if (tts == null) {
                tts = TextToSpeech(context) { status ->
                    ttsReady = status == TextToSpeech.SUCCESS
                    if (ttsReady) {
                        runCatching { tts?.language = Locale.UK }
                        runCatching { tts?.speak(word, TextToSpeech.QUEUE_FLUSH, null, UTTERANCE_ID) }
                    }
                }
            } else if (ttsReady) {
                tts?.speak(word, TextToSpeech.QUEUE_FLUSH, null, UTTERANCE_ID)
            }
        }
    }

    fun stop() {
        runCatching { player?.stop() }
        runCatching { player?.release() }
        player = null
    }

    fun release() {
        stop()
        runCatching { tts?.stop() }
        runCatching { tts?.shutdown() }
        tts = null
        ttsReady = false
    }

    private companion object {
        const val ASSET_DIR = "audio"
        const val INDEX_PATH = "audio/index.json"
        const val UTTERANCE_ID = "matura-word"
    }
}
