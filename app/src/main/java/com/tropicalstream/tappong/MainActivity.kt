package com.tropicalstream.tappong

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import android.util.DisplayMetrics
import android.view.Choreographer
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import com.tropicalstream.tappong.audio.Sfx
import com.tropicalstream.tappong.game.Particles
import com.tropicalstream.tappong.game.PongGame
import com.tropicalstream.tappong.input.TrackpadGestureEngine
import com.tropicalstream.tappong.render.PongView
import com.tropicalstream.tappong.ui.BinocularSbsLayout

/**
 * TapPong — Pong remixed for the RayNeo X3 Pro.
 *
 * Controls (right temple pad):
 *   slide up/down — move your paddle (continuous, velocity adds english)
 *   tap           — start / serve early / restart / resume
 *   double-tap    — pause / resume
 *   long-hold     — RayNeo system Control Center / exit
 */
class MainActivity : Activity() {

    companion object {
        /** Raw pad px → paddle px. Tuned against the X3Gemini cursor gain. */
        private const val DRAG_GAIN = 0.6f
    }

    private val game = PongGame()
    private val particles = Particles()
    private val gestures = TrackpadGestureEngine()
    private val sfx by lazy { Sfx(this) }
    private lateinit var settings: SettingsStore
    private lateinit var view: PongView
    private var introFocus = 0

    private var running = false
    private var lastFrameMs = 0L

    /** 1dp == 1px on the 640×480-per-eye canvas — idempotent density set. */
    override fun attachBaseContext(newBase: Context) {
        val config = Configuration(newBase.resources.configuration).apply {
            densityDpi = DisplayMetrics.DENSITY_MEDIUM
        }
        super.attachBaseContext(newBase.createConfigurationContext(config))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        configureImmersive()
        settings = SettingsStore(this)
        applySettings()

        view = PongView(this, game, particles)
        val root = BinocularSbsLayout(this).apply {
            setBackgroundColor(Color.BLACK)
            addView(view)
        }
        setContentView(root)
        syncIntroView()

        gestures.setScreenSize(640, 480)
        gestures.onDrag = { _, dy -> game.movePlayerBy(dy * DRAG_GAIN) }
        gestures.onTap = {
            if (game.state == PongGame.State.READY) {
                if (introFocus == 0) {
                    applySettings()
                    game.onTap()
                    sfx.play(Sfx.START)
                } else adjustIntroSetting(+1)
            } else {
                val wasGameOver = game.state == PongGame.State.GAMEOVER
                game.onTap()
                if (wasGameOver) sfx.play(Sfx.START)
            }
        }
        gestures.onSwipeVertical = { direction ->
            if (game.state == PongGame.State.READY) {
                introFocus = (introFocus + direction).mod(4)
                syncIntroView()
                sfx.play(Sfx.COUNT, pitch = 1.15f, vol = 0.55f)
            }
        }
        gestures.onSwipeHorizontal = { direction ->
            if (game.state == PongGame.State.READY && introFocus != 0) {
                adjustIntroSetting(direction)
            }
        }
        gestures.onDoubleTap = { game.togglePause() }
        gestures.onLongTap = { openRayNeoControlCenter() }

        wireGameEvents()
        sfx.loadAsync()
    }

    private fun adjustIntroSetting(direction: Int) {
        when (introFocus) {
            1 -> {
                val values = PongGame.Difficulty.entries
                val i = values.indexOf(settings.difficulty)
                settings.difficulty = values[(i + direction).mod(values.size)]
            }
            2 -> settings.powerUps = !settings.powerUps
            3 -> settings.sound = !settings.sound
        }
        applySettings()
        syncIntroView()
        sfx.play(Sfx.COUNT, pitch = 1.3f, vol = 0.6f)
    }

    private fun applySettings() {
        game.difficulty = settings.difficulty
        game.powerUpsEnabled = settings.powerUps
        sfx.volume = if (settings.sound) 0.6f else 0f
    }

    private fun syncIntroView() {
        if (!::view.isInitialized) return
        view.introFocus = introFocus
        view.introDifficulty = settings.difficulty
        view.introPowerUps = settings.powerUps
        view.introSound = settings.sound
        view.invalidate()
    }

    private fun wireGameEvents() {
        game.onPaddleHit = { x, y, byPlayer, rally ->
            // the classic blip, pitched up as the rally builds
            sfx.play(Sfx.PADDLE, pitch = 1f + (rally.coerceAtMost(16)) * 0.045f)
            particles.burst(x, y, if (byPlayer) PongView.CYAN else PongView.MAGENTA, 10, 180f)
        }
        game.onWallHit = { x, y ->
            sfx.play(Sfx.WALL)
            particles.burst(x, y, PongView.DIM, 5, 110f)
        }
        game.onScore = { playerScored ->
            sfx.play(if (playerScored) Sfx.SCORE_ME else Sfx.SCORE_CPU)
            val gx = if (playerScored) PongGame.W - 12f else 12f
            particles.burst(gx, game.balls.firstOrNull()?.y ?: PongGame.H / 2f,
                if (playerScored) PongView.CYAN else PongView.MAGENTA, 44, 320f)
        }
        game.onServe = { sfx.play(Sfx.SERVE) }
        game.onCountTick = { sfx.play(Sfx.COUNT) }
        game.onPowerSpawn = { sfx.play(Sfx.PWR_SPAWN) }
        game.onPowerGet = { p, _ ->
            sfx.play(Sfx.PWR_GET)
            particles.burst(p.x, p.y, PongView.GOLD, 22, 240f)
        }
        game.onGameOver = { won -> sfx.play(if (won) Sfx.WIN else Sfx.LOSE) }
    }

    private fun configureImmersive() {
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        window.addFlags(WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED)
        window.statusBarColor = Color.BLACK
        window.navigationBarColor = Color.BLACK
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility =
            View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
            View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or
            View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
            View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
            View.SYSTEM_UI_FLAG_FULLSCREEN or
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
        window.decorView.setBackgroundColor(Color.BLACK)
    }

    // ~frame-rate loop; dt-based physics keeps motion speed-correct.
    private val frame = object : Choreographer.FrameCallback {
        override fun doFrame(t: Long) {
            if (!running) return
            val now = SystemClock.uptimeMillis()
            val dt = if (lastFrameMs == 0L) 0f else ((now - lastFrameMs) / 1000f).coerceAtMost(0.05f)
            lastFrameMs = now
            game.update(dt)
            particles.update(dt)
            view.setFrameTime(now)
            view.invalidate()
            Choreographer.getInstance().postFrameCallback(this)
        }
    }

    override fun onResume() {
        super.onResume()
        running = true
        lastFrameMs = 0L
        Choreographer.getInstance().removeFrameCallback(frame)
        Choreographer.getInstance().postFrameCallback(frame)
    }

    override fun onPause() {
        super.onPause()
        running = false
        // Sleep button fires onPause mid-wear — pause the match, don't lose it.
        if (game.state == PongGame.State.PLAYING || game.state == PongGame.State.SERVING) {
            game.togglePause()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        gestures.release()
        sfx.release()
    }

    /** Open the genuine X3 launcher panel, with HOME as a firmware-safe escape. */
    private fun openRayNeoControlCenter() {
        val controlCenter = Intent(
            Intent.ACTION_VIEW,
            Uri.parse("mercury://com.ffalconxr.mercury.launcher/openApp/shortcut")
        ).addCategory(Intent.CATEGORY_DEFAULT)
        runCatching { startActivity(controlCenter) }
            .onFailure {
                val home = Intent(Intent.ACTION_MAIN)
                    .addCategory(Intent.CATEGORY_HOME)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                runCatching { startActivity(home); finishAndRemoveTask() }
            }
    }

    // Temple FIRM-click arrives as a KEY — check first so nothing swallows it.
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (gestures.onKeyEvent(event)) return true
        return super.dispatchKeyEvent(event)
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        if (gestures.onTouchEvent(ev)) return true
        return super.dispatchTouchEvent(ev)
    }

    override fun dispatchGenericMotionEvent(ev: MotionEvent): Boolean {
        if (gestures.onGenericMotion(ev)) return true
        return super.dispatchGenericMotionEvent(ev)
    }
}
