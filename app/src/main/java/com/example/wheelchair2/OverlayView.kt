package com.example.headposecontroller // Sesuaikan dengan package name-mu

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View

class OverlayView(context: Context, attrs: AttributeSet) : View(context, attrs) {
    private var yaw: Float = 0f
    private var pitch: Float = 0f
    private var command: String = "DIAM"

    private val textPaint = Paint().apply {
        color = Color.GREEN
        textSize = 60f
    }
    private val commandPaint = Paint().apply {
        color = Color.RED
        textSize = 80f
        textAlign = Paint.Align.CENTER
    }

    fun setResults(yaw: Float, pitch: Float, command: String) {
        this.yaw = yaw
        this.pitch = pitch
        this.command = command
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.drawText(String.format("Yaw: %.2f", yaw), 50f, 100f, textPaint)
        canvas.drawText(String.format("Pitch: %.2f", pitch), 50f, 200f, textPaint)
        canvas.drawText(command, width / 2f, height - 150f, commandPaint)
    }
}