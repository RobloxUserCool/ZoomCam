package com.zoomcam

import android.Manifest
import android.annotation.SuppressLint
import android.content.ContentValues
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapRegionDecoder
import android.graphics.Rect
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import android.content.ContentResolver
import android.content.ContentUris
import android.content.Intent
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.util.LruCache
import android.view.ViewOutlineProvider
import android.widget.BaseAdapter
import android.widget.GridView
import android.widget.MediaController
import android.widget.VideoView
import androidx.activity.OnBackPressedCallback
import java.util.concurrent.Executors
import android.graphics.SurfaceTexture
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.media.MediaMuxer
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLExt
import android.opengl.EGLSurface
import android.opengl.GLES20
import android.os.Build
import android.os.SystemClock
import android.view.Surface
import java.nio.FloatBuffer
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import android.animation.ValueAnimator
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CaptureRequest
import android.provider.MediaStore
import android.util.Range
import android.view.Choreographer
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.Gravity
import android.view.View
import android.util.TypedValue
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.ProgressBar
import android.view.ViewGroup
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.widget.SwitchCompat
import android.widget.Button
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.camera2.interop.Camera2CameraControl
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.camera2.interop.CaptureRequestOptions
import androidx.camera.camera2.interop.Camera2Interop
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.core.Preview
import androidx.camera.core.ZoomState
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.FallbackStrategy
import androidx.camera.video.MediaStoreOutputOptions
import androidx.camera.video.Quality
import androidx.camera.video.QualitySelector
import androidx.camera.video.Recorder
import androidx.camera.video.Recording
import androidx.camera.video.VideoCapture
import androidx.camera.video.VideoRecordEvent
import androidx.camera.view.PreviewView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LiveData
import androidx.lifecycle.Observer
import org.tensorflow.lite.Interpreter
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.pow

class MainActivity : AppCompatActivity() {

    private lateinit var previewView: PreviewView
    private lateinit var zoomLabel: TextView
    private lateinit var infoText: TextView
    private lateinit var timerText: TextView
    private lateinit var zoomSeek: SeekBar
    private lateinit var recBtn: View
    private lateinit var flipBtn: TextView
    private lateinit var qualityPill: TextView
    private lateinit var fpsPill: TextView
    private lateinit var focusRing: View
    private lateinit var zoomChips: List<Pair<TextView, Float>>
    private val ui = Handler(Looper.getMainLooper())
    private val hideZoomLabel = Runnable { zoomLabel.animate().alpha(0f).setDuration(450).start() }
    private var recPulse: ValueAnimator? = null
    private var dlg: AlertDialog? = null
    private var dlgProgress: ProgressBar? = null
    private var dlgStatus: TextView? = null
    private var dlgPreview: ImageView? = null
    private var dlgPick: TextView? = null
    private var galleryBtn: ImageView? = null
    private var galleryRoot: FrameLayout? = null
    private var galleryAdapter: BaseAdapter? = null
    private var galleryItems: List<MediaItem> = emptyList()
    private var galleryAll = false
    private var galleryEmpty: TextView? = null
    private var chipLast: TextView? = null
    private var chipAll: TextView? = null
    private var viewerRoot: FrameLayout? = null
    private var viewerImg: ImageView? = null
    private var viewerPlay: View? = null
    private var viewerCount: TextView? = null
    private var viewerVideo: VideoView? = null
    private var viewerRow: View? = null
    private var viewerIndex = 0
    private var viewerSeq = 0
    private var pendingGallery = false
    private var askedGalleryPerm = false
    private val thumbPool = Executors.newFixedThreadPool(3)
    private val thumbCache = object : LruCache<Long, Bitmap>(maxOf(16 * 1024, (Runtime.getRuntime().maxMemory() / 1024 / 8).toInt())) {
        override fun sizeOf(key: Long, value: Bitmap): Int = value.byteCount / 1024
    }
    private val galleryPerm = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { _ ->
        loadLastThumb()
        if (pendingGallery) { pendingGallery = false; openGallery() }
    }
    private val backCb = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() {
            if (viewerRoot != null) closeViewer() else closeGallery()
        }
    }
    private val pickPhoto = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) enhancePicked(uri)
    }
    private val qualityShort = listOf("480p", "720p", "1080p", "4K")
    private lateinit var nightBtn: TextView
    private lateinit var dayBtn: TextView
    private lateinit var aiBtn: TextView

    private var provider: ProcessCameraProvider? = null
    private var camera: Camera? = null
    private var videoCapture: VideoCapture<Recorder>? = null
    private var recording: Recording? = null
    private var imageCapture: ImageCapture? = null
    private var zoomLive: LiveData<ZoomState>? = null

    private var lens = CameraSelector.LENS_FACING_BACK
    private var minZ = 1f
    private var maxZ = 1f
    private var targetZ = 1f
    private var curZ = 1f
    private var lastApplied = 0f
    private var hwMaxZ = 1f          // максимум зума самой камеры (у телефона это около x2)
    private var lastHw = 0f
    private val virtualMax = 100f    // дальше аппаратного приближаем программно
    private var lastHint = 0L
    private var focusLocked = false
    private var stabOn = true
    private var isRecording = false
    private val photoLock = Any()
    private lateinit var focusBtn: TextView
    private lateinit var stabBtn: TextView
    private var aiOn = true
    private var aiBusy = false
    private var lastVideoInfo = ""
    private var highZoom = false
    private var mode = 0   // 0 = обычный, 1 = ночной, 2 = дневной (тёплый)

    private val qualities = listOf(
        "SD 480p" to Quality.SD,
        "HD 720p" to Quality.HD,
        "Full HD 1080p" to Quality.FHD,
        "4K" to Quality.UHD
    )
    private var qualityIndex = 2
    private var fpsIndex = 0   // 0 = 30 к/с, 1 = 60 к/с
    // целевой битрейт: заметно выше стандартного, чтобы сохранялись детали и не было «каши» в движении
    private val bitrates = listOf(8_000_000, 16_000_000, 30_000_000, 90_000_000)

    private val zoomObs = Observer<ZoomState> { z ->
        minZ = z.minZoomRatio
        hwMaxZ = z.maxZoomRatio
        maxZ = maxOf(hwMaxZ, virtualMax)
        infoText.text = "зум до " + fmt(maxZ) + "x"
    }

    // Плавный зум: каждый кадр экрана приближаемся к цели (в логарифмической шкале)
    private val frameCb = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            if (camera != null && maxZ >= minZ) {
                val c = curZ.coerceIn(minZ, maxZ)
                val t = targetZ.coerceIn(minZ, maxZ)
                val next = exp(ln(c.toDouble()) + (ln(t.toDouble()) - ln(c.toDouble())) * 0.12).toFloat()
                curZ = if (abs(next - t) < 0.002f * t) t else next
                if (curZ != lastApplied) {
                    // до предела камеры зумит сама камера, остальное добираем увеличением картинки
                    val hw = curZ.coerceIn(minZ, maxOf(minZ, hwMaxZ))
                    if (hw != lastHw) {
                        camera?.cameraControl?.setZoomRatio(hw)
                        lastHw = hw
                    }
                    val ex = if (hw > 0f) curZ / hw else 1f
                    if (previewView.scaleX != ex) {
                        previewView.scaleX = ex
                        previewView.scaleY = ex
                    }
                    lastApplied = curZ
                    zoomLabel.text = fmt(curZ) + "x"
                    if (zoomLabel.alpha < 1f) { zoomLabel.animate().cancel(); zoomLabel.alpha = 1f }
                    ui.removeCallbacks(hideZoomLabel)
                    ui.postDelayed(hideZoomLabel, 1300)
                    val hz = curZ >= 3f
                    if (hz != highZoom) { highZoom = hz; applyMode() }
                }
            }
            Choreographer.getInstance().postFrameCallback(this)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        previewView = findViewById(R.id.previewView)
        zoomLabel = findViewById(R.id.zoomLabel)
        infoText = findViewById(R.id.infoText)
        timerText = findViewById(R.id.timerText)
        zoomSeek = findViewById(R.id.zoomSeek)
        recBtn = findViewById(R.id.recBtn)
        flipBtn = findViewById(R.id.flipBtn)
        qualityPill = findViewById(R.id.qualityPill)
        fpsPill = findViewById(R.id.fpsPill)
        focusRing = findViewById(R.id.focusRing)
        nightBtn = findViewById(R.id.nightBtn)
        dayBtn = findViewById(R.id.dayBtn)
        aiBtn = findViewById(R.id.aiBtn)

        previewView.implementationMode = PreviewView.ImplementationMode.COMPATIBLE
        previewView.scaleType = PreviewView.ScaleType.FIT_CENTER
        previewView.keepScreenOn = true

        updatePills()
        qualityPill.setOnClickListener { v ->
            val pm = PopupMenu(this, v)
            qualities.forEachIndexed { i, q -> pm.menu.add(0, i, i, q.first) }
            pm.setOnMenuItemClickListener { mi ->
                if (mi.itemId != qualityIndex) {
                    qualityIndex = mi.itemId
                    updatePills()
                    bindCamera()
                }
                true
            }
            pm.show()
        }
        fpsPill.setOnClickListener { v ->
            val pm = PopupMenu(this, v)
            pm.menu.add(0, 0, 0, "30 к/с")
            pm.menu.add(0, 1, 1, "60 к/с")
            pm.setOnMenuItemClickListener { mi ->
                if (mi.itemId != fpsIndex) {
                    fpsIndex = mi.itemId
                    updatePills()
                    bindCamera()
                }
                true
            }
            pm.show()
        }

        zoomSeek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) {
                if (fromUser) setTarget(seekToZoom(p), false)
            }
            override fun onStartTrackingTouch(s: SeekBar?) {}
            override fun onStopTrackingTouch(s: SeekBar?) {}
        })

        zoomChips = listOf(
            findViewById<TextView>(R.id.z1) to 1f,
            findViewById<TextView>(R.id.z5) to 5f,
            findViewById<TextView>(R.id.z10) to 10f,
            findViewById<TextView>(R.id.zmax) to -1f
        )
        for ((chip, z) in zoomChips) {
            chip.setOnClickListener { setTarget(if (z < 0f) maxZ else z) }
            pressFx(chip)
        }

        flipBtn.setOnClickListener {
            lens = if (lens == CameraSelector.LENS_FACING_BACK)
                CameraSelector.LENS_FACING_FRONT else CameraSelector.LENS_FACING_BACK
            bindCamera()
        }
        nightBtn.setOnClickListener { setMode(if (mode == 1) 0 else 1) }
        dayBtn.setOnClickListener { setMode(if (mode == 2) 0 else 2) }
        aiBtn.setOnClickListener { showEnhanceDialog() }
        addExtraButtons()
        addGalleryButton()
        onBackPressedDispatcher.addCallback(this, backCb)
        updateModeUi()

        recBtn.setOnClickListener { toggleRecord() }
        val photoBtn = findViewById<View>(R.id.photoBtn)
        photoBtn.setOnClickListener { takePhoto() }
        for (v in listOf(photoBtn, recBtn, flipBtn, nightBtn, dayBtn, aiBtn, qualityPill, fpsPill)) pressFx(v)

        // красивое появление панелей
        val topBar = findViewById<View>(R.id.topBar)
        val bottomBar = findViewById<View>(R.id.bottomBar)
        val sideBar = findViewById<View>(R.id.sideBar)
        topBar.alpha = 0f; topBar.translationY = -160f
        bottomBar.alpha = 0f; bottomBar.translationY = 320f
        sideBar.alpha = 0f; sideBar.translationX = 160f
        val ip = DecelerateInterpolator(1.6f)
        topBar.animate().alpha(1f).translationY(0f).setDuration(500).setInterpolator(ip).start()
        bottomBar.animate().alpha(1f).translationY(0f).setDuration(550).setInterpolator(ip).start()
        sideBar.animate().alpha(1f).translationX(0f).setStartDelay(200).setDuration(500).setInterpolator(ip).start()
        ui.postDelayed(hideZoomLabel, 1800)
        updateZoomChips()

        // щипок = зум, тап = фокус
        val scale = ScaleGestureDetector(this, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(d: ScaleGestureDetector): Boolean {
                setTarget(targetZ * d.scaleFactor.pow(1.5f))
                return true
            }
        })
        val tap = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onSingleTapUp(e: MotionEvent): Boolean {
                val sc = previewView.scaleX
                val lx = previewView.pivotX + (e.x - previewView.pivotX) / sc
                val ly = previewView.pivotY + (e.y - previewView.pivotY) / sc
                val pt = previewView.meteringPointFactory.createPoint(lx, ly)
                if (focusLocked) {
                    // фокус держится на выбранном предмете, пока не выключишь кнопку 🎯 (яркость не трогаем)
                    camera?.cameraControl?.startFocusAndMetering(
                        FocusMeteringAction.Builder(pt, FocusMeteringAction.FLAG_AF).disableAutoCancel().build()
                    )
                } else {
                    camera?.cameraControl?.startFocusAndMetering(FocusMeteringAction.Builder(pt).build())
                }
                showFocusRing(e.x, e.y)
                return true
            }
        })
        previewView.setOnTouchListener { v, e ->
            // когда картинка увеличена программно, возвращаем координаты пальцев к экранным, иначе щипок перестаёт работать
            val ev = MotionEvent.obtain(e)
            val sc = v.scaleX
            if (sc != 1f) {
                val m = Matrix()
                m.setScale(sc, sc, v.pivotX, v.pivotY)
                ev.transform(m)
            }
            scale.onTouchEvent(ev)
            tap.onTouchEvent(ev)
            ev.recycle()
            true
        }

        if (hasPerm(Manifest.permission.CAMERA)) {
            startCamera()
        } else {
            ActivityCompat.requestPermissions(
                this, arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO), 10
            )
        }
    }

    override fun onResume() {
        super.onResume()
        Choreographer.getInstance().postFrameCallback(frameCb)
        loadLastThumb()
    }

    override fun onPause() {
        super.onPause()
        Choreographer.getInstance().removeFrameCallback(frameCb)
    }

    override fun onRequestPermissionsResult(code: Int, perms: Array<out String>, res: IntArray) {
        super.onRequestPermissionsResult(code, perms, res)
        if (hasPerm(Manifest.permission.CAMERA)) startCamera()
        else {
            Toast.makeText(this, "Нужен доступ к камере", Toast.LENGTH_LONG).show()
            finish()
        }
    }

    private fun hasPerm(p: String) =
        ContextCompat.checkSelfPermission(this, p) == PackageManager.PERMISSION_GRANTED

    private fun startCamera() {
        val f = ProcessCameraProvider.getInstance(this)
        f.addListener({
            provider = f.get()
            bindCamera()
        }, ContextCompat.getMainExecutor(this))
    }

    private fun bindCamera() {
        val p = provider ?: return
        if (recording != null) {
            Toast.makeText(this, "Сначала останови запись", Toast.LENGTH_SHORT).show()
            return
        }
        val selector = CameraSelector.Builder().requireLensFacing(lens).build()
        val fps = if (fpsIndex == 1) 60 else 30
        val q = qualities[qualityIndex].second
        val bitrate = (bitrates[qualityIndex] * (if (fps > 30) 1.5 else 1.0)).toInt()

        val recorder = Recorder.Builder()
            .setQualitySelector(QualitySelector.from(q, FallbackStrategy.lowerQualityOrHigherThan(q)))
            .setTargetVideoEncodingBitRate(bitrate)
            .build()
        val previewBuilder = Preview.Builder()
        val vcBuilder = VideoCapture.Builder(recorder).setTargetFrameRate(Range(fps, fps))
        val imageBuilder = ImageCapture.Builder()
            .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
            .setResolutionSelector(
                ResolutionSelector.Builder()
                    .setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY)
                    .setResolutionStrategy(ResolutionStrategy.HIGHEST_AVAILABLE_STRATEGY)
                    .build()
            )

        // Улучшения картинки на уровне камеры (только если телефон их поддерживает)
        try {
            val info = selector.filter(p.availableCameraInfos).firstOrNull()
            if (info != null) {
                val c2 = Camera2CameraInfo.from(info)
                val stab = c2.getCameraCharacteristic(CameraCharacteristics.CONTROL_AVAILABLE_VIDEO_STABILIZATION_MODES)
                val nr = c2.getCameraCharacteristic(CameraCharacteristics.NOISE_REDUCTION_AVAILABLE_NOISE_REDUCTION_MODES)
                val edge = c2.getCameraCharacteristic(CameraCharacteristics.EDGE_AVAILABLE_EDGE_MODES)
                val extenders = listOf<Camera2Interop.Extender<*>>(
                    Camera2Interop.Extender(previewBuilder),
                    Camera2Interop.Extender(vcBuilder)
                )
                for (ext in extenders) {
                    if (stab != null && stab.contains(CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_ON)) {
                        ext.setCaptureRequestOption(CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE, CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_ON)
                    }
                    if (fps <= 30) {
                        if (nr != null && nr.contains(CaptureRequest.NOISE_REDUCTION_MODE_HIGH_QUALITY)) {
                            ext.setCaptureRequestOption(CaptureRequest.NOISE_REDUCTION_MODE, CaptureRequest.NOISE_REDUCTION_MODE_HIGH_QUALITY)
                        }
                        if (edge != null && edge.contains(CaptureRequest.EDGE_MODE_HIGH_QUALITY)) {
                            ext.setCaptureRequestOption(CaptureRequest.EDGE_MODE, CaptureRequest.EDGE_MODE_HIGH_QUALITY)
                        }
                    }
                }
                // для фото всегда максимальное шумоподавление и резкость
                val imgExt = Camera2Interop.Extender(imageBuilder)
                if (nr != null && nr.contains(CaptureRequest.NOISE_REDUCTION_MODE_HIGH_QUALITY)) {
                    imgExt.setCaptureRequestOption(CaptureRequest.NOISE_REDUCTION_MODE, CaptureRequest.NOISE_REDUCTION_MODE_HIGH_QUALITY)
                }
                if (edge != null && edge.contains(CaptureRequest.EDGE_MODE_HIGH_QUALITY)) {
                    imgExt.setCaptureRequestOption(CaptureRequest.EDGE_MODE, CaptureRequest.EDGE_MODE_HIGH_QUALITY)
                }
            }
        } catch (e: Exception) {
            // если что-то не поддерживается, просто снимаем с настройками по умолчанию
        }

        val preview = previewBuilder.build().also { it.setSurfaceProvider(previewView.surfaceProvider) }
        val vc = vcBuilder.build()
        val ic = imageBuilder.build()
        try {
            p.unbindAll()
            zoomLive?.removeObserver(zoomObs)
            minZ = 1f; maxZ = 1f; targetZ = 1f; curZ = 1f; lastApplied = 0f
            hwMaxZ = 1f; lastHw = 0f
            focusLocked = false
            previewView.scaleX = 1f; previewView.scaleY = 1f
            camera = p.bindToLifecycle(this, selector, preview, vc, ic)
            videoCapture = vc
            imageCapture = ic
            zoomLive = camera!!.cameraInfo.zoomState
            zoomLive!!.observe(this, zoomObs)
            zoomSeek.progress = 0
            updateZoomChips()
            updateModeUi()
            applyMode()
        } catch (e: Exception) {
            if (fpsIndex == 1) {
                fpsIndex = 0
                updatePills()
                Toast.makeText(this, "Такой режим в 60 к/с не поддерживается, включил 30", Toast.LENGTH_LONG).show()
                bindCamera()
            } else {
                Toast.makeText(this, "Эта камера не поддерживает такое качество: " + e.message, Toast.LENGTH_LONG).show()
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun toggleRecord() {
        val vc = videoCapture ?: return
        val cur = recording
        if (cur != null) {
            cur.stop()
            return
        }
        if (curZ > hwMaxZ + 0.01f || targetZ > hwMaxZ + 0.01f) {
            Toast.makeText(this, "В видео зум только до " + fmt(hwMaxZ) + "x", Toast.LENGTH_SHORT).show()
            targetZ = hwMaxZ.coerceAtLeast(minZ)
            curZ = targetZ
            zoomSeek.progress = zoomToSeek(targetZ)
            updateZoomChips()
        }
        val name = "ZoomCam_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, "video/mp4")
            put(MediaStore.Video.Media.RELATIVE_PATH, "DCIM/Camera")
        }
        val out = MediaStoreOutputOptions.Builder(contentResolver, MediaStore.Video.Media.EXTERNAL_CONTENT_URI)
            .setContentValues(values).build()
        var pending = vc.output.prepareRecording(this, out)
        if (hasPerm(Manifest.permission.RECORD_AUDIO)) pending = pending.withAudioEnabled()
        recording = pending.start(ContextCompat.getMainExecutor(this)) { ev ->
            when (ev) {
                is VideoRecordEvent.Start -> {
                    isRecording = true
                    setRecUi(true)
                    applyMode()
                }
                is VideoRecordEvent.Status -> {
                    val s = ev.recordingStats.recordedDurationNanos / 1_000_000_000L
                    timerText.text = String.format(Locale.US, "● %02d:%02d", s / 60, s % 60)
                }
                is VideoRecordEvent.Finalize -> {
                    recording = null
                    isRecording = false
                    ui.postDelayed({ loadLastThumb() }, 1200)
                    setRecUi(false)
                    applyMode()
                    if (ev.hasError()) {
                        Toast.makeText(this, "Ошибка записи: " + ev.error, Toast.LENGTH_LONG).show()
                    } else {
                        Toast.makeText(this, "Сохранено: DCIM/Camera", Toast.LENGTH_LONG).show()
                    }
                }
                else -> {}
            }
        }
    }

    private fun takePhoto() {
        val ic = imageCapture ?: return
        val shotZoom = curZ
        val shotMode = mode
        val hwNow = curZ.coerceIn(minZ, maxOf(minZ, hwMaxZ))
        val extraZ = if (hwNow > 0f) curZ / hwNow else 1f
        val name = "ZoomCam_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
            put(MediaStore.Images.Media.RELATIVE_PATH, "DCIM/Camera")
        }
        val out = ImageCapture.OutputFileOptions.Builder(
            contentResolver, MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values
        ).build()
        previewView.animate().alpha(0.3f).setDuration(70).withEndAction {
            previewView.animate().alpha(1f).setDuration(110).start()
        }.start()
        ic.takePicture(out, ContextCompat.getMainExecutor(this), object : ImageCapture.OnImageSavedCallback {
            override fun onImageSaved(r: ImageCapture.OutputFileResults) {
                Toast.makeText(this@MainActivity, "Фото сохранено в галерею", Toast.LENGTH_SHORT).show()
                val u = r.savedUri
                if (u != null) {
                    // убираем цветной шум и (если зум выше аппаратного) приближаем фото
                    Thread {
                        val ok = synchronized(photoLock) {
                            try { processPhoto(u, extraZ, shotMode == 1) } catch (t: Throwable) { false }
                        }
                        runOnUiThread {
                            if (ok) {
                                Toast.makeText(this@MainActivity, "Фото очищено от шума", Toast.LENGTH_SHORT).show()
                            } else {
                                Toast.makeText(this@MainActivity, "Не удалось очистить фото, оставил как есть", Toast.LENGTH_LONG).show()
                            }
                            loadLastThumb()
                            if ((ok || extraZ <= 1.02f) && aiOn) enhanceAsync(u, shotZoom, name)
                        }
                    }.start()
                }
            }
            override fun onError(e: ImageCaptureException) {
                Toast.makeText(this@MainActivity, "Ошибка фото: " + e.message, Toast.LENGTH_LONG).show()
            }
        })
    }

    // Обработка фото после съёмки: чистим цветной шум, а при зуме выше аппаратного берём центр кадра и растягиваем
    private fun processPhoto(uri: Uri, extra: Float, strong: Boolean): Boolean {
        val bytes = contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: return false
        val bo = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bo)
        val w0 = bo.outWidth
        val h0 = bo.outHeight
        if (w0 <= 0 || h0 <= 0) return false
        val orient = ExifInterface(java.io.ByteArrayInputStream(bytes))
            .getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
        val deg = when (orient) {
            ExifInterface.ORIENTATION_ROTATE_90 -> 90f
            ExifInterface.ORIENTATION_ROTATE_180 -> 180f
            ExifInterface.ORIENTATION_ROTATE_270 -> 270f
            else -> 0f
        }
        val crop = extra > 1.02f
        var bmp: Bitmap
        if (crop) {
            val cw = (w0 / extra).toInt().coerceIn(16, w0)
            val ch = (h0 / extra).toInt().coerceIn(16, h0)
            val left = (w0 - cw) / 2
            val top = (h0 - ch) / 2
            @Suppress("DEPRECATION")
            val dec = BitmapRegionDecoder.newInstance(bytes, 0, bytes.size, false) ?: return false
            val part = dec.decodeRegion(Rect(left, top, left + cw, top + ch), null)
            dec.recycle()
            if (part == null) return false
            bmp = if (part.isMutable) part else (part.copy(Bitmap.Config.ARGB_8888, true) ?: return false)
        } else {
            val o = BitmapFactory.Options().apply {
                inMutable = true
                inPreferredConfig = Bitmap.Config.ARGB_8888
            }
            bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, o) ?: return false
        }
        // шум убираем на родном разрешении, пока картинка ещё не растянута
        cleanNoise(bmp, strong)
        if (deg != 0f) {
            val m = Matrix().apply { postRotate(deg) }
            val rot = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true)
            if (rot !== bmp) bmp.recycle()
            bmp = rot
        }
        if (crop) {
            val swap = deg == 90f || deg == 270f
            val outW = if (swap) h0 else w0
            val outH = if (swap) w0 else h0
            val big = Bitmap.createScaledBitmap(bmp, outW, outH, true)
            if (big !== bmp) bmp.recycle()
            bmp = big
        }
        val bos = ByteArrayOutputStream()
        bmp.compress(Bitmap.CompressFormat.JPEG, 97, bos)
        val out = contentResolver.openOutputStream(uri, "wt") ?: return false
        out.use { it.write(bos.toByteArray()) }
        return true
    }

    private fun halve(b: Bitmap): Bitmap =
        Bitmap.createScaledBitmap(b, maxOf(1, b.width / 2), maxOf(1, b.height / 2), true)

    private fun planeOf(bmp: Bitmap): FloatArray {
        val w = bmp.width
        val h = bmp.height
        val px = IntArray(w * h)
        bmp.getPixels(px, 0, w, 0, 0, w, h)
        val out = FloatArray(w * h)
        for (i in px.indices) {
            val p = px[i]
            out[i] = 0.299f * ((p shr 16) and 255) + 0.587f * ((p shr 8) and 255) + 0.114f * (p and 255)
        }
        return out
    }

    private fun upPlane(src: FloatArray, sw: Int, sh: Int, dw: Int, dh: Int): FloatArray {
        val out = FloatArray(dw * dh)
        val x0 = IntArray(dw); val x1 = IntArray(dw); val xt = FloatArray(dw)
        for (x in 0 until dw) {
            val f = (x + 0.5f) * sw / dw - 0.5f
            val i = floor(f).toInt()
            x0[x] = i.coerceIn(0, sw - 1); x1[x] = (i + 1).coerceIn(0, sw - 1); xt[x] = f - i
        }
        for (y in 0 until dh) {
            val f = (y + 0.5f) * sh / dh - 0.5f
            val i = floor(f).toInt()
            val t = f - i
            val r0 = i.coerceIn(0, sh - 1) * sw
            val r1 = (i + 1).coerceIn(0, sh - 1) * sw
            for (x in 0 until dw) {
                val a = xt[x]
                out[y * dw + x] = (src[r0 + x0[x]] * (1 - a) + src[r0 + x1[x]] * a) * (1 - t) +
                    (src[r1 + x0[x]] * (1 - a) + src[r1 + x1[x]] * a) * t
            }
        }
        return out
    }

    // оценка уровня шума по медиане отклонений (устойчива к настоящим краям)
    private fun histSigma(hist: IntArray, n: Int): Float {
        var acc = 0
        val half = n / 2
        for (b in hist.indices) {
            acc += hist[b]
            if (acc > half) return (b + 0.5f) / 4f * 1.4826f + 0.05f
        }
        return 1f
    }

    private fun planeSigma(y: FloatArray, base: FloatArray, step: Int): Float {
        val hist = IntArray(1024)
        var n = 0
        var i = 0
        while (i < y.size) {
            val b = minOf(1023, (abs(y[i] - base[i]) * 4f).toInt())
            hist[b]++; n++
            i += step
        }
        return histSigma(hist, n)
    }

    // сжимаем отклонения от размытой основы: мелкое зерно уходит, настоящие контуры остаются
    private fun shrinkLevel(y: FloatArray, base: FloatArray, c: Float, sig: Float): FloatArray {
        val out = FloatArray(y.size)
        for (i in y.indices) {
            val b0 = base[i]
            val d = y[i] - b0
            val cl = c * sig * (1.3f - 0.6f * minOf(b0, 200f) / 200f)
            out[i] = b0 + d * d / (d * d + cl * cl + 1e-6f) * d
        }
        return out
    }

    // Шумоподавление: яркость чистим по уровням (пирамида), цвет берём из очень размытой копии
    private fun cleanNoise(bmp: Bitmap, strong: Boolean) {
        val w = bmp.width
        val h = bmp.height
        if (w < 16 || h < 16) return
        val half = halve(bmp)
        val q = halve(half)
        val e = halve(q)
        val e16 = halve(e)
        val y8 = planeOf(e)
        val st = strong || y8.average() < 70.0   // тёмный кадр чистим сильнее
        val cs = if (st) e16 else e
        val cw = cs.width
        val ch = cs.height
        val cp = IntArray(cw * ch)
        cs.getPixels(cp, 0, cw, 0, 0, cw, ch)
        val cb = FloatArray(cw * ch)
        val cr = FloatArray(cw * ch)
        for (i in cp.indices) {
            val p = cp[i]
            val r = (p shr 16) and 255
            val g = (p shr 8) and 255
            val b = p and 255
            val y = 0.299f * r + 0.587f * g + 0.114f * b
            cb[i] = (b - y) * 0.564f
            cr[i] = (r - y) * 0.713f
        }
        val hw = half.width; val hh = half.height
        val qw = q.width; val qh = q.height
        val ew = e.width; val eh = e.height
        val y2 = planeOf(half)
        val y4 = planeOf(q)
        half.recycle(); q.recycle(); e.recycle(); e16.recycle()

        val c = if (st) 2.5f else 1.8f
        var base = upPlane(y8, ew, eh, qw, qh)
        var r = shrinkLevel(y4, base, c, planeSigma(y4, base, 1))
        base = upPlane(r, qw, qh, hw, hh)
        val r2 = shrinkLevel(y2, base, c, planeSigma(y2, base, 2))

        val cx0 = IntArray(w); val cx1 = IntArray(w); val cxt = FloatArray(w)
        val hx0 = IntArray(w); val hx1 = IntArray(w); val hxt = FloatArray(w)
        for (x in 0 until w) {
            val fc = (x + 0.5f) * cw / w - 0.5f
            val ci = floor(fc).toInt()
            cx0[x] = ci.coerceIn(0, cw - 1); cx1[x] = (ci + 1).coerceIn(0, cw - 1); cxt[x] = fc - ci
            val fh = (x + 0.5f) * hw / w - 0.5f
            val hi = floor(fh).toInt()
            hx0[x] = hi.coerceIn(0, hw - 1); hx1[x] = (hi + 1).coerceIn(0, hw - 1); hxt[x] = fh - hi
        }
        val rowsPer = 32
        val buf = IntArray(w * rowsPer)

        // уровень шума на полном разрешении (по каждой третьей точке)
        val hist = IntArray(1024)
        var hn = 0
        var s0 = 0
        while (s0 < h) {
            val rows = minOf(rowsPer, h - s0)
            bmp.getPixels(buf, 0, w, 0, s0, w, rows)
            var ry = 0
            while (ry < rows) {
                val y = s0 + ry
                val fhy = (y + 0.5f) * hh / h - 0.5f
                val hyi = floor(fhy).toInt()
                val hyt = fhy - hyi
                val hy0 = hyi.coerceIn(0, hh - 1) * hw
                val hy1 = (hyi + 1).coerceIn(0, hh - 1) * hw
                var x = 0
                while (x < w) {
                    val p = buf[ry * w + x]
                    val yy = 0.299f * ((p shr 16) and 255) + 0.587f * ((p shr 8) and 255) + 0.114f * (p and 255)
                    val ah = hxt[x]
                    val bs = (r2[hy0 + hx0[x]] * (1 - ah) + r2[hy0 + hx1[x]] * ah) * (1 - hyt) +
                        (r2[hy1 + hx0[x]] * (1 - ah) + r2[hy1 + hx1[x]] * ah) * hyt
                    hist[minOf(1023, (abs(yy - bs) * 4f).toInt())]++
                    hn++
                    x += 3
                }
                ry += 3
            }
            s0 += rows
        }
        val sig1 = histSigma(hist, hn)

        var y0 = 0
        while (y0 < h) {
            val rows = minOf(rowsPer, h - y0)
            bmp.getPixels(buf, 0, w, 0, y0, w, rows)
            for (ry in 0 until rows) {
                val y = y0 + ry
                val fcy = (y + 0.5f) * ch / h - 0.5f
                val cyi = floor(fcy).toInt()
                val cyt = fcy - cyi
                val cy0 = cyi.coerceIn(0, ch - 1) * cw
                val cy1 = (cyi + 1).coerceIn(0, ch - 1) * cw
                val fhy = (y + 0.5f) * hh / h - 0.5f
                val hyi = floor(fhy).toInt()
                val hyt = fhy - hyi
                val hy0 = hyi.coerceIn(0, hh - 1) * hw
                val hy1 = (hyi + 1).coerceIn(0, hh - 1) * hw
                for (x in 0 until w) {
                    val idx = ry * w + x
                    val p = buf[idx]
                    val pr = (p shr 16) and 255
                    val pg = (p shr 8) and 255
                    val pb = p and 255
                    val yy = 0.299f * pr + 0.587f * pg + 0.114f * pb
                    val a = cxt[x]
                    val i00 = cy0 + cx0[x]; val i01 = cy0 + cx1[x]
                    val i10 = cy1 + cx0[x]; val i11 = cy1 + cx1[x]
                    val cbv = (cb[i00] * (1 - a) + cb[i01] * a) * (1 - cyt) + (cb[i10] * (1 - a) + cb[i11] * a) * cyt
                    val crv = (cr[i00] * (1 - a) + cr[i01] * a) * (1 - cyt) + (cr[i10] * (1 - a) + cr[i11] * a) * cyt
                    val ah = hxt[x]
                    val bs = (r2[hy0 + hx0[x]] * (1 - ah) + r2[hy0 + hx1[x]] * ah) * (1 - hyt) +
                        (r2[hy1 + hx0[x]] * (1 - ah) + r2[hy1 + hx1[x]] * ah) * hyt
                    val d = yy - bs
                    val cl = c * sig1 * (1.3f - 0.6f * minOf(bs, 200f) / 200f)
                    val ny = bs + d * d / (d * d + cl * cl + 1e-6f) * d
                    var nr = ny + 1.403f * crv
                    var nb = ny + 1.773f * cbv
                    var ng = (ny - 0.299f * nr - 0.114f * nb) / 0.587f
                    if (nr < 0f) nr = 0f else if (nr > 255f) nr = 255f
                    if (ng < 0f) ng = 0f else if (ng > 255f) ng = 255f
                    if (nb < 0f) nb = 0f else if (nb > 255f) nb = 255f
                    buf[idx] = (0xFF shl 24) or (nr.toInt() shl 16) or (ng.toInt() shl 8) or nb.toInt()
                }
            }
            bmp.setPixels(buf, 0, w, 0, y0, w, rows)
            y0 += rows
        }
    }

    // ---------- Галерея ----------
    private fun dpx(v: Float) = (v * resources.displayMetrics.density).toInt()

    private fun mediaPerms(): Array<String> =
        if (Build.VERSION.SDK_INT >= 33) arrayOf("android.permission.READ_MEDIA_IMAGES", "android.permission.READ_MEDIA_VIDEO")
        else arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)

    private fun hasMediaPerm(): Boolean =
        if (Build.VERSION.SDK_INT >= 33) hasPerm("android.permission.READ_MEDIA_IMAGES")
        else hasPerm(Manifest.permission.READ_EXTERNAL_STORAGE)

    // только список (id и тип), без картинок: поэтому открывается мгновенно даже на тысячах фото
    private fun queryMedia(limit: Int): List<MediaItem> {
        val out = ArrayList<MediaItem>()
        try {
            val uri = MediaStore.Files.getContentUri("external")
            val idc = MediaStore.Files.FileColumns._ID
            val tc = MediaStore.Files.FileColumns.MEDIA_TYPE
            val b = Bundle().apply {
                putString(ContentResolver.QUERY_ARG_SQL_SELECTION, tc + "=? OR " + tc + "=?")
                putStringArray(ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS, arrayOf("1", "3"))
                putString(ContentResolver.QUERY_ARG_SQL_SORT_ORDER, MediaStore.Files.FileColumns.DATE_ADDED + " DESC, " + idc + " DESC")
                if (limit > 0) putInt(ContentResolver.QUERY_ARG_LIMIT, limit)
            }
            contentResolver.query(uri, arrayOf(idc, tc), b, null)?.use { c ->
                while (c.moveToNext()) {
                    val id = c.getLong(0)
                    val video = c.getInt(1) == 3
                    val u = ContentUris.withAppendedId(
                        if (video) MediaStore.Video.Media.EXTERNAL_CONTENT_URI else MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id)
                    out.add(MediaItem(id, video, u))
                }
            }
        } catch (e: Throwable) {
        }
        return out
    }

    private fun loadThumb(item: MediaItem, px: Int): Bitmap? {
        return try {
            if (Build.VERSION.SDK_INT >= 29) {
                contentResolver.loadThumbnail(item.uri, android.util.Size(px, px), null)
            } else if (item.video) {
                val r = MediaMetadataRetriever()
                try {
                    r.setDataSource(this, item.uri)
                    r.getFrameAtTime(0)
                } finally {
                    try { r.release() } catch (t: Throwable) {}
                }
            } else {
                loadForEnhance(item.uri, px)
            }
        } catch (e: Throwable) {
            null
        }
    }

    private fun thumbKey(m: MediaItem): Long = m.id * 2 + (if (m.video) 1 else 0)

    private fun loadLastThumb() {
        val btn = galleryBtn ?: return
        thumbPool.execute {
            val m = queryMedia(1).firstOrNull() ?: return@execute
            val b = loadThumb(m, 240) ?: return@execute
            runOnUiThread { btn.setImageBitmap(b) }
        }
    }

    private fun chipBg(on: Boolean): GradientDrawable = GradientDrawable().apply {
        setColor(if (on) 0xFFFFFFFF.toInt() else 0x2EFFFFFF)
        cornerRadius = dpx(40f).toFloat()
    }

    private fun makeChip(label: String): TextView {
        val t = TextView(this)
        t.text = label
        t.textSize = 14f
        t.setTextColor(0xFFFFFFFF.toInt())
        t.typeface = Typeface.DEFAULT_BOLD
        t.gravity = Gravity.CENTER
        t.setPadding(dpx(14f), dpx(8f), dpx(14f), dpx(8f))
        t.background = chipBg(false)
        pressFx(t)
        return t
    }

    private fun setChip(t: TextView?, on: Boolean) {
        if (t == null) return
        t.background = chipBg(on)
        t.setTextColor(if (on) 0xFF000000.toInt() else 0xFFFFFFFF.toInt())
    }

    // круглая миниатюра последнего фото в нижнем углу, ставится рядом с кнопкой съёмки, не поверх других кнопок
    private fun addGalleryButton() {
        val size = dpx(54f)
        val iv = ImageView(this)
        iv.scaleType = ImageView.ScaleType.CENTER_CROP
        iv.setPadding(dpx(2f), dpx(2f), dpx(2f), dpx(2f))
        iv.background = GradientDrawable().apply {
            setColor(0xFF26262C.toInt())
            setStroke(dpx(2f), 0xCCFFFFFF.toInt())
            cornerRadius = dpx(16f).toFloat()
        }
        iv.outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: android.graphics.Outline) {
                outline.setRoundRect(0, 0, view.width, view.height, dpx(16f).toFloat())
            }
        }
        iv.clipToOutline = true
        iv.alpha = 0f
        val lp = FrameLayout.LayoutParams(size, size, Gravity.TOP or Gravity.START)
        lp.leftMargin = dpx(20f)
        lp.topMargin = resources.displayMetrics.heightPixels - dpx(120f)
        findViewById<ViewGroup>(android.R.id.content).addView(iv, lp)
        iv.setOnClickListener { onGalleryTap() }
        pressFx(iv)
        galleryBtn = iv
        ui.postDelayed({ placeGalleryBtn() }, 800)
    }

    private fun placeGalleryBtn() {
        val iv = galleryBtn ?: return
        val photo = findViewById<View>(R.id.photoBtn)
        val content = findViewById<ViewGroup>(android.R.id.content)
        val cl = IntArray(2)
        content.getLocationOnScreen(cl)
        val pl = IntArray(2)
        photo.getLocationOnScreen(pl)
        val size = dpx(54f)
        val cy = pl[1] - cl[1] + photo.height / 2
        val blockers = ArrayList<View>()
        blockers.add(photo); blockers.add(recBtn); blockers.add(flipBtn); blockers.add(qualityPill); blockers.add(fpsPill)
        blockers.add(nightBtn); blockers.add(dayBtn); blockers.add(aiBtn)
        if (this::focusBtn.isInitialized) { blockers.add(focusBtn); blockers.add(stabBtn) }
        fun hits(left: Int): Boolean {
            val r = Rect(left - dpx(6f), cy - size / 2 - dpx(6f), left + size + dpx(6f), cy + size / 2 + dpx(6f))
            for (v in blockers) {
                if (v.visibility != View.VISIBLE || v.width == 0) continue
                val l = IntArray(2)
                v.getLocationOnScreen(l)
                val r2 = Rect(l[0] - cl[0], l[1] - cl[1], l[0] - cl[0] + v.width, l[1] - cl[1] + v.height)
                if (Rect.intersects(r, r2)) return true
            }
            return false
        }
        val leftX = dpx(20f)
        val rightX = content.width - dpx(20f) - size
        val x = if (!hits(leftX)) leftX else if (!hits(rightX)) rightX else leftX
        val lp = iv.layoutParams as FrameLayout.LayoutParams
        lp.leftMargin = x
        lp.topMargin = cy - size / 2
        iv.layoutParams = lp
        iv.animate().alpha(1f).setDuration(300).start()
    }

    private fun onGalleryTap() {
        if (!hasMediaPerm() && !askedGalleryPerm) {
            askedGalleryPerm = true
            pendingGallery = true
            galleryPerm.launch(mediaPerms())
            return
        }
        openGallery()
    }

    @Suppress("DEPRECATION")
    private fun openGallery() {
        if (galleryRoot != null) return
        val content = findViewById<ViewGroup>(android.R.id.content)
        val root = FrameLayout(this)
        root.setBackgroundColor(0xF2090A0E.toInt())
        root.isClickable = true

        val col = LinearLayout(this)
        col.orientation = LinearLayout.VERTICAL
        val bar = LinearLayout(this)
        bar.orientation = LinearLayout.HORIZONTAL
        bar.gravity = Gravity.CENTER_VERTICAL
        bar.setPadding(dpx(16f), dpx(14f), dpx(12f), dpx(10f))
        val title = TextView(this)
        title.text = "Галерея"
        title.textSize = 22f
        title.typeface = Typeface.DEFAULT_BOLD
        title.setTextColor(0xFFFFFFFF.toInt())
        bar.addView(title, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val cl = makeChip("Последние")
        val ca = makeChip("Все")
        val cx = makeChip("✕")
        chipLast = cl
        chipAll = ca
        galleryAll = false
        setChip(cl, true)
        setChip(ca, false)
        val gap = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        gap.leftMargin = dpx(8f)
        bar.addView(cl, gap)
        bar.addView(ca, LinearLayout.LayoutParams(gap))
        bar.addView(cx, LinearLayout.LayoutParams(gap))
        col.addView(bar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        val cols = if (resources.displayMetrics.widthPixels > dpx(700f)) 5 else 3
        val grid = GridView(this)
        grid.numColumns = cols
        grid.horizontalSpacing = dpx(2f)
        grid.verticalSpacing = dpx(2f)
        grid.stretchMode = GridView.STRETCH_COLUMN_WIDTH
        grid.setSelector(android.R.color.transparent)
        grid.overScrollMode = View.OVER_SCROLL_NEVER
        grid.clipToPadding = false
        grid.isFastScrollEnabled = true
        val adapter = object : BaseAdapter() {
            override fun getCount() = galleryItems.size
            override fun getItem(p: Int): Any = galleryItems[p]
            override fun getItemId(p: Int) = p.toLong()
            override fun getView(p: Int, cv: View?, parent: ViewGroup?): View {
                val ctx = this@MainActivity
                val cell = (cv as? SquareFrame) ?: SquareFrame(ctx).also { f ->
                    val iv = ImageView(ctx)
                    iv.scaleType = ImageView.ScaleType.CENTER_CROP
                    iv.setBackgroundColor(0xFF1B1C22.toInt())
                    f.addView(iv, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
                    val bd = TextView(ctx)
                    bd.text = "▶"
                    bd.textSize = 12f
                    bd.setTextColor(0xFFFFFFFF.toInt())
                    bd.setPadding(dpx(7f), dpx(2f), dpx(7f), dpx(3f))
                    bd.background = GradientDrawable().apply {
                        setColor(0x99000000.toInt())
                        cornerRadius = dpx(20f).toFloat()
                    }
                    val blp = FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM or Gravity.START)
                    blp.leftMargin = dpx(6f)
                    blp.bottomMargin = dpx(6f)
                    f.addView(bd, blp)
                }
                val iv = cell.getChildAt(0) as ImageView
                val badge = cell.getChildAt(1)
                val m = galleryItems[p]
                badge.visibility = if (m.video) View.VISIBLE else View.GONE
                val key = thumbKey(m)
                iv.tag = key
                iv.animate().cancel()
                val c = thumbCache.get(key)
                if (c != null) {
                    iv.setImageBitmap(c)
                    iv.alpha = 1f
                } else {
                    iv.setImageDrawable(null)
                    iv.alpha = 0f
                    thumbPool.execute {
                        if ((iv.tag as? Long) != key) return@execute
                        val b = loadThumb(m, 320) ?: return@execute
                        thumbCache.put(key, b)
                        runOnUiThread {
                            if ((iv.tag as? Long) == key) {
                                iv.setImageBitmap(b)
                                iv.animate().alpha(1f).setDuration(160).start()
                            }
                        }
                    }
                }
                return cell
            }
        }
        galleryAdapter = adapter
        grid.adapter = adapter
        grid.setOnItemClickListener { _, _, pos, _ -> openViewer(pos) }
        col.addView(grid, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        root.addView(col, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        val empty = TextView(this)
        empty.textSize = 16f
        empty.gravity = Gravity.CENTER
        empty.setTextColor(0xB3FFFFFF.toInt())
        empty.visibility = View.GONE
        galleryEmpty = empty
        root.addView(empty, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER))

        cl.setOnClickListener { if (galleryAll) { galleryAll = false; setChip(cl, true); setChip(ca, false); reloadGallery() } }
        ca.setOnClickListener { if (!galleryAll) { galleryAll = true; setChip(cl, false); setChip(ca, true); reloadGallery() } }
        cx.setOnClickListener { closeGallery() }

        root.setOnApplyWindowInsetsListener { _, ins ->
            bar.setPadding(dpx(16f), ins.systemWindowInsetTop + dpx(14f), dpx(12f), dpx(10f))
            grid.setPadding(0, 0, 0, ins.systemWindowInsetBottom)
            ins
        }
        content.addView(root, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        root.requestApplyInsets()
        galleryRoot = root
        backCb.isEnabled = true
        root.alpha = 0f
        root.scaleX = 1.04f
        root.scaleY = 1.04f
        root.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(220).setInterpolator(DecelerateInterpolator()).start()
        reloadGallery()
    }

    private fun reloadGallery() {
        val limit = if (galleryAll) 0 else 90
        Thread {
            val list = queryMedia(limit)
            runOnUiThread {
                if (galleryRoot == null) return@runOnUiThread
                galleryItems = list
                galleryAdapter?.notifyDataSetChanged()
                val e = galleryEmpty
                if (e != null) {
                    if (list.isEmpty()) {
                        if (hasMediaPerm()) {
                            e.text = "Пока пусто"
                            e.setOnClickListener(null)
                        } else {
                            e.text = "Нет доступа к фото\nНажми, чтобы разрешить"
                            e.setOnClickListener { galleryPerm.launch(mediaPerms()) }
                        }
                        e.visibility = View.VISIBLE
                    } else {
                        e.visibility = View.GONE
                    }
                }
            }
        }.start()
    }

    private fun closeGallery() {
        val r = galleryRoot ?: return
        galleryRoot = null
        viewerRoot = null
        viewerImg = null
        viewerPlay = null
        viewerCount = null
        try { viewerVideo?.stopPlayback() } catch (t: Throwable) {}
        viewerVideo = null
        viewerRow = null
        viewerSeq++
        backCb.isEnabled = false
        r.animate().alpha(0f).setDuration(180).withEndAction { (r.parent as? ViewGroup)?.removeView(r) }.start()
        loadLastThumb()
    }

    private fun closeViewer() {
        val v = viewerRoot ?: return
        viewerRoot = null
        viewerImg = null
        viewerPlay = null
        viewerCount = null
        try { viewerVideo?.stopPlayback() } catch (t: Throwable) {}
        viewerVideo = null
        viewerRow = null
        viewerSeq++
        v.animate().alpha(0f).setDuration(150).withEndAction { (v.parent as? ViewGroup)?.removeView(v) }.start()
    }

    // видео смотрим прямо в галерее камеры, без других приложений
    private fun stopViewerVideo() {
        val vid = viewerVideo ?: return
        try { vid.stopPlayback() } catch (t: Throwable) {}
        vid.visibility = View.GONE
        viewerRow?.visibility = View.VISIBLE
        val m = galleryItems.getOrNull(viewerIndex)
        viewerPlay?.visibility = if (m != null && m.video) View.VISIBLE else View.GONE
    }

    private fun playViewerVideo(m: MediaItem) {
        val vid = viewerVideo ?: return
        if (vid.visibility == View.VISIBLE) return
        viewerPlay?.visibility = View.GONE
        viewerRow?.visibility = View.GONE
        vid.visibility = View.VISIBLE
        vid.setOnPreparedListener { mp ->
            mp.isLooping = false
            vid.start()
        }
        vid.setOnCompletionListener { stopViewerVideo() }
        vid.setOnErrorListener { _, _, _ ->
            stopViewerVideo()
            Toast.makeText(this, "Не получилось воспроизвести это видео", Toast.LENGTH_SHORT).show()
            true
        }
        vid.setVideoURI(m.uri)
    }

    private fun openExternal(m: MediaItem) {
        try {
            val i = Intent(Intent.ACTION_VIEW)
            i.setDataAndType(m.uri, if (m.video) "video/*" else "image/*")
            i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            startActivity(i)
        } catch (e: Throwable) {
            Toast.makeText(this, "Не нашёл приложение, чтобы открыть", Toast.LENGTH_SHORT).show()
        }
    }

    @Suppress("DEPRECATION")
    private fun openViewer(index: Int) {
        val root = galleryRoot ?: return
        if (viewerRoot != null) return
        val v = FrameLayout(this)
        v.setBackgroundColor(0xFF000000.toInt())
        v.isClickable = true
        val img = ImageView(this)
        img.scaleType = ImageView.ScaleType.FIT_CENTER
        v.addView(img, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        val vid = VideoView(this)
        vid.visibility = View.GONE
        v.addView(vid, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT, Gravity.CENTER))
        val mc = MediaController(this)
        mc.setAnchorView(vid)
        vid.setMediaController(mc)
        val play = TextView(this)
        play.text = "▶"
        play.textSize = 30f
        play.gravity = Gravity.CENTER
        play.setTextColor(0xFFFFFFFF.toInt())
        play.background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(0x88000000.toInt())
        }
        v.addView(play, FrameLayout.LayoutParams(dpx(80f), dpx(80f), Gravity.CENTER))
        val back = makeChip("←")
        val blp = FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.START)
        blp.leftMargin = dpx(14f)
        blp.topMargin = dpx(14f)
        v.addView(back, blp)

        val row = LinearLayout(this)
        row.orientation = LinearLayout.HORIZONTAL
        row.gravity = Gravity.CENTER_VERTICAL
        row.setPadding(dpx(16f), dpx(10f), dpx(16f), dpx(26f))
        val count = TextView(this)
        count.textSize = 14f
        count.setTextColor(0xB3FFFFFF.toInt())
        row.addView(count, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val enh = makeChip("✨ Улучшить")
        row.addView(enh)
        v.addView(row, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM))

        back.setOnClickListener { closeViewer() }
        enh.setOnClickListener {
            val m = galleryItems.getOrNull(viewerIndex) ?: return@setOnClickListener
            if (aiBusy) {
                Toast.makeText(this, "Подожди, ИИ ещё обрабатывает прошлый файл", Toast.LENGTH_SHORT).show()
            } else {
                closeGallery()
                Toast.makeText(this, "ИИ улучшает, процент виден на экране камеры, результат будет в галерее", Toast.LENGTH_LONG).show()
                enhancePicked(m.uri)
            }
        }
        val gd = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onFling(e1: MotionEvent?, e2: MotionEvent, vx: Float, vy: Float): Boolean {
                if (abs(vx) > abs(vy) && abs(vx) > 700f) {
                    if (vx < 0) showViewerItem(viewerIndex + 1, 1) else showViewerItem(viewerIndex - 1, -1)
                    return true
                }
                return false
            }
            override fun onSingleTapUp(e: MotionEvent): Boolean {
                val m = galleryItems.getOrNull(viewerIndex)
                if (m != null && m.video) playViewerVideo(m)
                return true
            }
        })
        v.setOnTouchListener { _, e -> gd.onTouchEvent(e); true }
        v.setOnApplyWindowInsetsListener { _, ins ->
            val lp = back.layoutParams as FrameLayout.LayoutParams
            lp.topMargin = ins.systemWindowInsetTop + dpx(14f)
            back.layoutParams = lp
            row.setPadding(dpx(16f), dpx(10f), dpx(16f), ins.systemWindowInsetBottom + dpx(26f))
            ins
        }
        root.addView(v, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        v.requestApplyInsets()
        viewerRoot = v
        viewerImg = img
        viewerPlay = play
        viewerCount = count
        viewerVideo = vid
        viewerRow = row
        v.alpha = 0f
        v.animate().alpha(1f).setDuration(160).start()
        showViewerItem(index, 0)
    }

    private fun showViewerItem(i: Int, dir: Int) {
        val items = galleryItems
        if (i < 0 || i >= items.size) return
        val img = viewerImg ?: return
        stopViewerVideo()
        viewerIndex = i
        val seq = ++viewerSeq
        val m = items[i]
        viewerCount?.text = (i + 1).toString() + " / " + items.size
        viewerPlay?.visibility = if (m.video) View.VISIBLE else View.GONE
        img.animate().cancel()
        if (dir != 0) {
            img.translationX = dir * img.width * 0.25f
            img.alpha = 0.2f
            img.animate().translationX(0f).alpha(1f).setDuration(200).start()
        } else {
            img.translationX = 0f
            img.alpha = 1f
        }
        img.setImageBitmap(thumbCache.get(thumbKey(m)))
        thumbPool.execute {
            val big = (if (m.video) loadThumb(m, 1280) else loadForEnhance(m.uri, 2048)) ?: return@execute
            runOnUiThread { if (seq == viewerSeq) viewerImg?.setImageBitmap(big) }
        }
    }

    // ---------- Кнопки: фиксация фокуса и стабилизация ----------
    // 0 = нет, 1 = оптическая, 2 = электронная
    private fun stabKind(cam: Camera): Int {
        return try {
            val c2 = Camera2CameraInfo.from(cam.cameraInfo)
            val ois = c2.getCameraCharacteristic(CameraCharacteristics.LENS_INFO_AVAILABLE_OPTICAL_STABILIZATION)
            val eis = c2.getCameraCharacteristic(CameraCharacteristics.CONTROL_AVAILABLE_VIDEO_STABILIZATION_MODES)
            if (ois != null && ois.contains(CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE_ON)) 1
            else if (eis != null && eis.contains(CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_ON)) 2
            else 0
        } catch (e: Exception) {
            0
        }
    }

    private fun makeSideBtn(label: String): TextView {
        val t = TextView(this)
        t.text = label
        t.setTextSize(TypedValue.COMPLEX_UNIT_PX, aiBtn.textSize)
        t.gravity = aiBtn.gravity
        t.setTextColor(aiBtn.textColors)
        t.typeface = aiBtn.typeface
        t.setPadding(aiBtn.paddingLeft, aiBtn.paddingTop, aiBtn.paddingRight, aiBtn.paddingBottom)
        aiBtn.background?.constantState?.newDrawable()?.let { t.background = it.mutate() }
        return t
    }

    private fun addExtraButtons() {
        focusBtn = makeSideBtn("🎯")
        stabBtn = makeSideBtn("🛡")
        val parent = aiBtn.parent as? ViewGroup
        if (parent is LinearLayout) {
            val idx = parent.indexOfChild(aiBtn)
            fun copyLp(): ViewGroup.LayoutParams {
                val l = aiBtn.layoutParams
                return try {
                    l.javaClass.getConstructor(l.javaClass).newInstance(l) as ViewGroup.LayoutParams
                } catch (e: Throwable) {
                    ViewGroup.MarginLayoutParams(l)
                }
            }
            parent.addView(focusBtn, idx + 1, copyLp())
            parent.addView(stabBtn, idx + 2, copyLp())
        } else {
            // запасной вариант: отдельная колонка слева по центру экрана
            val d = resources.displayMetrics.density
            val col = LinearLayout(this)
            col.orientation = LinearLayout.VERTICAL
            val size = (50 * d).toInt()
            val gap = (10 * d).toInt()
            col.addView(focusBtn, LinearLayout.LayoutParams(size, size).apply { bottomMargin = gap })
            col.addView(stabBtn, LinearLayout.LayoutParams(size, size))
            val flp = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.START or Gravity.CENTER_VERTICAL
            )
            flp.leftMargin = (12 * d).toInt()
            findViewById<ViewGroup>(android.R.id.content).addView(col, flp)
        }
        focusBtn.setOnClickListener { toggleFocusLock() }
        stabBtn.setOnClickListener { toggleStab() }
        pressFx(focusBtn)
        pressFx(stabBtn)
    }

    private fun toggleFocusLock() {
        val cam = camera ?: return
        focusLocked = !focusLocked
        if (focusLocked) {
            val pt = previewView.meteringPointFactory.createPoint(previewView.width / 2f, previewView.height / 2f)
            cam.cameraControl.startFocusAndMetering(
                FocusMeteringAction.Builder(pt, FocusMeteringAction.FLAG_AF).disableAutoCancel().build()
            )
            showFocusRing(previewView.width / 2f, previewView.height / 2f)
            Toast.makeText(this, "Фокус зафиксирован. Тапни по предмету, чтобы навести на него", Toast.LENGTH_LONG).show()
        } else {
            cam.cameraControl.cancelFocusAndMetering()
            Toast.makeText(this, "Автофокус включён", Toast.LENGTH_SHORT).show()
        }
        updateModeUi()
        applyMode()
    }

    private fun toggleStab() {
        val cam = camera ?: return
        val kind = stabKind(cam)
        if (kind == 0) {
            Toast.makeText(this, "Этот телефон не поддерживает стабилизацию камеры", Toast.LENGTH_LONG).show()
            return
        }
        stabOn = !stabOn
        updateModeUi()
        applyMode()
        val how = if (kind == 1) "оптическая" else "электронная"
        Toast.makeText(this, if (stabOn) "Стабилизация включена ($how)" else "Стабилизация выключена", Toast.LENGTH_SHORT).show()
    }

    // ---------- ИИ-улучшение фото при зуме ----------
    private fun enhanceAsync(uri: Uri, zoom: Float, name: String) {
        if (zoom < 3f) return
        if (aiBusy) {
            Toast.makeText(this, "ИИ ещё обрабатывает прошлое фото", Toast.LENGTH_SHORT).show()
            return
        }
        aiBusy = true
        Toast.makeText(this, "ИИ улучшает фото, это займёт до минуты", Toast.LENGTH_LONG).show()
        Thread {
            var msg = ""
            try {
                val src = loadNative(uri, zoom)
                if (src == null) {
                    msg = "Для ИИ приблизь сильнее (примерно от 6x)"
                } else {
                    val big = superRes(src) { pr -> runOnUiThread { infoText.text = "ИИ " + pr + "%" } }
                    if (big == null) {
                        msg = "Нет модели esrgan.tflite в приложении"
                    } else {
                        saveJpeg(big, name + "_AI")
                        msg = "ИИ-фото готово: " + big.width + "x" + big.height
                    }
                }
            } catch (e: Throwable) {
                msg = "ИИ не получилось: " + e.message
            }
            runOnUiThread {
                aiBusy = false
                infoText.text = "зум до " + fmt(maxZ) + "x"
                loadLastThumb()
                Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
            }
        }.start()
    }

    // Фото уже растянуто камерой в zoom раз, возвращаем его к «родному» размеру сенсора (заодно это убирает шум)
    private fun loadNative(uri: Uri, zoom: Float): Bitmap? {
        val bo = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bo) }
        val w0 = bo.outWidth
        val h0 = bo.outHeight
        if (w0 <= 0 || h0 <= 0) return null
        val tw0 = (w0 / zoom).toInt()
        val th0 = (h0 / zoom).toInt()
        if (maxOf(tw0, th0) > 800 || minOf(tw0, th0) < 16) return null
        var sample = 1
        while (w0 / (sample * 2) >= tw0 && h0 / (sample * 2) >= th0) sample *= 2
        val o = BitmapFactory.Options().apply { inSampleSize = sample }
        var bmp = contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, o) } ?: return null
        val orient = contentResolver.openInputStream(uri)?.use {
            ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
        } ?: ExifInterface.ORIENTATION_NORMAL
        val deg = when (orient) {
            ExifInterface.ORIENTATION_ROTATE_90 -> 90f
            ExifInterface.ORIENTATION_ROTATE_180 -> 180f
            ExifInterface.ORIENTATION_ROTATE_270 -> 270f
            else -> 0f
        }
        if (deg != 0f) {
            val m = Matrix().apply { postRotate(deg) }
            bmp = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true)
        }
        val outW = if (deg == 90f || deg == 270f) th0 else tw0
        val outH = if (deg == 90f || deg == 270f) tw0 else th0
        return Bitmap.createScaledBitmap(bmp, outW.coerceAtLeast(16), outH.coerceAtLeast(16), true)
    }

    // ESRGAN x4 по плиткам с нахлёстом, чтобы не было швов
    private fun superRes(src: Bitmap, budgetMs: Long = 0L, progress: (Int) -> Unit): Bitmap? {
        val bytes = try { assets.open("esrgan.tflite").readBytes() } catch (e: Exception) { return null }
        val model = ByteBuffer.allocateDirect(bytes.size).order(ByteOrder.nativeOrder())
        model.put(bytes)
        model.rewind()
        val interp = Interpreter(model, Interpreter.Options().setNumThreads(4))
        try {
            val inShape = interp.getInputTensor(0).shape()
            val outShape = interp.getOutputTensor(0).shape()
            val n = inShape[1]
            val m = outShape[1]
            val sc = m / n
            if (sc < 2) return null
            val pad = 4
            val core = n - 2 * pad
            val w = src.width
            val h = src.height
            val pix = IntArray(w * h)
            src.getPixels(pix, 0, w, 0, 0, w, h)
            val out = Bitmap.createBitmap(w * sc, h * sc, Bitmap.Config.ARGB_8888)
            val inBuf = ByteBuffer.allocateDirect(n * n * 3 * 4).order(ByteOrder.nativeOrder())
            val outBuf = ByteBuffer.allocateDirect(m * m * 3 * 4).order(ByteOrder.nativeOrder())
            val cols = (w + core - 1) / core
            val rows = (h + core - 1) / core
            var done = 0
            val tStart = SystemClock.elapsedRealtime()
            var ty = 0
            while (ty < h) {
                var tx = 0
                while (tx < w) {
                    inBuf.rewind()
                    for (y in 0 until n) {
                        val sy = (ty - pad + y).coerceIn(0, h - 1)
                        for (x in 0 until n) {
                            val sx = (tx - pad + x).coerceIn(0, w - 1)
                            val px = pix[sy * w + sx]
                            inBuf.putFloat(((px shr 16) and 0xFF).toFloat())
                            inBuf.putFloat(((px shr 8) and 0xFF).toFloat())
                            inBuf.putFloat((px and 0xFF).toFloat())
                        }
                    }
                    outBuf.rewind()
                    interp.run(inBuf, outBuf)
                    outBuf.rewind()
                    val fb = outBuf.asFloatBuffer()
                    val cw = minOf(core, w - tx)
                    val ch = minOf(core, h - ty)
                    val tw = cw * sc
                    val th = ch * sc
                    val tile = IntArray(tw * th)
                    for (oy in 0 until th) {
                        for (ox in 0 until tw) {
                            val idx = ((pad * sc + oy) * m + (pad * sc + ox)) * 3
                            val r = fb.get(idx).toInt().coerceIn(0, 255)
                            val g = fb.get(idx + 1).toInt().coerceIn(0, 255)
                            val b = fb.get(idx + 2).toInt().coerceIn(0, 255)
                            tile[oy * tw + ox] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
                        }
                    }
                    out.setPixels(tile, 0, tw, tx * sc, ty * sc, tw, th)
                    done++
                    if (budgetMs > 0L && done == 3 && cols * rows > 8) {
                        val est = (SystemClock.elapsedRealtime() - tStart) / 3f * cols * rows
                        if (est > budgetMs * 1.1f) throw TooSlow(kotlin.math.sqrt(budgetMs / est))
                    }
                    if (done % 5 == 0) progress(done * 100 / (cols * rows))
                    tx += core
                }
                ty += core
            }
            return out
        } finally {
            interp.close()
        }
    }

    private fun saveJpeg(bmp: Bitmap, name: String) {
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
            put(MediaStore.Images.Media.RELATIVE_PATH, "DCIM/Camera")
        }
        val uri = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: return
        contentResolver.openOutputStream(uri)?.use { bmp.compress(Bitmap.CompressFormat.JPEG, 95, it) }
    }

    // ---------- окно «Улучшение качества» ----------
    private fun showEnhanceDialog() {
        val v = layoutInflater.inflate(R.layout.dialog_enhance, null)
        dlgProgress = v.findViewById(R.id.enProgress)
        dlgStatus = v.findViewById(R.id.enStatus)
        dlgPreview = v.findViewById(R.id.enPreview)
        dlgPick = v.findViewById(R.id.enPick)
        dlgPick?.text = "📁 Выбрать фото или видео"
        val sw = v.findViewById<SwitchCompat>(R.id.enAuto)
        sw.isChecked = aiOn
        sw.setOnCheckedChangeListener { _, on ->
            aiOn = on
            updateModeUi()
            applyMode()
        }
        dlgPick?.setOnClickListener {
            if (aiBusy) Toast.makeText(this, "Подожди, ИИ ещё обрабатывает прошлый файл", Toast.LENGTH_SHORT).show()
            else pickPhoto.launch(arrayOf("image/*", "video/*"))
        }
        val close = v.findViewById<View>(R.id.enClose)
        close.setOnClickListener { dlg?.dismiss() }
        pressFx(close)
        dlgPick?.let { pressFx(it) }
        val d = AlertDialog.Builder(this).setView(v).create()
        d.window?.setBackgroundDrawableResource(R.drawable.bg_dialog)
        d.setOnDismissListener {
            dlgProgress = null; dlgStatus = null; dlgPreview = null; dlgPick = null; dlg = null
        }
        dlg = d
        d.show()
        d.window?.setLayout((resources.displayMetrics.widthPixels * 0.9f).toInt(), ViewGroup.LayoutParams.WRAP_CONTENT)
        v.scaleX = 0.92f; v.scaleY = 0.92f; v.alpha = 0f
        v.animate().scaleX(1f).scaleY(1f).alpha(1f).setDuration(260)
            .setInterpolator(OvershootInterpolator(1.4f)).start()
        if (aiBusy) setEnhanceUi("ИИ ещё работает…", null, null)
    }

    private fun setEnhanceUi(status: String, progress: Int?, preview: Bitmap? = null) {
        dlgStatus?.let { it.text = status; it.visibility = View.VISIBLE }
        dlgProgress?.let {
            if (progress != null) { it.progress = progress; it.visibility = View.VISIBLE }
            else it.visibility = View.GONE
        }
        if (preview != null) dlgPreview?.let { it.setImageBitmap(preview); it.visibility = View.VISIBLE }
    }

    private fun enhancePicked(uri: Uri) {
        val type = contentResolver.getType(uri) ?: ""
        if (type.startsWith("video")) enhanceVideoPicked(uri) else enhancePhotoPicked(uri)
    }

    private fun enhancePhotoPicked(uri: Uri) {
        if (aiBusy) return
        aiBusy = true
        dlgPick?.alpha = 0.4f
        dlgPreview?.visibility = View.GONE
        setEnhanceUi("Открываю фото…", 0)
        Thread {
            var msg: String
            var prev: Bitmap? = null
            val t0 = SystemClock.elapsedRealtime()
            try {
                val cb: (Int) -> Unit = { pr ->
                    runOnUiThread {
                        setEnhanceUi("Убираю шум… " + pr + "%", pr)
                        infoText.text = "Фото " + pr + "%"
                    }
                }
                val src = loadForEnhance(uri, 1600, false)
                if (src == null) {
                    msg = "Не удалось открыть это фото"
                } else {
                    val res = NlmDenoise.run(src, cb)
                    saveJpeg(res, "ZoomCam_AI_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date()))
                    val ph = (600f * res.height / res.width).toInt().coerceAtLeast(1)
                    prev = Bitmap.createScaledBitmap(res, 600, ph, true)
                    val sec = (SystemClock.elapsedRealtime() - t0) / 1000
                    msg = "Готово за " + sec + " с! " + res.width + "x" + res.height + ", шум убран, фото в галерее"
                }
            } catch (e: Throwable) {
                msg = "Не получилось: " + e.message
            }
            runOnUiThread {
                aiBusy = false
                dlgPick?.alpha = 1f
                infoText.text = "зум до " + fmt(maxZ) + "x"
                loadLastThumb()
                setEnhanceUi(msg, null, prev)
                Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
            }
        }.start()
    }

    private fun enhanceVideoPicked(uri: Uri) {
        if (aiBusy) return
        aiBusy = true
        dlgPick?.alpha = 0.4f
        dlgPreview?.visibility = View.GONE
        setEnhanceUi("Открываю видео…", 0)
        Thread {
            var msg: String
            var prev: Bitmap? = null
            val t0 = SystemClock.elapsedRealtime()
            try {
                var lastPr = -1
                val out = enhanceVideo(uri) { pr ->
                    if (pr != lastPr) {
                        lastPr = pr
                        val sec = (SystemClock.elapsedRealtime() - t0) / 1000
                        runOnUiThread {
                            setEnhanceUi("Улучшаю видео… " + pr + "% (" + sec + " с)", pr)
                            infoText.text = "Видео " + pr + "%"
                        }
                    }
                }
                prev = videoThumb(out)
                val sec = (SystemClock.elapsedRealtime() - t0) / 1000
                msg = "Видео готово за " + sec + " с: " + lastVideoInfo
            } catch (e: Throwable) {
                msg = "Видео не получилось: " + e.message
            }
            runOnUiThread {
                aiBusy = false
                dlgPick?.alpha = 1f
                infoText.text = "зум до " + fmt(maxZ) + "x"
                loadLastThumb()
                setEnhanceUi(msg, null, prev)
                Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
            }
        }.start()
    }

    private fun videoThumb(uri: Uri): Bitmap? {
        val r = MediaMetadataRetriever()
        return try {
            r.setDataSource(this, uri)
            val f = r.getFrameAtTime(300_000L, MediaMetadataRetriever.OPTION_CLOSEST_SYNC) ?: return null
            val ph = (600f * f.height / f.width).toInt().coerceAtLeast(1)
            Bitmap.createScaledBitmap(f, 600, ph, true)
        } catch (e: Throwable) {
            null
        } finally {
            try { r.release() } catch (e: Throwable) {}
        }
    }

    private fun align16(v: Float): Int = (Math.round(v / 16f) * 16).coerceAtLeast(16)

    // Видео: декодер -> GPU-фильтр (убирает шум и «квадраты», повышает резкость, увеличивает до ~1080p) -> кодер.
    // Всё на видеокарте, поэтому идёт быстрее, чем если считать на процессоре. Звук копируется как есть.
    private fun enhanceVideo(uri: Uri, onProgress: (Int) -> Unit): Uri {
        val ex = MediaExtractor()
        var audioEx: MediaExtractor? = null
        var decoder: MediaCodec? = null
        var encoder: MediaCodec? = null
        var gl: VideoGl? = null
        var muxer: MediaMuxer? = null
        var pfd: android.os.ParcelFileDescriptor? = null
        var outUri: Uri? = null
        var ok = false
        try {
            ex.setDataSource(this, uri, null)
            var vi = -1
            var ai = -1
            for (i in 0 until ex.trackCount) {
                val m = ex.getTrackFormat(i).getString(MediaFormat.KEY_MIME) ?: continue
                if (vi < 0 && m.startsWith("video/")) vi = i
                if (ai < 0 && m.startsWith("audio/")) ai = i
            }
            if (vi < 0) throw IllegalStateException("в файле нет видео")
            val vf = ex.getTrackFormat(vi)
            val mime = vf.getString(MediaFormat.KEY_MIME)!!
            val w = vf.getInteger(MediaFormat.KEY_WIDTH)
            val h = vf.getInteger(MediaFormat.KEY_HEIGHT)
            val rot = if (vf.containsKey(MediaFormat.KEY_ROTATION)) vf.getInteger(MediaFormat.KEY_ROTATION) else 0
            // декодер при выводе на поверхность сам поворачивает кадр, поэтому размер считаем уже с учётом поворота
            val swapWH = rot == 90 || rot == 270
            val dw = if (swapWH) h else w
            val dh = if (swapWH) w else h
            val durUs = if (vf.containsKey(MediaFormat.KEY_DURATION)) vf.getLong(MediaFormat.KEY_DURATION) else 0L
            val fps = (try {
                vf.getInteger(MediaFormat.KEY_FRAME_RATE)
            } catch (e: Throwable) {
                try { vf.getFloat(MediaFormat.KEY_FRAME_RATE).toInt() } catch (e2: Throwable) { 30 }
            }).coerceIn(15, 60)

            // битрейт исходника: результат не должен быть сжат сильнее оригинала, иначе детали пропадают
            var srcBr = try { vf.getInteger(MediaFormat.KEY_BIT_RATE).toLong() } catch (e: Throwable) { 0L }
            if (srcBr <= 0L && durUs > 0L) {
                val len = try { contentResolver.openAssetFileDescriptor(uri, "r")?.use { it.length } ?: 0L } catch (e: Throwable) { 0L }
                if (len > 0L) srcBr = len * 8_000_000L / durUs
            }
            // увеличиваем маленькое видео до ~1080p (максимум x2), большое оставляем как есть
            val s0 = (2880f / maxOf(dw, dh)).coerceIn(1.5f, 2f)
            var outW = dw
            var outH = dh
            var inSurf: Surface? = null
            for (s in listOf(s0, 1.25f, 1f)) {
                outW = align16(dw * s)
                outH = align16(dh * s)
                val br = maxOf((outW.toLong() * outH * fps * 0.28).toLong(), (srcBr * 1.25).toLong()).coerceIn(6_000_000L, 60_000_000L).toInt()
                val f = MediaFormat.createVideoFormat("video/avc", outW, outH)
                f.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
                f.setInteger(MediaFormat.KEY_BIT_RATE, br)
                f.setInteger(MediaFormat.KEY_FRAME_RATE, fps)
                f.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
                try {
                    val e = MediaCodec.createEncoderByType("video/avc")
                    try {
                        e.configure(f, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                        inSurf = e.createInputSurface()
                        encoder = e
                        break
                    } catch (t: Throwable) {
                        try { e.release() } catch (t2: Throwable) {}
                    }
                } catch (t: Throwable) {}
            }
            val enc = encoder ?: throw IllegalStateException("кодер видео не запустился")
            enc.start()

            val scale = outW.toFloat() / dw
            val g = VideoGl(inSurf!!, outW, outH, w, h, scale)
            gl = g
            lastVideoInfo = (if (g.quality) "полный фильтр" else "БЫСТРЫЙ фильтр (полный не запустился)") + ", " + dw + "x" + dh + " -> " + outW + "x" + outH
            val dec = MediaCodec.createDecoderByType(mime)
            decoder = dec
            dec.configure(vf, g.decSurface, null, 0)
            dec.start()
            ex.selectTrack(vi)

            if (ai >= 0) {
                audioEx = MediaExtractor().also {
                    it.setDataSource(this, uri, null)
                    it.selectTrack(ai)
                }
            }

            val name = "ZoomCam_AI_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                put(MediaStore.MediaColumns.MIME_TYPE, "video/mp4")
                put(MediaStore.Video.Media.RELATIVE_PATH, "DCIM/Camera")
                if (Build.VERSION.SDK_INT >= 29) put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
            val ou = contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
                ?: throw IllegalStateException("не удалось создать файл в галерее")
            outUri = ou
            pfd = contentResolver.openFileDescriptor(ou, "rw")
            val mx = MediaMuxer(pfd!!.fileDescriptor, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            mx.setOrientationHint(0)
            muxer = mx

            val dinfo = MediaCodec.BufferInfo()
            val einfo = MediaCodec.BufferInfo()
            var extractorDone = false
            var decDone = false
            var encDone = false
            var muxStarted = false
            var vTrack = -1
            var aTrack = -1
            while (!encDone) {
                if (!extractorDone) {
                    val ii = dec.dequeueInputBuffer(5000)
                    if (ii >= 0) {
                        val buf = dec.getInputBuffer(ii)!!
                        val sz = ex.readSampleData(buf, 0)
                        if (sz < 0) {
                            dec.queueInputBuffer(ii, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            extractorDone = true
                        } else {
                            dec.queueInputBuffer(ii, 0, sz, ex.sampleTime, 0)
                            ex.advance()
                        }
                    }
                }
                if (!decDone) {
                    val oi = dec.dequeueOutputBuffer(dinfo, 5000)
                    if (oi >= 0) {
                        val render = dinfo.size > 0
                        dec.releaseOutputBuffer(oi, render)
                        if (render) {
                            if (!g.awaitFrame()) throw IllegalStateException("декодер не отдал кадр")
                            g.draw(dinfo.presentationTimeUs * 1000L)
                            if (durUs > 0L) onProgress((dinfo.presentationTimeUs * 100L / durUs).toInt().coerceIn(0, 99))
                        }
                        if (dinfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                            decDone = true
                            enc.signalEndOfInputStream()
                        }
                    }
                }
                while (true) {
                    val ei = enc.dequeueOutputBuffer(einfo, if (decDone) 10_000L else 0L)
                    if (ei == MediaCodec.INFO_TRY_AGAIN_LATER) break
                    if (ei == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        vTrack = mx.addTrack(enc.outputFormat)
                        if (audioEx != null) {
                            aTrack = try { mx.addTrack(audioEx.getTrackFormat(ai)) } catch (t: Throwable) { -1 }
                        }
                        mx.start()
                        muxStarted = true
                    } else if (ei >= 0) {
                        val data = enc.getOutputBuffer(ei)!!
                        if (einfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) einfo.size = 0
                        if (einfo.size > 0 && muxStarted) {
                            data.position(einfo.offset)
                            data.limit(einfo.offset + einfo.size)
                            mx.writeSampleData(vTrack, data, einfo)
                        }
                        enc.releaseOutputBuffer(ei, false)
                        if (einfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                            encDone = true
                            break
                        }
                    }
                }
            }
            if (!muxStarted) throw IllegalStateException("видео получилось пустым")

            // звук копируем без изменений
            if (audioEx != null && aTrack >= 0) {
                val ab = ByteBuffer.allocate(1 shl 18)
                val abi = MediaCodec.BufferInfo()
                while (true) {
                    ab.clear()
                    val sz = audioEx.readSampleData(ab, 0)
                    if (sz < 0) break
                    val fl = if (audioEx.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC != 0) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0
                    abi.set(0, sz, audioEx.sampleTime, fl)
                    mx.writeSampleData(aTrack, ab, abi)
                    audioEx.advance()
                }
            }
            mx.stop()
            mx.release()
            muxer = null
            pfd?.close()
            pfd = null
            if (Build.VERSION.SDK_INT >= 29) {
                val cv = ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }
                contentResolver.update(ou, cv, null, null)
            }
            onProgress(100)
            ok = true
            return ou
        } finally {
            try { decoder?.stop() } catch (t: Throwable) {}
            try { decoder?.release() } catch (t: Throwable) {}
            try { encoder?.stop() } catch (t: Throwable) {}
            try { encoder?.release() } catch (t: Throwable) {}
            try { gl?.release() } catch (t: Throwable) {}
            try { muxer?.release() } catch (t: Throwable) {}
            try { pfd?.close() } catch (t: Throwable) {}
            try { ex.release() } catch (t: Throwable) {}
            try { audioEx?.release() } catch (t: Throwable) {}
            if (!ok && outUri != null) {
                try { contentResolver.delete(outUri, null, null) } catch (t: Throwable) {}
            }
        }
    }

    // для выбранного из галереи фото: уменьшаем до maxSide по длинной стороне (убирает «квадраты») и поворачиваем как надо
    private fun loadForEnhance(uri: Uri, maxSide: Int, clean: Boolean = false): Bitmap? {
        val decSide = if (clean) maxSide * 2 else maxSide
        val bo = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bo) }
        if (bo.outWidth <= 0 || bo.outHeight <= 0) return null
        var sample = 1
        while (maxOf(bo.outWidth, bo.outHeight) / (sample * 2) >= decSide) sample *= 2
        val o = BitmapFactory.Options().apply { inSampleSize = sample }
        var bmp = contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, o) } ?: return null
        val orient = try {
            contentResolver.openInputStream(uri)?.use {
                ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
            } ?: ExifInterface.ORIENTATION_NORMAL
        } catch (e: Exception) {
            ExifInterface.ORIENTATION_NORMAL
        }
        val deg = when (orient) {
            ExifInterface.ORIENTATION_ROTATE_90 -> 90f
            ExifInterface.ORIENTATION_ROTATE_180 -> 180f
            ExifInterface.ORIENTATION_ROTATE_270 -> 270f
            else -> 0f
        }
        if (deg != 0f) {
            val m = Matrix().apply { postRotate(deg) }
            bmp = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true)
        }
        if (clean) {
            // ИИ принимает шум за детали и рисует из него «червяков», поэтому сначала сильно чистим шум
            if (!bmp.isMutable) bmp = bmp.copy(Bitmap.Config.ARGB_8888, true) ?: return null
            cleanNoise(bmp, true)
        }
        val ms = maxOf(bmp.width, bmp.height)
        if (ms > maxSide) {
            val f = maxSide.toFloat() / ms
            bmp = Bitmap.createScaledBitmap(
                bmp, (bmp.width * f).toInt().coerceAtLeast(16), (bmp.height * f).toInt().coerceAtLeast(16), true)
        }
        return bmp
    }

    private fun setMode(m: Int) {
        mode = m
        updateModeUi()
        applyMode()
        val msg = when (m) {
            1 -> "Ночной режим: светлее и меньше шума"
            2 -> "Дневной режим: тёплая картинка"
            else -> "Обычный режим"
        }
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    }

    private fun setSel(v: View, on: Boolean) {
        v.isSelected = on
        val sc = if (on) 1.12f else 1f
        v.tag = sc
        v.animate().scaleX(sc).scaleY(sc).alpha(if (on) 1f else 0.85f)
            .setDuration(220).setInterpolator(OvershootInterpolator(2f)).start()
    }

    private fun updateModeUi() {
        setSel(nightBtn, mode == 1)
        setSel(dayBtn, mode == 2)
        setSel(aiBtn, aiOn)
        if (this::focusBtn.isInitialized) {
            setSel(focusBtn, focusLocked)
            setSel(stabBtn, stabOn)
        }
    }

    private fun updatePills() {
        qualityPill.text = qualityShort[qualityIndex] + " ▾"
        fpsPill.text = (if (fpsIndex == 1) "60" else "30") + " к/с ▾"
    }

    private fun updateZoomChips() {
        for ((v, z) in zoomChips) {
            val ref = if (z < 0f) maxZ else z
            val on = ref > 0f && abs(targetZ - ref) < 0.04f * ref
            if (v.isSelected != on) v.isSelected = on
        }
    }

    // лёгкая «пружинка» при нажатии, без нагрузки на процессор
    @SuppressLint("ClickableViewAccessibility")
    private fun pressFx(v: View) {
        v.setOnTouchListener { view, e ->
            val base = (view.tag as? Float) ?: 1f
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN ->
                    view.animate().scaleX(base * 0.88f).scaleY(base * 0.88f).setDuration(90)
                        .setInterpolator(DecelerateInterpolator()).start()
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL ->
                    view.animate().scaleX(base).scaleY(base).setDuration(260)
                        .setInterpolator(OvershootInterpolator(3f)).start()
            }
            false
        }
    }

    private fun showFocusRing(x: Float, y: Float) {
        val half = focusRing.width / 2f
        focusRing.animate().cancel()
        focusRing.translationX = x - half
        focusRing.translationY = y - half
        focusRing.alpha = 1f
        focusRing.scaleX = 1.6f
        focusRing.scaleY = 1.6f
        focusRing.animate().scaleX(1f).scaleY(1f).setStartDelay(0).setDuration(240)
            .setInterpolator(DecelerateInterpolator())
            .withEndAction {
                focusRing.animate().alpha(0f).setStartDelay(550).setDuration(300).start()
            }.start()
    }

    // Ночной/дневной режим работает сразу для превью, видео и фото (без перезапуска камеры)
    private fun applyMode() {
        val cam = camera ?: return
        try {
            // 1) яркость: ночью поднимаем экспозицию, иначе стандартная
            val es = cam.cameraInfo.exposureState
            if (es.isExposureCompensationSupported) {
                val r = es.exposureCompensationRange
                val idx = if (mode == 1) r.upper else 0
                cam.cameraControl.setExposureCompensationIndex(idx)
            }
            // 2) цвет и шумоподавление через Camera2
            val c2info = Camera2CameraInfo.from(cam.cameraInfo)
            val awbModes = c2info.getCameraCharacteristic(CameraCharacteristics.CONTROL_AWB_AVAILABLE_MODES)
            val nrModes = c2info.getCameraCharacteristic(CameraCharacteristics.NOISE_REDUCTION_AVAILABLE_NOISE_REDUCTION_MODES)
            val edgeModes = c2info.getCameraCharacteristic(CameraCharacteristics.EDGE_AVAILABLE_EDGE_MODES)
            val b = CaptureRequestOptions.Builder()
            // сглаживание при сильном зуме: сильное шумоподавление и без излишней резкости (она подчёркивает «квадраты»)
            if (fpsIndex == 0) {
                if (nrModes != null && nrModes.contains(CaptureRequest.NOISE_REDUCTION_MODE_HIGH_QUALITY)) {
                    b.setCaptureRequestOption(CaptureRequest.NOISE_REDUCTION_MODE, CaptureRequest.NOISE_REDUCTION_MODE_HIGH_QUALITY)
                }
                if (highZoom && edgeModes != null && edgeModes.contains(CaptureRequest.EDGE_MODE_FAST)) {
                    b.setCaptureRequestOption(CaptureRequest.EDGE_MODE, CaptureRequest.EDGE_MODE_FAST)
                }
            }
            if (mode == 2 && awbModes != null) {
                // «облачный дневной» баланс белого делает картинку теплее
                val warm = when {
                    awbModes.contains(CaptureRequest.CONTROL_AWB_MODE_CLOUDY_DAYLIGHT) -> CaptureRequest.CONTROL_AWB_MODE_CLOUDY_DAYLIGHT
                    awbModes.contains(CaptureRequest.CONTROL_AWB_MODE_SHADE) -> CaptureRequest.CONTROL_AWB_MODE_SHADE
                    else -> null
                }
                if (warm != null) b.setCaptureRequestOption(CaptureRequest.CONTROL_AWB_MODE, warm)
            }
            if (mode == 1 && nrModes != null && nrModes.contains(CaptureRequest.NOISE_REDUCTION_MODE_HIGH_QUALITY)) {
                b.setCaptureRequestOption(CaptureRequest.NOISE_REDUCTION_MODE, CaptureRequest.NOISE_REDUCTION_MODE_HIGH_QUALITY)
            }
            // плавный автофокус при записи видео: без «наезда» размытия, когда двигаешь телефон
            if (isRecording && !focusLocked) {
                val afModes = c2info.getCameraCharacteristic(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES)
                if (afModes != null && afModes.contains(CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO)) {
                    b.setCaptureRequestOption(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO)
                }
            }
            // стабилизация: оптическая, а если её нет, то электронная
            val kind = stabKind(cam)
            if (kind == 1) {
                b.setCaptureRequestOption(
                    CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE,
                    if (stabOn) CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE_ON else CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE_OFF
                )
            } else if (kind == 2) {
                b.setCaptureRequestOption(
                    CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE,
                    if (stabOn) CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_ON else CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_OFF
                )
            }
            Camera2CameraControl.from(cam.cameraControl).setCaptureRequestOptions(b.build())
        } catch (e: Exception) {
            // если телефон не поддерживает что-то из этого, просто пропускаем
        }
    }

    private fun setRecUi(on: Boolean) {
        recBtn.isSelected = on
        for (v in listOf<View>(qualityPill, fpsPill, flipBtn)) {
            v.isEnabled = !on
            v.animate().alpha(if (on) 0.35f else 1f).setDuration(200).start()
        }
        recPulse?.cancel()
        if (on) {
            recPulse = ValueAnimator.ofFloat(1f, 0.35f).apply {
                duration = 650
                repeatCount = ValueAnimator.INFINITE
                repeatMode = ValueAnimator.REVERSE
                addUpdateListener { timerText.alpha = it.animatedValue as Float }
                start()
            }
        } else {
            recPulse = null
            timerText.alpha = 1f
            timerText.text = ""
        }
    }

    private fun setTarget(z: Float, updateSeek: Boolean = true) {
        val lim = if (recording != null) minOf(maxZ, hwMaxZ) else maxZ
        if (recording != null && z > lim * 1.02f) {
            val now = System.currentTimeMillis()
            if (now - lastHint > 2500) {
                lastHint = now
                Toast.makeText(this, "Во время записи зум только до " + fmt(lim) + "x", Toast.LENGTH_SHORT).show()
            }
        }
        targetZ = z.coerceIn(minZ, maxOf(minZ, lim))
        if (updateSeek) zoomSeek.progress = zoomToSeek(targetZ)
        updateZoomChips()
    }

    private fun seekToZoom(p: Int): Float =
        if (maxZ <= minZ) minZ else minZ * (maxZ / minZ).pow(p / 1000f)

    private fun zoomToSeek(z: Float): Int =
        if (maxZ <= minZ) 0 else (ln(z / minZ) / ln(maxZ / minZ) * 1000f).toInt().coerceIn(0, 1000)

    private fun fmt(z: Float) = if (z >= 10f) z.toInt().toString() else String.format(Locale.US, "%.1f", z)
}

private class TooSlow(val factor: Float) : Exception("слишком долго")

// GPU-конвейер для видео: 1) убираем шум и «квадраты» сжатия (с сохранением краёв) и увеличиваем,
// 2) смешиваем с прошлым кадром там, где картинка неподвижна (меньше мерцания и «каши»), 3) умная резкость.
private val GL_PREC = "#ifdef GL_FRAGMENT_PRECISION_HIGH\nprecision highp float;\n#else\nprecision mediump float;\n#endif\n"
private val GL_VS_EXT = "uniform mat4 uTm;attribute vec4 aPos;attribute vec2 aTex;varying vec2 vTex;" +
    "void main(){gl_Position=aPos;vTex=(uTm*vec4(aTex,0.0,1.0)).xy;}"
private val GL_VS_2D = "attribute vec4 aPos;attribute vec2 aTex;varying vec2 vTex;" +
    "void main(){gl_Position=aPos;vTex=aTex;}"
private val GL_FS_FAST = "#extension GL_OES_EGL_image_external : require\n" + GL_PREC +
    "varying vec2 vTex;uniform samplerExternalOES sTex;" +
    "uniform vec2 uPx;uniform float uDen;uniform float uSharp;" +
    "void main(){" +
    "vec3 c=texture2D(sTex,vTex).rgb;vec3 acc=c;float ws=1.0;vec3 mean=c;" +
    "for(int i=-1;i<=1;i++){for(int j=-1;j<=1;j++){" +
    "if(i==0&&j==0)continue;" +
    "vec3 s=texture2D(sTex,vTex+vec2(float(i),float(j))*uPx).rgb;" +
    "vec3 d=s-c;float w=exp(-dot(d,d)*uDen);acc+=s*w;ws+=w;mean+=s;}}" +
    "vec3 den=acc/ws;vec3 blur=mean/9.0;" +
    "vec3 o=den+(den-blur)*uSharp;" +
    "float l=dot(o,vec3(0.299,0.587,0.114));" +
    "o=mix(vec3(l),o,1.08);o=(o-0.5)*1.04+0.5;" +
    "gl_FragColor=vec4(clamp(o,0.0,1.0),1.0);}"
private val GL_FS_S1 = "#extension GL_OES_EGL_image_external : require\n" + GL_PREC +
    "varying vec2 vTex;uniform samplerExternalOES sTex;uniform vec2 uPx;uniform float uDen;" +
    "void main(){" +
    "vec3 c=texture2D(sTex,vTex).rgb;vec3 acc=c;float ws=1.0;vec3 wide=c;" +
    "for(int i=-1;i<=1;i++){for(int j=-1;j<=1;j++){" +
    "if(i==0&&j==0)continue;" +
    "vec2 o=vec2(float(i),float(j));" +
    "vec3 s=texture2D(sTex,vTex+o*uPx).rgb;" +
    "vec3 d=s-c;float w=exp(-dot(d,d)*uDen);acc+=s*w;ws+=w;" +
    "vec3 s2=texture2D(sTex,vTex+o*uPx*2.0).rgb;" +
    "vec3 d2=s2-c;float w2=0.6*exp(-dot(d2,d2)*uDen*1.6);acc+=s2*w2;ws+=w2;" +
    "wide+=texture2D(sTex,vTex+o*uPx*4.0).rgb;}}" +
    "vec3 den=acc/ws;wide/=9.0;" +
    "vec3 k=vec3(0.299,0.587,0.114);" +
    "float y=dot(den,k);" +
    "vec3 chroma=mix(den-vec3(y),wide-vec3(dot(wide,k)),0.9);" +
    "gl_FragColor=vec4(clamp(vec3(y)+chroma,0.0,1.0),1.0);}"
private val GL_FS_T = GL_PREC +
    "varying vec2 vTex;uniform sampler2D sCur;uniform sampler2D sPrev;uniform vec2 uPx;uniform float uHas;" +
    "float lum(vec3 c){return dot(c,vec3(0.299,0.587,0.114));}" +
    "void main(){" +
    "vec3 c=texture2D(sCur,vTex).rgb;" +
    "if(uHas<0.5){gl_FragColor=vec4(c,1.0);return;}" +
    "vec3 p=texture2D(sPrev,vTex).rgb;" +
    "vec2 o=uPx*1.5;" +
    "float a=lum(texture2D(sCur,vTex+vec2(o.x,0.0)).rgb)+lum(texture2D(sCur,vTex-vec2(o.x,0.0)).rgb)" +
    "+lum(texture2D(sCur,vTex+vec2(0.0,o.y)).rgb)+lum(texture2D(sCur,vTex-vec2(0.0,o.y)).rgb)+lum(c)*2.0;" +
    "float b=lum(texture2D(sPrev,vTex+vec2(o.x,0.0)).rgb)+lum(texture2D(sPrev,vTex-vec2(o.x,0.0)).rgb)" +
    "+lum(texture2D(sPrev,vTex+vec2(0.0,o.y)).rgb)+lum(texture2D(sPrev,vTex-vec2(0.0,o.y)).rgb)+lum(p)*2.0;" +
    "float d=abs(a-b)/6.0;" +
    "float wy=0.92*(1.0-smoothstep(0.03,0.14,d));" +
    "float wc=0.97*(1.0-smoothstep(0.06,0.25,d));" +
    "float lc=lum(c);float lp=lum(p);" +
    "float y=mix(lc,lp,wy);" +
    "vec3 ch=mix(c-vec3(lc),p-vec3(lp),wc);" +
    "gl_FragColor=vec4(clamp(vec3(y)+ch,0.0,1.0),1.0);}"
private val GL_FS_S3 = GL_PREC +
    "varying vec2 vTex;uniform sampler2D sTex;uniform vec2 uPx;uniform float uSharp;" +
    "void main(){" +
    "vec3 e=texture2D(sTex,vTex).rgb;" +
    "vec3 b=texture2D(sTex,vTex+vec2(0.0,uPx.y)).rgb;" +
    "vec3 h=texture2D(sTex,vTex-vec2(0.0,uPx.y)).rgb;" +
    "vec3 d=texture2D(sTex,vTex-vec2(uPx.x,0.0)).rgb;" +
    "vec3 f=texture2D(sTex,vTex+vec2(uPx.x,0.0)).rgb;" +
    "vec3 mn=min(min(min(d,e),min(f,b)),h);vec3 mx=max(max(max(d,e),max(f,b)),h);" +
    "vec3 amp=sqrt(clamp(min(mn,1.0-mx)/(mx+0.0001),0.0,1.0));" +
    "vec3 w=amp*(-1.0/mix(8.0,5.0,uSharp));" +
    "vec3 o=((b+d+f+h)*w+e)/(4.0*w+1.0);" +
    "float l=dot(o,vec3(0.299,0.587,0.114));" +
    "o=mix(vec3(l),o,1.12);o=(o-0.5)*1.06+0.5;" +
    "gl_FragColor=vec4(clamp(o,0.0,1.0),1.0);}"

private class VideoGl(
    outSurface: Surface,
    private val outW: Int,
    private val outH: Int,
    private val srcW: Int,
    private val srcH: Int,
    private val scale: Float
) {
    private var dpy: EGLDisplay = EGL14.EGL_NO_DISPLAY
    private var ctx: EGLContext = EGL14.EGL_NO_CONTEXT
    private var surf: EGLSurface = EGL14.EGL_NO_SURFACE
    private var progFast = 0
    private var progS1 = 0
    private var progT = 0
    private var progS3 = 0
    private var tex = 0
    private val tm = FloatArray(16)
    private val frames = Semaphore(0)
    private val fboTex = IntArray(3)
    private val fbo = IntArray(3)
    private var flip = false
    private var hasPrev = false
    var quality = false
    var usedScale = 1f
    val st: SurfaceTexture
    val decSurface: Surface
    private val quad: FloatBuffer = ByteBuffer.allocateDirect(16 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer().apply {
        put(floatArrayOf(-1f, -1f, 0f, 0f, 1f, -1f, 1f, 0f, -1f, 1f, 0f, 1f, 1f, 1f, 1f, 1f))
        position(0)
    }

    init {
        dpy = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        val ver = IntArray(2)
        EGL14.eglInitialize(dpy, ver, 0, ver, 1)
        val attrs = intArrayOf(
            EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8, EGL14.EGL_BLUE_SIZE, 8, EGL14.EGL_ALPHA_SIZE, 8,
            EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT, 0x3142, 1, EGL14.EGL_NONE
        )
        val cfgs = arrayOfNulls<EGLConfig>(1)
        val n = IntArray(1)
        EGL14.eglChooseConfig(dpy, attrs, 0, cfgs, 0, 1, n, 0)
        ctx = EGL14.eglCreateContext(dpy, cfgs[0], EGL14.EGL_NO_CONTEXT, intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE), 0)
        surf = EGL14.eglCreateWindowSurface(dpy, cfgs[0], outSurface, intArrayOf(EGL14.EGL_NONE), 0)
        EGL14.eglMakeCurrent(dpy, surf, surf, ctx)

        progFast = link(GL_VS_EXT, GL_FS_FAST)
        // сложный конвейер; если телефон его не тянет, останется быстрый вариант
        try {
            progS1 = link(GL_VS_EXT, GL_FS_S1)
            progT = link(GL_VS_2D, GL_FS_T)
            progS3 = link(GL_VS_2D, GL_FS_S3)
            for (k in 0..2) makeFbo(k)
            quality = true
        } catch (t: Throwable) {
            quality = false
        }

        val t = IntArray(1)
        GLES20.glGenTextures(1, t, 0)
        tex = t[0]
        GLES20.glBindTexture(0x8D65, tex)
        GLES20.glTexParameteri(0x8D65, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(0x8D65, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(0x8D65, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(0x8D65, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        st = SurfaceTexture(tex)
        st.setOnFrameAvailableListener(
            SurfaceTexture.OnFrameAvailableListener { frames.release() },
            android.os.Handler(android.os.Looper.getMainLooper())
        )
        decSurface = Surface(st)
    }

    private fun compile(type: Int, src: String): Int {
        val s = GLES20.glCreateShader(type)
        GLES20.glShaderSource(s, src)
        GLES20.glCompileShader(s)
        val ok = IntArray(1)
        GLES20.glGetShaderiv(s, GLES20.GL_COMPILE_STATUS, ok, 0)
        if (ok[0] == 0) {
            val log = GLES20.glGetShaderInfoLog(s)
            GLES20.glDeleteShader(s)
            throw RuntimeException("шейдер: " + log)
        }
        return s
    }

    private fun link(vs: String, fs: String): Int {
        val v = compile(GLES20.GL_VERTEX_SHADER, vs)
        val f = compile(GLES20.GL_FRAGMENT_SHADER, fs)
        val p = GLES20.glCreateProgram()
        GLES20.glAttachShader(p, v)
        GLES20.glAttachShader(p, f)
        GLES20.glLinkProgram(p)
        val ls = IntArray(1)
        GLES20.glGetProgramiv(p, GLES20.GL_LINK_STATUS, ls, 0)
        if (ls[0] == 0) throw RuntimeException("GL программа не собралась")
        return p
    }

    private fun makeFbo(k: Int) {
        val t = IntArray(1)
        GLES20.glGenTextures(1, t, 0)
        fboTex[k] = t[0]
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, t[0])
        GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, outW, outH, 0, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, null)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        val f = IntArray(1)
        GLES20.glGenFramebuffers(1, f, 0)
        fbo[k] = f[0]
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, f[0])
        GLES20.glFramebufferTexture2D(GLES20.GL_FRAMEBUFFER, GLES20.GL_COLOR_ATTACHMENT0, GLES20.GL_TEXTURE_2D, t[0], 0)
        val status = GLES20.glCheckFramebufferStatus(GLES20.GL_FRAMEBUFFER)
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
        if (status != GLES20.GL_FRAMEBUFFER_COMPLETE) throw RuntimeException("FBO не готов")
    }

    fun awaitFrame(): Boolean {
        if (!frames.tryAcquire(3, TimeUnit.SECONDS)) return false
        st.updateTexImage()
        st.getTransformMatrix(tm)
        return true
    }

    private fun bindQuad(p: Int) {
        val aPos = GLES20.glGetAttribLocation(p, "aPos")
        val aTex = GLES20.glGetAttribLocation(p, "aTex")
        quad.position(0)
        GLES20.glVertexAttribPointer(aPos, 2, GLES20.GL_FLOAT, false, 16, quad)
        GLES20.glEnableVertexAttribArray(aPos)
        quad.position(2)
        GLES20.glVertexAttribPointer(aTex, 2, GLES20.GL_FLOAT, false, 16, quad)
        GLES20.glEnableVertexAttribArray(aTex)
    }

    private fun loc(p: Int, name: String) = GLES20.glGetUniformLocation(p, name)

    fun draw(ptsNs: Long) {
        if (!quality) drawFast() else drawQuality()
        EGLExt.eglPresentationTimeANDROID(dpy, surf, ptsNs)
        EGL14.eglSwapBuffers(dpy, surf)
    }

    private fun drawFast() {
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
        GLES20.glViewport(0, 0, outW, outH)
        GLES20.glUseProgram(progFast)
        bindQuad(progFast)
        GLES20.glUniformMatrix4fv(loc(progFast, "uTm"), 1, false, tm, 0)
        GLES20.glUniform2f(loc(progFast, "uPx"), 1f / srcW, 1f / srcH)
        GLES20.glUniform1f(loc(progFast, "uDen"), 70f)
        GLES20.glUniform1f(loc(progFast, "uSharp"), 1.4f)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(0x8D65, tex)
        GLES20.glUniform1i(loc(progFast, "sTex"), 0)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
    }

    private fun drawQuality() {
        // проход 1: шумоподавление и увеличение -> fbo[0]
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fbo[0])
        GLES20.glViewport(0, 0, outW, outH)
        GLES20.glUseProgram(progS1)
        bindQuad(progS1)
        GLES20.glUniformMatrix4fv(loc(progS1, "uTm"), 1, false, tm, 0)
        GLES20.glUniform2f(loc(progS1, "uPx"), 1f / srcW, 1f / srcH)
        GLES20.glUniform1f(loc(progS1, "uDen"), 38f)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(0x8D65, tex)
        GLES20.glUniform1i(loc(progS1, "sTex"), 0)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)

        // проход 2: смешивание с прошлым результатом там, где нет движения
        val cur = if (flip) 2 else 1
        val prev = if (flip) 1 else 2
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fbo[cur])
        GLES20.glUseProgram(progT)
        bindQuad(progT)
        GLES20.glUniform2f(loc(progT, "uPx"), 1f / outW, 1f / outH)
        GLES20.glUniform1f(loc(progT, "uHas"), if (hasPrev) 1f else 0f)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, fboTex[0])
        GLES20.glUniform1i(loc(progT, "sCur"), 0)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE1)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, fboTex[prev])
        GLES20.glUniform1i(loc(progT, "sPrev"), 1)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)

        // проход 3: умная резкость -> окно кодера
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
        GLES20.glViewport(0, 0, outW, outH)
        GLES20.glUseProgram(progS3)
        bindQuad(progS3)
        GLES20.glUniform2f(loc(progS3, "uPx"), 1f / outW, 1f / outH)
        GLES20.glUniform1f(loc(progS3, "uSharp"), 1f)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, fboTex[cur])
        GLES20.glUniform1i(loc(progS3, "sTex"), 0)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)

        flip = !flip
        hasPrev = true
    }

    fun release() {
        try { decSurface.release() } catch (t: Throwable) {}
        try { st.release() } catch (t: Throwable) {}
        if (dpy != EGL14.EGL_NO_DISPLAY) {
            EGL14.eglMakeCurrent(dpy, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
            EGL14.eglDestroySurface(dpy, surf)
            EGL14.eglDestroyContext(dpy, ctx)
            EGL14.eglReleaseThread()
            EGL14.eglTerminate(dpy)
        }
        dpy = EGL14.EGL_NO_DISPLAY
    }
}

private data class MediaItem(val id: Long, val video: Boolean, val uri: Uri)

private class SquareFrame(ctx: android.content.Context) : FrameLayout(ctx) {
    override fun onMeasure(w: Int, h: Int) {
        super.onMeasure(w, w)
    }
}

// Шумоподавление фото (Non-Local Means): ищет похожие кусочки картинки рядом и усредняет их.
// Шум исчезает, а края и мелкие детали остаются. Нейросеть тут вредила: принимала зерно за детали.
private object NlmDenoise {
    private fun estimateSigma(y: FloatArray, w: Int, h: Int): Float {
        var sum = 0.0
        var n = 0L
        var j = 1
        while (j < h - 1) {
            var i = 1
            while (i < w - 1) {
                val k = j * w + i
                val v = y[k - w - 1] - 2f * y[k - w] + y[k - w + 1] - 2f * y[k - 1] + 4f * y[k] - 2f * y[k + 1] + y[k + w - 1] - 2f * y[k + w] + y[k + w + 1]
                sum += Math.abs(v).toDouble()
                n++
                i += 2
            }
            j += 2
        }
        if (n == 0L) return 10f
        return (Math.sqrt(Math.PI / 2.0) * sum / (6.0 * n.toDouble())).toFloat()
    }

    private fun bandWork(
        w: Int, h: Int, rr: FloatArray, gg: FloatArray, bb: FloatArray, out: IntArray,
        y0: Int, y1: Int, sr: Int, pr: Int, s: Float, hk: Float
    ) {
        val ya = Math.max(0, y0 - pr)
        val yb = Math.min(h, y1 + pr)
        val rows = yb - ya
        val brow = y1 - y0
        val d2 = FloatArray(rows * w)
        val tmp = FloatArray(rows * w)
        val aR = FloatArray(brow * w)
        val aG = FloatArray(brow * w)
        val aB = FloatArray(brow * w)
        val aW = FloatArray(brow * w)
        for (y in y0 until y1) {
            for (x in 0 until w) {
                val i = y * w + x
                val o = (y - y0) * w + x
                aR[o] = rr[i]
                aG[o] = gg[i]
                aB[o] = bb[i]
                aW[o] = 1f
            }
        }
        val pw = 2 * pr + 1
        val norm = 1f / (3f * pw * pw)
        val hh = hk * s
        val base = 2f * s * s
        val dmax = base + 9f * hh * hh
        val ln = 2048
        val lut = FloatArray(ln)
        val inv = ln / dmax
        for (i in 0 until ln) {
            val d = (i + 0.5f) / inv
            lut[i] = Math.exp((-Math.max(d - base, 0f) / (hh * hh)).toDouble()).toFloat()
        }
        for (dy in -sr..sr) {
            for (dx in -sr..sr) {
                if (dx == 0 && dy == 0) continue
                // квадраты разности цветов между точкой и её соседкой со сдвигом (dx, dy)
                for (y in ya until yb) {
                    val yy = y + dy
                    val ro = (y - ya) * w
                    if (yy < 0 || yy >= h) {
                        for (x in 0 until w) d2[ro + x] = 0f
                        continue
                    }
                    val ri = y * w
                    val rj = yy * w
                    for (x in 0 until w) {
                        val xx = x + dx
                        if (xx < 0 || xx >= w) {
                            d2[ro + x] = 0f
                        } else {
                            val a = rr[ri + x] - rr[rj + xx]
                            val b = gg[ri + x] - gg[rj + xx]
                            val c = bb[ri + x] - bb[rj + xx]
                            d2[ro + x] = a * a + b * b + c * c
                        }
                    }
                }
                // сумма по окну по горизонтали
                for (r in 0 until rows) {
                    val ro = r * w
                    var acc = 0f
                    var x = 0
                    while (x <= pr && x < w) {
                        acc += d2[ro + x]
                        x++
                    }
                    tmp[ro] = acc
                    for (xi in 1 until w) {
                        val add = xi + pr
                        val sub = xi - pr - 1
                        if (add < w) acc += d2[ro + add]
                        if (sub >= 0) acc -= d2[ro + sub]
                        tmp[ro + xi] = acc
                    }
                }
                // сумма по вертикали и накопление взвешенного среднего
                for (y in y0 until y1) {
                    val yy = y + dy
                    if (yy < 0 || yy >= h) continue
                    val r0 = Math.max(0, y - pr - ya)
                    val r1 = Math.min(rows - 1, y + pr - ya)
                    for (x in 0 until w) {
                        val xx = x + dx
                        if (xx < 0 || xx >= w) continue
                        var sum = 0f
                        for (r in r0..r1) sum += tmp[r * w + x]
                        val d = sum * norm
                        var idx = (d * inv).toInt()
                        if (idx < 0) idx = 0
                        if (idx >= ln) continue
                        val wt = lut[idx]
                        val o = (y - y0) * w + x
                        val j = yy * w + xx
                        aW[o] += wt
                        aR[o] += wt * rr[j]
                        aG[o] += wt * gg[j]
                        aB[o] += wt * bb[j]
                    }
                }
            }
        }
        for (y in y0 until y1) {
            for (x in 0 until w) {
                val o = (y - y0) * w + x
                val iw = 1f / aW[o]
                val r = Math.min(255, Math.max(0, Math.round(aR[o] * iw)))
                val g = Math.min(255, Math.max(0, Math.round(aG[o] * iw)))
                val b = Math.min(255, Math.max(0, Math.round(aB[o] * iw)))
                out[y * w + x] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
            }
        }
    }

    private fun runNlm(px: IntArray, w: Int, h: Int, progress: (Int) -> Unit): IntArray {
        val n = w * h
        val rr = FloatArray(n)
        val gg = FloatArray(n)
        val bb = FloatArray(n)
        val yy = FloatArray(n)
        for (i in 0 until n) {
            val p = px[i]
            rr[i] = ((p shr 16) and 255).toFloat()
            gg[i] = ((p shr 8) and 255).toFloat()
            bb[i] = (p and 255).toFloat()
            yy[i] = 0.299f * rr[i] + 0.587f * gg[i] + 0.114f * bb[i]
        }
        // зерно у нас крупное (камера растягивает снимок), поэтому оценку шума усиливаем
        var s = estimateSigma(yy, w, h) * 2.6f
        if (s < 8f) s = 8f
        if (s > 40f) s = 40f
        val out = IntArray(n)
        val band = 64
        val nb = (h + band - 1) / band
        val next = java.util.concurrent.atomic.AtomicInteger(0)
        val doneB = java.util.concurrent.atomic.AtomicInteger(0)
        val err = arrayOfNulls<Throwable>(1)
        val nth = Math.max(1, Math.min(4, Runtime.getRuntime().availableProcessors()))
        val fs = s
        val threads = Array(nth) {
            Thread {
                try {
                    while (true) {
                        val k = next.getAndIncrement()
                        if (k >= nb) break
                        val y0 = k * band
                        val y1 = Math.min(h, y0 + band)
                        bandWork(w, h, rr, gg, bb, out, y0, y1, 5, 3, fs, 1.6f)
                        val d = doneB.incrementAndGet()
                        if (d % 3 == 0 || d == nb) progress(d * 90 / nb)
                    }
                } catch (t: Throwable) {
                    err[0] = t
                }
            }
        }
        for (t in threads) t.start()
        for (t in threads) t.join()
        val e = err[0]
        if (e != null) throw e
        return out
    }

    private fun boxBlur(a: FloatArray, w: Int, h: Int, r: Int) {
        val t = FloatArray(w * h)
        for (y in 0 until h) {
            val ro = y * w
            var acc = 0f
            var cnt = 0
            var x = 0
            while (x <= r && x < w) {
                acc += a[ro + x]
                cnt++
                x++
            }
            t[ro] = acc / cnt
            for (xi in 1 until w) {
                if (xi + r < w) {
                    acc += a[ro + xi + r]
                    cnt++
                }
                if (xi - r - 1 >= 0) {
                    acc -= a[ro + xi - r - 1]
                    cnt--
                }
                t[ro + xi] = acc / cnt
            }
        }
        for (x in 0 until w) {
            var acc = 0f
            var cnt = 0
            var y = 0
            while (y <= r && y < h) {
                acc += t[y * w + x]
                cnt++
                y++
            }
            a[x] = acc / cnt
            for (yi in 1 until h) {
                if (yi + r < h) {
                    acc += t[(yi + r) * w + x]
                    cnt++
                }
                if (yi - r - 1 >= 0) {
                    acc -= t[(yi - r - 1) * w + x]
                    cnt--
                }
                a[yi * w + x] = acc / cnt
            }
        }
    }

    // цветной шум размываем сильно (глаз его не замечает), яркость слегка подчёркиваем
    private fun finish(px: IntArray, w: Int, h: Int) {
        val n = w * h
        val yv = FloatArray(n)
        val cb = FloatArray(n)
        val cr = FloatArray(n)
        for (i in 0 until n) {
            val p = px[i]
            val r = ((p shr 16) and 255).toFloat()
            val g = ((p shr 8) and 255).toFloat()
            val b = (p and 255).toFloat()
            val y = 0.299f * r + 0.587f * g + 0.114f * b
            yv[i] = y
            cb[i] = b - y
            cr[i] = r - y
        }
        boxBlur(cb, w, h, 6)
        boxBlur(cr, w, h, 6)
        boxBlur(cb, w, h, 6)
        boxBlur(cr, w, h, 6)
        val yb = yv.copyOf()
        boxBlur(yb, w, h, 1)
        boxBlur(yb, w, h, 1)
        for (i in 0 until n) {
            var y = yv[i] + 1.6f * (yv[i] - yb[i])
            if (y < 0f) y = 0f else if (y > 255f) y = 255f
            val r = y + cr[i]
            val b = y + cb[i]
            val g = (y - 0.299f * r - 0.114f * b) / 0.587f
            val ri = Math.min(255, Math.max(0, Math.round(r)))
            val gi = Math.min(255, Math.max(0, Math.round(g)))
            val bi = Math.min(255, Math.max(0, Math.round(b)))
            px[i] = (0xFF shl 24) or (ri shl 16) or (gi shl 8) or bi
        }
    }

    fun run(src: Bitmap, progress: (Int) -> Unit): Bitmap {
        val w = src.width
        val h = src.height
        val px = IntArray(w * h)
        src.getPixels(px, 0, w, 0, 0, w, h)
        val out = runNlm(px, w, h, progress)
        finish(out, w, h)
        progress(100)
        val res = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        res.setPixels(out, 0, w, 0, 0, w, h)
        return res
    }
}
