package com.nitish.still

import android.app.Activity
import android.os.Bundle
import android.util.Log
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import java.text.SimpleDateFormat
import java.util.*

class JournalActivity : ComponentActivity() {
    companion object {
        private const val TAG = "JournalActivity"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Simple UI: an EditText and a Save button (replace with Compose UI if preferred)
        val container = FrameLayout(this)
        val edit = EditText(this).apply {
            hint = "Write a short note about your break..."
            setPadding(30, 30, 30, 30)
        }
        val btn = Button(this).apply {
            text = "Save"
            setOnClickListener {
                val text = edit.text.toString()
                saveNote(text)
                setResult(Activity.RESULT_OK)
                finish()
            }
        }

        // layout positioning (quick and dirty)
        val editLp = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.WRAP_CONTENT
        )
        editLp.topMargin = 40
        container.addView(edit, editLp)

        val btnLp = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT,
            FrameLayout.LayoutParams.WRAP_CONTENT
        )
        btnLp.topMargin = 260
        btnLp.leftMargin = 30
        container.addView(btn, btnLp)

        setContentView(container)
    }

    private fun saveNote(text: String) {
        try {
            val prefs = getSharedPreferences("still_prefs", MODE_PRIVATE)
            val sdf = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US)
            val key = "journal_${sdf.format(Date())}"
            prefs.edit().putString(key, text).apply()
            Log.d(TAG, "Saved journal note: $key")
        } catch (t: Throwable) {
            Log.w(TAG, "saveNote error: ${t.message}")
        }
    }
}
