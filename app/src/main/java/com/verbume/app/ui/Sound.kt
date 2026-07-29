package com.verbume.app.ui

import android.media.AudioManager
import android.media.ToneGenerator
import com.verbume.app.game.FlashKind

/**
 * OPTIONS offers enable/disable sound. There are no audio assets in this build, so
 * feedback is a handful of short system tones — cheap, and it keeps the APK free of
 * binary blobs that would have to be licensed.
 */
class Sfx {
    private var gen: ToneGenerator? = null

    private fun generator(): ToneGenerator? {
        if (gen == null) {
            gen = runCatching { ToneGenerator(AudioManager.STREAM_MUSIC, 70) }.getOrNull()
        }
        return gen
    }

    fun play(kind: FlashKind, enabled: Boolean) {
        if (!enabled) return
        val g = generator() ?: return
        val tone = when (kind) {
            FlashKind.HIT -> ToneGenerator.TONE_PROP_BEEP
            FlashKind.ARMOR -> ToneGenerator.TONE_PROP_ACK
            FlashKind.MISS -> ToneGenerator.TONE_PROP_NACK
            FlashKind.CRATE -> ToneGenerator.TONE_PROP_BEEP2
            FlashKind.LIFE_LOST -> ToneGenerator.TONE_SUP_ERROR
        }
        runCatching { g.startTone(tone, 90) }
    }

    fun key(enabled: Boolean) {
        if (!enabled) return
        runCatching { generator()?.startTone(ToneGenerator.TONE_DTMF_S, 20) }
    }

    fun release() {
        runCatching { gen?.release() }
        gen = null
    }
}
