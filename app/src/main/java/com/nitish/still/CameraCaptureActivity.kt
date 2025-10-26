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
        // playful / sarcastic messages shown when eyes open (friendly roast)
        private val FUNNY_LINES = listOf(
            "Hey sleepyhead — eyes open? That was a bold strategy.",
            "Cheater detected. Blink once for honesty, twice for denial.",
            "I see you. I also see the snack you’re pretending not to eat. 🍪",
            "Nice try. You closing your eyes in spirit doesn't count.",
            "Open eyes? Cute. Try closing them and pretending this never happened.",
            "Welcome back to the land of the living. Please collect your focus badge.",
            "If staring were a sport you'd be on the bench today.",
            "Eyes open again — you’re single-handedly keeping the eyelid industry employed.",
            "Blinking? That’s not a break, that’s a tactical retreat.",
            "Look who’s awake — still not impressed though.",
            "Pretend you were meditating. I won't tell. Mostly.",
            "Ah, the classic ‘I’m relaxing’ face — we’ve all been there.",
            "You opened your eyes. I assume you’ve completed the emotional arc.",
            "I’d award you points for effort, but they’re imaginary and I spent them.",
            "Eyes open — plot twist: the break owes you nothing.",
            "Open? Fine. But no cookies — they’re in the cloud.",
            "Did you just peek? Big sus. Very sus.",
            "I was rooting for you. Also mildly disappointed.",
            "You breaking the rules like a tiny rebel — so tiny.",
            "Open eyes detected. Insert motivational gif here.",
            "You looked. Shame. Also, adorable.",
            "Pro tip: closing eyes increases coolness by at least 12%.",
            "You popped back like a surprise ad. Unskippable.",
            "You opened your eyes like it’s an optional feature. It is not.",
            "A+ for showing up, F for following instructions.",
            "If focus were a pizza, you just ordered delivery late.",
            "Bold move opening your eyes — very dramatic.",
            "If this were an exam, that peek would be a participation mark.",
            "Eyes open. Mood: detective. Motivation: TBD.",
            "Still here? Congrats, you’re a dedicated non-closer.",
            "Cheater! Are your eyelids on vacation?",
            "You opened your eyes. Hope you enjoy the guilt.",
            "Nice try — the break called, it wants a do-over.",
            "Blink and I’ll dock imaginary points.",
            "You look surprised — probably because you failed a tiny test.",
            "Opening eyes already? New personal best in impatience.",
            "This isn’t hide-and-seek; you can’t hide from your eyelids.",
            "You peeked. Shame, shame, and one eye-roll.",
            "Your eyelids need a union. They refuse to close.",
            "Congrats, you found the ‘not playing by the rules’ achievement.",
            "Eyes open again? That’s dedication to procrastination.",
            "If ignoring breaks were a career, you’d be CEO.",
            "You opened your eyes like you paid for premium distractions.",
            "Plot twist: cheating does not increase break effectiveness.",
            "You look awake; success rate: 0. Try closing them next time."
        )
        // minimum ms between showing open-message repeats (coarse debounce)
        private const val OPEN_MSG_DEBOUNCE_MS = 2500L
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
    // UI state for showing open-message once per open-event
    @Volatile private var lastOpenMsgAt: Long = 0L
    @Volatile private var lastOpenWasShown: Boolean = false


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
        Log.d(TAG, "onCreate() - breakSeconds=$breakSeconds intentExtras=${intent?.extras}")

        // read requested break seconds (caller supplies)
        breakSeconds = intent?.getIntExtra("break_seconds", 30) ?: 30

        previewView = PreviewView(this).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            scaleType = PreviewView.ScaleType.FILL_CENTER
        }
        // slightly dim the live preview so overlay messages & countdown feel primary
        previewView.alpha = 0.88f

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

        // status TextView (bigger, lower, with semi-opaque background so messages stand out)
        statusText = TextView(this).apply {
            text = "Closed ms: 0"
            textSize = 18f
            setPadding(20, 14, 20, 14)
            setTextColor(android.graphics.Color.WHITE)
            // semi-opaque rounded background to read on any preview
            setBackgroundColor(android.graphics.Color.parseColor("#66000000"))
            setShadowLayer(4f, 0f, 0f, android.graphics.Color.BLACK)
            val lp = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            )
            lp.gravity = Gravity.CENTER_HORIZONTAL
            lp.topMargin = 220 // push down so it's visible below countdown and preview
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

    override fun onResume() {
        super.onResume()
        Log.d(TAG, "onResume() - CameraCaptureActivity visible")
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

// Launch JournalActivity so the user can add a short note
        try {
            val journalIntent = Intent(this, JournalActivity::class.java).apply {
                putExtra("prefill", "")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            }
            startActivity(journalIntent)
        } catch (t: Throwable) {
            Log.w(TAG, "Failed to start JournalActivity: ${t.message}")
        }

// finish this capture screen (JournalActivity will be foreground)
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

                                    // reset open-message state so next open will show again
                                    lastOpenWasShown = false

                                    // update small status UI to show closed accumulation
                                    runOnUiThread {
                                        statusText?.text = "Closed ms: ${closedAccumMs}"
                                        // ensure countdown is fully visible while eyes closed
                                        statusText?.textSize = 18f
                                        statusText?.alpha = 0.95f
                                        countdownText?.alpha = 1.0f
                                    }
                                } else {
                                    // eyes likely open — show playful message once per open event (debounced)
                                    val nowOpen = System.currentTimeMillis()
                                    if (!lastOpenWasShown || nowOpen - lastOpenMsgAt >= OPEN_MSG_DEBOUNCE_MS) {
                                        lastOpenMsgAt = nowOpen
                                        lastOpenWasShown = true
                                        val msg = FUNNY_LINES.random()
                                        Log.d(TAG, "Showing open-message: $msg")
                                        runOnUiThread {
                                            statusText?.text = msg
                                            // de-emphasize countdown while showing message
                                            countdownText?.alpha = 0.6f
                                        }
                                    } else {
                                        // avoid changing UI too often; keep countdown slightly dimmed
                                        runOnUiThread {
                                            val msg = FUNNY_LINES.random()
                                            Log.d(TAG, "Showing open-message: $msg")
                                            runOnUiThread {
                                                // prominent overlay
                                                statusText?.text = msg
                                                statusText?.textSize = 20f
                                                statusText?.setPadding(28, 16, 28, 16)
                                                statusText?.alpha = 1.0f
                                                countdownText?.alpha = 0.5f

                                                // centered Toast so user can't miss it
                                                val toast = Toast.makeText(this@CameraCaptureActivity, msg, Toast.LENGTH_SHORT)
                                                val tv = toast.view?.findViewById<TextView>(android.R.id.message)
                                                tv?.textSize = 16f
                                                toast.setGravity(Gravity.CENTER, 0, 0)
                                                toast.show()

                                                Log.d(TAG, "Open-message UI updated (toast + overlay shown).")
                                            }

                                        }
                                    }

                                    Log.v(TAG, "Classifier reports OPEN/uncertain; delta=${delta}")
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
