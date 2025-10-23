// File: BreakPromptActivity.kt
package com.nitish.still

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.view.setPadding
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.util.concurrent.TimeUnit

private const val TAG_BREAK_PROMPT = "BREAK_PROMPT"
private const val TAG_CAMERA_CAPTURE = "CAMERA_CAPTURE"

class BreakPromptActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Log.d(TAG_BREAK_PROMPT, "BreakPromptActivity started")

        val breakSeconds = intent?.getIntExtra("break_seconds", 300) ?: 300

        // Build a minimal UI programmatically (keeps layout simple & safe)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            setPadding(32)
        }

        val title = TextView(this).apply {
            text = "Time for a short break"
            textSize = 20f
            setPadding(8)
            gravity = Gravity.CENTER
        }

        val instr = TextView(this).apply {
            text = "Turn camera on, position your face in view and gently shut your eyes. Press START when you're ready.\n\nBreak length: ${TimeUnit.SECONDS.toSeconds(breakSeconds.toLong())}s"
            textSize = 14f
            setPadding(8)
        }

        val startBtn = Button(this).apply {
            text = "START BREAK"
            setOnClickListener {
                Log.d(TAG_BREAK_PROMPT, "Start pressed -> launching CameraCaptureActivity (breakSeconds=$breakSeconds)")
                // Start CameraCaptureActivity for result to get optional return
                val camIntent = Intent(this@BreakPromptActivity, CameraCaptureActivity::class.java).apply {
                    putExtra("break_seconds", breakSeconds)
                }
                cameraLauncher.launch(camIntent)
            }
        }

        val cancelBtn = Button(this).apply {
            text = "CANCEL"
            setOnClickListener {
                Log.d(TAG_BREAK_PROMPT, "User cancelled break prompt")
                setResult(Activity.RESULT_CANCELED)
                finish()
            }
        }

        root.addView(title)
        root.addView(instr)
        root.addView(startBtn)
        root.addView(cancelBtn)
        setContentView(root)
    }

    private val cameraLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            // CameraCaptureActivity already saved prefs & broadcasted ACTION_BREAK_COMPLETED.
            Log.d(TAG_BREAK_PROMPT, "CameraCaptureActivity returned OK; finishing BreakPromptActivity")
            setResult(Activity.RESULT_OK, result.data)
            finish()
        } else {
            Log.d(TAG_BREAK_PROMPT, "CameraCaptureActivity returned non-OK (${result.resultCode})")
            setResult(result.resultCode, result.data)
            finish()
        }
    }
}
