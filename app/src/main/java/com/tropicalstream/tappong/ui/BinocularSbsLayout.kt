package com.tropicalstream.tappong.ui

import android.content.Context
import android.graphics.Canvas
import android.util.AttributeSet
import android.view.View
import android.widget.FrameLayout

/**
 * Binocular side-by-side layout for the RayNeo X3 Pro — the proven dual-draw path
 * (TapInsight / Everyday / Moonlight / WanderQuest).
 *
 * Holds exactly ONE child (the logical viewport), measures it to half the physical
 * width (1280×480 → 640×480), and draws it twice: once at x=0 for the left eye and
 * once translated by the logical width for the right eye. The wearer fuses them.
 *
 * Geometry is read from the LIVE view width each frame (idempotent). SBS turns on
 * only for a very wide-and-short surface (the glasses); on a phone it degrades to a
 * normal single-image FrameLayout so it's testable.
 */
class BinocularSbsLayout @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : FrameLayout(context, attrs) {

    private fun sbsEnabled(w: Int, h: Int) = h > 0 && w >= h * 2

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
        if (!sbsEnabled(measuredWidth, measuredHeight)) return
        val child = getChildAt(0) ?: return
        child.measure(
            MeasureSpec.makeMeasureSpec(measuredWidth / 2, MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(measuredHeight, MeasureSpec.EXACTLY)
        )
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        if (!sbsEnabled(width, height)) {
            super.onLayout(changed, l, t, r, b)
            return
        }
        val child = getChildAt(0) ?: return
        child.layout(0, 0, child.measuredWidth, child.measuredHeight)
    }

    override fun dispatchDraw(canvas: Canvas) {
        if (!sbsEnabled(width, height)) {
            super.dispatchDraw(canvas)
            return
        }
        val child = getChildAt(0) ?: return
        val logicalWidth = width / 2
        val drawTime = drawingTime
        canvas.save()
        canvas.clipRect(0, 0, logicalWidth, height)
        drawChild(canvas, child, drawTime)
        canvas.restore()
        canvas.save()
        canvas.translate(logicalWidth.toFloat(), 0f)
        canvas.clipRect(0, 0, logicalWidth, height)
        drawChild(canvas, child, drawTime)
        canvas.restore()
    }

    override fun onDescendantInvalidated(child: View, target: View) {
        super.onDescendantInvalidated(child, target)
        if (sbsEnabled(width, height)) invalidate()
    }
}
