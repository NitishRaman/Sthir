package com.nitish.still

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import org.tensorflow.lite.Interpreter
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel

/**
 * EyeClassifier
 *
 * Lightweight wrapper around a TFLite interpreter + labels.
 * - Put model in app/src/main/assets/<modelFileName>
 * - Put labels in app/src/main/assets/<labelsFileName> (one label per line)
 *
 * This version adds extensive logging to help debugging model load + inference.
 */
class EyeClassifier(
    private val context: Context,
    private val modelFileName: String = "eye_model.tflite",
    private val labelsFileName: String = "labels.txt",
    private val inputSize: Int = 224 // change to your model input size
) {
    companion object {
        private const val TAG = "EyeClassifier"
    }

    // lazy init of interpreter and labels - logs occur when these helpers run
    private val interpreter: Interpreter by lazy {
        try {
            val buffer = loadModelFile(modelFileName)
            val interp = Interpreter(buffer)
            Log.i(TAG, "Interpreter created for model='$modelFileName'")
            interp
        } catch (t: Throwable) {
            Log.e(TAG, "Failed to create Interpreter for model='$modelFileName': ${t.message}", t)
            throw t
        }
    }

    private val labels: List<String> by lazy {
        try {
            val l = loadLabels(labelsFileName)
            Log.i(TAG, "Labels loaded from '$labelsFileName' count=${l.size}")
            l
        } catch (t: Throwable) {
            Log.e(TAG, "Failed to load labels from '$labelsFileName': ${t.message}", t)
            emptyList()
        }
    }

    // helper: expose label count
    val labelsCount: Int
        get() = labels.size

    // Load model file from assets into MappedByteBuffer
    private fun loadModelFile(name: String): MappedByteBuffer {
        Log.d(TAG, "loadModelFile: loading '$name' from assets")
        try {
            val afd = context.assets.openFd(name)
            FileInputStream(afd.fileDescriptor).use { fis ->
                val channel = fis.channel
                val mapped = channel.map(FileChannel.MapMode.READ_ONLY, afd.startOffset, afd.declaredLength)
                Log.i(TAG, "loadModelFile: mapped '$name' length=${afd.declaredLength}")
                return mapped
            }
        } catch (t: Throwable) {
            Log.e(TAG, "loadModelFile: error loading '$name': ${t.message}", t)
            throw t
        }
    }

    // Load labels from assets; tolerates numeric prefixes
    private fun loadLabels(name: String): List<String> {
        Log.d(TAG, "loadLabels: reading labels file '$name'")
        return try {
            context.assets.open(name).bufferedReader().useLines { lines ->
                lines.map { it.trim().replaceFirst(Regex("^\\d+\\s*"), "") }.filter { it.isNotEmpty() }.toList()
            }.also { list ->
                Log.d(TAG, "loadLabels: loaded ${list.size} labels (preview=${list.take(5)})")
            }
        } catch (t: Throwable) {
            Log.e(TAG, "loadLabels: failed to read '$name': ${t.message}", t)
            emptyList()
        }
    }

    /**
     * Classify a bitmap and return Pair<label,confidence>.
     * Adds detailed logs of input size, inference time and raw scores.
     */
    fun classify(bitmap: Bitmap): Pair<String, Float> {
        val startTs = System.currentTimeMillis()
        Log.v(TAG, "classify START inputSize=$inputSize bitmap=${bitmap.width}x${bitmap.height}")

        try {
            // Resize to expected input shape
            val scaled = Bitmap.createScaledBitmap(bitmap, inputSize, inputSize, true)
            Log.v(TAG, "classify: scaled bitmap to ${scaled.width}x${scaled.height}")

            val buffer = convertBitmapToByteBuffer(scaled)
            val output = Array(1) { FloatArray(maxOf(1, labels.size)) }

            // Run inference synchronized to interpreter (TFLite is not thread-safe for simultaneous runs)
            synchronized(interpreter) {
                interpreter.run(buffer, output)
            }

            val scores = output[0]
            // find best
            val bestIndex = scores.indices.maxByOrNull { scores[it] } ?: 0
            val bestScore = scores.getOrNull(bestIndex) ?: 0f
            val bestLabel = labels.getOrNull(bestIndex) ?: "unknown"

            // Prepare a human-friendly preview of the scores (first 10 values)
            val preview = scores.take(10).joinToString(prefix = "[", postfix = if (scores.size > 10) ", ...]" else "]") { String.format("%.4f", it) }

            val durationMs = System.currentTimeMillis() - startTs
            Log.i(TAG, "classify END label='$bestLabel' confidence=${String.format("%.4f", bestScore)} index=$bestIndex time=${durationMs}ms scoresPreview=$preview")

            return bestLabel to bestScore
        } catch (t: Throwable) {
            Log.e(TAG, "classify ERROR: ${t.message}", t)
            return "error" to 0f
        }
    }

    // Converts ARGB bitmap (0..255) to float32 ByteBuffer normalized to [-1,1]
    private fun convertBitmapToByteBuffer(bitmap: Bitmap): ByteBuffer {
        val bytePerChannel = 4 // float32
        val bb = ByteBuffer.allocateDirect(1 * inputSize * inputSize * 3 * bytePerChannel)
        bb.order(ByteOrder.nativeOrder())
        val intValues = IntArray(inputSize * inputSize)
        // Note: getPixels expects width parameter -> use bitmap.width (should equal inputSize)
        bitmap.getPixels(intValues, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        var pixel = 0
        for (i in 0 until inputSize) {
            for (j in 0 until inputSize) {
                val value = intValues[pixel++]
                val r = ((value shr 16) and 0xFF).toFloat()
                val g = ((value shr 8) and 0xFF).toFloat()
                val b = (value and 0xFF).toFloat()
                // normalization to [-1,1]
                bb.putFloat((r - 127.5f) / 127.5f)
                bb.putFloat((g - 127.5f) / 127.5f)
                bb.putFloat((b - 127.5f) / 127.5f)
            }
        }
        bb.rewind()
        return bb
    }

    fun close() {
        try {
            interpreter.close()
            Log.i(TAG, "Interpreter closed")
        } catch (t: Throwable) {
            Log.w(TAG, "Error closing interpreter: ${t.message}", t)
        }
    }
}
