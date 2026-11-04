package com.oxdh.cubeclash

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.media.SoundPool
import android.os.SystemClock
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.pow
import kotlin.math.sin
import kotlin.random.Random

enum class Sfx { COLLECT, BOOST, OPPONENT_DEFEAT, PLAYER_DEFEAT, CLICK, HIGH_SCORE, WARNING, MERGE0, MERGE1, MERGE2, MERGE3, MERGE4 }

/**
 * All sound effects and the music loop are synthesised at first launch (original audio, no licensing), written as
 * WAV files to the cache and played with SoundPool / MediaPlayer. Separate music & SFX volume and mute.
 */
class Sound(private val context: Context, private val settings: Settings) {
    private val pool = SoundPool.Builder().setMaxStreams(10).setAudioAttributes(
        AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_GAME).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build()).build()
    private val ids = IntArray(Sfx.values().size)
    private var music: MediaPlayer? = null
    @Volatile private var ready = false
    private var lastCollect = 0L
    private var musicWanted = false

    init {
        Thread({
            try {
                val dir = File(context.cacheDir, "audio_v1").apply { mkdirs() }
                for (s in Sfx.values()) {
                    val f = File(dir, s.name.lowercase() + ".wav")
                    if (!f.exists()) writeWav(f, synth(s))
                    ids[s.ordinal] = pool.load(f.path, 1)
                }
                ready = true
                val mf = File(dir, "music.wav")
                if (!mf.exists()) writeWav(mf, music())
                val mp = MediaPlayer()
                mp.setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_GAME).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
                mp.setDataSource(mf.path)
                mp.isLooping = true
                mp.prepare()
                synchronized(this) {
                    music = mp
                    applyVolumes()
                    if (musicWanted) mp.start()
                }
            } catch (e: Exception) {
                android.util.Log.w("CubeClash", "Audio init failed: ${e.message}")
            }
        }, "audio-init").start()
        settings.addListener { applyVolumes() }
    }

    @Synchronized fun applyVolumes() {
        val v = if (settings.muted) 0f else settings.musicVolume * 0.6f
        music?.setVolume(v, v)
    }

    @Synchronized fun startMusic() { musicWanted = true; music?.let { if (!it.isPlaying) it.start() } }
    @Synchronized fun pauseMusic() { musicWanted = false; music?.let { if (it.isPlaying) it.pause() } }

    fun play(s: Sfx, rate: Float = 1f, volume: Float = 1f) {
        if (!ready || settings.muted) return
        val v = (settings.sfxVolume * volume).coerceIn(0f, 1f)
        if (v <= 0.001f) return
        pool.play(ids[s.ordinal], v, v, 1, 0, rate.coerceIn(0.5f, 2f))
    }

    fun collect(level: Int) {
        val now = SystemClock.uptimeMillis()
        val combo = now - lastCollect < 400
        lastCollect = now
        play(Sfx.COLLECT, (if (combo) 1.12f else 1f) + level * 0.015f, 0.8f)
    }

    /** Higher values use brighter merge tiers and pitch, so big merges sound more distinctive. */
    fun merge(level: Int) {
        val tier = ((level - 1) / 3).coerceIn(0, 4)
        play(Sfx.values()[Sfx.MERGE0.ordinal + tier], 1f + ((level - 1).coerceAtLeast(0) % 3) * 0.06f, 0.9f)
    }

    fun release() {
        pool.release()
        synchronized(this) { music?.release(); music = null }
    }

    // ------------------------------------------------------------------ synthesis

    private companion object {
        const val RATE = 22050
        fun env(t: Float, a: Float, d: Float) = if (t < a) t / a else exp(-(t - a) / d)
        fun sine(f: Float, t: Float) = sin(2.0 * PI * f * t).toFloat()
        fun tri(f: Float, t: Float): Float { val x = t * f; return 2f * abs(2f * (x - floor(x + 0.5f))) - 1f }
        fun square(f: Float, t: Float, duty: Float = 0.5f) = if ((t * f) % 1f < duty) 1f else -1f

        fun make(seconds: Float, fn: (Float) -> Float): FloatArray {
            val n = (seconds * RATE).toInt()
            val d = FloatArray(n) { fn(it / RATE.toFloat()).coerceIn(-1f, 1f) }
            val fade = minOf(200, n)
            for (i in 0 until fade) d[n - 1 - i] *= i / fade.toFloat()
            return d
        }

        fun synth(s: Sfx): FloatArray = when (s) {
            Sfx.COLLECT -> make(0.14f) { t -> val f = 880f + (1500f - 880f) * t / 0.14f; (sine(f, t) * 0.6f + tri(f * 2, t) * 0.15f) * env(t, 0.004f, 0.05f) * 0.7f }
            Sfx.BOOST -> { val r = Random(3); var lp = 0f
                make(0.4f) { t -> val n = r.nextFloat() * 2 - 1; lp += (n - lp) * (0.05f + 0.3f * t / 0.4f)
                    (lp * 0.9f + sine(180f + 240f * t / 0.4f, t) * 0.25f) * env(t, 0.03f, 0.15f) * 0.6f } }
            Sfx.OPPONENT_DEFEAT -> { val r = Random(7)
                make(0.5f) { t -> val f = 600f - 460f * t / 0.5f
                    (square(f, t, 0.3f) * 0.25f + (r.nextFloat() * 2 - 1) * 0.35f * env(t, 0.001f, 0.06f) + sine(f * 0.5f, t) * 0.3f) * env(t, 0.003f, 0.16f) * 0.7f } }
            Sfx.PLAYER_DEFEAT -> make(0.9f) { t -> val f = 420f - 350f * kotlin.math.sqrt(t / 0.9f); (tri(f, t) * 0.5f + sine(f * 0.5f, t) * 0.4f) * env(t, 0.01f, 0.35f) * 0.75f }
            Sfx.CLICK -> make(0.06f) { t -> sine(1250f, t) * env(t, 0.001f, 0.015f) * 0.6f }
            Sfx.HIGH_SCORE -> { val notes = floatArrayOf(523.25f, 659.25f, 783.99f, 1046.5f)
                make(1.1f) { t -> var v = 0f
                    for (i in notes.indices) { val st = i * 0.12f; if (t < st) continue; val lt = t - st
                        v += (sine(notes[i], lt) * 0.5f + sine(notes[i] * 2, lt) * 0.15f) * env(lt, 0.005f, if (i == notes.size - 1) 0.45f else 0.12f) }
                    v * 0.45f } }
            Sfx.WARNING -> make(0.22f) { t -> square(if (t < 0.11f) 660f else 520f, t) * 0.18f * env(t, 0.005f, 0.08f) }
            else -> { val tier = s.ordinal - Sfx.MERGE0.ordinal
                make(0.32f + tier * 0.06f) { t ->
                    val bf = 523.25f * 2f.pow(tier * 3 / 12f)
                    var v = sine(bf, t) * 0.5f + sine(bf * 1.5f, t) * 0.25f * minOf(1f, t / 0.05f)
                    v += sine(bf * 2, t) * 0.18f * tier / 4f
                    if (tier >= 2) v += sine(bf * 3.01f, t) * 0.12f * env(t, 0.001f, 0.06f)
                    if (tier >= 3) v += sine(bf * 4 + 7f * sin(t * 40f), t) * 0.1f * env(maxOf(0f, t - 0.06f), 0.01f, 0.12f)
                    v * env(t, 0.005f, 0.12f + tier * 0.03f) * 0.65f } }
        }

        /** Calm looping track: I–vi–IV–V with pad, arpeggio, bass and a soft hat. */
        fun music(): FloatArray {
            val beat = 60f / 104f
            val chords = arrayOf(floatArrayOf(261.63f, 329.63f, 392f), floatArrayOf(220f, 261.63f, 329.63f),
                floatArrayOf(174.61f, 220f, 261.63f), floatArrayOf(196f, 246.94f, 293.66f))
            val r = Random(11)
            return make(8 * 4 * beat) { t ->
                val chord = chords[((t / (4 * beat)).toInt()) % chords.size]
                val barT = t % (4 * beat)
                var pad = 0f
                for (f in chord) pad += sine(f, t) * 0.06f + sine(f * 1.003f, t) * 0.04f
                pad *= 0.8f + 0.2f * sin(t * 0.7f)
                val eighth = beat * 0.5f
                val step = (barT / eighth).toInt()
                val nf = chord[step % 3] * (if (step % 6 >= 3) 2f else 1f) * 2f
                val arp = (tri(nf, t) * 0.5f + sine(nf, t) * 0.5f) * env(barT % eighth, 0.005f, 0.09f) * 0.13f
                val bass = sine(chord[0] * 0.5f, t) * env(barT % beat, 0.01f, 0.18f) * 0.22f
                val hat = (r.nextFloat() * 2 - 1) * env((barT + beat * 0.5f) % beat, 0.001f, 0.015f) * 0.05f
                (pad + arp + bass + hat) * 0.9f
            }
        }

        fun writeWav(f: File, samples: FloatArray) {
            val data = ByteBuffer.allocate(samples.size * 2).order(ByteOrder.LITTLE_ENDIAN)
            for (s in samples) data.putShort((s * 32767f).toInt().coerceIn(-32768, 32767).toShort())
            val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
            header.put("RIFF".toByteArray()).putInt(36 + samples.size * 2).put("WAVE".toByteArray())
            header.put("fmt ".toByteArray()).putInt(16).putShort(1).putShort(1).putInt(RATE).putInt(RATE * 2).putShort(2).putShort(16)
            header.put("data".toByteArray()).putInt(samples.size * 2)
            val tmp = File(f.path + ".tmp")
            FileOutputStream(tmp).use { it.write(header.array()); it.write(data.array()) }
            tmp.renameTo(f)
        }
    }
}
