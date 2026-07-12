package com.tropicalstream.tappong.game

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

/** Lightweight neon particle system for eat bursts and death explosions. */
class Particles {

    private class P(
        var x: Float, var y: Float,
        var vx: Float, var vy: Float,
        var life: Float, val maxLife: Float,
        val color: Int, val size: Float
    )

    private val list = ArrayList<P>()
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)

    fun burst(x: Float, y: Float, color: Int, count: Int, speed: Float) {
        repeat(count) {
            val ang = Random.nextFloat() * (2.0 * Math.PI).toFloat()
            val sp = speed * (0.35f + Random.nextFloat())
            val life = 0.4f + Random.nextFloat() * 0.6f
            list.add(P(x, y, cos(ang) * sp, sin(ang) * sp, life, life, color, 3f + Random.nextFloat() * 4f))
        }
        // Cap so a stray flood can't balloon memory.
        while (list.size > 600) list.removeAt(0)
    }

    fun update(dt: Float) {
        val it = list.iterator()
        while (it.hasNext()) {
            val p = it.next()
            p.life -= dt
            if (p.life <= 0f) {
                it.remove(); continue
            }
            p.x += p.vx * dt
            p.y += p.vy * dt
            p.vx *= 0.90f
            p.vy = p.vy * 0.90f + 90f * dt // gentle gravity
        }
    }

    fun draw(canvas: Canvas) {
        for (p in list) {
            val a = (p.life / p.maxLife).coerceIn(0f, 1f)
            paint.color = p.color
            paint.alpha = (a * 230).toInt()
            canvas.drawCircle(p.x, p.y, p.size * a, paint)
            // tiny white-hot core
            paint.color = Color.WHITE
            paint.alpha = (a * 120).toInt()
            canvas.drawCircle(p.x, p.y, p.size * a * 0.4f, paint)
        }
    }

    fun clear() = list.clear()
}
