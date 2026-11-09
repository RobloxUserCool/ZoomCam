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
    private val pickPhoto = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
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
    private fun superRes(src: Bitmap, progress: (Int) -> Unit): Bitmap? {
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
        val sw = v.findViewById<SwitchCompat>(R.id.enAuto)
        sw.isChecked = aiOn
        sw.setOnCheckedChangeListener { _, on ->
            aiOn = on
            updateModeUi()
            applyMode()
        }
        dlgPick?.setOnClickListener {
            if (aiBusy) Toast.makeText(this, "Подожди, ИИ ещё обрабатывает прошлое фото", Toast.LENGTH_SHORT).show()
            else pickPhoto.launch("image/*")
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
        if (aiBusy) return
        aiBusy = true
        dlgPick?.alpha = 0.4f
        dlgPreview?.visibility = View.GONE
        setEnhanceUi("Открываю фото…", 0)
        Thread {
            var msg: String
            var prev: Bitmap? = null
            try {
                val src = loadForEnhance(uri, 800)
                if (src == null) {
                    msg = "Не удалось открыть это фото"
                } else {
                    val big = superRes(src) { pr ->
                        runOnUiThread {
                            setEnhanceUi("ИИ улучшает… " + pr + "%", pr)
                            infoText.text = "ИИ " + pr + "%"
                        }
                    }
                    if (big == null) {
                        msg = "Нет модели esrgan.tflite в приложении"
                    } else {
                        saveJpeg(big, "ZoomCam_AI_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date()))
                        val ph = (600f * big.height / big.width).toInt().coerceAtLeast(1)
                        prev = Bitmap.createScaledBitmap(big, 600, ph, true)
                        msg = "Готово! " + big.width + "x" + big.height + ", фото сохранено в галерею"
                    }
                }
            } catch (e: Throwable) {
                msg = "Не получилось: " + e.message
            }
            runOnUiThread {
                aiBusy = false
                dlgPick?.alpha = 1f
                infoText.text = "зум до " + fmt(maxZ) + "x"
                setEnhanceUi(msg, null, prev)
                Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
            }
        }.start()
    }

    // для выбранного из галереи фото: уменьшаем до maxSide по длинной стороне (убирает «квадраты») и поворачиваем как надо
    private fun loadForEnhance(uri: Uri, maxSide: Int): Bitmap? {
        val bo = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bo) }
        if (bo.outWidth <= 0 || bo.outHeight <= 0) return null
        var sample = 1
        while (maxOf(bo.outWidth, bo.outHeight) / (sample * 2) >= maxSide) sample *= 2
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
