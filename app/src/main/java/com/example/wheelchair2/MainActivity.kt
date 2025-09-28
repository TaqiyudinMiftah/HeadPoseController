package com.example.headposecontroller

import android.Manifest
import android.content.pm.PackageManager
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
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.math.atan2
import kotlin.math.sqrt

class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding
    private lateinit var faceLandmarker: FaceLandmarker
    private lateinit var cameraExecutor: ExecutorService

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        cameraExecutor = Executors.newSingleThreadExecutor()
        setupFaceLandmarker()

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
                        val bitmap = imageProxy.toBitmap()
                        if (bitmap != null) {
                            val mpImage = BitmapImageBuilder(bitmap).build()
                            // gunakan timestamp (boleh pake imageProxy.imageInfo.timestamp atau SystemClock.uptimeMillis())
                            val ts = imageProxy.imageInfo.timestamp.takeIf { it != 0L } ?: SystemClock.uptimeMillis()
                            faceLandmarker.detectAsync(mpImage, ts)
                        }
                        imageProxy.close()
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

    // callback result untuk live stream (sesuai signature yang diharapkan)
    private fun returnLivestreamResult(result: FaceLandmarkerResult, input: MPImage) {
        // facialTransformationMatrixes() -> Optional<List<float[]>>
        if (result.facialTransformationMatrixes().isPresent && result.facialTransformationMatrixes().get().isNotEmpty()) {
            val matrix = result.facialTransformationMatrixes().get()[0] // FloatArray (4x4 column-major flattened)
            val sy = sqrt(matrix[0] * matrix[0] + matrix[1] * matrix[1])
            val x = atan2(matrix[6], matrix[10])   // atan2(m21, m22)
            val y = atan2(-matrix[2], sy)          // atan2(-m20, sy)
            val pitch = Math.toDegrees(x.toDouble()).toFloat()
            val yaw = Math.toDegrees(y.toDouble()).toFloat()
            val command = when {
                yaw < -15 -> "KANAN"
                yaw > 15 -> "KIRI"
                pitch < -10 -> "MAJU"
                pitch > 15 -> "MUNDUR"
                else -> "DIAM"
            }
            runOnUiThread { binding.overlay.setResults(yaw, pitch, command) }
        } else {
            runOnUiThread { binding.overlay.setResults(0f, 0f, "DIAM") }
        }
    }

    private fun returnLivestreamError(error: Exception, timestampMs: Long) {
        Log.e(TAG, "MediaPipe Error: ${error.message}")
    }

    private fun allPermissionsGranted() = REQUIRED_PERMISSIONS.all {
        ContextCompat.checkSelfPermission(baseContext, it) == PackageManager.PERMISSION_GRANTED
    }

    override fun onDestroy() {
        super.onDestroy()
        cameraExecutor.shutdown()
        if (::faceLandmarker.isInitialized) faceLandmarker.close()
    }

    companion object {
        private const val TAG = "HeadPoseController"
        private val REQUIRED_PERMISSIONS = arrayOf(Manifest.permission.CAMERA)
        private const val REQUEST_CODE_PERMISSIONS = 10
    }
}
