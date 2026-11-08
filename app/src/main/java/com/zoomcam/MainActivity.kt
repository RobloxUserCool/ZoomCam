package com.zoomcam

import android.Manifest
import android.annotation.SuppressLint
import android.content.ContentValues
import android.content.pm.PackageManager
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
import androidx.camera.camera2.interop.Camera2CameraInfo
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
            }
            override fun onError(e: ImageCaptureException) {
                Toast.makeText(this@MainActivity, "Ошибка фото: " + e.message, Toast.LENGTH_LONG).show()
            }
        })
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
