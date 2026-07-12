package com.tropicalstream.tappong.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.SoundPool
import android.os.Handler
import android.os.HandlerThread
import java.io.BufferedOutputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileOutputStream
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.sin
import kotlin.random.Random

/**
 * Synthesized SFX bank for TapPong (no audio binaries ship) — the classic
 * square-wave Pong voice, remixed: paddle blips whose pitch climbs with the
 * rally, a hollow wall knock, a dirty score zap, shimmering power-up chords,
 * and win/lose arpeggios. Same SoundPool-on-a-worker pattern as TapMeteors.
 */
class Sfx(private val context: Context) {

    companion object {
        const val PADDLE = 0
        const val WALL = 1
        const val SCORE_ME = 2
        const val SCORE_CPU = 3
        const val SERVE = 4
        const val COUNT = 5
        const val PWR_SPAWN = 6
        const val PWR_GET = 7
        const val WIN = 8
        const val LOSE = 9
        const val START = 10
        private const val BANK = 11
        private const val RATE = 22050
    }

    private val pool = SoundPool.Builder()
        .setMaxStreams(8)
        .setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_GAME)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build()
        ).build()

    private val ids = IntArray(BANK)
    @Volatile private var loaded = false
    @Volatile var volume = 0.6f
    private val rng = Random(7)

    // SoundPool calls are binder calls; keep them off the render thread.
    private var thread: HandlerThread? = null
    private var handler: Handler? = null

    fun loadAsync() {
        thread = HandlerThread("tappong-sfx").apply { start() }
        handler = Handler(thread!!.looper)
        handler?.post {
            runCatching {
                val dir = File(context.cacheDir, "sfx").apply { mkdirs() }
                // The iconic Pong blip: short square. Pitch is varied at play().
                ids[PADDLE] = load(dir, "paddle", buf(70) { t -> sq(440f, t) * exp(-t * 30f) * 0.5f })
                ids[WALL] = load(dir, "wall", buf(55) { t -> sq(220f, t) * exp(-t * 40f) * 0.45f })
                ids[SCORE_ME] = load(dir, "scoreme", arpeggio(intArrayOf(523, 659, 784), 70, 0.8f))
                ids[SCORE_CPU] = load(dir, "scorecpu", buf(500) { t ->
                    val f = 300f - t * 190f
                    (sq(f, t) * 0.45f + noise() * 0.12f * exp(-t * 9f)) * exp(-t * 5f)
                })
                ids[SERVE] = load(dir, "serve", buf(160) { t -> sq(660f + 400f * t, t) * exp(-t * 14f) * 0.4f })
                ids[COUNT] = load(dir, "count", buf(90) { t -> sine(880f, t) * exp(-t * 24f) * 0.35f })
                ids[PWR_SPAWN] = load(dir, "pspawn", buf(420) { t ->
                    sine(500f + 1100f * t, t) * exp(-t * 5f) * 0.3f + sine(750f + 1100f * t, t) * exp(-t * 6f) * 0.18f
                })
                ids[PWR_GET] = load(dir, "pget", arpeggio(intArrayOf(659, 880, 1174, 1568), 55, 0.75f))
                ids[WIN] = load(dir, "win", arpeggio(intArrayOf(523, 659, 784, 1046, 1318, 1568), 80, 0.75f))
                ids[LOSE] = load(dir, "lose", buf(900) { t ->
                    val f = if (t < 0.4f) 330f - t * 180f else 260f - (t - 0.4f) * 140f
                    (saw(f, t) * 0.4f + sine(f * 0.5f, t) * 0.4f) * exp(-t * 2.2f)
                })
                ids[START] = load(dir, "start", arpeggio(intArrayOf(330, 440, 554, 659, 880), 70, 0.7f))
                loaded = true
            }
        }
    }

    /** Safe from any thread. */
    fun play(id: Int, pitch: Float = 1f, vol: Float = 1f) {
        if (!loaded || id < 0 || id >= BANK) return
        handler?.post {
            val s = ids[id]
            if (s == 0) return@post
            val v = (volume * vol).coerceIn(0f, 1f)
            if (v <= 0f) return@post
            pool.play(s, v, v, 1, 0, pitch.coerceIn(0.5f, 2f))
        }
    }

    fun release() {
        handler?.post { runCatching { pool.release() } }
        thread?.quitSafely()
        thread = null
        handler = null
    }

    // ------------------------------------------------------------ synthesis

    private fun buf(ms: Int, gen: (Float) -> Float): ShortArray {
        val n = RATE * ms / 1000
        return ShortArray(n) { i -> (gen(i.toFloat() / RATE).coerceIn(-1f, 1f) * 30000f).toInt().toShort() }
    }

    private fun sine(f: Float, t: Float) = sin(2.0 * PI * f * t).toFloat()
    private fun saw(f: Float, t: Float): Float { val p = (f * t) % 1f; return 2f * p - 1f }
    private fun sq(f: Float, t: Float) = if ((f * t) % 1f < 0.5f) 1f else -1f
    private fun noise() = rng.nextFloat() * 2f - 1f

    private fun arpeggio(freqs: IntArray, noteMs: Int, amp: Float): ShortArray {
        val total = noteMs * freqs.size + 220
        return buf(total) { t ->
            var v = 0f
            for ((i, f) in freqs.withIndex()) {
                val start = i * noteMs / 1000f
                if (t >= start) {
                    val lt = t - start
                    v += (sine(f.toFloat(), lt) + 0.3f * sine(f * 2f, lt)) * exp(-lt * 5.5f) * amp * 0.4f
                }
            }
            v
        }
    }

    // ------------------------------------------------------------- wav

    private fun DataOutputStream.wInt(v: Int) { write(v and 0xFF); write((v shr 8) and 0xFF); write((v shr 16) and 0xFF); write((v shr 24) and 0xFF) }
    private fun DataOutputStream.wShort(v: Int) { write(v and 0xFF); write((v shr 8) and 0xFF) }

    private fun load(dir: File, name: String, pcm: ShortArray): Int {
        val f = File(dir, "$name.wav")
        val dataLen = pcm.size * 2
        DataOutputStream(BufferedOutputStream(FileOutputStream(f))).use { o ->
            o.writeBytes("RIFF"); o.wInt(36 + dataLen); o.writeBytes("WAVE")
            o.writeBytes("fmt "); o.wInt(16); o.wShort(1); o.wShort(1)
            o.wInt(RATE); o.wInt(RATE * 2); o.wShort(2); o.wShort(16)
            o.writeBytes("data"); o.wInt(dataLen)
            for (s in pcm) o.wShort(s.toInt())
        }
        return pool.load(f.absolutePath, 1)
    }
}
