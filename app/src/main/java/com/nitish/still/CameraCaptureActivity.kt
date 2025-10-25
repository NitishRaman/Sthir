package com.nitish.still

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.os.Bundle
import android.util.Log
import android.view.Gravity
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.math.min

class CameraCaptureActivity : ComponentActivity() {

    companion object {
        private const val TAG = "CameraCaptureActivity"

        private const val MODEL_FILENAME = "eye_model.tflite"
        private const val LABELS_FILENAME = "labels.txt"
        private const val MODEL_INPUT_SIZE = 224
        private const val MODEL_INPUT_CHANNELS = 3
        private const val IMAGE_MEAN = 127.5f
        private const val IMAGE_STD = 127.5f

        const val EXTRA_RESULT_LABEL = "still_result_label"
        const val EXTRA_RESULT_CONFIDENCE = "still_result_confidence"
    }

    private lateinit var previewView: PreviewView
    private var eyeClassifier: EyeClassifier? = null
    private val cameraExecutor = Executors.newSingleThreadExecutor()

    // throttle analysis (ms between inferences)
    private val inferenceIntervalMs = 800L

    // ---- new fields for break behavior ----
    private var breakSeconds: Int = 30
    @Volatile private var closedAccumMs: Long = 0L
    @Volatile private var lastFrameTimeMs: Long = 0L
    private var countdownJob: Job? = null

    // require at least this many ms of closed-eyes during the break for success
    private val minClosedMsForSuccess = 1500L // tune as desired

    // UI overlay
    private var countdownText: TextView? = null
    private var statusText: TextView? = null

    private val requestPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) {
                Log.d(TAG, "Camera permission granted — starting camera")
                try {
                    startCamera()
                } catch (t: Throwable) {
                    Log.e(TAG, "startCamera failed after permission granted: ${t.message}", t)
                    Toast.makeText(this, "Could not start camera.", Toast.LENGTH_SHORT).show()
                    finish()
                }
            } else {
                Log.w(TAG, "Camera permission denied by user")
                Toast.makeText(this, "Camera permission is required.", Toast.LENGTH_LONG).show()
                finish()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // read requested break seconds (caller supplies)
        breakSeconds = intent?.getIntExtra("break_seconds", 30) ?: 30

        previewView = PreviewView(this).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            scaleType = PreviewView.ScaleType.FILL_CENTER
        }

        // we will place previewView as the content view and add a small overlay for countdown/status
        val container = FrameLayout(this).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }
        container.addView(previewView)

        // countdown TextView (large, top-center)
        countdownText = TextView(this).apply {
            text = "${breakSeconds}s"
            textSize = 36f
            setPadding(24, 24, 24, 24)
            setTextColor(android.graphics.Color.WHITE)
            setShadowLayer(4f, 0f, 0f, android.graphics.Color.BLACK)
            val lp = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            )
            lp.gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            layoutParams = lp
        }
        container.addView(countdownText)

        // status TextView (small, below countdown)
        statusText = TextView(this).apply {
            text = "Closed ms: 0"
            textSize = 14f
            setPadding(12, 12, 12, 12)
            setTextColor(android.graphics.Color.WHITE)
            setShadowLayer(4f, 0f, 0f, android.graphics.Color.BLACK)
            val lp = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            )
            lp.gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            lp.topMargin = 90
            layoutParams = lp
        }
        container.addView(statusText)

        setContentView(container)

        // Load model & labels off the main thread
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                eyeClassifier = EyeClassifier(
                    this@CameraCaptureActivity,
                    MODEL_FILENAME,
                    LABELS_FILENAME,
                    MODEL_INPUT_SIZE
                )
                val labelsCount = eyeClassifier?.labelsCount ?: -1
                Log.i(TAG, "EyeClassifier initialized (model OK). labelsCount=$labelsCount")
            } catch (t: Throwable) {
                Log.e(TAG, "Failed to init EyeClassifier: ${t.message}")
            }
        }

        // Ensure camera permission then start camera
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED
        ) {
            startCamera()
        } else {
            requestPermissionLauncher.launch(Manifest.permission.CAMERA)
        }

        // start the countdown promptly so activity stays visible for full breakSeconds
        startBreakCountdown()
    }

    override fun onDestroy() {
        super.onDestroy()
        cameraExecutor.shutdown()
        countdownJob?.cancel()
        try {
            eyeClassifier?.close()
            Log.d(TAG, "EyeClassifier closed")
        } catch (t: Throwable) {
            Log.w(TAG, "Error closing EyeClassifier: ${t.message}")
        }
        eyeClassifier = null
    }

    private fun startBreakCountdown() {
        countdownJob?.cancel()
        closedAccumMs = 0L
        lastFrameTimeMs = System.currentTimeMillis()

        countdownJob = lifecycleScope.launch {
            var remaining = breakSeconds * 1000L
            val tickMs = 250L
            while (isActive && remaining > 0L) {
                val secs = TimeUnit.MILLISECONDS.toSeconds(remaining)
                countdownText?.text = "${secs}s"
                delay(tickMs)
                remaining -= tickMs
            }

            // finished countdown: evaluate success
            if (closedAccumMs >= minClosedMsForSuccess) {
                commitBreakSuccess()
            } else {
                showFailureAndFinish()
            }
        }
    }

    private fun commitBreakSuccess() {
        try {
            Log.d(TAG, "commitBreakSuccess: persisting last_break_completed_at")
            val prefs = getSharedPreferences("still_prefs", MODE_PRIVATE)
            prefs.edit().putLong("last_break_completed_at", System.currentTimeMillis()).apply()
            incrementTodayBreakCount()
        } catch (t: Throwable) {
            Log.w(TAG, "commitBreakSuccess prefs error: ${t.message}")
        }

        try {
            Log.d(TAG, "commitBreakSuccess: broadcasting ACTION_BREAK_COMPLETED")
            val done = Intent("com.nitish.still.ACTION_BREAK_COMPLETED")
            sendBroadcast(done)
        } catch (t: Throwable) {
            Log.w(TAG, "commitBreakSuccess broadcast error: ${t.message}")
        }

        // return result and finish
        val out = Intent().apply {
            putExtra(EXTRA_RESULT_LABEL, "closed_accum")
            putExtra(EXTRA_RESULT_CONFIDENCE, 1.0f)
        }
        setResult(Activity.RESULT_OK, out)
        finish()
    }

    private fun showFailureAndFinish() {
        runOnUiThread {
            Toast.makeText(this, "Could not detect eyes closed enough during break. Try again.", Toast.LENGTH_SHORT).show()
        }
        setResult(Activity.RESULT_CANCELED)
        finish()
    }

    private fun incrementTodayBreakCount() {
        try {
            val prefs = getSharedPreferences("still_prefs", MODE_PRIVATE)
            val sdf = SimpleDateFormat("yyyy-MM-dd", Locale.US)
            val key = "break_count_${sdf.format(Date())}"
            val current = prefs.getInt(key, 0)
            prefs.edit().putInt(key, current + 1).apply()
            Log.d(TAG, "incrementTodayBreakCount: $key -> ${current + 1}")
        } catch (t: Throwable) {
            Log.w(TAG, "incrementTodayBreakCount error: ${t.message}")
        }
    }

    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        Log.d(TAG, "startCamera: cameraProvider obtained, configuring preview + analysis")
        cameraProviderFuture.addListener({
            val cameraProvider = cameraProviderFuture.get()

            // Preview use case
            val preview = Preview.Builder()
                .build()
                .also { it.setSurfaceProvider(previewView.surfaceProvider) }

            // Analyzer use case
            val imageAnalysis = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                .build()

            var lastInferenceTime = 0L

            imageAnalysis.setAnalyzer(cameraExecutor, ImageAnalysis.Analyzer { imageProxy ->
                val now = System.currentTimeMillis()
                try {
                    if (now - lastInferenceTime >= inferenceIntervalMs && eyeClassifier != null) {
                        lastInferenceTime = now
                        val bitmap = toBitmap(imageProxy) ?: return@Analyzer imageProxy.close()
                        lifecycleScope.launch(Dispatchers.Default) {
                            try {
                                val result = eyeClassifier!!.classify(bitmap)
                                val (label, confidence) = result
                                Log.d(TAG, "Inference => $label : $confidence")

                                val closedLabelCandidates =
                                    listOf("Close_eyes", "closed", "eyes_closed", "close_eyes")
                                val isClosedLabel = closedLabelCandidates.any {
                                    label.contains(it, ignoreCase = true)
                                }

                                // update accumulation using elapsed time since lastFrameTimeMs
                                val nowFrame = System.currentTimeMillis()
                                val delta = (nowFrame - lastFrameTimeMs).coerceAtMost(2000L).coerceAtLeast(0L)
                                lastFrameTimeMs = nowFrame

                                if (isClosedLabel && confidence >= 0.85f) {
                                    closedAccumMs += delta
                                    Log.d(TAG, "Classifier reports CLOSED; delta=${delta} closedAccumMs=$closedAccumMs")
                                } else {
                                    // if you want strict continuous requirement, uncomment the next line:
                                    // closedAccumMs = 0L
                                    Log.v(TAG, "Classifier reports OPEN/uncertain; delta=${delta}")
                                }

                                // update small status UI
                                runOnUiThread {
                                    statusText?.text = "Closed ms: ${closedAccumMs}"
                                }

                                // do NOT finish here; countdown will decide when to finish.
                            } catch (t: Throwable) {
                                Log.w(TAG, "Error during classification: ${t.message}")
                            }
                        }
                    }
                } catch (t: Throwable) {
                    Log.w(TAG, "Analyzer error: ${t.message}")
                } finally {
                    imageProxy.close()
                }
            })

            // Select front camera
            val cameraSelector = CameraSelector.DEFAULT_FRONT_CAMERA

            try {
                cameraProvider.unbindAll()
                Log.d(TAG, "startCamera: binding use cases with cameraSelector=$cameraSelector")
                cameraProvider.bindToLifecycle(this, cameraSelector, preview, imageAnalysis)
            } catch (exc: Exception) {
                Log.e(TAG, "Use case binding failed", exc)
                Toast.makeText(this, "Could not start camera.", Toast.LENGTH_SHORT).show()
                finish()
            }
        }, ContextCompat.getMainExecutor(this))
    }

    // --- utils: ImageProxy -> Bitmap ---
    private fun toBitmap(image: ImageProxy): Bitmap? {
        return try {
            val buffer = image.planes[0].buffer
            val bytes = ByteArray(buffer.remaining()).also { buffer.get(it) }
            val bitmap = Bitmap.createBitmap(image.width, image.height, Bitmap.Config.ARGB_8888)
            bitmap.copyPixelsFromBuffer(ByteBuffer.wrap(bytes))
            bitmap
        } catch (t: Throwable) {
            Log.w(TAG, "toBitmap failed: ${t.message}")
            null
        }
    }

    // (Your existing helper functions for convertBitmapToByteBuffer/loadModelFile/loadLabels remain if used by EyeClassifier)

}
