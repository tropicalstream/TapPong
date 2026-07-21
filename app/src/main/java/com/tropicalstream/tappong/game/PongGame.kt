package com.tropicalstream.tappong.game

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin
import kotlin.random.Random

/**
 * TapPong — Pong remixed for the X3 Pro. All game logic, no Android deps.
 *
 * The remix over classic Pong:
 *   • ENGLISH — the reflect angle comes from where the ball meets the paddle,
 *     and a MOVING paddle adds spin. Spin curves the ball's flight (Magnus)
 *     and decays, so a cut shot bends around a lazy opponent.
 *   • POWER-UPS drift into mid-court; whoever hit the ball last collects on
 *     ball contact: GROW self, SHRINK the foe, MULTI-ball, TURBO.
 *   • RALLY RAMP — every return nudges ball speed up; long rallies get loud.
 *   • RUBBER-BAND CPU — the AI paddle speeds up when it's losing and eases
 *     off when it's crushing, so games stay close and exciting.
 *
 * Field is the logical 640×480 eye canvas. First to [WIN_SCORE] wins.
 */
class PongGame {

    enum class State { READY, SERVING, PLAYING, POINT, GAMEOVER, PAUSED }
    enum class Difficulty(val label: String) {
        RELAXED("RELAXED"), CLASSIC("CLASSIC"), ACE("ACE")
    }

    class Ball(
        var x: Float, var y: Float,
        var vx: Float, var vy: Float,
        var spin: Float = 0f
    )

    enum class PowerType { GROW, SHRINK, MULTI, TURBO }

    class PowerUp(
        val type: PowerType,
        var x: Float, var y: Float,
        var vx: Float, var vy: Float,
        var life: Float = POWER_LIFE_S
    )

    // ---- court geometry (logical px) ----
    companion object {
        const val W = 640f
        const val H = 480f
        const val COURT_TOP = 34f
        const val COURT_BOTTOM = H - 14f
        const val PADDLE_W = 9f
        const val PADDLE_BASE_H = 66f
        const val PLAYER_X = 30f          // player paddle left edge
        const val AI_X = W - 30f - PADDLE_W
        const val BALL_R = 5.5f

        const val WIN_SCORE = 7
        const val SERVE_DELAY_S = 1.2f
        const val POINT_FREEZE_S = 0.9f

        const val BALL_SPEED_0 = 250f
        const val BALL_SPEED_MAX = 720f
        const val RALLY_RAMP = 1.055f     // speed multiplier per paddle return
        const val MAX_BOUNCE_DEG = 58f    // reflect angle at paddle edge
        const val SPIN_FROM_PADDLE = 0.55f
        const val SPIN_CURVE = 0.55f      // spin → lateral acceleration factor
        const val SPIN_DECAY = 0.55f      // fraction surviving per second

        const val POWER_LIFE_S = 10f
        const val POWER_EFFECT_S = 8f
        const val POWER_R = 13f
        const val TURBO_MULT = 1.38f
        const val TURBO_S = 6f
    }

    // ---- public state the renderer reads ----
    var state = State.READY; private set
    var stateBefore = State.READY; private set
    var playerY = (COURT_TOP + COURT_BOTTOM) / 2f; private set
    var aiY = (COURT_TOP + COURT_BOTTOM) / 2f; private set
    var playerH = PADDLE_BASE_H; private set
    var aiH = PADDLE_BASE_H; private set
    val balls = ArrayList<Ball>()
    val powerUps = ArrayList<PowerUp>()
    var playerScore = 0; private set
    var aiScore = 0; private set
    var rally = 0; private set
    var bestRally = 0; private set
    var playerWon = false; private set
    var serveCountdown = 0f; private set
    var shake = 0f; private set          // renderer decays-reads this
    /** Active effect timers, 0 = inactive (renderer shows pills). */
    var growLeft = 0f; private set
    var shrinkFoeLeft = 0f; private set
    var turboLeft = 0f; private set
    var lastPointToPlayer = false; private set
    var difficulty = Difficulty.CLASSIC
    var powerUpsEnabled = true

    // ---- events (host wires sounds/particles) ----
    var onPaddleHit: ((x: Float, y: Float, byPlayer: Boolean, rally: Int) -> Unit)? = null
    var onWallHit: ((x: Float, y: Float) -> Unit)? = null
    var onScore: ((playerScored: Boolean) -> Unit)? = null
    var onServe: (() -> Unit)? = null
    var onPowerSpawn: ((p: PowerUp) -> Unit)? = null
    var onPowerGet: ((p: PowerUp, byPlayer: Boolean) -> Unit)? = null
    var onGameOver: ((playerWon: Boolean) -> Unit)? = null
    var onCountTick: (() -> Unit)? = null

    private val rng = Random(System.nanoTime())
    private var pointTimer = 0f
    private var powerSpawnIn = 6f
    private var lastHitByPlayer = true
    private var serveTowardPlayer = false
    private var lastCountSecond = -1
    private var playerVel = 0f           // smoothed paddle velocity for english
    private var playerYPrev = playerY
    private var aiYPrev = aiY
    private var aiVel = 0f
    private var aiTargetY = (COURT_TOP + COURT_BOTTOM) / 2f
    private var aiRetargetIn = 0f

    // ------------------------------------------------------------ input

    /** Continuous paddle drive from the trackpad (already gain-scaled px). */
    fun movePlayerBy(dy: Float) {
        if (state == State.READY || state == State.GAMEOVER) return
        playerY = (playerY + dy).coerceIn(
            COURT_TOP + playerH / 2f, COURT_BOTTOM - playerH / 2f
        )
    }

    /** Tap: start match / serve early / restart after game over / unpause. */
    fun onTap() {
        when (state) {
            State.READY, State.GAMEOVER -> startMatch()
            State.PAUSED -> { state = stateBefore }
            State.SERVING -> { serveCountdown = 0f }
            else -> {}
        }
    }

    fun togglePause() {
        if (state == State.PLAYING || state == State.SERVING) {
            stateBefore = state
            state = State.PAUSED
        } else if (state == State.PAUSED) {
            state = stateBefore
        }
    }

    // ------------------------------------------------------------ lifecycle

    fun startMatch() {
        playerScore = 0; aiScore = 0
        rally = 0; bestRally = 0
        playerH = PADDLE_BASE_H; aiH = PADDLE_BASE_H
        growLeft = 0f; shrinkFoeLeft = 0f; turboLeft = 0f
        aiGrowLeft = 0f; playerShrinkLeft = 0f
        balls.clear(); powerUps.clear()
        powerSpawnIn = 6f
        serveTowardPlayer = rng.nextBoolean()
        beginServe()
    }

    private fun beginServe() {
        state = State.SERVING
        serveCountdown = SERVE_DELAY_S
        lastCountSecond = -1
        balls.clear()
        rally = 0
    }

    private fun serve() {
        val dir = if (serveTowardPlayer) -1f else 1f
        val ang = (rng.nextFloat() * 40f - 20f) * (Math.PI / 180f).toFloat()
        balls.add(
            Ball(
                x = W / 2f, y = (COURT_TOP + COURT_BOTTOM) / 2f,
                vx = cos(ang) * initialBallSpeed() * dir,
                vy = sin(ang) * initialBallSpeed()
            )
        )
        state = State.PLAYING
        onServe?.invoke()
    }

    // ------------------------------------------------------------ update

    fun update(dt: Float) {
        if (dt <= 0f) return
        shake = (shake - dt * 3.2f).coerceAtLeast(0f)
        when (state) {
            State.SERVING -> {
                // paddle velocities keep tracking during the countdown
                trackPaddleVelocities(dt)
                updateAi(dt)
                serveCountdown -= dt
                val sec = serveCountdown.toInt()
                if (sec != lastCountSecond && serveCountdown > 0f) {
                    lastCountSecond = sec
                    onCountTick?.invoke()
                }
                if (serveCountdown <= 0f) serve()
            }
            State.PLAYING -> {
                trackPaddleVelocities(dt)
                updateAi(dt)
                updateEffects(dt)
                updatePowerUps(dt)
                updateBalls(dt)
            }
            State.POINT -> {
                pointTimer -= dt
                if (pointTimer <= 0f) {
                    if (playerScore >= WIN_SCORE || aiScore >= WIN_SCORE) {
                        playerWon = playerScore >= WIN_SCORE
                        state = State.GAMEOVER
                        onGameOver?.invoke(playerWon)
                    } else {
                        beginServe()
                    }
                }
            }
            else -> {}
        }
    }

    private fun trackPaddleVelocities(dt: Float) {
        val pv = (playerY - playerYPrev) / dt
        playerVel = playerVel * 0.7f + pv * 0.3f
        playerYPrev = playerY
        val av = (aiY - aiYPrev) / dt
        aiVel = aiVel * 0.7f + av * 0.3f
        aiYPrev = aiY
    }

    private fun updateEffects(dt: Float) {
        if (growLeft > 0f) {
            growLeft -= dt
            if (growLeft <= 0f) { growLeft = 0f }
        }
        if (shrinkFoeLeft > 0f) {
            shrinkFoeLeft -= dt
            if (shrinkFoeLeft <= 0f) { shrinkFoeLeft = 0f }
        }
        if (turboLeft > 0f) {
            turboLeft -= dt
            if (turboLeft <= 0f) turboLeft = 0f
        }
        playerH = if (growLeft > 0f) PADDLE_BASE_H * 1.6f else PADDLE_BASE_H
        aiH = if (shrinkFoeLeft > 0f) PADDLE_BASE_H * 0.62f else PADDLE_BASE_H
        playerY = playerY.coerceIn(COURT_TOP + playerH / 2f, COURT_BOTTOM - playerH / 2f)
        aiY = aiY.coerceIn(COURT_TOP + aiH / 2f, COURT_BOTTOM - aiH / 2f)
    }

    // ------------------------------------------------------------ AI

    /**
     * Rubber-band CPU: reads the ball only when it's inbound, aims with an
     * error that shrinks as it falls behind on score, and its top speed
     * scales the same way — so it's beatable but never a pushover.
     */
    private fun updateAi(dt: Float) {
        val scoreEdge = (playerScore - aiScore).coerceIn(-5, 5)
        val tuning = when (difficulty) {
            Difficulty.RELAXED -> AiTuning(205f, 24f, 40f, 5f, 0.19f)
            Difficulty.CLASSIC -> AiTuning(250f, 38f, 14f, 7f, 0.12f)
            Difficulty.ACE -> AiTuning(310f, 44f, 7f, 3f, 0.075f)
        }
        val maxSpeed = tuning.baseSpeed + scoreEdge * tuning.comebackSpeed
        val inbound = balls.filter { it.vx > 0f }
            .minByOrNull { AI_X - it.x }

        aiRetargetIn -= dt
        if (aiRetargetIn <= 0f) {
            aiRetargetIn = tuning.reactionSeconds
            aiTargetY = if (inbound != null) {
                val t = ((AI_X - inbound.x) / inbound.vx).coerceAtLeast(0f)
                var predicted = inbound.y + inbound.vy * t
                // fold the prediction into the court (wall bounces)
                val span = COURT_BOTTOM - COURT_TOP - BALL_R * 2
                var folded = (predicted - COURT_TOP - BALL_R) % (span * 2)
                if (folded < 0) folded += span * 2
                predicted = COURT_TOP + BALL_R + if (folded > span) span * 2 - folded else folded
                // aim error grows when the AI is comfortably ahead
                val err = (tuning.aimError - scoreEdge * tuning.comebackAccuracy)
                    .coerceIn(if (difficulty == Difficulty.ACE) 2f else 4f, 70f)
                predicted + (rng.nextFloat() * 2f - 1f) * err
            } else {
                (COURT_TOP + COURT_BOTTOM) / 2f
            }
        }
        val delta = aiTargetY - aiY
        val step = maxSpeed * dt
        aiY += delta.coerceIn(-step, step)
        aiY = aiY.coerceIn(COURT_TOP + aiH / 2f, COURT_BOTTOM - aiH / 2f)
    }

    // ------------------------------------------------------------ power-ups

    private fun updatePowerUps(dt: Float) {
        if (!powerUpsEnabled) {
            powerUps.clear()
            return
        }
        powerSpawnIn -= dt
        if (powerSpawnIn <= 0f && powerUps.size < 2) {
            powerSpawnIn = 7f + rng.nextFloat() * 5f
            val type = PowerType.entries[rng.nextInt(PowerType.entries.size)]
            val p = PowerUp(
                type = type,
                x = W * (0.32f + rng.nextFloat() * 0.36f),
                y = COURT_TOP + 40f + rng.nextFloat() * (COURT_BOTTOM - COURT_TOP - 80f),
                vx = (rng.nextFloat() * 2f - 1f) * 18f,
                vy = (rng.nextFloat() * 2f - 1f) * 14f
            )
            powerUps.add(p)
            onPowerSpawn?.invoke(p)
        }
        val it = powerUps.iterator()
        while (it.hasNext()) {
            val p = it.next()
            p.life -= dt
            if (p.life <= 0f) { it.remove(); continue }
            p.x += p.vx * dt
            p.y += p.vy * dt
            if (p.y < COURT_TOP + POWER_R || p.y > COURT_BOTTOM - POWER_R) p.vy = -p.vy
            if (p.x < W * 0.28f || p.x > W * 0.72f) p.vx = -p.vx
            // ball contact collects for the LAST HITTER
            for (b in balls) {
                val dx = b.x - p.x
                val dy = b.y - p.y
                if (dx * dx + dy * dy <= (POWER_R + BALL_R) * (POWER_R + BALL_R)) {
                    applyPower(p)
                    onPowerGet?.invoke(p, lastHitByPlayer)
                    it.remove()
                    break
                }
            }
        }
    }

    private fun applyPower(p: PowerUp) {
        when (p.type) {
            // GROW enlarges the collector's own paddle; SHRINK hits the foe.
            PowerType.GROW ->
                if (lastHitByPlayer) growLeft = POWER_EFFECT_S
                else aiGrowLeft = POWER_EFFECT_S
            PowerType.SHRINK ->
                if (lastHitByPlayer) shrinkFoeLeft = POWER_EFFECT_S
                else playerShrinkLeft = POWER_EFFECT_S
            PowerType.MULTI -> {
                val src = balls.firstOrNull() ?: return
                repeat(2) { i ->
                    val ang = (if (i == 0) 18f else -18f) * (Math.PI / 180f).toFloat()
                    val cs = cos(ang); val sn = sin(ang)
                    balls.add(
                        Ball(
                            src.x, src.y,
                            src.vx * cs - src.vy * sn,
                            src.vx * sn + src.vy * cs,
                            src.spin
                        )
                    )
                }
            }
            PowerType.TURBO -> turboLeft = TURBO_S
        }
    }

    // AI-side effect timers (kept separate so the pills can label them)
    var aiGrowLeft = 0f; private set
    var playerShrinkLeft = 0f; private set

    // ------------------------------------------------------------ balls

    private fun updateBalls(dt: Float) {
        // AI/self grow-shrink timers tick here so updateEffects stays player-centric
        if (aiGrowLeft > 0f) { aiGrowLeft -= dt; if (aiGrowLeft < 0f) aiGrowLeft = 0f }
        if (playerShrinkLeft > 0f) { playerShrinkLeft -= dt; if (playerShrinkLeft < 0f) playerShrinkLeft = 0f }
        if (aiGrowLeft > 0f) aiH = PADDLE_BASE_H * 1.6f
        if (playerShrinkLeft > 0f) playerH = PADDLE_BASE_H * 0.62f

        val turbo = if (turboLeft > 0f) TURBO_MULT else 1f
        val it = balls.iterator()
        var scored = false
        var scoredByPlayer = false
        while (it.hasNext()) {
            val b = it.next()
            // substep so a fast ball can't tunnel a paddle
            val speed = abs(b.vx) * turbo
            val steps = 1 + (speed * dt / (PADDLE_W * 0.8f)).toInt().coerceAtMost(8)
            val sdt = dt / steps
            var removed = false
            repeat(steps) {
                if (removed) return@repeat
                // spin curves the flight, then decays
                b.vy += b.spin * SPIN_CURVE * abs(b.vx) * sdt / 100f
                b.spin *= Math.pow(SPIN_DECAY.toDouble(), sdt.toDouble()).toFloat()
                b.x += b.vx * turbo * sdt
                b.y += b.vy * turbo * sdt
                // walls
                if (b.y - BALL_R < COURT_TOP) {
                    b.y = COURT_TOP + BALL_R; b.vy = abs(b.vy)
                    onWallHit?.invoke(b.x, b.y)
                } else if (b.y + BALL_R > COURT_BOTTOM) {
                    b.y = COURT_BOTTOM - BALL_R; b.vy = -abs(b.vy)
                    onWallHit?.invoke(b.x, b.y)
                }
                // player paddle
                if (b.vx < 0f &&
                    b.x - BALL_R <= PLAYER_X + PADDLE_W && b.x + BALL_R >= PLAYER_X &&
                    b.y >= playerY - playerH / 2f - BALL_R && b.y <= playerY + playerH / 2f + BALL_R
                ) {
                    reflect(b, byPlayer = true)
                } else if (b.vx > 0f &&
                    b.x + BALL_R >= AI_X && b.x - BALL_R <= AI_X + PADDLE_W &&
                    b.y >= aiY - aiH / 2f - BALL_R && b.y <= aiY + aiH / 2f + BALL_R
                ) {
                    reflect(b, byPlayer = false)
                }
                // goals
                if (b.x < -BALL_R * 2) {
                    removed = true; scored = true; scoredByPlayer = false
                } else if (b.x > W + BALL_R * 2) {
                    removed = true; scored = true; scoredByPlayer = true
                }
            }
            if (removed) it.remove()
        }
        if (scored && balls.isEmpty()) {
            // point resolves only when the LAST ball leaves (multi-ball rule)
            if (scoredByPlayer) playerScore++ else aiScore++
            lastPointToPlayer = scoredByPlayer
            serveTowardPlayer = !scoredByPlayer   // loser receives the serve
            shake = 1f
            state = State.POINT
            pointTimer = POINT_FREEZE_S
            onScore?.invoke(scoredByPlayer)
        } else if (scored && balls.isNotEmpty()) {
            // a stray multi-ball left play — small shake, rally continues
            shake = maxOf(shake, 0.4f)
        }
    }

    private fun reflect(b: Ball, byPlayer: Boolean) {
        val paddleY = if (byPlayer) playerY else aiY
        val paddleH = if (byPlayer) playerH else aiH
        val paddleVel = if (byPlayer) playerVel else aiVel
        val offset = ((b.y - paddleY) / (paddleH / 2f)).coerceIn(-1f, 1f)
        val speed = min(
            maxBallSpeed(),
            (kotlin.math.hypot(b.vx, b.vy)) * rallyRamp()
        )
        val ang = offset * MAX_BOUNCE_DEG * (Math.PI / 180f).toFloat()
        val dir = if (byPlayer) 1f else -1f
        b.vx = cos(ang) * speed * dir
        b.vy = sin(ang) * speed
        // english: a moving paddle cuts the ball
        b.spin = (b.spin * 0.3f) + paddleVel * SPIN_FROM_PADDLE / 60f
        // eject the ball clear of the paddle face
        b.x = if (byPlayer) PLAYER_X + PADDLE_W + BALL_R + 0.5f else AI_X - BALL_R - 0.5f
        lastHitByPlayer = byPlayer
        rally++
        if (rally > bestRally) bestRally = rally
        onPaddleHit?.invoke(b.x, b.y, byPlayer, rally)
    }

    private fun initialBallSpeed() = when (difficulty) {
        Difficulty.RELAXED -> 225f
        Difficulty.CLASSIC -> BALL_SPEED_0
        Difficulty.ACE -> 275f
    }

    private fun maxBallSpeed() = when (difficulty) {
        Difficulty.RELAXED -> 620f
        Difficulty.CLASSIC -> BALL_SPEED_MAX
        Difficulty.ACE -> 780f
    }

    private fun rallyRamp() = when (difficulty) {
        Difficulty.RELAXED -> 1.045f
        Difficulty.CLASSIC -> RALLY_RAMP
        Difficulty.ACE -> 1.06f
    }

    private data class AiTuning(
        val baseSpeed: Float,
        val comebackSpeed: Float,
        val aimError: Float,
        val comebackAccuracy: Float,
        val reactionSeconds: Float
    )
}
