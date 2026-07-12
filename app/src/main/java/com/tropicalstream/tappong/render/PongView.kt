package com.tropicalstream.tappong.render

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.view.View
import com.tropicalstream.tappong.game.Particles
import com.tropicalstream.tappong.game.PongGame
import kotlin.math.min
import kotlin.math.sin
import kotlin.random.Random

/**
 * All rendering for TapPong on the 640×480 logical eye canvas: neon court,
 * glowing paddles (cyan = you, magenta = CPU), a retro SQUARE ball with a
 * fading trail, drifting power-up glyphs, seven-segment-ish scores, and
 * title/serve/point/game-over overlays — on pure black (waveguide-off).
 *
 * Glow is layered translucent strokes (no BlurMaskFilter) so everything stays
 * on the hardware-accelerated path BinocularSbsLayout's dual-draw needs.
 */
class PongView(
    context: Context,
    private val game: PongGame,
    private val particles: Particles
) : View(context) {

    companion object {
        const val CYAN = 0xFF00E5FF.toInt()
        const val MAGENTA = 0xFFFF2E97.toInt()
        const val GOLD = 0xFFFFD54F.toInt()
        const val GREEN = 0xFF69F0AE.toInt()
        const val WHITE = 0xFFFFFFFF.toInt()
        const val DIM = 0x66FFFFFF
        private const val TRAIL_LEN = 10
    }

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
        textAlign = Paint.Align.CENTER
    }
    private val dash = DashPathEffect(floatArrayOf(10f, 12f), 0f)
    private val rect = RectF()
    private var frameTime = 0L
    private val rng = Random(3)

    // Per-ball position history for the trail (parallel to game.balls by index).
    private val trails = ArrayList<ArrayDeque<Pair<Float, Float>>>()

    fun setFrameTime(now: Long) {
        frameTime = now
    }

    override fun onDraw(canvas: Canvas) {
        canvas.drawColor(Color.BLACK)

        // screen shake on goals
        val sh = game.shake
        if (sh > 0f) {
            canvas.save()
            canvas.translate(
                (rng.nextFloat() * 2f - 1f) * sh * 7f,
                (rng.nextFloat() * 2f - 1f) * sh * 7f
            )
        }

        drawCourt(canvas)
        if (game.state != PongGame.State.READY) {
            drawScores(canvas)
            drawPowerUps(canvas)
            drawPaddles(canvas)
            drawBalls(canvas)
            drawEffectPills(canvas)
            drawRally(canvas)
        }
        particles.draw(canvas)
        drawOverlay(canvas)

        if (sh > 0f) canvas.restore()
    }

    // ------------------------------------------------------------ court

    private fun drawCourt(canvas: Canvas) {
        val top = PongGame.COURT_TOP
        val bottom = PongGame.COURT_BOTTOM
        // walls: layered neon lines
        for ((w, a) in listOf(7f to 26, 3.5f to 80, 1.6f to 200)) {
            stroke.pathEffect = null
            stroke.strokeWidth = w
            stroke.color = CYAN
            stroke.alpha = a
            canvas.drawLine(0f, top, width.toFloat(), top, stroke)
            canvas.drawLine(0f, bottom, width.toFloat(), bottom, stroke)
        }
        // center line: dashed, breathing
        val breathe = 120 + (40 * sin(frameTime / 700.0)).toInt()
        stroke.strokeWidth = 3f
        stroke.pathEffect = dash
        stroke.color = WHITE
        stroke.alpha = breathe.coerceIn(60, 200)
        canvas.drawLine(width / 2f, top + 6f, width / 2f, bottom - 6f, stroke)
        stroke.pathEffect = null
    }

    private fun drawScores(canvas: Canvas) {
        text.textSize = 44f
        text.typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
        // player (left, cyan)
        text.color = CYAN
        text.alpha = 70
        canvas.drawText("${game.playerScore}", width * 0.38f, 76f, text)
        text.alpha = 255
        canvas.drawText("${game.playerScore}", width * 0.38f - 1.5f, 74.5f, text)
        // cpu (right, magenta)
        text.color = MAGENTA
        text.alpha = 70
        canvas.drawText("${game.aiScore}", width * 0.62f, 76f, text)
        text.alpha = 255
        canvas.drawText("${game.aiScore}", width * 0.62f - 1.5f, 74.5f, text)
        // match point flash
        val matchPoint = game.playerScore == PongGame.WIN_SCORE - 1 ||
            game.aiScore == PongGame.WIN_SCORE - 1
        if (matchPoint && game.state == PongGame.State.PLAYING) {
            val blink = (frameTime / 400) % 2 == 0L
            if (blink) {
                text.textSize = 13f
                text.color = GOLD
                text.alpha = 230
                canvas.drawText("MATCH POINT", width / 2f, 66f, text)
            }
        }
    }

    // ------------------------------------------------------------ actors

    private fun drawPaddles(canvas: Canvas) {
        drawPaddle(canvas, PongGame.PLAYER_X, game.playerY, game.playerH, CYAN)
        drawPaddle(canvas, PongGame.AI_X, game.aiY, game.aiH, MAGENTA)
    }

    private fun drawPaddle(canvas: Canvas, x: Float, cy: Float, h: Float, color: Int) {
        rect.set(x, cy - h / 2f, x + PongGame.PADDLE_W, cy + h / 2f)
        // glow halo
        paint.style = Paint.Style.FILL
        paint.color = color
        paint.alpha = 46
        canvas.drawRoundRect(
            rect.left - 5f, rect.top - 5f, rect.right + 5f, rect.bottom + 5f,
            6f, 6f, paint
        )
        paint.alpha = 110
        canvas.drawRoundRect(rect.left - 2f, rect.top - 2f, rect.right + 2f, rect.bottom + 2f, 4f, 4f, paint)
        paint.alpha = 255
        canvas.drawRoundRect(rect, 3f, 3f, paint)
        // white-hot core stripe
        paint.color = WHITE
        paint.alpha = 130
        canvas.drawRoundRect(rect.left + 2.5f, rect.top + 4f, rect.right - 2.5f, rect.bottom - 4f, 2f, 2f, paint)
        paint.alpha = 255
    }

    private fun drawBalls(canvas: Canvas) {
        // keep trail store parallel to the ball list
        while (trails.size < game.balls.size) trails.add(ArrayDeque())
        while (trails.size > game.balls.size) trails.removeAt(trails.size - 1)

        for ((i, b) in game.balls.withIndex()) {
            val trail = trails[i]
            trail.addLast(b.x to b.y)
            while (trail.size > TRAIL_LEN) trail.removeFirst()
            // trail: shrinking, fading squares
            paint.style = Paint.Style.FILL
            for ((j, p) in trail.withIndex()) {
                val f = (j + 1f) / trail.size
                val r = PongGame.BALL_R * (0.35f + 0.65f * f)
                paint.color = if (game.turboLeft > 0f) GOLD else WHITE
                paint.alpha = (f * f * 90).toInt()
                canvas.drawRect(p.first - r, p.second - r, p.first + r, p.second + r, paint)
            }
            // ball: retro square, white-hot with halo (gold under turbo)
            val r = PongGame.BALL_R
            paint.color = if (game.turboLeft > 0f) GOLD else CYAN
            paint.alpha = 60
            canvas.drawRect(b.x - r - 4f, b.y - r - 4f, b.x + r + 4f, b.y + r + 4f, paint)
            paint.color = WHITE
            paint.alpha = 255
            canvas.drawRect(b.x - r, b.y - r, b.x + r, b.y + r, paint)
            // spin indicator: tiny side streaks when the ball carries english
            if (kotlin.math.abs(b.spin) > 1.2f) {
                paint.color = GREEN
                paint.alpha = 150
                val s = if (b.spin > 0) 1f else -1f
                canvas.drawRect(b.x - r, b.y + s * (r + 2.5f) - 1f, b.x + r, b.y + s * (r + 2.5f) + 1f, paint)
                paint.alpha = 255
            }
        }
    }

    // ------------------------------------------------------------ power-ups

    private fun drawPowerUps(canvas: Canvas) {
        for (p in game.powerUps) {
            val pulse = 0.85f + 0.15f * sin(frameTime / 160.0).toFloat()
            val r = PongGame.POWER_R * pulse
            val color = when (p.type) {
                PongGame.PowerType.GROW -> GREEN
                PongGame.PowerType.SHRINK -> MAGENTA
                PongGame.PowerType.MULTI -> CYAN
                PongGame.PowerType.TURBO -> GOLD
            }
            // fade out in the last 2 seconds of life
            val lifeA = (p.life / 2f).coerceIn(0.25f, 1f)
            paint.style = Paint.Style.FILL
            paint.color = color
            paint.alpha = (40 * lifeA).toInt()
            canvas.drawCircle(p.x, p.y, r + 6f, paint)
            stroke.strokeWidth = 2f
            stroke.color = color
            stroke.alpha = (230 * lifeA).toInt()
            canvas.drawCircle(p.x, p.y, r, stroke)
            text.textSize = 14f
            text.color = color
            text.alpha = (255 * lifeA).toInt()
            val glyph = when (p.type) {
                PongGame.PowerType.GROW -> "+"
                PongGame.PowerType.SHRINK -> "−"
                PongGame.PowerType.MULTI -> "3"
                PongGame.PowerType.TURBO -> "»"
            }
            canvas.drawText(glyph, p.x, p.y + 5f, text)
            text.alpha = 255
        }
    }

    /** Small labels under the score while an effect runs. */
    private fun drawEffectPills(canvas: Canvas) {
        text.textSize = 11f
        var yL = 96f
        var yR = 96f
        fun pill(label: String, secs: Float, color: Int, leftSide: Boolean) {
            if (secs <= 0f) return
            val x = if (leftSide) width * 0.38f else width * 0.62f
            val y = if (leftSide) yL else yR
            text.color = color
            text.alpha = 210
            canvas.drawText("$label ${secs.toInt() + 1}", x, y, text)
            if (leftSide) yL += 15f else yR += 15f
        }
        pill("BIG", game.growLeft, GREEN, leftSide = true)
        pill("SMALL", game.playerShrinkLeft, MAGENTA, leftSide = true)
        pill("BIG", game.aiGrowLeft, GREEN, leftSide = false)
        pill("SMALL", game.shrinkFoeLeft, MAGENTA, leftSide = false)
        if (game.turboLeft > 0f) {
            text.color = GOLD
            text.alpha = 210
            canvas.drawText("TURBO ${game.turboLeft.toInt() + 1}", width / 2f, 96f, text)
        }
        text.alpha = 255
    }

    private fun drawRally(canvas: Canvas) {
        if (game.rally >= 4 && game.state == PongGame.State.PLAYING) {
            text.textSize = 12f
            text.color = WHITE
            text.alpha = 120 + min(100, game.rally * 8)
            canvas.drawText("RALLY ${game.rally}", width / 2f, PongGame.COURT_BOTTOM - 10f, text)
            text.alpha = 255
        }
    }

    // ------------------------------------------------------------ overlays

    private fun drawOverlay(canvas: Canvas) {
        when (game.state) {
            PongGame.State.READY -> {
                title(canvas, "TAPPONG", CYAN)
                text.textSize = 15f
                text.color = WHITE
                text.alpha = 220
                canvas.drawText("slide finger — move paddle", width / 2f, height * 0.60f, text)
                canvas.drawText("catch drifting power-ups with the ball", width / 2f, height * 0.66f, text)
                text.color = GOLD
                val blink = (frameTime / 500) % 2 == 0L
                if (blink) canvas.drawText("TAP TO PLAY  ·  first to ${PongGame.WIN_SCORE}", width / 2f, height * 0.76f, text)
            }
            PongGame.State.SERVING -> {
                val n = game.serveCountdown.toInt() + 1
                text.textSize = 40f
                text.color = WHITE
                text.alpha = 230
                canvas.drawText("$n", width / 2f, height / 2f - 30f, text)
                text.textSize = 12f
                text.alpha = 140
                canvas.drawText("tap to serve now", width / 2f, height / 2f - 4f, text)
            }
            PongGame.State.POINT -> {
                text.textSize = 26f
                text.color = if (game.lastPointToPlayer) CYAN else MAGENTA
                text.alpha = 240
                canvas.drawText(
                    if (game.lastPointToPlayer) "POINT — YOU" else "POINT — CPU",
                    width / 2f, height / 2f - 20f, text
                )
            }
            PongGame.State.PAUSED -> {
                title(canvas, "PAUSED", GOLD)
                text.textSize = 14f
                text.color = WHITE
                text.alpha = 200
                canvas.drawText("tap to resume", width / 2f, height * 0.62f, text)
            }
            PongGame.State.GAMEOVER -> {
                title(canvas, if (game.playerWon) "YOU WIN" else "CPU WINS", if (game.playerWon) GREEN else MAGENTA)
                text.textSize = 14f
                text.color = WHITE
                text.alpha = 220
                canvas.drawText(
                    "${game.playerScore} — ${game.aiScore}   ·   best rally ${game.bestRally}",
                    width / 2f, height * 0.60f, text
                )
                text.color = GOLD
                val blink = (frameTime / 500) % 2 == 0L
                if (blink) canvas.drawText("TAP TO PLAY AGAIN", width / 2f, height * 0.70f, text)
            }
            else -> {}
        }
    }

    /** Big neon title with layered glow. */
    private fun title(canvas: Canvas, s: String, color: Int) {
        val cx = width / 2f
        val cy = height * 0.42f
        text.textSize = 54f
        text.typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
        text.color = color
        text.alpha = 50
        canvas.drawText(s, cx + 2f, cy + 2f, text)
        text.alpha = 90
        canvas.drawText(s, cx - 2f, cy - 2f, text)
        text.alpha = 255
        canvas.drawText(s, cx, cy, text)
        text.color = WHITE
        text.alpha = 60
        canvas.drawText(s, cx, cy - 1f, text)
        text.alpha = 255
    }
}
