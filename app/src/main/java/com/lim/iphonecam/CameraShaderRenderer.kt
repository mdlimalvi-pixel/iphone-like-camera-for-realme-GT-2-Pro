package com.lim.iphonecam

import android.annotation.SuppressLint
import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import android.graphics.SurfaceTexture
import android.hardware.camera2.*
import android.media.MediaRecorder
import android.opengl.*
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.HandlerThread
import android.provider.MediaStore
import android.util.Range
import android.util.Size
import android.view.Surface
import android.widget.Toast
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import javax.microedition.khronos.egl.EGL10
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.egl.EGLDisplay
import javax.microedition.khronos.opengles.GL10

class CameraShaderRenderer(
    private val context: Context,
    private val glSurfaceView: GLSurfaceView
) : GLSurfaceView.Renderer, SurfaceTexture.OnFrameAvailableListener {

    // iPhone 16 Photographic Styles Uniforms
    @Volatile var styleTone: Float = 0.0f       // -1.0 (Dramatic) to +1.0 (Bright)
    @Volatile var styleColor: Float = 0.0f      // -1.0 (Muted/Cool) to +1.0 (Vibrant/Warm)
    @Volatile var styleUndertone: Float = 0.1f  // -0.5 (Cool Rose) to +0.5 (Amber/Gold)

    // Camera2 Hardware Controls (Realme GT 2 Pro IMX766)
    @Volatile var exposureCompSteps: Int = -2   // Slight highlight protection by default
    @Volatile var use60Fps: Boolean = true

    private var programId = 0
    private var oesTextureId = 0
    private var surfaceTexture: SurfaceTexture? = null
    private val stMatrix = FloatArray(16)

    private var surfaceWidth = 1080
    private var surfaceHeight = 1920

    // Photo & Video Capture State
    @Volatile private var capturePhotoRequested = false
    @Volatile var isRecordingVideo = false
        private set

    private var mediaRecorder: MediaRecorder? = null
    private var currentVideoFile: File? = null
    private var encoderEglSurface: android.opengl.EGLSurface = EGL14.EGL_NO_SURFACE
    private var eglConfigUsed: android.opengl.EGLConfig? = null
    private val videoWidth = 1080
    private val videoHeight = 1920

    // Camera2 State
    private var cameraDevice: CameraDevice? = null
    private var captureSession: CameraCaptureSession? = null
    private var previewRequestBuilder: CaptureRequest.Builder? = null
    private var cameraThread: HandlerThread? = null
    private var cameraHandler: Handler? = null

    private val vertexData = floatArrayOf(
        // X, Y,     U, V
        -1f, -1f,   0f, 0f,
         1f, -1f,   1f, 0f,
        -1f,  1f,   0f, 1f,
         1f,  1f,   1f, 1f
    )
    private val vertexBuffer: FloatBuffer = ByteBuffer
        .allocateDirect(vertexData.size * 4)
        .order(ByteOrder.nativeOrder())
        .asFloatBuffer()
        .apply { put(vertexData); position(0) }

    private val vertexShaderCode = """
        #version 300 es
        layout(location = 0) in vec4 aPosition;
        layout(location = 1) in vec4 aTexCoord;
        uniform mat4 uSTMatrix;
        out vec2 vTexCoord;
        void main() {
            gl_Position = aPosition;
            vTexCoord = (uSTMatrix * aTexCoord).xy;
        }
    """.trimIndent()

    // Apple iPhone 16 Photographic Styles Engine (Tone + Color + Skin Undertone Isolation)
    private val fragmentShaderCode = """
        #version 300 es
        #extension GL_OES_EGL_image_external_essl3 : require
        precision mediump float;

        in vec2 vTexCoord;
        out vec4 fragColor;

        uniform samplerExternalOES uCameraTexture;
        uniform float uTone;
        uniform float uColor;
        uniform float uUndertone;

        vec3 rgb2hsv(vec3 c) {
            vec4 K = vec4(0.0, -1.0 / 3.0, 2.0 / 3.0, -1.0);
            vec4 p = mix(vec4(c.bg, K.wz), vec4(c.gb, K.xy), step(c.b, c.g));
            vec4 q = mix(vec4(p.xyw, c.r), vec4(c.r, p.yzx), step(p.x, c.r));
            float d = q.x - min(q.w, q.y);
            float e = 1.0e-10;
            return vec3(abs(q.z + (q.w - q.y) / (6.0 * d + e)), d / (q.x + e), q.x);
        }

        // Gaussian hue/saturation mask isolating human skin tones (~14 to 44 deg)
        float getSkinMask(vec3 rgb) {
            vec3 hsv = rgb2hsv(rgb);
            float hue = hsv.x * 360.0;
            float hueDist = abs(hue - 26.0);
            float hueMask = smoothstep(22.0, 6.0, hueDist);
            float satMask = smoothstep(0.10, 0.24, hsv.y) * smoothstep(0.85, 0.55, hsv.y);
            float valMask = smoothstep(0.12, 0.28, hsv.z);
            return clamp(hueMask * satMask * valMask, 0.0, 1.0);
        }

        void main() {
            vec3 col = texture(uCameraTexture, vTexCoord).rgb;

            // 1. Neutralize Realme GT 2 Pro's slightly neon green/cyan bias toward Apple Display P3 warmth
            col.r *= 1.02;
            col.g *= 0.985;
            col.b *= 0.99;

            float skinMask = getSkinMask(col);
            float luma = dot(col, vec3(0.2126, 0.7152, 0.0722));

            // 2. TONE AXIS: Parametric Shadow/Midtone Curve (protects highlights from blowing out)
            float shadowWeight = (1.0 - luma) * (1.0 - luma);
            float toneGamma = pow(2.0, -uTone * 0.48 * shadowWeight);
            vec3 tonedCol = pow(clamp(col, 0.0001, 1.0), vec3(toneGamma));

            // Dampen aggressive shadow crushing on skin by 50% for natural facial roll-off
            col = mix(tonedCol, col, skinMask * 0.50);

            // 3. COLOR AXIS: Scene Vibrance & Warmth vs. Protected Skin Undertones
            float newLuma = dot(col, vec3(0.2126, 0.7152, 0.0722));
            vec3 chroma = col - vec3(newLuma);

            float envSatScale = 1.0 + (uColor * 0.45);
            vec3 envCol = vec3(newLuma) + chroma * envSatScale;
            envCol.r += uColor * 0.028;
            envCol.b -= uColor * 0.028;

            vec3 skinCol = vec3(newLuma) + chroma * (1.0 + uUndertone * 0.16);
            skinCol.r += uUndertone * 0.026;
            skinCol.g += uUndertone * 0.010;

            vec3 finalCol = mix(envCol, skinCol, skinMask);

            // 4. Apple-style filmic highlight shoulder roll-off
            finalCol = (finalCol / (1.0 + finalCol * 0.14)) * 1.14;

            fragColor = vec4(clamp(finalCol, 0.0, 1.0), 1.0);
        }
    """.trimIndent()

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        programId = createProgram(vertexShaderCode, fragmentShaderCode)
        oesTextureId = createOESTexture()

        surfaceTexture = SurfaceTexture(oesTextureId).apply {
            setDefaultBufferSize(1920, 1080)
            setOnFrameAvailableListener(this@CameraShaderRenderer)
        }

        startCameraThread()
        openCamera()
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        surfaceWidth = width
        surfaceHeight = height
        GLES30.glViewport(0, 0, width, height)
    }

    override fun onDrawFrame(gl: GL10?) {
        val st = surfaceTexture ?: return
        synchronized(this) {
            st.updateTexImage()
            st.getTransformMatrix(stMatrix)
        }

        // 1. Render stylized frame to screen
        GLES30.glViewport(0, 0, surfaceWidth, surfaceHeight)
        drawStylizedQuad()

        // 2. Capture full-res stylized JPEG if requested
        if (capturePhotoRequested) {
            capturePhotoRequested = false
            saveCurrentFramebufferAsJpeg(surfaceWidth, surfaceHeight)
        }

        // 3. Render stylized frame into hardware video encoder if recording
        if (isRecordingVideo && encoderEglSurface != EGL14.EGL_NO_SURFACE) {
            val eglDisplay = EGL14.eglGetCurrentDisplay()
            val eglContext = EGL14.eglGetCurrentContext()
            val screenSurface = EGL14.eglGetCurrentSurface(EGL14.EGL_DRAW)

            if (EGL14.eglMakeCurrent(eglDisplay, encoderEglSurface, encoderEglSurface, eglContext)) {
                GLES30.glViewport(0, 0, videoWidth, videoHeight)
                drawStylizedQuad()
                EGLExt.eglPresentationTimeANDROID(eglDisplay, encoderEglSurface, st.timestamp)
                EGL14.eglSwapBuffers(eglDisplay, encoderEglSurface)
                EGL14.eglMakeCurrent(eglDisplay, screenSurface, screenSurface, eglContext)
            }
        }
    }

    private fun drawStylizedQuad() {
        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT)
        GLES30.glUseProgram(programId)

        vertexBuffer.position(0)
        GLES30.glVertexAttribPointer(0, 2, GLES30.GL_FLOAT, false, 16, vertexBuffer)
        GLES30.glEnableVertexAttribArray(0)

        vertexBuffer.position(2)
        GLES30.glVertexAttribPointer(1, 2, GLES30.GL_FLOAT, false, 16, vertexBuffer)
        GLES30.glEnableVertexAttribArray(1)

        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        GLES30.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, oesTextureId)
        GLES30.glUniform1i(GLES30.glGetUniformLocation(programId, "uCameraTexture"), 0)

        GLES30.glUniformMatrix4fv(GLES30.glGetUniformLocation(programId, "uSTMatrix"), 1, false, stMatrix, 0)
        GLES30.glUniform1f(GLES30.glGetUniformLocation(programId, "uTone"), styleTone)
        GLES30.glUniform1f(GLES30.glGetUniformLocation(programId, "uColor"), styleColor)
        GLES30.glUniform1f(GLES30.glGetUniformLocation(programId, "uUndertone"), styleUndertone)

        GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, 4)
    }

    override fun onFrameAvailable(surfaceTexture: SurfaceTexture?) {
        glSurfaceView.requestRender()
    }

    fun triggerPhotoCapture() {
        capturePhotoRequested = true
        glSurfaceView.requestRender()
    }

    fun updateCameraParameters(evSteps: Int, enable60Fps: Boolean) {
        exposureCompSteps = evSteps
        use60Fps = enable60Fps
        applyCamera2Parameters()
    }

    private fun applyCamera2Parameters() {
        val builder = previewRequestBuilder ?: return
        val session = captureSession ?: return
        try {
            builder.set(CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION, exposureCompSteps)
            builder.set(CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE, CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE_ON)
            builder.set(CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE, CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_ON)
            val fps = if (use60Fps) Range(60, 60) else Range(30, 30)
            builder.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, fps)
            session.setRepeatingRequest(builder.build(), null, cameraHandler)
        } catch (_: Exception) {}
    }

    fun toggleVideoRecording(onStateChanged: (Boolean) -> Unit) {
        glSurfaceView.queueEvent {
            if (!isRecordingVideo) {
                startVideoRecordingInternal()
                isRecordingVideo = true
            } else {
                stopVideoRecordingInternal()
                isRecordingVideo = false
            }
            glSurfaceView.post { onStateChanged(isRecordingVideo) }
        }
    }

    @Suppress("DEPRECATION")
    private fun startVideoRecordingInternal() {
        try {
            val outFile = File(context.externalCacheDir, "temp_iphone16_${System.currentTimeMillis()}.mp4")
            currentVideoFile = outFile

            val recorder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                MediaRecorder(context)
            } else {
                MediaRecorder()
            }

            recorder.apply {
                setAudioSource(MediaRecorder.AudioSource.CAMCORDER)
                setVideoSource(MediaRecorder.VideoSource.SURFACE)
                setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                setOutputFile(outFile.absolutePath)
                setVideoEncodingBitRate(50_000_000) // 50 Mbps high-bitrate capture
                setVideoFrameRate(if (use60Fps) 60 else 30)
                setVideoSize(videoWidth, videoHeight)
                setVideoEncoder(MediaRecorder.VideoEncoder.H264)
                setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                setAudioEncodingBitRate(192_000)
                setAudioSamplingRate(48_000)
                prepare()
            }

            val eglDisplay = EGL14.eglGetCurrentDisplay()
            val configs = arrayOfNulls<android.opengl.EGLConfig>(1)
            val numConfigs = IntArray(1)
            val attrList = intArrayOf(
                EGL14.EGL_RED_SIZE, 8,
                EGL14.EGL_GREEN_SIZE, 8,
                EGL14.EGL_BLUE_SIZE, 8,
                EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
                EGLExt.EGL_RECORDABLE_ANDROID, 1,
                EGL14.EGL_NONE
            )
            EGL14.eglChooseConfig(eglDisplay, attrList, 0, configs, 0, 1, numConfigs, 0)
            eglConfigUsed = configs[0]

            val surfaceAttribs = intArrayOf(EGL14.EGL_NONE)
            encoderEglSurface = EGL14.eglCreateWindowSurface(
                eglDisplay, eglConfigUsed, recorder.surface, surfaceAttribs, 0
            )

            recorder.start()
            mediaRecorder = recorder
        } catch (e: Exception) {
            e.printStackTrace()
            showToast("Video start error: ${e.message}")
        }
    }

    private fun stopVideoRecordingInternal() {
        try {
            val eglDisplay = EGL14.eglGetCurrentDisplay()
            if (encoderEglSurface != EGL14.EGL_NO_SURFACE) {
                EGL14.eglDestroySurface(eglDisplay, encoderEglSurface)
                encoderEglSurface = EGL14.EGL_NO_SURFACE
            }
            mediaRecorder?.apply {
                stop()
                release()
            }
            mediaRecorder = null

            currentVideoFile?.let { file ->
                saveVideoToGallery(file)
                file.delete()
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun saveCurrentFramebufferAsJpeg(width: Int, height: Int) {
        val buffer = ByteBuffer.allocateDirect(width * height * 4).order(ByteOrder.nativeOrder())
        GLES30.glReadPixels(0, 0, width, height, GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, buffer)
        buffer.rewind()

        Thread {
            try {
                val rawBitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                rawBitmap.copyPixelsFromBuffer(buffer)

                val flipMatrix = Matrix().apply { preScale(1f, -1f) }
                val uprightBitmap = Bitmap.createBitmap(rawBitmap, 0, 0, width, height, flipMatrix, false)
                rawBitmap.recycle()

                val values = ContentValues().apply {
                    put(MediaStore.Images.Media.DISPLAY_NAME, "IMG_iPhoneStyle_${System.currentTimeMillis()}.jpg")
                    put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                    put(MediaStore.Images.Media.RELATIVE_PATH, "${Environment.DIRECTORY_PICTURES}/iPhone16StyleCam")
                }

                val uri = context.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                uri?.let {
                    context.contentResolver.openOutputStream(it)?.use { out ->
                        uprightBitmap.compress(Bitmap.CompressFormat.JPEG, 98, out)
                    }
                }
                uprightBitmap.recycle()
                showToast("Saved Photo to Pictures/iPhone16StyleCam")
            } catch (e: Exception) {
                showToast("Photo error: ${e.message}")
            }
        }.start()
    }

    private fun saveVideoToGallery(tempFile: File) {
        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, "VID_iPhoneStyle_${System.currentTimeMillis()}.mp4")
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            put(MediaStore.Video.Media.RELATIVE_PATH, "${Environment.DIRECTORY_MOVIES}/iPhone16StyleCam")
        }
        val uri = context.contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
        uri?.let {
            context.contentResolver.openOutputStream(it)?.use { out ->
                tempFile.inputStream().use { input -> input.copyTo(out) }
            }
        }
        showToast("Saved 50Mbps Video to Movies/iPhone16StyleCam")
    }

    @SuppressLint("MissingPermission")
    private fun openCamera() {
        val manager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        try {
            val backCameraId = manager.cameraIdList.firstOrNull { id ->
                val chars = manager.getCameraCharacteristics(id)
                chars.get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK
            } ?: manager.cameraIdList.first()

            manager.openCamera(backCameraId, object : CameraDevice.StateCallback() {
                override fun onOpened(camera: CameraDevice) {
                    cameraDevice = camera
                    startPreviewSession(camera)
                }
                override fun onDisconnected(camera: CameraDevice) { camera.close() }
                override fun onError(camera: CameraDevice, error: Int) { camera.close() }
            }, cameraHandler)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    @Suppress("DEPRECATION")
    private fun startPreviewSession(camera: CameraDevice) {
        val st = surfaceTexture ?: return
        st.setDefaultBufferSize(1920, 1080)
        val previewSurface = Surface(st)

        val builder = camera.createCaptureRequest(CameraDevice.TEMPLATE_RECORD).apply {
            addTarget(previewSurface)
            set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO)
        }
        previewRequestBuilder = builder

        camera.createCaptureSession(
            listOf(previewSurface),
            object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(session: CameraCaptureSession) {
                    captureSession = session
                    applyCamera2Parameters()
                }
                override fun onConfigureFailed(session: CameraCaptureSession) {}
            },
            cameraHandler
        )
    }

    private fun startCameraThread() {
        cameraThread = HandlerThread("IMX766CameraThread").also { it.start() }
        cameraHandler = Handler(cameraThread!!.looper)
    }

    fun release() {
        captureSession?.close()
        cameraDevice?.close()
        cameraThread?.quitSafely()
    }

    private fun showToast(msg: String) {
        glSurfaceView.post { Toast.makeText(context, msg, Toast.LENGTH_SHORT).show() }
    }

    private fun createOESTexture(): Int {
        val tex = IntArray(1)
        GLES30.glGenTextures(1, tex, 0)
        GLES30.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, tex[0])
        GLES30.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)
        return tex[0]
    }

    private fun createProgram(vertexSource: String, fragmentSource: String): Int {
        val vs = loadShader(GLES30.GL_VERTEX_SHADER, vertexSource)
        val fs = loadShader(GLES30.GL_FRAGMENT_SHADER, fragmentSource)
        return GLES30.glCreateProgram().also {
            GLES30.glAttachShader(it, vs)
            GLES30.glAttachShader(it, fs)
            GLES30.glLinkProgram(it)
        }
    }

    private fun loadShader(type: Int, code: String): Int {
        return GLES30.glCreateShader(type).also {
          