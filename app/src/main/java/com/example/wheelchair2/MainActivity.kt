package com.example.headposecontroller

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.example.headposecontroller.databinding.ActivityMainBinding
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.framework.image.MPImage
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.facelandmarker.FaceLandmarker
import com.google.mediapipe.tasks.vision.facelandmarker.FaceLandmarkerResult
import kotlin.math.abs
import kotlin.math.sqrt

class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding
    private lateinit var faceLandmarker: FaceLandmarker
    private lateinit var cameraExecutor: java.util.concurrent.ExecutorService

    // smoothing
    private var yawAvg = 0f
    private var pitchAvg = 0f
    private var rollAvg = 0f
    private val alpha = 0.25f

    // kalibrasi roll (auto-zero)
    private var rollOffset: Float? = null
    private var rollWarmupSum = 0f
    private var rollWarmupCount = 0
    private val ROLL_WARMUP_FRAMES = 12

    // Bluetooth
    private lateinit var bt: BtClient
    private val ESP32_MAC = "14:33:5C:64:A9:92" // GANTI ke MAC ESP32 kamu
    private var lastCmdSent = ""
    private var lastSentAt = 0L
    private val SEND_MIN_INTERVAL_MS = 150L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // permission BT connect (Android 12+)
        if (Build.VERSION.SDK_INT >= 31) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT)
                != PackageManager.PERMISSION_GRANTED
            ) {
                ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.BLUETOOTH_CONNECT), 99)
            }
        }

        cameraExecutor = java.util.concurrent.Executors.newSingleThreadExecutor()
        setupFaceLandmarker()

        // Bluetooth connect (pastikan sudah pair di Settings)
        bt = BtClient(this)
        bt.connectByMac(ESP32_MAC) { ok ->
            runOnUiThread { Log.d(TAG, if (ok) "BT connected" else "BT connect FAILED") }
        }

        if (allPermissionsGranted()) {
            startCamera()
        } else {
            ActivityCompat.requestPermissions(this, REQUIRED_PERMISSIONS, REQUEST_CODE_PERMISSIONS)
        }
    }

    private fun setupFaceLandmarker() {
        val baseOptions = BaseOptions.builder().setModelAssetPath("face_landmarker.task").build()
        val options = FaceLandmarker.FaceLandmarkerOptions.builder()
            .setBaseOptions(baseOptions)
            .setRunningMode(RunningMode.LIVE_STREAM)
            .setResultListener(this::returnLivestreamResult)
            .setErrorListener { e: RuntimeException ->
                Log.e(TAG, "MediaPipe Error (livestream):", e)
            }
            .setOutputFacialTransformationMatrixes(true)
            .setNumFaces(1)
            .build()

        faceLandmarker = FaceLandmarker.createFromOptions(this, options)
    }

    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        cameraProviderFuture.addListener({
            val cameraProvider: ProcessCameraProvider = cameraProviderFuture.get()

            val preview = Preview.Builder().build().also {
                it.setSurfaceProvider(binding.viewFinder.surfaceProvider)
            }

            val imageAnalyzer = ImageAnalysis.Builder()
                .setTargetAspectRatio(AspectRatio.RATIO_4_3)
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build().also { analyzer ->
                    analyzer.setAnalyzer(cameraExecutor) { imageProxy ->
                        try {
                            val bitmap = imageProxy.toBitmap()
                            if (bitmap != null) {
                                val mpImage = BitmapImageBuilder(bitmap).build()
                                val ts = imageProxy.imageInfo.timestamp.takeIf { it != 0L }
                                    ?: SystemClock.uptimeMillis()
                                faceLandmarker.detectAsync(mpImage, ts)
                            }
                        } catch (t: Throwable) {
                            Log.e(TAG, "Analyzer error", t)
                        } finally {
                            imageProxy.close()
                        }
                    }
                }

            val cameraSelector = CameraSelector.DEFAULT_FRONT_CAMERA
            try {
                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(this, cameraSelector, preview, imageAnalyzer)
            } catch (exc: Exception) {
                Log.e(TAG, "Use case binding failed", exc)
            }
        }, ContextCompat.getMainExecutor(this))
    }

    // callback MediaPipe livestream
    private fun returnLivestreamResult(result: FaceLandmarkerResult, input: MPImage) {
        val opt = result.facialTransformationMatrixes()
        if (!opt.isPresent || opt.get().isEmpty()) {
            runOnUiThread { binding.overlay.setResults(0f, 0f, 0f, "DIAM") }
            return
        }

        val m = opt.get()[0] // 4x4, COLUMN-MAJOR, length 16

        // Ambil rotasi 3x3 dari column-major
        val r00 = m[0];  val r10 = m[1];  val r20 = m[2]
        val r01 = m[4];  val r11 = m[5];  val r21 = m[6]
        val r02 = m[8];  val r12 = m[9];  val r22 = m[10]

        // Normalisasi kolom
        fun norm3(x: Float, y: Float, z: Float) = kotlin.math.sqrt((x*x + y*y + z*z).toDouble()).toFloat()
        var R00 = r00; var R10 = r10; var R20 = r20
        var R01 = r01; var R11 = r11; var R21 = r21
        var R02 = r02; var R12 = r12; var R22 = r22
        val c0 = norm3(R00, R10, R20); if (c0 > 1e-6f) { R00 /= c0; R10 /= c0; R20 /= c0 }
        val c1 = norm3(R01, R11, R21); if (c1 > 1e-6f) { R01 /= c1; R11 /= c1; R21 /= c1 }
        val c2 = norm3(R02, R12, R22); if (c2 > 1e-6f) { R02 /= c2; R12 /= c2; R22 /= c2 }

        val sy = kotlin.math.sqrt((R00 * R00 + R10 * R10).toDouble())
        val hasGimbal = sy <= 1e-6

        val pitchDeg = if (!hasGimbal)
            Math.toDegrees(kotlin.math.atan2(R21.toDouble(), R22.toDouble())).toFloat()
        else
            Math.toDegrees(kotlin.math.atan2((-R12).toDouble(), R11.toDouble())).toFloat()

        val yawDeg   = Math.toDegrees(kotlin.math.atan2((-R20).toDouble(), sy)).toFloat()
        var rollDeg  = Math.toDegrees(kotlin.math.atan2(R10.toDouble(), R00.toDouble())).toFloat()
        // Jika arah terasa terbalik, aktifkan:
        // rollDeg = -rollDeg

        // Auto-zero roll
        if (rollOffset == null) {
            rollWarmupSum += rollDeg
            rollWarmupCount++
            if (rollWarmupCount >= ROLL_WARMUP_FRAMES) {
                rollOffset = - (rollWarmupSum / rollWarmupCount)
            }
        }
        val rollCorrected = rollDeg + (rollOffset ?: 0f)

        // smoothing
        val yawSm   = ((1 - alpha) * yawAvg + alpha * yawDeg).also { yawAvg = it }
        val pitchSm = ((1 - alpha) * pitchAvg + alpha * pitchDeg).also { pitchAvg = it }
        val rollSm  = ((1 - alpha) * rollAvg + alpha * rollCorrected).also { rollAvg = it }

        // threshold & mapping (sesuaikan preferensimu)
        val TH_YAW = 15f
        val TH_PITCH = 12f
        val TH_ROLL = 15f

        val ay = abs(yawSm)
        val ap = abs(pitchSm)
        val ar = abs(rollSm)

        val command = when {
            ap > TH_PITCH && ap >= maxOf(ay, ar) -> if (pitchSm < 0) "MUNDUR" else "MAJU"
            ar > TH_ROLL -> if (rollSm < 0) "KIRI" else "KANAN"
            else -> "DIAM"
        }


        runOnUiThread {
            binding.overlay.setResults(yawSm, pitchSm, rollSm, command)
        }

        // Kirim ke ESP32 (F/B/L/R/S) dgn throttling
        val now = SystemClock.uptimeMillis()
        if (command != lastCmdSent && now - lastSentAt >= SEND_MIN_INTERVAL_MS) {
            val token = when (command) {
                "MAJU" -> "F"
                "MUNDUR" -> "B"
                "KIRI" -> "L"
                "KANAN" -> "R"
                else -> "S"
            }
            bt.sendCommand(token)
            lastCmdSent = command
            lastSentAt = now
        }
    }

    private fun allPermissionsGranted() = REQUIRED_PERMISSIONS.all {
        ContextCompat.checkSelfPermission(baseContext, it) == PackageManager.PERMISSION_GRANTED
    }

    override fun onDestroy() {
        super.onDestroy()
        cameraExecutor.shutdown()
        if (::faceLandmarker.isInitialized) faceLandmarker.close()
        if (::bt.isInitialized) bt.close()
    }

    companion object {
        private const val TAG = "HeadPoseController"
        private val REQUIRED_PERMISSIONS = arrayOf(Manifest.permission.CAMERA)
        private const val REQUEST_CODE_PERMISSIONS = 10
    }
}
