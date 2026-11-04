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

    data class StylePreset(val name: String, val tone: Float, val color: Float, val undertone: Float)

    private val presets = listOf(
        StylePreset("Standard", 0.0f, 0.0f, 0.05f),
        StylePreset("Amber", -0.20f, 0.28f, 0.35f),
        StylePreset("Gold", 0.12f, 0.32f, 0.45f),
        StylePreset("Rose Gold", 0.18f, 0.15f, -0.22f),
        StylePreset("Cool Rose", -0.10f, -0.25f, -0.35f),
        StylePreset("Dramatic", -0.65f, -0.15f, 0.10f)
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED ||
            checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO), 101)
        } else {
            setupCameraUi()
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            setupCameraUi()
        } else {
            Toast.makeText(this, "Camera & Audio permissions are required.", Toast.LENGTH_LONG).show()
        }
    }

    private fun setupCameraUi() {
        val root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }

        // 1. OpenGL ES 3.0 Viewfinder
        val glView = GLSurfaceView(this).apply {
            setEGLContextClientVersion(3)
            setEGLConfigChooser(CameraShaderRenderer.RecordableConfigChooser())
        }
        val camRenderer = CameraShaderRenderer(this, glView)
        glView.setRenderer(camRenderer)
        glView.renderMode = GLSurfaceView.RENDERMODE_WHEN_DIRTY

        glSurfaceView = glView
        renderer = camRenderer
        root.addView(glView, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT
        ))

        // 2. Top Status & FPS Toggle Bar
        val topBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(40, 60, 40, 20)
        }

        readoutLabel = TextView(this).apply {
            text = "TONE  0   ·   COLOR  0   ·   EV -0.3"
            setTextColor(Color.parseColor("#FFD60A")) // Apple Camera Yellow
            textSize = 13f
            typeface = Typeface.MONOSPACE
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }

        val fpsButton = Button(this).apply {
            text = "60 FPS"
            setTextColor(Color.BLACK)
            setBackgroundColor(Color.parseColor("#FFD60A"))
            setOnClickListener {
                val next60 = !(renderer?.use60Fps ?: true)
                renderer?.updateCameraParameters(renderer?.exposureCompSteps ?: -2, next60)
                text = if (next60) "60 FPS" else "30 FPS"
            }
        }

        topBar.addView(readoutLabel)
        topBar.addView(fpsButton)
        root.addView(topBar, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.WRAP_CONTENT,
            Gravity.TOP
        ))

        // 3. Bottom Control Deck (iPhone 16 2D Pad + Undertone/EV Sliders + Presets + Shutter)
        val bottomDeck = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setBackgroundColor(Color.parseColor("#AA000000"))
            setPadding(32, 24, 32, 48)
        }

        // Interactive 2D Tone & Color Pad
        stylePad = StylePad2DView(this) { normColor, normTone ->
            renderer?.styleColor = normColor
            renderer?.styleTone = normTone
            updateReadout()
        }
        val padParams = LinearLayout.LayoutParams(380, 380).apply { bottomMargin = 20 }
        bottomDeck.addView(stylePad, padParams)

        // Skin Undertone & EV Exposure Sliders
        bottomDeck.addView(createSliderRow("SKIN UNDERTONE (Rose ↔ Gold)", 60) { progress ->
            renderer?.styleUndertone = (progress - 50) / 100f
        })
        bottomDeck.addView(createSliderRow("HIGHLIGHT EV (-2.0 ↔ +1.0)", 35) { progress ->
            val evSteps = ((progress - 50) / 10f).roundToInt()
            renderer?.updateCameraParameters(evSteps, renderer?.use60Fps ?: true)
            updateReadout()
        })

        // Preset Pills ScrollView
        val presetScroll = HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false }
        val presetRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        presets.forEach { preset ->
            val btn = Button(this).apply {
                text = preset.name
                textSize = 12f
                setTextColor(Color.WHITE)
                setBackgroundColor(Color.parseColor("#33FFFFFF"))
                setOnClickListener {
                    renderer?.styleTone = preset.tone
                    renderer?.styleColor = preset.color
                    renderer?.styleUndertone = preset.undertone
                    stylePad.setNormalizedPosition(preset.color, preset.tone)
                    updateReadout()
                }
            }
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(8, 12, 8, 20) }
            presetRow.addView(btn, lp)
        }
        presetScroll.addView(presetRow)
        bottomDeck.addView(presetScroll)

        // Shutter Row (Photo + Video Record)
        val shutterRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }

        val photoBtn = Button(this).apply {
            text = "SNAP PHOTO"
            setTextColor(Color.BLACK)
            setBackgroundColor(Color.WHITE)
            setPadding(48, 28, 48, 28)
            setOnClickListener { renderer?.triggerPhotoCapture() }
        }

        val videoBtn = Button(this).apply {
            text = "REC VIDEO"
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.parseColor("#FF3B30"))
            setPadding(48, 28, 48, 28)
            setOnClickListener {
                renderer?.toggleVideoRecording { recording ->
                    text = if (recording) "STOP REC" else "REC VIDEO"
                    setBackgroundColor(if (recording) Color.parseColor("#FF9500") else Color.parseColor("#FF3B30"))
                }
            }
        }

        val btnParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { setMargins(24, 8, 24, 8) }

        shutterRow.addView(photoBtn, btnParams)
        shutterRow.addView(videoBtn, btnParams)
        bottomDeck.addView(shutterRow)

        root.addView(bottomDeck, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.WRAP_CONTENT,
            Gravity.BOTTOM
        ))

        setContentView(root)
    }

    private fun createSliderRow(label: String, initialProgress: Int, onChange: (Int) -> Unit): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val tv = TextView(context).apply {
                text = label
                setTextColor(Color.LTGRAY)
                textSize = 11f
            }
            val seek = SeekBar(context).apply {
                max = 100
                progress = initialProgress
                setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) { onChange(p) }
                    override fun onStartTrackingTouch(s: SeekBar?) {}
                    override fun onStopTrackingTouch(s: SeekBar?) {}
                })
            }
            addView(tv)
            addView(seek)
        }
    }

    private fun updateReadout() {
        val t = ((renderer?.styleTone ?: 0f) * 100).roundToInt()
        val c = ((renderer?.styleColor ?: 0f) * 100).roundToInt()
        val ev = (renderer?.exposureCompSteps ?: -2) * 0.17f
        readoutLabel.text = String.format("TONE %+d   ·   COLOR %+d   ·   EV %+.1f", t, c, ev)
    }

    override fun onDestroy() {
        super.onDestroy()
        renderer?.release()
    }

    // Custom 2D Touch Pad matching the iPhone 16 Photographic Styles grid
    class StylePad2DView(
        context: Context,
        private val onPadChanged: (Float, Float) -> Unit
    ) : View(context) {

        private var normX = 0f
        private var normY = 0f

        private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#88FFFFFF")
            style = Paint.Style.STROKE
            strokeWidth = 3f
        }
        private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#44FFFFFF")
            style = Paint.Style.STROKE
            strokeWidth = 2f
        }
        private val knobPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#FFD60A")
            style = Paint.Style.FILL
        }

        fun setNormalizedPosition(x: Float, y: Float) {
            normX = x.coerceIn(-1f, 1f)
            normY = y.coerceIn(-1f, 1f)
            invalidate()
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val w = width.toFloat()
            val h = height.toFloat()

            canvas.drawRect(4f, 4f, w - 4f, h - 4f, borderPaint)
            canvas.drawLine(w / 2f, 0f, w / 2f, h, gridPaint)
            canvas.drawLine(0f, h / 2f, w, h / 2f, gridPaint)

            val cx = ((normX + 1f) / 2f) * w
            val cy = ((1f - normY) / 2f) * h
            canvas.drawCircle(cx, cy, 18f, knobPaint)
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
