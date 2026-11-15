package com.lexiflow.overlay

import android.app.Activity
import android.content.Intent
import android.os.Build
import android.os.Bundle

class ProcessTextActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val selectedText = intent.getCharSequenceExtra(Intent.EXTRA_PROCESS_TEXT)
            ?.toString()
            ?.trim()
            ?.replace(Regex("[^a-zA-Z-]"), "")

        if (!selectedText.isNullOrEmpty()) {
            val serviceIntent = Intent(this, OverlayDictionaryService::class.java).apply {
                action = OverlayDictionaryService.ACTION_SHOW_WORD_FORCE
                putExtra("WORD", selectedText)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(serviceIntent)
            } else {
                startService(serviceIntent)
            }
        }
        finish()
    }
}
