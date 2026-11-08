package com.zoomcam

import android.Manifest
import android.annotation.SuppressLint
import android.content.ContentValues
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import android.os.Bundle
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CaptureRequest
import android.provider.MediaStore
import android.util.Range
import android.view.Choreographer
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.SeekBar
import android.widget.Spinner
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
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.pow

class MainActivity : AppCompatActivity() {

    private lateinit var previewView: PreviewView
    private lateinit var zoomLabel: TextView
    private lateinit var infoText: TextView
    private lateinit var timerText: TextView
    private lateinit var zoomSeek: SeekBar
    private lateinit var recBtn: Button
    private lateinit var flipBtn: Button
    private lateinit var qualitySpinner: Spinner
    private lateinit var fpsSpinner: Spinner
    private lateinit var nightBtn: Button
    private lateinit var dayBtn: Button
    private lateinit var aiBtn: Button

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
        maxZ = z.maxZoomRatio
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
                    camera?.cameraControl?.setZoomRatio(curZ)
                    lastApplied = curZ
                    zoomLabel.text = fmt(curZ) + "x"
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
        qualitySpinner = findViewById(R.id.qualitySpinner)
        fpsSpinner = findViewById(R.id.fpsSpinner)
        nightBtn = findViewById(R.id.nightBtn)
        dayBtn = findViewById(R.id.dayBtn)
        aiBtn = findViewById(R.id.aiBtn)

        previewView.scaleType = PreviewView.ScaleType.FIT_CENTER
        previewView.keepScreenOn = true

        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, qualities.map { it.first })
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        qualitySpinner.adapter = adapter
        qualitySpinner.setSelection(qualityIndex)
        qualitySpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                if (pos != qualityIndex) {
                    qualityIndex = pos
                    bindCamera()
                }
            }
            override fun onNothingSelected(p: AdapterView<*>?) {}
        }

        val fpsAdapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, listOf("30 к/с", "60 к/с"))
        fpsAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        fpsSpinner.adapter = fpsAdapter
        fpsSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                if (pos != fpsIndex) {
                    fpsIndex = pos
                    bindCamera()
                }
            }
            override fun onNothingSelected(p: AdapterView<*>?) {}
        }

        zoomSeek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) {
                if (fromUser) setTarget(seekToZoom(p), false)
            }
            override fun onStartTrackingTouch(s: SeekBar?) {}
            override fun onStopTrackingTouch(s: SeekBar?) {}
        })

        findViewById<Button>(R.id.z1).setOnClickListener { setTarget(1f) }
        findViewById<Button>(R.id.z5).setOnClickListener { setTarget(5f) }
        findViewById<Button>(R.id.z10).setOnClickListener { setTarget(10f) }
        findViewById<Button>(R.id.zmax).setOnClickListener { setTarget(maxZ) }

        flipBtn.setOnClickListener {
            lens = if (lens == CameraSelector.LENS_FACING_BACK)
                CameraSelector.LENS_FACING_FRONT else CameraSelector.LENS_FACING_BACK
            bindCamera()
        }
        nightBtn.setOnClickListener { setMode(if (mode == 1) 0 else 1) }
        dayBtn.setOnClickListener { setMode(if (mode == 2) 0 else 2) }
        aiBtn.setOnClickListener {
            aiOn = !aiOn
            updateModeUi()
            Toast.makeText(this, if (aiOn) "ИИ-улучшение фото: вкл" else "ИИ-улучшение фото: выкл", Toast.LENGTH_SHORT).show()
        }
        updateModeUi()

        recBtn.setOnClickListener { toggleRecord() }
        findViewById<Button>(R.id.photoBtn).setOnClickListener { takePhoto() }

        // щипок = зум, тап = фокус
        val scale = ScaleGestureDetector(this, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(d: ScaleGestureDetector): Boolean {
                setTarget(targetZ * d.scaleFactor.pow(1.5f))
                return true
            }
        })
        val tap = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onSingleTapUp(e: MotionEvent): Boolean {
                val pt = previewView.meteringPointFactory.createPoint(e.x, e.y)
                camera?.cameraControl?.startFocusAndMetering(FocusMeteringAction.Builder(pt).build())
                return true
            }
        })
        previewView.setOnTouchListener { _, e ->
            scale.onTouchEvent(e)
            tap.onTouchEvent(e)
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
            camera = p.bindToLifecycle(this, selector, preview, vc, ic)
            videoCapture = vc
            imageCapture = ic
            zoomLive = camera!!.cameraInfo.zoomState
            zoomLive!!.observe(this, zoomObs)
            zoomSeek.progress = 0
            applyMode()
        } catch (e: Exception) {
            if (fpsIndex == 1) {
                fpsIndex = 0
                fpsSpinner.setSelection(0)
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
                is VideoRecordEvent.Start -> setRecUi(true)
                is VideoRecordEvent.Status -> {
                    val s = ev.recordingStats.recordedDurationNanos / 1_000_000_000L
                    timerText.text = String.format(Locale.US, "● %02d:%02d", s / 60, s % 60)
                }
                is VideoRecordEvent.Finalize -> {
                    recording = null
                    setRecUi(false)
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
                if (aiOn && u != null) enhanceAsync(u, shotZoom, name)
            }
            override fun onError(e: ImageCaptureException) {
                Toast.makeText(this@MainActivity, "Ошибка фото: " + e.message, Toast.LENGTH_LONG).show()
            }
        })
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

    private fun updateModeUi() {
        nightBtn.alpha = if (mode == 1) 1f else 0.55f
        dayBtn.alpha = if (mode == 2) 1f else 0.55f
        aiBtn.alpha = if (aiOn) 1f else 0.55f
        aiBtn.backgroundTintList = android.content.res.ColorStateList.valueOf(
            if (aiOn) 0xFF2E7D32.toInt() else 0xFF444444.toInt())
        nightBtn.backgroundTintList = android.content.res.ColorStateList.valueOf(
            if (mode == 1) 0xFF3F51B5.toInt() else 0xFF444444.toInt())
        dayBtn.backgroundTintList = android.content.res.ColorStateList.valueOf(
            if (mode == 2) 0xFFFF9800.toInt() else 0xFF444444.toInt())
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
            if (highZoom && fpsIndex == 0) {
                if (nrModes != null && nrModes.contains(CaptureRequest.NOISE_REDUCTION_MODE_HIGH_QUALITY)) {
                    b.setCaptureRequestOption(CaptureRequest.NOISE_REDUCTION_MODE, CaptureRequest.NOISE_REDUCTION_MODE_HIGH_QUALITY)
                }
                if (edgeModes != null && edgeModes.contains(CaptureRequest.EDGE_MODE_FAST)) {
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
            Camera2CameraControl.from(cam.cameraControl).setCaptureRequestOptions(b.build())
        } catch (e: Exception) {
            // если телефон не поддерживает что-то из этого, просто пропускаем
        }
    }

    private fun setRecUi(on: Boolean) {
        recBtn.text = if (on) "СТОП" else "ЗАПИСЬ"
        qualitySpinner.isEnabled = !on
        fpsSpinner.isEnabled = !on
        flipBtn.isEnabled = !on
        if (!on) timerText.text = ""
    }

    private fun setTarget(z: Float, updateSeek: Boolean = true) {
        targetZ = z.coerceIn(minZ, maxZ)
        if (updateSeek) zoomSeek.progress = zoomToSeek(targetZ)
    }

    private fun seekToZoom(p: Int): Float =
        if (maxZ <= minZ) minZ else minZ * (maxZ / minZ).pow(p / 1000f)

    private fun zoomToSeek(z: Float): Int =
        if (maxZ <= minZ) 0 else (ln(z / minZ) / ln(maxZ / minZ) * 1000f).toInt().coerceIn(0, 1000)

    private fun fmt(z: Float) = if (z >= 10f) z.toInt().toString() else String.format(Locale.US, "%.1f", z)
}
