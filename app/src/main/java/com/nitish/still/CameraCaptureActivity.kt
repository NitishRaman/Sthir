package com.nitish.still

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.os.Bundle
import android.util.Log
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import java.util.concurrent.Executors
import com.nitish.still.EyeClassifier
import kotlin.math.min

/**
 * CameraCaptureActivity
 *
 * - Fullscreen camera preview using CameraX PreviewView
 * - ImageAnalysis that runs at a throttled rate and runs TFLite inference
 * - Returns result via setResult / finishes when requested
 *
 * IMPORTANT:
 * - Put your TFLite model into app/src/main/assets/<MODEL_FILENAME>
 * - Put the labels file into app/src/main/assets/<LABELS_FILENAME> (one label per line)
 * - Adjust MODEL_INPUT_SIZE and normalization according to your model
 *
 * Usage:
 * startActivityForResult(Intent(context, CameraCaptureActivity::class.java), REQUEST_CODE)
 * Result Intent will contain extras:
 *   "still_result_label" -> String (e.g. "eyes_closed")
 *   "still_result_confidence" -> Float (0..1)
 */

class CameraCaptureActivity : ComponentActivity() {

    companion object {
        private const val TAG = "CameraCaptureActivity"

        // --- EDIT THESE to match your exported TFLite ---
        private const val MODEL_FILENAME = "eye_model.tflite"      // place in src/main/assets/
        private const val LABELS_FILENAME = "labels.txt"           // place in src/main/assets/
        private const val MODEL_INPUT_SIZE =
            224                   // typical; change to your model's input width/height
        private const val MODEL_INPUT_CHANNELS = 3
        private const val IMAGE_MEAN = 127.5f
        private const val IMAGE_STD = 127.5f
        // ---------------------------------------------------

        const val EXTRA_RESULT_LABEL = "still_result_label"
        const val EXTRA_RESULT_CONFIDENCE = "still_result_confidence"
    }

    private lateinit var previewView: PreviewView
    private var eyeClassifier: EyeClassifier? = null
    private val cameraExecutor = Executors.newSingleThreadExecutor()

    // throttle analysis (ms between inferences)
    private val inferenceIntervalMs = 800L

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

        previewView = PreviewView(this).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            scaleType = PreviewView.ScaleType.FILL_CENTER
        }
        setContentView(previewView)

        // Load model & labels on background thread
        // Initialize EyeClassifier off the main thread
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                eyeClassifier = EyeClassifier(
                    this@CameraCaptureActivity,
                    MODEL_FILENAME,
                    LABELS_FILENAME,
                    MODEL_INPUT_SIZE
                )
                try {
                    val labelsCount = eyeClassifier?.labelsCount ?: -1
                    Log.i(TAG, "EyeClassifier initialized (model OK). labelsCount=$labelsCount")
                } catch (t: Throwable) {
                    Log.w(TAG, "EyeClassifier init: could not read labelsCount: ${t.message}")
                }
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
    }

    override fun onDestroy() {
        super.onDestroy()
        cameraExecutor.shutdown()
        try {
            eyeClassifier?.close()
            Log.d(TAG, "EyeClassifier closed")
        } catch (t: Throwable) {
            Log.w(TAG, "Error closing EyeClassifier: ${t.message}")
        }
        eyeClassifier = null
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
                            // convert to bitmap
                            val bitmap = toBitmap(imageProxy) ?: return@Analyzer imageProxy.close()
                            // ---- REPLACE the existing lifecycleScope.launch(Dispatchers.Default) { ... } block with this ----
                            Log.v(TAG, "Analyzer captured bitmap: ${bitmap.width}x${bitmap.height} at ${System.currentTimeMillis()}")
                            lifecycleScope.launch(Dispatchers.Default) {
                                try {
                                    Log.d(TAG, "Running inference on background thread")
                                    val result = eyeClassifier!!.classify(bitmap)
                                    val (label, confidence) = result
                                    Log.d(TAG, "Inference => $label : $confidence")

                                    // debug: record raw output to help trace model outputs (optional)
                                    Log.v(
                                        TAG,
                                        "Model result pair: label='$label' confidence=$confidence"
                                    )

                                    // threshold decision (match your model's label text exactly if needed)
                                    val closedLabelCandidates =
                                        listOf("Close_eyes", "closed", "eyes_closed", "close_eyes")
                                    val isClosedLabel = closedLabelCandidates.any {
                                        label.contains(
                                            it,
                                            ignoreCase = true
                                        )
                                    }

                                    if (isClosedLabel && confidence >= 0.85f) {
                                        Log.i(
                                            TAG,
                                            "Closed-eyes detected (label=$label, conf=$confidence) -> committing break result"
                                        )

                                        // 1) persist a small marker (last break time)
                                        try {
                                            Log.d(TAG, "Writing last_break_completed_at to prefs")
                                            val prefs =
                                                getSharedPreferences("still_prefs", MODE_PRIVATE)
                                            prefs.edit().putLong(
                                                "last_break_completed_at",
                                                System.currentTimeMillis()
                                            ).apply()
                                            Log.d(TAG, "Wrote last_break_completed_at to prefs")
                                        } catch (t: Throwable) {
                                            Log.w(TAG, "Could not write prefs: ${t.message}")
                                        }

                                        // 2) broadcast so TimerService (or other components) can react
                                        try {
                                            Log.d(TAG, "Broadcasting ACTION_BREAK_COMPLETED")
                                            val done =
                                                Intent("com.nitish.still.ACTION_BREAK_COMPLETED").apply {
                                                    putExtra("result_label", label)
                                                    putExtra("confidence", confidence)
                                                }
                                            sendBroadcast(done)
                                            Log.d(
                                                TAG,
                                                "Broadcast sent: com.nitish.still.ACTION_BREAK_COMPLETED -> $label / $confidence"
                                            )
                                        } catch (t: Throwable) {
                                            Log.w(
                                                TAG,
                                                "Failed to send break broadcast: ${t.message}"
                                            )
                                        }

                                        // 3) return result to caller and finish
                                        try {
                                            val out = Intent().apply {
                                                putExtra(EXTRA_RESULT_LABEL, label)
                                                putExtra(EXTRA_RESULT_CONFIDENCE, confidence)
                                            }
                                            setResult(Activity.RESULT_OK, out)
                                            Log.d(
                                                TAG,
                                                "setResult RESULT_OK prepared, finishing activity"
                                            )
                                            runOnUiThread { finish() }
                                        } catch (t: Throwable) {
                                            Log.w(TAG, "Error finishing with result: ${t.message}")
                                            runOnUiThread { finish() }
                                        }
                                    } else {
                                        Log.d(
                                            TAG,
                                            "Not considered closed: label=$label confidence=$confidence"
                                        )
                                    }
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

                // Select back camera as default
                val cameraSelector =
                    CameraSelector.DEFAULT_FRONT_CAMERA // front camera for user's face

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
            // CameraX OUTPUT_IMAGE_FORMAT_RGBA_8888 gives easier conversion
            return try {
                val buffer = image.planes[0].buffer
                val bytes = ByteArray(buffer.remaining()).also { buffer.get(it) }
                // create bitmap from NV21 or RGBA depends on device; simplest robust way: use ImageProxy.toBitmap extension
                // But since we set RGBA_8888 output, we can use bitmap copy
                val bitmap = Bitmap.createBitmap(image.width, image.height, Bitmap.Config.ARGB_8888)
                bitmap.copyPixelsFromBuffer(ByteBuffer.wrap(bytes))
                // Rotate/mirror if front camera
                // PreviewView handles display; for inference most models don't need mirror/rotation if trained accordingly
                bitmap
            } catch (t: Throwable) {
                Log.w(TAG, "toBitmap failed: ${t.message}")
                null
            }
        }


        private fun convertBitmapToByteBuffer(bitmap: Bitmap): ByteBuffer {
            val inputSize = MODEL_INPUT_SIZE
            val bytePerChannel = 4 // float32
            val byteBuffer =
                ByteBuffer.allocateDirect(1 * inputSize * inputSize * MODEL_INPUT_CHANNELS * bytePerChannel)
            byteBuffer.order(ByteOrder.nativeOrder())
            val intValues = IntArray(inputSize * inputSize)
            bitmap.getPixels(intValues, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
            var pixel = 0
            for (i in 0 until inputSize) {
                for (j in 0 until inputSize) {
                    val value = intValues[pixel++]
                    val r = ((value shr 16) and 0xFF).toFloat()
                    val g = ((value shr 8) and 0xFF).toFloat()
                    val b = (value and 0xFF).toFloat()
                    // normalize to [-1,1] or [0,1] depending on model; this uses [-1,1]
                    byteBuffer.putFloat((r - IMAGE_MEAN) / IMAGE_STD)
                    byteBuffer.putFloat((g - IMAGE_MEAN) / IMAGE_STD)
                    byteBuffer.putFloat((b - IMAGE_MEAN) / IMAGE_STD)
                }
            }
            byteBuffer.rewind()
            return byteBuffer
        }

        // load model file from assets as MappedByteBuffer for Interpreter
        private fun loadModelFile(modelFilename: String): java.nio.MappedByteBuffer {
            val assetFileDescriptor = assets.openFd(modelFilename)
            val inputStream = FileInputStream(assetFileDescriptor.fileDescriptor)
            val fileChannel = inputStream.channel
            val startOffset = assetFileDescriptor.startOffset
            val declaredLength = assetFileDescriptor.declaredLength
            return fileChannel.map(FileChannel.MapMode.READ_ONLY, startOffset, declaredLength)
        }

        // load labels from assets
        private fun loadLabels(labelsFilename: String): List<String> {
            return try {
                assets.open(labelsFilename).bufferedReader().useLines { it.toList() }
            } catch (t: Throwable) {
                Log.w(TAG, "Failed to load labels: ${t.message}")
                emptyList()
            }
        }
    }