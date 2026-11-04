package com.lim.iphonecam

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.opengl.GLSurfaceView
import android.os.Bundle
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.*
import kotlin.math.roundToInt

class MainActivity : Activity() {

    private var glSurfaceView: GLSurfaceView? = null
    private var renderer: CameraShaderRenderer? = null
    private lateinit var readoutLabel: TextView
    private lateinit var stylePad: StylePad2DView
    private lateinit var proControlsLayout: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.CAMERA), 101)
        } else {
            setupCameraUi()
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) setupCameraUi()
    }

    private fun setupCameraUi() {
        val root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }

        val glView = GLSurfaceView(this).apply {
            setEGLContextClientVersion(3)
        }
        val camRenderer = CameraShaderRenderer(this, glView)
        glView.setRenderer(camRenderer)
        glView.renderMode = GLSurfaceView.RENDERMODE_WHEN_DIRTY

        glSurfaceView = glView
        renderer = camRenderer
        root.addView(glView, FrameLayout.LayoutParams(-1, -1))

        val topBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(40, 60, 40, 20)
        }
        readoutLabel = TextView(this).apply {
            text = "TONE  0   ·   COLOR  0   ·   MODE: PHOTO"
            setTextColor(Color.parseColor("#FFD60A"))
            textSize = 13f
            typeface = Typeface.MONOSPACE
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        topBar.addView(readoutLabel)
        root.addView(topBar, FrameLayout.LayoutParams(-1, -2, Gravity.TOP))

        val bottomDeck = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setBackgroundColor(Color.parseColor("#BB000000"))
            setPadding(32, 20, 32, 48)
        }

        val modeBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, 16)
        }
        val modes = arrayOf("PHOTO", "PRO")
        modes.forEach { mode ->
            val btn = Button(this).apply {
                text = mode
                textSize = 12f
                setTextColor(Color.WHITE)
                setBackgroundColor(Color.parseColor("#44FFFFFF"))
                setOnClickListener {
                    renderer?.isProMode = (mode == "PRO")
                    proControlsLayout.visibility = if (mode == "PRO") View.VISIBLE else View.GONE
                    renderer?.updateCameraParameters(renderer?.exposureCompSteps ?: -2, renderer?.use60Fps ?: true)
                    readoutLabel.text = "MODE: $mode"
                }
            }
            val lp = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { setMargins(8, 0, 8, 0) }
            modeBar.addView(btn, lp)
        }
        bottomDeck.addView(modeBar)

        stylePad = StylePad2DView(this) { normColor, normTone ->
            renderer?.styleColor = normColor
            renderer?.styleTone = normTone
        }
        bottomDeck.addView(stylePad, LinearLayout.LayoutParams(340, 340).apply { bottomMargin = 16 })

        proControlsLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
        }
        proControlsLayout.addView(createSliderRow("ISO (100 - 3200)", 20) { p ->
            renderer?.manualIso = 100 + (p * 31)
            renderer?.updateCameraParameters(renderer?.exposureCompSteps ?: -2, renderer?.use60Fps ?: true)
        })
        proControlsLayout.addView(createSliderRow("SHUTTER SPEED", 50) { p ->
            renderer?.manualShutterNs = (1_000_000L + (p * 200_000L))
            renderer?.updateCameraParameters(renderer?.exposureCompSteps ?: -2, renderer?.use60Fps ?: true)
        })
        bottomDeck.addView(proControlsLayout)

        val shutterRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(0, 12, 0, 0)
        }
        val snapBtn = Button(this).apply {
            text = "SNAP SHUTTER"
            setTextColor(Color.BLACK)
            setBackgroundColor(Color.WHITE)
            setPadding(50, 28, 50, 28)
            setOnClickListener { renderer?.triggerPhotoCapture() }
        }
        shutterRow.addView(snapBtn)
        bottomDeck.addView(shutterRow)

        root.addView(bottomDeck, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM))
        setContentView(root)
    }

    private fun createSliderRow(label: String, initialProgress: Int, onChange: (Int) -> Unit): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 4, 0, 4)
            val tv = TextView(context).apply { text = label; setTextColor(Color.LTGRAY); textSize = 10f }
            val seek = SeekBar(context).apply {
                max = 100; progress = initialProgress
                setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) { onChange(p) }
                    override fun onStartTrackingTouch(s: SeekBar?) {}
                    override fun onStopTrackingTouch(s: SeekBar?) {}
                })
            }
            addView(tv); addView(seek)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        renderer?.release()
    }

    class StylePad2DView(context: Context, private val onPadChanged: (Float, Float) -> Unit) : View(context) {
        private var normX = 0f; private var normY = 0f
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#FFD60A"); style = Paint.Style.FILL }
        private val border = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#66FFFFFF"); style = Paint.Style.STROKE; strokeWidth = 2f }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val w = width.toFloat(); val h = height.toFloat()
            canvas.drawRect(2f, 2f, w - 2f, h - 2f, border)
            canvas.drawCircle(((normX + 1f) / 2f) * w, ((1f - normY) / 2f) * h, 16f, paint)
        }

        override fun onTouchEvent(event: MotionEvent): Boolean {
            if (event.action == MotionEvent.ACTION_DOWN || event.action == MotionEvent.ACTION_MOVE) {
                normX = ((event.x / width) * 2f - 1f).coerceIn(-1f, 1f)
                normY = (1f - (event.y / height) * 2f).coerceIn(-1f, 1f)
                onPadChanged(normX, normY)
                invalidate()
                return true
            }
            return super.onTouchEvent(event)
        }
    }
}
